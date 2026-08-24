package com.sexidium.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeIdentityTest {

  @Test
  @DisplayName("standalone holds every capability, so every subsystem gate passes")
  void standalone_hasEveryCapability() {
    NodeIdentity identity = NodeIdentity.standalone();

    assertTrue(identity.isStandalone());
    assertEquals(NodeIdentity.LOCAL_NODE, identity.nodeId());
    for (NodeCapability capability : NodeCapability.values()) {
      assertTrue(identity.can(capability), "standalone must allow " + capability);
    }
  }

  @Test
  @DisplayName("an unknown role fails loudly instead of silently granting everything")
  void unknownRole_throws() {
    // The dangerous failure this guards: treating a typo as "standalone" hands a
    // restricted node every capability, which would start a Discord bot on all four.
    assertThrows(IllegalArgumentException.class, () -> NodeIdentity.capabilitiesForRole("worker-1"));
    assertThrows(IllegalArgumentException.class, () -> NodeIdentity.capabilitiesForRole("lobbby"));
  }

  @Test
  @DisplayName("blank and null roles mean standalone")
  void blankRole_isStandalone() {
    assertEquals(EnumSet.allOf(NodeCapability.class), NodeIdentity.capabilitiesForRole(null));
    assertEquals(EnumSet.allOf(NodeCapability.class), NodeIdentity.capabilitiesForRole("  "));
    assertEquals(EnumSet.allOf(NodeCapability.class), NodeIdentity.capabilitiesForRole("STANDALONE"));
  }

  @Test
  @DisplayName("a worker hosts experiences and minigames but never the lobby or the queue")
  void workerRole_capabilities() {
    Set<NodeCapability> worker = NodeIdentity.capabilitiesForRole("worker");

    assertTrue(worker.contains(NodeCapability.EXPERIENCES));
    assertTrue(worker.contains(NodeCapability.MINIGAMES));
    assertFalse(worker.contains(NodeCapability.LOBBY));
    assertFalse(worker.contains(NodeCapability.QUEUE_AUTHORITY));
    assertFalse(worker.contains(NodeCapability.BOT_HOST));
    assertFalse(worker.contains(NodeCapability.PACK_HOST));
  }

  @Test
  @DisplayName("exactly one role in a network owns the queue, the bot and the pack")
  void networkRoles_haveSingleOwners() {
    Set<NodeCapability> lobby = NodeIdentity.capabilitiesForRole("lobby");
    Set<NodeCapability> worker = NodeIdentity.capabilitiesForRole("worker");
    Set<NodeCapability> proxy = NodeIdentity.capabilitiesForRole("proxy");

    // Four backends each offering a resource pack means four SHA-1s and a re-prompt
    // on every server switch; four bot hosts means four Discord gateways on one token.
    assertEquals(1, count(NodeCapability.PACK_HOST, lobby, worker, proxy));
    assertEquals(1, count(NodeCapability.BOT_HOST, lobby, worker, proxy));
    assertEquals(1, count(NodeCapability.QUEUE_AUTHORITY, lobby, worker, proxy));
    assertEquals(1, count(NodeCapability.ROUTER, lobby, worker, proxy));
    assertEquals(1, count(NodeCapability.LOBBY, lobby, worker, proxy));
  }

  @Test
  @DisplayName("the proxy owns no world-bound capability")
  void proxyRole_ownsNoWorlds() {
    Set<NodeCapability> proxy = NodeIdentity.capabilitiesForRole("proxy");

    assertFalse(proxy.contains(NodeCapability.LOBBY));
    assertFalse(proxy.contains(NodeCapability.EXPERIENCES));
    assertFalse(proxy.contains(NodeCapability.MINIGAMES));
    assertTrue(proxy.contains(NodeCapability.ROUTER));
  }

  @Test
  @DisplayName("a configured identity is not standalone and reports its own id")
  void configuredIdentity() {
    NodeIdentity worker =
        NodeIdentity.of("worker-2", "Worker 2", NodeIdentity.capabilitiesForRole("worker"));

    assertFalse(worker.isStandalone());
    assertEquals("worker-2", worker.nodeId());
    assertEquals("Worker 2", worker.displayName());
    assertTrue(worker.can(NodeCapability.EXPERIENCES));
    assertFalse(worker.can(NodeCapability.LOBBY));
    assertTrue(worker.toString().contains("worker-2"));
  }

  @Test
  @DisplayName("displayName falls back to the node id when blank")
  void displayName_fallsBackToNodeId() {
    NodeIdentity node = NodeIdentity.of("worker-3", "", NodeIdentity.capabilitiesForRole("worker"));
    assertEquals("worker-3", node.displayName());
  }

  @Test
  @DisplayName("capabilities are immutable, so a caller cannot widen a node's rights")
  void capabilities_areImmutable() {
    NodeIdentity worker =
        NodeIdentity.of("worker-1", "Worker 1", NodeIdentity.capabilitiesForRole("worker"));

    assertThrows(
        UnsupportedOperationException.class,
        () -> worker.capabilities().add(NodeCapability.BOT_HOST));
  }

  @SafeVarargs
  private static int count(NodeCapability capability, Set<NodeCapability>... roles) {
    int seen = 0;
    for (Set<NodeCapability> role : roles) {
      if (role.contains(capability)) {
        seen++;
      }
    }
    return seen;
  }
}
