package com.sexidium.core.command;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.SexidiumCoreDependencies;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.PlatformType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the map commands actually ASK about {@code MAP_AUTHORITY}.
 *
 * <p>Separate from {@code CapabilityGatingTest}, which only pins the role table, because a
 * capability that is declared and never consulted is indistinguishable from a working gate when you
 * look at the table alone — that is precisely what happened to {@code EXPERIENCES} and
 * {@code MINIGAMES}, which are expanded per role and asked about nowhere. So these tests drive the
 * real command grammar and read the messages the admin would have seen.</p>
 *
 * <p>They are the negative control too: delete the gate from any of the three handlers and the
 * corresponding "refuses" test fails, because the command falls through to its ordinary reply.</p>
 */
class MapAuthorityGateTest {

  private static final String AUTHORITY_KEY = "map.authority-required";

  @TempDir
  Path tempDir;

  @Test
  @DisplayName("a worker refuses TNT War base capture and names the authority")
  void worker_refusesTntWarCapture() {
    try (Harness harness = harness("worker")) {
      harness.commands.execute(harness.admin(), new String[] {"admin", "map", "tntwar", "demo", "save"});

      assertTrue(harness.messages.sent.contains(AUTHORITY_KEY),
          "a worker must be told it cannot write map sidecars, and which node can");
    }
  }

  @Test
  @DisplayName("a worker refuses Combat spawn capture")
  void worker_refusesCombatCapture() {
    try (Harness harness = harness("worker")) {
      harness.commands.execute(harness.admin(), new String[] {"admin", "map", "combat", "demo", "spawn"});

      assertTrue(harness.messages.sent.contains(AUTHORITY_KEY));
    }
  }

  @Test
  @DisplayName("a worker refuses to open the map editor at all, not just to save")
  void worker_refusesEditorEntry() {
    try (Harness harness = harness("worker")) {
      FakePlayer admin = harness.player("Admin");

      harness.commands.execute(admin, new String[] {"admin", "map", "edit", "combat", "demo"});
      harness.commands.execute(admin, new String[] {"admin", "map", "edit", "save"});

      assertTrue(harness.messages.sent.contains(AUTHORITY_KEY),
          "entering drops the admin into the template world itself; refusing only at save wastes their work");
      assertFalse(harness.core.mapEditor().hasSession(admin.uniqueId()),
          "no session may exist on a node that could never keep the result");
    }
  }

  @Test
  @DisplayName("the refusal is never silent: reading the maps still works everywhere")
  void worker_stillLists() {
    try (Harness harness = harness("worker")) {
      harness.commands.execute(harness.admin(), new String[] {"admin", "map", "tntwar", "list"});

      assertFalse(harness.messages.sent.contains(AUTHORITY_KEY),
          "listing writes nothing; an admin needs it to make sense of the refusal");
      assertFalse(harness.messages.sent.isEmpty(), "and it must still answer something");
    }
  }

  @Test
  @DisplayName("a node holding the capability is not gated: the command proceeds to its real work")
  void authority_proceeds() {
    // "standalone" is the authority case that matters most: a single server must behave exactly as
    // it did before the gate existed. It reaches TNT War's own "no such map" reply, which is only
    // produced after the gate has let it through.
    try (Harness harness = harness("standalone")) {
      harness.commands.execute(harness.admin(), new String[] {"admin", "map", "tntwar", "demo", "save"});

      assertFalse(harness.messages.sent.contains(AUTHORITY_KEY));
      assertTrue(harness.messages.sent.contains("tntwar.map.unknown"),
          "it must get all the way to the map lookup, i.e. past the gate");
    }
  }

  @Test
  @DisplayName("the lobby is an authority too, so the network keeps one place to edit maps")
  void lobby_proceeds() {
    try (Harness harness = harness("lobby")) {
      harness.commands.execute(harness.admin(), new String[] {"admin", "map", "combat", "demo", "spawn"});

      assertFalse(harness.messages.sent.contains(AUTHORITY_KEY));
      assertTrue(harness.messages.sent.stream().anyMatch(sent -> sent.contains("minigames.combat.maps")),
          "it reaches Combat's own unknown-map reply, which lives past the gate");
    }
  }

