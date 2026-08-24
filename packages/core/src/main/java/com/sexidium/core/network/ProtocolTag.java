package com.sexidium.core.network;

/**
 * A build's protocol tag as a value, so two of them can exist in one JVM.
 *
 * <p>{@link Protocol#VERSION} and {@link Protocol#MIN_PEER} are never read at a call site — every
 * consumer takes one of these through a constructor overload that defaults to {@link #current()}.
 * That is the whole trick behind testing mixed-version operation: the registry tests already run
 * several "nodes" against one in-memory database, so a skew test is two tags and no classloaders, no
 * second JVM and no fixtures.</p>
 */
public record ProtocolTag(long version, long minPeer) {

  /** The tag this build was compiled with. */
  public static ProtocolTag current() {
    return new ProtocolTag(Protocol.VERSION, Protocol.MIN_PEER);
  }

  /**
   * Whether this node may take ownership of a process from a peer at {@code peerProtocol}.
   *
   * <p>The single compatibility rule, stated once: {@code peer.protocol >= MY.MIN_PEER}. A peer with
   * a HIGHER tag is fully usable — it knows its own floor and will refuse us if we are too old.</p>
   */
  public boolean acceptsOwnershipFrom(long peerProtocol) {
    return peerProtocol >= minPeer;
  }

  /**
   * How a peer relates to this build, for the operator surface and the {@code SX-PROTOCOL} log line.
   *
   * <p>{@code INCOMPATIBLE} does not make a peer unusable: it stays routable, stays registered on the
   * proxy, keeps its worlds and keeps its players. It is only excluded as an ownership <em>target</em>.
   * An orchestrator should nevertheless treat any INCOMPATIBLE verdict as a roll-abort condition.</p>
   */
  public String verdict(long peerProtocol) {
    if (!acceptsOwnershipFrom(peerProtocol)) {
      return "INCOMPATIBLE";
    }
    if (peerProtocol > version) {
      return "NEWER";
    }
    return peerProtocol == version ? "SAME" : "OLDER-OK";
  }
}
