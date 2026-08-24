package com.sexidium.velocity;

import com.google.inject.Inject;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.auth.AuthLoginService;
import com.sexidium.core.auth.AuthService;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.data.DatabaseConfig;
import com.sexidium.core.lib.data.DatabaseSettings;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.platform.NetworkPlayer;
import com.sexidium.velocity.adapter.VelocityNodeRuntime;
import com.sexidium.velocity.adapter.VelocityPlayer;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Sexidium's Velocity module.
 *
 * <p>Hosts no worlds, no blocks and no inventories, and never constructs {@code SexidiumCore}. It
 * builds a {@link VelocityNodeRuntime} from the narrow SPI and does the jobs only the proxy can do.</p>
 *
 * <p>Implemented: node identity, initial routing to a lobby, a kick redirect, and placement
 * routing — consuming the handoffs backends record when a world turns out to live on another node,
 * and performing the transfer.</p>
 *
 * <p>Not yet wired: the message bus. Deliberately absent rather than stubbed, so nothing here
 * claims to work when it does not.</p>
 */
@Plugin(
    id = "sexidium",
    name = "Sexidium",
    version = "1.0.0",
    description = "Sexidium network proxy module",
    authors = {"Sexidium"})
public final class SexidiumVelocityPlugin {

  private final ProxyServer proxy;
  private final Logger logger;
  private final Path dataDirectory;

  private VelocityNodeRuntime runtime;
  private Database database;
  private com.sexidium.core.network.transfer.DbTransferService transfers;
  private TransferConsumer transferConsumer;
  private BackendDirectory backends;
  private ProxyRegistration registration;
  private com.sexidium.core.network.DbWorldLeaseAuthority placements;
  private AuthLoginService authLoginService;
  /**
   * The node registry, kept as a field so lobby selection reads it.
   *
   * <p>It was built in {@code connectDatabase()} and handed only to {@link ProxyRegistration} and
   * {@link BackendDirectory}, so the routing decisions could not see load, capability or drain state
   * at all — which is why {@code lobbyServer()} had to guess by name.</p>
   */
  private com.sexidium.core.network.NodeRegistry registry;
  private long lobbyTimeoutMillis = com.sexidium.core.network.NetworkSettings.DEFAULT_NODE_TIMEOUT_SECONDS * 1000L;

