package com.sexidium.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What each role actually starts.
 *
 * <p>Mirrors {@code SexidiumCore.start()}'s gating table rather than booting a whole core, which
 * would need a running server. The value is in the table itself: it is the one place a subsystem
 * declares what it needs, and the invariants below are the ones that go wrong quietly — four Discord
 * bots on one token, four resource-pack hosts handing out four SHA-1s, two matchmakers racing.
 */
class CapabilityGatingTest {

  /** The gating table from SexidiumCore.start(), in order. */
  private static final List<String[]> SUBSYSTEMS = List.of(
      new String[] {"api", NodeCapability.API_HOST.name()},
      new String[] {"resource-pack", NodeCapability.PACK_HOST.name()},
      new String[] {"discord-bot", NodeCapability.BOT_HOST.name()},
      new String[] {"bridge", NodeCapability.BOT_HOST.name()},
      new String[] {"lobby-hud", NodeCapability.LOBBY.name()},
      new String[] {"npcs", NodeCapability.LOBBY.name()},
      new String[] {"decor", NodeCapability.LOBBY.name()},
      new String[] {"lobbies", NodeCapability.QUEUE_AUTHORITY.name()});

  private static Set<String> started(String role) {
    Set<NodeCapability> held = NodeIdentity.capabilitiesForRole(role);
    Set<String> started = new LinkedHashSet<>();
    for (String[] subsystem : SUBSYSTEMS) {
      if (held.contains(NodeCapability.valueOf(subsystem[1]))) {
        started.add(subsystem[0]);
      }
    }
    return started;
  }

  @Test
  @DisplayName("standalone starts everything, so a single server is unchanged")
  void standalone_startsEverything() {
    Set<String> started = started("standalone");

    for (String[] subsystem : SUBSYSTEMS) {
      assertTrue(started.contains(subsystem[0]), subsystem[0] + " must start on a standalone server");
    }
  }

  @Test
  @DisplayName("a worker starts no lobby subsystem")
  void worker_startsNoLobbySubsystems() {
    Set<String> started = started("worker");

    assertFalse(started.contains("lobby-hud"));
    assertFalse(started.contains("npcs"));
    assertFalse(started.contains("decor"));
    assertFalse(started.contains("lobbies"));
    // It still needs its own API endpoint for the bridge.
    assertTrue(started.contains("api"));
  }

  @Test
  @DisplayName("a worker runs neither the Discord bot nor the resource-pack host")
  void worker_runsNoSingletons() {
    Set<String> started = started("worker");

    assertFalse(started.contains("discord-bot"),
        "four workers each starting the bot would open four Discord gateways on one token");
    assertFalse(started.contains("resource-pack"),
        "several pack hosts hand clients several SHA-1s and re-prompt on every server switch");
  }

  @Test
  @DisplayName("the lobby owns the queue and the lobby world")
  void lobby_ownsQueueAndWorld() {
    Set<String> started = started("lobby");

    assertTrue(started.contains("lobbies"));
    assertTrue(started.contains("lobby-hud"));
    assertTrue(started.contains("npcs"));
    assertTrue(started.contains("decor"));
  }

  @Test
  @DisplayName("across a 1-lobby + 3-worker network each singleton starts exactly once")
  void singletonsStartExactlyOnce() {
    List<Set<String>> network = new ArrayList<>();
    network.add(started("proxy"));
    network.add(started("lobby"));
    network.add(started("worker"));
    network.add(started("worker"));
    network.add(started("worker"));

    assertEquals(1, count(network, "discord-bot"));
    assertEquals(1, count(network, "resource-pack"));
    assertEquals(1, count(network, "lobbies"));
    assertEquals(1, count(network, "lobby-hud"));
  }

  /**
   * Mirrors the gate in {@code PaperSexidiumPlugin.provisionWorlds()}. Lobby provisioning is not a
   * subsystem in the table above (it runs before the core exists), but it is the most destructive
   * capability-gated thing the plugin does: {@code LobbyBundle} deletes the target folder before
   * extracting, so a worker doing it against a shared world root would erase a live lobby.
   */
  @Test
  @DisplayName("only a lobby node (or standalone) may seed and clear the lobby world")
  void onlyTheLobbyNodeProvisionsTheLobbyWorld() {
    assertTrue(NodeIdentity.capabilitiesForRole("standalone").contains(NodeCapability.LOBBY),
        "a standalone server must keep provisioning its lobby exactly as before");
    assertTrue(NodeIdentity.capabilitiesForRole("lobby").contains(NodeCapability.LOBBY));
    assertFalse(NodeIdentity.capabilitiesForRole("worker").contains(NodeCapability.LOBBY),
        "a worker must never run LobbyBundle: it deletes the world folder before extracting");
    assertFalse(NodeIdentity.capabilitiesForRole("proxy").contains(NodeCapability.LOBBY));
  }

