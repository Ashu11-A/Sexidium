package com.sexidium.core.world.lobby;
import com.sexidium.core.world.lobby.LobbyEnums.*;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.DirectSchedulerAdapter;
import com.sexidium.core.platform.noop.NoopCommandDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopEventDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopUiAdapter;
import com.sexidium.core.platform.noop.NoopWorldLeaseService;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyManagerTest {
  private static final String DUEL = "duel"; // min 2
  private static final String RACE = "race"; // min 1

  private final Map<UUID, FakePlayer> playerMap = new HashMap<>();
  private FakeLauncher launcher;
  private ServerAdapter server;
  private LobbyManager lobbies;

  @BeforeEach
  void setUp() {
    launcher = new FakeLauncher();
    server = server();
    lobbies = new LobbyManager(server, launcher, null);
  }

  private FakePlayer player(String name) {
    FakePlayer fake = new FakePlayer(UUID.randomUUID(), name);
    playerMap.put(fake.id, fake);
    return fake;
  }

  // ----- group lifecycle (was PartyManagerTest) -------------------------------------------------

  @Test
  void invite_self_returnsSelf() {
    FakePlayer alice = player("Alice");
    assertEquals(LobbyResult.SELF, lobbies.invite(alice, alice));
  }

  @Test
  void invite_makesInviterLeader_andAcceptJoins() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    assertEquals(LobbyResult.INVITE_SENT, lobbies.invite(alice, bob));
    assertTrue(lobbies.isLeader(alice.id));
    assertEquals(LobbyResult.JOINED, lobbies.accept(bob, alice.id));
    assertTrue(lobbies.inGroup(bob.id));
    assertTrue(lobbies.inGroup(alice.id));
    assertEquals(2, lobbies.lobbyOf(alice.id).size());
  }

  @Test
  void accept_noInvite_returnsNoInvite() {
    FakePlayer bob = player("Bob");
    assertEquals(LobbyResult.NO_INVITE, lobbies.accept(bob, UUID.randomUUID()));
  }

  @Test
  void accept_ambiguousWithMultipleInvites() {
    FakePlayer alice = player("Alice");
    FakePlayer charlie = player("Charlie");
    FakePlayer bob = player("Bob");
    lobbies.invite(alice, bob);
    lobbies.invite(charlie, bob);
    assertEquals(LobbyResult.AMBIGUOUS, lobbies.accept(bob, null));
  }

  @Test
  void invite_targetAlreadyInGroup() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    FakePlayer charlie = player("Charlie");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    assertEquals(LobbyResult.TARGET_IN_PARTY, lobbies.invite(charlie, bob));
  }

  @Test
  void notLeader_cannotInvite() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    FakePlayer charlie = player("Charlie");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    assertEquals(LobbyResult.NOT_LEADER, lobbies.invite(bob, charlie));
  }

  @Test
  void leave_memberLeavesGroup() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    assertEquals(LobbyResult.LEFT, lobbies.leave(bob));
    assertFalse(lobbies.inGroup(bob.id));
    assertEquals(1, lobbies.lobbyOf(alice.id).size());
  }

  @Test
  void leave_hostTransfersToOldestRemaining() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    FakePlayer carol = player("Carol");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    lobbies.invite(alice, carol);
    lobbies.accept(carol, alice.id);

    assertEquals(LobbyResult.LEFT, lobbies.leave(alice));
    assertNull(lobbies.lobbyOf(alice.id));
    Lobby lobby = lobbies.lobbyOf(bob.id);
    assertNotNull(lobby);
    assertEquals(bob.id, lobby.leader(), "oldest remaining member becomes host");
    assertEquals(2, lobby.size());
  }

  @Test
  void kick_removesTarget_butNotSelf() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    assertFalse(lobbies.kick(alice.id, alice.id));
    assertTrue(lobbies.kick(alice.id, bob.id));
    assertFalse(lobbies.inGroup(bob.id));
  }

  @Test
  void disband_removesEveryone_leaderOnly() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.invite(alice, bob);
    lobbies.accept(bob, alice.id);
    assertEquals(LobbyResult.NOT_LEADER, lobbies.disband(bob.id));
    assertEquals(LobbyResult.DISBANDED, lobbies.disband(alice.id));
    assertNull(lobbies.lobbyOf(alice.id));
    assertNull(lobbies.lobbyOf(bob.id));
  }

  @Test
  void declineInvite_blocksAccept() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.invite(alice, bob);
    assertTrue(lobbies.declineInvite(bob.id, alice.id));
    assertEquals(LobbyResult.NO_INVITE, lobbies.accept(bob, alice.id));
  }

  @Test
  void maxSize_defaultsToEight() {
    assertEquals(8, lobbies.maxSize());
  }

  // ----- quick-play queue (was MatchmakingManagerTest) ------------------------------------------

  @Test
  void queue_unknownMode_returnsNotMinigame() {
    FakePlayer alice = player("Alice");
    assertEquals(LobbyResult.NOT_MINIGAME, lobbies.queue(alice, "not-a-mode"));
    assertFalse(lobbies.isQueued(alice.id));
  }

  @Test
  void queue_soloBelowMin_waitsWithoutLaunching() {
    FakePlayer alice = player("Alice");
    assertEquals(LobbyResult.QUEUED, lobbies.queue(alice, DUEL));
    assertTrue(lobbies.isQueued(alice.id));
    assertEquals(DUEL, lobbies.queuedMode(alice.id));
    assertEquals(1, lobbies.queueSize(DUEL));
    assertEquals(-1, lobbies.countdownSeconds(DUEL));
    lobbies.tick();
    lobbies.tick();
    assertEquals(0, launcher.startCalls, "never launches below the minimum");
  }

  @Test
  void queue_alreadyQueued() {
    FakePlayer alice = player("Alice");
    lobbies.queue(alice, DUEL);
    assertEquals(LobbyResult.ALREADY_QUEUED, lobbies.queue(alice, DUEL));
  }

  @Test
  void queue_whileInMatch_returnsAlreadyInMatch() {
    FakePlayer alice = player("Alice");
    launcher.matches.put(alice.id, new ActiveMatch(UUID.randomUUID(), DUEL, List.of(), null, null));
    assertEquals(LobbyResult.ALREADY_IN_MATCH, lobbies.queue(alice, DUEL));
  }

  @Test
  void queue_isLeaderGated_memberCannotQueueGroup() {
    FakePlayer leader = player("Leader");
    FakePlayer member = player("Member");
    lobbies.invite(leader, member);
    lobbies.accept(member, leader.id);
    assertEquals(LobbyResult.NOT_LEADER, lobbies.queue(member, DUEL));
    assertFalse(lobbies.isQueued(leader.id));
  }

  @Test
  void queue_leaderQueuesWholeGroup() {
    FakePlayer leader = player("Leader");
    FakePlayer member = player("Member");
    lobbies.invite(leader, member);
    lobbies.accept(member, leader.id);
    assertEquals(LobbyResult.QUEUED, lobbies.queue(leader, DUEL));
    assertTrue(lobbies.isQueued(leader.id));
    assertTrue(lobbies.isQueued(member.id), "the whole roster is one ticket");
    assertEquals(2, lobbies.queueSize(DUEL));
  }

  @Test
  void reachingMinimum_startsCountdown() {
    lobbies.queue(player("Alice"), DUEL);
    lobbies.queue(player("Bob"), DUEL);
    assertEquals(5, lobbies.countdownSeconds(DUEL));
    assertEquals(0, launcher.startCalls);
  }

  @Test
  void countdownElapses_launchesGroupsAsOpposingTeams_andLobbiesReturnToIdle() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.queue(alice, DUEL);
    lobbies.queue(bob, DUEL);
    for (int tick = 0; tick < 10 && launcher.startCalls == 0; tick++) {
      lobbies.tick();
    }
    assertEquals(1, launcher.startCalls);
    assertEquals(DUEL, launcher.startedMode);
    // Party-aware matchmaking: two separate (solo) groups are matched as two opposing teams, each group
    // kept intact, instead of the old flat free-for-all.
    assertTrue(launcher.startedArgs.contains("teams:2"), launcher.startedArgs.toString());
    int aliceTeam = teamFor(launcher.startedArgs, alice.id);
    int bobTeam = teamFor(launcher.startedArgs, bob.id);
    assertTrue(aliceTeam >= 0 && bobTeam >= 0, "every queued player gets a team assignment");
    assertTrue(aliceTeam != bobTeam, "two solo groups land on opposing teams");
    assertTrue(launcher.startedRoster.contains(alice.id));
    assertTrue(launcher.startedRoster.contains(bob.id));
    assertFalse(lobbies.isQueued(alice.id), "launched players leave the queue");
    assertEquals(0, lobbies.queueSize(DUEL));
    // Play-again: the solo lobbies survive (return to IDLE), they are not destroyed.
    assertNotNull(lobbies.lobbyOf(alice.id));
    assertTrue(lobbies.lobbyOf(alice.id).isIdle());
  }

  /** The team index a launch's mode args assigned to a player, or -1 when none. */
  private static int teamFor(List<String> args, UUID playerId) {
    for (String arg : args) {
      if (arg.startsWith("assign:" + playerId + ":")) {
        return Integer.parseInt(arg.substring(arg.lastIndexOf(':') + 1));
      }
    }
    return -1;
  }

  @Test
  void dequeue_dropsLobby_andCancelsCountdown() {
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.queue(alice, DUEL);
    lobbies.queue(bob, DUEL);
    assertEquals(5, lobbies.countdownSeconds(DUEL));
    assertEquals(LobbyResult.DEQUEUED, lobbies.dequeue(bob));
    assertFalse(lobbies.isQueued(bob.id));
    assertEquals(1, lobbies.queueSize(DUEL));
    assertEquals(-1, lobbies.countdownSeconds(DUEL), "dropping below the minimum cancels the countdown");
  }

  @Test
  void tick_prunesOfflineLobby() {
    FakePlayer alice = player("Alice");
    lobbies.queue(alice, DUEL);
    alice.online = false;
    lobbies.tick();
    assertFalse(lobbies.isQueued(alice.id));
    assertEquals(0, lobbies.queueSize(DUEL));
  }

  @Test
  void failedLaunch_keepsLobbiesQueuedForRetry() {
    launcher.launchSucceeds = false;
    FakePlayer alice = player("Alice");
    FakePlayer bob = player("Bob");
    lobbies.queue(alice, DUEL);
    lobbies.queue(bob, DUEL);
    for (int tick = 0; tick < 10 && launcher.startCalls == 0; tick++) {
      lobbies.tick();
    }
    assertEquals(1, launcher.startCalls);
    assertTrue(lobbies.isQueued(alice.id));
    assertTrue(lobbies.isQueued(bob.id));
    assertEquals(2, lobbies.queueSize(DUEL));
  }

  // ----- host match (was MatchLobbyManagerTest) -------------------------------------------------

  @Test
  void configure_whileQueued_leavesTheQueue() {
    FakePlayer host = player("Host");
    assertEquals(LobbyResult.QUEUED, lobbies.queue(host, DUEL));
    assertTrue(lobbies.isQueued(host.id));
    assertEquals(LobbyResult.CONFIGURED, lobbies.configure(host, DUEL));
    assertFalse(lobbies.isQueued(host.id));
    assertEquals(0, lobbies.queueSize(DUEL));
    assertTrue(lobbies.lobbyOf(host.id).isConfigured());
  }

  @Test
  void join_pullsPlayerFromTheirOwnQueueIntoHostLobby() {
    FakePlayer host = player("Host");
    FakePlayer guest = player("Guest");
    lobbies.configure(host, DUEL);
    lobbies.queue(guest, DUEL);

    assertEquals(LobbyResult.JOINED, lobbies.join(guest, host.id));
    assertFalse(lobbies.isQueued(guest.id));
    Lobby lobby = lobbies.lobbyOf(guest.id);
    assertNotNull(lobby);
    assertTrue(lobby.contains(guest.id));
    assertEquals(host.id, lobby.leader());
  }

  @Test
  void join_inviteOnly_deniesUninvited() {
    FakePlayer host = player("Host");
    FakePlayer guest = player("Guest");
    lobbies.configure(host, DUEL);
    lobbies.setVisibility(host, LobbyVisibility.INVITE_ONLY);
    assertEquals(LobbyResult.NOT_INVITED, lobbies.join(guest, host.id));
    lobbies.inviteToLobby(host, guest.id);
    assertEquals(LobbyResult.JOINED, lobbies.join(guest, host.id));
  }

  @Test
  void start_teamRace_requiresAtLeastOnePlayerPerTeam() {
    FakePlayer host = player("Host");
    assertEquals(LobbyResult.CONFIGURED, lobbies.configure(host, RACE));
    assertEquals(LobbyResult.OK, lobbies.setTeamCount(host, 2));
    assertEquals(LobbyResult.TOO_FEW, lobbies.start(host));
    Lobby lobby = lobbies.lobbyOf(host.id);
    assertNotNull(lobby);
    assertEquals(2, lobby.requiredPlayersForStart());
    assertEquals(1, lobby.size());
  }

  @Test
  void start_launchesAndReturnsLobbyToIdle() {
    FakePlayer host = player("Host");
    FakePlayer member = player("Member");
    lobbies.invite(host, member);
    lobbies.accept(member, host.id);
    assertEquals(LobbyResult.CONFIGURED, lobbies.configure(host, DUEL));
    assertEquals(LobbyResult.STARTED, lobbies.start(host));
    assertEquals(1, launcher.startCalls);
    Lobby lobby = lobbies.lobbyOf(host.id);
    assertNotNull(lobby, "play-again: lobby survives the match");
    assertTrue(lobby.isIdle());
    assertEquals(2, lobby.size(), "the roster stays together");
  }

  // ----- quit cleanup (new) ---------------------------------------------------------------------

  @Test
  void onPlayerQuit_removesGhostMember_andTransfersHost() {
    FakePlayer host = player("Host");
    FakePlayer member = player("Member");
    lobbies.invite(host, member);
    lobbies.accept(member, host.id);

    lobbies.onPlayerQuit(host.id);
    assertNull(lobbies.lobbyOf(host.id));
    Lobby lobby = lobbies.lobbyOf(member.id);
    assertNotNull(lobby);
    assertEquals(member.id, lobby.leader());
    assertEquals(1, lobby.size());
  }

  @Test
  void onPlayerQuit_soloPlayer_removesLobby() {
    FakePlayer alice = player("Alice");
    lobbies.queue(alice, DUEL);
    lobbies.onPlayerQuit(alice.id);
    assertNull(lobbies.lobbyOf(alice.id));
    assertEquals(0, lobbies.queueSize(DUEL));
  }

  // ----- fakes ----------------------------------------------------------------------------------

  private ServerAdapter server() {
    return new ServerAdapter() {
      @Override public String serverName() { return "Test"; }
      @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
      @Override public java.nio.file.Path dataDirectory() { return java.nio.file.Path.of("."); }
      @Override public com.sexidium.core.platform.ConfigurationAdapter configuration() { return new PropertiesConfigurationAdapter(); }
      @Override public com.sexidium.core.platform.LoggerAdapter logger() { return new StdoutLoggerAdapter("Test"); }
      @Override public com.sexidium.core.platform.ResourceAdapter resources() { return new ClassLoaderResourceAdapter(null); }
      @Override public com.sexidium.core.platform.SchedulerAdapter scheduler() { return new DirectSchedulerAdapter(); }
      @Override public com.sexidium.core.platform.UiAdapter ui() { return new NoopUiAdapter(); }
      @Override public com.sexidium.core.platform.MessageAdapter messages() { return null; }
      @Override public com.sexidium.core.platform.EventDispatcherAdapter events() { return new NoopEventDispatcherAdapter(); }
      @Override public com.sexidium.core.platform.CommandDispatcherAdapter commands() { return new NoopCommandDispatcherAdapter(); }
      @Override public com.sexidium.core.platform.WorldLeaseService worlds() { return new NoopWorldLeaseService(); }
      @Override public CommandSource console() { return null; }
      @Override public Collection<PlayerAdapter> onlinePlayers() { return new ArrayList<>(playerMap.values()); }
      @Override public Optional<PlayerAdapter> player(UUID id) {
        FakePlayer fake = playerMap.get(id);
        return fake != null && fake.online ? Optional.of(fake) : Optional.empty();
      }
      @Override public Optional<PlayerAdapter> playerExact(String name) {
        for (FakePlayer fake : playerMap.values()) {
          if (fake.online && fake.name.equalsIgnoreCase(name)) {
            return Optional.of(fake);
          }
        }
        return Optional.empty();
      }
    };
  }

  private static final class FakeLauncher implements MatchLauncher {
    private final Map<UUID, ActiveMatch> matches = new HashMap<>();
    private boolean launchSucceeds = true;
    private int startCalls;
    private String startedMode;
    private List<UUID> startedRoster = List.of();
    private List<String> startedArgs = List.of();

    @Override
    public List<GameModeDescriptor> descriptors() {
      return List.of(
          new GameModeDescriptor(DUEL, CoreGameRegistryInitializer.CATEGORY_MINIGAMES, "Duel", 2, List.of("d")),
          new GameModeDescriptor(RACE, CoreGameRegistryInitializer.CATEGORY_MINIGAMES, "Race for Item", 1, List.of()));
    }

    @Override
    public ActiveMatch matchOf(UUID playerId) {
      return matches.get(playerId);
    }

    @Override
    public boolean startWithPlayers(String modeId, Collection<UUID> participantIds, CommandSource initiator, List<String> modeArgs) {
      startCalls++;
      startedMode = modeId;
      startedRoster = new ArrayList<>(participantIds);
      startedArgs = new ArrayList<>(modeArgs);
      return launchSucceeds;
    }
  }

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id;
    private final String name;
    private boolean online = true;

    private FakePlayer(UUID id, String name) {
      this.id = id;
      this.name = name;
    }

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return online; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
  }
}
