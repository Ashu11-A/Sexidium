package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.SQLException;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Gate that decides, at the connection stage, whether a player may join. When the gate is active and
 * the player has not linked a Discord account, it mints a fresh {@code /auth} code (generated in Java)
 * and rejects the login with a disconnect message instructing them to run {@code /auth <code>} in
 * Discord. The message is rendered in BOTH English and Brazilian Portuguese because the client locale
 * is not known this early in the handshake — both blocks are shown so any player can read it.
 *
 * <p>The gate is on by default whenever a Discord bot is configured. {@code auth.require-for-login}
 * accepts {@code auto} (default — on iff a bot is configured), {@code true} (always on) or
 * {@code false} (always off).</p>
 *
 * <p>When an {@link AuthSessionService} is attached and {@code auth.session.enabled} is on, the code
 * path above becomes the FALLBACK rather than the rule: a player with a live IP+name session, a
 * Mojang-verified connection or a fresh Discord approval never sees a code at all. The code flow is
 * kept for the one case that still needs it — a player with no Discord link yet.</p>
 */
public final class AuthLoginService {
  private final ConfigurationAdapter configurationAdapter;
  private final LoggerAdapter loggerAdapter;
  private final MessageService messageService;
  private final AuthService authService;
  private final BooleanSupplier authEnabled;
  /**
   * Answers "is a Discord bot reachable from this network?" on behalf of a node that holds no bot
   * config of its own.
   *
   * <p>The proxy is that node, and it is also the gate: {@code auto} resolves through
   * {@code bot.enabled}/{@code bot.token}, which live on the bot's node, so on the proxy {@code auto}
   * silently meant OFF and the gate logged "active" while allowing everybody. The proxy passes
   * {@code registry.anyAlive(BOT_HOST)} here instead.</p>
   */
  private final BooleanSupplier botConfiguredOverride;
  private final AuthSessionService sessionService;

  public AuthLoginService(
      ConfigurationAdapter configurationAdapter,
      LoggerAdapter loggerAdapter,
      MessageService messageService,
      AuthService authService,
      BooleanSupplier authEnabled
  ) {
    this(configurationAdapter, loggerAdapter, messageService, authService, authEnabled, null, null);
  }

  public AuthLoginService(
      ConfigurationAdapter configurationAdapter,
      LoggerAdapter loggerAdapter,
      MessageService messageService,
      AuthService authService,
      BooleanSupplier authEnabled,
      BooleanSupplier botConfiguredOverride,
      AuthSessionService sessionService
  ) {
    this.configurationAdapter = configurationAdapter;
    this.loggerAdapter = loggerAdapter;
    this.messageService = messageService;
    this.authService = authService;
    this.authEnabled = authEnabled == null ? () -> true : authEnabled;
    this.botConfiguredOverride = botConfiguredOverride;
    this.sessionService = sessionService;
    if (sessionService != null) {
      // The one thing the session gate cannot do itself: mint a /auth code for a player who has no
      // Discord account attached yet, because there is nobody to send a button to.
      sessionService.setUnlinkedGate((connection, identity) ->
          // The code is BOUND to the network that asked for it, so consuming it mints that
          // network's first session and the player's very first join costs one reconnect, not two.
          issueCode(identity.identityId(), identity.displayName(),
              sessionService.ipHash(connection.ip())).toGateOutcome());
    }
  }

  public LoginDecision verify(UUID playerId, String playerName) {
    return verify(AuthConnection.legacy(playerId, playerName)).toLoginDecision();
  }

