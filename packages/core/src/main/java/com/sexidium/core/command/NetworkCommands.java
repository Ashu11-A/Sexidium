package com.sexidium.core.command;

import com.sexidium.core.network.DbWorldLeaseAuthority;
import com.sexidium.core.network.NetworkInspector;
import com.sexidium.core.network.NodeRegistry;
import com.sexidium.core.network.transfer.TransferTicket;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;

import java.util.List;
import java.util.Optional;

import static com.sexidium.core.command.CommandText.lower;

/**
 * {@code /sx admin net …} — the operator's window into the network.
 *
 * <p>There was none. When a player landed in the wrong world the only evidence was a refusal line on
 * the deciding node (the wrong machine — the symptom is on the worker), one routing line on the proxy,
 * and nothing at all on the destination. Diagnosing the live transfer loop meant hand-querying MariaDB,
 * which tests the database rather than the system.</p>
 */
final class NetworkCommands {

  private static final String USAGE =
      "<gray>Usage: <white>/sx admin net <nodes|placements|transfers|stats|locate <experienceId>"
          + "|evict <worldKey>|report [--json]|protocol|capability [<nodeId>]"
          + "|drain [<reason>] [--force]|undrain|rebalance [--max <n>]></white></gray>";

  private final CommandContext ctx;

  NetworkCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  /** {@code args = [net, <sub>, …]}. */
  void handle(CommandSource source, String[] args) {
    NetworkInspector inspector = ctx.core.networkInspector();
    if (inspector == null) {
      // Standalone. Saying so beats an empty table that reads like a broken network.
      ctx.send(source, "<gray>This server is <white>standalone</white>: there is no network to"
          + " inspect. Every world it has, it owns.</gray>");
      return;
    }
    if (args.length < 2) {
      ctx.send(source, USAGE);
      return;
    }
    switch (lower(args[1])) {
      case "nodes" -> nodes(source, inspector);
      case "placements" -> placements(source, inspector, args.length > 2 ? args[2] : null);
      case "transfers" -> transfers(source, inspector);
      case "stats" -> stats(source, inspector);
      case "locate" -> locate(source, inspector, args.length > 2 ? args[2] : null);
      case "evict" -> evict(source, inspector, args.length > 2 ? args[2] : null);
      case "report" -> report(source, args);
      case "protocol" -> protocol(source, inspector);
      case "capability" -> capability(source, inspector, args.length > 2 ? args[2] : null);
      case "drain" -> drain(source, args);
      case "undrain" -> undrain(source);
      case "rebalance" -> rebalance(source);
      default -> ctx.send(source, USAGE);
    }
  }

  /**
   * The one-call status an orchestrator polls, and the one an operator reads. {@code --json} prints
   * exactly what {@code GET /network} returns, so a script driving the console and a script driving
   * HTTP are never reading two different answers.
   */
  private void report(CommandSource source, String[] args) {
    com.sexidium.core.network.NetworkService network = ctx.core.network();
    if (hasFlag(args, "--json")) {
      ctx.send(source, "<white>"
          + com.sexidium.core.network.NodeStatusReport.networkJson(network).replace("<", "\\<")
          + "</white>");
      return;
    }
    var registry = network.registry();
    java.util.List<com.sexidium.core.network.NodeRegistry.Node> nodes =
        registry == null ? java.util.List.of() : registry.all();
    int backendPlayers = 0;
    int proxyPlayers = 0;
    for (var node : nodes) {
      if ("proxy".equals(node.role())) {
        proxyPlayers += node.players();
      } else {
        backendPlayers += node.players();
      }
    }
    ctx.send(source, "<gold>Network report</gold>");
    // Two totals, because SUM(players) over the whole table is about twice reality: the proxy row
    // counts the whole network and every backend row counts its own players again.
    ctx.send(source, "<gray>· players (network, from the proxy row): <white>"
        + (proxyPlayers > 0 ? proxyPlayers : backendPlayers) + "</white></gray>");
    ctx.send(source, "<gray>· players (summed over backends): <white>" + backendPlayers
        + "</white></gray>");
    ctx.send(source, "<gray>· this build: <white>"
        + escape(network.buildIdentity().publishedVersion()) + "</white> <gray>protocol=</gray>"
        + network.protocolTag().version() + " <gray>content=</gray>"
        + escape(network.contentManifest().digest()) + "</gray>");
    ctx.send(source, "<gray>· drain phase: " + colourPhase(network.drainControl().state().phase())
        + "</gray>");
    for (var node : nodes) {
      ctx.send(source, "<gray>· <white>" + escape(node.nodeId()) + "</white> "
          + colourState(node.state())
          + " <gray>build=</gray>" + escape(node.pluginVersion() == null ? "—" : node.pluginVersion())
          + " <gray>content=</gray>" + escape(node.contentDigest() == null ? "—" : node.contentDigest())
          + " <gray>tps=</gray>" + scaled(node.tps())
          + " <gray>heap=</gray>" + node.heapUsedMb() + "/" + node.heapMaxMb() + "MiB</gray>");
    }
  }

