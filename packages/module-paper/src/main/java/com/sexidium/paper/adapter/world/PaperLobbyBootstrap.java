package com.sexidium.paper.adapter.world;

import com.sexidium.paper.adapter.config.PaperConfigurationAdapter;
import com.sexidium.paper.adapter.logging.PaperLoggerAdapter;
import com.sexidium.paper.adapter.resource.PaperResourceAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.LevelDataSpawn;
import com.sexidium.core.world.LobbyBundle;
import com.sexidium.core.world.MapBundle;
import com.sexidium.core.world.map.SpawnPointStore;
import com.sexidium.core.world.map.SpawnPoints;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Provisiona o mundo lobby no servidor Paper. Encapsula a integracao
 * com Multiverse-Core (dependencia rigida) atraves de uma ponte reflexiva
 * que suporta multiplas versoes da API instavel do plugin.
 */
public final class PaperLobbyBootstrap {
  private static final String MULTIVERSE_PLUGIN_NAME = "Multiverse-Core";

  private final JavaPlugin plugin;
  private final Server server;
  private final PaperConfigurationAdapter configuration;
  private final LoggerAdapter logger;

  public PaperLobbyBootstrap(JavaPlugin plugin, PaperConfigurationAdapter configuration, LoggerAdapter logger) {
    this.plugin = plugin;
    this.server = plugin.getServer();
    this.configuration = configuration;
    this.logger = logger;
  }

  public Path provision() {
    Path serverHome = plugin.getServer().getWorldContainer().toPath();
    String lobbyName = configuration.getString("worlds.lobby.name", "lobby");

    // Provisioning is driven by worlds.lobby.create-if-missing: when the lobby world does not yet exist,
    // seed it from the embedded bundle if one is present, otherwise generate a fresh world below. An
    // EXISTING lobby is never overwritten. On modern Paper (MC 1.21.6+, unified dimension storage) the
    // world named "<lobbyName>" lives at <primary-world>/dimensions/minecraft/<lobbyName>, NOT a
    // top-level folder, so the bundle is extracted there rather than into the (ignored) worlds/ stub.
    Path canonicalFolder = canonicalDimensionFolder(serverHome, lobbyName);
    boolean createMissing = configuration.getBoolean("worlds.lobby.create-if-missing", true);
    if (server.getWorld(lobbyName) == null && !hasWorldData(canonicalFolder) && createMissing) {
      File dataFolder = plugin.getDataFolder();
      boolean seeded = new LobbyBundle(configuration, new PaperResourceAdapter(plugin), logger)
          .seedFromEmbeddedBundle(canonicalFolder, dataFolder == null ? canonicalFolder : dataFolder.toPath());
      if (seeded) {
        captureBundledSpawn(canonicalFolder, lobbyName);
        stripWorldRootArtifacts(canonicalFolder);
      }
      logger.info(seeded
          ? "Lobby '" + lobbyName + "' seeded from the embedded world bundle."
          : "No embedded lobby bundle found; generating a fresh lobby world '" + lobbyName + "'.");
    }

    // Load (or generate) the lobby as a plain Bukkit world. We deliberately do NOT route this through
    // Multiverse: MV v5 cannot load such a world on MC 1.21.6+ (it reports WORLD_FOLDER_INVALID), which
    // is exactly why the lobby previously fell back to the broken "worlds/lobby" duplicate. Multiverse is
    // registered LAST, best-effort, only so "/mv tp <lobby>" can resolve the name.
    World lobby = loadOrCreateLobby(lobbyName);
    if (lobby == null) {
      logger.warning("Could not load or create the lobby world '" + lobbyName + "'.");
      return canonicalFolder;
    }
    applyNativeLobbySettings(lobby);
    applyConfiguredSpawn(lobby);
    registerWithMultiverseBestEffort(lobbyName);
    return lobby.getWorldFolder().toPath();
  }

