package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.BlockPlaceGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.BlockBreakService;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.PlayerRadius;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Omni Chunk — everything you do to a block happens in the same slot of every chunk. Lay a plank and it
 * appears in that exact spot of every chunk you can see; mine it out and it vanishes from all of them.
 * Build an iron T, cap it with a carved pumpkin, and an iron golem stands up in every one of them.
 *
 * <h2>It is a commit log, not a copy</h2>
 * The history lives in a {@link ChunkLedger}: every place, break and item-use is a <b>commit</b> against a
 * chunk-local slot, kept in order and squashed when a slot is overwritten. That ordering is not
 * bookkeeping — it <em>is</em> the mechanic. A golem exists because the iron was already there when the
 * pumpkin landed; TNT explodes because it was lit after it was placed. So every commit is applied
 * <b>naturally</b> ({@link WorldAdapter#setBlockNatural}), with real block updates and physics, and a
 * chunk that has never been visited is <b>replayed from the log in chronological order</b> the moment a
 * player can see it — the same events, in the same sequence, unfolding again.
 *
 * <h2>Scope follows the player's eyes</h2>
 * Replication and replay cover the acting player's <b>render distance</b>, resolved by the shared
 * {@link PlayerRadius} rules — a circle rather than a square, extended slightly past the visible edge, with
 * boundary chunks included only when most of them actually falls inside. Everything the player can see
 * stays consistent, and nothing is computed for chunks nobody is looking at. Work is drained against a
 * {@code blocks-per-tick} budget nearest-chunk-first, so the change ripples outwards from you instead of
 * landing as one spike; only already-loaded chunks are touched ({@link WorldAdapter#isChunkLoaded}), and
 * the arrival replay covers the rest.
 *
 * <h2>Each dimension is its own world</h2>
 * An experience is three worlds, and every one of them keeps its <b>own</b> history: a plank laid in the
 * Overworld repeats through the Overworld's chunks and leaves the Nether and the End exactly as they
 * were. That is not a detail — a shared history would mean mining out a Nether corridor also carved the
 * same holes through everything you had built at home, and a fresh dimension would arrive pre-filled with
 * edits that were never made in it. Each {@link DimensionLog} owns a ledger, its chunk versions and its
 * queued set, so independence is structural rather than a convention to remember.
 *
 * <p>The histories are persisted with the experience's own world folder, one entry per dimension, so they
 * travel with the world and need no database. See {@code experiences.modes.omnichunk}.</p>
 */
public final class OmniChunkChallenge extends Challenge {
  private static final String KEY_LOG = "log";
  private static final String KEY_APPLIED = "applied";
  private static final String KEY_VERSIONS = "chunkversions";
  private static final String KEY_HASH = "loghash";
  /** Index of which worlds have a history, so a restart knows what to restore. */
  private static final String KEY_WORLDS = "worlds";
  /** Separates the world from the coordinates in the per-player "last chunk seen" key. */
  private static final char FIELD = '|';

  /** Items whose use on a block is worth recording and re-running in the copies. */
  private static final List<String> DEFAULT_USES = List.of("flint_and_steel", "fire_charge", "bone_meal");

  private int blocksPerTick;
  private int minChunkRadius;
  private int maxChunkRadius;
  private double overscan;
  private boolean copyBreaks;
  private boolean naturalPlacement;
  private int maxChanges;
  private Set<String> recordedUses;

  /**
   * One history per DIMENSION, keyed by world name. An experience is three worlds, and each keeps its own
   * changes: laying a plank in the Overworld repeats through the Overworld's chunks and leaves the Nether
   * and the End untouched. Sharing one history across them would have meant mining in the Nether carving
   * holes through everything you had built at home.
   */
  private final Map<String, DimensionLog> dimensions = new LinkedHashMap<>();
  // Work waiting for its per-tick budget, across every dimension — one shared budget so a busy Overworld
  // and a busy Nether cannot each spend a full tick's worth of edits.
  private final Deque<Job> queue = new ArrayDeque<>();
  // The chunk each player was last seen in, so arrival is detected without scanning.
  private final Map<UUID, String> lastChunk = new HashMap<>();
  private long commitsApplied;

  /**
   * One chunk's pending work: the commits it is missing, and the version it will be on once they have all
   * been applied. The version is recorded ONLY on completion — a chunk whose work never ran must stay
   * behind so it is retried, which is precisely what went wrong when it was recorded at queue time.
   */
  private static final class Job {
    final String worldName;
    final int chunkX;
    final int chunkZ;
    final List<ChunkLedger.Commit> commits;
    final long targetSeq;
    int cursor;

    Job(String worldName, int chunkX, int chunkZ, List<ChunkLedger.Commit> commits, long targetSeq) {
      this.worldName = worldName;
      this.chunkX = chunkX;
      this.chunkZ = chunkZ;
      this.commits = commits;
      this.targetSeq = targetSeq;
    }

    boolean done() {
      return cursor >= commits.size();
    }
  }

  public OmniChunkChallenge() {
    super("omnichunk", "Omni Chunk");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // "max-changes" is the current name; the original "max-commits" is still read so an existing config
    // is never silently ignored. It is a per-DIMENSION budget: each keeps its own history.
    maxChanges = Math.max(1,
        cfg().getInt(configPath("max-changes"), cfg().getInt(configPath("max-commits"), 4096)));
    blocksPerTick = Math.max(1, cfg().getInt(configPath("blocks-per-tick"), 512));
    minChunkRadius = Math.max(0, cfg().getInt(configPath("min-chunk-radius"), 4));
    maxChunkRadius = Math.max(minChunkRadius, cfg().getInt(configPath("max-chunk-radius"), 32));
    overscan = Math.max(0.0, cfg().getDouble(configPath("overscan"), PlayerRadius.DEFAULT_OVERSCAN));
    copyBreaks = cfg().getBoolean(configPath("copy-breaks"), true);
    naturalPlacement = cfg().getBoolean(configPath("natural-placement"), true);
    recordedUses = lowercased(configPath("recorded-uses"), DEFAULT_USES);
    // The histories are shared, persistent state: rejoining players find each dimension as the group left
    // it, and a chunk first seen after a restart still replays that dimension's full history correctly.
    restoreDimensions();
    commitsApplied = stateLong(KEY_APPLIED, 0L);
    runTimer(this::drain, 1L, 1L);
    // Replication has to be CONTINUOUS, not a one-off around wherever a commit happened: chunks stream in
    // as players travel, and a chunk that was not loaded when its turn came must get another one. This
    // sweep re-checks every participant's surroundings on a slow beat; chunks already at the head cost a
    // map lookup each, so it stays cheap.
    long sweepTicks = Math.max(5L, cfg().getLong(configPath("sync-period-ticks"), 20L));
    runTimer(this::sweepPlayers, sweepTicks, sweepTicks);
  }

  private Set<String> lowercased(String path, List<String> fallback) {
    List<String> configured = cfg().getStringList(path);
    Set<String> values = new LinkedHashSet<>();
    for (String value : configured == null || configured.isEmpty() ? fallback : configured) {
      if (value != null && !value.isBlank()) {
        values.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    return values;
  }

  // ----- recording commits -----------------------------------------------------------------------

  @Override
  public void onBlockPlace(BlockPlaceGameEvent event) {
    if (!event.cancelled() && event.blockKey() != null) {
      commit(event.playerAdapter(), event.blockPosition(), ChunkLedger.Kind.PLACE, event.blockKey());
    }
  }

  @Override
  public void onBlockBreak(BlockBreakGameEvent event) {
    if (!event.cancelled() && copyBreaks) {
      // Symmetric with placing, and it is where the danger lives: a careless dig chews through terrain
      // everywhere at once. Breaking also squashes that slot's earlier commits — the history that led to
      // a block nobody can see any more is dead.
      commit(event.playerAdapter(), event.blockPosition(), ChunkLedger.Kind.BREAK, null);
    }
  }

  @Override
  public void onPlayerInteract(PlayerInteractGameEvent event) {
    if (event.cancelled() || event.actionType() != PlayerInteractGameEvent.ActionType.RIGHT_CLICK) {
      return;
    }
    // Using an item ON a block is an event layered on the block, not a replacement for it: lighting TNT
    // does not erase the fact that TNT was placed there, so the copies place it AND then light it.
    if (ChunkLedger.recordableUse(event.itemKey(), recordedUses)) {
      commit(event.playerAdapter(), event.blockPosition(), ChunkLedger.Kind.USE, event.itemKey());
    }
  }

  /**
   * Appends the change to the history OF THE DIMENSION IT HAPPENED IN, then brings every chunk the player
   * can see — in that same dimension — up to its new head. Nothing here can reach another dimension.
   */
  private void commit(PlayerAdapter actor, BlockPosition at, ChunkLedger.Kind kind, ItemKey payload) {
    if (at == null || at.worldName() == null) {
      return;
    }
    DimensionLog dimension = dimensionOf(at.worldName());
    long previousHead = dimension.ledger().head().seq();
    dimension.ledger().append(at.blockX(), at.blockY(), at.blockZ(), kind, payload);
    int actorChunkX = ChunkStamp.chunkOf(at.blockX());
    int actorChunkZ = ChunkStamp.chunkOf(at.blockZ());
    // The chunk the player edited already holds the real block, so it is at the new head by definition —
    // but only if it was current beforehand. A chunk that was still catching up must not skip its history.
    long actorKey = DimensionLog.chunkKey(actorChunkX, actorChunkZ);
    if (dimension.versionOf(actorKey) == previousHead) {
      dimension.setVersion(actorKey, dimension.ledger().head().seq());
    }
    syncArea(at.worldName(), actorChunkX, actorChunkZ, areaFor(actor));
    saveLog();
  }

  /** This world's own history, created the first time anything happens in it. */
  private DimensionLog dimensionOf(String worldName) {
    return dimensions.computeIfAbsent(worldName, name -> new DimensionLog(name, maxChanges));
  }

  /** How far a player's actions reach — resolved by the shared {@link PlayerRadius} rules. */
  private PlayerRadius.Area areaFor(PlayerAdapter actor) {
    return PlayerRadius.around(actor, minChunkRadius, maxChunkRadius, overscan);
  }

  // ----- replaying history into newly seen chunks -------------------------------------------------

  @Override
  public void onPlayerMove(PlayerMoveGameEvent event) {
    if (event.toPosition() == null || event.playerAdapter() == null) {
      return;
    }
    WorldPosition to = event.toPosition();
    int chunkX = ChunkStamp.chunkOf((int) Math.floor(to.coordinateX()));
    int chunkZ = ChunkStamp.chunkOf((int) Math.floor(to.coordinateZ()));
    String key = to.worldName() + FIELD + chunkX + FIELD + chunkZ;
    PlayerAdapter player = event.playerAdapter();
    if (key.equals(lastChunk.get(player.uniqueId()))) {
      return; // same chunk as the last move, and this event fires constantly
    }
    lastChunk.put(player.uniqueId(), key);
    syncArea(to.worldName(), chunkX, chunkZ, areaFor(player));
  }

  /**
   * Brings every chunk in {@code area} up to the head of ITS OWN dimension's history. This is the ONE path
   * used for both directions of the engine — a new change spreading out, and a player arriving somewhere —
   * because they are the same question: is this chunk on the latest version, and if not, what is it
   * missing?
   */
  private void syncArea(String worldName, int chunkX, int chunkZ, PlayerRadius.Area area) {
    if (worldName == null) {
      return;
    }
    DimensionLog dimension = dimensions.get(worldName);
    if (dimension == null || dimension.ledger().size() == 0) {
      return; // nothing has ever happened in this dimension, so there is nothing to copy
    }
    // Resolved ONCE for the whole area, not per chunk. The head cannot move while this loop runs — it
    // only changes on an append, and appends come from the game thread we are already on — so hoisting
    // it is free of meaning and expensive to leave inside: the area is every chunk in the player's
    // radius, and this call used to walk the whole commit log each time round.
    ChunkLedger.Head head = dimension.ledger().head();
    for (int[] offset : area.chunkOffsets()) {
      syncChunk(dimension, head, chunkX + offset[0], chunkZ + offset[1]);
    }
  }

  /**
   * Fast-forwards one chunk against its own dimension's history. A chunk already at the head is skipped
   * outright; one that is behind gets exactly the changes it has not seen, in order. A chunk so far behind
   * that its missing changes have already been trimmed off the log replays everything that survives
   * instead — a re-clone rather than a pull.
   */
  private void syncChunk(DimensionLog dimension, ChunkLedger.Head head, int chunkX, int chunkZ) {
    ChunkLedger ledger = dimension.ledger();
    long key = DimensionLog.chunkKey(chunkX, chunkZ);
    long have = dimension.versionOf(key);
    if (have >= head.seq() || !dimension.enqueue(key)) {
      return; // already on the latest version, or already waiting for its turn
    }
    List<ChunkLedger.Commit> missing = ledger.needsFullReplay(have) ? ledger.commits() : ledger.since(have);
    if (missing.isEmpty()) {
      dimension.dequeue(key);
      dimension.setVersion(key, head.seq());
      return;
    }
    // The version is NOT recorded here. A chunk is only up to date once its changes have actually been
    // applied to it — recording it now would write off a chunk that turns out not to be loaded yet, and
    // that chunk would never be looked at again. That is what made replication stop at a distance.
    queue.add(new Job(dimension.worldName(), chunkX, chunkZ, missing, head.seq()));
  }

  /** Re-checks every online participant's surroundings, so replication follows them across the world. */
  private void sweepPlayers() {
    if (dimensions.isEmpty()) {
      return;
    }
    for (PlayerAdapter player : online()) {
      WorldPosition at = player.position();
      if (at == null || at.worldName() == null) {
        continue;
      }
      syncArea(at.worldName(), ChunkStamp.chunkOf((int) Math.floor(at.coordinateX())),
          ChunkStamp.chunkOf((int) Math.floor(at.coordinateZ())), areaFor(player));
    }
  }

  // ----- applying ---------------------------------------------------------------------------------

  /**
   * Spends this tick's budget. Jobs drain oldest-first and each walks its chunk's missing commits in log
   * order, so history always replays in the sequence it happened. One job is one chunk, and its cursor
   * can never run past its own snapshot of the commits — the list is a copy taken when the job was
   * queued, so a later trim of the log cannot invalidate it.
   */
  private void drain() {
    WorldAdapter experienceWorld = world();
    if (experienceWorld == null || queue.isEmpty()) {
      return;
    }
    int budget = blocksPerTick;
    // Bound how many not-yet-loaded chunks we skip past in one tick, so a long queue of them cannot spin.
    int inspections = blocksPerTick;
    while (budget > 0 && inspections > 0 && !queue.isEmpty()) {
      Job job = queue.peek();
      inspections--;
      // Ask the job's OWN world, not the experience's overworld. Chunk residency is a per-world fact, so
      // testing a Nether chunk against the Overworld answered about a different chunk entirely — jobs in a
      // sibling dimension were dropped as "not loaded" for ever, and the mode looked dead the moment a
      // player stepped through a portal.
      WorldAdapter world = experienceWorld.inWorld(job.worldName);
      if (!world.isChunkLoaded(job.chunkX, job.chunkZ)) {
        // Not resident yet. Drop the job WITHOUT recording a version, so the sweep re-queues this chunk
        // once it streams in — the difference between "replicated everywhere" and "stopped at the edge".
        finish(job, false);
        continue;
      }
      try {
        while (budget > 0 && !job.done()) {
          if (apply(world, job.commits.get(job.cursor++), job.worldName, job.chunkX, job.chunkZ)) {
            commitsApplied++;
            budget--;
          }
        }
      } catch (RuntimeException exception) {
        // One bad job (an unloaded region mid-apply, a block the platform rejects) must never be retried
        // for ever and spam a warning every tick. Drop it WITHOUT recording a version, so the chunk is
        // simply re-queued by the next sweep.
        finish(job, false);
        continue;
      }
      if (job.done()) {
        finish(job, true);
      }
    }
  }

  /** Removes a finished job; {@code applied} records the chunk's new version, otherwise it stays behind. */
  private void finish(Job job, boolean applied) {
    queue.poll();
    DimensionLog dimension = dimensions.get(job.worldName);
    if (dimension == null) {
      return;
    }
    long key = DimensionLog.chunkKey(job.chunkX, job.chunkZ);
    dimension.dequeue(key);
    if (applied) {
      dimension.setVersion(key, job.targetSeq);
    }
  }

  /** Applies one commit to one chunk, honouring the protected list and the shared block-change vetoes. */
  private boolean apply(WorldAdapter world, ChunkLedger.Commit commit, String worldName, int chunkX, int chunkZ) {
    BlockPosition target = commit.at(worldName, chunkX, chunkZ);
    // Dimensions are not the same height. A commit recorded at y=200 in the Overworld has nowhere to land
    // in the Nether (0…128), and asking the platform to edit a block outside a world's range is an error,
    // not a no-op — so a copy that does not fit is simply skipped rather than attempted.
    if (target.blockY() < world.minBuildHeight() || target.blockY() >= world.maxBuildHeight()) {
      return false;
    }
    // ONE question, asked of the shared funnel: may this position be changed at all? It covers both the
    // world-integrity guard (an End portal frame is never replicated over, by any mode) and the other
    // challenges' vetoes — Block Deleter's "this type is gone for good" and Break-One-Break-All's "this
    // type is broken everywhere".
    if (!blocks().mayModify(world, target)) {
      return false;
    }
    WorldPosition at = BlockBreakService.center(target);
    return switch (commit.kind()) {
      case PLACE -> {
        if (!blocks().allowsPlace(at, commit.payload())) {
          yield false;
        }
        place(world, target, commit.payload());
        yield true;
      }
      case BREAK -> {
        place(world, target, ItemKey.minecraft("air"));
        yield true;
      }
      // The genuine article: the platform re-runs the item's real effect, so a copied TNT block really
      // primes and really explodes rather than just disappearing.
      case USE -> world.useItemOn(target, commit.payload());
    };
  }

  private void place(WorldAdapter world, BlockPosition target, ItemKey block) {
    if (naturalPlacement) {
      world.setBlockNatural(target, block);
    } else {
      world.setBlock(target, block);
    }
  }

  /** A chunk's coordinates packed into one long. Scoped to a {@link Dimension}, so no world is needed. */
  private static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      lastChunk.remove(playerAdapter.uniqueId());
    }
  }

  // ----- persistence (one entry per dimension) ----------------------------------------------------

  /**
   * Saves every dimension's history separately, under a key derived from its world name, plus an index of
   * which worlds exist so a restart knows what to restore. Each entry carries its own hash, so one
   * dimension's history being edited or restored never invalidates another's chunk versions.
   */
  private void saveLog() {
    StringBuilder index = new StringBuilder();
    for (DimensionLog dimension : dimensions.values()) {
      if (index.length() > 0) {
        index.append(',');
      }
      index.append(dimension.worldName());
      String slug = dimension.slug();
      setStateString(KEY_LOG + "." + slug, dimension.ledger().encode());
      setStateString(KEY_HASH + "." + slug, dimension.ledger().head().hash());
      setStateString(KEY_VERSIONS + "." + slug, dimension.encodeVersions());
    }
    setStateString(KEY_WORLDS, index.toString());
    setStateLong(KEY_APPLIED, commitsApplied);
  }

  private void restoreDimensions() {
    dimensions.clear();
    String index = stateString(KEY_WORLDS, "");
    if (index.isBlank()) {
      restoreLegacySharedHistory();
      return;
    }
    for (String worldName : index.split(",")) {
      if (worldName.isBlank()) {
        continue;
      }
      DimensionLog dimension = dimensionOf(worldName);
      String slug = dimension.slug();
      dimension.ledger().decode(stateString(KEY_LOG + "." + slug, ""));
      dimension.restoreVersions(stateString(KEY_VERSIONS + "." + slug, ""),
          stateString(KEY_HASH + "." + slug, ""));
    }
  }

  /**
   * Adopts a history written before each dimension kept its own. There was one shared log, and it
   * belonged to the Overworld — nothing else was ever correctly replicated — so it is restored there and
   * the siblings start clean, which is also the state a player would expect to find them in.
   */
  private void restoreLegacySharedHistory() {
    String legacy = stateString(KEY_LOG, "");
    if (legacy.isBlank() || world() == null || world().name() == null) {
      return;
    }
    DimensionLog dimension = dimensionOf(world().name());
    dimension.ledger().decode(legacy);
    dimension.restoreVersions("", stateString(KEY_HASH, ""));
  }

  @Override
  public void onStop() {
    saveLog();
    queue.clear();
    dimensions.clear();
    lastChunk.clear();
  }

  private void describeHud(HudContext context) {
    // The count is the viewer's OWN dimension: each keeps its own changes, so showing a total would tell
    // them their Nether had edits that are really sitting in the Overworld.
    DimensionLog here = viewedDimension(context);
    ChunkLedger ledger = here == null ? null : here.ledger();
    // "Commits" is the engine's vocabulary, not the player's — on the scoreboard they are simply the
    // CHANGES that are being copied into every chunk of the dimension they are in.
    int changes = ledger == null ? 0 : ledger.size();
    context.line("<aqua>Omni Chunk:</aqua> <white>" + changes + (changes == 1 ? " change" : " changes")
        + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Dimension", here == null ? "-" : here.worldName());
      context.debugStat("Changes applied (all)", commitsApplied);
      context.debugStat("Changes / slots", changes + " / " + (ledger == null ? 0 : ledger.slotCount()));
      context.debugStat("Version", ledger == null ? "-" : ledger.head().seq() + " @" + ledger.head().hash());
      context.debugStat("Queued chunks (all)", queue.size());
      context.debugStat("Chunks tracked", here == null ? 0 : here.trackedChunks());
      context.debugStat("Dimensions tracked", dimensions.size());
      context.debugStat("Natural placement", naturalPlacement);
    }
  }

  /** The history of the dimension the HUD's viewer is currently standing in, or null when unknown. */
  private DimensionLog viewedDimension(HudContext context) {
    PlayerAdapter viewer = context.player();
    WorldPosition at = viewer == null ? null : viewer.position();
    return at == null || at.worldName() == null ? null : dimensions.get(at.worldName());
  }
}
