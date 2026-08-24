package com.sexidium.core.network;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chooses which node should host a world that does not have a home yet.
 *
 * <p>This is the piece the network was missing. {@link DbWorldLeaseAuthority#claim} assigns an
 * unplaced world to <em>whichever node asked</em>, and the only node players ever reach is the
 * lobby, so every experience ended up homed on the lobby and the workers sat empty holding nothing
 * but their warm pool. The capabilities {@code EXPERIENCES} and {@code MINIGAMES} were declared and
 * never read: this class is the one place that reads them.</p>
 *
 * <p>The choice has exactly three inputs, in strict priority order:</p>
 *
 * <ol>
 *   <li><b>Existing ownership</b> — not decided here at all. A world with a placement row is homed
 *       where that row says, forever, because its chunks are on that machine's disk. The caller
 *       consults {@link DbWorldLeaseAuthority#lookup} first and only asks this class about worlds
 *       nobody owns. Re-choosing a home for an existing world is how player saves get overwritten.</li>
 *   <li><b>Capability</b> — a node that does not declare the capability is not a candidate, whatever
 *       its load. A proxy has no disk for worlds; a lobby-only node must not be handed experiences.</li>
 *   <li><b>Load</b> — fewest players, then fewest worlds, then node id so the choice is deterministic
 *       and two nodes planning at the same instant agree (the placement row's primary key settles
 *       the remaining race, but agreeing first means it almost never happens).</li>
 * </ol>
 *
 * <p><b>The lobby is always the last candidate.</b> Not excluded — a two-container topology (proxy +
 * lobby) must still work — but ranked behind every dedicated worker regardless of load, because the
 * lobby's tick is shared by every player on the network: an experience generating terrain there is
 * felt by people who are not in it, while the same work on a worker is felt only by its occupants.</p>
 */
public final class NodePlacementPlanner {

  private final NodeRegistry registry;
  private final NodeIdentity identity;
  /**
   * This build's wire tag, so a peer too old to be handed ownership is not offered any.
   *
   * <p>Injected rather than read from {@link Protocol}, which is what lets one test JVM stand a
   * build-N node and a build-N+1 node up against the same database.</p>
   */
  private final ProtocolTag protocolTag;

  /** {@code registry} is null when standalone, where this node is the only possible answer. */
  public NodePlacementPlanner(NodeRegistry registry, NodeIdentity identity) {
    this(registry, identity, ProtocolTag.current());
  }

  public NodePlacementPlanner(NodeRegistry registry, NodeIdentity identity, ProtocolTag protocolTag) {
    this.registry = registry;
    this.identity = identity;
    this.protocolTag = protocolTag == null ? ProtocolTag.current() : protocolTag;
  }

  /**
   * The node that should host a new world needing {@code required}.
   *
   * <p>Empty means <em>nobody can host this</em> — every capable node is down or draining — which the
   * caller must report to the player rather than paper over. Falling back to "host it here" would put
   * an experience on a node the operator deliberately excluded (the proxy, or a lobby in a topology
   * where the lobby holds no worlds), and once the folder exists there it is stuck there.</p>
   */
  public Optional<String> choose(NodeCapability required) {
    return choose(required, List.of());
  }

  /**
   * The node that should host a new world needing {@code required} <em>and</em> the content codes in
   * {@code requiredCodes}.
   *
   * <p>Two gates beyond capability, and both close the same class of failure — a node that says yes
   * to a world it cannot actually run:</p>
   *
   * <ul>
   *   <li><b>Content.</b> A node whose challenge catalog is missing one of the world's challenges
   *       answers "no, this does not need a void world" to the four world-shaping questions and
   *       generates <em>normal terrain over an empty SkyBlock</em>. Silently, permanently, and with no
   *       version skew required — a typo in the row is enough. The published code set is the reader-
   *       side answer to that.</li>
   *   <li><b>Protocol.</b> A peer below this build's {@code MIN_PEER} cannot be handed ownership,
   *       because during a roll the old nodes are the receivers and an old build cannot know a tag
   *       that did not exist when it was compiled. It stays routable and keeps its players — nothing
   *       player-facing is ever gated on the tag — it is only excluded as an ownership target.</li>
   * </ul>
   *
   * <p>A peer that published <em>nothing</em> (an older build, or one that has not wired its manifest
   * yet) is treated as unknown rather than as bad: it passes both gates. Excluding it would empty the
   * candidate list on the first roll of a fleet that has never published a manifest, and a drain with
   * no candidates has nowhere to put its worlds.</p>
   */
  public Optional<String> choose(NodeCapability required, java.util.Collection<String> requiredCodes) {
    if (registry == null) {
      // Standalone holds every capability by construction, so this is always the local node.
      return identity.can(required) ? Optional.of(identity.nodeId()) : Optional.empty();
    }
    List<NodeRegistry.Node> candidates = registry.with(required).stream()
        .filter(node -> NodeRegistry.STATE_UP.equals(node.state()))
        .filter(this::acceptsOwnership)
        .filter(node -> canRun(node, requiredCodes))
        .sorted(byPreference())
        .toList();
    if (!candidates.isEmpty()) {
      return Optional.of(candidates.get(0).nodeId());
    }
    // Nothing alive answered. This node is still a legitimate host if it holds the capability —
    // it is demonstrably up, whatever the registry believes about the rest of the world — and this
    // is what keeps a network working during a database hiccup that empties the node list.
    return identity.can(required) ? Optional.of(identity.nodeId()) : Optional.empty();
  }

  /**
   * Whether this build may hand ownership to that peer.
   *
   * <p>A peer that published {@link Protocol#UNKNOWN} predates the column being written at all and is
   * accepted: "silent" is not "incompatible", and refusing every such peer would make the first roll
   * onto a tagged build the one roll that cannot complete.</p>
   */
  private boolean acceptsOwnership(NodeRegistry.Node node) {
    return node.protocol() == Protocol.UNKNOWN || protocolTag.acceptsOwnershipFrom(node.protocol());
  }

  /**
   * Whether a peer's published content covers a requirement.
   *
   * <p>A peer whose {@code content_codes} is null or blank has published nothing — it is on a build
   * that predates the column, or it has not wired its manifest yet — and that reads as UNKNOWN, not
   * as EMPTY. Reading it as empty would exclude every node in a fleet on the previous build, which is
   * exactly the fleet a roll starts from.</p>
   */
  private static boolean canRun(NodeRegistry.Node node, java.util.Collection<String> requiredCodes) {
    if (requiredCodes == null || requiredCodes.isEmpty()) {
      return true;
    }
    String published = node.contentCodes();
    if (published == null || published.isBlank()) {
      return true;
    }
    return ContentManifest.covers(published, requiredCodes);
  }

  private static Comparator<NodeRegistry.Node> byPreference() {
    return Comparator.<NodeRegistry.Node>comparingInt(node -> hosts(node, NodeCapability.LOBBY) ? 1 : 0)
        .thenComparingInt(NodeRegistry.Node::players)
        .thenComparingInt(NodeRegistry.Node::worlds)
        .thenComparing(NodeRegistry.Node::nodeId);
  }

  private static boolean hosts(NodeRegistry.Node node, NodeCapability capability) {
    return node.capabilities() != null
        && List.of(node.capabilities().split(",")).contains(capability.name().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether a node is currently reachable.
   *
   * <p>Asked before a player is sent anywhere. Routing someone to a node that is down produces the
   * worst failure this system has: the proxy either cannot find the server or the connection is
   * refused, and the player is left with a generic error about a world they know exists. Saying
   * "that world's server is offline" instead is the difference between a bug report and a shrug.</p>
   */
  /**
   * The epoch a transfer ticket to {@code ownerNodeId} should be addressed to, given what the
   * placement row records.
   *
   * <p>This closes the {@code epoch 0} fencing gap, seen live in the {@code routeToOwningNode} trace.
   * The root cause is one branch of {@link DbWorldLeaseAuthority#claimOn}: a world PLANNED onto a peer
   * records {@code node_epoch = 0}, because the node doing the planning is recording a decision and
   * has no business stamping another node's boot. That write is correct. The READ was not — it took
   * the 0 at face value, and {@code TransferTicket.addressedTo} treats 0 as "any node, any boot", so
   * the ticket still landed but the fence was inert for that world: a worker that restarted between
   * the plan and the arrival happily consumed a ticket minted for its predecessor.</p>
   *
   * <p>So a recorded 0 means <em>unknown</em>, and the owner's live epoch from the registry is the
   * answer. Falls back to 0 when that node is not registered — the old behaviour, which still routes;
   * an unfenced transfer beats no transfer.</p>
   */
  public long ownerEpoch(long recordedEpoch, String ownerNodeId) {
    if (recordedEpoch != 0L || ownerNodeId == null || registry == null) {
      return recordedEpoch;
    }
    return registry.all().stream()
        .filter(node -> ownerNodeId.equals(node.nodeId()))
        .mapToLong(NodeRegistry.Node::epoch)
        .findFirst()
        .orElse(0L);
  }

  public boolean isAlive(String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      return false;
    }
    if (nodeId.equals(identity.nodeId())) {
      return true; // This process is running; nothing in a table can say otherwise.
    }
    if (registry == null) {
      return false;
    }
    return registry.alive().stream()
        .anyMatch(node -> node.nodeId().equals(nodeId) && !NodeRegistry.STATE_DOWN.equals(node.state()));
  }
}
