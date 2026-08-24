package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import com.sexidium.core.auth.AuthService;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AuthLoginServiceTest {
  private Database db;
  private AuthService authService;
  private PropertiesConfigurationAdapter config;
  private MessageService messages;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-login-test");
    db = new Database(dir.resolve("db.db").toFile());
    authService = new AuthService(db, () -> true);
    config = new PropertiesConfigurationAdapter();
    config.set("auth.require-for-login", "true");
    config.set("auth.code-expiry-seconds", "600");
    config.set("auth.code-length", "6");
    config.set("auth.code-characters", "23456789");
    messages = new MessageService(new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
  }

  @AfterEach
  void tearDown() throws Exception {
    db.close();
  }

  @Test
  void verify_authDisabled_allows() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,authService, () -> false);
    assertTrue(svc.verify(UUID.randomUUID(), "Steve").allowed());
  }

  @Test
  void verify_loginNotRequired_allows() {
    config.set("auth.require-for-login", "false");
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,authService, () -> true);
    assertTrue(svc.verify(UUID.randomUUID(), "Steve").allowed());
  }

  @Test
  void verify_unlinkedPlayer_rejectsWithCode() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,authService, () -> true);
    LoginDecision decision = svc.verify(UUID.randomUUID(), "Steve");
    assertFalse(decision.allowed());
    assertTrue(decision.rejectionMessage().contains("/auth"));
  }

  @Test
  void verify_linkedPlayer_allows() throws Exception {
    UUID playerId = UUID.randomUUID();
    linkPlayer(playerId.toString(), "Steve", "discord-123");
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,authService, () -> true);
    assertTrue(svc.verify(playerId, "Steve").allowed());
  }

  @Test
  void verify_nullAuthService_rejectsWithUnavailable() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,null, () -> true);
    LoginDecision decision = svc.verify(UUID.randomUUID(), "Steve");
    assertFalse(decision.allowed());
    assertTrue(decision.rejectionMessage().toLowerCase().contains("unavailable"));
  }

  @Test
  void verify_nullAuthEnabled_defaultsToTrue() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,authService, null);
    // authEnabled=null defaults to () -> true
    LoginDecision decision = svc.verify(UUID.randomUUID(), "NewPlayer");
    assertFalse(decision.allowed()); // not linked, so rejected
  }

  @Test
  void verify_authConnection_behavesLikeTheUuidOverload() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages, authService, () -> true);
    AuthResults.GateOutcome outcome = svc.verify(AuthConnection.of(UUID.randomUUID(), "Steve", "187.61.4.9"));
    assertFalse(outcome.allowed());
    assertTrue(outcome.rejectionMessage().contains("/auth"));
  }

  @Test
  void verify_connectionWithoutAUuid_rejectsRatherThanAllowing() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages, authService, () -> true);
    AuthResults.GateOutcome outcome = svc.verify(new AuthConnection("Steve", null, "1.2.3.4", null, 0, false, false, null));
    assertFalse(outcome.allowed());
  }

  @Test
  void verify_premiumVerified_bypassesTheLinkRequirement() {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages, authService, () -> true);
    AuthConnection verified = new AuthConnection("Notch", UUID.randomUUID(), "187.61.4.9", null, 0, false, true, "069a79f4");
    // Mojang already proved who they are; a Discord link on top is friction for no security.
    assertTrue(svc.verify(verified).allowed());

    config.set("auth.premium.bypass-link", "false");
    assertFalse(svc.verify(verified).allowed());
  }

  @Test
  void requireForLogin_auto_asksTheOverrideRatherThanThisJvmsBotConfig() {
    // `auto` on a proxy resolves through bot.enabled/bot.token, which live on the BOT's node -- so
    // without the override it silently means OFF and the gate allows everybody.
    config.set("auth.require-for-login", "auto");

    AuthLoginService withoutBot = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,
        authService, () -> true, () -> false, null);
    assertTrue(withoutBot.verify(UUID.randomUUID(), "Steve").allowed());

    AuthLoginService withBotSomewhere = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,
        authService, () -> true, () -> true, null);
    assertFalse(withBotSomewhere.verify(UUID.randomUUID(), "Steve").allowed());
  }

  @Test
  void requireForLogin_auto_fallsBackToThisJvmsBotConfigWithNoOverride() {
    config.set("auth.require-for-login", "auto");
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages, authService, () -> true);
    assertTrue(svc.verify(UUID.randomUUID(), "Steve").allowed(), "no bot configured here -> gate off");

    config.set("bot.enabled", "true");
    config.set("bot.token", "a-token");
    assertFalse(svc.verify(UUID.randomUUID(), "Steve").allowed());
  }

  @Test
  void verify_authServiceDisabled_rejectsWithUnavailable() {
    // The gate itself is on, but the auth SERVICE refuses to mint a code -- a different failure
    // from "auth is switched off", and the player must be told something rather than let in.
    AuthService disabledCodes = new AuthService(db, () -> false);
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages,
        disabledCodes, () -> true);

    AuthResults.GateOutcome outcome = svc.verify(UUID.randomUUID(), "Steve") == null
        ? null : svc.verify(AuthConnection.legacy(UUID.randomUUID(), "Steve"));
    assertFalse(outcome.allowed());
    assertTrue(outcome.rejectionMessage().toLowerCase().contains("unavailable"));
  }

  @Test
  void verify_databaseGone_rejectsWithAnError() throws Exception {
    AuthLoginService svc = new AuthLoginService(config, new StdoutLoggerAdapter("T"), messages, authService, () -> true);
    db.close();

    AuthResults.GateOutcome outcome = svc.verify(AuthConnection.legacy(UUID.randomUUID(), "Steve"));
    assertFalse(outcome.allowed());
    assertFalse(outcome.rejectionMessage().isBlank());
  }

  private void linkPlayer(String uuid, String name, String discordId) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO players(uuid, name, discord_user_id, points, level, wins, kills, games, updated_at) VALUES(?,?,?,0,0,0,0,0,0)")) {
        ps.setString(1, uuid);
        ps.setString(2, name);
        ps.setString(3, discordId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO discord_accounts(discord_user_id, minecraft_uuid, minecraft_name, created_at, updated_at) VALUES(?,?,?,0,0)")) {
        ps.setString(1, discordId);
        ps.setString(2, uuid);
        ps.setString(3, name);
        ps.executeUpdate();
      }
    }
  }
}
