package com.sexidium.core.lib.net;

import com.sexidium.core.auth.AuthResults.AuthLinkResult;
import com.sexidium.core.auth.AuthResults.DecisionOutcome;
import com.sexidium.core.auth.AuthResults.SessionView;
import com.sexidium.core.auth.AuthService;
import com.sexidium.core.auth.AuthSessionService;
import com.sexidium.core.data.RankService;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.ServerInfoPort;
import com.sexidium.core.platform.SkinPort;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * Dispatches a typed procedure (method + params) to the server subsystems and returns the JSON payload
 * that goes back to the bot. This is the Java counterpart to the bot's Zod contract in
 * {@code bot/src/types/contract.ts}: every method here matches a `procedures` entry there.
 *
 * <p>Handlers return a JSON string (the {@code payload}) or throw {@link BridgeException} whose message
 * is a machine token the bot maps to a friendly message (e.g. {@code disabled}/{@code forbidden}).</p>
 */
public final class BridgeRouter {
  private static final String DEFAULT_TOKEN = "change-me-please";

  private final ServerAdapter serverAdapter;
  private final RankService rankService;
  private final AuthService authService;
  private final AuthSessionService sessionService;
  private final IntFunction<List<ConsoleLine>> consoleTail;

  public BridgeRouter(
      ServerAdapter serverAdapter,
      RankService rankService,
      AuthService authService,
      IntFunction<List<ConsoleLine>> consoleTail) {
    this(serverAdapter, rankService, authService, null, consoleTail);
  }

  public BridgeRouter(
      ServerAdapter serverAdapter,
      RankService rankService,
      AuthService authService,
      AuthSessionService sessionService,
      IntFunction<List<ConsoleLine>> consoleTail) {
    this.serverAdapter = serverAdapter;
    this.rankService = rankService;
    this.authService = authService;
    this.sessionService = sessionService;
    this.consoleTail = consoleTail;
  }

  /** A failed procedure; the message is the token the bot switches on. */
  public static final class BridgeException extends RuntimeException {
    public BridgeException(String token) {
      super(token);
    }
  }

  /** Handle one request. {@code params} is the decoded payload object. Returns the response payload JSON. */
  public String dispatch(String method, Map<String, Object> params) {
    return switch (method) {
      case "rank.top" -> rankTop(intOr(params, "limit", 10));
      case "rank.byName" -> rankByName(str(params, "name"));
      case "rank.byDiscord" -> rankByDiscord(str(params, "id"));
      case "server.command" -> serverCommand(str(params, "command"));
      case "server.info" -> serverInfo();
      case "server.restart" -> serverControl(serverAdapter.configuration().getString("api.restart-command", "restart"));
      case "server.stop" -> serverControl(serverAdapter.configuration().getString("api.stop-command", "stop"));
      case "console.tail" -> consoleTail(intOr(params, "n", 50));
      case "skin.get" -> skinGet(str(params, "target"));
      case "auth.link" -> authLink(params);
      case "auth.decide" -> authDecide(params);
      case "auth.sessions" -> authSessions(str(params, "discordUserId"));
      case "auth.revoke" -> authRevoke(str(params, "discordUserId"), str(params, "sessionId"));
      default -> throw new BridgeException("unknown-method");
    };
  }

  // --- rank ---------------------------------------------------------------

  private String rankTop(int limit) {
    if (rankService == null) {
      return "[]";
    }
    return Json.ofEntries(rankService.topAggregated(limit));
  }

  private String rankByName(String name) {
    if (rankService == null || name == null || name.isBlank()) {
      return "null";
    }
    LeaderboardEntry entry = rankService.aggregateByName(name);
    return entry == null ? "null" : Json.of(entry);
  }

  private String rankByDiscord(String id) {
    if (rankService == null || id == null || id.isBlank()) {
      return "null";
    }
    LeaderboardEntry entry = rankService.aggregateByDiscordId(id);
    return entry == null ? "null" : Json.of(entry);
  }

  // --- server -------------------------------------------------------------

  private String serverCommand(String command) {
    String cleaned = requireEnabledCommand(command);
    if (!commandAllowed(cleaned)) {
      throw new BridgeException("forbidden");
    }
    dispatchConsole(cleaned);
    return "{\"ok\":true,\"command\":\"" + Json.escape(cleaned) + "\"}";
  }