  @Test
  @DisplayName("the editor opens on an authority node exactly as before")
  void authority_reachesTheEditor() {
    try (Harness harness = harness("standalone")) {
      FakePlayer admin = harness.player("Admin");

      harness.commands.execute(admin, new String[] {"admin", "map", "edit", "save"});

      assertFalse(harness.messages.sent.contains(AUTHORITY_KEY));
      assertTrue(harness.messages.sent.stream().anyMatch(sent -> sent.contains("not editing a map")),
          "it reaches the editor's own 'you are not editing' reply, which lives past the gate");
    }
  }

  // ----- harness -----------------------------------------------------------------------------------

  private Harness harness(String role) {
    GameRegistry registry = new GameRegistry();
    FakeServerAdapter delegate = new FakeServerAdapter(tempDir);
    CapturingMessages messages = new CapturingMessages();
    delegate.setMessages(messages);
    ServerAdapter server = new RoleServerAdapter(delegate, role);
    SexidiumCore core = new SexidiumCore(new SexidiumCoreDependencies(
        server, new FakeKitAdapter(Set.of()), registry, null, null, () -> false));
    return new Harness(core, new CoreCommandService(core, () -> {}), delegate, messages);
  }

  private record Harness(SexidiumCore core, CoreCommandService commands, FakeServerAdapter server,
                         CapturingMessages messages) implements AutoCloseable {

    CommandSource admin() {
      return new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    }

    FakePlayer player(String name) {
      FakePlayer player = new FakePlayer(name,
          Set.of(CoreCommandService.ADMIN_PERMISSION, CoreCommandService.PLAY_PERMISSION));
      server.players.put(name.toLowerCase(java.util.Locale.ROOT), player);
      return player;
    }

    @Override
    public void close() {
      try {
        core.close();
      } catch (Exception ignored) {
        // Nothing was started; closing is best-effort bookkeeping only.
      }
    }
  }

  /**
   * The shared {@code FakeServerAdapter} with one thing changed: which node this process claims to
   * be. {@code NodeRuntime.identity()} is a defaulted interface method (standalone), so the only way
   * to test a worker is to wrap rather than edit the shared fake — which also keeps every other
   * command test on the identity it already assumes.
   */
  private record RoleServerAdapter(FakeServerAdapter delegate, String role) implements ServerAdapter {

    @Override
    public NodeIdentity identity() {
      Set<NodeCapability> capabilities = NodeIdentity.capabilitiesForRole(role);
      return "standalone".equals(role)
          ? NodeIdentity.standalone()
          : NodeIdentity.of(role + "-1", role + "-1", capabilities);
    }

    @Override public String serverName() { return delegate.serverName(); }
    @Override public PlatformType platformType() { return delegate.platformType(); }
    @Override public Path dataDirectory() { return delegate.dataDirectory(); }
    @Override public ConfigurationAdapter configuration() { return delegate.configuration(); }
    @Override public LoggerAdapter logger() { return delegate.logger(); }
    @Override public ResourceAdapter resources() { return delegate.resources(); }
    @Override public SchedulerAdapter scheduler() { return delegate.scheduler(); }
    @Override public UiAdapter ui() { return delegate.ui(); }
    @Override public MessageAdapter messages() { return delegate.messages(); }
    @Override public EventDispatcherAdapter events() { return delegate.events(); }
    @Override public CommandDispatcherAdapter commands() { return delegate.commands(); }
    @Override public WorldLeaseService worlds() { return delegate.worlds(); }
    @Override public CommandSource console() { return delegate.console(); }
    @Override public Collection<PlayerAdapter> onlinePlayers() { return delegate.onlinePlayers(); }
    @Override public Optional<PlayerAdapter> player(UUID playerId) { return delegate.player(playerId); }
    @Override public Optional<PlayerAdapter> playerExact(String playerName) { return delegate.playerExact(playerName); }
  }
}
