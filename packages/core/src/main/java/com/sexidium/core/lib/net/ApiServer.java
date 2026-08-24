package com.sexidium.core.lib.net;

import com.sexidium.core.auth.AuthResults.AuthLinkResult;
import com.sexidium.core.auth.AuthService;
import com.sexidium.core.data.RankService;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.lib.net.Json;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ApiServer {
  /** Token value shipped in config.yml; the /command bridge refuses to run while it is unchanged. */
  private static final String DEFAULT_TOKEN = "change-me-please";

  /**
   * Where the API listens.
   *
   * <p>Loopback by default, so a standalone or dev server is unchanged. A network node sets
   * {@code -Dsexidium.api.bind=0.0.0.0}: every container has its own network namespace, so that
   * exposes the API on the compose bridge only and nothing is published to the host. Before this the
   * bind was hardcoded to loopback, which made the whole control surface reachable ONLY through
   * {@code docker exec} — and the entrypoint is a bare {@code exec java … nogui} with no rcon, no
   * tmux and no stdin, so the orchestrator had no way in at all. The token is the real gate.</p>
   */
  private static final String DEFAULT_BIND = "127.0.0.1";

  /** How many requests the API may serve at once. See the executor's own note. */
  private static final int API_THREADS = 4;

  /** How long an endpoint waits for the server thread before answering 503. */
  private static final long SERVER_THREAD_TIMEOUT_SECONDS = 10L;

  private final ServerAdapter serverAdapter;
  private final RankService rankService;
  private final AuthService authService;
  /** Null on a process with no network layer; every node endpoint then answers 503. */
  private final com.sexidium.core.network.NetworkService network;
  private HttpServer server;
  private ExecutorService executor;

  public ApiServer(ServerAdapter serverAdapter, RankService rankService, AuthService authService) {
    this(serverAdapter, rankService, authService, null);
  }

  public ApiServer(ServerAdapter serverAdapter, RankService rankService, AuthService authService,
      com.sexidium.core.network.NetworkService network) {
    this.serverAdapter = Objects.requireNonNull(serverAdapter, "serverAdapter");
    this.rankService = rankService;
    this.authService = authService;
    this.network = network;
  }

  public void start() {
    ConfigurationAdapter configuration = serverAdapter.configuration();
    if (!configuration.getBoolean("api.enabled", true)) {
      serverAdapter.logger().info("HTTP API disabled in config.");
      return;
    }
    int port = configuration.getInt("api.port", 8787);
    String bind = configuration.getString("api.bind", DEFAULT_BIND);
    if (bind == null || bind.isBlank()) {
      bind = DEFAULT_BIND;
    }
    String token = configuration.getString("api.token", "");
    if (token == null || token.isBlank() || DEFAULT_TOKEN.equals(token)) {
      serverAdapter.logger().warning(
          "api.token is unset or still the shipped default; the /command bridge is DISABLED until you set a unique api.token in config.yml.");
    }
    try {
      server = HttpServer.create(new InetSocketAddress(bind, port), 0);
      server.createContext("/health", exchange -> respond(exchange, 200, "{\"ok\":true}"));
      server.createContext("/rank", this::handleRank);
      server.createContext("/player", this::handlePlayer);
      server.createContext("/discord", this::handleDiscord);
      server.createContext("/command", this::handleCommand);
      server.createContext("/auth/link", this::handleAuthLink);
      // The rolling-update control surface. /command is fire-and-forget -- it schedules the dispatch
      // and answers {"ok":true} before the command has run -- so anything that has to RETURN data
      // needs its own endpoint. These are those.
      server.createContext("/node/drain", this::handleDrain);
      server.createContext("/node/undrain", this::handleUndrain);
      server.createContext("/node/selftest", this::handleSelfTest);
      // Registered last of the /node prefixes: com.sun.net.httpserver matches the LONGEST registered
      // path, so /node/drain still reaches its own handler and /node catches only the bare path.
      server.createContext("/node", this::handleNode);
      server.createContext("/network", this::handleNetwork);
      // Four threads, not one. Two of these endpoints WAIT on the server thread (the selftest for up
      // to ten seconds), and on a single-threaded executor everything else queues behind them --
      // including the unauthenticated /health liveness probe, which would then report a healthy node
      // as unreachable for exactly as long as its selftest took.
      executor = Executors.newFixedThreadPool(API_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "Sexidium-API");
        thread.setDaemon(true);
        return thread;
      });
      server.setExecutor(executor);
      server.start();
      // Reports the ACTUAL bind. It used to print 127.0.0.1 unconditionally, which is a lie the
      // moment the bind is configurable -- and an orchestrator waits on this line.
      serverAdapter.logger().info("HTTP API listening on http://" + bind + ":" + port);
    } catch (IOException exception) {
      serverAdapter.logger().warning(
          "Failed to start HTTP API on " + bind + ":" + port, exception);
    }
  }

  /**
   * The port this API actually bound, or -1 when it is not running.
   *
   * <p>Not the configured one: {@code api.port: 0} lets the OS choose, which is how a test drives the
   * real endpoints over a real socket without racing another process for a fixed port.</p>
   */
  public int boundPort() {
    return server == null ? -1 : server.getAddress().getPort();
  }

  public void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    if (executor != null) {
      executor.shutdownNow();
      executor = null;
    }
  }

  private void handleRank(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    if (rankService == null) {
      respond(exchange, 200, "[]");
      return;
    }
    int limit = parseInt(query(exchange).get("limit"), 10);
    // Aggregated by Discord account: one row per linked user, summed across all their Minecraft names.
    respond(exchange, 200, Json.ofEntries(rankService.topAggregated(limit)));
  }

  private void handlePlayer(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    String name = query(exchange).get("name");
    if (name == null || name.isBlank()) {
      respond(exchange, 400, "{\"error\":\"name required\"}");
      return;
    }
    if (rankService == null) {
      respond(exchange, 503, "{\"error\":\"ranks unavailable\"}");
      return;
    }
    // Aggregated across every Minecraft name linked to the same Discord account as this one.
    LeaderboardEntry entry = rankService.aggregateByName(name);
    if (entry == null) {
      respond(exchange, 404, "{\"error\":\"not found\"}");
    } else {
      respond(exchange, 200, Json.of(entry));
    }
  }

  /** Aggregated totals for a Discord user id (summed across all their linked Minecraft names). */
  private void handleDiscord(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    String id = query(exchange).get("id");
    if (id == null || id.isBlank()) {
      respond(exchange, 400, "{\"error\":\"id required\"}");
      return;
    }
    if (rankService == null) {
      respond(exchange, 503, "{\"error\":\"ranks unavailable\"}");
      return;
    }
    LeaderboardEntry entry = rankService.aggregateByDiscordId(id);
    if (entry == null) {
      respond(exchange, 404, "{\"error\":\"not found\"}");
    } else {
      respond(exchange, 200, Json.of(entry));
    }
  }

  private void handleCommand(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    String expected = serverAdapter.configuration().getString("api.token", "");
    if (expected == null || expected.isBlank() || DEFAULT_TOKEN.equals(expected)) {
      // Never run console commands while the shared secret is unset or still the shipped default.
      respond(exchange, 503, "{\"error\":\"command bridge disabled: set a unique api.token\"}");
      return;
    }
    String provided = exchange.getRequestHeaders().getFirst("X-Sexidium-Token");
    if (!constantTimeEquals(expected, provided)) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return;
    }
    String command = new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8).trim();
    if (command.isEmpty()) {
      respond(exchange, 400, "{\"error\":\"empty command\"}");
      return;
    }
    String cleanedCommand = command.startsWith("/") ? command.substring(1) : command;
    if (!commandAllowed(cleanedCommand)) {
      serverAdapter.logger().warning("HTTP API rejected disallowed command: /" + cleanedCommand);
      respond(exchange, 403, "{\"error\":\"command not allowed\"}");
      return;
    }
    serverAdapter.scheduler().runNow(() -> serverAdapter.commands().dispatchFromConsole(cleanedCommand));
    serverAdapter.logger().info("Discord bridge ran command: /" + cleanedCommand);
    respond(exchange, 200, "{\"ok\":true,\"command\":\"" + Json.escape(cleanedCommand) + "\"}");
  }

  // ----- the rolling-update control surface -----------------------------------------------------
  //
  // One auth scheme for everything here: the X-Sexidium-Token header the /command bridge and the bot
  // already use. A second scheme (Bearer) next to it would be two things to configure, two things to
  // rotate and two ways to get it wrong. /health stays UNAUTHENTICATED so the existing liveness probe
  // keeps working untouched.

  /** {@code GET /node} — the boot/verify gate. One authoritative object, no log-scraping. */
  private void handleNode(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    if (!authorized(exchange) || network == null) {
      return;
    }
    respond(exchange, 200, com.sexidium.core.network.NodeStatusReport.localJson(network, true));
  }

  /** {@code GET /network} — every node plus the two player totals, computed on the right side. */
  private void handleNetwork(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    if (!authorized(exchange) || network == null) {
      return;
    }
    respond(exchange, 200, com.sexidium.core.network.NodeStatusReport.networkJson(network));
  }

  /** {@code GET /node/drain} reads the phase; {@code POST /node/drain} asks this node to drain. */
  private void handleDrain(HttpExchange exchange) throws IOException {
    if (!authorized(exchange) || network == null) {
      return;
    }
    com.sexidium.core.network.DrainControlPort control = network.drainControl();
    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 200,
          com.sexidium.core.network.NodeStatusReport.drainJson(control.state()));
      return;
    }
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    Map<String, String> query = query(exchange);
    String reason = query.getOrDefault("reason", "api");
    boolean force = "1".equals(query.get("force")) || "true".equalsIgnoreCase(query.get("force"));
    onServerThread(exchange, () -> control.drain(reason, force, "api"));
  }

  /** {@code POST /node/undrain} — back to UP. */
  private void handleUndrain(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    if (!authorized(exchange) || network == null) {
      return;
    }
    onServerThread(exchange, () -> network.drainControl().undrain());
  }

  /**
   * Run a drain request on the SERVER thread and answer with what it decided.
   *
   * <p>Both of these used to run on the API thread, straight out of the HTTP handler, while the
   * coordinator's state machine advances from the network heartbeat on the main thread. They share
   * three plain (non-volatile) one-shot flags — "the menus have been closed", "the matches have been
   * ended" — and {@code drain()} publishes its volatile row <em>before</em> resetting them, so there
   * was no happens-before edge for the resets at all. An orchestrator retry (undrain, then drain)
   * could therefore leave the main thread reading a stale "already started", skipping
   * {@code closeMenus()} for the entire second drain: players evacuated with chest GUIs open, which is
   * the item-duplication path the drain order exists to close. Hopping onto the server thread deletes
   * the question rather than answering it.</p>
   */
  private void onServerThread(HttpExchange exchange,
      java.util.function.Supplier<com.sexidium.core.network.DrainControlPort.DrainResult> request)
      throws IOException {
    java.util.concurrent.CompletableFuture<
        com.sexidium.core.network.DrainControlPort.DrainResult> future =
        new java.util.concurrent.CompletableFuture<>();
    serverAdapter.scheduler().runNow(() -> {
      try {
        future.complete(request.get());
      } catch (RuntimeException failed) {
        future.completeExceptionally(failed);
      }
    });
    try {
      respondToDrain(exchange, future.get(SERVER_THREAD_TIMEOUT_SECONDS,
          java.util.concurrent.TimeUnit.SECONDS));
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      respond(exchange, 503, "{\"error\":\"interrupted\"}");
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
      // A main thread that cannot answer a drain request inside ten seconds is itself the finding:
      // reporting "accepted" here would have the orchestrator restart a node that never began.
      serverAdapter.logger().warning("A drain request did not reach the server thread: " + failed);
      respond(exchange, 503, "{\"error\":\"the server thread did not answer in "
          + SERVER_THREAD_TIMEOUT_SECONDS + "s\"}");
    }
  }

  /**
   * A refused drain is a 409 carrying a machine-readable code, not a 500 and not a silent 200: an
   * orchestrator told "accepted" by a node that cannot drain would restart it with players inside.
   */
  private void respondToDrain(HttpExchange exchange,
      com.sexidium.core.network.DrainControlPort.DrainResult result) throws IOException {
    String state = com.sexidium.core.network.NodeStatusReport.drainJson(result.state());
    if (result.accepted()) {
      respond(exchange, 200, state);
      return;
    }
    respond(exchange, 409, "{\"refused\":\"" + Json.escape(result.refusal()) + "\",\"detail\":\""
        + Json.escape(result.detail() == null ? "" : result.detail()) + "\",\"drain\":" + state + "}");
  }

  /**
   * {@code GET /node/selftest} — the rollback trigger.
   *
   * <p>Runs on the SERVER thread, not the API thread: it instantiates every challenge in the catalog
   * and touches the shared database, both of which belong on the main thread. The wait is bounded
   * because the selftest itself is bounded and never throws.</p>
   */
  private void handleSelfTest(HttpExchange exchange) throws IOException {
    if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    if (!authorized(exchange) || network == null) {
      return;
    }
    com.sexidium.core.network.NodeSelfTest selfTest = network.selfTest();
    if (selfTest == null) {
      respond(exchange, 503, "{\"error\":\"no selftest is wired on this node\"}");
      return;
    }
    java.util.concurrent.CompletableFuture<com.sexidium.core.network.NodeSelfTest.Result> future =
        new java.util.concurrent.CompletableFuture<>();
    serverAdapter.scheduler().runNow(() -> future.complete(selfTest.run()));
    com.sexidium.core.network.NodeSelfTest.Result result;
    try {
      result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      respond(exchange, 503, "{\"error\":\"selftest interrupted\"}");
      return;
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed) {
      // A main thread too busy to run a selftest inside ten seconds IS the finding.
      respond(exchange, 503, "{\"ok\":false,\"detail\":\"selftest did not complete in 10s\"}");
      return;
    }
    serverAdapter.logger().info(result.logLine());
    respond(exchange, result.ok() ? 200 : 503,
        com.sexidium.core.network.NodeStatusReport.selfTestJson(result));
  }

  /**
   * The shared gate for every node endpoint: a configured token, a matching header, and a network
   * layer to answer from. Responds and returns false when any of the three is missing, so a caller
   * is one {@code if} away from the happy path.
   */
  private boolean authorized(HttpExchange exchange) throws IOException {
    String expected = serverAdapter.configuration().getString("api.token", "");
    if (expected == null || expected.isBlank() || DEFAULT_TOKEN.equals(expected)) {
      respond(exchange, 503, "{\"error\":\"node API disabled: set a unique api.token\"}");
      return false;
    }
    if (!constantTimeEquals(expected, exchange.getRequestHeaders().getFirst("X-Sexidium-Token"))) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return false;
    }
    if (network == null) {
      respond(exchange, 503, "{\"error\":\"this process has no network layer\"}");
      return false;
    }
    return true;
  }

  /**
   * Optional allowlist: when {@code api.command-allowlist} is non-empty the bridge only runs commands
   * whose first word is listed. Empty/absent means any command is allowed (still token-gated and
   * bound to loopback).
   */
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

  private boolean constantTimeEquals(String expected, String provided) {
    if (provided == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        provided.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Links a Discord account to a Minecraft player by consuming a /sx auth code. Performed on the Java
   * side (single SQLite writer) so the bot never opens the database file directly. Token-gated.
   */
  private void handleAuthLink(HttpExchange exchange) throws IOException {
    if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
      respond(exchange, 405, "{\"error\":\"method\"}");
      return;
    }
    String expected = serverAdapter.configuration().getString("api.token", "");
    if (expected == null || expected.isBlank() || DEFAULT_TOKEN.equals(expected)) {
      respond(exchange, 503, "{\"error\":\"auth bridge disabled: set a unique api.token\"}");
      return;
    }
    if (!constantTimeEquals(expected, exchange.getRequestHeaders().getFirst("X-Sexidium-Token"))) {
      respond(exchange, 401, "{\"error\":\"unauthorized\"}");
      return;
    }
    if (authService == null) {
      respond(exchange, 503, "{\"error\":\"auth unavailable\"}");
      return;
    }
    Map<String, String> form = parseForm(new String(readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
    String code = form.get("code");
    String discordUserId = form.get("discordUserId");
    String discordUsername = form.get("discordUsername");
    String discordGlobalName = form.get("discordGlobalName");
    String discordAvatar = form.get("discordAvatar");
    if (code == null || code.isBlank() || discordUserId == null || discordUserId.isBlank()) {
      respond(exchange, 400, "{\"error\":\"code and discordUserId required\"}");
      return;
    }
    try {
      AuthLinkResult result = authService.consumeCode(
          code, discordUserId, discordUsername, discordGlobalName, discordAvatar);
      String name = result.minecraftName() == null ? "" : result.minecraftName();
      respond(exchange, 200, "{\"status\":\"" + result.statusToken() + "\",\"minecraftName\":\"" + Json.escape(name) + "\"}");
    } catch (Exception exception) {
      serverAdapter.logger().warning("Auth link failed", exception);
      respond(exchange, 500, "{\"error\":\"link failed\"}");
    }
  }

  private Map<String, String> parseForm(String body) {
    Map<String, String> values = new HashMap<>();
    if (body == null || body.isBlank()) {
      return values;
    }
    for (String pair : body.split("&")) {
      int index = pair.indexOf('=');
      if (index > 0) {
        values.put(
            URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
      }
    }
    return values;
  }

  private Map<String, String> query(HttpExchange exchange) {
    Map<String, String> values = new HashMap<>();
    String raw = exchange.getRequestURI().getRawQuery();
    if (raw == null) {
      return values;
    }
    for (String pair : raw.split("&")) {
      int index = pair.indexOf('=');
      if (index > 0) {
        values.put(
            URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
            URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
      }
    }
    return values;
  }

  private int parseInt(String value, int defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    try {
      return Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  private byte[] readAll(InputStream inputStream) throws IOException {
    return inputStream.readAllBytes();
  }

  private void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream outputStream = exchange.getResponseBody()) {
      outputStream.write(bytes);
    }
  }
}
