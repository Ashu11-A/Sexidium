package com.sexidium.core.game.experience;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.SafeSpawn;
import com.sexidium.core.world.WorldNaming;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Owns an {@link ExperienceGame}'s persistence: the resolved world key / experience id, the shared
 * challenge {@link ExperienceState} (load + debounced flush) and per-player position/inventory
 * snapshots (load + capture, with the foreign-world guards that fix the wall/water teleport bug). The
 * game remains the facade; this class is package-private state + logic only, driving the host's
 * scheduler via the supplied {@link ExperienceGame} for the debounce timer.
 */
public final class ExperiencePersistence {
  private final PersistenceHost game;
  private final GameContext gameContext;

  // Shared, persistent gameplay state for this experience's challenges (Double Drops multiplier,
  // Randomizer mappings, …). Loaded from the experiences table at start, flushed back (debounced).
  private ExperienceState state = ExperienceState.empty();
  private String experienceId;
  private String worldName;
  private boolean persistentWorld;
  private boolean saveScheduled;
  // The experience dimension each participant was last seen alive in, so a respawn can put them back
  // there instead of in the Overworld vanilla defaults to. See recordDimension / respawnPosition.
  private final Map<UUID, WorldDimension> lastDimension = new HashMap<>();

  public ExperiencePersistence(PersistenceHost game, GameContext gameContext) {
    this.game = game;
    this.gameContext = gameContext;
  }

  ExperienceState state() {
    return state;
  }

  public boolean persistentWorld() {
    return persistentWorld;
  }

  /**
   * Loads this experience's shared challenge state from its {@code state.yml} (in the world folder via
   * {@link ExperienceStateStore}). One-time migrates legacy DB-column state into the file the first
   * time. Only PERSISTENT experiences (a folder under {@code experiencesSubdir}) persist; transient
   * temp-world experiences keep their state only in memory.
   */
  public void loadState() {
    state = ExperienceState.empty();
    experienceId = null;
    worldName = currentWorldName();
    persistentWorld = false;
    ExperienceStateStore store = gameContext.experienceStore();
    ExperienceManager experiences = gameContext.experiences();
    if (experiences != null && experiences.available() && worldName != null) {
      // One index lookup on the one spelling. This used to be a whole-table scan through a caller
      // supplied canonicaliser, because `worldName` was a canonical key carrying the current generation
      // ("death_resets_ab12_r10") while the registry stored the launcher's owner-scoped spelling
      // ("ashu11a/death_resets_ab12"). Those never compared equal, so the lookup missed for every world
      // on every load — and a null id silently disables updateWorldKey, the crash net and the hardcore
      // death flag further down, which is how the registry came to point at a deleted generation.
      ExperienceManager.Experience experience =
          com.sexidium.core.world.WorldKey.tryParse(worldName).map(experiences::byWorld).orElse(null);
      if (experience != null) {
        experienceId = experience.id();
      } else {
        gameContext.server().logger().warning(
            "No experience registry row resolves for world '" + worldName + "'; its progress will not"
                + " be recorded and a reset would leave the registry pointing at a deleted world.");
      }
    }
    if (store != null && worldName != null && store.persistent(worldName)) {
      persistentWorld = true;
      state = store.loadSharedState(worldName);
      // Migration: an experience that still has legacy DB state but no state.yml yet gets seeded once.
      if (state.values().isEmpty() && experienceId != null && experiences != null) {
        String legacy = experiences.challengeState(experienceId);
        if (legacy != null && !legacy.isBlank()) {
          state = ExperienceState.decode(legacy);
          store.saveSharedState(worldName, state);
        }
      }
    } else if (experienceId != null && experiences != null) {
      // No persistent folder (transient experience): read-only legacy DB state if any.
      state = ExperienceState.decode(experiences.challengeState(experienceId));
    }
    state.onChange(this::scheduleStateSave);
  }

  /**
   * Installs a shared state built elsewhere and writes it out immediately.
   *
   * <p>For the one caller that has to carry state ACROSS a world regeneration: {@code state.yml} lives
   * inside the world folder, so a reset destroys it, and the surviving keys have to be put back by hand.
   * The write is synchronous rather than debounced on purpose — until the file exists in the new folder,
   * a crash (or any other writer) would leave the fresh world looking like it had never had state at
   * all.</p>
   */
  public void adoptState(ExperienceState replacement) {
    state = replacement == null ? ExperienceState.empty() : replacement;
    state.onChange(this::scheduleStateSave);
    flushState();
  }