  /** Restart/stop: token-gated like {@code server.command}, but the command isn't the bot's to choose. */
  private String serverControl(String consoleCommand) {
    requireEnabledToken();
    dispatchConsole(consoleCommand);
    return "{\"ok\":true,\"command\":\"" + Json.escape(consoleCommand) + "\"}";
  }

  private String serverInfo() {
    ServerInfoPort.ServerInfo info = serverAdapter.serverInfo().snapshot();
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    sb.append("\"ip\":\"").append(Json.escape(nullToEmpty(info.ip()))).append("\",");
    sb.append("\"port\":").append(info.port()).append(',');
    sb.append("\"online\":").append(info.online()).append(',');
    sb.append("\"max\":").append(info.max()).append(',');
    sb.append("\"motd\":\"").append(Json.escape(nullToEmpty(info.motd()))).append("\",");
    sb.append("\"version\":\"").append(Json.escape(nullToEmpty(info.version()))).append("\",");
    sb.append("\"tps\":").append(info.tps() < 0 ? "null" : trimTps(info.tps()));
    sb.append('}');
    return sb.toString();
  }

  private String consoleTail(int n) {
    List<ConsoleLine> lines = consoleTail == null ? List.of() : consoleTail.apply(Math.max(1, Math.min(200, n)));
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < lines.size(); i++) {
      ConsoleLine line = lines.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"level\":\"").append(Json.escape(nullToEmpty(line.level())))
          .append("\",\"message\":\"").append(Json.escape(nullToEmpty(line.message())))
          .append("\",\"ts\":").append(line.timestamp()).append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  // --- skin ---------------------------------------------------------------

  private String skinGet(String target) {
    if (target == null || target.isBlank()) {
      return emptySkin();
    }
    SkinPort skins = serverAdapter.skins();
    var resolved = skins == null ? java.util.Optional.<SkinPort.SkinTexture>empty() : skins.resolve(target);
    if (resolved.isEmpty()) {
      return emptySkin();
    }
    SkinPort.SkinTexture texture = resolved.get();
    String url = null;
    String model = "classic";
    try {
      String decoded = new String(Base64.getDecoder().decode(texture.value()), java.nio.charset.StandardCharsets.UTF_8);
      Map<String, Object> root = JsonReader.parseObject(decoded);
      if (root.get("textures") instanceof Map<?, ?> textures && textures.get("SKIN") instanceof Map<?, ?> skin) {
        Object rawUrl = skin.get("url");
        if (rawUrl != null) {
          url = String.valueOf(rawUrl);
        }
        if (skin.get("metadata") instanceof Map<?, ?> metadata && "slim".equals(String.valueOf(metadata.get("model")))) {
          model = "slim";
        }
      }
    } catch (RuntimeException exception) {
      // A malformed/rotated texture property must not break the card render; fall back to no URL.
      url = null;
    }
    return "{\"url\":" + jsonNullableString(url)
        + ",\"base64\":" + jsonNullableString(texture.value())
        + ",\"signature\":" + jsonNullableString(texture.signature())
        + ",\"model\":\"" + model + "\"}";
  }

  private String emptySkin() {
    return "{\"url\":null,\"base64\":null,\"signature\":null,\"model\":\"classic\"}";
  }

  // --- auth ---------------------------------------------------------------

  private String authLink(Map<String, Object> params) {
    if (authService == null) {
      return "{\"status\":\"disabled\",\"minecraftName\":\"\"}";
    }
    try {
      AuthLinkResult result = authService.consumeCode(
          str(params, "code"),
          str(params, "discordUserId"),
          str(params, "discordUsername"),
          str(params, "discordGlobalName"),
          str(params, "discordAvatar"));
      String name = result.minecraftName() == null ? "" : result.minecraftName();
      return "{\"status\":\"" + result.statusToken() + "\",\"minecraftName\":\"" + Json.escape(name) + "\"}";
    } catch (Exception exception) {
      serverAdapter.logger().warning("Auth link failed", exception);
      throw new BridgeException("error");
    }
  }

