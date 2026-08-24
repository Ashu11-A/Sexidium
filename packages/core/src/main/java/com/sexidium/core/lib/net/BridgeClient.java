package com.sexidium.core.lib.net;

import com.sexidium.core.auth.AuthService;
import com.sexidium.core.data.RankService;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.lib.net.BridgeRouter.BridgeException;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.ServerInfoPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The real-time bridge to the Discord bot. The bot hosts a loopback WebSocket RPC server (Bun); this
 * connects to it as a client using only the JDK's {@link java.net.http.WebSocket} (no networking deps),
 * with an automatic reconnect loop because the Java server launches the bot and the bot's server may
 * not be up yet.
 *
 * <p>Protocol (mirrors {@code bot/src/types/contract.ts}):</p>
 * <ul>
 *   <li>On connect we send {@code hello {token}}; the bot validates it against {@code api.token} and
 *       replies {@code hello {ok}}.</li>
 *   <li>The bot sends {@code req} frames; we run them through {@link BridgeRouter} and reply
 *       {@code res}/{@code err}.</li>
 *   <li>We push {@code event} frames (player join/leave, rank change, console line, server status).</li>
 * </ul>
 */
public final class BridgeClient {
  private static final int RECONNECT_SECONDS = 5;
  private static final int STATUS_HEARTBEAT_SECONDS = 30;
  private static final int CONSOLE_BUFFER = 300;

  private final ServerAdapter serverAdapter;
  private final RankService rankService;
  private final BridgeRouter router;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ExecutorService dispatchExecutor =
      Executors.newSingleThreadExecutor(daemon("Sexidium-Bridge-Dispatch"));
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(daemon("Sexidium-Bridge"));

  private final Deque<ConsoleLine> consoleBuffer = new ArrayDeque<>();
  private final Object sendLock = new Object();
  private CompletableFuture<WebSocket> sendChain = CompletableFuture.completedFuture(null);

  private volatile boolean running;
  private volatile boolean connected;
  private volatile WebSocket webSocket;

  public BridgeClient(ServerAdapter serverAdapter, RankService rankService, AuthService authService) {
    this(serverAdapter, rankService, authService, null);
  }

  public BridgeClient(ServerAdapter serverAdapter, RankService rankService, AuthService authService,
      com.sexidium.core.auth.AuthSessionService sessionService) {
    this.serverAdapter = serverAdapter;
    this.rankService = rankService;
    this.router = new BridgeRouter(serverAdapter, rankService, authService, sessionService, this::consoleTail);
  }

  // --- lifecycle ----------------------------------------------------------

