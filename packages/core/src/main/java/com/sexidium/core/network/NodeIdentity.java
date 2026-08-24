package com.sexidium.core.network;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Who this process is within the network.
 *
 * <p>Never assigned by the proxy. A node that learned its role from the proxy could not boot while the
 * proxy was down, which would break the standalone requirement outright — the proxy validates a claimed
 * id and refuses a duplicate, but it never hands one out.</p>
 *
 * <p>The standalone identity holds <em>every</em> capability, which is what makes one code path serve
 * both topologies: subsystems are gated on capability, and a standalone server passes every gate, so it
 * starts exactly the subsystems it starts today.</p>
 */
public final class NodeIdentity {

  /** Node id used when no network is configured. */
  public static final String LOCAL_NODE = "standalone";

  private final String nodeId;
  private final String displayName;
  private final Set<NodeCapability> capabilities;
  private final boolean standalone;

  private NodeIdentity(String nodeId, String displayName, Set<NodeCapability> capabilities, boolean standalone) {
    this.nodeId = nodeId;
    this.displayName = displayName;
    this.capabilities = Collections.unmodifiableSet(capabilities);
    this.standalone = standalone;
  }

  /** The default identity: one server, every capability, no network bookkeeping. */
  public static NodeIdentity standalone() {
    return new NodeIdentity(LOCAL_NODE, "Standalone", EnumSet.allOf(NodeCapability.class), true);
  }

  public static NodeIdentity of(String nodeId, String displayName, Set<NodeCapability> capabilities) {
    return new NodeIdentity(nodeId, displayName, EnumSet.copyOf(capabilities), false);
  }

  /**
   * Expands a configured {@code network.node.role} into its capability set.
   *
   * <p>Operators configure one word; the code branches on capabilities. Keeping the expansion here — and
   * not in the provisioning scripts — means a topology change is a code review rather than four config
   * files that have drifted apart.</p>
   *
   * <p>An unrecognised role is <em>not</em> silently treated as standalone: that would hand every
   * capability to a node the operator meant to restrict, so a typo would start four Discord bots.</p>
   */
  public static Set<NodeCapability> capabilitiesForRole(String role) {
    switch (role == null ? "" : role.trim().toLowerCase(Locale.ROOT)) {
      case "standalone":
      case "":
        return EnumSet.allOf(NodeCapability.class);
      case "lobby":
        // MAP_AUTHORITY sits on the lobby for two reasons that happen to agree. Practically, it is
        // where admins already are -- the in-game editor needs a human standing in the template
        // world, and a lobby node is the one a human reaches without being routed. Structurally, it
        // is the only backend role there is exactly one of, so "one writer" is true by construction
        // rather than by convention (the proxy hosts no worlds at all, and workers come in fours).
        //
        // BOT_HOST lives here and not on the proxy, which is where it used to sit. The proxy runs
        // module-velocity, which never builds SexidiumCore and therefore never reaches the
        // botManager.start()/bridgeClient.start() lines the capability gates -- so the bot was
        // never started by anybody, while docker/stack.sexidium.yml (SX_BOT_NODE: lobby) and
        // scripts/lib/sexidium.sh (bot.enabled: true on the lobby) both pointed here. The lobby is
        // a Paper node with a real core, so the capability and the scripts finally agree.
        return EnumSet.of(
            NodeCapability.LOBBY,
            NodeCapability.QUEUE_AUTHORITY,
            NodeCapability.MINIGAMES,
            NodeCapability.MAP_AUTHORITY,
            NodeCapability.BOT_HOST,
            NodeCapability.API_HOST);
      case "worker":
        // Keeps MINIGAMES (it hosts matches, which only ever CLONE a template) and deliberately not
        // MAP_AUTHORITY: a worker editing a template would make its copy diverge from the other
        // three nodes, and the divergence surfaces as a match that starts on the wrong layout with
        // no error anywhere.
        return EnumSet.of(NodeCapability.EXPERIENCES, NodeCapability.MINIGAMES, NodeCapability.API_HOST);
      case "proxy":
        // Deliberately NO BOT_HOST -- the proxy runs module-velocity, which never builds
        // SexidiumCore and so has nothing to start a bot with. The capability sat here inert while
        // the provisioning scripts pointed the bot at the lobby; it now lives where it can be
        // honoured. The proxy still asks the registry whether SOME node holds it (see
        // NodeRegistry#anyAlive), which is what makes auth.require-for-login: auto meaningful here.
        return EnumSet.of(
            NodeCapability.ROUTER,
            NodeCapability.PACK_HOST,
            NodeCapability.API_HOST);
      default:
        throw new IllegalArgumentException(
            "Unknown network.node.role '" + role + "' (expected standalone, lobby, worker or proxy)");
    }
  }

  public String nodeId() {
    return nodeId;
  }

  public String displayName() {
    return displayName.isEmpty() ? nodeId : displayName;
  }

  public Set<NodeCapability> capabilities() {
    return capabilities;
  }

  /**
   * True when there is no network: one JVM, SQLite, no proxy.
   *
   * <p>Named {@code isStandalone} rather than {@code standalone} only because the static factory
   * {@link #standalone()} owns that name, and it reads better at the call site.</p>
   */
  public boolean isStandalone() {
    return standalone;
  }

  public boolean can(NodeCapability capability) {
    return capabilities.contains(capability);
  }

  @Override
  public String toString() {
    return "NodeIdentity[" + nodeId + " " + capabilities + (standalone ? " standalone" : "") + "]";
  }
}