  /**
   * The full gate for one connection.
   *
   * <p>Three layers, each a superset of the one below it, so a deployment can enable exactly as much
   * as it has verified: off, code-only (today's behaviour), or sessions + approvals + premium.</p>
   */
  public GateOutcome verify(AuthConnection connection) {
    if (!authEnabled.getAsBoolean() || !requireForLogin()) {
      return GateOutcome.allow();
    }
    if (authService == null) {
      return GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_UNAVAILABLE));
    }
    if (sessionService != null && sessionService.enabled()) {
      return sessionService.authorize(connection);
    }
    // A Mojang-verified connection is already proven; demanding a Discord link on top of it is
    // friction for no security. Honoured even with sessions off, so premium auto-login is useful
    // on its own.
    if (connection.premiumVerified()
        && configurationAdapter.getBoolean("auth.premium.bypass-link", true)) {
      return GateOutcome.allow();
    }
    UUID playerId = connection.connectionUuid();
    if (playerId == null) {
      return GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_ERROR));
    }
    return issueCode(playerId.toString(), connection.username(), null).toGateOutcome();
  }

  /**
   * The legacy code flow, as its own step so the session gate can fall back to it.
   *
   * <p>{@code ipHash} is null on the pure code path, where there are no sessions to mint.</p>
   */
  private CodeOutcome issueCode(String minecraftUuid, String playerName, String ipHash) {
    try {
      String linkedDiscordId = authService.linkedDiscordId(minecraftUuid);
      if (linkedDiscordId != null && !linkedDiscordId.isBlank()) {
        return CodeOutcome.pass();
      }

      long ttlSeconds = Math.max(1L, configurationAdapter.getLong("auth.code-expiry-seconds", 600L));
      int length = configurationAdapter.getInt("auth.code-length", 6);
      String characters = configurationAdapter.getString("auth.code-characters", "23456789");
      AuthCodeResult result = authService.createCode(
          minecraftUuid, playerName, length, ttlSeconds * 1000L, characters, ipHash);
      return switch (result.status()) {
        case CREATED -> CodeOutcome.rejected(bilingual(
            MessageKey.AUTH_LOGIN_REQUIRED,
            MessageArg.text("code", result.code()),
            MessageArg.text("seconds", ttlSeconds)));
        case ALREADY_LINKED -> CodeOutcome.pass();
        case DISABLED -> CodeOutcome.rejected(bilingual(MessageKey.AUTH_LOGIN_UNAVAILABLE));
      };
    } catch (SQLException exception) {
      loggerAdapter.warning("Failed to verify auth login for " + playerName, exception);
      return CodeOutcome.rejected(bilingual(MessageKey.AUTH_LOGIN_ERROR));
    }
  }

  /** The code flow's answer, before it is widened into a {@link GateOutcome}. */
  private record CodeOutcome(boolean allowed, String rejectionMessage) {
    static CodeOutcome pass() {
      return new CodeOutcome(true, "");
    }

    static CodeOutcome rejected(String rejectionMessage) {
      return new CodeOutcome(false, rejectionMessage);
    }

    GateOutcome toGateOutcome() {
      return allowed ? GateOutcome.allow() : GateOutcome.reject(rejectionMessage);
    }
  }

  /** {@code auto} (on iff a bot is configured), {@code true} or {@code false}. */
  private boolean requireForLogin() {
    String mode = configurationAdapter.getString("auth.require-for-login", "auto");
    String normalized = mode == null ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
    return switch (normalized) {
      case "true", "yes", "on", "always" -> true;
      case "false", "no", "off", "never" -> false;
      default -> botConfigured();
    };
  }

  private boolean botConfigured() {
    if (botConfiguredOverride != null) {
      return botConfiguredOverride.getAsBoolean();
    }
    if (!configurationAdapter.getBoolean("bot.enabled", false)) {
      return false;
    }
    String token = configurationAdapter.getString("bot.token", "");
    return token != null && !token.isBlank();
  }

  /**
   * Renders one message key in English and Brazilian Portuguese, joined with a divider. The client
   * locale is unavailable pre-login, so both versions are always shown.
   */
  private String bilingual(MessageKey key, MessageArg... args) {
    return AuthMessages.bilingual(messageService, key, args);
  }
}
