package com.sexidium.core.command;

import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.economy.Money;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.CommandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /pay}, {@code /balance}, {@code /baltop} and {@code /sx admin eco} through the real
 * dispatcher, against a real database.
 *
 * <p>The permission assertions are the ones that matter most here. Money is the one subsystem where a
 * missing gate is not a cosmetic bug: {@code /sx admin eco give} reachable by anybody with
 * {@code sexidium.play} is an infinite money command.</p>
 */
class EconomyCommandsTest {

  private Path tempDir;
  private Database database;
  private CommandTestFixture fixture;

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("sexidium-economy-command-test");
    database = new Database(tempDir.resolve("sexidium.db").toFile());
    fixture = CommandTestFixture.create(tempDir, database, true);
  }

  @AfterEach
  void tearDown() {
    if (fixture != null) {
      fixture.close();
    }
    if (database != null) {
      database.close();
    }
  }

  /**
   * A player who may pay. {@code sexidium.economy.pay} defaults to true in plugin.yml, so on a real
   * server everybody has it — but the fake source is exact set membership, so it has to be granted
   * here or every /pay test would be testing the permission gate instead of the command.
   */
  private FakePlayer payer(String name) {
    return fixture.player(name, EconomyCommands.PAY_PERMISSION);
  }

  /**
   * A console-shaped source. Given both play and admin because a real Bukkit console answers true to
   * every permission; {@code FakeServerAdapter.console()} holds admin alone, which would have these
   * tests failing at the root gate rather than at the thing they are about.
   */
  private CommandSource console() {
    return new FakeSource("Console", java.util.Set.of(
        CoreCommandService.PLAY_PERMISSION,
        CoreCommandService.ADMIN_PERMISSION,
        EconomyCommands.PAY_PERMISSION,
        EconomyCommands.BALANCE_OTHERS_PERMISSION));
  }

  private EconomyService economy() {
    return fixture.core.economy();
  }

  private String lastMessage() {
    List<String> sent = fixture.messages.sent;
    return sent.isEmpty() ? "" : sent.get(sent.size() - 1);
  }

  private boolean sent(String path) {
    return fixture.messages.sent.contains(path);
  }

  // ----- /pay --------------------------------------------------------------------------------------

  @Test
  void pay_withNoArguments_showsTheUsage() {
    FakePlayer alice = payer("Alice");
    fixture.commands.execute(alice, new String[] {"pay"});
    assertEquals("economy.pay.usage", lastMessage());
  }

  @Test
  @DisplayName("/pay from the console is player-only, not a usage line")
  void pay_fromConsole_isPlayerOnly() {
    fixture.commands.execute(console(), new String[] {"pay", "Alice", "1.00"});
    assertTrue(sent("command.player-only"));
  }

  @Test
  void pay_toYourself_isRefused() {
    FakePlayer alice = payer("Alice");
    fixture.commands.execute(alice, new String[] {"pay", "Alice", "1.00"});
    assertEquals("economy.pay.self", lastMessage());
  }

  @Test
  void pay_withAnUnparsableAmount_saysSoRatherThanThrowing() {
    FakePlayer alice = payer("Alice");
    fixture.player("Bob");
    fixture.commands.execute(alice, new String[] {"pay", "Bob", "not-a-number"});
    assertEquals("economy.invalid-amount", lastMessage());
  }

  @Test
  void pay_moreThanYouHave_saysInsufficientAndMovesNothing() {
    FakePlayer alice = payer("Alice");
    FakePlayer bob = fixture.player("Bob");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    economy().ensureAccount(bob.uniqueId(), "Bob", true);
    fixture.commands.execute(alice, new String[] {"pay", "Bob", "1000.00"});
    assertEquals("economy.insufficient-funds", lastMessage());
    assertEquals(Money.parse("100.00").orElseThrow(), economy().balance(alice.uniqueId()));
    assertEquals(Money.parse("100.00").orElseThrow(), economy().balance(bob.uniqueId()));
  }

  @Test
  @DisplayName("a successful /pay tells BOTH players and moves exactly the amount")
  void pay_happyPath() {
    FakePlayer alice = payer("Alice");
    FakePlayer bob = fixture.player("Bob");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    economy().ensureAccount(bob.uniqueId(), "Bob", true);
    fixture.commands.execute(alice, new String[] {"pay", "Bob", "25.00"});
    assertTrue(sent("economy.pay.sent"));
    assertTrue(sent("economy.pay.received"));
    assertEquals(Money.parse("75.00").orElseThrow(), economy().balance(alice.uniqueId()));
    assertEquals(Money.parse("125.00").orElseThrow(), economy().balance(bob.uniqueId()));
  }

  @Test
  void pay_toAnUnknownName_saysPlayerNotFound() {
    FakePlayer alice = payer("Alice");
    fixture.commands.execute(alice, new String[] {"pay", "Nobody", "1.00"});
    assertEquals("command.player-not-found", lastMessage());
  }

  @Test
  @DisplayName("/pay reaches an OFFLINE player through the account table")
  void pay_offlineTargetResolvesByName() {
    FakePlayer alice = payer("Alice");
    java.util.UUID bob = java.util.UUID.randomUUID();
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    economy().ensureAccount(bob, "Bob", true);
    fixture.commands.execute(alice, new String[] {"pay", "Bob", "10.00"});
    assertTrue(sent("economy.pay.sent"));
    assertEquals(Money.parse("110.00").orElseThrow(), economy().balance(bob));
  }

  @Test
  @DisplayName("/pay without sexidium.economy.pay is refused")
  void pay_needsThePayPermission() {
    FakePlayer alice = fixture.player("Alice");
    FakePlayer bob = fixture.player("Bob");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    economy().ensureAccount(bob.uniqueId(), "Bob", true);
    fixture.commands.execute(alice, new String[] {"pay", "Bob", "1.00"});
    assertEquals("command.no-permission", lastMessage());
    assertEquals(Money.parse("100.00").orElseThrow(), economy().balance(bob.uniqueId()));
  }

  // ----- /balance ----------------------------------------------------------------------------------

  @Test
  void balance_withNoArgument_showsYourOwn() {
    FakePlayer alice = fixture.player("Alice");
    fixture.commands.execute(alice, new String[] {"balance"});
    assertEquals("economy.balance.self", lastMessage());
  }

  @Test
  @DisplayName("/balance <other> without the permission is refused, aliases included")
  void balance_ofAnotherPlayer_needsThePermission() {
    FakePlayer alice = fixture.player("Alice");
    fixture.player("Bob");
    fixture.commands.execute(alice, new String[] {"balance", "Bob"});
    assertEquals("command.no-permission", lastMessage());
    fixture.commands.execute(alice, new String[] {"bal", "Bob"});
    assertEquals("command.no-permission", lastMessage());
    fixture.commands.execute(alice, new String[] {"money", "Bob"});
    assertEquals("command.no-permission", lastMessage());
  }

  @Test
  void balance_ofAnotherPlayer_withThePermission() {
    FakePlayer alice = fixture.player("Alice", "sexidium.economy.balance.others");
    fixture.player("Bob");
    fixture.commands.execute(alice, new String[] {"balance", "Bob"});
    assertEquals("economy.balance.other", lastMessage());
  }

  @Test
  @DisplayName("/balance <player> works from the console")
  void balance_ofAnotherPlayer_fromConsole() {
    fixture.player("Bob");
    fixture.commands.execute(console(), new String[] {"balance", "Bob"});
    assertEquals("economy.balance.other", lastMessage());
  }

  // ----- /baltop -----------------------------------------------------------------------------------

  @Test
  void baltop_withNoAccounts_saysSo() {
    FakePlayer alice = fixture.player("Alice");
    fixture.commands.execute(alice, new String[] {"baltop"});
    assertEquals("economy.baltop.empty", lastMessage());
  }

  @Test
  void baltop_listsAccountsRichestFirst() {
    FakePlayer alice = fixture.player("Alice");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    fixture.commands.execute(alice, new String[] {"baltop"});
    assertTrue(sent("economy.baltop.title"));
    assertTrue(sent("economy.baltop.row"));
  }

  // ----- /sx admin eco -----------------------------------------------------------------------------

  @Test
  @DisplayName("/sx admin eco as a plain player is refused — this one is an infinite money command")
  void adminEco_needsAdmin() {
    FakePlayer alice = fixture.player("Alice");
    fixture.commands.execute(alice, new String[] {"admin", "eco", "give", "Alice", "1000000.00"});
    assertEquals("command.no-permission", lastMessage());
    assertEquals(Money.parse("100.00").orElseThrow(), economy().balance(alice.uniqueId()));
  }

  @Test
  void adminEco_giveTakeSetReset() {
    FakePlayer admin = fixture.admin("Root");
    FakePlayer alice = fixture.player("Alice");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);

    fixture.commands.execute(admin, new String[] {"admin", "eco", "give", "Alice", "50.00"});
    assertTrue(sent("economy.admin.given"));
    assertEquals(Money.parse("150.00").orElseThrow(), economy().balance(alice.uniqueId()));

    fixture.commands.execute(admin, new String[] {"admin", "eco", "take", "Alice", "20.00"});
    assertTrue(sent("economy.admin.taken"));
    assertEquals(Money.parse("130.00").orElseThrow(), economy().balance(alice.uniqueId()));

    fixture.commands.execute(admin, new String[] {"admin", "eco", "set", "Alice", "5.00"});
    assertTrue(sent("economy.admin.set"));
    assertEquals(Money.parse("5.00").orElseThrow(), economy().balance(alice.uniqueId()));

    fixture.commands.execute(admin, new String[] {"admin", "eco", "reset", "Alice"});
    assertTrue(sent("economy.admin.reset"));
    assertEquals(Money.parse("100.00").orElseThrow(), economy().balance(alice.uniqueId()));
  }

  @Test
  void adminEco_balanceAndTop() {
    FakePlayer admin = fixture.admin("Root");
    FakePlayer alice = fixture.player("Alice");
    economy().ensureAccount(alice.uniqueId(), "Alice", true);
    fixture.commands.execute(admin, new String[] {"admin", "eco", "balance", "Alice"});
    assertTrue(sent("economy.balance.other"));
    fixture.commands.execute(admin, new String[] {"admin", "eco", "top"});
    assertTrue(sent("economy.baltop.title"));
  }

  @Test
  void adminEco_withAnUnknownVerb_showsTheUsage() {
    FakePlayer admin = fixture.admin("Root");
    fixture.commands.execute(admin, new String[] {"admin", "eco", "wat"});
    assertEquals("economy.admin.usage", lastMessage());
  }

  // ----- completions -------------------------------------------------------------------------------

  @Test
  @DisplayName("every documented position completes — a branch without one is a bug, not a nicety")
  void suggestions_areNonEmptyAtEveryDocumentedPosition() {
    FakePlayer alice = fixture.player("Alice", "sexidium.economy.balance.others");
    FakePlayer admin = fixture.admin("Root");
    fixture.player("Bob");

    assertTrue(fixture.commands.suggest(alice, new String[] {""}).contains("pay"));
    assertTrue(fixture.commands.suggest(alice, new String[] {""}).contains("balance"));
    assertTrue(fixture.commands.suggest(alice, new String[] {""}).contains("baltop"));
    // The aliases gate correctly but stay out of completion: one canonical spelling per command.
    assertFalse(fixture.commands.suggest(alice, new String[] {""}).contains("bal"));

    assertFalse(fixture.commands.suggest(alice, new String[] {"pay", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(alice, new String[] {"pay", "Bob", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(alice, new String[] {"balance", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(alice, new String[] {"baltop", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(admin, new String[] {"admin", "eco", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(admin, new String[] {"admin", "eco", "give", ""}).isEmpty());
    assertFalse(fixture.commands.suggest(admin, new String[] {"admin", "eco", "give", "Bob", ""}).isEmpty());
    assertTrue(fixture.commands.suggest(admin, new String[] {"admin", ""}).contains("eco"));
  }

  @Test
  @DisplayName("/balance completion offers no names to somebody who may not look them up")
  void balanceSuggestions_respectThePermission() {
    FakePlayer alice = fixture.player("Alice");
    fixture.player("Bob");
    assertTrue(fixture.commands.suggest(alice, new String[] {"balance", ""}).isEmpty());
  }
}
