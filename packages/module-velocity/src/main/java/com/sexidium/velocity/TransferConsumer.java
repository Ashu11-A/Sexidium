package com.sexidium.velocity;

import com.sexidium.core.network.transfer.TransferExecutor;
import com.sexidium.core.network.transfer.TransferReason;
import com.sexidium.core.network.transfer.TransferState;
import com.sexidium.core.network.transfer.TransferTicket;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.util.Optional;

/**
 * Moves players the backends have asked to be moved. Claim, connect, then COMPLETE.
 *
 * <p>This is the half of a transfer only the proxy can do: a backend discovers that an experience is
 * claimed on another node and records the intent, and only the proxy can act on it. Polled rather than
 * pushed, deliberately — the alternative is Velocity plugin messaging, which rides a player's
 * connection and therefore cannot reach a backend with nobody on it, which is exactly the worker most
 * likely to be holding an idle experience.</p>
 *
 * <p>What replaced {@code RouteConsumer}: that one SELECTed pending routes and DELETEd them in the
 * same breath, and only then attempted the connect. Every failure after the delete lost the intent
 * silently — a failed connect logged one warning, a full or draining backend produced a red chat line
 * and nothing else, and a proxy restart between the delete and the connect dropped the transfer
 * entirely. In none of those cases was the requesting node told anything at all.</p>
 */
public final class TransferConsumer {

  /** How many tickets one poll takes. Bounded so a backlog cannot monopolise a proxy tick. */
  private static final int BATCH = 32;

  private final ProxyServer proxy;
  private final Logger logger;
  private final TransferExecutor executor;
  private final String executorId;
  private final long claimLeaseMillis;

  public TransferConsumer(ProxyServer proxy, Logger logger, TransferExecutor executor,
      String executorId, long claimLeaseMillis) {
    this.proxy = proxy;
    this.logger = logger;
    this.executor = executor;
    this.executorId = executorId;
    this.claimLeaseMillis = claimLeaseMillis;
  }

  /** Claim and act on a batch of tickets. Safe to call on a timer, and safe with several proxies. */
  public void tick() {
    for (TransferTicket ticket : executor.claim(executorId, BATCH, claimLeaseMillis)) {
      dispatch(ticket);
    }
  }

  private void dispatch(TransferTicket ticket) {
    Optional<Player> player = proxy.getPlayer(ticket.playerId());
    if (player.isEmpty()) {
      // They disconnected between the request and now. Terminal rather than retried: dragging them
      // somewhere on their next login is precisely what an expiring intent exists to prevent.
      executor.complete(ticket.token(), TransferState.FAILED, "the player is not connected");
      return;
    }
    Player connected = player.get();

    Optional<RegisteredServer> target = proxy.getServer(ticket.targetNode());
    if (target.isEmpty()) {
      logger.warn("Cannot transfer {} to unknown node '{}' (is it registered?)",
          connected.getUsername(), ticket.targetNode());
      connected.sendMessage(MiniMessage.miniMessage().deserialize(
          "<red>That world lives on a server this proxy does not know about.</red>"));
      executor.complete(ticket.token(), TransferState.FAILED,
          "no backend registered as '" + ticket.targetNode() + "'");
      return;
    }

    if (connected.getCurrentServer()
        .map(current -> current.getServerInfo().getName().equals(ticket.targetNode()))
        .orElse(false)) {
      // Already there. LANDED, not silently dropped: the requesting node is waiting for an answer,
      // and "they were already where you wanted them" is a success.
      executor.complete(ticket.token(), TransferState.LANDED, "already on the target node");
      return;
    }

    logger.info("Transferring {} to '{}' for {} ({}) [token {}, attempt {}]",
        connected.getUsername(), ticket.targetNode(), ticket.reason(), ticket.worldKey(),
        ticket.token(), ticket.attempts());
    // A return trip to the lobby is not "your world": on a worker every way out of a match is a
    // transfer, and telling those players they are being taken to a world is simply wrong.
    connected.sendMessage(MiniMessage.miniMessage().deserialize(
        ticket.reason() == TransferReason.LOBBY
            ? "<gray>Taking you back to the lobby…</gray>"
            : "<gray>Taking you to your world…</gray>"));

    connected.createConnectionRequest(target.get()).connect().whenComplete((result, error) -> {
      if (error != null) {
        logger.warn("Transfer of {} to '{}' failed: {}",
            connected.getUsername(), ticket.targetNode(), error.getMessage());
        // RETRY, bounded. The executor turns the last attempt into a FAILED the requester can read.
        executor.retry(ticket.token(), String.valueOf(error.getMessage()));
        return;
      }
      if (result != null && !result.isSuccessful()) {
        // The target refused (full, starting, or draining). Say so rather than leaving the player
        // wondering why nothing happened — and hand the ticket back, because "starting" is exactly
        // the condition a retry a second later resolves.
        logger.warn("Node '{}' refused {}", ticket.targetNode(), connected.getUsername());
        connected.sendMessage(MiniMessage.miniMessage().deserialize(
            "<red>That server is not accepting players right now. Trying again shortly.</red>"));
        executor.retry(ticket.token(), "the target refused the connection");
        return;
      }
      // The connect succeeded. The ticket stays live until the DESTINATION claims the arrival —
      // that is what proves the player is actually being served, not merely connected.
      logger.info("{} connected to '{}' [token {}]",
          connected.getUsername(), ticket.targetNode(), ticket.token());
    });
  }
}
