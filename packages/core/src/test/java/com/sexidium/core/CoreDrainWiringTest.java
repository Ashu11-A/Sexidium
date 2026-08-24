package com.sexidium.core;

import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.DrainPhase;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.network.NodeRegistry;
import com.sexidium.core.network.transfer.TransferReason;
import com.sexidium.core.network.transfer.TransferTicket;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import com.sexidium.core.world.FakeWorldControl;
import com.sexidium.core.world.WorldGeneration;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a drain does to a REAL core: the two steps whose wiring nothing else exercises.
 *
 * <p>Both were wrong in ways their own unit tests could not see. {@code handOverWorld} ended a match
 * and did nothing else, so a world with no match — a failed unload, an evicted world still in the
 * world layer's {@code closing} set — pinned {@code worlds_left} at 1 until the drain stalled. And
 * every evacuated player's lobby ticket was minted with a hard-coded epoch 0, which
 * {@code TransferTicket.addressedTo} reads as "any node, any boot": the one fencing column on the
 * path EVERY drained player takes was inert.</p>
 */
class CoreDrainWiringTest {

  private static final WorldKey WORLD = WorldKey.parse("diamond_hunt_ab12cd34");

  /** Minimal in-memory config; only the handful of overrides a networked test node needs. */
  private static final class FakeConfig implements ConfigurationAdapter {
    private final Map<String, Object> values = new HashMap<>();

    FakeConfig with(String path, Object value) {
      values.put(path, value);
      return this;
    }

    @Override public boolean getBoolean(String path, boolean defaultValue) {
      return values.containsKey(path) ? (Boolean) values.get(path) : defaultValue;
    }

    @Override public int getInt(String path, int defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).intValue() : defaultValue;
    }

    @Override public long getLong(String path, long defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).longValue() : defaultValue;
    }

    @Override public double getDouble(String path, double defaultValue) { return defaultValue; }

    @Override public String getString(String path, String defaultValue) {
      return values.containsKey(path) ? (String) values.get(path) : defaultValue;
    }

    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public Set<String> keys(String path) { return Set.of(); }
    @Override public Object get(String path) { return values.get(path); }
    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public void set(String path, Object value) { values.put(path, value); }
    @Override public void reload() { }
    @Override public void save() { }
  }

  /** One player standing on the node, so the drain has somebody to evacuate. */
  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Ashu11a"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }

  /** A worker: it hosts experiences, has one player on it, and its world layer is the fake. */
  private static final class WorkerAdapter extends TestServerAdapter {
    private final Path dataDirectory;
    private final ConfigurationAdapter configuration;
    private final FakeWorldControl worlds;
    private final PlayerAdapter player;

    WorkerAdapter(Path dataDirectory, ConfigurationAdapter configuration, FakeWorldControl worlds,
        PlayerAdapter player) {
      this.dataDirectory = dataDirectory;
      this.configuration = configuration;
      this.worlds = worlds;
      this.player = player;
    }

    @Override public Path dataDirectory() { return dataDirectory; }

    @Override public ConfigurationAdapter configuration() { return configuration; }

    @Override public WorldLeaseService worlds() { return worlds; }

    @Override public Collection<PlayerAdapter> onlinePlayers() {
      return player == null ? List.of() : List.of(player);
    }

    @Override public Optional<PlayerAdapter> player(UUID id) {
      return player != null && player.uniqueId().equals(id) ? Optional.of(player) : Optional.empty();
    }

    @Override public NodeIdentity identity() {
      return NodeIdentity.of("worker-1", "worker-1", Set.of(
          NodeCapability.EXPERIENCES, NodeCapability.MINIGAMES));
    }
  }

  @TempDir
  Path tmp;

  private Database database;
  private FakeWorldControl worlds;
  private FakePlayer player;
  private SexidiumCore core;
  private long lobbyEpoch;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "drain.db"));
    worlds = new FakeWorldControl(new FakeConfig(), new StdoutLoggerAdapter("test"), tmp);
    player = new FakePlayer();
    // A live lobby to evacuate to, with a real registry epoch — which is the whole point of the
    // ticket assertion below.
    NodeRegistry lobby = new NodeRegistry(database, new StdoutLoggerAdapter("lobby"),
        NodeIdentity.of("lobby", "lobby", NodeIdentity.capabilitiesForRole("lobby")), 30_000L);
    lobby.heartbeat(0, 0);
    lobbyEpoch = lobby.epoch();
    core = new SexidiumCore(new SexidiumCoreDependencies(
        new WorkerAdapter(tmp, new FakeConfig()
            .with("api.enabled", false)
            // The test tree is this node's own, and I10 refuses to enable without the marker.
            .with("network.shared-world-storage", false), worlds, player),
        new NoopKitAdapter(), new GameRegistry(), database, null, () -> false));
    core.start();
  }

  @AfterEach
  void tearDown() {
    if (core != null) {
      core.close();
    }
  }

  /** Open the experience world here, through the real placement gate the core just installed. */
  private void openWorldHere() {
    AtomicReference<WorldLease> lease = new AtomicReference<>();
    worlds.acquireOrCreatePersistent(WORLD, List.of(), WorldGeneration.DEFAULT, lease::set, () -> { });
    assertNotNull(lease.get(), "this node has to actually hold the world for the drain to hand it over");
    assertTrue(worlds.openPersistentPlacementKeys().contains(WORLD.key()));
  }

  @Test
  @DisplayName("a drained world with no live match is still handed over")
  void aWorldWithNoMatchIsHandedOver() {
    openWorldHere();

    assertTrue(core.network().drainControl().drain("rolling-update", false, "test").accepted());
    core.network().drainControl().tick();

    assertEquals(List.of(WORLD.runtimeName()), worlds.unloaded,
        "ending a match was the ONLY handover this had, and there is no match here — so worlds_left"
            + " stayed at 1 until the drain stalled at its deadline");
    assertTrue(worlds.openPersistentPlacementKeys().isEmpty());
    assertEquals(0, core.network().drainControl().state().worldsLeft());
  }

  @Test
  @DisplayName("an evacuated player's lobby ticket is addressed to the lobby's live epoch")
  void theLobbyTicketCarriesTheOwnerEpoch() {
    assertTrue(core.network().drainControl().drain("rolling-update", false, "test").accepted());
    core.network().drainControl().tick();

    List<TransferTicket> tickets = core.network().transferService().inFlight();
    TransferTicket lobbyTicket = tickets.stream()
        .filter(ticket -> ticket.reason() == TransferReason.LOBBY)
        .findFirst().orElseThrow(() -> new AssertionError("the player has to be sent somewhere"));
    assertEquals("lobby", lobbyTicket.targetNode());
    assertNotEquals(0L, lobbyTicket.targetEpoch(),
        "epoch 0 is 'any node, any boot' to TransferTicket.addressedTo, so the fence was inert on the"
            + " one path every drained player takes");
    assertEquals(lobbyEpoch, lobbyTicket.targetEpoch());
  }
}