  /**
   * Extracts the TNT War map worlds embedded in the jar (build-time {@code prepareMapBundle}) into the
   * server world root, so each {@code minigames.tntwar.maps} entry has a clone template ready on first
   * boot, and replaces the ones whose bundled copy changed since they were seeded (a re-exported map
   * reaches the server on the next restart instead of needing the folder deleted by hand). A map whose
   * bundle is unchanged is never touched, so in-game edits survive. Gated by
   * {@code worlds.map-bundle.extract-if-missing} and {@code worlds.map-bundle.refresh-when-changed}
   * (both default true).
   *
   * <p>Safe here because these folders are clone <em>templates</em>: nothing has loaded them as worlds at
   * provisioning time. See {@link MapBundle} for what happens to the copy being replaced.</p>
   *
   * @return the number of map folders written (freshly extracted + refreshed)
   */
  public int provisionBundledMaps() {
    if (!configuration.getBoolean("worlds.map-bundle.extract-if-missing", true)) {
      return 0;
    }
    boolean refreshWhenChanged = configuration.getBoolean("worlds.map-bundle.refresh-when-changed", true);
    Path worldsRoot = resolveWorldsRoot(plugin.getServer().getWorldContainer().toPath());
    return new MapBundle(new PaperResourceAdapter(plugin), logger).seedBundledMaps(worldsRoot, refreshWhenChanged);
  }

  /**
   * Carries the freshly seeded map's own world spawn over into the lobby sidecar, BEFORE
   * {@link #stripWorldRootArtifacts} deletes the {@code level.dat} that holds it.
   *
   * <p>Without this the dimension has no spawn of its own, so vanilla runs its initial-spawn search —
   * a biome probe that happily lands hundreds of blocks from the build (the BreadBuilds lobby seeded at
   * spawn 8/47/8 put joiners at roughly -576/-464, in empty terrain). Persisting it as spawn point 1
   * makes every consumer agree: the pinned world spawn, joins, respawns and the void redirect all read
   * the sidecar. Never overwrites an existing sidecar, and a map without a readable spawn is left to the
   * old behaviour.</p>
   */
  private void captureBundledSpawn(Path dimensionFolder, String lobbyName) {
    SpawnPointStore store = new SpawnPointStore(SpawnPointStore.LOBBY);
    if (store.load(dimensionFolder, lobbyName).isReady()) {
      return;
    }
    Optional<WorldPosition> spawn = LevelDataSpawn.read(dimensionFolder.resolve("level.dat"), lobbyName);
    if (spawn.isEmpty()) {
      logger.info("Bundled lobby map has no readable world spawn; run '/sx lobby setspawn' on the build.");
      return;
    }
    SpawnPoints points = new SpawnPoints(lobbyName);
    points.setPrimary(spawn.get());
    try {
      store.save(dimensionFolder, points);
      logger.info("Lobby spawn captured from the bundled map's level.dat: "
          + spawn.get().coordinateX() + ", " + spawn.get().coordinateY() + ", " + spawn.get().coordinateZ());
    } catch (IOException exception) {
      logger.warning("Could not write the bundled lobby spawn sidecar: " + exception.getMessage());
    }
  }

  /**
   * Removes whole-world root files a classic world bundle drops into the lobby's DIMENSION folder
   * ({@code level.dat}, {@code level.dat_old}, {@code session.lock}, {@code uid.dat}). A dimension
   * folder (like the sibling {@code overworld}) must not carry these — leaving a {@code level.dat}
   * there makes Paper treat the dimension as a standalone world and can trigger a destructive storage
   * migration on the next boot. Region/entity/poi data is left untouched.
   */
  private void stripWorldRootArtifacts(Path dimensionFolder) {
    for (String artifact : new String[]{"level.dat", "level.dat_old", "session.lock", "uid.dat"}) {
      try {
        Files.deleteIfExists(dimensionFolder.resolve(artifact));
      } catch (IOException exception) {
        logger.warning("Could not remove stray '" + artifact + "' from the lobby dimension folder: "
            + exception.getMessage());
      }
    }
  }

  /** True when the lobby's dimension folder already holds world data (so it must not be re-seeded). */
  private boolean hasWorldData(Path canonicalFolder) {
    return Files.isDirectory(canonicalFolder)
        && (Files.isDirectory(canonicalFolder.resolve("region")) || Files.exists(canonicalFolder.resolve("level.dat")));
  }

