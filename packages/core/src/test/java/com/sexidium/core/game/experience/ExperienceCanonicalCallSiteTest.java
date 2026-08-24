package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import com.sexidium.core.world.AbstractWorldControl;
import com.sexidium.core.world.WorldHandle;
import com.sexidium.core.world.WorldKind;
import com.sexidium.core.world.WorldRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production CALL SITES that look an experience up by world, driven end to end.
 *
 * <p>These tests pinned a BUG. They asserted that the registry's spelling and the world layer's
 * spelling were deliberately different — {@code assertNotEquals(key, experience.worldName())}, "the
 * point of the test is that these two spellings are NOT equal" — and every lookup in production had
 * to go through a whole-table scan to bridge them. There is one spelling now, so what these pin is
 * the opposite: the registry stores exactly {@link com.sexidium.core.world.WorldKey#key()}, a
 * regeneration is the same run at a later generation, and every call site resolves it with a single
 * index lookup. They still drive {@code ExperienceService.enterByWorld} and
 * {@code ExperiencePersistence.loadState/loadSnapshot} end to end over a real database and a real
 * {@link AbstractWorldControl}, because a lookup being correct is worth nothing if a caller asks a
 * different question — which is what every caller did, and what the whole incident was.</p>
 */
class ExperienceCanonicalCallSiteTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private ExperienceManager experiences;
  private ExperienceManager.Experience experience;
  private TestControl worlds;
  private TestServerAdapter server;

  @BeforeEach
  void setUp() throws Exception {
    experiences = new ExperienceManager(SILENT, new Database(new File(tmp.toFile(), "callsites.db")));
    experience = experiences.create(UUID.randomUUID(), "Ashu11a", List.of("deathresets"),
        "Death Resets", System.currentTimeMillis());
    assertNotNull(experience, "the fixture needs a stored experience");
    worlds = new TestControl(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tmp);
    server = new TestServerAdapter() {
      @Override public WorldLeaseService worlds() { return worlds; }
      @Override public LoggerAdapter logger() { return SILENT; }
    };
  }

  /** The key the world layer holds after {@code generation} resets, from the stored one. */
  private com.sexidium.core.world.WorldKey runtimeKey(int generation) {
    return new com.sexidium.core.world.WorldKey(experience.key().base(), generation);
  }

  /**
   * A cross-node arrival. The transfer carries a WORLD, because placement is about worlds; this node
   * has to turn it back into an experience, and the answer "no such experience" bounces the player
   * straight back to the lobby — an entry loop with nothing they can do about it.
   */
  @Test
  @DisplayName("enterByWorld resolves a world named the way the placement layer names it")
  void enterByWorldResolvesACanonicalName() {
    ExperienceService service = new ExperienceService(server, null, experiences, null, null);
    OfflinePlayer player = new OfflinePlayer();

    // OFFLINE means the lookup SUCCEEDED and enter() then refused the offline player; NOT_FOUND means
    // the lookup missed. The offline player is what lets this test stop at the lookup.
    assertEquals(ExperienceService.EnterOutcome.OFFLINE,
        service.enterByWorld(player, experience.key()),
        "a handoff naming the world canonically must find the experience");
    assertEquals(ExperienceService.EnterOutcome.OFFLINE,
        service.enterByWorld(player,
            com.sexidium.core.world.WorldKey.fromRuntime(
                "experiences/" + experience.worldKey()).orElseThrow()),
        "...and so must the namespaced runtime spelling, once parsed back to a key");
    assertEquals(ExperienceService.EnterOutcome.OFFLINE,
        service.enterByWorld(player,
            com.sexidium.core.world.WorldKey.fromRuntime(
                "experiences_" + experience.worldKey()).orElseThrow()),
        "...and Bukkit's FLATTENED label, which is the only form a live world ever reports");

    assertEquals(ExperienceService.EnterOutcome.NOT_FOUND,
        service.enterByWorld(player, runtimeKey(4)),
        "a generation that is not the one stored is a different world; the registry follows the"
            + " current one and nothing else");
    assertEquals(ExperienceService.EnterOutcome.NOT_FOUND,
        service.enterByWorld(player,
            com.sexidium.core.world.WorldKey.parse("some_other_map_ff00ff00")),
        "a world nobody stored must still be NOT_FOUND, or this test proves nothing");
  }

  /**
   * The load that decides whether a run is recorded at all. A null id here silently disables
   * {@code updateWorldKey}, the crash net and the hardcore death flag — which is how the registry came
   * to point at a generation that had been deleted ten resets earlier, and how the next entry
   * generated an empty world over a live save.
   */
  @Test
  @DisplayName("loadState resolves the registry id from the CURRENT generation's world name")
  void loadStateResolvesTheExperienceIdAfterResets() throws Exception {
    Path subdir = tmp.resolve("experiences");
    // Nine resets on. The registry FOLLOWS the world (ExperienceWorldReset writes updateWorldKey), so
    // the row names the ninth generation — which is what makes one index lookup sufficient.
    com.sexidium.core.world.WorldKey key = runtimeKey(9);
    experiences.updateWorldKey(experience.id(), key, 2_000L);
    experience = experiences.get(experience.id());
    Files.createDirectories(subdir.resolve(key.key()));
    GameContext context = new GameContext(server, new NoopKitAdapter(), null);
    context.attachExperiences(experiences);
    context.attachExperienceStore(new ExperienceStateStore(subdir, SILENT));

    ExperiencePersistence persistence = new ExperiencePersistence(
        new FakeHost(new FakeWorld("experiences/" + key.key())), context);
    persistence.loadState();

    assertEquals(key.key(), persistence.stateWorldKey(),
        "the state key is the runtime name stripped to the experiences subdir");
    assertEquals(experience.id(), persistence.experienceId(),
        "nine resets on, the registry row still has to resolve — a null id here loses the run");
    assertEquals(key.key(), experience.worldKey(),
        "and the registry holds exactly that key: ONE spelling, not two that have to be bridged");
    assertTrue(key.sameRun(runtimeKey(0)),
        "every generation of one experience shares a base — that is what makes it one run");
  }

  @Test
  @DisplayName("loadState leaves the id null for a world no experience owns")
  void loadStateDoesNotInventAnExperience() throws Exception {
    Path subdir = tmp.resolve("experiences");
    Files.createDirectories(subdir.resolve("unknown_map_ff00ff00"));
    GameContext context = new GameContext(server, new NoopKitAdapter(), null);
    context.attachExperiences(experiences);
    context.attachExperienceStore(new ExperienceStateStore(subdir, SILENT));

    ExperiencePersistence persistence = new ExperiencePersistence(
        new FakeHost(new FakeWorld("experiences/unknown_map_ff00ff00")), context);
    persistence.loadState();

    assertNull(persistence.experienceId(),
        "resolving SOMETHING for an unknown world would bind a run to a stranger's registry row");
  }

  /**
   * The legacy-snapshot fallback, which is the only thing standing between a pre-{@code state.yml}
   * experience and an emptied inventory. It resolves the same id the same way, and reverting it to an
   * exact match makes the fallback silently return nothing — the exact shape of "my items are gone".
   */
  @Test
  @DisplayName("loadSnapshot's legacy fallback resolves the row from the runtime world name")
  void legacySnapshotResolvesByIdentity() throws Exception {
    Path subdir = tmp.resolve("experiences");
    com.sexidium.core.world.WorldKey key = runtimeKey(2);
    experiences.updateWorldKey(experience.id(), key, 2_000L);
    experience = experiences.get(experience.id());
    Files.createDirectories(subdir.resolve(key.key()));
    UUID player = UUID.randomUUID();
    // A row written before state.yml existed: the DB is the only place this player's world is recorded.
    experiences.rememberPlayerWorld(experience.id(), player, key, 1_000L);

    GameContext context = new GameContext(server, new NoopKitAdapter(), null);
    context.attachExperiences(experiences);
    context.attachExperienceStore(new ExperienceStateStore(subdir, SILENT));
    ExperiencePersistence persistence = new ExperiencePersistence(
        new FakeHost(new FakeWorld("experiences/" + key.key())), context);

    PlayerSnapshot recovered = persistence.loadSnapshot(new OfflinePlayer(player));

    assertNotNull(recovered, "the legacy row exists; failing to find it is how a run reads as empty");
    assertEquals(key.key(), recovered.worldName);
  }

  // ===== fakes ==================================================================================

  /** A real {@link AbstractWorldControl} — the naming rules under test are its own — with no backend. */
  private static final class TestControl extends AbstractWorldControl {
    private final Path home;

    private TestControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home) {
      super(configuration, logger);
      this.home = home;
    }

    @Override protected void runOnWorldThread(Runnable task) { task.run(); }
    @Override protected Path serverHome() { return home; }
    @Override protected Path experiencesDiskRoot() { return home.resolve("experiences"); }
    @Override protected Path lobbyDiskFolder() { return home.resolve("lobby"); }
    @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean create) {
      return Optional.empty();
    }
    @Override protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      return Optional.empty();
    }
    @Override protected boolean backendUnload(WorldHandle handle, boolean save) { return true; }
    @Override protected Optional<WorldHandle> backendLobby() { return Optional.empty(); }
  }

  private record FakeHost(WorldAdapter world) implements PersistenceHost {
    @Override public ScheduledTask runLater(Runnable runnable, long delayTicks) {
      return null;
    }
  }

  private record FakeWorld(String name) implements WorldAdapter {
    @Override public WorldPosition spawnPosition() { return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition target, ItemStackData item) { }
    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec spec) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }

  /**
   * A player who is not online. Deliberately: it makes {@code enter()} stop at OFFLINE, which separates
   * "the world was resolved" from "the world was not found" without a GameManager in the way.
   */
  private static final class OfflinePlayer implements PlayerAdapter {
    private final UUID uniqueId;
    private final FakeInventory inventory = new FakeInventory();

    private OfflinePlayer() { this(UUID.randomUUID()); }

    private OfflinePlayer(UUID uniqueId) { this.uniqueId = uniqueId; }

    @Override public UUID uniqueId() { return uniqueId; }
    @Override public boolean online() { return false; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public InventoryAdapter inventory() { return inventory; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { inventory.clear(); }
    @Override public void clearPotionEffects() { }
    @Override public String name() { return "Ashu11a"; }
    @Override public Locale locale() { return Locale.ROOT; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
  }

  private static final class FakeInventory implements InventoryAdapter {
    private List<ItemStackData> contents = new ArrayList<>();
    private Map<String, List<ItemStackData>> equipment = new HashMap<>();

    @Override public void clear() { contents.clear(); }
    @Override public boolean contains(ItemKey itemKey) {
      return contents.stream().anyMatch(item -> item.itemKey().equals(itemKey));
    }
    @Override public void add(ItemStackData itemStackData) { contents.add(itemStackData); }
    @Override public List<ItemStackData> storageContents() { return List.copyOf(contents); }
    @Override public void setStorageContents(List<ItemStackData> itemStacks) {
      contents = new ArrayList<>(itemStacks);
    }
    @Override public Map<String, List<ItemStackData>> equipmentContents() { return Map.copyOf(equipment); }
    @Override public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) {
      equipment = new HashMap<>(equipmentContents);
    }
  }
}
