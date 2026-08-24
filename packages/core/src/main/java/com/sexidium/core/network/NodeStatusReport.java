package com.sexidium.core.network;

import com.sexidium.core.lib.net.Json;
import com.sexidium.core.platform.NodeHealthPort;

import java.util.List;

/**
 * What a node says about itself, in the one shape every reader gets.
 *
 * <p>{@code GET /node}, {@code GET /network} and {@code /sx admin net report --json} all render from
 * here, so an operator reading a console table and a pipeline parsing JSON can never be looking at
 * two different answers. The field names are a frozen contract.</p>
 *
 * <h2>Player counts — the trap this class exists to close</h2>
 * <p>The proxy's {@code players} is the whole network's count; every backend row counts its own
 * players again. {@code SUM(players)} over {@code network_nodes} is therefore about <b>twice</b>
 * reality. Neither writer's meaning is changed — changing a column's meaning across builds is exactly
 * what would force a protocol bump and break mixed operation — so the correction is on the reader
 * side, here, once: the network total is the proxy row, the backend total is the sum of the rest.</p>
 */
public final class NodeStatusReport {

  private NodeStatusReport() {
  }

  /**
   * The boot/verify gate: one authoritative boolean instead of a composite of log lines.
   *
   * <p>{@code Done (} alone is not enough — Paper prints it even when a plugin threw during enable
   * and was disabled, which has been seen live.</p>
   */
  public static boolean ready(NetworkService network, boolean started, boolean heartbeatWritten) {
    if (!started) {
      return false;
    }
    if (!network.networked()) {
      return true; // Standalone is ready the moment it started; there is no registry to appear in.
    }
    return heartbeatWritten;
  }

  /** {@code GET /node} for this process. */
  public static String localJson(NetworkService network, boolean ready) {
    NodeIdentity identity = network.identity();
    NodeRegistry registry = network.registry();
    ContentManifest manifest = network.contentManifest();
    BuildIdentity build = network.buildIdentity();
    ProtocolTag tag = network.protocolTag();
    NodeRegistry.Node self = selfRow(registry, identity.nodeId());

    StringBuilder json = new StringBuilder(512);
    json.append('{');
    string(json, "node", identity.nodeId()).append(',');
    string(json, "role", self == null ? roleOf(identity) : self.role()).append(',');
    number(json, "epoch", registry == null ? 0L : registry.epoch()).append(',');
    string(json, "state", self == null ? NodeRegistry.STATE_UP : self.state()).append(',');
    number(json, "protocol", tag.version()).append(',');
    number(json, "minPeerProtocol", tag.minPeer()).append(',');
    string(json, "pluginVersion", build.publishedVersion()).append(',');
    nullableString(json, "buildSha", self == null ? null : self.buildSha()).append(',');
    json.append("\"capabilities\":");
    strings(json, identity.capabilities().stream()
        .map(capability -> capability.name().toLowerCase(java.util.Locale.ROOT))
        .toList()).append(',');
    string(json, "contentDigest", manifest.digest()).append(',');
    json.append("\"contentCodes\":");
    strings(json, manifest.codes()).append(',');
    number(json, "players", self == null ? 0 : self.players()).append(',');
    number(json, "maxPlayers", self == null ? 0 : self.maxPlayers()).append(',');
    number(json, "worlds", self == null ? 0 : self.worlds()).append(',');
    number(json, "maxWorlds", self == null ? 0 : self.maxWorlds()).append(',');
    scaled(json, "tps", self == null ? NodeHealthPort.UNKNOWN : self.tps()).append(',');
    scaled(json, "mspt", self == null ? NodeHealthPort.UNKNOWN : self.mspt()).append(',');
    number(json, "heapUsedMb", self == null ? 0 : self.heapUsedMb()).append(',');
    number(json, "heapMaxMb", self == null ? 0 : self.heapMaxMb()).append(',');
    json.append("\"drain\":").append(drainJson(network.drainControl().state())).append(',');
    json.append("\"ready\":").append(ready).append(',');
    json.append("\"peers\":").append(peersJson(network));
    json.append('}');
    return json.toString();
  }

  /** {@code GET /network}: every registered node plus the two player totals, computed correctly. */
  public static String networkJson(NetworkService network) {
    NodeRegistry registry = network.registry();
    List<NodeRegistry.Node> nodes = registry == null ? List.of() : registry.all();
    int networkTotal = 0;
    int backendTotal = 0;
    for (NodeRegistry.Node node : nodes) {
      if ("proxy".equals(node.role())) {
        networkTotal += node.players();
      } else {
        backendTotal += node.players();
      }
    }
    StringBuilder json = new StringBuilder(1024);
    json.append("{\"nodes\":[");
    for (int index = 0; index < nodes.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append(peerJson(nodes.get(index), network.protocolTag()));
    }
    json.append("],");
    // No proxy row (a standalone-ish network, or the proxy is down): the backend sum is the closest
    // honest answer, and it is an UNDER-count rather than the ~2x over-count of summing everything.
    number(json, "totalPlayers", networkTotal > 0 ? networkTotal : backendTotal).append(',');
    number(json, "backendPlayers", backendTotal).append(',');
    number(json, "nodeCount", nodes.size());
    json.append('}');
    return json.toString();
  }