  @Inject
  public SexidiumVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
    // Velocity documents that very little may safely happen in the constructor; real
    // work belongs in ProxyInitializeEvent.
    this.proxy = proxy;
    this.logger = logger;
    this.dataDirectory = dataDirectory;
  }

  @Subscribe
  public void onProxyInitialize(ProxyInitializeEvent event) {
    this.runtime = new VelocityNodeRuntime(proxy, logger, dataDirectory, this);

    logger.info("Sexidium proxy module starting as node '{}' with capabilities {}",
        runtime.identity().nodeId(), runtime.identity().capabilities());

    if (!runtime.identity().can(NodeCapability.ROUTER)) {
      // A proxy whose config says it is not a router will still boot, but it will not
      // route -- worth one loud line rather than silent inaction.
      logger.warn("This node lacks the ROUTER capability; set network.node.role: proxy in config.yml");
    }
    logger.info("Known backends: {}", proxy.getAllServers().stream()
        .map(server -> server.getServerInfo().getName())
        .toList());

    connectDatabase();
  }

  /**
   * Open the shared database and start consuming player routes.
   *
   * <p>The proxy needs direct database access because the login gate cannot tolerate an RPC hop to a
   * possibly-down lobby, and because routing decisions are recorded in the database by whichever
   * backend made them. Failure here is logged and survivable: the proxy still routes players to the
   * lobby, it just cannot perform placement handoffs.</p>
   */
  /**
   * This proxy's executor id.
   *
   * <p>Distinct per proxy, because claim-then-complete is only safe if two proxies can tell their own
   * claims apart. Derived from the node id rather than randomised so an operator reading
   * {@code player_transfers.claimed_by} sees a name they recognise.</p>
   */
  private String proxyId() {
    return runtime.identity().nodeId();
  }

  private void connectDatabase() {
    try {
      DatabaseConfig config =
          DatabaseSettings.resolve(runtime.configuration(), dataDirectory.toFile());
      if (config == null) {
        logger.warn("No database configured; placement routing is disabled on this proxy");
        return;
      }
      // Caught explicitly because an operator WILL hit this: config.yml ships database.type: sqlite,
      // and copying a backend's config onto the proxy is the obvious thing to try. The generic
      // "no driver" error would send them looking for a missing jar that is absent on purpose.
      if (config.dialect().isSqlite()) {
        logger.error("This proxy is configured for SQLite, which it cannot use. A network shares ONE "
            + "database across every node, so the proxy needs mysql or postgres. Set database.type "
            + "(and host/name/user/password) in {}/config.yml.", dataDirectory);
        return;
      }
      this.database = new Database(config, runtime.configuration().getLong(
          "network.db.validate-interval-millis", Database.DEFAULT_VALIDATE_INTERVAL_MILLIS));
      long ttl = runtime.configuration().getLong("network.transfer.ttl-seconds", 30L) * 1000L;
      this.transfers = new com.sexidium.core.network.transfer.DbTransferService(
          database, runtime.logger(), proxyId(), ttl,
          (int) runtime.configuration().getLong("network.transfer.max-attempts", 3L),
          runtime.configuration().getLong("network.transfer.breaker-window-seconds",
              com.sexidium.core.network.NetworkSettings.DEFAULT_BREAKER_WINDOW_SECONDS) * 1000L,
          // From the shared constant, not a second literal. This defaulted to 3 while the backends
          // defaulted to 6, so the same key meant two different things depending on which process
          // read it -- inert only while the proxy never calls request(), and a drain is exactly the
          // burst that would have found it.
          (int) runtime.configuration().getLong("network.transfer.breaker-max-per-node",
              com.sexidium.core.network.NetworkSettings.DEFAULT_BREAKER_MAX_PER_NODE));
      long pollMillis = Math.max(250L, runtime.configuration().getLong("network.route-poll-millis", 1000L));
      // The claim lease is a few polls wide: long enough that a slow connect is not re-dispatched
      // underneath itself, short enough that a proxy which dies mid-transfer frees the ticket fast.
      this.transferConsumer = new TransferConsumer(proxy, logger, transfers, proxyId(), pollMillis * 5L);

      proxy.getScheduler().buildTask(this, transferConsumer::tick)
          .delay(Duration.ofMillis(pollMillis))
          .repeat(Duration.ofMillis(pollMillis))
          .schedule();
      logger.info("Placement routing active (polling every {}ms)", pollMillis);

      // The proxy joins the network it routes (F12), and learns its backends FROM that network
      // rather than from a file rendered at provision time (F13/D5).
      com.sexidium.core.network.NetworkSettings.Timings timings =
          com.sexidium.core.network.NetworkSettings.timings(runtime.configuration());
      com.sexidium.core.network.NodeRegistry registry = new com.sexidium.core.network.NodeRegistry(
          database, runtime.logger(), runtime.identity(), timings.nodeTimeoutMillis());
      registry.publishAddress(
          runtime.configuration().getString("network.node.address", ""),
          (int) runtime.configuration().getLong("network.node.port", 0L));
      // The proxy publishes its build identity too, so a roll can verify it came back the same way
      // it verifies a backend. Its tick health stays UNKNOWN, truthfully: a proxy has no tick loop,
      // and reporting a plausible 20 TPS would be worse than admitting it cannot answer.
      registry.publishTelemetry(new com.sexidium.core.network.NodeRegistry.Telemetry(
          com.sexidium.core.network.BuildIdentity
              .load(runtime.resources(), runtime.configuration().getString("build.id", ""))
              .publishedVersion(),
          null, null, null,
          (int) runtime.configuration().getLong("network.node.max-players", 0L), 0));
      this.registry = registry;
      this.lobbyTimeoutMillis = timings.nodeTimeoutMillis();
      this.registration =
          new ProxyRegistration(registry, logger, () -> proxy.getPlayerCount());
      this.backends = new BackendDirectory(proxy, logger, registry);
      this.placements = new com.sexidium.core.network.DbWorldLeaseAuthority(
          database, runtime.logger(), runtime.identity(), registry.epoch(),
          timings.worldLeaseMillis());
      proxy.getScheduler().buildTask(this, () -> {
        registration.heartbeat();
        backends.refresh();
      })
          .delay(Duration.ofSeconds(1))
          .repeat(Duration.ofSeconds(Math.max(1L, timings.heartbeatSeconds())))
          .schedule();
      logger.info("Proxy registered in the node registry; discovering backends every {}s",
          timings.heartbeatSeconds());

      // The auth gate. Running it HERE rather than on each backend is the point: a rejected
      // player is refused at the proxy and never reaches a backend at all. That matters because
      // backends run online-mode=false under modern forwarding, so a backend-side gate is the
      // last line rather than the first.
      AuthService authService = new AuthService(
          database, () -> runtime.configuration().getBoolean("auth.enabled", true));
      MessageService messages = new MessageService(
          runtime.resources(), runtime.configuration(), runtime.logger());
      messages.reload();

      com.sexidium.core.auth.premium.CachingPremiumLookup premium =
          new com.sexidium.core.auth.premium.CachingPremiumLookup(
              new com.sexidium.core.auth.premium.HttpMojangApiClient(
                  runtime.configuration().getLong("auth.premium.lookup-timeout-millis", 2500L)),
              null,
              runtime.logger(),
              java.time.Clock.systemUTC(),
              new com.sexidium.core.auth.premium.CachingPremiumLookup.Settings(
                  runtime.configuration().getLong("auth.premium.positive-cache-seconds", 21600L) * 1000L,
                  runtime.configuration().getLong("auth.premium.negative-cache-seconds", 1800L) * 1000L,
                  runtime.configuration().getLong("auth.premium.recheck-days", 30L) * 86_400_000L,
                  (int) runtime.configuration().getLong("auth.premium.max-lookups-per-minute", 120L)));
      com.sexidium.core.auth.AuthSessionService sessions =
          new com.sexidium.core.auth.AuthSessionService(
              database, runtime.configuration(), runtime.logger(), messages, authService, premium,
              proxyId(),
              // "Is a Discord bot reachable from this network?" -- which is NOT the same question as
              // "does this JVM have a bot token", and the proxy holds no bot config at all.
              () -> registry.anyAlive(NodeCapability.BOT_HOST));
      // The hold is the only path that lets an unverified connection reach a world, so it is offered
      // only when it is actually performable: a lobby has to be alive to do the freezing.
      sessions.setHoldAvailable(() ->
          runtime.configuration().getBoolean("auth.hold.enabled", false)
              && registry.anyAlive(NodeCapability.LOBBY));
      // Only the proxy can flip online-mode per connection, so only the proxy may enforce the
      // "this name is premium, refuse offline connections to it" rule.
      sessions.setPremiumGate(true);
      premium.attachIdentities(sessions.identities());
      authService.setSessionService(sessions);

      this.authLoginService = new AuthLoginService(
          runtime.configuration(), runtime.logger(), messages, authService,
          () -> runtime.configuration().getBoolean("auth.enabled", true),
          () -> registry.anyAlive(NodeCapability.BOT_HOST),
          sessions);
      proxy.getEventManager().register(this, new com.sexidium.velocity.auth.VelocityAuthGate(
          proxy, logger, runtime.configuration(), messages, authLoginService, sessions, premium));
      logger.info("Login auth gate active (sessions={}, approvals={}, premium={}, hold={})",
          sessions.enabled(), sessions.approvalsEnabled(), sessions.premiumEnabled(),
          sessions.holdEnabled());
    } catch (Exception failed) {
      // A proxy that cannot reach the database is degraded, not broken: players still connect
      // and reach the lobby. Saying so loudly beats silently never routing anyone.
      logger.error("Could not open the shared database; placement routing is disabled", failed);
    }
  }

  @Subscribe
  public void onProxyShutdown(ProxyShutdownEvent event) {
    logger.info("Sexidium proxy module stopping");
    if (registration != null) {
      registration.shutdown();
    }
    if (database != null) {
      database.close();
      database = null;
    }
  }

  /**
   * Send an arriving player to a lobby.
   *
   * <p>Velocity's own {@code try:} list already does this; going through the same lobby lookup the
   * kick redirect uses keeps one definition of "where is the lobby" instead of two that drift.</p>
   */
  @Subscribe
  public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
    // Short-circuit a LIVE experience. "Land in the lobby, get the menu" stays the rule for ordinary
    // logins and is the right one — but when a player's world is loaded RIGHT NOW on a worker, going
    // via the lobby is a wasted hop through the one node whose tick is shared by the whole network,
    // and D2 forbids the lobby opening the world anyway. One read, and it fails open to the lobby.
    liveExperienceHost(event).or(this::lobbyServer).ifPresent(event::setInitialServer);
  }

  /**
   * The worker currently holding this player's last experience world, if any.
   *
   * <p>Read-only, and deliberately narrow: a placement row that is LOADED with a live lease is the
   * only evidence that a world is being served this instant. Anything less certain lands in the
   * lobby, which is always correct if sometimes an extra hop.</p>
   */
  private Optional<RegisteredServer> liveExperienceHost(PlayerChooseInitialServerEvent event) {
    if (placements == null || backends == null) {
      return Optional.empty();
    }
    try {
      return placements.all().stream()
          .filter(placement -> com.sexidium.core.network.DbWorldLeaseAuthority.STATE_LOADED
              .equals(placement.state()))
          .filter(placement -> placement.leaseHeld(System.currentTimeMillis()))
          .filter(placement -> event.getPlayer().getUniqueId().toString()
              .equals(placement.ownerUuid()))
          // Never land a fresh login on a node that is draining for a restart. It is still routable
          // for the players already inside it -- that is what DRAINING means -- but sending it a new
          // one adds work to the very node trying to reach zero, and that player would be moved
          // straight out again. One extra hop through the lobby; never a disconnect.
          .filter(placement -> !draining(placement.nodeId()))
          .findFirst()
          .flatMap(placement -> backends.byNodeId(placement.nodeId()));
    } catch (RuntimeException unavailable) {
      // The lobby is the fallback for every uncertainty here; a login must never fail on this.
      return Optional.empty();
    }
  }

  /**
   * A player kicked from a backend goes back to the lobby instead of off the network.
   *
   * <p>Without this, restarting a worker disconnects everyone on it entirely — they are dropped from
   * the proxy, not moved — which turns a routine rolling restart into a visible outage. This is the
   * single highest-value listener in the module and the reason it exists before the auth gate.</p>
   */
  @Subscribe
  public void onKickedFromServer(KickedFromServerEvent event) {
    Optional<RegisteredServer> lobby = lobbyServer();
    if (lobby.isEmpty()) {
      return; // Nothing to redirect to; let Velocity's default disconnect stand.
    }
    // Do not bounce a player being kicked FROM the lobby back INTO the lobby.
    if (event.getServer().getServerInfo().getName().equals(lobby.get().getServerInfo().getName())) {
      return;
    }
    NetworkPlayer player = new VelocityPlayer(event.getPlayer(), runtime.identity().nodeId());
    // Cancel any ticket in flight for this player. Without it a rolling restart leaves a live ticket
    // aimed at the backend they were just kicked off, which re-fires the moment a proxy polls again
    // and bounces them straight back into a server that is still coming up.
    if (transfers != null) {
      transfers.cancel(event.getPlayer().getUniqueId());
    }
    logger.info("Redirecting {} to the lobby after being kicked from {}",
        player.name(), event.getServer().getServerInfo().getName());
    event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby.get(), reason(event)));
  }

  private Component reason(KickedFromServerEvent event) {
    return event.getServerKickReason()
        .orElse(MiniMessage.miniMessage().deserialize(
            "<gray>That server is unavailable; you were moved to the lobby.</gray>"));
  }

  /**
   * The lobby to route to: the least-loaded LIVE, NON-DRAINING lobby node.
   *
   * <p>This used to return {@code proxy.getServer("lobby")} the moment that name existed — which it
   * always does, because the provisioner writes it into velocity.toml. So the least-loaded fallback
   * below was dead code in every deployed topology, a second lobby would have received zero initial
   * joins however full the first one was, and a draining lobby kept being handed new players by the
   * very fallback that was supposed to route around it. Fixing that is what unblocks lobby
   * scale-out, and therefore what makes a zero-disconnect lobby update possible at all.</p>
   *
   * <p>The registry's answer first; everything below it is {@link LobbySelector#fallback}, which is
   * where the tiers and their reasons live — pure over the node rows and a name/player-count pair per
   * server, so the whole decision is unit-testable. It was three lines here, untested because a
   * {@code RegisteredServer} cannot be constructed in a unit test, and two of the three were wrong:
   * the named-{@code lobby} tier kept feeding a DRAINING lobby (so its drain could never finish) and
   * the last tier was not filtered to lobbies at all (so a kicked player could land on a worker).</p>
   */
  private Optional<RegisteredServer> lobbyServer() {
    Optional<RegisteredServer> chosen = registry == null ? Optional.empty()
        : LobbySelector.choose(
                safeNodes(), System.currentTimeMillis(), lobbyTimeoutMillis,
                (int) runtime.configuration().getLong("network.placement.assumed-capacity", 100L),
                (int) runtime.configuration().getLong("network.placement.load-band-percent", 10L))
            .flatMap(proxy::getServer);
    if (chosen.isPresent()) {
      return chosen;
    }
    List<LobbySelector.Candidate> candidates = proxy.getAllServers().stream()
        .map(server -> new LobbySelector.Candidate(
            server.getServerInfo().getName(), server.getPlayersConnected().size()))
        .toList();
    return LobbySelector.fallback(safeNodes(), candidates, "lobby").flatMap(proxy::getServer);
  }

  /** Whether a node is draining. Unknown nodes are treated as NOT draining — fail open, then route. */
  private boolean draining(String nodeId) {
    if (registry == null || nodeId == null) {
      return false;
    }
    for (com.sexidium.core.network.NodeRegistry.Node node : safeNodes()) {
      if (nodeId.equals(node.nodeId())) {
        return node.draining();
      }
    }
    return false;
  }

  /** The registry, defended: a login must never fail because a SELECT did. */
  private java.util.List<com.sexidium.core.network.NodeRegistry.Node> safeNodes() {
    try {
      return registry.all();
    } catch (RuntimeException unavailable) {
      return java.util.List.of();
    }
  }
}
