package com.sexidium.core.network;

import com.sexidium.core.platform.ConfigurationAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkSettingsTest {

  /** Minimal in-memory ConfigurationAdapter; only the getters NetworkSettings uses do anything. */
  private static final class FakeConfig implements ConfigurationAdapter {
    private final Map<String, Object> values = new HashMap<>();

    /** Named `with` rather than `set`: ConfigurationAdapter already declares a void set(String, Object). */
    FakeConfig with(String path, Object value) {
      values.put(path, value);
      return this;
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
      return values.containsKey(path) ? (Boolean) values.get(path) : defaultValue;
    }

    @Override
    public String getString(String path, String defaultValue) {
      return values.containsKey(path) ? (String) values.get(path) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> getStringList(String path) {
      return (List<String>) values.getOrDefault(path, new ArrayList<String>());
    }

    @Override public int getInt(String path, int defaultValue) { return defaultValue; }

    @Override
    public long getLong(String path, long defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).longValue() : defaultValue;
    }

    @Override public double getDouble(String path, double defaultValue) { return defaultValue; }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public Set<String> keys(String path) { return Set.of(); }
    @Override public Object get(String path) { return values.get(path); }
    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public void set(String path, Object value) { values.put(path, value); }
    @Override public void reload() { }
    @Override public void save() { }
  }

  @Test
  @DisplayName("a config with no network block is standalone")
  void missingBlock_isStandalone() {
    assertTrue(NetworkSettings.resolve(new FakeConfig()).isStandalone());
  }

  @Test
  @DisplayName("a null config is standalone rather than a crash")
  void nullConfig_isStandalone() {
    // Headless/test adapters construct subsystems with no configuration at all.
    assertTrue(NetworkSettings.resolve(null).isStandalone());
  }

  @Test
  @DisplayName("network.enabled false wins even when a role is configured")
  void disabled_ignoresRole() {
    FakeConfig config = new FakeConfig().with("network.enabled", false).with("network.node.role", "worker");
    assertTrue(NetworkSettings.resolve(config).isStandalone());
  }

  @Test
  @DisplayName("enabled with role standalone stays standalone instead of inventing an identity")
  void enabledButStandaloneRole_staysStandalone() {
    FakeConfig config = new FakeConfig().with("network.enabled", true).with("network.node.role", "standalone");
    assertTrue(NetworkSettings.resolve(config).isStandalone());
  }

  @Test
  @DisplayName("a worker resolves its role preset and defaults its id to the role")
  void worker_resolvesPreset() {
    FakeConfig config = new FakeConfig().with("network.enabled", true).with("network.node.role", "worker");

    NodeIdentity identity = NetworkSettings.resolve(config);

    assertFalse(identity.isStandalone());
    assertEquals("worker", identity.nodeId());
    assertTrue(identity.can(NodeCapability.EXPERIENCES));
    assertFalse(identity.can(NodeCapability.LOBBY));
  }

  @Test
  @DisplayName("an explicit node id and display name are honoured")
  void explicitIdAndName() {
    FakeConfig config = new FakeConfig()
        .with("network.enabled", true)
        .with("network.node.role", "worker")
        .with("network.node.id", "worker-2")
        .with("network.node.display-name", "Worker 2");

    NodeIdentity identity = NetworkSettings.resolve(config);

    assertEquals("worker-2", identity.nodeId());
    assertEquals("Worker 2", identity.displayName());
  }

  @Test
  @DisplayName("an explicit capability list overrides the role preset, in either spelling")
  void explicitCapabilities_overridePreset() {
    FakeConfig config = new FakeConfig()
        .with("network.enabled", true)
        .with("network.node.role", "worker")
        .with("network.node.id", "worker-1")
        .with("network.node.capabilities", List.of("experiences", "QUEUE_AUTHORITY", "queue-authority"));

    NodeIdentity identity = NetworkSettings.resolve(config);

    assertTrue(identity.can(NodeCapability.EXPERIENCES));
    assertTrue(identity.can(NodeCapability.QUEUE_AUTHORITY));
    // MINIGAMES is in the worker preset but not in the explicit list, so it must be gone.
    assertFalse(identity.can(NodeCapability.MINIGAMES));
  }

  @Test
  @DisplayName("an unknown capability fails loudly rather than being dropped")
  void unknownCapability_throws() {
    FakeConfig config = new FakeConfig()
        .with("network.enabled", true)
        .with("network.node.role", "worker")
        .with("network.node.capabilities", List.of("teleportation"));

    assertThrows(IllegalArgumentException.class, () -> NetworkSettings.resolve(config));
  }

  @Test
  @DisplayName("blank capability entries are skipped, not treated as unknown")
  void blankCapabilityEntries_areSkipped() {
    FakeConfig config = new FakeConfig()
        .with("network.enabled", true)
        .with("network.node.role", "lobby")
        .with("network.node.capabilities", List.of("", "  ", "lobby"));

    NodeIdentity identity = NetworkSettings.resolve(config);

    assertTrue(identity.can(NodeCapability.LOBBY));
    assertFalse(identity.can(NodeCapability.EXPERIENCES));
  }

  // ===== I9: world-lease-seconds is in [3 x heartbeat, node-timeout) ==========================

  @Test
  @DisplayName("the shipped defaults satisfy the timing invariant")
  void defaults_satisfyTheInvariant() {
    NetworkSettings.Timings timings = NetworkSettings.timings(new FakeConfig());

    assertEquals(5L, timings.heartbeatSeconds());
    assertEquals(15L, timings.worldLeaseSeconds());
    assertEquals(30L, timings.nodeTimeoutSeconds());
    assertEquals(15_000L, timings.worldLeaseMillis());
    assertEquals(30_000L, timings.nodeTimeoutMillis());
    assertEquals(5_000L, timings.heartbeatMillis());
  }

  @Test
  @DisplayName("a null config still yields the defaults rather than throwing")
  void nullConfig_yieldsDefaults() {
    assertEquals(15L, NetworkSettings.timings(null).worldLeaseSeconds());
  }

  @Test
  @DisplayName("the OLD shipped pair (lease 60, timeout 30) is refused, naming both keys")
  void leaseLongerThanNodeTimeout_isRefused() {
    // This is exactly what was deployed: a node could be reaped and its worlds handed to a peer
    // while its own claim was still valid for another 30 seconds. Two writers, one region file.
    FakeConfig config = new FakeConfig()
        .with("network.enabled", true)
        .with("network.node.role", "worker")
        .with("network.world-lease-seconds", 60L)
        .with("network.node-timeout-seconds", 30L);

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> NetworkSettings.timings(config));

    assertTrue(refused.getMessage().contains("network.world-lease-seconds"));
    assertTrue(refused.getMessage().contains("network.node-timeout-seconds"));
  }

  @Test
  @DisplayName("a lease exactly equal to the node timeout is refused; the bound is strict")
  void leaseEqualToNodeTimeout_isRefused() {
    FakeConfig config = new FakeConfig()
        .with("network.world-lease-seconds", 30L)
        .with("network.node-timeout-seconds", 30L);

    assertThrows(IllegalStateException.class, () -> NetworkSettings.timings(config));
  }

  @Test
  @DisplayName("a lease shorter than three heartbeats is refused, naming both keys")
  void leaseShorterThanThreeHeartbeats_isRefused() {
    FakeConfig config = new FakeConfig()
        .with("network.heartbeat-seconds", 5L)
        .with("network.world-lease-seconds", 14L)
        .with("network.node-timeout-seconds", 30L);

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> NetworkSettings.timings(config));

    assertTrue(refused.getMessage().contains("network.world-lease-seconds"));
    assertTrue(refused.getMessage().contains("network.heartbeat-seconds"));
  }

  @Test
  @DisplayName("exactly three heartbeats is allowed; the lower bound is inclusive")
  void leaseOfExactlyThreeHeartbeats_isAllowed() {
    FakeConfig config = new FakeConfig()
        .with("network.heartbeat-seconds", 4L)
        .with("network.world-lease-seconds", 12L)
        .with("network.node-timeout-seconds", 30L);

    assertEquals(12L, NetworkSettings.timings(config).worldLeaseSeconds());
  }

  @Test
  @DisplayName("a non-positive heartbeat is clamped to one second rather than dividing by nothing")
  void nonPositiveHeartbeat_isClamped() {
    FakeConfig config = new FakeConfig()
        .with("network.heartbeat-seconds", 0L)
        .with("network.world-lease-seconds", 15L)
        .with("network.node-timeout-seconds", 30L);

    assertEquals(1L, NetworkSettings.timings(config).heartbeatSeconds());
  }

  @Test
  @DisplayName("validateTimings rejects a heartbeat of zero when called directly")
  void validateTimings_rejectsZeroHeartbeat() {
    assertThrows(IllegalStateException.class, () -> NetworkSettings.validateTimings(0L, 15L, 30L));
  }
}
