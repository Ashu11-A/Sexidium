package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.NodeHealthPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a node publishes about itself, and how a reader must treat a node that publishes nothing.
 *
 * <p>Four of these columns ({@code protocol}, {@code plugin_version}, {@code max_players},
 * {@code max_worlds}) existed in the schema and were written by nobody, so "is that node on the build
 * I staged?" — the one question a rolling update has to answer before it moves on — was unanswerable
 * from outside the container.</p>
 */
class NodeTelemetryTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final long TIMEOUT = 30_000L;

  @TempDir
  Path tmp;

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "net.db"));
  }

  private NodeRegistry registry(String nodeId, String role, ProtocolTag tag) {
    return new NodeRegistry(database, SILENT,
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role)), TIMEOUT, tag);
  }

  private NodeRegistry.Node read(String nodeId) {
    return registry("reader", "worker", ProtocolTag.current()).all().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @DisplayName("build, content, capacity and tick health all reach the shared table")
  void publishesProtocolBuildContentAndCapacity() {
    NodeRegistry worker = registry("worker-1", "worker", new ProtocolTag(3L, 2L));
    worker.publishTelemetry(new NodeRegistry.Telemetry(
        "1.0.0+a1b2c3d4e5", "9f86d081", "3a7f1c0b9d2e4f56", "c:cleave,m:tntwar", 80, 12));
    worker.publishHealth(new NodeHealthPort() {
      @Override public int tpsTimes100() { return 1987; }
      @Override public int msptTimes100() { return 1240; }
      @Override public int heapUsedMb() { return 1840; }
      @Override public int heapMaxMb() { return 3072; }
    });
    worker.heartbeat(7, 3);

    NodeRegistry.Node row = read("worker-1");
    assertEquals(3L, row.protocol(), "the tag is published from the INJECTED ProtocolTag");
    assertEquals("1.0.0+a1b2c3d4e5", row.pluginVersion());
    assertEquals("9f86d081", row.buildSha());
    assertEquals("3a7f1c0b9d2e4f56", row.contentDigest());
    assertEquals("c:cleave,m:tntwar", row.contentCodes());
    assertEquals(80, row.maxPlayers());
    assertEquals(12, row.maxWorlds());
    assertEquals(1987, row.tps());
    assertEquals(1240, row.mspt());
    assertEquals(1840, row.heapUsedMb());
    assertEquals(3072, row.heapMaxMb());
    assertEquals(7, row.players());
    assertEquals(3, row.worlds());
  }

  @Test
  @DisplayName("a node that publishes nothing reads as UNKNOWN, never as a node at zero TPS")
  void unpublishedTelemetryReadsAsUnknown() {
    registry("worker-2", "worker", ProtocolTag.current()).heartbeat(0, 0);
    NodeRegistry.Node row = read("worker-2");
    assertNull(row.pluginVersion());
    assertNull(row.contentDigest());
    assertEquals(0, row.maxWorlds());
    // -1, not 0. A saturation gate that read the default as "0 TPS" would exclude every node that
    // has not published yet -- which is every node in its first five seconds.
    assertEquals(NodeHealthPort.UNKNOWN, row.tps());
    assertEquals(NodeHealthPort.UNKNOWN, row.mspt());
  }

  @Test
  @DisplayName("a health probe that throws costs the reading, never the heartbeat")
  void aThrowingHealthProbeDoesNotBreakTheHeartbeat() {
    NodeRegistry worker = registry("worker-3", "worker", ProtocolTag.current());
    worker.publishHealth(new NodeHealthPort() {
      @Override public int tpsTimes100() { throw new IllegalStateException("no tick loop"); }
      @Override public int maxPlayers() { throw new IllegalStateException("nope"); }
    });
    worker.heartbeat(2, 1);
    NodeRegistry.Node row = read("worker-3");
    // The heartbeat still landed: a node that stops checking in gets reaped and loses its worlds,
    // which is far worse than a missing TPS reading.
    assertEquals(2, row.players());
    assertEquals(NodeHealthPort.UNKNOWN, row.tps());
    assertEquals(0, row.maxPlayers());
  }

  @Test
  @DisplayName("two builds coexist in one JVM against one database — the whole mixed-version trick")
  void twoProtocolTagsCoexistInOneJvm() {
    // Protocol.VERSION is never read at a call site, so a skew test needs no classloader games.
    registry("old-node", "worker", new ProtocolTag(1L, 1L)).heartbeat(0, 0);
    registry("new-node", "worker", new ProtocolTag(2L, 2L)).heartbeat(0, 0);

    ProtocolTag newBuild = new ProtocolTag(2L, 2L);
    List<NodeRegistry.Node> nodes = read("old-node") == null ? List.of() : List.of(read("old-node"));
    assertEquals(1L, nodes.get(0).protocol());
    assertTrue(newBuild.acceptsOwnershipFrom(read("new-node").protocol()));
    // The old node is below the new build's floor: usable, routable, keeps its players -- just never
    // handed ownership of anything.
    assertEquals("INCOMPATIBLE", newBuild.verdict(read("old-node").protocol()));
    // And the OLD build, whose floor is 1, is perfectly happy to take from the new one. The floor is
    // one-sided on purpose: the newer node is always the one that enforces.
    assertTrue(new ProtocolTag(1L, 1L).acceptsOwnershipFrom(read("new-node").protocol()));
  }

  @Test
  @DisplayName("the decoded content codes come back off the row")
  void contentCodesDecodeOffTheRow() {
    NodeRegistry worker = registry("worker-4", "worker", ProtocolTag.current());
    worker.publishTelemetry(new NodeRegistry.Telemetry(
        null, null, null, "c:cleave,m:tntwar", 0, 0));
    worker.heartbeat(0, 0);
    assertEquals(java.util.Set.of("c:cleave", "m:tntwar"), read("worker-4").codes());
  }
}