  /**
   * One Approve/Deny press.
   *
   * <p>{@code discordUserId} arrives from the bot and is treated as a CLAIM, never as proof: the
   * service compares it against the request row's own owner, so a forwarded message or a
   * hand-crafted customId (they are client-visible strings) comes back {@code forbidden}.</p>
   */
  private String authDecide(Map<String, Object> params) {
    if (sessionService == null) {
      return "{\"status\":\"disabled\",\"minecraftName\":\"\",\"sessionHours\":0}";
    }
    String action = str(params, "action");
    if (!"approve".equals(action) && !"deny".equals(action)) {
      throw new BridgeException("error");
    }
    try {
      DecisionOutcome outcome = sessionService.decide(
          str(params, "requestId"), str(params, "discordUserId"), "approve".equals(action));
      return "{\"status\":\"" + outcome.statusToken()
          + "\",\"minecraftName\":\"" + Json.escape(nullToEmpty(outcome.minecraftName()))
          + "\",\"sessionHours\":" + outcome.sessionHours() + "}";
    } catch (Exception exception) {
      serverAdapter.logger().warning("Auth decide failed", exception);
      throw new BridgeException("error");
    }
  }

  private String authSessions(String discordUserId) {
    if (sessionService == null || discordUserId == null || discordUserId.isBlank()) {
      return "[]";
    }
    List<SessionView> sessions = sessionService.sessionsOf(discordUserId);
    StringBuilder sb = new StringBuilder();
    sb.append('[');
    for (int i = 0; i < sessions.size(); i++) {
      SessionView session = sessions.get(i);
      if (i > 0) {
        sb.append(',');
      }
      sb.append("{\"sessionId\":\"").append(Json.escape(nullToEmpty(session.sessionId())))
          .append("\",\"minecraftName\":\"").append(Json.escape(nullToEmpty(session.minecraftName())))
          .append("\",\"ipPrefix\":\"").append(Json.escape(nullToEmpty(session.ipPrefix())))
          .append("\",\"device\":\"").append(Json.escape(nullToEmpty(session.device())))
          .append("\",\"createdAt\":").append(session.createdAt())
          .append(",\"lastSeenAt\":").append(session.lastSeenAt())
          .append(",\"expiresAt\":").append(session.expiresAt()).append('}');
    }
    sb.append(']');
    return sb.toString();
  }

  private String authRevoke(String discordUserId, String sessionId) {
    if (sessionService == null || discordUserId == null || discordUserId.isBlank()) {
      return "{\"revoked\":0}";
    }
    return "{\"revoked\":" + sessionService.revoke(discordUserId, sessionId) + "}";
  }

  // --- helpers ------------------------------------------------------------

  private String requireEnabledCommand(String command) {
    requireEnabledToken();
    if (command == null || command.isBlank()) {
      throw new BridgeException("error");
    }
    return command.startsWith("/") ? command.substring(1) : command;
  }

  private void requireEnabledToken() {
    String token = serverAdapter.configuration().getString("api.token", "");
    if (token == null || token.isBlank() || DEFAULT_TOKEN.equals(token)) {
      throw new BridgeException("disabled");
    }
  }

  private boolean commandAllowed(String command) {
    List<String> allowlist = serverAdapter.configuration().getStringList("api.command-allowlist");
    if (allowlist == null || allowlist.isEmpty()) {
      return true;
    }
    String head = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    for (String allowed : allowlist) {
      if (allowed != null && head.equals(allowed.trim().toLowerCase(Locale.ROOT))) {
        return true;
      }
    }
    return false;
  }

  private void dispatchConsole(String command) {
    serverAdapter.scheduler().runNow(() -> serverAdapter.commands().dispatchFromConsole(command));
    serverAdapter.logger().info("Discord bridge ran command: /" + command);
  }

  private static String jsonNullableString(String value) {
    return value == null ? "null" : "\"" + Json.escape(value) + "\"";
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static String trimTps(double tps) {
    return String.format(Locale.ROOT, "%.2f", Math.min(tps, 20.0));
  }

  private static String str(Map<String, Object> params, String key) {
    Object value = params.get(key);
    return value == null ? null : String.valueOf(value);
  }

  private static int intOr(Map<String, Object> params, String key, int fallback) {
    Object value = params.get(key);
    if (value instanceof Number number) {
      return number.intValue();
    }
    if (value instanceof String string) {
      try {
        return Integer.parseInt(string.trim());
      } catch (NumberFormatException ignored) {
        return fallback;
      }
    }
    return fallback;
  }
}