  /** {@code GET /node/drain}. */
  public static String drainJson(DrainState state) {
    DrainState drain = state == null ? DrainState.idle() : state;
    StringBuilder json = new StringBuilder(256);
    json.append('{');
    string(json, "phase", drain.phase().name()).append(',');
    nullableString(json, "reason", drain.reason()).append(',');
    nullableString(json, "requestedBy", drain.requestedBy()).append(',');
    number(json, "startedAt", drain.startedAt()).append(',');
    number(json, "deadline", drain.deadline()).append(',');
    number(json, "worldsTotal", drain.worldsTotal()).append(',');
    number(json, "worldsLeft", drain.worldsLeft()).append(',');
    number(json, "playersLeft", drain.playersLeft()).append(',');
    number(json, "matchesLeft", drain.matchesLeft()).append(',');
    nullableString(json, "detail", drain.detail()).append(',');
    json.append("\"stalled\":").append(drain.stalled());
    json.append('}');
    return json.toString();
  }

  /** The selftest result, for {@code GET /node/selftest}. */
  public static String selfTestJson(NodeSelfTest.Result result) {
    StringBuilder json = new StringBuilder(256);
    json.append('{');
    json.append("\"ok\":").append(result.ok()).append(',');
    number(json, "challengesOk", result.challengesOk()).append(',');
    number(json, "challengesTotal", result.challengesTotal()).append(',');
    number(json, "modesOk", result.modesOk()).append(',');
    number(json, "modesTotal", result.modesTotal()).append(',');
    string(json, "pool", result.pool()).append(',');
    nullableString(json, "poolDetail", result.poolDetail()).append(',');
    json.append("\"databaseOk\":").append(result.databaseOk()).append(',');
    nullableString(json, "detail", result.detail());
    json.append('}');
    return json.toString();
  }

  private static String peersJson(NetworkService network) {
    NodeRegistry registry = network.registry();
    if (registry == null) {
      return "[]";
    }
    StringBuilder json = new StringBuilder(256);
    json.append('[');
    boolean first = true;
    for (NodeRegistry.Node node : registry.all()) {
      if (node.nodeId() == null || node.nodeId().equals(network.identity().nodeId())) {
        continue;
      }
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append('{');
      string(json, "node", node.nodeId()).append(',');
      number(json, "protocol", node.protocol()).append(',');
      string(json, "verdict", network.protocolTag().verdict(node.protocol()));
      json.append('}');
    }
    json.append(']');
    return json.toString();
  }

  private static String peerJson(NodeRegistry.Node node, ProtocolTag tag) {
    StringBuilder json = new StringBuilder(384);
    json.append('{');
    string(json, "node", node.nodeId()).append(',');
    string(json, "role", node.role()).append(',');
    string(json, "state", node.state()).append(',');
    number(json, "epoch", node.epoch()).append(',');
    number(json, "heartbeatAt", node.heartbeatAt()).append(',');
    number(json, "protocol", node.protocol()).append(',');
    string(json, "verdict", tag.verdict(node.protocol())).append(',');
    nullableString(json, "pluginVersion", node.pluginVersion()).append(',');
    nullableString(json, "buildSha", node.buildSha()).append(',');
    nullableString(json, "contentDigest", node.contentDigest()).append(',');
    string(json, "capabilities", node.capabilities() == null ? "" : node.capabilities()).append(',');
    number(json, "players", node.players()).append(',');
    number(json, "maxPlayers", node.maxPlayers()).append(',');
    number(json, "worlds", node.worlds()).append(',');
    number(json, "maxWorlds", node.maxWorlds()).append(',');
    scaled(json, "tps", node.tps()).append(',');
    scaled(json, "mspt", node.mspt()).append(',');
    number(json, "heapUsedMb", node.heapUsedMb()).append(',');
    number(json, "heapMaxMb", node.heapMaxMb());
    json.append('}');
    return json.toString();
  }

  private static NodeRegistry.Node selfRow(NodeRegistry registry, String nodeId) {
    if (registry == null) {
      return null;
    }
    for (NodeRegistry.Node node : registry.all()) {
      if (nodeId.equals(node.nodeId())) {
        return node;
      }
    }
    return null;
  }

  private static String roleOf(NodeIdentity identity) {
    if (identity.isStandalone()) {
      return "standalone";
    }
    if (identity.can(NodeCapability.ROUTER)) {
      return "proxy";
    }
    return identity.can(NodeCapability.LOBBY) ? "lobby" : "worker";
  }

  private static StringBuilder string(StringBuilder json, String key, String value) {
    return json.append('"').append(key).append("\":\"")
        .append(Json.escape(value == null ? "" : value)).append('"');
  }

  private static StringBuilder nullableString(StringBuilder json, String key, String value) {
    json.append('"').append(key).append("\":");
    if (value == null || value.isBlank()) {
      return json.append("null");
    }
    return json.append('"').append(Json.escape(value)).append('"');
  }

  private static StringBuilder number(StringBuilder json, String key, long value) {
    return json.append('"').append(key).append("\":").append(value);
  }

  /** The ×100 columns, rendered back as the decimal a human and a pipeline both expect. */
  private static StringBuilder scaled(StringBuilder json, String key, int timesHundred) {
    json.append('"').append(key).append("\":");
    if (timesHundred < 0) {
      return json.append("null"); // Unknown. NOT 0 — 0 TPS is a reading, and a very bad one.
    }
    return json.append(timesHundred / 100).append('.')
        .append(String.format("%02d", timesHundred % 100));
  }

  private static StringBuilder strings(StringBuilder json, List<String> values) {
    json.append('[');
    for (int index = 0; index < values.size(); index++) {
      if (index > 0) {
        json.append(',');
      }
      json.append('"').append(Json.escape(values.get(index))).append('"');
    }
    return json.append(']');
  }
}
