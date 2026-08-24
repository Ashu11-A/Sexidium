package com.sexidium.velocity;

import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Which lobby an arriving or bounced player should land on.
 *
 * <p>The proxy used to answer this with {@code proxy.getServer("lobby")} and return the moment that
 * name existed — which it always does, because the provisioner writes it into velocity.toml. The
 * least-loaded fallback underneath was therefore dead code in every deployed topology, and a second
 * lobby would have received <b>zero</b> initial joins no matter how full the first one was. That one
 * short-circuit is what blocks lobby scale-out entirely, and it is also what makes draining a lobby
 * impossible: the fallback points at the node that is dying.</p>
 *
 * <p>Pure over {@code List<Node>} on purpose — no Velocity class appears in this file, so the whole
 * decision is unit-testable, which the previous version was not.</p>
 */
public final class LobbySelector {

  private LobbySelector() {
  }

  /**
   * The least-loaded live lobby, or empty when the registry names none.
   *
   * <p>Excludes DRAINING and DOWN nodes: a draining lobby is still routable for the players it
   * already has (that is the whole point of DRAINING) but must never be given a new one, or a drain
   * can never finish.</p>
   *
   * @param assumedCapacity slots to assume for a node that has not published {@code max_players},
   *     so the load band means something before a node has told us its size
   */
  public static Optional<String> choose(
      List<NodeRegistry.Node> nodes, long now, long timeoutMillis, int assumedCapacity,
      int bandPercent) {
    if (nodes == null) {
      return Optional.empty();
    }
    return nodes.stream()
        .filter(node -> node != null && node.nodeId() != null)
        .filter(node -> hosts(node, NodeCapability.LOBBY))
        .filter(node -> NodeRegistry.STATE_UP.equals(node.state()))
        .filter(node -> node.alive(now, timeoutMillis))
        .min(byLoad(assumedCapacity, bandPercent))
        .map(NodeRegistry.Node::nodeId);
  }

  /**
   * Banded load, so one player's difference does not flip the choice for every single join.
   *
   * <p>Inside a band the tiebreak is the world count and then the node id, which keeps the answer
   * deterministic: two proxies deciding at the same instant agree, and an operator reading the table
   * can predict where the next player goes.</p>
   */
  private static Comparator<NodeRegistry.Node> byLoad(int assumedCapacity, int bandPercent) {
    // Note what is NOT in this chain: the raw player count. Comparing it inside a band would undo
    // the band entirely — the emptier node would win by one player and every single join would
    // re-target the other lobby, which is the ping-pong the band exists to stop.
    return Comparator.<NodeRegistry.Node>comparingInt(node -> band(node, assumedCapacity, bandPercent))
        .thenComparingInt(NodeRegistry.Node::worlds)
        .thenComparing(NodeRegistry.Node::nodeId);
  }

  static int band(NodeRegistry.Node node, int assumedCapacity, int bandPercent) {
    int capacity = node.maxPlayers() > 0 ? node.maxPlayers() : Math.max(1, assumedCapacity);
    int width = Math.max(1, bandPercent);
    return (int) Math.floor(100.0 * node.players() / capacity / width);
  }

  /**
   * One proxy server, reduced to what the choice actually depends on.
   *
   * @param name    the server's name in {@code velocity.toml}, which is also its node id
   * @param players how many players the PROXY sees on it, which is live truth and needs no registry
   */
  public record Candidate(String name, int players) {
  }

  /**
   * The fallback tiers, for when {@link #choose} finds no live registered lobby.
   *
   * <p>All of this used to be three lines in the plugin, untested because a Velocity
   * {@code RegisteredServer} cannot be constructed in a unit test — and two of the three were wrong.
   * Tier 2 was a bare name lookup, so a DRAINING lobby went on being handed every arriving and every
   * kicked player, which is precisely how that node's {@code players_left} never reaches zero and its
   * drain stalls. Tier 3 was "the least-loaded server of any kind", which can hand a kicked player to
   * a worker: no lobby world, no menu, no NPCs, and nothing to do.</p>
   *
   * <p>The tiers, in order:</p>
   * <ol>
   *   <li>the server literally named {@code namedLobby} — the hand-written topology whose nodes never
   *       registered — unless its registry row says it is draining;</li>
   *   <li>the least-loaded server the registry says HOSTS a lobby and is not draining;</li>
   *   <li>a draining lobby, as a last resort. A stalled drain is recoverable in one command; a
   *       disconnected player is not, and this answer is what {@code KickedFromServerEvent} redirects
   *       to instead of letting the disconnect stand;</li>
   *   <li>and only when the registry knows of no lobby node at all, the least-loaded server of any
   *       kind — the pre-registry behaviour, unchanged, for a topology that never registers.</li>
   * </ol>
   */
  public static Optional<String> fallback(
      List<NodeRegistry.Node> nodes, List<Candidate> servers, String namedLobby) {
    if (servers == null || servers.isEmpty()) {
      return Optional.empty();
    }
    boolean named = namedLobby != null
        && servers.stream().anyMatch(server -> namedLobby.equals(server.name()));
    if (named && !draining(nodes, namedLobby)) {
      return Optional.of(namedLobby);
    }
    Optional<String> registered = leastLoaded(servers, lobbyNodeIds(nodes, false));
    if (registered.isPresent()) {
      return registered;
    }
    Optional<String> draining = leastLoaded(servers, lobbyNodeIds(nodes, true));
    if (draining.isPresent()) {
      return draining;
    }
    if (named) {
      return Optional.of(namedLobby);
    }
    if (nodes != null && nodes.stream()
        .anyMatch(node -> node != null && hosts(node, NodeCapability.LOBBY))) {
      // The registry names lobbies and none of them is a server this proxy knows. Handing the player
      // a worker instead would put them somewhere with no lobby world and no way out.
      return Optional.empty();
    }
    return leastLoaded(servers, null);
  }

  /** Node ids the registry says host a lobby, optionally including the ones that are draining. */
  private static List<String> lobbyNodeIds(List<NodeRegistry.Node> nodes, boolean includeDraining) {
    if (nodes == null) {
      return List.of();
    }
    return nodes.stream()
        .filter(node -> node != null && node.nodeId() != null)
        .filter(node -> hosts(node, NodeCapability.LOBBY))
        .filter(node -> includeDraining || !node.draining())
        .map(NodeRegistry.Node::nodeId)
        .toList();
  }

  /** Fewest players wins; the name breaks ties so two proxies deciding at once agree. */
  private static Optional<String> leastLoaded(List<Candidate> servers, List<String> allowedNames) {
    return servers.stream()
        .filter(server -> server != null && server.name() != null)
        .filter(server -> allowedNames == null || allowedNames.contains(server.name()))
        .min(Comparator.comparingInt(Candidate::players).thenComparing(Candidate::name))
        .map(Candidate::name);
  }

  /** Whether a node is draining. An unknown node is NOT draining — fail open, then route. */
  private static boolean draining(List<NodeRegistry.Node> nodes, String nodeId) {
    if (nodes == null || nodeId == null) {
      return false;
    }
    return nodes.stream()
        .filter(node -> node != null && nodeId.equals(node.nodeId()))
        .anyMatch(NodeRegistry.Node::draining);
  }

  private static boolean hosts(NodeRegistry.Node node, NodeCapability capability) {
    return node.capabilities() != null
        && List.of(node.capabilities().split(","))
            .contains(capability.name().toLowerCase(Locale.ROOT));
  }
}