  /**
   * How this build relates to every peer.
   *
   * <p>An {@code INCOMPATIBLE} verdict is the roll-abort condition: it means one node will refuse to
   * adopt what another releases, so a drain would stall with nowhere to put its worlds. It does NOT
   * make the peer unusable — it stays routable and keeps its players, because nothing player-facing
   * is ever gated on the tag.</p>
   */
  private void protocol(CommandSource source, NetworkInspector inspector) {
    com.sexidium.core.network.ProtocolTag tag = ctx.core.network().protocolTag();
    ctx.send(source, "<gold>Protocol</gold> <gray>mine=</gray><white>" + tag.version()
        + "</white> <gray>minPeer=</gray><white>" + tag.minPeer() + "</white>");
    for (var node : inspector.nodes()) {
      if (node.nodeId() == null || node.nodeId().equals(ctx.core.network().identity().nodeId())) {
        continue;
      }
      String verdict = tag.verdict(node.protocol());
      ctx.send(source, "<gray>· <white>" + escape(node.nodeId()) + "</white> <gray>protocol=</gray>"
          + node.protocol() + " <gray>verdict=</gray>"
          + ("INCOMPATIBLE".equals(verdict) ? "<red>" : "<green>") + verdict
          + ("INCOMPATIBLE".equals(verdict) ? "</red>" : "</green>"));
    }
  }

  /** What content a node can run, and what this node has that it does not (or the other way round). */
  private void capability(CommandSource source, NetworkInspector inspector, String nodeId) {
    com.sexidium.core.network.ContentManifest mine = ctx.core.network().contentManifest();
    if (nodeId == null || nodeId.isBlank()) {
      ctx.send(source, "<gold>Content</gold> <gray>digest=</gray><white>" + escape(mine.digest())
          + "</white> <gray>codes=</gray><white>" + mine.codes().size() + "</white>");
      for (var node : inspector.nodes()) {
        String digest = node.contentDigest();
        boolean same = mine.digest().equals(digest);
        ctx.send(source, "<gray>· <white>" + escape(node.nodeId()) + "</white> <gray>digest=</gray>"
            + (same ? "<green>" : "<yellow>") + escape(digest == null ? "—" : digest)
            + (same ? "</green>" : "</yellow>"));
      }
      return;
    }
    for (var node : inspector.nodes()) {
      if (!nodeId.equalsIgnoreCase(node.nodeId())) {
        continue;
      }
      java.util.Set<String> theirs = node.codes();
      java.util.List<String> missingThere = new java.util.ArrayList<>(mine.codes());
      missingThere.removeAll(theirs);
      java.util.List<String> missingHere = new java.util.ArrayList<>(theirs);
      missingHere.removeAll(mine.codes());
      ctx.send(source, "<gold>" + escape(node.nodeId()) + "</gold> <gray>codes=</gray><white>"
          + theirs.size() + "</white>");
      ctx.send(source, "<gray>· it lacks: <white>"
          + escape(missingThere.isEmpty() ? "nothing" : String.join(", ", missingThere))
          + "</white></gray>");
      ctx.send(source, "<gray>· we lack: <white>"
          + escape(missingHere.isEmpty() ? "nothing" : String.join(", ", missingHere))
          + "</white></gray>");
      return;
    }
    ctx.send(source, "<gray>No node <white>" + escape(nodeId) + "</white> is registered.</gray>");
  }