  public void start() {
    if (!serverAdapter.configuration().getBoolean("bot.enabled", false)) {
      return; // no bot -> nothing to connect to
    }
    if (running) {
      return;
    }
    running = true;
    subscribeConsole();
    connect();
    scheduler.scheduleAtFixedRate(this::heartbeat,
        STATUS_HEARTBEAT_SECONDS, STATUS_HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    serverAdapter.logger().info("Bridge RPC client active (ws://127.0.0.1:" + rpcPort() + ").");
  }

  public void stop() {
    running = false;
    connected = false;
    WebSocket ws = webSocket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
      } catch (RuntimeException ignored) {
        // best-effort
      }
    }
    scheduler.shutdownNow();
    dispatchExecutor.shutdownNow();
  }

  private void connect() {
    if (!running) {
      return;
    }
    try {
      URI uri = URI.create("ws://127.0.0.1:" + rpcPort());
      httpClient.newWebSocketBuilder()
          .buildAsync(uri, new Handler())
          .whenComplete((ws, error) -> {
            if (error != null) {
              scheduleReconnect();
            } else {
              webSocket = ws;
            }
          });
    } catch (RuntimeException exception) {
      scheduleReconnect();
    }
  }

  private void scheduleReconnect() {
    connected = false;
    webSocket = null;
    if (running) {
      scheduler.schedule(this::connect, RECONNECT_SECONDS, TimeUnit.SECONDS);
    }
  }

  // --- inbound ------------------------------------------------------------

  private final class Handler implements WebSocket.Listener {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      // Handshake: prove ourselves with the shared secret before the bot will talk to us.
      String token = serverAdapter.configuration().getString("api.token", "");
      webSocket.sendText("{\"type\":\"hello\",\"method\":\"\",\"payload\":{\"token\":\""
          + Json.escape(token == null ? "" : token) + "\"}}", true);
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      buffer.append(data);
      if (last) {
        String message = buffer.toString();
        buffer.setLength(0);
        handleInbound(message);
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      scheduleReconnect();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      scheduleReconnect();
      return null;
    }
  }

  @SuppressWarnings("unchecked")
  private void handleInbound(String message) {
    Object parsed;
    try {
      parsed = JsonReader.parse(message);
    } catch (RuntimeException exception) {
      return; // malformed frame
    }
    if (!(parsed instanceof Map<?, ?> map)) {
      return;
    }
    Map<String, Object> envelope = (Map<String, Object>) map;
    String type = string(envelope.get("type"));
    if ("hello".equals(type)) {
      connected = true;
      serverAdapter.logger().info("Discord bot connected to the bridge.");
      return;
    }
    if ("req".equals(type)) {
      String id = string(envelope.get("id"));
      String method = string(envelope.get("method"));
      Object payload = envelope.get("payload");
      Map<String, Object> params = payload instanceof Map<?, ?> p
          ? (Map<String, Object>) p
          : new LinkedHashMap<>();
      dispatchExecutor.execute(() -> handleRequest(id, method, params));
    }
  }

  private void handleRequest(String id, String method, Map<String, Object> params) {
    try {
      String payload = router.dispatch(method, params);
      enqueueSend("{\"id\":\"" + Json.escape(nn(id)) + "\",\"type\":\"res\",\"method\":\""
          + Json.escape(nn(method)) + "\",\"payload\":" + payload + "}");
    } catch (BridgeException exception) {
      enqueueSend(errorFrame(id, method, exception.getMessage()));
    } catch (RuntimeException exception) {
      serverAdapter.logger().warning("Bridge handler failed for " + method, exception);
      enqueueSend(errorFrame(id, method, "error"));
    }
  }

  private static String errorFrame(String id, String method, String token) {
    return "{\"id\":\"" + Json.escape(nn(id)) + "\",\"type\":\"err\",\"method\":\""
        + Json.escape(nn(method)) + "\",\"error\":\"" + Json.escape(nn(token)) + "\"}";
  }

  // --- outbound events ----------------------------------------------------

  /** Emit an event frame (payload is raw JSON). Returns whether it was handed to the send chain. */
  private boolean emit(String method, String payloadJson) {
    if (!connected) {
      return false;
    }
    enqueueSend("{\"type\":\"event\",\"method\":\"" + Json.escape(method) + "\",\"payload\":" + payloadJson + "}");
    return true;
  }

  public void emitPlayerJoin(String uuid, String name) {
    emit("player.join", "{\"uuid\":\"" + Json.escape(nn(uuid)) + "\",\"name\":\"" + Json.escape(nn(name)) + "\"}");
  }

  public void emitPlayerLeave(String uuid, String name) {
    emit("player.leave", "{\"uuid\":\"" + Json.escape(nn(uuid)) + "\",\"name\":\"" + Json.escape(nn(name)) + "\"}");
  }

  /**
   * Push one pending "is this you?" approval to the bot, which DMs the player's Discord account.
   *
   * <p>Emitted by the courier on the {@code BOT_HOST} node, never by the node that opened the
   * request — the proxy writes the row and has no bridge of its own. Returns whether the frame was
   * handed to a live socket: the courier marks a request notified ONLY on a true return, so a push
   * that vanished into a dead connection (a bot restart, a flapping socket) leaves the row pending
   * and the next poll delivers it again rather than swallowing it.</p>
   */
  public boolean emitAuthRequest(String requestId, String identityId, String minecraftName,
      String discordUserId, String ipPrefix, String kind, boolean firstSeen,
      long createdAt, long expiresAt) {
    return emit("auth.request", "{\"requestId\":\"" + Json.escape(nn(requestId))
        + "\",\"identityId\":\"" + Json.escape(nn(identityId))
        + "\",\"minecraftName\":\"" + Json.escape(nn(minecraftName))
        + "\",\"discordUserId\":\"" + Json.escape(nn(discordUserId))
        + "\",\"ipPrefix\":\"" + Json.escape(nn(ipPrefix))
        + "\",\"kind\":\"" + Json.escape(nn(kind))
        + "\",\"firstSeen\":" + firstSeen
        + ",\"createdAt\":" + createdAt + ",\"expiresAt\":" + expiresAt + "}");
  }

  /** Tell the bot a request has been resolved, so a stale message can be tidied up. */
  public void emitAuthDecided(String requestId, String minecraftName, String status) {
    emit("auth.decided", "{\"requestId\":\"" + Json.escape(nn(requestId))
        + "\",\"minecraftName\":\"" + Json.escape(nn(minecraftName))
        + "\",\"status\":\"" + Json.escape(nn(status)) + "\"}");
  }

  /** Resolve the up-to-date aggregated rank and push a `rank.changed` event. */
  public void emitRankChanged(String uuid, String name, String discordUserId) {
    if (!connected || rankService == null) {
      return;
    }
    LeaderboardEntry entry = null;
    try {
      if (discordUserId != null && !discordUserId.isBlank()) {
        entry = rankService.aggregateByDiscordId(discordUserId);
      }
      if (entry == null && name != null && !name.isBlank()) {
        entry = rankService.aggregateByName(name);
      }
    } catch (RuntimeException ignored) {
      // fall through with defaults
    }
    String rankClass = entry != null ? entry.rankClass() : "Omega";
    String rankColor = entry != null ? entry.rankColor() : "#9AA0A6";
    emit("rank.changed", "{\"uuid\":\"" + Json.escape(nn(uuid))
        + "\",\"discordUserId\":" + (discordUserId == null || discordUserId.isBlank() ? "null" : "\"" + Json.escape(discordUserId) + "\"")
        + ",\"name\":\"" + Json.escape(nn(name))
        + "\",\"rankClass\":\"" + Json.escape(rankClass)
        + "\",\"rankColor\":\"" + Json.escape(rankColor) + "\"}");
  }

  private void heartbeat() {
    if (!connected) {
      return;
    }
    try {
      ServerInfoPort.ServerInfo info = serverAdapter.serverInfo().snapshot();
      emit("server.status", "{\"online\":" + info.online() + ",\"max\":" + info.max() + "}");
    } catch (RuntimeException ignored) {
      // heartbeat is best-effort
    }
  }

  // --- console tap --------------------------------------------------------

  private void subscribeConsole() {
    boolean emitEvents = serverAdapter.configuration().getBoolean("api.rpc-console-events", false);
    serverAdapter.consoleTap().subscribe((level, msg) -> {
      ConsoleLine line = new ConsoleLine(level, msg, System.currentTimeMillis());
      synchronized (consoleBuffer) {
        if (consoleBuffer.size() >= CONSOLE_BUFFER) {
          consoleBuffer.removeFirst();
        }
        consoleBuffer.addLast(line);
      }
      if (emitEvents) {
        emit("console.line", "{\"level\":\"" + Json.escape(nn(level))
            + "\",\"message\":\"" + Json.escape(nn(msg)) + "\",\"ts\":" + line.timestamp() + "}");
      }
    });
  }

  private List<ConsoleLine> consoleTail(int n) {
    synchronized (consoleBuffer) {
      int size = consoleBuffer.size();
      int from = Math.max(0, size - n);
      List<ConsoleLine> all = new ArrayList<>(consoleBuffer);
      return new ArrayList<>(all.subList(from, size));
    }
  }

  // --- send serialization -------------------------------------------------

  private void enqueueSend(String text) {
    WebSocket ws = webSocket;
    if (ws == null) {
      return;
    }
    synchronized (sendLock) {
      sendChain = sendChain
          .exceptionally(throwable -> ws)
          .thenCompose(previous -> ws.sendText(text, true));
    }
  }

  private int rpcPort() {
    return serverAdapter.configuration().getInt("api.rpc-port", 8789);
  }

  private static String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private static String nn(String value) {
    return value == null ? "" : value;
  }

  private static java.util.concurrent.ThreadFactory daemon(String name) {
    return runnable -> {
      Thread thread = new Thread(runnable, name);
      thread.setDaemon(true);
      return thread;
    };
  }
}