  /**
   * The lobby's real on-disk folder. The primary world's folder IS its own dimension folder — on MC
   * 1.21.6+ that is {@code <container>/world/dimensions/minecraft/overworld}, on classic storage it is
   * {@code <container>/world}. The lobby is a SIBLING of the primary dimension, so swap the last path
   * segment for the lobby name; this resolves correctly under both layouts. (Do NOT append
   * {@code dimensions/minecraft/<name>} to the primary folder — under dimension storage that
   * double-nests into {@code .../overworld/dimensions/minecraft/<name>}.)
   */
  private Path canonicalDimensionFolder(Path serverHome, String lobbyName) {
    var worlds = server.getWorlds();
    if (worlds != null && !worlds.isEmpty() && worlds.get(0).getWorldFolder() != null) {
      Path primary = worlds.get(0).getWorldFolder().toPath();
      Path parent = primary.getParent();
      if (parent != null) {
        return parent.resolve(sanitizeFolderName(lobbyName));
      }
    }
    return serverHome.resolve("world").resolve("dimensions").resolve("minecraft").resolve(sanitizeFolderName(lobbyName));
  }

  /** Loads the lobby world (or generates it when missing), bypassing Multiverse entirely. */
  private World loadOrCreateLobby(String lobbyName) {
    World existing = server.getWorld(lobbyName);
    if (existing != null) {
      return existing;
    }
    boolean createMissing = configuration.getBoolean("worlds.lobby.create-if-missing", true);
    Path canonical = canonicalDimensionFolder(plugin.getServer().getWorldContainer().toPath(), lobbyName);
    if (!Files.isDirectory(canonical) && !createMissing) {
      logger.warning("Lobby world '" + lobbyName
          + "' has no data and worlds.lobby.create-if-missing is false; skipping.");
      return null;
    }
    try {
      return server.createWorld(new WorldCreator(lobbyName)
          .environment(World.Environment.NORMAL)
          .type(WorldType.NORMAL));
    } catch (RuntimeException exception) {
      logger.warning("Could not load lobby world '" + lobbyName + "': " + exception.getMessage());
      return null;
    }
  }

