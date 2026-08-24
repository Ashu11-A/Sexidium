package com.sexidium.core.bot;

import com.sexidium.core.Branding;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.lib.data.DatabaseConfig;
import com.sexidium.core.lib.data.DatabaseSettings;
import com.sexidium.core.lib.data.SqlDialect;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class BotManager {
  private static final int MAX_RECENT_LOGS = 20;

  private final ServerAdapter serverAdapter;
  private final Database database;
  private final Object lock = new Object();
  private final Deque<String> recentLogs = new ArrayDeque<>();
  private Process process;
  private Thread logPump;
  private boolean launching;
  private long lastStartedAt;
  private long lastStoppedAt;
  private String lastRuntime = "";
  private String lastCommand = "";
  private String lastStartError = "";

  public BotManager(ServerAdapter serverAdapter, Database database) {
    this.serverAdapter = Objects.requireNonNull(serverAdapter, "serverAdapter");
    this.database = database;
  }

  public void start() {
    synchronized (lock) {
      if (launching || isRunningLocked()) {
        logger().info("Discord bot is already running or starting.");
        return;
      }
    }
    ConfigurationAdapter configuration = configuration();
    if (!configuration.getBoolean("bot.enabled", false)) {
      lastStartError = "bot.enabled is false";
      logger().info("Discord bot disabled (set bot.enabled: true and a token to use it).");
      return;
    }
    String token = configuration.getString("bot.token", "");
    if (token == null || token.isBlank()) {
      lastStartError = "bot.token is empty";
      logger().warning("Discord bot enabled but bot.token is empty - not starting.");
      return;
    }
    synchronized (lock) {
      launching = true;
      lastStartError = "";
    }
    Thread thread = new Thread(this::launch, "Sexidium-Bot-Start");
    thread.setDaemon(true);
    thread.start();
  }

  public void stop() {
    Process current;
    synchronized (lock) {
      current = process;
    }
    if (current != null && current.isAlive()) {
      current.destroy();
      try {
        if (!current.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
          current.destroyForcibly();
        }
      } catch (InterruptedException exception) {
        current.destroyForcibly();
        Thread.currentThread().interrupt();
      }
    }
    synchronized (lock) {
      process = null;
      launching = false;
      lastStoppedAt = System.currentTimeMillis();
    }
    rememberLog("stopped by plugin");
  }

  public void restart() {
    stop();
    start();
  }

  public BotStatus status() {
    synchronized (lock) {
      File data = dataDirectory();
      File botDir = new File(data, "bot");
      File entry = new File(botDir, "src/index.ts");
      File bundledBun = new File(data, "runtime/" + bunBinaryName());
      File databaseFile = database == null ? null : database.file();
      return new BotStatus(
          configuration().getBoolean("bot.enabled", false),
          isRunningLocked(),
          launching,
          process == null ? "" : String.valueOf(process.pid()),
          exitCodeLocked(),
          configuredState("bot.token"),
          configuration().getBoolean("bot.download-runtime", true),
          configuration().getString("bot.runtime-command", "bun"),
          lastRuntime,
          lastCommand,
          botDir.getAbsolutePath(),
          entry.getAbsolutePath(),
          entry.isFile(),
          bundledBun.getAbsolutePath(),
          bundledBun.isFile(),
          "http://127.0.0.1:" + configuration().getInt("api.port", 8787),
          configuredState("api.token"),
          databaseFile == null ? "" : databaseFile.getAbsolutePath(),
          configuredState("bot.guild-id"),
          configuredState("bot.admin-role-id"),
          lastStartedAt,
          lastStoppedAt,
          lastStartError == null ? "" : lastStartError,
          List.copyOf(recentLogs));
    }
  }

  private void launch() {
    try {
      File data = dataDirectory();
      Files.createDirectories(data.toPath());
      // The bot's TypeScript source is bundled in the jar; extract it on first run. The bun runtime is
      // NOT bundled — it is downloaded per architecture by resolveRuntime() to keep the jar small.
      extractBundleIfNeeded(data);

      File botDir = new File(data, "bot");
      File entry = new File(botDir, "src/index.ts");
      if (!entry.isFile()) {
        logger().warning("Bot entry " + entry + " not found. Use a " + Branding.label(configuration())
            + " jar with bot resources or place a bot/ folder in the data directory. Bot not started.");
        return;
      }

      String runtime = resolveRuntime(data);
      if (runtime == null) {
        logger().warning("No bun runtime available - bot not started.");
        return;
      }
      installDependencies(botDir, runtime);

      List<String> command = new ArrayList<>();
      command.add(runtime);
      command.add("run");
      command.add(entry.getAbsolutePath());

      ProcessBuilder processBuilder = new ProcessBuilder(command);
      processBuilder.directory(botDir);
      processBuilder.redirectErrorStream(true);
      Map<String, String> environment = processBuilder.environment();
      environment.put("BOT_TOKEN", configuration().getString("bot.token", ""));
      environment.put("API_URL", "http://127.0.0.1:" + configuration().getInt("api.port", 8787));
      environment.put("API_TOKEN", configuration().getString("api.token", ""));
      environment.put("GUILD_ID", configuration().getString("bot.guild-id", ""));
      environment.put("ADMIN_ROLE_ID", configuration().getString("bot.admin-role-id", ""));
      // The bot's ready.ts has read LOG_CHANNEL_ID / EVENTS_CHANNEL_ID since the RPC relays landed and
      // nothing ever injected them, so both relays were silently dead in production. AUTH_CHANNEL_ID
      // is the fallback for a player whose Discord DMs are closed, which is very common.
      environment.put("LOG_CHANNEL_ID", configuration().getString("bot.log-channel-id", ""));
      environment.put("EVENTS_CHANNEL_ID", configuration().getString("bot.events-channel-id", ""));
      environment.put("AUTH_CHANNEL_ID", configuration().getString("auth.approval.channel-id", ""));
      // Only the embedded SQLite backend exposes a file path. The live bot reaches data over the HTTP
      // bridge (API_URL/API_TOKEN) and never opens the DB itself, so a networked backend (file() == null)
      // simply omits this — the bot stays backend-agnostic.
      if (database != null && database.file() != null) {
        environment.put("DATABASE_PATH", database.file().getAbsolutePath());
      }
      // WebSocket RPC port the bot listens on (Java connects to it as a client).
      environment.put("RPC_PORT", String.valueOf(configuration().getInt("api.rpc-port", 8789)));
      // Networked DB credentials for the bot's TypeORM connection. When the bot is enabled the plugin
      // runs on MySQL/Postgres only (embedded SQLite is rejected at startup), so this always resolves.
      try {
        DatabaseConfig databaseConfig = DatabaseSettings.resolve(configuration(), dataDirectory());
        if (databaseConfig.dialect() != SqlDialect.SQLITE) {
          boolean mysql = databaseConfig.dialect() == SqlDialect.MYSQL;
          int port = databaseConfig.port() > 0 ? databaseConfig.port() : (mysql ? 3306 : 5432);
          environment.put("DB_TYPE", mysql ? "mysql" : "postgres");
          environment.put("DB_HOST", databaseConfig.host() == null ? "127.0.0.1" : databaseConfig.host());
          environment.put("DB_PORT", String.valueOf(port));
          environment.put("DB_NAME", databaseConfig.databaseName() == null ? "" : databaseConfig.databaseName());
          environment.put("DB_USER", databaseConfig.user() == null ? "" : databaseConfig.user());
          environment.put("DB_PASSWORD", databaseConfig.password() == null ? "" : databaseConfig.password());
        }
      } catch (RuntimeException databaseEnvError) {
        logger().warning("Could not resolve database settings for the bot environment: " + databaseEnvError.getMessage());
      }

      logger().info("Starting Discord bot via " + runtime + " ...");
      Process started = processBuilder.start();
      synchronized (lock) {
        process = started;
        lastRuntime = runtime;
        lastCommand = String.join(" ", command);
        lastStartedAt = System.currentTimeMillis();
        lastStartError = "";
      }
      rememberLog("started via " + runtime);
      pumpLogs(started);
    } catch (Exception exception) {
      synchronized (lock) {
        lastStartError = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
      }
      logger().warning("Failed to start the Discord bot", exception);
    } finally {
      synchronized (lock) {
        launching = false;
      }
    }
  }

  private String resolveRuntime(File data) {
    boolean download = configuration().getBoolean("bot.download-runtime", true);
    File bun = new File(data, "runtime/" + bunBinaryName());
    if (download) {
      if (!bun.isFile()) {
        try {
          downloadBun(data, bun);
        } catch (Exception exception) {
          logger().warning("Failed to download the bun runtime; falling back to a system runtime.", exception);
        }
      }
      if (bun.isFile()) {
        bun.setExecutable(true, false);
        return bun.getAbsolutePath();
      }
    }
    String fallback = configuration().getString("bot.runtime-command", "bun");
    return fallback == null || fallback.isBlank() ? "bun" : fallback;
  }

  /**
   * Downloads the bun runtime build matching this server's OS/architecture from the GitHub releases and
   * extracts the single {@code bun} binary into {@code <data>/runtime/}. Pinned with
   * {@code bot.bun-version} (default {@code latest}). This replaces the old per-architecture jar
   * bundling: the jar stays small and architecture-independent.
   */
  private void downloadBun(File data, File target) throws IOException {
    String asset = bunAssetName();
    String version = configuration().getString("bot.bun-version", "latest");
    String base = (version == null || version.isBlank() || version.trim().equalsIgnoreCase("latest"))
        ? "https://github.com/oven-sh/bun/releases/latest/download/"
        : "https://github.com/oven-sh/bun/releases/download/bun-v" + version.trim() + "/";
    String url = base + asset + ".zip";
    logger().info("Downloading bun runtime for this server (" + asset + ") from " + url + " ...");
    File parent = target.getParentFile();
    if (parent != null) {
      Files.createDirectories(parent.toPath());
    }
    File temp = File.createTempFile("sexidium-bun", ".zip", data);
    try {
      try (InputStream inputStream = URI.create(url).toURL().openStream()) {
        Files.copy(inputStream, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      extractBunBinary(temp, target);
    } finally {
      temp.delete();
    }
    if (target.isFile()) {
      target.setExecutable(true, false);
      logger().info("bun runtime ready at " + target.getAbsolutePath());
    } else {
      throw new IOException("bun binary not found inside " + asset + ".zip");
    }
  }

  private void extractBunBinary(File zip, File target) throws IOException {
    String binaryName = bunBinaryName();
    try (java.util.zip.ZipInputStream zipInputStream = new java.util.zip.ZipInputStream(
        new java.io.BufferedInputStream(new java.io.FileInputStream(zip)))) {
      java.util.zip.ZipEntry entry;
      while ((entry = zipInputStream.getNextEntry()) != null) {
        String name = entry.getName();
        if (!entry.isDirectory() && (name.endsWith("/" + binaryName) || name.equals(binaryName))) {
          Files.copy(zipInputStream, target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
          return;
        }
      }
    }
  }

  /** GitHub release asset (without extension) for this server's OS/architecture. */
  private static String bunAssetName() {
    String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
    String arch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
    boolean arm = arch.contains("aarch64") || arch.contains("arm64");
    if (os.contains("win")) {
      return "bun-windows-x64";
    }
    if (os.contains("mac") || os.contains("darwin")) {
      return arm ? "bun-darwin-aarch64" : "bun-darwin-x64";
    }
    return arm ? "bun-linux-aarch64" : "bun-linux-x64-baseline";
  }

  private static String bunBinaryName() {
    return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "bun.exe" : "bun";
  }

  private void installDependencies(File botDir, String runtime) throws IOException, InterruptedException {
    File packageJson = new File(botDir, "package.json");
    File lockFile = new File(botDir, "bun.lock");
    if (!packageJson.isFile()) {
      throw new IOException("Bot package.json was not found at " + packageJson);
    }
    String fingerprint = installFingerprint(runtime, packageJson, lockFile);
    File marker = new File(botDir, ".node_modules-installed");
    File nodeModules = new File(botDir, "node_modules");
    if (!marker.isFile() || !fingerprint.equals(Files.readString(marker.toPath()))) {
      deleteRecursively(nodeModules.toPath());
    }

    List<String> command = List.of(runtime, "install", "--no-save");
    logger().info("Installing Discord bot dependencies via " + runtime + " ...");
    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.directory(botDir);
    processBuilder.redirectErrorStream(true);
    Process installProcess = processBuilder.start();
    try (BufferedReader reader = new BufferedReader(
        new InputStreamReader(installProcess.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        rememberLog("install: " + line);
        logger().info("[bot install] " + line);
      }
    }
    int exitCode = installProcess.waitFor();
    if (exitCode != 0) {
      throw new IOException("Bot dependency install failed with exit code " + exitCode);
    }
    Files.writeString(marker.toPath(), fingerprint, StandardCharsets.UTF_8);
  }

  private String installFingerprint(String runtime, File packageJson, File lockFile) {
    return System.getProperty("os.name") + "\n"
        + System.getProperty("os.arch") + "\n"
        + runtime + "\n"
        + fileStamp(packageJson) + "\n"
        + fileStamp(lockFile);
  }

  private String fileStamp(File file) {
    return file.isFile() ? file.length() + ":" + file.lastModified() : "missing";
  }

  private void deleteRecursively(Path path) throws IOException {
    if (!Files.exists(path)) {
      return;
    }
    try (var paths = Files.walk(path)) {
      List<Path> orderedPaths = paths.sorted(Comparator.reverseOrder()).toList();
      for (Path child : orderedPaths) {
        Files.deleteIfExists(child);
      }
    }
  }

  private void extractBundleIfNeeded(File data) throws IOException {
    URL location = getClass().getProtectionDomain().getCodeSource().getLocation();
    File jar;
    try {
      jar = new File(location.toURI());
    } catch (URISyntaxException exception) {
      throw new IOException(exception);
    }
    if (!jar.isFile()) {
      logger().info("Not running from a jar; skipping bot extraction.");
      return;
    }

    File marker = new File(data, "bot/.extracted-" + bundleId(jar));
    if (marker.isFile()) {
      return;
    }

    int extracted = 0;
    try (JarFile jarFile = new JarFile(jar)) {
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!(name.startsWith("bot/") || name.startsWith("runtime/") || name.startsWith("packages/"))) {
          continue;
        }
        File output = new File(data, name);
        if (entry.isDirectory()) {
          output.mkdirs();
          continue;
        }
        File parent = output.getParentFile();
        if (parent != null) {
          parent.mkdirs();
        }
        try (InputStream inputStream = jarFile.getInputStream(entry)) {
          Files.copy(inputStream, output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        extracted++;
      }
    }
    File bun = new File(data, "runtime/bun");
    if (bun.isFile()) {
      bun.setExecutable(true, false);
    }
    File markerParent = marker.getParentFile();
    if (markerParent != null) {
      markerParent.mkdirs();
    }
    Files.writeString(marker.toPath(), "ok");
    logger().info("Extracted bundled bot resources (" + extracted + " files).");
  }

  private String bundleId(File jar) {
    return Long.toHexString(jar.length()) + "-" + Long.toHexString(jar.lastModified());
  }

  private void pumpLogs(Process currentProcess) {
    logPump = new Thread(() -> {
      try (BufferedReader reader = new BufferedReader(
          new InputStreamReader(currentProcess.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          rememberLog(line);
          logger().info("[bot] " + line);
        }
        rememberLog("process output ended");
      } catch (IOException ignored) {
        rememberLog("process output reader stopped");
      }
    }, "Sexidium-Bot-Log");
    logPump.setDaemon(true);
    logPump.start();
  }

  private boolean isRunningLocked() {
    return process != null && process.isAlive();
  }

  private String exitCodeLocked() {
    if (process == null || process.isAlive()) {
      return "";
    }
    try {
      return String.valueOf(process.exitValue());
    } catch (IllegalThreadStateException ignored) {
      return "";
    }
  }

  private String configuredState(String path) {
    String value = configuration().getString(path, "");
    return value == null || value.isBlank() ? "empty" : "configured";
  }

  private void rememberLog(String line) {
    synchronized (lock) {
      if (recentLogs.size() >= MAX_RECENT_LOGS) {
        recentLogs.removeFirst();
      }
      recentLogs.addLast(line == null ? "" : line);
    }
  }

  private File dataDirectory() {
    return serverAdapter.dataDirectory().toFile();
  }

  private ConfigurationAdapter configuration() {
    return serverAdapter.configuration();
  }

  private LoggerAdapter logger() {
    return serverAdapter.logger();
  }

  public record BotStatus(
      boolean enabled,
      boolean running,
      boolean launching,
      String pid,
      String exitCode,
      String tokenState,
      boolean useBundledRuntime,
      String runtimeCommand,
      String lastRuntime,
      String lastCommand,
      String botDir,
      String entryPath,
      boolean entryExists,
      String bundledRuntimePath,
      boolean bundledRuntimeExists,
      String apiUrl,
      String apiTokenState,
      String databasePath,
      String guildIdState,
      String adminRoleState,
      long lastStartedAt,
      long lastStoppedAt,
      String lastStartError,
      List<String> recentLogs) {
  }
}
