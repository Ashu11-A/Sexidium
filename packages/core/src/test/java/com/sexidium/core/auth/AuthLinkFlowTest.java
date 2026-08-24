package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthResults.AuthCodeResult;
import com.sexidium.core.auth.AuthResults.AuthLinkResult;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code /auth &lt;code&gt;} flow end to end, including the step that was added for sessions: a
 * freshly linked account remembers the network it linked FROM, which is one fewer approval on a
 * player's very first join.
 */
class AuthLinkFlowTest {

  private Database db;
  private PropertiesConfigurationAdapter config;
  private AuthService service;
  private AuthSessionService sessions;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-link-flow-test");
    db = new Database(dir.resolve("link.db").toFile());
    config = new PropertiesConfigurationAdapter();
    config.set("auth.session.enabled", "true");
    config.set("auth.session.ip-pepper", "pepper");
    service = new AuthService(db, () -> true);
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    messages.reload();
    sessions = new AuthSessionService(db, config, new StdoutLoggerAdapter("T"), messages,
        service, null, "node-1", () -> true);
    service.setSessionService(sessions);
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("consuming a code links the account and mints that network's first session")
  void consumingACodeLinksAndMintsTheFirstSession() throws Exception {
    AuthIdentity identity = sessions.identity("Steve");
    String ipHash = sessions.ipHash("187.61.4.9");
    AuthCodeResult created = service.createCode(
        identity.identityId(), "Steve", 6, 60_000L, "23456789", ipHash);

    AuthLinkResult linked = service.consumeCode(
        created.code(), "discord-1", "steve#0", "Steve", "https://avatar");

    assertEquals(AuthLinkResult.Status.LINKED, linked.status());
    assertEquals("Steve", linked.minecraftName());
    assertNotNull(sessions.sessions().find(identity.identityId(), ipHash),
        "the very first join should not also have to be approved from the same network");
  }

  @Test
  @DisplayName("a code with no network bound to it links without minting anything")
  void aCodeWithoutANetworkLinksOnly() throws Exception {
    AuthIdentity identity = sessions.identity("Steve");
    AuthCodeResult created = service.createCode(identity.identityId(), "Steve", 6, 60_000L, "23456789");

    assertEquals(AuthLinkResult.Status.LINKED,
        service.consumeCode(created.code(), "discord-1", null, null, null).status());
    assertTrue(sessions.sessions().byIdentity(identity.identityId()).isEmpty());
  }

  @Test
  @DisplayName("with sessions off, linking mints nothing at all")
  void sessionsOffMintsNothing() throws Exception {
    config.set("auth.session.enabled", "false");
    AuthIdentity identity = sessions.identity("Steve");
    String ipHash = sessions.ipHash("187.61.4.9");
    AuthCodeResult created = service.createCode(
        identity.identityId(), "Steve", 6, 60_000L, "23456789", ipHash);

    service.consumeCode(created.code(), "discord-1", null, null, null);

    assertTrue(sessions.sessions().byIdentity(identity.identityId()).isEmpty());
  }

  @Test
  @DisplayName("a code nobody issued, or an empty one, is simply invalid")
  void invalidCodes() throws Exception {
    assertEquals(AuthLinkResult.Status.INVALID,
        service.consumeCode("999999", "discord-1", null, null, null).status());
    assertEquals(AuthLinkResult.Status.INVALID,
        service.consumeCode("", "discord-1", null, null, null).status());
    assertEquals(AuthLinkResult.Status.INVALID,
        service.consumeCode("123456", "", null, null, null).status());
  }

  @Test
  @DisplayName("linking is disabled wholesale when auth is off")
  void disabledService() throws Exception {
    AuthService disabled = new AuthService(db, () -> false);
    assertEquals(AuthLinkResult.Status.DISABLED,
        disabled.consumeCode("123456", "discord-1", null, null, null).status());
  }

  @Test
  @DisplayName("a code cannot be used twice")
  void aCodeIsSingleUse() throws Exception {
    AuthCodeResult created = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    service.consumeCode(created.code(), "discord-1", null, null, null);

    assertEquals(AuthLinkResult.Status.ALREADY_USED,
        service.consumeCode(created.code(), "discord-2", null, null, null).status());
  }

  @Test
  @DisplayName("an expired code is refused, and says which account it was for")
  void anExpiredCodeIsRefused() throws Exception {
    AuthCodeResult created = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    expireCodes();

    AuthLinkResult result = service.consumeCode(created.code(), "discord-1", null, null, null);
    assertEquals(AuthLinkResult.Status.EXPIRED, result.status());
    assertEquals("Steve", result.minecraftName());
  }