  /** {@code /sx admin net drain [<reason>] [--force]}. Delegates to the drain coordinator. */
  private void drain(CommandSource source, String[] args) {
    String reason = "console";
    for (int index = 2; index < args.length; index++) {
      if (!args[index].startsWith("--")) {
        reason = args[index];
        break;
      }
    }
    var result = ctx.core.network().drainControl()
        .drain(reason, hasFlag(args, "--force"), sourceName(source));
    if (result.accepted()) {
      ctx.send(source, "<yellow>Draining: <white>" + result.state().phase()
          + "</white>. Watch <white>SX-DRAIN</white> in the console; the node is safe to restart at"
          + " <white>phase=READY</white>.</yellow>");
      return;
    }
    ctx.send(source, "<red>Refused (" + escape(result.refusal()) + "): "
        + escape(result.detail() == null ? "no detail" : result.detail()) + "</red>");
  }

  private void undrain(CommandSource source) {
    var result = ctx.core.network().drainControl().undrain();
    if (result.accepted()) {
      ctx.send(source, "<green>This node is no longer draining.</green>");
      return;
    }
    ctx.send(source, "<red>Refused (" + escape(result.refusal()) + "): "
        + escape(result.detail() == null ? "no detail" : result.detail()) + "</red>");
  }

  /**
   * Opt-in convergence after a roll.
   *
   * <p>Nothing calls this automatically and nothing ever will: automatic rebalancing of live worlds
   * is exactly the churn {@code PlacementPlanner} documents against a real observed player
   * ping-pong. A world a peer adopted and is actively serving stays there.</p>
   */
  private void rebalance(CommandSource source) {
    ctx.send(source, "<gray>Rebalancing is deliberately manual and moves only <white>idle,"
        + " unleased</white> placements. It is not implemented yet; a returning node reattracts its"
        + " share by rejoining the planner as the least-loaded candidate, which is the policy that"
        + " avoids the world-bouncing this command could cause.</gray>");
  }

  private static boolean hasFlag(String[] args, String flag) {
    for (String arg : args) {
      if (flag.equalsIgnoreCase(arg)) {
        return true;
      }
    }
    return false;
  }

  private static String sourceName(CommandSource source) {
    return source instanceof com.sexidium.core.platform.PlayerAdapter player
        ? player.name() : "console";
  }

  /** The ×100 telemetry columns, rendered for a human. {@code -1} means the node cannot answer. */
  private static String scaled(int timesHundred) {
    return timesHundred < 0 ? "—" : (timesHundred / 100) + "." + String.format("%02d", timesHundred % 100);
  }

  private static String colourPhase(com.sexidium.core.network.DrainPhase phase) {
    return switch (phase) {
      case NONE -> "<green>NONE</green>";
      case READY, QUIESCENT -> "<yellow>" + phase + "</yellow>";
      case STALLED -> "<red>STALLED</red>";
      default -> "<gold>" + phase + "</gold>";
    };
  }

  private void nodes(CommandSource source, NetworkInspector inspector) {
    List<NodeRegistry.Node> nodes = inspector.nodes();
    if (nodes.isEmpty()) {
      ctx.send(source, "<gray>No nodes are registered.</gray>");
      return;
    }
    long now = System.currentTimeMillis();
    ctx.send(source, "<gold>Nodes (" + nodes.size() + ")</gold>");
    for (NodeRegistry.Node node : nodes) {
      long age = (now - node.heartbeatAt()) / 1000L;
      ctx.send(source, "<gray>· <white>" + escape(node.nodeId()) + "</white> "
          + colourState(node.state()) + " <gray>role=</gray>" + escape(node.role())
          + " <gray>players=</gray>" + node.players()
          // The two columns an operator checks first, and both used to be wrong: worlds was
          // hard-coded 0 on every heartbeat, and there was no address at all.
          + " <gray>worlds=</gray>" + node.worlds()
          + " <gray>at=</gray>" + escape(node.addressable() ? node.address() + ":" + node.port() : "—")
          + " <gray>epoch=</gray>" + node.epoch()
          + " <gray>beat=</gray>" + age + "s");
    }
  }

