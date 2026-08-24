package com.sexidium.velocity;

import com.sexidium.core.network.NodeRegistry;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * The proxy's backend list, derived from {@code network_nodes} rather than from a file.
 *
 * <p>{@code velocity.toml}'s {@code [servers]} block was rendered at PROVISION time from a static
 * {@code SX_NODES} list, so adding a worker meant editing the stack, re-running the provisioner and
 * restarting the proxy. Meanwhile the capability side was already dynamic — the new worker's row
 * appeared on its first heartbeat and {@code registry.with(EXPERIENCES)} picked it up — so the
 * planner would cheerfully home a world onto a node the proxy could not reach, and every transfer to
 * it answered "unknown node… is it in velocity.toml?".</p>
 *
 * <p>A node becomes reachable by STARTING UP. Nothing else changes anywhere.</p>
 */
public final class BackendDirectory {

  private final ProxyServer proxy;
  private final Logger logger;
  private final NodeRegistry registry;
  /** Servers this directory registered, so it never unregisters one from velocity.toml. */
  private final Set<String> ours = new LinkedHashSet<>();

  public BackendDirectory(ProxyServer proxy, Logger logger, NodeRegistry registry) {
    this.proxy = proxy;
    this.logger = logger;
    this.registry = registry;
  }

  public Optional<RegisteredServer> byNodeId(String nodeId) {
    return nodeId == null ? Optional.empty() : proxy.getServer(nodeId);
  }

  /** Diff the live registry against what the proxy knows, and register the difference. */
  public void refresh() {
    Set<String> live = new HashSet<>();
    for (NodeRegistry.Node node : registry.alive()) {
      if (!node.addressable()) {
        // A node that has not published an address yet is not an error: it is a backend still in its
        // first seconds. It is simply not connectable.
        continue;
      }
      if (node.capabilities() != null
          && java.util.Arrays.asList(node.capabilities().split(",")).contains("router")) {
        // A ROUTER hosts no worlds and accepts no backend connections. It publishes an address so it
        // is visible in the registry like everything else -- and registering that address as a
        // BACKEND would let lobbyServer()'s least-loaded fallback pick the proxy itself and send
        // players into a loop through their own front door.
        continue;
      }
      live.add(node.nodeId());
      if (proxy.getServer(node.nodeId()).isPresent()) {
        continue;
      }
      try {
        proxy.registerServer(new ServerInfo(
            node.nodeId(), new InetSocketAddress(node.address(), node.port())));
        ours.add(node.nodeId());
        logger.info("Discovered backend '{}' at {}:{} from the node registry",
            node.nodeId(), node.address(), node.port());
      } catch (RuntimeException refused) {
        // A name clash with velocity.toml is the normal cause, and it is benign: the static entry
        // wins and points at the same place.
        logger.warn("Could not register backend '{}': {}", node.nodeId(), refused.getMessage());
      }
    }

    // Unregister only what WE registered. A server written into velocity.toml by hand is the
    // operator's, and dropping it because a heartbeat lapsed would turn a brief DB hiccup into a
    // proxy that cannot reach its own lobby.
    for (String nodeId : Set.copyOf(ours)) {
      if (live.contains(nodeId)) {
        continue;
      }
      proxy.getServer(nodeId).ifPresent(server -> {
        // Only when nobody is on it: yanking the registration out from under connected players
        // would disconnect them from the network rather than move them.
        if (!server.getPlayersConnected().isEmpty()) {
          return;
        }
        proxy.unregisterServer(server.getServerInfo());
        ours.remove(nodeId);
        logger.info("Backend '{}' left the network; unregistered it", nodeId);
      });
    }
  }
}
