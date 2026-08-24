package com.sexidium.core.lib.net;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.DrainControlPort;
import com.sexidium.core.network.DrainState;
import com.sexidium.core.network.NetworkService;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.noop.NoopScheduledTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which thread the node endpoints run their work on, and how many of them may run at once.
 *
 * <p>{@code POST /node/drain} used to call the coordinator straight from the HTTP handler, on the
 * {@code Sexidium-API} thread, while the same coordinator's state machine advances on the server
 * thread from the network heartbeat. The two share three plain one-shot flags, and {@code drain()}
 * published its volatile row <em>before</em> resetting them — so an orchestrator's undrain/drain
 * retry could leave the server thread reading a stale "OFFERING already started" and skipping
 * {@code closeMenus()} for the whole second drain. That is players evacuated with chest GUIs open,
 * i.e. the item duplication the drain order exists to prevent.</p>
 */
class ApiServerThreadingTest {

  private static final String TOKEN = "test-token";

  /** A scheduler with a REAL, single server thread — the shape the production main thread has. */
  private static final class ServerThreadScheduler implements SchedulerAdapter {
    private final ExecutorService serverThread = Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "Fake-Server-Thread");
      thread.setDaemon(true);
      return thread;
    });

    @Override public ScheduledTask runNow(Runnable runnable) {
      serverThread.execute(runnable);
      return NoopScheduledTask.INSTANCE;
    }

    @Override public ScheduledTask runLater(Runnable runnable, long delayTicks) {
      return runNow(runnable);
    }

    @Override public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
      return NoopScheduledTask.INSTANCE;
    }

    @Override public void runAsync(Runnable runnable) {
      serverThread.execute(runnable);
    }

    void stop() {
      serverThread.shutdownNow();
    }
  }

  /** Records which thread the drain request was decided on, and how long it took to get there. */
  private static final class ThreadRecordingDrain implements DrainControlPort {
    final AtomicReference<String> drainedOn = new AtomicReference<>();

    @Override public DrainState state() {
      return DrainState.idle();
    }

    @Override public DrainResult drain(String reason, boolean force, String requestedBy) {
      drainedOn.set(Thread.currentThread().getName());
      return DrainResult.accepted(DrainState.idle());
    }

    @Override public DrainResult undrain() {
      return DrainResult.accepted(DrainState.idle());
    }

    @Override public void tick() {
    }
  }

  private static final class FakeConfig implements ConfigurationAdapter {
    private final Map<String, Object> values = new HashMap<>();

    FakeConfig with(String path, Object value) {
      values.put(path, value);
      return this;
    }

    @Override public boolean getBoolean(String path, boolean defaultValue) {
      return values.containsKey(path) ? (Boolean) values.get(path) : defaultValue;
    }

    @Override public int getInt(String path, int defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).intValue() : defaultValue;
    }

    @Override public long getLong(String path, long defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).longValue() : defaultValue;
    }

    @Override public double getDouble(String path, double defaultValue) { return defaultValue; }

    @Override public String getString(String path, String defaultValue) {
      return values.containsKey(path) ? (String) values.get(path) : defaultValue;
    }

    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public Set<String> keys(String path) { return Set.of(); }
    @Override public Object get(String path) { return values.get(path); }
    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public void set(String path, Object value) { values.put(path, value); }
    @Override public void reload() { }
    @Override public void save() { }
  }

  private static final class ApiTestAdapter extends TestServerAdapter {
    private final Path dataDirectory;
    private final ConfigurationAdapter configuration;
    private final SchedulerAdapter scheduler;

    ApiTestAdapter(Path dataDirectory, ConfigurationAdapter configuration, SchedulerAdapter scheduler) {
      this.dataDirectory = dataDirectory;
      this.configuration = configuration;
      this.scheduler = scheduler;
    }

    @Override public Path dataDirectory() { return dataDirectory; }

    @Override public ConfigurationAdapter configuration() { return configuration; }

    @Override public SchedulerAdapter scheduler() { return scheduler; }

    @Override public NodeIdentity identity() {
      return NodeIdentity.of("worker-1", "worker-1", Set.of(NodeCapability.API_HOST));
    }
  }

  @TempDir
  Path tmp;

  private ServerThreadScheduler scheduler;
  private ApiServer api;
  private NetworkService network;

  @BeforeEach
  void setUp() throws Exception {
    scheduler = new ServerThreadScheduler();
    Database database = new Database(new File(tmp.toFile(), "api.db"));
    ApiTestAdapter adapter = new ApiTestAdapter(tmp,
        // Port 0: the OS picks, so the test never races another process for a fixed one.
        new FakeConfig().with("api.port", 0).with("api.token", TOKEN), scheduler);
    network = new NetworkService(adapter, database);
    api = new ApiServer(adapter, null, null, network);
    api.start();
    assertTrue(api.boundPort() > 0, "the API has to be listening for any of this to mean anything");
  }

  @AfterEach
  void tearDown() {
    if (api != null) {
      api.stop();
    }
    if (network != null) {
      network.close();
    }
    if (scheduler != null) {
      scheduler.stop();
    }
  }

  @Test
  @DisplayName("a drain request is decided on the server thread, not on the API thread")
  void drainRunsOnTheServerThread() throws Exception {
    ThreadRecordingDrain control = new ThreadRecordingDrain();
    network.setDrainControl(control);

    assertEquals(200, post("/node/drain?reason=rolling-update"));

    assertNotNull(control.drainedOn.get(), "the request has to have been decided at all");
    assertEquals("Fake-Server-Thread", control.drainedOn.get(),
        "deciding it on the API thread races the heartbeat's tick() over three non-volatile"
            + " one-shot flags, and the losing read skips closeMenus() for a whole drain");
  }

  @Test
  @DisplayName("a request blocked on the server thread does not queue the liveness probe behind it")
  void healthAnswersWhileTheServerThreadIsBusy() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    network.setDrainControl(new DrainControlPort() {
      @Override public DrainState state() { return DrainState.idle(); }

      @Override public DrainResult drain(String reason, boolean force, String requestedBy) {
        started.countDown();
        try {
          release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
        return DrainResult.accepted(DrainState.idle());
      }

      @Override public DrainResult undrain() { return DrainResult.accepted(DrainState.idle()); }

      @Override public void tick() { }
    });
    Thread caller = new Thread(() -> {
      try {
        post("/node/drain");
      } catch (IOException ignored) {
        // The assertion is on /health below; this request is only here to occupy a thread.
      }
    });
    caller.setDaemon(true);
    caller.start();
    assertTrue(started.await(10, TimeUnit.SECONDS), "the slow request has to be in flight");

    try {
      // On a single-threaded API executor this read blocks until the drain above returns, so the
      // orchestrator's own liveness probe reports a perfectly healthy node as unreachable.
      assertEquals(200, get("/health"));
    } finally {
      release.countDown();
      caller.join(10_000L);
    }
  }

  private int post(String path) throws IOException {
    return request("POST", path);
  }

  private int get(String path) throws IOException {
    return request("GET", path);
  }

  private int request(String method, String path) throws IOException {
    HttpURLConnection connection = (HttpURLConnection) URI
        .create("http://127.0.0.1:" + api.boundPort() + path).toURL().openConnection();
    connection.setRequestMethod(method);
    connection.setRequestProperty("X-Sexidium-Token", TOKEN);
    connection.setConnectTimeout(5_000);
    connection.setReadTimeout(5_000);
    try {
      int status = connection.getResponseCode();
      try (InputStream body = status < 400 ? connection.getInputStream() : connection.getErrorStream()) {
        if (body != null) {
          body.readAllBytes();
        }
      }
      return status;
    } finally {
      connection.disconnect();
    }
  }
}