  /**
   * Pins the lobby world's vanilla spawn so a join lands on the built spawn instead of the raw
   * world-generation spawn (which may sit in plain terrain below a floating build). Prefers the admin
   * captured primary spawn from the lobby sidecar ({@code sexidium-lobby.yml}, written by
   * {@code /lobby setspawn}); falls back to a fixed {@code worlds.lobby.spawn.{x,y,z}} config block.
   * No-op when neither is set: the world's own spawn is used.
   */
  private void applyConfiguredSpawn(World lobby) {
    if (lobby == null) {
      return;
    }
    WorldPosition primary = sidecarPrimarySpawn(lobby);
    if (primary != null) {
      lobby.setSpawnLocation((int) Math.floor(primary.coordinateX()), (int) Math.floor(primary.coordinateY()),
          (int) Math.floor(primary.coordinateZ()));
      logger.info("Lobby spawn pinned to " + primary.coordinateX() + ", " + primary.coordinateY() + ", "
          + primary.coordinateZ() + " from /lobby setspawn.");
      return;
    }
    if (!configuration.contains("worlds.lobby.spawn.x")) {
      return;
    }
    double x = configuration.getDouble("worlds.lobby.spawn.x", lobby.getSpawnLocation().getX());
    double y = configuration.getDouble("worlds.lobby.spawn.y", lobby.getSpawnLocation().getY());
    double z = configuration.getDouble("worlds.lobby.spawn.z", lobby.getSpawnLocation().getZ());
    lobby.setSpawnLocation((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    logger.info("Lobby spawn pinned to " + x + ", " + y + ", " + z + " from config.");
  }

  /** The lobby's captured primary spawn point (point 1 of sexidium-lobby.yml), or null when none is set. */
  private WorldPosition sidecarPrimarySpawn(World lobby) {
    if (lobby.getWorldFolder() == null) {
      return null;
    }
    SpawnPoints spawns = new SpawnPointStore(SpawnPointStore.LOBBY)
        .load(lobby.getWorldFolder().toPath(), lobby.getName());
    return spawns.primary();
  }

  /** Best-effort Multiverse registration so {@code /mv tp <lobby>} resolves; never fatal if MV refuses. */
  private void registerWithMultiverseBestEffort(String lobbyName) {
    try {
      ensureMultiverseWorldsRoot(resolveWorldsRoot(plugin.getServer().getWorldContainer().toPath()));
      Plugin multiverse = server.getPluginManager().getPlugin(MULTIVERSE_PLUGIN_NAME);
      if (multiverse == null) {
        return;
      }
      // The shared Multiverse bridge — the same one PaperWorldControl uses, so the two call sites
      // cannot drift apart on Multiverse v4/v5 handling again.
      MultiverseBridge mv = MultiverseBridge.tryBind(multiverse, logger);
      if (mv != null && mv.world(lobbyName) == null) {
        mv.importWorld(lobbyName, World.Environment.NORMAL, WorldType.NORMAL, null);
      }
    } catch (RuntimeException exception) {
      logger.info("Multiverse lobby registration skipped: " + exception.getMessage());
    }
  }

  private void applyNativeLobbySettings(World world) {
    if (world == null) {
      return;
    }
    world.setAutoSave(true);
    world.setDifficulty(parseDifficulty(configuration.getString("worlds.lobby.difficulty", "NORMAL")));
    world.setPVP(configuration.getBoolean("worlds.lobby.pvp", false));
    applyLobbyTime(world);
  }

  /** Freezes the lobby at a fixed bright time of day so the map always shows daytime. */
  private void applyLobbyTime(World world) {
    if (!configuration.getBoolean("worlds.lobby.lock-time", true)) {
      return;
    }
    if (world == null) {
      return;
    }
    long time = configuration.getLong("worlds.lobby.time", 6000L);
    world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
    world.setTime(time);
  }

  private void ensureMultiverseWorldsRoot(Path worldsRoot) {
    File multiverseConfigFile = new File(plugin.getServer().getWorldContainer(), "plugins/Multiverse-Core/config.yml");
    if (!multiverseConfigFile.isFile()) {
      return;
    }
    YamlConfiguration multiverseConfig = YamlConfiguration.loadConfiguration(multiverseConfigFile);
    String rootString = worldsRoot.getFileName() == null ? "worlds" : worldsRoot.getFileName().toString();
    String current = multiverseConfig.getString("worlds", null);
    if (rootString.equals(current)) {
      return;
    }
    multiverseConfig.set("worlds", rootString);
    multiverseConfig.set("firstspawnworld", configuration.getString("worlds.lobby.name", "lobby"));
    try {
      multiverseConfig.save(multiverseConfigFile);
      logger.info("Patched Multiverse-Core config: worlds=" + rootString
          + ", firstspawnworld=" + configuration.getString("worlds.lobby.name", "lobby"));
    } catch (IOException exception) {
      logger.warning("Could not update Multiverse-Core config.yml: " + exception.getMessage());
    }
  }

  private Path resolveWorldsRoot(Path serverHome) {
    String rootName = configuration.getString("worlds.root", "worlds");
    return serverHome.resolve(sanitizeFolderName(rootName));
  }

  private static String sanitizeFolderName(String raw) {
    if (raw == null || raw.isBlank()) {
      return "worlds";
    }
    String trimmed = raw.trim();
    String[] parts = trimmed.split("[\\\\/]+");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (part.isBlank()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append('/');
      }
      builder.append(part);
    }
    return builder.length() == 0 ? "worlds" : builder.toString();
  }

  private static Difficulty parseDifficulty(String raw) {
    if (raw == null) {
      return Difficulty.NORMAL;
    }
    try {
      return Difficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      return Difficulty.NORMAL;
    }
  }

  // Fornece um adapter de logger a partir do JavaPlugin quando o caller
  // nao precisar de uma implementacao customizada.
  public static LoggerAdapter loggerFor(JavaPlugin plugin) {
    return new PaperLoggerAdapter(plugin.getLogger());
  }
}