  /**
   * The map-authority role table. Companion to {@code MapAuthorityGateTest}, which proves the three
   * map commands actually consult this capability — a capability that is declared and never asked
   * about is the failure mode EXPERIENCES and MINIGAMES already fell into, and it looks exactly like
   * a working gate from here.
   */
  @Test
  @DisplayName("exactly one backend role may write map templates")
  void mapAuthority_isTheLobbyOnly() {
    assertTrue(NodeIdentity.capabilitiesForRole("standalone").contains(NodeCapability.MAP_AUTHORITY),
        "a standalone server must keep editing its own maps exactly as before: it is the only node there is");
    assertTrue(NodeIdentity.capabilitiesForRole("lobby").contains(NodeCapability.MAP_AUTHORITY),
        "the lobby is where an admin lands without being routed, and there is exactly one of it");
    assertFalse(NodeIdentity.capabilitiesForRole("worker").contains(NodeCapability.MAP_AUTHORITY),
        "a worker editing a template makes its copy silently diverge from the other three nodes");
    assertFalse(NodeIdentity.capabilitiesForRole("proxy").contains(NodeCapability.MAP_AUTHORITY),
        "the proxy hosts no worlds at all");
  }

  @Test
  @DisplayName("a worker still hosts matches, which only ever clone a template")
  void mapAuthority_isNotMinigames() {
    Set<NodeCapability> worker = NodeIdentity.capabilitiesForRole("worker");

    assertTrue(worker.contains(NodeCapability.MINIGAMES),
        "reading a template to clone it is not writing one; workers must keep running matches");
    assertFalse(worker.contains(NodeCapability.MAP_AUTHORITY));
  }

  @Test
  @DisplayName("across the network exactly one node may write a map template")
  void mapAuthority_isASingletonAcrossTheNetwork() {
    List<Set<NodeCapability>> network = List.of(
        NodeIdentity.capabilitiesForRole("proxy"),
        NodeIdentity.capabilitiesForRole("lobby"),
        NodeIdentity.capabilitiesForRole("worker"),
        NodeIdentity.capabilitiesForRole("worker"),
        NodeIdentity.capabilitiesForRole("worker"));

    long writers = network.stream().filter(held -> held.contains(NodeCapability.MAP_AUTHORITY)).count();
    assertEquals(1, writers,
        "two writers means two templates, and the divergence only surfaces as a match on the wrong layout");
  }

  @Test
  @DisplayName("the proxy starts no world-bound subsystem")
  void proxy_startsNoWorldSubsystems() {
    Set<String> started = started("proxy");

    assertFalse(started.contains("lobby-hud"));
    assertFalse(started.contains("npcs"));
    assertFalse(started.contains("decor"));
    // It does host the pack. It does NOT host the bot: the proxy runs module-velocity, which never
    // builds SexidiumCore and so never reaches the line this capability gates -- the bot was
    // therefore started by nobody, while the provisioning scripts pointed it at the lobby all along.
    assertFalse(started.contains("discord-bot"),
        "a proxy has no SexidiumCore, so BOT_HOST there is a capability nothing can honour");
    assertTrue(started.contains("resource-pack"));
  }

  @Test
  @DisplayName("the lobby hosts the Discord bot, because it is the only singleton node that can")
  void lobby_hostsTheBot() {
    Set<String> started = started("lobby");

    assertTrue(started.contains("discord-bot"),
        "docker/stack.sexidium.yml (SX_BOT_NODE) and scripts/lib/sexidium.sh (bot.enabled) both"
            + " point here; the capability now agrees with them");
    assertTrue(started.contains("bridge"));
  }

  private static int count(List<Set<String>> network, String subsystem) {
    int seen = 0;
    for (Set<String> node : network) {
      if (node.contains(subsystem)) {
        seen++;
      }
    }
    return seen;
  }
}
