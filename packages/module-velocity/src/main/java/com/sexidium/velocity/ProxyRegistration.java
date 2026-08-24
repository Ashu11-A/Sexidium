package com.sexidium.velocity;

import com.sexidium.core.network.NodeRegistry;
import org.slf4j.Logger;

/**
 * The proxy joining the network it routes.
 *
 * <p>It never registered, never heartbeated and never joined the bus, so {@code planner.isAlive("proxy")}
 * was false and a backend could not tell "not yet routed" from "nobody will ever route this" — routes
 * simply aged out unread with no log anywhere. It also meant two containers sharing an {@code SX_NODE}
 * would both claim the same identity undetected, which {@code NodeIdentity}'s own javadoc claims the
 * proxy validates and nothing implemented.</p>
 *
 * <p>Registering is cheap and makes the proxy visible in exactly the place an operator already looks.</p>
 */
public final class ProxyRegistration {

  private final NodeRegistry registry;
  private final Logger logger;
  private final java.util.function.IntSupplier onlinePlayers;

  public ProxyRegistration(NodeRegistry registry, Logger logger,
      java.util.function.IntSupplier onlinePlayers) {
    this.registry = registry;
    this.logger = logger;
    this.onlinePlayers = onlinePlayers;
  }

  /** One heartbeat. Called on the same timer the backends use. */
  public void heartbeat() {
    try {
      // Zero worlds, and truthfully so: a proxy hosts none, which is different from the hard-coded
      // zero every BACKEND used to report.
      registry.heartbeat(onlinePlayers.getAsInt(), 0);
    } catch (RuntimeException failed) {
      logger.warn("Proxy heartbeat failed: {}", failed.getMessage());
    }
  }

  /** Tell peers we are going away, rather than making them wait for the timeout. */
  public void shutdown() {
    try {
      registry.draining();
    } catch (RuntimeException ignored) {
      // A shutdown must never fail on a courtesy write.
    }
  }
}
