package com.sexidium.core.command;

import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCommandServiceAdvancedTest {

  private Path tempDir;
  private Database database;
  private CommandTestFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("sexidium-cmd-test");
    database = new Database(tempDir.resolve("sexidium.db").toFile());
    fixture = CommandTestFixture.create(tempDir, database, true);
  }

  @AfterEach
  void tearDown() throws Exception {
    if (fixture != null) fixture.close();
    if (database != null) database.close();
    if (tempDir != null) {
      Files.walk(tempDir)
          .sorted((left, right) -> right.compareTo(left))
          .forEach(path -> {
            try { Files.deleteIfExists(path); } catch (IOException ignored) {}
          });
    }
  }

  // --- help / default ---

  @Test
  void execute_emptyArgs_runsHelp() {
    fixture.commands.execute(fixture.admin("Admin"), new String[]{});
    assertTrue(fixture.messages.sent.size() > 0);
  }

  @Test
  void execute_questionMarkAlias_runsHelp() {
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"?"});
    assertTrue(fixture.messages.sent.size() > 0);
  }

  @Test
  void execute_unknownSubcommand_runsHelp() {
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"definitelyNotACommand"});
    assertTrue(fixture.messages.sent.size() > 0);
  }

  // --- start ---

  @Test
  void start_withoutModeId_sendsUsage() {
    fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"start"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void start_withUnknownCategory_sendsUnknownCategory() {
    fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"start", "wat", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void start_withCategoryMismatch_sendsUnknownCategory() {
    fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"start", "experience", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void start_withNoOnlinePlayers_sendsNoPlayers() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"start", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void start_withOfflineRoster_sendsNoPlayers() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"start", "combat", "GhostOne", "GhostTwo"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- stop ---

  @Test
  void stop_whenNoGameRunning_sendsNotRunning() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "stop"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- kit (now under /sx admin kit) ---

  @Test
  void kit_withoutSubcommand_sendsList() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_list_sendsList() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit", "list"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_unknownSubcommand_sendsUsage() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit", "weird"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_give_withoutArguments_sendsUsage() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit", "give"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_give_unknownKit_sendsUnknown() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit", "give", "nonexistentKit", "Admin"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_give_toOfflinePlayer_sendsOffline() {
    int before = fixture.messages.sent.size();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "kit", "give", "pvp", "Ghost"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void kit_give_fromConsoleWithoutTarget_sendsConsoleTarget() {
    CommandTestFixture empty = CommandTestFixture.createEmpty(tempDir, false);
    try {
      FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
      int before = empty.messages.sent.size();
      empty.commands.execute(console, new String[]{"admin", "kit", "give", "anything"});
      assertTrue(empty.messages.sent.size() > before);
    } finally {
      empty.close();
    }
  }

  // --- exit/leave ---

  @Test
  void exit_fromConsole_sendsPlayerOnly() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"exit"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void exit_whenNotInGame_sendsNotInGame() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"leave"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- top (need ranks service with auth; skip without db) ---

  @Test
  void top_withoutRanksService_sendsUnavailable() {
    CommandTestFixture empty = CommandTestFixture.createEmpty(tempDir, true);
    try {
      int before = empty.messages.sent.size();
      empty.commands.execute(empty.admin("Admin"), new String[]{"top"});
      assertTrue(empty.messages.sent.size() > before);
    } finally {
      empty.close();
    }
  }

  // --- rank (need ranks service; skip without db) ---

  @Test
  void rank_withoutRanksService_sendsUnavailable() {
    CommandTestFixture empty = CommandTestFixture.createEmpty(tempDir, true);
    try {
      int before = empty.messages.sent.size();
      empty.commands.execute(empty.admin("Admin"), new String[]{"rank", "Nobody"});
      assertTrue(empty.messages.sent.size() > before);
    } finally {
      empty.close();
    }
  }

  // --- auth (need AuthService + database) ---

  @Test
  void auth_consoleSource_sendsPlayerOnly() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"auth"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void auth_player_sendsCode() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.AUTH_PERMISSION, CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"auth"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- lobby (roster ops) ---

  @Test
  void lobby_withoutSubcommand_opensGuiSilently() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    // Bare "/sx lobby" opens the unified lobby GUI for a player — no usage/error message.
    fixture.commands.execute(alice, new String[]{"lobby"});
    assertEquals(before, fixture.messages.sent.size());
  }

  @Test
  void lobby_console_sendsPlayerOnly() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"lobby", "invite", "Alice"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_invite_sendsInviteMessages() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Bob"});
    assertTrue(fixture.messages.sent.size() - before >= 2);
  }

  @Test
  void lobby_inviteSelf_sendsSelf() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Alice"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_inviteOfflineTarget_sendsNotFound() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Ghost"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_accept_joinAndBroadcast() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    FakePlayer bob = fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Bob"});
    int before = fixture.messages.sent.size();
    fixture.commands.execute(bob, new String[]{"lobby", "accept"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_acceptNoInvite_sendsNoInvite() {
    FakePlayer bob = fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(bob, new String[]{"lobby", "accept"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_leave_sendsLeft() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    FakePlayer bob = fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Bob"});
    fixture.commands.execute(bob, new String[]{"lobby", "accept"});
    int before = fixture.messages.sent.size();
    fixture.commands.execute(bob, new String[]{"lobby", "leave"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_leaveNotInParty_sendsNotInParty() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "leave"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_kick_sendsSuccess() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    FakePlayer bob = fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    fixture.commands.execute(alice, new String[]{"lobby", "invite", "Bob"});
    fixture.commands.execute(bob, new String[]{"lobby", "accept"});
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "kick", "Bob"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_list_sendsHeader() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "list"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_disbandByLeader_sendsDisbanded() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "disband"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void lobby_unknownSubcommand_sendsUsage() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"lobby", "explode"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- join ---

  @Test
  void join_withoutModeId_sendsUsage() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"join"});
    assertTrue(fixture.messages.sent.size() > before);
    boolean foundUsage = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.usage"));
    assertTrue(foundUsage, "expected COMMAND_JOIN_USAGE in messages");
  }

  @Test
  void join_withBlankModeId_sendsUsage() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"join", "   "});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void join_fromConsole_sendsPlayerOnly() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"join", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void join_whenNoMatchRunning_sendsNotRunning() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"join", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
    boolean found = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.not-running"));
    assertTrue(found, "expected COMMAND_JOIN_NOT_RUNNING in messages");
  }

  @Test
  void join_whenMatchRunning_sendsSuccess() {
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    FakePlayer joiner = fixture.player("Joiner", CoreCommandService.PLAY_PERMISSION);
    // The joiner may only enter a match where a party member / friend is playing.
    fixture.core.lobbies().invite(host, joiner);
    fixture.core.lobbies().accept(joiner, host.uniqueId());
    boolean started = fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));
    assertTrue(started, "match should have started");
    assertTrue(fixture.core.games().isRunning());

    int before = fixture.messages.sent.size();
    fixture.commands.execute(joiner, new String[]{"join", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
    boolean found = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.success"));
    assertTrue(found, "expected COMMAND_JOIN_SUCCESS in messages");
    assertTrue(fixture.core.games().matchOf(joiner) != null,
        "joiner should be tracked in match after join");
  }

  @Test
  void join_whenNotRelated_sendsNotAllowed() {
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    FakePlayer stranger = fixture.player("Stranger", CoreCommandService.PLAY_PERMISSION);
    fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));

    fixture.commands.execute(stranger, new String[]{"join", "combat"});
    boolean denied = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.not-allowed"));
    assertTrue(denied, "a stranger with no party/friend in the match must be denied");
    assertTrue(fixture.core.games().matchOf(stranger) == null, "stranger must not be admitted");
  }

  @Test
  void join_whenPlayerAlreadyInMatch_sendsAlreadyInMatch() {
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    boolean started = fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));
    assertTrue(started);

    int before = fixture.messages.sent.size();
    fixture.commands.execute(host, new String[]{"join", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
    boolean found = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.already-in-match"));
    assertTrue(found, "expected COMMAND_JOIN_ALREADY_IN_MATCH in messages");
  }

  @Test
  void join_whenPlayerOffline_sendsOfflineMessage() {
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    boolean started = fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));
    assertTrue(started);

    OfflineFakePlayer ghost = new OfflineFakePlayer("Ghost", Set.of(CoreCommandService.PLAY_PERMISSION));

    int before = fixture.messages.sent.size();
    fixture.commands.execute(ghost, new String[]{"join", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void join_normalisesModeIdToLowerCase() {
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    FakePlayer joiner = fixture.player("Joiner", CoreCommandService.PLAY_PERMISSION);
    fixture.core.lobbies().invite(host, joiner);
    fixture.core.lobbies().accept(joiner, host.uniqueId());
    fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));

    fixture.commands.execute(joiner, new String[]{"join", "COMBAT"});
    boolean found = fixture.messages.sent.stream()
        .anyMatch(path -> path.contains("command.join.success"));
    assertTrue(found, "uppercase mode id should still join when 'combat' is running");
  }

  @Test
  void suggestJoin_includesRunningModeIds() {
    assertTrue(fixture.core.games().runningModeIds().isEmpty());
    FakePlayer host = fixture.player("Host", CoreCommandService.PLAY_PERMISSION);
    fixture.core.games().start("combat", List.of(host), fixture.admin("Admin"));
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"join", ""});
    assertTrue(suggestions.contains("combat"),
        "expected 'combat' in join suggestions when that mode is running");
  }

  @Test
  void suggestJoin_emptyWhenNoMatchesRunning() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"join", ""});
    assertTrue(suggestions.isEmpty(),
        "no running modes → no join suggestions");
  }

  // --- friend ---

  @Test
  void friend_withoutSubcommand_sendsUsage() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"friend"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void friend_console_sendsPlayerOnly() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"friend", "add", "Alice"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void friend_unknownSubcommand_sendsUsage() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(alice, new String[]{"friend", "frob"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  // --- reload ---

  @Test
  void reload_invokesCallback() {
    int before = fixture.reloadCount.get();
    fixture.commands.execute(fixture.admin("Admin"), new String[]{"admin", "reload"});
    assertEquals(before + 1, fixture.reloadCount.get());
  }

  // --- suggestions ---

  @Test
  void suggestStart_offersOnlyCategoriesAtFirstPosition() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"start", ""});
    assertTrue(suggestions.contains("minigames"));
    assertTrue(suggestions.contains("experience"));
    assertFalse(suggestions.contains("combat"), "mode ids should only appear after a category is chosen");
    assertFalse(suggestions.contains("gravity"), "mode ids should only appear after a category is chosen");
  }

  @Test
  void suggestStart_filtersByCategory() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"start", "minigames", ""});
    assertTrue(suggestions.contains("combat"));
    assertFalse(suggestions.contains("gravity"));
  }

  @Test
  void suggestKit_listAndGive() {
    assertEquals(List.of("list", "give"),
        fixture.commands.suggest(fixture.admin("Admin"), new String[]{"admin", "kit", ""}));
  }

  @Test
  void suggestKit_give_kitsAndPlayerNames() {
    fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"admin", "kit", "give", ""});
    assertTrue(suggestions.contains("pvp"));
    assertTrue(suggestions.contains("builder"));
    assertTrue(suggestions.contains("Bob"));
  }

  @Test
  void suggestLobby_includesUnifiedSubcommands() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"lobby", ""});
    // Roster ops (formerly /sx party) + host/queue ops are now all under /sx lobby.
    assertTrue(suggestions.contains("invite"));
    assertTrue(suggestions.contains("accept"));
    assertTrue(suggestions.contains("leave"));
    assertTrue(suggestions.contains("kick"));
    assertTrue(suggestions.contains("disband"));
    assertTrue(suggestions.contains("mode"));
    assertTrue(suggestions.contains("teams"));
    assertTrue(suggestions.contains("queue"));
    assertTrue(suggestions.contains("start"));
    assertTrue(suggestions.contains("visibility"));
  }

  @Test
  void suggestLobby_invite_returnsPlayerNames() {
    FakePlayer alice = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    List<String> suggestions = fixture.commands.suggest(alice, new String[]{"lobby", "invite", ""});
    assertTrue(suggestions.contains("Bob"));
  }

  @Test
  void suggestFriend_includesAllSubcommands() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"friend", ""});
    assertTrue(suggestions.contains("add"));
    assertTrue(suggestions.contains("accept"));
    assertTrue(suggestions.contains("remove"));
    assertTrue(suggestions.contains("list"));
    assertTrue(suggestions.contains("requests"));
  }

  @Test
  void suggestBot_includesAllActions() {
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"admin", "bot", ""});
    assertTrue(suggestions.contains("status"));
    assertTrue(suggestions.contains("start"));
    assertTrue(suggestions.contains("stop"));
    assertTrue(suggestions.contains("restart"));
    assertTrue(suggestions.contains("logs"));
    assertTrue(suggestions.contains("reload"));
    assertTrue(suggestions.contains("config"));
  }

  @Test
  void suggestRank_returnsPlayerNames() {
    fixture.player("Bob", CoreCommandService.PLAY_PERMISSION);
    List<String> suggestions = fixture.commands.suggest(fixture.admin("Admin"), new String[]{"rank", ""});
    assertTrue(suggestions.contains("Bob"));
  }

  // --- permission gating on dispatch ---

  @Test
  void dispatch_deniesAdminSubcommandToPlayer() {
    FakePlayer player = fixture.player("Alice", CoreCommandService.PLAY_PERMISSION);
    int before = fixture.messages.sent.size();
    fixture.commands.execute(player, new String[]{"start", "combat"});
    assertTrue(fixture.messages.sent.size() > before);
  }

  @Test
  void dispatch_deniesPlayerSubcommandToConsole() {
    FakeSource console = new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION));
    int before = fixture.messages.sent.size();
    fixture.commands.execute(console, new String[]{"exit"});
    assertTrue(fixture.messages.sent.size() > before);
  }
}
