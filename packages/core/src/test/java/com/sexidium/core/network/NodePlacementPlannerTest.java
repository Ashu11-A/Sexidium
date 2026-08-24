package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug this class exists to make impossible: every world ending up on the lobby because the node
 * that asked was always the node that got it.
 */
class NodePlacementPlannerTest {

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
    database = new Database(new File(tmp.toFile(), "planner.db"));
  }

  private NodeIdentity identity(String nodeId, String role) {
    return NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role));
  }

  private NodeRegistry registerAlive(String nodeId, String role, int players, int worlds) {
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity(nodeId, role), TIMEOUT);
    registry.heartbeat(players, worlds);
    return registry;
  }

  private NodePlacementPlanner plannerOn(String nodeId, String role) {
    NodeIdentity identity = identity(nodeId, role);
    return new NodePlacementPlanner(new NodeRegistry(database, SILENT, identity, TIMEOUT), identity);
  }

  @Test
  @DisplayName("a worker is chosen over the lobby even when the lobby is emptier")
  void lobbyIsTheLastCandidate() {
    registerAlive("lobby", "lobby", 0, 0);
    registerAlive("worker-1", "worker", 7, 3);

    assertEquals(Optional.of("worker-1"), plannerOn("lobby", "lobby").choose(NodeCapability.EXPERIENCES));
  }

  @Test
  @DisplayName("among workers, the least loaded wins")
  void leastLoadedWins() {
    registerAlive("worker-1", "worker", 5, 1);
    registerAlive("worker-2", "worker", 1, 4);
    registerAlive("worker-3", "worker", 1, 2);

    assertEquals(Optional.of("worker-3"), plannerOn("lobby", "lobby").choose(NodeCapability.EXPERIENCES));
  }

  @Test
  @DisplayName("a node without the capability is never chosen, however idle")
  void capabilityIsNotNegotiable() {
    registerAlive("proxy", "proxy", 0, 0);
    registerAlive("worker-1", "worker", 30, 9);

    assertEquals(Optional.of("worker-1"), plannerOn("lobby", "lobby").choose(NodeCapability.EXPERIENCES));
    // The lobby holds QUEUE_AUTHORITY; the proxy is the only ROUTER.
    assertEquals(Optional.of("proxy"), plannerOn("lobby", "lobby").choose(NodeCapability.ROUTER));
  }

  @Test
  @DisplayName("a draining node stops receiving new worlds")
  void drainingIsExcluded() {
    NodeRegistry worker = registerAlive("worker-1", "worker", 0, 0);
    registerAlive("worker-2", "worker", 8, 8);
    worker.draining();

    assertEquals(Optional.of("worker-2"), plannerOn("lobby", "lobby").choose(NodeCapability.EXPERIENCES));
  }

  @Test
  @DisplayName("with nothing alive, a capable node still hosts its own worlds")
  void fallsBackToSelfWhenCapable() {
    // Nobody has ever heartbeated: the registry is empty, which is also what a database blip looks like.
    assertEquals(Optional.of("worker-1"), plannerOn("worker-1", "worker").choose(NodeCapability.EXPERIENCES));
  }

  @Test
  @DisplayName("a node that cannot host says so instead of hosting anyway")
  void refusesWhenIncapable() {
    assertTrue(plannerOn("proxy", "proxy").choose(NodeCapability.EXPERIENCES).isEmpty());
  }

  @Test
  @DisplayName("standalone is always the answer to itself")
  void standaloneChoosesItself() {
    NodePlacementPlanner planner = new NodePlacementPlanner(null, NodeIdentity.standalone());
    assertEquals(Optional.of(NodeIdentity.LOCAL_NODE), planner.choose(NodeCapability.EXPERIENCES));
    assertTrue(planner.isAlive(NodeIdentity.LOCAL_NODE));
  }

  @Test
  @DisplayName("liveness distinguishes a registered node from a dead one")
  void livenessIsCheckable() {
    registerAlive("worker-1", "worker", 0, 0);
    NodePlacementPlanner planner = plannerOn("lobby", "lobby");

    assertTrue(planner.isAlive("worker-1"));
    assertTrue(planner.isAlive("lobby"), "this process is up whatever the table says");
    assertFalse(planner.isAlive("worker-9"));
    assertFalse(planner.isAlive(null));
  }
}