  private void placements(CommandSource source, NetworkInspector inspector, String filter) {
    List<DbWorldLeaseAuthority.Placement> placements = inspector.placements(filter);
    if (placements.isEmpty()) {
      ctx.send(source, "<gray>No placements" + (filter == null ? "" : " match '" + escape(filter) + "'")
          + ".</gray>");
      return;
    }
    long now = System.currentTimeMillis();
    ctx.send(source, "<gold>Placements (" + placements.size() + ")</gold>");
    for (DbWorldLeaseAuthority.Placement placement : placements) {
      boolean held = placement.leaseHeld(now);
      ctx.send(source, "<gray>· <white>" + escape(placement.worldKey()) + "</white> "
          + colourState(placement.state())
          + " <gray>on=</gray>" + escape(placement.nodeId())
          + " <gray>epoch=</gray>" + placement.nodeEpoch()
          // The fence is the whole of I1, so an operator has to be able to see it: two rows for one
          // run with the same fence, or a LOADED row with fence=0, are both real problems.
          + " <gray>fence=</gray>" + placement.fence()
          + " <gray>players=</gray>" + placement.players()
          + " <gray>lease=</gray>"
          + (held ? "<green>" + (placement.leaseExpiresAt() - now) / 1000L + "s</green>"
                  : "<red>lapsed</red>"));
    }
  }

  private void transfers(CommandSource source, NetworkInspector inspector) {
    List<TransferTicket> tickets = inspector.inFlight();
    if (tickets.isEmpty()) {
      ctx.send(source, "<gray>No transfers are in flight.</gray>");
      return;
    }
    ctx.send(source, "<gold>Transfers in flight (" + tickets.size() + ")</gold>");
    long now = System.currentTimeMillis();
    for (TransferTicket ticket : tickets) {
      ctx.send(source, "<gray>· <white>" + ticket.playerId() + "</white> "
          + escape(String.valueOf(ticket.sourceNode())) + " <gray>-></gray> "
          + "<white>" + escape(ticket.targetNode()) + "</white>"
          + " <gray>epoch=</gray>" + ticket.targetEpoch()
          + " <gray>why=</gray>" + ticket.reason()
          + " <gray>world=</gray>" + escape(String.valueOf(ticket.worldKey()))
          + " <gray>state=</gray>" + ticket.state()
          // attempts is what tells a bounce apart from a slow connect at a glance.
          + " <gray>attempts=</gray>" + ticket.attempts()
          + " <gray>ttl=</gray>" + Math.max(0L, (ticket.expiresAt() - now) / 1000L) + "s");
    }
  }

  private void stats(CommandSource source, NetworkInspector inspector) {
    NetworkInspector.TransferStats stats = inspector.stats();
    ctx.send(source, "<gold>Network</gold>");
    ctx.send(source, "<gray>· live nodes: <white>" + stats.liveNodes() + "</white></gray>");
    ctx.send(source, "<gray>· loaded worlds (live lease): <white>" + stats.loadedWorlds()
        + "</white></gray>");
    ctx.send(source, "<gray>· transfers in flight: <white>" + stats.transfersInFlight()
        + "</white></gray>");
    // The two counters a live verification is actually looking for, and which nothing counted.
    ctx.send(source, "<gray>· loop-breaker trips: "
        + (stats.loopBreakerTrips() == 0 ? "<white>0</white>"
            : "<red>" + stats.loopBreakerTrips() + "</red>") + "</gray>");
    ctx.send(source, "<gray>· evictions (claims lost): "
        + (stats.evictions() == 0 ? "<white>0</white>"
            : "<red>" + stats.evictions() + "</red>") + "</gray>");
  }

