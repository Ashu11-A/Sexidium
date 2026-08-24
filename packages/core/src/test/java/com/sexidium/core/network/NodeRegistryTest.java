package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRegistryTest {

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

  private NodeRegistry registry(String nodeId, String role) {
    return new NodeRegistry(
        database, SILENT,
        NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role)),
        TIMEOUT);
  }

  /** Ages a node's heartbeat so the reaper considers it stale, without waiting. */
  private void ageHeartbeat(String nodeId, long millisAgo) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection()
          .prepareStatement("UPDATE network_nodes SET heartbeat_at = ? WHERE node_id = ?")) {
        ps.setLong(1, System.currentTimeMillis() - millisAgo);
        ps.setString(2, nodeId);
        ps.executeUpdate();
      }
    }
  }

  @Test
  @DisplayName("a heartbeat registers the node and is visible to its peers")
  void heartbeat_registersNode() {
    registry("worker-1", "worker").heartbeat(3, 2);

    List<NodeRegistry.Node> nodes = registry("lobby", "lobby").all();

    assertEquals(1, nodes.size());
    assertEquals("worker-1", nodes.get(0).nodeId());
    assertEquals("worker", nodes.get(0).role());
    assertEquals(3, nodes.get(0).players());
    assertEquals(2, nodes.get(0).worlds());
  }

  @Test
  @DisplayName("repeated heartbeats update in place rather than inserting duplicates")
  void heartbeat_isIdempotent() {
    NodeRegistry worker = registry("worker-1", "worker");
    worker.heartbeat(1, 1);
    worker.heartbeat(2, 2);
    worker.heartbeat(5, 4);

    List<NodeRegistry.Node> nodes = worker.all();

    assertEquals(1, nodes.size());
    assertEquals(5, nodes.get(0).players());
  }

  @Test
  @DisplayName("all four nodes of the target topology register independently")
  void fullTopology() {
    registry("lobby", "lobby").heartbeat(10, 1);
    registry("worker-1", "worker").heartbeat(4, 3);
    registry("worker-2", "worker").heartbeat(0, 0);
    registry("worker-3", "worker").heartbeat(7, 5);

    assertEquals(4, registry("lobby", "lobby").alive().size());
  }

  @Test
  @DisplayName("capability lookup finds the nodes that could host a world")
  void capabilityLookup() {
    registry("lobby", "lobby").heartbeat(0, 0);
    registry("worker-1", "worker").heartbeat(0, 0);
    registry("worker-2", "worker").heartbeat(0, 0);

    NodeRegistry view = registry("lobby", "lobby");

    // Only workers carry EXPERIENCES; the lobby must never be offered a persistent world.
    List<String> hosts = view.with(NodeCapability.EXPERIENCES).stream()
        .map(NodeRegistry.Node::nodeId).sorted().toList();
    assertEquals(List.of("worker-1", "worker-2"), hosts);

    List<String> lobbies = view.with(NodeCapability.LOBBY).stream()
        .map(NodeRegistry.Node::nodeId).toList();
    assertEquals(List.of("lobby"), lobbies);
  }

  @Test
  @DisplayName("a node whose heartbeat aged out is no longer alive")
  void staleNode_isNotAlive() throws Exception {
    registry("worker-1", "worker").heartbeat(0, 0);
    registry("worker-2", "worker").heartbeat(0, 0);
    ageHeartbeat("worker-2", TIMEOUT * 2);

    List<String> alive = registry("lobby", "lobby").alive().stream()
        .map(NodeRegistry.Node::nodeId).toList();

    assertEquals(List.of("worker-1"), alive);
  }

  @Test
  @DisplayName("the reaper marks a stale node DOWN and reports it once")
  void reaper_marksDownOnce() throws Exception {
    registry("worker-1", "worker").heartbeat(0, 0);
    registry("worker-2", "worker").heartbeat(0, 0);
    ageHeartbeat("worker-2", TIMEOUT * 2);

    NodeRegistry lobby = registry("lobby", "lobby");
    lobby.heartbeat(0, 0);

    assertEquals(List.of("worker-2"), lobby.reapStaleNodes());
    // Idempotent: a second sweep has nothing left to transition, so peers are not
    // told twice that the same node died.
    assertTrue(lobby.reapStaleNodes().isEmpty());
  }

  @Test
  @DisplayName("the reaper never marks the node it is running on")
  void reaper_skipsSelf() throws Exception {
    NodeRegistry worker = registry("worker-1", "worker");
    worker.heartbeat(0, 0);
    ageHeartbeat("worker-1", TIMEOUT * 2);

    // A long GC pause must not make a node declare ITSELF dead and stop serving.
    assertTrue(worker.reapStaleNodes().isEmpty());
  }

  @Test
  @DisplayName("a node that checks in between read and write is not reaped")
  void reaper_losesRaceSafely() throws Exception {
    registry("worker-2", "worker").heartbeat(0, 0);
    ageHeartbeat("worker-2", TIMEOUT * 2);

    NodeRegistry lobby = registry("lobby", "lobby");
    // Simulate worker-2 heartbeating after the reaper read the registry: the guarded
    // UPDATE matches nothing, so it survives.
    List<NodeRegistry.Node> stale = lobby.all();
    assertFalse(stale.isEmpty());
    registry("worker-2", "worker").heartbeat(1, 1);

    assertTrue(lobby.reapStaleNodes().isEmpty());
    assertTrue(lobby.alive().stream().anyMatch(node -> node.nodeId().equals("worker-2")));
  }

  @Test
  @DisplayName("draining keeps the node registered but out of the alive set for placement")
  void draining() {
    NodeRegistry worker = registry("worker-1", "worker");
    worker.heartbeat(5, 2);
    worker.draining();

    NodeRegistry.Node node = worker.all().get(0);
    assertEquals(NodeRegistry.STATE_DRAINING, node.state());
    // Still alive (it is serving players) but a placement policy reads state, not liveness.
    assertTrue(node.alive(System.currentTimeMillis(), TIMEOUT));
  }

  @Test
  @DisplayName("epoch differs across boots, giving placements a fencing token")
  void epochIsPerBoot() throws Exception {
    NodeRegistry firstBoot = registry("worker-1", "worker");
    firstBoot.heartbeat(0, 0);
    long first = firstBoot.epoch();

    Thread.sleep(2);
    NodeRegistry secondBoot = registry("worker-1", "worker");
    secondBoot.heartbeat(0, 0);

    assertTrue(secondBoot.epoch() > first,
        "a returning node must be distinguishable from the one that died");
    assertEquals(secondBoot.epoch(), secondBoot.all().get(0).epoch());
  }
}