  @Test
  @DisplayName("a Minecraft account that is already linked cannot be linked again")
  void anAlreadyLinkedAccountIsRefused() throws Exception {
    AuthCodeResult first = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    service.consumeCode(first.code(), "discord-1", null, null, null);

    // A second code for the same player, consumed by somebody else.
    AuthCodeResult second = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    assertEquals(AuthCodeResult.Status.ALREADY_LINKED, second.status());
  }

  @Test
  @DisplayName("one Discord account may own several Minecraft names — that is the point")
  void oneDiscordManyNames() throws Exception {
    AuthCodeResult first = service.createCode("uuid-1", "Steve", 6, 60_000L, "23456789");
    service.consumeCode(first.code(), "discord-1", null, null, null);
    AuthCodeResult second = service.createCode("uuid-2", "Alex", 6, 60_000L, "23456789");

    assertEquals(AuthLinkResult.Status.LINKED,
        service.consumeCode(second.code(), "discord-1", null, null, null).status());
  }

  @Test
  @DisplayName("a failed mint costs one extra approval later, never the link itself")
  void aFailedMintDoesNotFailTheLink() throws Exception {
    AuthIdentity identity = sessions.identity("Steve");
    AuthCodeResult created = service.createCode(
        identity.identityId(), "Steve", 6, 60_000L, "23456789", sessions.ipHash("187.61.4.9"));
    // A name with no identity row: the mint finds nothing to attach a session to.
    renameCode(created.code(), "Ghost");

    assertEquals(AuthLinkResult.Status.LINKED,
        service.consumeCode(created.code(), "discord-1", null, null, null).status());
  }

  @Test
  @DisplayName("the whole first join: gate -> code -> link -> straight back in, one reconnect")
  void firstJoinCostsExactlyOneReconnect() throws Exception {
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    messages.reload();
    config.set("auth.require-for-login", "true");
    config.set("auth.approval.enabled", "true");
    AuthLoginService gate = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,
        service, () -> true, () -> true, sessions);

    AuthConnection connection = AuthConnection.of(UUID.randomUUID(), "Steve", "187.61.4.9");

    // 1. Unknown player: refused with a code, and the code REMEMBERS the network.
    AuthResults.GateOutcome first = gate.verify(connection);
    assertFalse(first.allowed());
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("/auth ([0-9]{6})").matcher(first.rejectionMessage());
    assertTrue(matcher.find(), "the disconnect screen must carry the code the player has to type");
    String code = matcher.group(1);
    assertEquals(sessions.ipHash("187.61.4.9"), codeNetwork(code));

    // 2. They run /auth <code> in Discord.
    assertEquals(AuthLinkResult.Status.LINKED,
        service.consumeCode(code, "discord-1", null, null, null).status());

    // 3. They reconnect once and are simply in -- no second approval for the network they
    //    demonstrably just proved they were on.
    assertTrue(gate.verify(connection).allowed());
  }

  @Test
  @DisplayName("the pre-login screens are rendered in both languages under the brand header")
  void bilingualRendering() {
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    messages.reload();

    String screen = AuthMessages.bilingual(messages, MessageKey.AUTH_PREMIUM_UNAVAILABLE);
    assertTrue(screen.contains("Mojang is not answering"));
    assertTrue(screen.contains("não está respondendo"));
    assertTrue(screen.contains("──────────────────"));

    // The inline form is for a title or an action bar, where there is no room for a header.
    String inline = AuthMessages.bilingualInline(messages, MessageKey.AUTH_HOLD_TITLE);
    assertTrue(inline.contains("Confirm on Discord"));
    assertTrue(inline.contains("Confirme no Discord"));
    assertFalse(inline.contains("──────────────────"));
  }

  @Test
  @DisplayName("a connection knows the lowercase name every part of the gate keys on")
  void connectionShape() {
    UUID uuid = UUID.randomUUID();
    AuthConnection legacy = AuthConnection.legacy(uuid, "Steve");
    assertEquals("steve", legacy.nameLower());
    assertNull(legacy.ip());
    assertFalse(legacy.premiumVerified());

    assertEquals("steve", AuthConnection.of(uuid, "  STEVE  ", "1.2.3.4").nameLower());
    assertEquals("", new AuthConnection(null, uuid, null, null, 0, false, false, null).nameLower());
  }

  /** The network a code was bound to, read straight from the row. */
  private String codeNetwork(String code) throws Exception {
    return new AuthRepository(db).bindingOf(AuthService.hashCode(code)).ipHash();
  }

  private void expireCodes() throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "UPDATE auth_codes SET expires_at = 1")) {
        ps.executeUpdate();
      }
    }
  }

  private void renameCode(String code, String name) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "UPDATE auth_codes SET minecraft_name = ? WHERE code_hash = ?")) {
        ps.setString(1, name);
        ps.setString(2, AuthService.hashCode(code));
        ps.executeUpdate();
      }
    }
  }
}