  private void locate(CommandSource source, NetworkInspector inspector, String experienceId) {
    if (experienceId == null || experienceId.isBlank()) {
      ctx.send(source, "<gray>Usage: <white>/sx admin net locate <experienceId></white></gray>");
      return;
    }
    Optional<WorldKey> key = inspector.keyOf(experienceId);
    if (key.isEmpty()) {
      ctx.send(source, "<gray>Nothing is recorded for experience <white>" + escape(experienceId)
          + "</white>.</gray>");
      return;
    }
    Optional<ExperienceLocator.Placement> placement = inspector.locate(key.get());
    if (placement.isEmpty()) {
      ctx.send(source, "<gray>Experience <white>" + escape(experienceId) + "</white> is world <white>"
          + escape(key.get().key()) + "</white>, which nothing has claimed.</gray>");
      return;
    }
    ExperienceLocator.Placement found = placement.get();
    boolean held = found.leaseHeld(System.currentTimeMillis());
    ctx.send(source, "<gold>" + escape(experienceId) + "</gold> <gray>-> world</gray> <white>"
        + escape(key.get().key()) + "</white>");
    ctx.send(source, "<gray>· node: <white>" + escape(found.nodeId()) + "</white> (epoch "
        + found.nodeEpoch() + ", fence " + found.fence() + ")</gray>");
    ctx.send(source, "<gray>· state: " + colourState(found.state()) + " <gray>players=</gray>"
        + found.players() + " <gray>lease=</gray>"
        + (held ? "<green>live</green>" : "<red>lapsed</red>") + "</gray>");
  }

  private void evict(CommandSource source, NetworkInspector inspector, String worldKey) {
    if (worldKey == null || worldKey.isBlank()) {
      ctx.send(source, "<gray>Usage: <white>/sx admin net evict <worldKey></white></gray>");
      return;
    }
    Optional<WorldKey> key = WorldKey.tryParse(worldKey);
    if (key.isEmpty()) {
      ctx.send(source, "<red>'" + escape(worldKey) + "' is not a world key.</red>");
      return;
    }
    if (!inspector.evict(key.get())) {
      ctx.send(source, "<gray>No placement row for <white>" + escape(key.get().key())
          + "</white>.</gray>");
      return;
    }
    // Deliberately explicit about what this does NOT do: the holder unloads the world itself, on its
    // next refused renewal, so nothing is yanked out from under a player mid-write.
    ctx.send(source, "<yellow>Force-expired the lease on <white>" + escape(key.get().key())
        + "</white>. Its holder will be refused on its next renewal and will evacuate and unload it"
        + " itself; nothing was deleted.</yellow>");
  }

  private static String colourState(String state) {
    if (state == null) {
      return "<gray>?</gray>";
    }
    return switch (state) {
      case DbWorldLeaseAuthority.STATE_LOADED, NodeRegistry.STATE_UP -> "<green>" + state + "</green>";
      case NodeRegistry.STATE_DOWN -> "<red>" + state + "</red>";
      case NodeRegistry.STATE_DRAINING -> "<yellow>" + state + "</yellow>";
      default -> "<gray>" + state + "</gray>";
    };
  }

  /** World keys and node ids come from the database, and MiniMessage would happily render a tag. */
  private static String escape(String value) {
    return value == null ? "—" : value.replace("<", "\\<");
  }

  List<String> suggest(CommandSource source, String[] args) {
    if (args.length == 2) {
      return ctx.filter(
          List.of("nodes", "placements", "transfers", "stats", "locate", "evict", "report",
              "protocol", "capability", "drain", "undrain", "rebalance"), args[1]);
    }
    if (args.length == 3 && "capability".equals(lower(args[1]))) {
      NetworkInspector inspector = ctx.core.networkInspector();
      return inspector == null ? List.of()
          : ctx.filter(inspector.nodes().stream().map(NodeRegistry.Node::nodeId).toList(), args[2]);
    }
    return List.of();
  }
}