  /** Debounces persistence: a burst of state writes (e.g. per block break) collapses to one file write. */
  private void scheduleStateSave() {
    if (!persistentWorld || saveScheduled) {
      return;
    }
    saveScheduled = true;
    game.runLater(this::flushState, 40L);
  }

  public void flushState() {
    saveScheduled = false;
    ExperienceStateStore store = gameContext.experienceStore();
    if (persistentWorld && store != null && worldName != null) {
      store.saveSharedState(worldName, state);
    }
  }

  /**
   * Restores a player's saved inventory + health/food/gamemode for this experience. The POSITION is
   * applied by {@code entrySpawn} (the single entry teleport), so this no longer teleports. First
   * entry has no snapshot and keeps the cleared (empty) inventory.
   */
  public void restorePlayerState(PlayerAdapter player) {
    PlayerSnapshot snapshot = loadSnapshot(player);
    if (snapshot != null) {
      snapshot.applyTo(player, gameContext.server().inventorySerializer());
    }
  }

  /** Saved position used as the entry teleport target, retrieved BEFORE the player is teleported. */
  public WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter) {
    return entrySpawn(playerAdapter, worldAdapter, ExperienceWorldType.NORMAL);
  }

  /**
   * As {@link #entrySpawn(PlayerAdapter, WorldAdapter)} but honouring the experience's chosen map type:
   * a player with no saved spot in a Nether/End experience starts in THAT dimension (the experience's
   * own linked sibling), on safe ground, instead of the overworld spawn.
   */
  public WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter, ExperienceWorldType worldType) {
    return resolveEntryPosition(loadSnapshot(playerAdapter),
        worldAdapter != null ? worldAdapter : game.world(), worldType);
  }

  /**
   * Resolves where to drop a (re)entering player, with the hard guard that fixes the wall/water teleport
   * bug. A saved snapshot position is reused ONLY when it was captured in THIS experience world (compared
   * on the normalized short name, so a runtime slash-path prefix never causes a false mismatch). A
   * snapshot whose world does not match — e.g. lobby coordinates captured during a transition — is
   * rejected here instead of being force-applied, which would teleport the player to foreign coordinates
   * inside the experience world (inside a wall, under water). The fallback is the world's SAFE surface
   * spawn, never the raw generated spawn that may itself be buried or submerged.
   */
  public WorldPosition resolveEntryPosition(PlayerSnapshot snapshot, WorldAdapter worldAdapter) {
    return resolveEntryPosition(snapshot, worldAdapter, ExperienceWorldType.NORMAL);
  }

  /**
   * As {@link #resolveEntryPosition(PlayerSnapshot, WorldAdapter)} but sending a first-time player into
   * the experience's chosen STARTING DIMENSION. A returning player always wins: their saved spot is kept
   * (they may have walked out of the starting dimension long ago).
   */
  public WorldPosition resolveEntryPosition(PlayerSnapshot snapshot, WorldAdapter worldAdapter,
      ExperienceWorldType worldType) {
    if (worldAdapter == null) {
      return snapshot == null ? null : snapshot.savedPosition();
    }
    WorldPosition saved = snapshot == null ? null : snapshot.savedPosition();
    // Reuse the saved spot when it belongs to THIS experience — its overworld OR one of its linked
    // Nether/End siblings — so a player who left from the Nether returns to the Nether, not the overworld
    // spawn. Returned verbatim (the platform resolves the world by name); only a genuinely foreign
    // position (e.g. lobby coordinates captured mid-transition) falls back to the world's SAFE spawn.
    if (saved != null && belongsToExperience(saved.worldName(), worldAdapter.name())) {
      // A returning player who can BREATHE where they left off is handed back their own coordinates,
      // untouched. Routing every entry through safePositionNear looked like a free safety net and was
      // not one: that search judges a column by scanning DOWN from the heightmap, so it resolves every
      // column to its OUTDOOR SURFACE. Someone who logged out standing safely on a cave floor at y=24
      // came back at y=121 on the hillside above it, and someone standing inside their own roofed base
      // at y=64 came back at y=70 — on the roof. Nothing was wrong with where either of them was.
      if (!SafeSpawn.buried(worldAdapter, saved)) {
        return saved;
      }
      // Only now, genuinely inside the rock, is a move owed at all — and it is owed, because belonging
      // to this world is NOT the same as being safe in it: returning a buried spot verbatim is what made
      // a bad position permanent. A player who ended up buried once had that position autosaved, and
      // every later entry put them straight back into the rock. In Death Resets that is an endless
      // death-and-regenerate loop, which is how one run reached Reset 10.
      //
      // The escape is the LOCAL one, exactly as `ExperienceGame.settleOne` rescues a suffocating
      // player: step out of the wall into the next pocket rather than up to the sky, so a buried cave
      // dweller comes back in their cave.
      WorldPosition nearby = SafeSpawn.nearestFree(worldAdapter, saved);
      if (nearby != null) {
        return nearby;
      }
      // Encased with nothing free within reach: the heightmap search is the only thing left to offer,
      // and the surface beats suffocating.
      return worldAdapter.safePositionNear(saved);
    }
    return startPosition(worldAdapter, worldType);
  }

  /**
   * Where a first-time player lands: the safe surface spawn of the experience's starting dimension. For
   * a Nether/End experience that is the matching linked sibling; if the platform cannot hand one back
   * (an unmanaged world, or a backend without linked dimensions) the overworld spawn is used, so a
   * missing sibling degrades to a normal start instead of a failed teleport.
   */
  private WorldPosition startPosition(WorldAdapter worldAdapter, ExperienceWorldType worldType) {
    return dimensionSpawn(worldAdapter, (worldType == null ? ExperienceWorldType.NORMAL : worldType).dimension());
  }

  /**
   * The safe spawn of one of this experience's dimensions, falling back to its Overworld when the
   * platform has no such sibling — so a missing dimension degrades to a normal spawn instead of a
   * failed teleport.
   */
  /** The registry id of the experience this match is running, or null when it is not a stored one. */
  /**
   * The stable experience world KEY this persistence is currently bound to ({@code <nick>/<map>_<id>}),
   * or null when nothing is resolved. This — not the runtime world name — is what
   * {@link ExperienceStateStore} is keyed by.
   */
  public String stateWorldKey() {
    return worldName;
  }

  public String experienceId() {
    return experienceId;
  }

  public WorldPosition dimensionSpawn(WorldAdapter worldAdapter, WorldDimension dimension) {
    if (worldAdapter == null) {
      return null;
    }
    if (dimension != null && dimension != WorldDimension.OVERWORLD) {
      WorldAdapter target = worldAdapter.dimension(dimension);
      if (target != null) {
        WorldPosition spawn = target.safeSpawnPosition();
        if (spawn != null) {
          return spawn;
        }
      }
    }
    return worldAdapter.safeSpawnPosition();
  }

  // ----- death / respawn dimension ---------------------------------------------------------------

  /**
   * Remembers which of the experience's dimensions a participant is currently in. Sampled while they are
   * alive (on damage and on a short timer) because by the time the respawn fires, vanilla has already
   * moved them — usually to the Overworld — so the dimension they actually died in is no longer readable
   * from the player.
   */
  public void recordDimension(PlayerAdapter player, WorldAdapter experienceWorld) {
    if (player == null || experienceWorld == null) {
      return;
    }
    WorldAdapter current = player.world();
    if (current != null && belongsToExperience(current.name(), experienceWorld.name())) {
      lastDimension.put(player.uniqueId(), current.currentDimension());
    }
  }

  /** Forgets a player's tracked dimension (they left the experience). */
  public void forgetDimension(UUID playerId) {
    if (playerId != null) {
      lastDimension.remove(playerId);
    }
  }

  /**
   * Where a dead participant comes back. An experience is open-ended and never ejects, so a death keeps
   * the player in the dimension it happened in: die in the Nether, respawn in the Nether. When that
   * dimension is unknown (the player was never sampled) the experience's chosen STARTING dimension is
   * used, so an End experience never dumps its players into an Overworld they did not choose.
   *
   * <p>{@code vanillaPosition} is where the platform was going to put them. When that is already inside
   * the right dimension of THIS experience it is kept and {@code null} is returned — that is a bed or a
   * respawn anchor doing its job, and overriding it would be wrong. Pass {@code null} when there is no
   * platform placement to respect (a challenge soft-reset), and the dimension spawn is always returned.</p>
   */
  public WorldPosition respawnPosition(PlayerAdapter player, WorldAdapter experienceWorld,
      ExperienceWorldType worldType, WorldPosition vanillaPosition) {
    if (experienceWorld == null || player == null) {
      return null;
    }
    WorldDimension wanted = lastDimension.get(player.uniqueId());
    if (wanted == null) {
      wanted = (worldType == null ? ExperienceWorldType.NORMAL : worldType).dimension();
    }
    if (vanillaPosition != null && isDimensionOf(experienceWorld, wanted, vanillaPosition.worldName())) {
      return null; // the platform already lands them in the right dimension — respect the bed/anchor
    }
    return dimensionSpawn(experienceWorld, wanted);
  }

  /** Whether {@code worldName} IS this experience's world for {@code dimension} (compared by name). */
  private boolean isDimensionOf(WorldAdapter experienceWorld, WorldDimension dimension, String worldName) {
    if (worldName == null || !belongsToExperience(worldName, experienceWorld.name())) {
      return false;
    }
    WorldAdapter target = experienceWorld.dimension(dimension);
    return target != null && WorldNaming.sameWorld(target.name(), worldName);
  }

  /** Loads a player's snapshot from the file store (migrating a legacy DB row once if needed). */
  public PlayerSnapshot loadSnapshot(PlayerAdapter player) {
    if (player == null) {
      return null;
    }
    String forWorld = worldName != null ? worldName : currentWorldName();
    ExperienceStateStore store = gameContext.experienceStore();
    if (store != null && forWorld != null && store.persistent(forWorld)) {
      PlayerSnapshot snapshot = store.loadPlayerSnapshot(forWorld, player.uniqueId(), player.name());
      if (snapshot != null) {
        return snapshot;
      }
      PlayerSnapshot legacy = legacySnapshot(player, forWorld);
      if (legacy != null) {
        store.savePlayerSnapshot(forWorld, legacy);
      }
      return legacy;
    }
    return legacySnapshot(player, forWorld);
  }

  private PlayerSnapshot legacySnapshot(PlayerAdapter player, String forWorld) {
    ExperienceManager experiences = gameContext.experiences();
    String id = experienceId;
    if (id == null && experiences != null && experiences.available() && forWorld != null) {
      // Same identity rule as loadState: the fallback that recovers a pre-state.yml snapshot is
      // useless if it cannot find the row either.
      ExperienceManager.Experience experience =
          com.sexidium.core.world.WorldKey.tryParse(forWorld).map(experiences::byWorld).orElse(null);
      if (experience != null) {
        id = experience.id();
      }
    }
    return id != null && experiences != null
        ? experiences.loadPlayerState(id, player.uniqueId(), player.name())
        : null;
  }

  /**
   * Captures and persists a player's current position + inventory to the experience's player file.
   * Returns true when stored, so the caller can clear the live inventory knowing the items are saved.
   */
  public boolean capturePlayerState(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return false;
    }
    String forWorld = worldName != null ? worldName : currentWorldName();
    // Record WHERE this player is, durably, before writing what they have. The snapshot below lives
    // inside the world folder, which is the right place for it and useless on its own: a player who
    // reconnects to a node with no local session has nothing telling anyone which folder to open, so
    // they land in that node's default overworld. This row is that missing sentence.
    ExperienceManager registry = gameContext.experiences();
    if (registry != null && registry.available() && experienceId != null && forWorld != null) {
      registry.rememberPlayerWorld(experienceId, player.uniqueId(),
          com.sexidium.core.world.WorldKey.tryParse(forWorld).orElse(null), System.currentTimeMillis());
    }
    ExperienceStateStore store = gameContext.experienceStore();
    if (store == null || forWorld == null || !store.persistent(forWorld)) {
      return false;
    }
    PlayerSnapshot snapshot = new PlayerSnapshot(player.uniqueId(), player.name(), null);
    snapshot.captureLive(player, gameContext.server().inventorySerializer());
    // Guard against persisting a foreign-world position: a participant caught briefly in another world
    // (the lobby, mid rejoin/release) must NOT have those coordinates saved, or the next entry would force
    // them into this experience world — dropping the player inside a wall or under water. Keep the last
    // known in-experience position (refusing the save entirely when there is none yet) while still letting
    // a genuine in-world capture overwrite it. "In-experience" is the overworld OR its linked Nether/End
    // siblings — compared on the match world's live name, NOT the stripped storage key, so an ordinary
    // capture in the experience's own overworld is no longer wrongly rejected (which reset position and
    // wiped inventory on every exit).
    String overworldName = game.world() == null ? null : game.world().name();
    if (!belongsToExperience(snapshot.worldName, overworldName)) {
      PlayerSnapshot previous = store.loadPlayerSnapshot(forWorld, player.uniqueId(), player.name());
      WorldPosition keep = previous == null ? null : previous.savedPosition();
      if (keep == null) {
        return false;
      }
      snapshot.worldName = keep.worldName();
      snapshot.coordinateX = keep.coordinateX();
      snapshot.coordinateY = keep.coordinateY();
      snapshot.coordinateZ = keep.coordinateZ();
      snapshot.yaw = keep.yaw();
      snapshot.pitch = keep.pitch();
    }
    // The write ANSWERS now, and this returns its answer rather than an unconditional true. It used to
    // promise "stored" whatever the disk did -- a full volume, a read-only mount, a permission fault --
    // and the callers cash that promise in by clearing the live inventory (ExperienceGame's stop and
    // leave paths, ChaosGame's leave path). A snapshot that never landed plus a cleared inventory is
    // the player's items destroyed by the code that exists to save them.
    return store.savePlayerSnapshot(forWorld, snapshot);
  }

  /**
   * The stable experience world KEY (e.g. {@code Ashu11a/Diamond_Hunt_ab12cd34}). The Paper runtime name
   * is the slash path {@code worlds/experience/Ashu11a/Diamond_Hunt_ab12cd34}; normalize to the path
   * RELATIVE to the experiences subdir so it matches both the registry ({@code experiences.byWorld}, which
   * stores the key) and the on-disk state folder ({@link ExperienceStateStore}). Falls back to the last
   * segment for non-experience (transient temp) worlds that carry no experiences-subdir marker.
   */
  private String currentWorldName() {
    WorldAdapter world = game.world();
    if (world == null) {
      return null;
    }
    return experienceKey(world.name(), gameContext.server().worlds().experiencesSubdirName());
  }

  /**
   * The path after the experiences-subdir segment in a runtime world name. For
   * {@code worlds/experience/<nick>/<map>_<id>} with subdir {@code experience} this yields
   * {@code <nick>/<map>_<id>}; a name with no subdir marker degrades to its last segment.
   */
  static String experienceKey(String name, String subdirName) {
    if (name == null) {
      return null;
    }
    String normalized = name.replace('\\', '/');
    // Strip a leading namespace ("sexidium:..." style dimension ids); the namespace never contains
    // a slash, so a colon that precedes every slash is a namespace separator, not part of the key.
    int colon = normalized.indexOf(':');
    if (colon >= 0 && normalized.lastIndexOf('/', colon) < 0) {
      normalized = normalized.substring(colon + 1);
    }
    if (subdirName != null && !subdirName.isBlank()) {
      String marker = "/" + subdirName + "/";
      int index = normalized.indexOf(marker);
      if (index >= 0) {
        return normalized.substring(index + marker.length());
      }
      if (normalized.startsWith(subdirName + "/")) {
        return normalized.substring(subdirName.length() + 1);
      }
    }
    return shortName(normalized);
  }

  /** Last path segment of a (possibly slash-pathed) world name, so a runtime path and a stored short name compare equal. */
  private static String shortName(String name) {
    if (name == null) {
      return null;
    }
    int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    return separator >= 0 ? name.substring(separator + 1) : name;
  }

  /**
   * True when {@code liveWorldName} (a participant's current world) is part of THIS experience: its
   * overworld OR one of its linked Nether/End siblings. Names are compared flattened, so the platform's
   * runtime label {@code experiences_<nick>_<map>_<id>[_nether|_end]} matches the match world's canonical
   * name {@code experiences/<nick>/<map>_<id>}. Used both to keep a genuinely foreign world (the lobby,
   * captured mid-transition) out of a player's saved position and to accept the experience's own
   * overworld and dimensions, where the modes are equally played.
   */
  private static boolean belongsToExperience(String liveWorldName, String overworldName) {
    return WorldNaming.belongsToExperience(liveWorldName, overworldName);
  }
}
