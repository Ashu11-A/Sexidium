package com.sexidium.core;

import com.sexidium.core.auth.AuthLoginService;
import com.sexidium.core.auth.AuthService;
import com.sexidium.core.bot.BotManager;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.data.RankService;
import com.sexidium.core.game.GameEventRouter;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.persist.MatchRepository;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.net.ApiServer;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.AbstractWorldControl;
import com.sexidium.core.world.lobby.LobbyManager;

import java.util.List;

public final class SexidiumCore implements AutoCloseable {
  private final SexidiumCoreDependencies dependencies;
  private final MessageService messageService;
  private final MatchRepository matchRepository;
  private final RankService rankService;
  private final FriendService friendService;
  /** Display rules for money. Built even with no database: the noop port still formats nothing. */
  private final com.sexidium.core.economy.CurrencyFormat currencyFormat;
  /** The money ledger, or null when there is no database — exactly like {@link #ranks()}. */
  private final com.sexidium.core.economy.EconomyService economyService;
  private final LobbyManager lobbyManager;
  private final AuthLoginService authLoginService;
  private final com.sexidium.core.auth.AuthSessionService authSessionService;
  /** Node-local register of players frozen waiting on a Discord button. See {@code PaperAuthHold}. */
  private final com.sexidium.core.auth.AuthHoldService authHoldService =
      new com.sexidium.core.auth.AuthHoldService();
  private com.sexidium.core.auth.AuthRequestCourier authRequestCourier;
  private ScheduledTask authCourierTask;
  private ScheduledTask authSweepTask;
  private final ApiServer apiServer;
  private final com.sexidium.core.lib.net.BridgeClient bridgeClient;
  private final com.sexidium.core.lib.net.ResourcePackServer resourcePackServer;
  private final BotManager botManager;
  private final GameEventRouter gameEventRouter;
  private final GameContext gameContext;
  private final GameManager gameManager;
  private final com.sexidium.core.world.map.editor.MapEditorService mapEditorService;
  private final com.sexidium.core.menu.MenuService menuService;
  private final com.sexidium.core.world.hotbar.HotbarController hotbarController;
  private final com.sexidium.core.game.experience.ExperienceManager experienceManager;
  private final com.sexidium.core.game.experience.ExperienceService experienceService;
  private final com.sexidium.core.world.npc.NpcManager npcManager;
  private final com.sexidium.core.decor.DecorManager decorManager;
  private final com.sexidium.core.world.LobbyHud lobbyHud;
  private final com.sexidium.core.data.RankTagService rankTagService;
  private final com.sexidium.core.network.NetworkService networkService;
  /** The drain state machine, or null when standalone (nothing to hand anything to). */
  private com.sexidium.core.network.DrainCoordinator drainCoordinator;
  private ScheduledTask worldGcTask;
  private final com.sexidium.core.game.experience.compose.StackMergeService stackMergeService =
      new com.sexidium.core.game.experience.compose.StackMergeService();
  private ScheduledTask stackMergeTask;
  private ScheduledTask stackMergePumpTask;

  public SexidiumCore(SexidiumCoreDependencies dependencies) {
    this.dependencies = dependencies;
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    this.messageService = new MessageService(serverAdapter.resources(), serverAdapter.configuration(), serverAdapter.logger());
    // Resolved before every other subsystem: identity decides what this node may run at all.
    this.networkService = new com.sexidium.core.network.NetworkService(serverAdapter, dependencies.database());
    this.matchRepository = dependencies.database() == null
        ? null
        : new MatchRepository(serverAdapter.logger(), dependencies.database());
    if (matchRepository != null) {
      // Scope every match row to THIS node. `matches` had no node column, so each node read the whole
      // shared table, imported every other node's live matches, and then deleted them as "stale" ten
      // minutes after every boot.
      matchRepository.setNodeId(networkService.identity().nodeId());
    }
    this.rankService = dependencies.database() == null
        ? null
        : new RankService(
            serverAdapter.configuration(),
            serverAdapter.logger(),
            serverAdapter.messages(),
            dependencies.database(),
            dependencies.authService(),
            dependencies.authEnabled()
        );
    this.friendService = dependencies.database() == null
        ? null
        : new FriendService(serverAdapter.logger(), dependencies.database());
    // Money depends on neither ranks nor auth: a balance is a fact about a Minecraft account, and
    // making it wait on a Discord link would mean a player who never links has no money at all.
    this.currencyFormat = new com.sexidium.core.economy.CurrencyFormat(serverAdapter.configuration());
    this.economyService = dependencies.database() == null
        ? null
        : new com.sexidium.core.economy.EconomyService(
            serverAdapter.configuration(),
            serverAdapter.logger(),
            dependencies.database(),
            currencyFormat,
            networkService.identity().nodeId());
    // The frictionless half of the gate: IP+name sessions, Discord approvals and premium
    // verification. Built here (not injected) because everything it needs is already in scope, and
    // null when there is no database at all -- the code gate below then behaves exactly as before.
    this.authSessionService = dependencies.database() == null
        ? null
        : new com.sexidium.core.auth.AuthSessionService(
            dependencies.database(),
            serverAdapter.configuration(),
            serverAdapter.logger(),
            messageService,
            dependencies.authService(),
            null,
            networkService.identity().nodeId(),
            // On a backend the bot node is a peer, so ask the registry rather than this node's own
            // (absent) bot config. Standalone holds every capability and answers true.
            () -> networkService.registry() == null
                || networkService.identity().can(com.sexidium.core.network.NodeCapability.BOT_HOST)
                || networkService.registry().anyAlive(com.sexidium.core.network.NodeCapability.BOT_HOST));
    if (authSessionService != null) {
      // A backend IS the node that performs a hold, so here the config key is the whole answer.
      authSessionService.setHoldAvailable(authSessionService::holdEnabled);
    }
    if (authSessionService != null && dependencies.authService() != null) {
      // Linking an account also remembers the network it was linked from, which is one fewer
      // approval on a player's very first join.
      dependencies.authService().setSessionService(authSessionService);
    }
    this.authLoginService = new AuthLoginService(
        serverAdapter.configuration(),
        serverAdapter.logger(),
        messageService,
        dependencies.authService(),
        dependencies.authEnabled(),
        null,
        authSessionService
    );
    // The API is also the rolling-update control surface (/node, /network, /node/drain,
    // /node/selftest), which is why it needs the network layer: /command is fire-and-forget and
    // cannot return data, so anything an orchestrator has to READ needs its own endpoint.
    this.apiServer =
        new ApiServer(serverAdapter, rankService, dependencies.authService(), networkService);
    // Real-time typed RPC to the Discord bot (WebSocket). Supersedes the pull-only HTTP bridge above,
    // which is kept for backwards compatibility / health checks.
    this.bridgeClient = new com.sexidium.core.lib.net.BridgeClient(
        serverAdapter, rankService, dependencies.authService(), authSessionService);
    if (authSessionService != null) {
      // A decision reaches the held player two ways on purpose: directly when the hold is on THIS
      // node (the lobby is both the bot host and the hold host in the normal topology), and over the
      // bus when it is not. The 1s re-read of auth_requests in the hold is the third net.
      authSessionService.setDecisionListener((row, approved) -> {
        bridgeClient.emitAuthDecided(row.requestId(), row.displayName(),
            approved ? "approved" : "denied");
        networkService.bus().publish(
            com.sexidium.core.network.NetworkBus.Topics.AUTH_DECIDED,
            row.identityId(), approved ? "approved" : "denied");
        releaseHold(row.identityId(), approved);
      });
    }
    if (rankService != null) {
      // Live rank tag/role refresh in Discord the moment a player's points change.
      // Fan out to BOTH the Discord bridge and the network bus. The seam holds one
      // listener, so composing here keeps RankService unaware of either consumer.
      // Friends are symmetric and the two players are usually on different nodes, so BOTH
      // uuids go on the wire; each node acts only for the player it actually hosts.
      if (friendService != null) {
        friendService.setFriendChangeListener((first, second) -> {
          networkService.bus().publish(
              com.sexidium.core.network.NetworkBus.Topics.FRIEND_CHANGED, first.toString(), second.toString());
          networkService.bus().publish(
              com.sexidium.core.network.NetworkBus.Topics.FRIEND_CHANGED, second.toString(), first.toString());
        });
      }
      rankService.setRankChangeListener((uuid, name, discordUserId) -> {
        bridgeClient.emitRankChanged(uuid, name, discordUserId);
        // Key is the player uuid: a peer only needs to know WHO changed, then reads
        // the authoritative value from the shared database itself.
        networkService.bus().publish(
            com.sexidium.core.network.NetworkBus.Topics.RANK_CHANGED, uuid, name == null ? "" : name);
      });
    }
    // A SIBLING of the block above and deliberately not inside it: money exists on a node with a
    // database and no ranks, and nesting this under `rankService != null` would have silently made
    // every balance node-local the day somebody turned ranks off.
    if (economyService != null) {
      economyService.setBalanceChangeListener((accountId, accountName) ->
          networkService.bus().publish(
              com.sexidium.core.network.NetworkBus.Topics.ECONOMY_CHANGED,
              accountId.toString(), accountName == null ? "" : accountName));
    }
    this.resourcePackServer = new com.sexidium.core.lib.net.ResourcePackServer(serverAdapter);
    this.botManager = new BotManager(serverAdapter, dependencies.database());
    RankAwardPort rankAwardPort = rankService == null ? RankAwardPort.noop() : rankService;
    this.gameContext = new GameContext(serverAdapter, dependencies.kitAdapter(), rankAwardPort);
    this.gameManager = new GameManager(gameContext, dependencies.gameRegistry(), matchRepository);
    this.lobbyManager = new LobbyManager(serverAdapter, gameManager, friendService);
    this.gameEventRouter = new GameEventRouter(serverAdapter, gameManager, lobbyManager);
    // Push real-time join/leave events to the Discord bot.
    // ONE slot, two consumers, so this composes rather than assigns: setPlayerJoinListener holds a
    // single listener, and a second call would have replaced the Discord bridge emit above with the
    // account materialisation below -- taking join announcements off Discord to create a money row.
    this.gameEventRouter.setPlayerJoinListener(player -> {
      bridgeClient.emitPlayerJoin(player.uniqueId().toString(), player.name());
      if (economyService != null) {
        // Async: a first join must not wait on an INSERT, and balance() already answers the
        // starting balance for a player whose row does not exist yet.
        economyService.ensureAccountAsync(player.uniqueId(), player.name());
      }
    });
    this.gameEventRouter.setPlayerLeaveListener(
        player -> bridgeClient.emitPlayerLeave(player.uniqueId().toString(), player.name()));
    this.mapEditorService = new com.sexidium.core.world.map.editor.MapEditorService(serverAdapter);
    this.gameEventRouter.setMapEditor(this.mapEditorService);
    this.experienceManager = new com.sexidium.core.game.experience.ExperienceManager(serverAdapter.logger(), dependencies.database());
    this.gameContext.attachExperiences(experienceManager);
    this.gameContext.attachStackMerge(stackMergeService);
    com.sexidium.core.game.experience.ExperienceStateStore experienceStore =
        new com.sexidium.core.game.experience.ExperienceStateStore(
            serverAdapter.worlds().experiencesSubdir(), serverAdapter.logger());
    this.gameContext.attachExperienceStore(experienceStore);
    this.experienceService = new com.sexidium.core.game.experience.ExperienceService(serverAdapter, gameManager, experienceManager, lobbyManager, friendService);
    // The backup engine reads region files straight off the disk, so it needs to know when a world is
    // busy from all THREE sides: the world layer answers "is any of its dimensions open here", the game
    // layer answers "is a match running on it here", and the placement table answers the one question
    // neither of them can — "does another node have it". The first two are node-local by construction,
    // and a restore or a refresh runs on the node holding the SOURCE while touching a second world that
    // any worker may have open; the router is where the locator lives, so the answer comes from there.
    // It is a method reference and not a captured value because the locator is attached later, when the
    // network comes up, and before that this is the only node there is.
    this.experienceService.attachBackup(new com.sexidium.core.game.experience.ExperienceBackupService(
        serverAdapter, experienceManager, experienceStore,
        worldKey -> gameManager.matchByWorldKey(worldKey) != null,
        this.experienceService.commands()::heldElsewhere,
        // And the other end of the same verb: a copy has to RECORD where it landed. Without this a
        // backup and a duplicate were born with folders and no placement row, which made every folder
        // op on them unroutable from the lobby -- so the owner's delete was refused for good and the
        // only way out was to enter the world once. Read lazily rather than captured: the placement
        // authority does not exist until the network comes up, and before that there is one node.
        key -> {
          com.sexidium.core.network.DbWorldLeaseAuthority authority = networkService.placements();
          return authority == null || authority.adopt(key.key(),
              com.sexidium.core.network.DbWorldLeaseAuthority.KIND_PERSISTENT, null);
        }));
    this.npcManager = new com.sexidium.core.world.npc.NpcManager(serverAdapter, gameManager, lobbyManager);
    this.menuService = new com.sexidium.core.menu.MenuService(serverAdapter, gameManager, lobbyManager, friendService, experienceService, npcManager);
    // Single authority over the dynamic lobby hotbar (Menu/Minigames/Players/My Experiences + a
    // conditional Friend Requests badge). Platform adapters render its resolved items and forward clicks.
    this.hotbarController = new com.sexidium.core.world.hotbar.HotbarController(
        serverAdapter, gameManager, lobbyManager, friendService, menuService);
    this.npcManager.setAdminEditorOpener(this.menuService::openNpcEditor);
    // First player join is the reliable "server fully up" signal: re-spawn NPCs then so they survive the
    // boot-time race against the platform NPC backend's own load. See NpcManager.onPlayerJoin().
    this.gameEventRouter.setPlayerJoinHook(this.npcManager::onPlayerJoin);
    this.decorManager = new com.sexidium.core.decor.DecorManager(serverAdapter, npcManager);
    this.npcManager.setDecorSync(decorManager::rebuildAndSpawn, decorManager::respawnForNpc, decorManager::removeForNpc);
    this.lobbyHud = new com.sexidium.core.world.LobbyHud(serverAdapter, gameManager, friendService, rankService);
    this.rankTagService = new com.sexidium.core.data.RankTagService(serverAdapter, rankService);
  }

  /** Node identity, the cross-node bus and (when networked) the node registry. */
  public com.sexidium.core.network.NetworkService network() {
    return networkService;
  }

  public void start() {
    messageService.reload();
    // Published BEFORE the first heartbeat: a peer deciding where to put a world has to be able to
    // see what this node can actually run, and "no codes yet" reads identically to "an older build".
    networkService.setContentManifest(
        com.sexidium.core.network.ContentManifest.local(dependencies.gameRegistry()));
    // And the other half of the same gate: what each WORLD needs. Without this the requirements stay
    // WorldContentRequirements.NONE for the life of the process, so missingContentFor() answers "[]"
    // for every world, canHostContent() answers "yes" for every world, and all four enforcement
    // points -- the door guard, choose(), adoptableHere and the SX-CONTENT refusal -- are dead code
    // that never refuses anything. The manifest above is what this node HAS; this is what a world
    // ASKS for, and a capability check needs both halves to be a check at all.
    //
    // The digest is read per call rather than captured: setContentManifest may be re-run, and a
    // chaos world's requirement is "the host's catalog is identical to MINE".
    networkService.setContentRequirements(worldKey -> experienceManager.contentCodesFor(
        worldKey, networkService.contentManifest().digest()));
    networkService.setSelfTest(new com.sexidium.core.network.NodeSelfTest(
        dependencies.gameRegistry(),
        // Reported, not exercised: acquiring a world from the pool generates terrain, which is
        // minutes of work on a cold node and would blow the selftest's time bound on exactly the
        // node a roll is waiting for.
        () -> dependencies.serverAdapter().worlds() instanceof AbstractWorldControl control
            ? control.describePool() : "unknown",
        dependencies.database()));
    networkService.start();
    if (networkService.networked()) {
      networkInspector = new com.sexidium.core.network.NetworkInspector(
          networkService.registry(), networkService.placements(), networkService.transferService());
      startDrainCoordinator();
    }
    subscribeToNetworkEvents();
    // Install BEFORE any world is acquired: on a network a persistent world must never be
    // generated by a node that does not hold its folder.
    if (dependencies.serverAdapter().worlds() instanceof com.sexidium.core.world.AbstractWorldControl control) {
      // The planner must never plan away a world this node already has on disk, and only the world
      // control knows what is on disk. Installed before the gate for that reason.
      networkService.setLocalDiskIndex(this::hasWorldFolder);
      requireSharedStorageMarker();
      // The lease heartbeat's two inputs: what this node holds open, and where each player is. A
      // world with a live lease is one a peer must not touch; one whose lease lapses is free.
      networkService.setOpenWorlds(control::openPersistentPlacementKeys, this::experiencePopulations);
      // The fenced half of the heartbeat: renew each claim, and EVICT this node from any world whose
      // renewal is refused. Without this a dispossessed node keeps writing to region files a peer has
      // open, which is the two-writer path that has no session.lock standing in its way.
      networkService.setClaimHeartbeat(control::heldClaims, claim -> {
        com.sexidium.core.network.NetworkInspector inspector = networkInspector;
        if (inspector != null) {
          inspector.recordEviction();
        }
        control.onLeaseLost(claim);
      });
      if (networkService.placements() != null) {
        control.setLeaseAuthority(networkService.placements());
      }
      control.setPlacementGate(networkService.placementGate());
      // Invariant I3, in all four doors. The lobby deliberately lacks EXPERIENCES, and must never
      // open, create, delete or regenerate an experience world -- the capability decided only where
      // a world was PLANNED before, and guarded none of the doors that actually open one.
      //
      // Two more conditions on the same door, both closing a way to open a world this node should not:
      //   - CONTENT. A build missing one of the world's challenges answers "no, this does not need a
      //     void world" and generates NORMAL terrain over an empty SkyBlock. Silent, permanent, and
      //     reachable during any roll -- so a node that cannot run it refuses to open it, says so once
      //     (SX-CONTENT), and leaves it shut until a node that can does.
      //   - DRAINING. This node is on its way out; opening a fresh world here would be work handed to
      //     a process that is about to stop, and one more world for the drain to hand back.
      control.setExperienceDoorGuard(key -> {
        if (!networkService.identity().can(com.sexidium.core.network.NodeCapability.EXPERIENCES)) {
          return false;
        }
        if (networkService.draining()) {
          return false;
        }
        java.util.List<String> missing = networkService.missingContentFor(key.key());
        if (!missing.isEmpty()) {
          networkService.logContentRefusal(key.key(), missing);
          return false;
        }
        return true;
      });
      // When the gate refuses, the players who were trying to get in must be SENT to the node that
      // owns the world. Refusing without routing is safe but leaves the player stuck.
      control.setPlacementRouter(this::routeToOwningNode);
      // Where evicted players go when this node has no lobby world to put them in -- which is the
      // normal state of a worker, i.e. of every node that hosts experiences. Without this, eviction
      // moved nobody on exactly the nodes where it matters, and a world with players still in it
      // cannot be unloaded: the evicted node kept the region files open, claim already dropped,
      // while the new holder wrote them.
      control.setEvacuationFallback(players -> {
        for (com.sexidium.core.platform.PlayerAdapter player : players) {
          if (player != null && player.online()) {
            routeToLobbyNode(player);
          }
        }
      });
      // BEFORE anything is opened: drop this node's autoload entries for worlds it has no claim on.
      // Multiverse acts on its books before Sexidium is even enabled, so this is the only moment the
      // question can be asked at all.
      if (networkService.placements() != null) {
        java.util.Set<String> held = new java.util.LinkedHashSet<>();
        for (var placement : networkService.placements().heldBy(networkService.identity().nodeId())) {
          if (placement.key() != null) {
            held.add(placement.key().key());
          }
        }
        control.pruneForeignRegistrations(held);
      }
      reconcilePlacements();
    }
    // "I was draining when I went down -- what is still mine?" A QUERY and never a bus replay:
    // DbNetworkBus.start() seeds its cursor to MAX(id), so a node that was down for the restart sees
    // nothing published while it was gone. After reconciliation, because reconciliation is what makes
    // the placement rows agree with the disk this reads.
    if (drainCoordinator != null) {
      try {
        drainCoordinator.reclaim();
      } catch (RuntimeException failed) {
        // A reclaim that fails costs a stale row a sweeper will remove. It must never stop the boot:
        // a plugin that does not enable is a dead node, which is worse than an untidy table.
        dependencies.serverAdapter().logger().warning(
            "Drain reclaim failed at boot: " + failed.getMessage());
      }
    }
    // Cross-node match assembly: the launcher publishes the roster, the arrival gate admits
    // players who were transferred in for it.
    if (networkService.handoffs() != null) {
      gameManager.setHandoffs(
          networkService.handoffs(),
          networkService.identity().nodeId(),
          networkService.registry() == null ? 0L : networkService.registry().epoch());
    }

    // Both halves of an experience transfer, wired together (see GameManager#setTransferHooks):
    // the arrival opens the world the player was sent for, and the launcher stops reporting a local
    // world failure at players who are on their way somewhere else.
    if (networkService.transfers() != null) {
      gameManager.setTransferHooks(
          this::resumeTransferredExperience,
          // Backed by the ticket itself now, not by an in-memory "I asked recently" map that expired
          // blind after ten seconds and knew nothing about whether the move had actually happened.
          playerId -> networkService.transfers().inFlight(playerId));
      gameManager.setArrivalGate(
          networkService.arrivals(),
          networkService.identity().nodeId(),
          networkService.registry() == null ? 0L : networkService.registry().epoch());
      // A worker has no lobby world: every "back to the lobby" there is a transfer to the lobby node.
      if (!networkService.identity().can(com.sexidium.core.network.NodeCapability.LOBBY)) {
        gameContext.attachLobbyRouter(this::routeToLobbyNode);
      }
    }

    // Owner actions run where the WORLD is, not where the owner is. The manage menu lives in the
    // lobby, which never holds an experience world -- so delete, end-match and every live edit used
    // to silently do nothing to the running world, and delete did something far worse.
    if (experienceService != null) {
      experienceService.commands().attach(
          networkService.placements(), networkService.bus(), networkService.identity().nodeId(),
          networkService.experienceCommands(),
          // The SAME question invariant I3's door asks. Without it the lobby fell through to running
          // a delete itself, was refused by that door, and told the owner their map was gone.
          () -> networkService.identity().can(com.sexidium.core.network.NodeCapability.EXPERIENCES)
              && !networkService.draining());
      // The recovery half of the same fix: a copy made BEFORE the recorder above existed has folders
      // on the shared tree and no placement row, so the lobby could not route its delete and refused
      // it every time. This asks the fleet who could run it instead of telling the owner to go and
      // enter a world they are trying to throw away. See NetworkService.homeForUnplacedWorld for the
      // two cases it declines -- no capable node, and storage the nodes do not actually share.
      experienceService.commands().attachHomeFinder(
          key -> networkService.homeForUnplacedWorld(key.key()));
      networkService.bus().subscribe(
          com.sexidium.core.network.NetworkBus.Topics.EXPERIENCE_COMMAND,
          (topic, key, payload, originNode) ->
              // Back onto the main thread: ending a match and unloading a world are both world-thread
              // operations, and the bus dispatches on its poller.
              dependencies.serverAdapter().scheduler().runNow(
                  () -> experienceService.commands().onMessage(key, payload)));
      networkService.bus().subscribe(
          com.sexidium.core.network.NetworkBus.Topics.EXPERIENCE_COMMAND_RESULT,
          (topic, key, payload, originNode) ->
              dependencies.serverAdapter().scheduler().runNow(
                  () -> experienceService.commands().onResult(key, payload)));
      // The recovery tick, and the only reason a delete survives the worker holding that world being
      // restarted: the bus seeds its cursor to MAX(id) at start, so what was published while a node
      // was down is never delivered to it. The ROW is, on this timer.
      if (networkService.networked()) {
        dependencies.serverAdapter().scheduler().runTimer(
            () -> experienceService.commands().tick(), 20L, 20L);
      }
      // A SHARED map has to show its owner's latest state, and the registry row is what "latest"
      // means: everything below re-reads it rather than being handed a copy of the new value.
      //
      // Two consumers, and neither of them could learn a change before this existed. The world that
      // is RUNNING composed its challenges and its setup once, at start, on whichever worker holds
      // it. And the menus in the LOBBY are photographs of rows the owner is editing from that same
      // lobby, for players standing next to them.
      experienceService.setUpdateChannel(networkService.bus(), networkService.identity().nodeId(),
          experienceId -> dependencies.serverAdapter().scheduler().runNow(() -> {
            // On the node holding the world: make the live match agree with the row. A no-op, and
            // one primary-key read, on every node that is not running it.
            experienceService.reconcileLive(experienceId);
            // On the node holding the VIEWERS: redraw what they have open.
            menuService.refreshExperienceScreens();
          }));
      networkService.bus().subscribe(
          com.sexidium.core.network.NetworkBus.Topics.EXPERIENCE_UPDATED,
          (topic, key, payload, originNode) ->
              // No scheduler hop here: the watcher above does its own, because it is also called
              // directly on the node that made the change (the bus never echoes to the publisher).
              experienceService.onExperienceUpdated(key, payload));
    }

    // Share party membership across nodes: the lobby mirrors it, workers read it.
    if (networkService.lobbyDirectory() != null) {
      lobbyManager.setLobbyDirectory(
          networkService.lobbyDirectory(), networkService.identity().nodeId());
      experienceService.setLobbyDirectory(networkService.lobbyDirectory());
    }

    dependencies.kitAdapter().reload();
    restorePersistedMatches();

    // --- capability-gated subsystems -------------------------------------------------
    //
    // Each subsystem declares WHAT it needs, once, here. Standalone holds every capability, so a
    // single server starts exactly what it starts today; a worker simply never constructs the
    // lobby's NPCs, decor and HUD, and never ticks a matchmaker the lobby owns.
    //
    // The capability check lives on these lines and nowhere else -- gameplay code never asks what
    // kind of node it is running on.
    com.sexidium.core.network.NodeIdentity node = networkService.identity();

    dependencies.serverAdapter().worlds().start();

    startIf(node, "api", apiServer::start, com.sexidium.core.network.NodeCapability.API_HOST);
    // Exactly one node serves the pack. Several would hand clients several SHA-1s and re-prompt
    // on every server switch.
    startIf(node, "resource-pack", resourcePackServer::start,
        com.sexidium.core.network.NodeCapability.PACK_HOST);
    // Exactly one node runs the bot; four would open four Discord gateways on one token.
    startIf(node, "discord-bot", botManager::start,
        com.sexidium.core.network.NodeCapability.BOT_HOST);
    // Start after the bot is launching; the client retries until the bot's WebSocket server is up.
    startIf(node, "bridge", bridgeClient::start,
        com.sexidium.core.network.NodeCapability.BOT_HOST);
    // The courier carries approvals from the shared table to the bot, so it belongs on the node with
    // the bridge -- the proxy writes the rows and cannot reach the bot at all.
    startIf(node, "auth-courier", this::startAuthCourier,
        com.sexidium.core.network.NodeCapability.BOT_HOST);
    // One sweeper, on the node there is exactly one of.
    startIf(node, "auth-sweeper", this::startAuthSweeper,
        com.sexidium.core.network.NodeCapability.QUEUE_AUTHORITY);

    startIf(node, "lobby-hud", lobbyHud::start, com.sexidium.core.network.NodeCapability.LOBBY);
    startIf(node, "npcs", npcManager::start, com.sexidium.core.network.NodeCapability.LOBBY);
    startIf(node, "decor", decorManager::start, com.sexidium.core.network.NodeCapability.LOBBY);
    startIf(node, "lobbies", lobbyManager::start,
        com.sexidium.core.network.NodeCapability.QUEUE_AUTHORITY);

    startWorldGarbageCollector();
    startStackMerge();
  }

  /**
   * Wire the drain state machine.
   *
   * <p>No timer of its own: {@code NetworkService}'s heartbeat already calls {@code tick()} on the
   * port, wrapped so a throw cannot cost the heartbeat. This only has to hand it the three tables it
   * writes ({@code node_drains}, {@code network_leases}, {@code world_placements}) and the work port
   * below, which is the entire contact surface between the protocol and the game.</p>
   */
  private void startDrainCoordinator() {
    com.sexidium.core.lib.data.Database database = networkService.database();
    if (database == null) {
      return;
    }
    com.sexidium.core.platform.LoggerAdapter logger = dependencies.serverAdapter().logger();
    drainCoordinator = new com.sexidium.core.network.DrainCoordinator(
        networkService.identity(),
        networkService.registry(),
        new com.sexidium.core.network.DbDrainStore(database, logger),
        new com.sexidium.core.network.DbNetworkLeases(database, logger),
        networkService.placements(),
        new CoreDrainWork(),
        networkService.bus(),
        logger,
        com.sexidium.core.network.DrainCoordinator.Settings.from(
            dependencies.serverAdapter().configuration()),
        System::currentTimeMillis);
    networkService.setDrainControl(drainCoordinator);
  }

  /**
   * What a drain actually does to this server.
   *
   * <p>Everything here is ordered by one rule: <b>a player's items must be on disk before that player
   * is moved, and their menu must be shut before either.</b> The coordinator drives the order; this
   * class only knows how to perform each step.</p>
   */
  private final class CoreDrainWork implements com.sexidium.core.network.DrainWorkPort {

    @Override
    public int onlinePlayers() {
      return dependencies.serverAdapter().onlinePlayers().size();
    }

    @Override
    public void announceDrain() {
      for (com.sexidium.core.platform.PlayerAdapter player
          : dependencies.serverAdapter().onlinePlayers()) {
        dependencies.serverAdapter().messages().send(player, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_DRAIN_NOTICE));
        // Rendered per player rather than broadcast as one string: a title takes MiniMessage, not a
        // LocalizedText, so the only way to keep it in the player's own language is to resolve it
        // against that player before it is sent.
        dependencies.serverAdapter().scheduler().runNow(() -> player.showTitle(
            new com.sexidium.core.platform.model.TitleSpec(
                messageService.renderMini(player, com.sexidium.core.i18n.LocalizedText.of(
                    com.sexidium.core.i18n.MessageKey.NETWORK_DRAIN_NOTICE_TITLE)),
                messageService.renderMini(player, com.sexidium.core.i18n.LocalizedText.of(
                    com.sexidium.core.i18n.MessageKey.NETWORK_DRAIN_NOTICE_SUBTITLE)),
                250L, 3000L, 500L)));
      }
    }

    @Override
    public void closeMenus() {
      menuService.closeAll();
    }

    /**
     * Live MINIGAME matches only — an experience is counted as a world, because it moves as one.
     *
     * <p>A minigame does not move: there is no serialised match state and no disk anchor to follow,
     * so building the fence for one would be a lock around nothing.</p>
     */
    @Override
    public int matchesInProgress() {
      int live = 0;
      for (com.sexidium.core.game.ActiveMatch match : gameManager.matches()) {
        if (!(match.game() instanceof com.sexidium.core.game.experience.ExperienceGame)) {
          live++;
        }
      }
      return live;
    }

    @Override
    public void endMatchesForUpdate() {
      for (com.sexidium.core.game.ActiveMatch match : List.copyOf(gameManager.matches())) {
        if (match.game() instanceof com.sexidium.core.game.experience.ExperienceGame) {
          continue;
        }
        gameManager.endMatch(match, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_DRAIN_MATCH_ENDED));
      }
    }

    @Override
    public List<String> openExperienceWorlds() {
      return dependencies.serverAdapter().worlds() instanceof AbstractWorldControl control
          ? List.copyOf(control.openPersistentPlacementKeys())
          : List.of();
    }

    /**
     * End the session on one experience world so its claim comes back.
     *
     * <p>Deliberately just {@code endMatch}: its teardown runs {@code ExperienceGame.stop()} — which
     * now captures every player's items BEFORE the reset clears them — saves and closes the world,
     * and only then hands the claim back. Releasing the claim by hand instead would advertise the
     * world as free while this node was still writing its region files, which is the exact two-writer
     * failure the whole lease exists to prevent.</p>
     *
     * <p>A world with NO match still has to be handed over, and ending a match was the only thing
     * this did. {@code openExperienceWorlds()} reports everything the world layer holds open, which
     * includes its {@code closing} set — "closing, or failed to close" — so one failed unload left a
     * key with no match, no other handover path, and {@code worlds_left} pinned at 1 until the drain
     * stalled at its deadline. The world control's own close/release path is the answer, and it is
     * idempotent: a key whose world is already gone is simply forgotten.</p>
     */
    @Override
    public void handOverWorld(String worldKey) {
      com.sexidium.core.world.WorldKey key =
          com.sexidium.core.world.WorldKey.tryParse(worldKey).orElse(null);
      if (key == null) {
        return;
      }
      com.sexidium.core.game.ActiveMatch match = gameManager.matchByWorldKey(key);
      if (match != null) {
        gameManager.endMatch(match, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_DRAIN_NOTICE));
        return;
      }
      if (dependencies.serverAdapter().worlds() instanceof AbstractWorldControl control) {
        control.handOverPersistent(key);
      }
    }

    @Override
    public void evacuateToLobby() {
      for (com.sexidium.core.platform.PlayerAdapter player
          : List.copyOf(dependencies.serverAdapter().onlinePlayers())) {
        if (player != null && player.online()) {
          routeToLobbyNode(player);
        }
      }
    }

    @Override
    public void repointLobbyState(String targetNodeId) {
      com.sexidium.core.lib.data.Database database = networkService.database();
      if (database == null || targetNodeId == null) {
        return;
      }
      new com.sexidium.core.network.DbDrainStore(database, dependencies.serverAdapter().logger())
          .repointLobbyGroups(networkService.identity().nodeId(), targetNodeId);
    }

    @Override
    public void flushWriters() {
      if (rankService != null) {
        rankService.flushWrites();
      }
      if (friendService != null) {
        friendService.flushWrites();
      }
      if (matchRepository != null) {
        matchRepository.flushWrites();
      }
      if (economyService != null) {
        economyService.flushWrites();
      }
    }
  }

  /** Start a subsystem only when this node holds the capability it needs. */
  private void startIf(
      com.sexidium.core.network.NodeIdentity node,
      String id,
      Runnable start,
      com.sexidium.core.network.NodeCapability required) {
    if (node.can(required)) {
      start.run();
      startedSubsystems.add(id);
      return;
    }
    dependencies.serverAdapter().logger().info(
        "Subsystem '" + id + "' not started: this node does not hold " + required);
  }

  /** Which capability-gated subsystems actually started, so close() only stops those. */
  private final java.util.Set<String> startedSubsystems = new java.util.LinkedHashSet<>();

  /** Visible for tests: what this node chose to run. */
  public java.util.Set<String> startedSubsystems() {
    return java.util.Collections.unmodifiableSet(startedSubsystems);
  }

  public void reload() {
    dependencies.serverAdapter().configuration().reload();
    messageService.reload();
    dependencies.kitAdapter().reload();
    lobbyHud.reload();
    if (economyService != null) {
      economyService.reload();
    } else {
      currencyFormat.reload();
    }
    // Pool sizes were restart-only, which made tuning them on a live server impossible: the config key
    // changed and nothing read it again until the next boot.
    if (dependencies.serverAdapter().worlds() instanceof AbstractWorldControl worldControl) {
      worldControl.reloadPool();
    }
  }

  @Override
  public void close() {
    // First: tell peers this node is going away, so they stop routing here before the
    // subsystems below start tearing down.
    networkService.close();
    stopWorldGarbageCollector();
    if (stackMergeTask != null) {
      stackMergeTask.cancel();
      stackMergeTask = null;
    }
    if (stackMergePumpTask != null) {
      stackMergePumpTask.cancel();
      stackMergePumpTask = null;
    }
    // Only stop what this node actually started; stopping an unstarted subsystem is at best a
    // no-op and at worst touches state that was never initialised.
    // BEFORE anything else is torn down. An open chest GUI outlives the listener that cancels its
    // clicks, and every button in it then becomes a REAL item the player can drag out -- so skipping
    // this is not a cosmetic glitch, it is item duplication on every stop.
    if (menuService != null) {
      menuService.closeAll();
    }
    stopIf("lobbies", lobbyManager::stop);
    stopIf("decor", decorManager::stop);
    stopIf("npcs", npcManager::stop);
    stopIf("lobby-hud", lobbyHud::stop);
    gameManager.prepareShutdown();
    dependencies.serverAdapter().worlds().shutdown();
    if (authCourierTask != null) {
      authCourierTask.cancel();
      authCourierTask = null;
    }
    if (authSweepTask != null) {
      authSweepTask.cancel();
      authSweepTask = null;
    }
    stopIf("bridge", bridgeClient::stop);
    stopIf("discord-bot", botManager::stop);
    stopIf("resource-pack", resourcePackServer::stop);
    stopIf("api", apiServer::stop);
    if (matchRepository != null) {
      matchRepository.shutdown();
    }
    if (friendService != null) {
      friendService.shutdown();
    }
    if (rankService != null) {
      rankService.shutdown();
    }
    if (economyService != null) {
      economyService.shutdown();
    }
  }

  public MessageService messages() {
    return messageService;
  }

  public GameManager games() {
    return gameManager;
  }

  public com.sexidium.core.menu.MenuService menus() {
    return menuService;
  }

  /** The dynamic lobby hotbar authority; platform lobby guards render its items and route its clicks. */
  public com.sexidium.core.world.hotbar.HotbarController hotbar() {
    return hotbarController;
  }

  /** The hosted menu resource pack (URL + SHA-1), or {@code null} when delivery is disabled/failed. */
  public com.sexidium.core.menu.pack.ResourcePackInfo resourcePack() {
    return resourcePackServer.pack();
  }

  public com.sexidium.core.game.experience.ExperienceManager experiences() {
    return experienceManager;
  }

  public com.sexidium.core.game.experience.ExperienceService experienceService() {
    return experienceService;
  }

  public LobbyManager lobbies() {
    return lobbyManager;
  }

  public com.sexidium.core.world.npc.NpcManager npcManager() {
    return npcManager;
  }

  public com.sexidium.core.decor.DecorManager decorManager() {
    return decorManager;
  }

  public FriendService friends() {
    return friendService;
  }

  public RankService ranks() {
    return rankService;
  }

  /** The money ledger, or {@code null} when this node has no database. Exactly like {@link #ranks()}. */
  public com.sexidium.core.economy.EconomyService economy() {
    return economyService;
  }

  /**
   * The read seam every non-economy subsystem uses. NEVER null — a node with no database gets
   * {@code EconomyPort.noop()}, so the sidebar (and anything else that only reads a balance) needs no
   * null check of its own.
   */
  public com.sexidium.core.economy.EconomyPort economyPort() {
    return economyService == null ? com.sexidium.core.economy.EconomyPort.noop() : economyService;
  }

  public com.sexidium.core.data.RankTagService rankTags() {
    return rankTagService;
  }

  public MatchRepository matches() {
    return matchRepository;
  }

  public AuthLoginService authLogin() {
    return authLoginService;
  }

  /** IP+name sessions, Discord approvals and premium state. Null when there is no database. */
  public com.sexidium.core.auth.AuthSessionService authSessions() {
    return authSessionService;
  }

  /** Who this node currently has frozen waiting on a Discord button. */
  public com.sexidium.core.auth.AuthHoldService authHold() {
    return authHoldService;
  }

  /**
   * How the platform actually lets a held player go (restore gamemode, visibility, hotbar) or kicks
   * them on a Deny. Installed by the adapter's hold listener; absent on a platform without one.
   */
  @FunctionalInterface
  public interface HoldRelease {
    void release(String identityId, boolean approved);
  }

  private volatile HoldRelease holdRelease;

  public void setHoldRelease(HoldRelease holdRelease) {
    this.holdRelease = holdRelease;
  }

  private void releaseHold(String identityId, boolean approved) {
    HoldRelease release = holdRelease;
    if (release == null) {
      return;
    }
    // Back onto the main thread: the release restores a gamemode and player visibility, and the
    // decision arrives on the bridge's dispatch thread or the bus poller.
    dependencies.serverAdapter().scheduler().runNow(() -> release.release(identityId, approved));
  }

  public AuthService auth() {
    return dependencies.authService();
  }

  public BotManager bot() {
    return botManager;
  }

  /**
   * The operator's window into the network, or null standalone (there is nothing to inspect).
   *
   * <p>Built once the network services exist, because every one of its answers is a read of a table
   * that only exists on a network.</p>
   */
  private volatile com.sexidium.core.network.NetworkInspector networkInspector;

  public com.sexidium.core.network.NetworkInspector networkInspector() {
    return networkInspector;
  }

  public GameContext gameContext() {
    return gameContext;
  }

  public GameEventRouter events() {
    return gameEventRouter;
  }

  /**
   * Who is not currently driving their own character. Reached straight from the platform's mob-target
   * guard, which has a mob and a player in hand and no match to route through.
   */
  public com.sexidium.core.game.presence.PlayerControlWatch playerControl() {
    return gameContext.playerControl();
  }

  public com.sexidium.core.world.map.editor.MapEditorService mapEditor() {
    return mapEditorService;
  }

  private void restorePersistedMatches() {
    if (matchRepository == null) {
      return;
    }
    try {
      List<MatchSnapshot> matchSnapshots = matchRepository.loadAll();
      if (matchSnapshots.isEmpty()) {
        return;
      }
      gameManager.importPersisted(matchSnapshots);
      dependencies.serverAdapter().worlds().preserve(gameManager.pendingWorldNames());
      dependencies.serverAdapter().logger().info("Loaded " + matchSnapshots.size() + " in-progress match(es) for reconnection.");
      long graceTicks = Math.max(30L, dependencies.serverAdapter().configuration()
          .getLong("reconnect.restore-grace-seconds", 600L)) * 20L;
      dependencies.serverAdapter().scheduler().runLater(gameManager::discardStalePending, graceTicks);
    } catch (Exception exception) {
      dependencies.serverAdapter().logger().warning("Failed to restore persisted matches", exception);
    }
  }

  /**
   * React to state changed on ANOTHER node.
   *
   * <p>A rank earned on worker-2 has to repaint that player's name tag wherever they actually are.
   * Without this the tag only refreshes on the node that awarded it, so a player who earns a rank in
   * a minigame keeps their old colour in the lobby until they reconnect.</p>
   *
   * <p>Standalone subscribes too, and simply never receives anything — one code path, and the
   * subscription itself is exercised by the same tests either way.</p>
   */
  // ===== placement: choosing a node, taking players there, and getting them back ================

  // TRANSFER_GRACE_MILLIS and HANDOFF_TTL_MILLIS used to live here: two independent timeouts, 10s and
  // 120s, on two independent rows describing one transfer, plus a third (the route TTL, 30s) in the
  // network config. A ticket has ONE expiry and one state machine, and "is this player being moved?"
  // is a question the ticket answers rather than a clock nobody could see.

  /** Whether this node physically has an experience world's folder. */
  /**
   * How many players are in each experience world this node has open.
   *
   * <p>Written into the lease so a peer choosing where to put the next world can see real occupancy
   * instead of guessing from the node's total.</p>
   */
  private java.util.Map<String, Integer> experiencePopulations() {
    java.util.Map<String, Integer> counts = new java.util.HashMap<>();
    var worlds = dependencies.serverAdapter().worlds();
    for (var player : dependencies.serverAdapter().onlinePlayers()) {
      var world = player.world();
      if (world == null) {
        continue;
      }
      // Through WorldKey.fromRuntime, because world.name() is the platform's FLATTENED label
      // ("experiences_death_resets_ab12"), never the canonical name. The old normaliser found no
      // namespace marker and no slash in that, handed the whole string back, and the key therefore
      // never equalled the placement key -- so world_placements.players was 0 for every world in the
      // network and the planner had no occupancy signal at all.
      com.sexidium.core.world.WorldKey.fromRuntime(world.name(), worlds.experiencesSubdirName())
          .ifPresent(key -> counts.merge(key.key(), 1, Integer::sum));
    }
    return counts;
  }

  /**
   * Whether this node's experiences folder is the shared tree every node writes into.
   *
   * <p>The marker is written by the provisioner at the moment it creates that tree and points the
   * nodes at it, so its presence is evidence of the arrangement rather than a restatement of the
   * intent. A node whose folder is genuinely its own never sees one.</p>
   */
  private boolean sharedStorageMarkerPresent() {
    java.nio.file.Path root = dependencies.serverAdapter().worlds().experiencesSubdir();
    return root != null && java.nio.file.Files.exists(root.resolve(".shared-storage"));
  }

  /**
   * Invariant I10: on a node that may open experience worlds, the shared tree must actually be shared.
   *
   * <p>This used to be a WARNING that silently narrowed the feature off. That narrowing is exactly how
   * the network ran for weeks believing a setting that was not true of its filesystem — and the cost
   * of believing it wrongly is not a misconfiguration, it is data loss: a node "takes over" a world
   * whose bytes it cannot see and generates an empty one under the same name. A belief this expensive
   * has to be confirmed by something only the filesystem can answer, and it has to HARD FAIL when the
   * answer is no. A warning nobody reads is how F5 stayed live.</p>
   */
  private void requireSharedStorageMarker() {
    if (!networkService.networked() || !networkService.sharedWorldStorage()
        || !networkService.identity().can(com.sexidium.core.network.NodeCapability.EXPERIENCES)) {
      return;
    }
    if (sharedStorageMarkerPresent()) {
      return;
    }
    throw new IllegalStateException(
        "network.shared-world-storage is on, but this node's experiences folder ("
            + dependencies.serverAdapter().worlds().experiencesSubdir()
            + ") carries no .shared-storage marker. Every node must point at ONE shared tree"
            + " (docker/provision.sh and scripts/lib/paper.sh do this). Refusing to enable rather"
            + " than opening worlds a peer may also be writing.");
  }

  private boolean hasWorldFolder(String worldKey) {
    if (worldKey == null || worldKey.isBlank()) {
      return false;
    }
    try {
      java.nio.file.Path root = dependencies.serverAdapter().worlds().experiencesSubdir();
      if (root == null) {
        return false;
      }
      java.util.Optional<com.sexidium.core.world.WorldKey> key =
          com.sexidium.core.world.WorldKey.tryParse(worldKey);
      if (key.isEmpty()) {
        return false;
      }
      // World keys come out of the database, so they are as trusted as the database is. WorldKey
      // itself refuses a base carrying '/', ':' or '..', and confining the resolved path to the
      // experiences root costs one comparison and removes the remaining question.
      java.nio.file.Path candidate = key.get().folderIn(root).normalize();
      return candidate.startsWith(root.normalize()) && java.nio.file.Files.isDirectory(candidate);
    } catch (RuntimeException invalidPath) {
      return false;
    }
  }

  /**
   * Every experience world folder on this node's disk, as world keys.
   *
   * <p>A world is a folder containing {@code level.dat} — the same thing the server itself considers
   * a world — rather than "any directory", so a stray file or a half-copied folder is not adopted as
   * somebody's save.</p>
   */
  private com.sexidium.core.network.PlacementReconciler.DiskScan localExperienceWorlds() {
    java.nio.file.Path root = dependencies.serverAdapter().worlds().experiencesSubdir();
    if (root == null || !java.nio.file.Files.exists(root)) {
      // Nothing has created it yet. That is a real, readable answer -- a fresh server genuinely has
      // no experience worlds -- and must not be confused with the case below.
      return com.sexidium.core.network.PlacementReconciler.DiskScan.readable(java.util.Set.of());
    }
    if (!java.nio.file.Files.isDirectory(root)) {
      // It exists but is not a directory: on the network `root` is a symlink into the shared tree,
      // so this is a broken link or an unmounted volume, NOT an empty park.
      dependencies.serverAdapter().logger().warning(
          "The experiences path '" + root + "' exists but is not a directory (a broken symlink or an"
              + " unmounted shared volume). Treating the disk as unreadable.");
      return com.sexidium.core.network.PlacementReconciler.DiskScan.unreadable();
    }
    java.util.Set<String> keys = new java.util.LinkedHashSet<>();
    // Marked by `region/`, NOT by level.dat. A keyed dimension folder
    // (world/dimensions/<ns>/<key>) has no level.dat at all -- it holds region/, data/, poi/ and
    // entities/, and the level metadata belongs to the parent world. Filtering on level.dat therefore
    // reported an EMPTY disk on Paper, which is not a missed optimisation: the reconciler adopted
    // nothing, and read every placement row pointing here as a world folder that had vanished. That is
    // the "no world folder is on this disk" line that kept appearing about worlds sitting right there.
    // FOLLOW_LINKS, and it is not optional: in the network layout `root` IS a symlink into the shared
    // tree, and Files.walk does not descend a symlinked root -- it returns the link and stops. Measured:
    // 1 path without the flag, 3 with it, on the same tree. Together with the level.dat filter below
    // this made every node discover ZERO local worlds, so the reconciler was a permanent no-op while
    // reporting live worlds as missing.
    try (var paths = java.nio.file.Files.walk(root, 4, java.nio.file.FileVisitOption.FOLLOW_LINKS)) {
      paths.filter(path -> path.getFileName() != null
              && "region".equals(path.getFileName().toString())
              && java.nio.file.Files.isDirectory(path))
          .map(java.nio.file.Path::getParent)
          .filter(java.util.Objects::nonNull)
          .map(folder -> root.relativize(folder).toString().replace(java.io.File.separatorChar, '/'))
          .filter(key -> !key.isBlank())
          // A linked Nether/End is not a placeable world of its own: it is opened by, and travels
          // with, its overworld. Giving it a placement row of its own would invite the planner to home
          // a dimension on a different node from the experience it belongs to.
          .filter(key -> !key.endsWith(com.sexidium.core.world.WorldNaming.NETHER_SUFFIX)
              && !key.endsWith(com.sexidium.core.world.WorldNaming.END_SUFFIX))
          .forEach(keys::add);
    } catch (java.io.IOException | RuntimeException unreadable) {
      // RuntimeException too, and that is not defensive padding: a failure DURING the walk surfaces as
      // UncheckedIOException, not IOException -- an unreadable directory in the shared tree (another
      // node's uid) or a symlink loop both arrive that way. Catching only IOException let it escape
      // through reconcile() into SexidiumCore.start(), which the platform does not guard, so the whole
      // plugin failed to enable after the database and the network were already up. Reconciling
      // nothing is a bad day; not loading is a dead server.
      dependencies.serverAdapter().logger().warning(
          "Could not scan the experiences folder for reconciliation: " + unreadable);
      // NOT an empty set. Answering "no worlds here" to a scan that failed is what let the
      // reconciler read every row homed on this node as a folder that had vanished.
      return com.sexidium.core.network.PlacementReconciler.DiskScan.unreadable();
    }
    return com.sexidium.core.network.PlacementReconciler.DiskScan.readable(keys);
  }

  /**
   * Bring the three sources of truth into agreement at boot, before anyone can connect.
   *
   * <p>Deliberately at boot and not on demand: the repair for an unregistered world is to claim it
   * for this node, and that has to happen before a player asks for it — once the request is in
   * flight, the planner has already sent them somewhere else.</p>
   */
  private void reconcilePlacements() {
    com.sexidium.core.network.PlacementReconciler reconciler = networkService.reconciler(
        this::localExperienceWorlds,
        worldKey -> experienceManager != null
            && com.sexidium.core.world.WorldKey.tryParse(worldKey)
                .map(experienceManager::byWorld).orElse(null) != null);
    if (reconciler != null) {
      reconciler.reconcile();
    }
  }

  /**
   * Send the players waiting on a world to the node that owns it.
   *
   * <p>Three things happen per player, and leaving any of them out produces a bug that was live on
   * the network: the owning node is checked for life (or the player is told a truth instead of being
   * dropped into a failed connection), the destination is told what they are coming for (or they
   * arrive on a worker with no lobby and have to click the same experience again), and only then is
   * the route recorded for the proxy to act on.</p>
   */
  private void routeToOwningNode(
      String worldKey, String ownerNodeId, java.util.Collection<? extends com.sexidium.core.platform.PlayerAdapter> viewers) {
    com.sexidium.core.network.transfer.TransferDispatcher transfers = networkService.transfers();
    if (transfers == null || viewers == null || viewers.isEmpty()) {
      return;
    }
    if (ownerNodeId != null && ownerNodeId.equals(networkService.identity().nodeId())) {
      // Defence in depth for the re-entrant-claim bug (see DbWorldLeaseAuthority.claim): a ticket
      // addressed to the node the player is standing on is not a transfer, it is a silent no-op --
      // the proxy completes it LANDED and the intent is consumed with nothing opened. If we ever get
      // here again, say so loudly rather than losing the player's click.
      dependencies.serverAdapter().logger().severe(
          "Refusing to route " + viewers.size() + " player(s) to '" + ownerNodeId + "' for '" + worldKey
              + "': that is THIS node. Something decided a world we hold is owned elsewhere-but-here;"
              + " the players were left where they are rather than sent on a round trip to nowhere.");
      return;
    }
    boolean reachable = networkService.planner().isAlive(ownerNodeId);
    // The REAL epoch of the node that holds the claim, read from the placement row. It used to be a
    // hard-coded 0L, so the one fencing column the schema documents was dead on this path and a
    // ticket addressed to a worker that had since restarted was consumed by its successor.
    long ownerEpoch = ownerEpochFor(worldKey, ownerNodeId);
    for (com.sexidium.core.platform.PlayerAdapter viewer : viewers) {
      if (!reachable) {
        // Its folders are on that machine and come back with it. Saying so beats a failed connect,
        // and beats regenerating the world here, which is the only other thing we could do.
        dependencies.serverAdapter().messages().send(viewer, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_NODE_OFFLINE,
            com.sexidium.core.i18n.MessageArg.text("node", ownerNodeId)));
        continue;
      }
      // ONE call, one row, one token. Two uncorrelated rows with two TTLs and no addressing is what
      // the whole of finding F1 was made of.
      java.util.Optional<com.sexidium.core.network.transfer.TransferTicket> ticket =
          transfers.request(viewer.uniqueId(), ownerNodeId, ownerEpoch,
              com.sexidium.core.network.transfer.TransferReason.EXPERIENCE, worldKey);
      if (ticket.isEmpty()) {
        // Refused, which today means the loop breaker tripped. The player STAYS, and is told, rather
        // than being bounced a ninth time by a system that cannot see it is looping.
        dependencies.serverAdapter().logger().warning("Refusing to transfer " + viewer.name()
            + " to '" + ownerNodeId + "' for '" + worldKey + "': the transfer breaker refused it.");
        // NOT "that server is offline": liveness was checked five lines up and the node is UP. Saying
        // otherwise sent players to check a server that was running and hosting their world.
        dependencies.serverAdapter().messages().send(viewer, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_TRANSFER_REFUSED));
        continue;
      }
      dependencies.serverAdapter().logger().info("Routing " + viewer.name() + " to '" + ownerNodeId
          + "' epoch " + ownerEpoch + " for experience '" + worldKey + "' (token "
          + ticket.get().token() + ", attempt " + ticket.get().attempts() + ").");
      dependencies.serverAdapter().messages().send(viewer, com.sexidium.core.i18n.LocalizedText.of(
          com.sexidium.core.i18n.MessageKey.NETWORK_TRANSFER,
          com.sexidium.core.i18n.MessageArg.text("node", ownerNodeId)));
    }
  }

  /**
   * The epoch a transfer ticket should be addressed to, closing the {@code epoch 0} fencing gap.
   *
   * <p>Verified live: {@code routeToOwningNode} logged {@code epoch 0}. The root cause is one branch
   * in {@code DbWorldLeaseAuthority.claimOn} — a world PLANNED onto a peer records
   * {@code node_epoch = 0}, because the planning node is recording a decision and has no business
   * stamping another node's epoch. That is correct as a write. It is the READ that was wrong: it took
   * the row's 0 at face value, and {@code TransferTicket.addressedTo} treats 0 as "any node, any
   * boot" (`:44-51`), so the ticket still landed but the fence was inert for that world — a worker
   * that restarted between the plan and the arrival consumed a ticket minted for its predecessor.</p>
   *
   * <p>So: a row epoch of 0 means "unknown", and the owner's LIVE epoch from the registry is the
   * answer. Falls back to 0 when the node is not registered, which is the old behaviour and still
   * routes — an unfenced transfer beats no transfer.</p>
   */
  private long ownerEpochFor(String worldKey, String ownerNodeId) {
    long recorded = networkService.placements() == null ? 0L
        : networkService.placements().lookup(worldKey)
            .map(com.sexidium.core.network.DbWorldLeaseAuthority.Placement::nodeEpoch).orElse(0L);
    // The rule itself lives on the planner, which is the one thing that already reads the registry —
    // and being pure over it, it is testable without standing up a whole core.
    return networkService.planner().ownerEpoch(recorded, ownerNodeId);
  }

  /**
   * Open the experience a transferred player was sent here for.
   *
   * <p>A null {@code worldKey} means the arrival gate had no destination to hand over — no session, no
   * handoff — and is asking this node, which is the only thing that knows what kind of node it is, to
   * work out where the player belongs. See {@link #rememberedExperienceWorld}.</p>
   *
   * <p>The failure branch matters as much as the success one: this node is a worker, which has no
   * lobby world, no NPCs and no menu. A player left standing here because their experience could not
   * be opened has no way back at all, so they go straight back to the lobby node instead.</p>
   */
  private void resumeTransferredExperience(
      com.sexidium.core.platform.PlayerAdapter player, com.sexidium.core.world.WorldKey worldKey) {
    // A player who arrived while this node is draining must not have a world opened for them here:
    // it would be opened on a process that is about to stop, and the drain would immediately have to
    // close it again with the player inside. One extra hop for an unlucky player, never a disconnect.
    //
    // BackendDirectory deliberately keeps a DRAINING node registered so in-flight players are not
    // dropped -- that is why this guard has to exist on the arrival path and not only on the proxy.
    if (networkService.draining()) {
      dependencies.serverAdapter().logger().info("Player " + (player == null ? "?" : player.name())
          + " arrived while this node is draining; sending them straight on to the lobby.");
      routeToLobbyNode(player);
      return;
    }
    if (worldKey == null) {
      worldKey = rememberedExperienceWorld(player);
      if (worldKey == null) {
        return;
      }
    }
    com.sexidium.core.game.experience.ExperienceService.EnterOutcome outcome =
        experienceService.enterByWorld(player, worldKey);
    // The mapping matters more than the call. This used to be "anything that is not ENTERED or
    // STARTED means send them back", which closed the live loop: from the second pass onwards the
    // reachable outcome is ALREADY_IN_MATCH, because handleQuit keeps a player in the match index for
    // reconnect.timeout-seconds (120 by default) after they disconnect. So the arrival path itself
    // guaranteed a bounce, for two minutes, every time.
    switch (outcome) {
      case ENTERED, STARTED -> {
        // Logged on SUCCESS too. The destination said nothing at all on the happy path, which is
        // precisely why a player ping-ponging between the lobby and worker-1 eight times in 45
        // seconds was invisible in the worker log: every line an operator could see was on the
        // other node.
        dependencies.serverAdapter().logger().info("Transferred player " + player.name()
            + " entered '" + worldKey + "' (" + outcome + ").");
      }
      case ALREADY_IN_MATCH -> {
        // DO NOTHING. They are already where they were sent; the session simply has not been torn
        // down yet. Routing them anywhere from here is the bounce.
        dependencies.serverAdapter().logger().info("Transferred player " + player.name()
            + " is already in a match for '" + worldKey + "'; leaving them where they are.");
      }
      case HOST_OFFLINE, REQUEST_SENT, NOT_FOUND -> {
        // Real, explicable answers. Tell the player something they can act on, THEN move them back —
        // a silent bounce is what made this loop invisible from inside the game.
        dependencies.serverAdapter().logger().info("Transferred player " + player.name()
            + " may not enter '" + worldKey + "' (" + outcome + "); returning them to the lobby.");
        // Also not an offline node -- and the old call put a WORLD KEY in the <node> slot, so the
        // player read "the server that holds that world (experiences/foo_ab12) is offline".
        dependencies.serverAdapter().messages().send(player, com.sexidium.core.i18n.LocalizedText.of(
            com.sexidium.core.i18n.MessageKey.NETWORK_TRANSFER_REFUSED));
        routeToLobbyNode(player);
      }
      default -> {
        dependencies.serverAdapter().logger().severe("Transferred player " + player.name()
            + " could not enter '" + worldKey + "' (" + outcome + "); sending them back to the lobby.");
        routeToLobbyNode(player);
      }
    }
  }

  /**
   * The experience world a player was last in, from the durable pointer — the answer for someone who
   * arrives outside the seconds-long window in which a handoff row exists (a plain reconnect, a node
   * that restarted under them, a reset that renamed their world while they were offline).
   *
   * <p>Only on a node WITHOUT a lobby. Where there is a lobby the lobby is the right place to land: the
   * player gets the menu, the NPCs and the choice of going back in or doing something else, and pulling
   * them into their last world instead would take that choice away on every single login. A worker has
   * none of that, which is why it is the node that has to act on the pointer. Standalone holds every
   * capability, so a single server is untouched — as is a deployment with no registry at all.</p>
   */
  private com.sexidium.core.world.WorldKey rememberedExperienceWorld(
      com.sexidium.core.platform.PlayerAdapter player) {
    if (player == null || experienceManager == null || !experienceManager.available()
        || networkService.identity().can(com.sexidium.core.network.NodeCapability.LOBBY)) {
      return null;
    }
    return experienceManager.rememberedWorldOf(player.uniqueId());
  }

  /**
   * Send a player back to the lobby node. True when the transfer was requested, which tells the
   * caller not to teleport: on a worker there is nowhere to teleport them to.
   */
  private boolean routeToLobbyNode(com.sexidium.core.platform.PlayerAdapter player) {
    com.sexidium.core.network.transfer.TransferDispatcher transfers = networkService.transfers();
    if (transfers == null || player == null || !player.online()) {
      return false;
    }
    java.util.Optional<String> lobby =
        networkService.planner().choose(com.sexidium.core.network.NodeCapability.LOBBY);
    if (lobby.isEmpty() || lobby.get().equals(networkService.identity().nodeId())) {
      // No lobby node is up. Keeping the player where they are beats routing them into nothing.
      return false;
    }
    // Through the breaker like every other transfer: the lobby half of a ping-pong is still half of
    // a ping-pong, and bounding only the outbound leg would leave the loop running at half speed.
    //
    // Addressed to the lobby's LIVE epoch, not to a hard-coded 0 -- which TransferTicket.addressedTo
    // reads as "any node, any boot". This is the path EVERY drained player takes, so a lobby that
    // restarts inside a ticket's TTL would have its successor consume tickets minted for its
    // predecessor. Same rule, same call, as routeToOwningNode: the planner reads the registry.
    return transfers.request(player.uniqueId(), lobby.get(),
        networkService.planner().ownerEpoch(0L, lobby.get()),
        com.sexidium.core.network.transfer.TransferReason.LOBBY, null).isPresent();
  }

  private void subscribeToNetworkEvents() {
    // A friend added on another node must refresh this node's view for whichever of the two
    // players is here. Without it a friend list stays stale until the player reconnects.
    networkService.bus().subscribe(
        com.sexidium.core.network.NetworkBus.Topics.FRIEND_CHANGED,
        (topic, key, payload, originNode) -> onPlayerDataChanged(key));

    // A Discord Approve/Deny is decided on the bot's node; the frozen player is on whichever node
    // holds their connection. Keyed by identity, and a no-op on every node that is not holding them.
    networkService.bus().subscribe(
        com.sexidium.core.network.NetworkBus.Topics.AUTH_DECIDED,
        (topic, key, payload, originNode) -> releaseHold(key, "approved".equals(payload)));

    // NOTE: experience metadata has no CACHE to invalidate -- ExperienceManager holds none, every
    // read is a direct query against the shared `experiences` table -- but it does have two
    // consumers that read the row once and then stop looking: a live match (composed at start) and
    // an open menu (drawn at open). EXPERIENCE_UPDATED is subscribed for them, next to the rest of
    // the experience wiring in start(), where the service that owns both of them is.

    // A peer changed a balance. Nothing is recomputed here: the row is the truth, so this only drops
    // the cached copy and lets the next read re-fetch it.
    networkService.bus().subscribe(
        com.sexidium.core.network.NetworkBus.Topics.ECONOMY_CHANGED,
        (topic, key, payload, originNode) -> {
          if (economyService == null || key == null) {
            return;
          }
          try {
            economyService.invalidate(java.util.UUID.fromString(key));
          } catch (IllegalArgumentException notAUuid) {
            // A peer on a future protocol keying this topic by something else. Ignored rather than
            // logged: an unparsable key from a newer build is a forward-compat event, not a fault.
          }
        });

    networkService.bus().subscribe(
        com.sexidium.core.network.NetworkBus.Topics.RANK_CHANGED,
        (topic, key, payload, originNode) -> {
          if (key == null) {
            return;
          }
          java.util.UUID playerId;
          try {
            playerId = java.util.UUID.fromString(key);
          } catch (IllegalArgumentException notAUuid) {
            return;
          }
          // Only act if that player is on THIS node; otherwise the node hosting them will.
          dependencies.serverAdapter().player(playerId).ifPresent(player -> {
            // Back onto the main thread: applying a scoreboard team touches server state.
            dependencies.serverAdapter().scheduler().runNow(() -> rankTagService.apply(player));
          });
        });
  }

  private void stopIf(String id, Runnable stop) {
    if (startedSubsystems.remove(id)) {
      stop.run();
    }
  }

  /** Re-apply anything derived from shared player data, for a player hosted on THIS node. */
  private void onPlayerDataChanged(String playerUuid) {
    if (playerUuid == null) {
      return;
    }
    java.util.UUID playerId;
    try {
      playerId = java.util.UUID.fromString(playerUuid);
    } catch (IllegalArgumentException notAUuid) {
      return;
    }
    dependencies.serverAdapter().player(playerId).ifPresent(player ->
        dependencies.serverAdapter().scheduler().runNow(() -> rankTagService.apply(player)));
  }

  /**
   * Poll {@code auth_requests} for pending pushes and hand each to the bot.
   *
   * <p>Ticks are ticks, not milliseconds, so the configured poll is rounded up to whole ticks with a
   * floor of one — a sub-tick poll is not a thing a server can do, and asking for one should not
   * produce a busy loop.</p>
   */
  private void startAuthCourier() {
    if (authSessionService == null) {
      return;
    }
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    long pollMillis = Math.max(250L,
        serverAdapter.configuration().getLong("auth.approval.courier-poll-millis", 750L));
    long periodTicks = Math.max(1L, pollMillis / 50L);
    this.authRequestCourier = new com.sexidium.core.auth.AuthRequestCourier(
        authSessionService.requests(),
        serverAdapter.logger(),
        networkService.identity().nodeId(),
        row -> bridgeClient.emitAuthRequest(row.requestId(), row.identityId(), row.displayName(),
            row.discordUserId(), row.ipPrefix(), row.kind(), row.firstSeen(),
            row.createdAt(), row.expiresAt()),
        // A few polls wide: long enough that a slow DM is not re-sent underneath itself, short
        // enough that a node which dies mid-notification frees the row fast.
        pollMillis * 5L);
    this.authCourierTask = serverAdapter.scheduler().runTimer(
        authRequestCourier::tick, periodTicks, periodTicks);
  }

  private void startAuthSweeper() {
    if (authSessionService == null) {
      return;
    }
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    long periodTicks = Math.max(20L,
        serverAdapter.configuration().getLong("auth.approval.sweep-interval-seconds", 60L) * 20L);
    com.sexidium.core.auth.AuthSessionSweeper sweeper = new com.sexidium.core.auth.AuthSessionSweeper(
        authSessionService.sessions(), authSessionService.requests(), authSessionService.blocks(),
        serverAdapter.logger());
    this.authSweepTask = serverAdapter.scheduler().runTimer(sweeper::tick, periodTicks, periodTicks);
  }

  private void startWorldGarbageCollector() {
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    if (!serverAdapter.worlds().enabled()
        || !serverAdapter.configuration().getBoolean("worlds.temp.gc.enabled", true)) {
      return;
    }
    long initialDelayTicks = Math.max(1L,
        serverAdapter.configuration().getLong("worlds.temp.gc.initial-delay-seconds", 300L)) * 20L;
    long periodTicks = Math.max(30L,
        serverAdapter.configuration().getLong("worlds.temp.gc.period-seconds", 300L)) * 20L;
    worldGcTask = serverAdapter.scheduler().runTimer(this::collectWorldGarbage, initialDelayTicks, periodTicks);
  }

  private void startStackMerge() {
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    if (!serverAdapter.configuration().getBoolean("experiences.stack-merge.enabled", true)) {
      return;
    }
    long periodTicks = Math.max(5L,
        serverAdapter.configuration().getLong("experiences.stack-merge.period-ticks", 40L));
    stackMergeService.configure(readMergeAnimation(serverAdapter));
    stackMergeService.configure(readMergePacing(serverAdapter, periodTicks));
    stackMergeService.configure(readMergeWatch(serverAdapter));
    stackMergeService.logger(serverAdapter.logger());
    // DISCOVERY stays on the slow fixed beat: it scans every player's surroundings, which is the expensive
    // part, and a new pile elsewhere can afford to be noticed a couple of seconds late.
    stackMergeTask = serverAdapter.scheduler().runTimer(this::runStackMerge, periodTicks, periodTicks);
    // The MERGE pass and the flight animation are driven from one every-tick pump instead: the pump paces
    // the passes itself, keeping the `period-ticks` baseline while things are calm and accelerating as the
    // measured item pressure rises. It early-returns while nothing is active, which is the normal case.
    stackMergePumpTask = serverAdapter.scheduler().runTimer(this::runStackMergePump, 1L, 1L);
  }

  /** Reads the dynamic pass pacing (see {@code StackMergeService.MergePacing}); baseline = period-ticks. */
  private static com.sexidium.core.game.experience.compose.StackMergeService.MergePacing readMergePacing(
      ServerAdapter serverAdapter, long baselineTicks) {
    var defaults = com.sexidium.core.game.experience.compose.StackMergeService.MergePacing.DEFAULT;
    var configuration = serverAdapter.configuration();
    return new com.sexidium.core.game.experience.compose.StackMergeService.MergePacing(
        baselineTicks,
        configuration.getLong("experiences.stack-merge.pacing.min-period-ticks", defaults.minTicks()),
        configuration.getInt("experiences.stack-merge.pacing.comfortable-items", defaults.comfortableItems()),
        configuration.getInt("experiences.stack-merge.pacing.emergency-items", defaults.emergencyItems()));
  }

  /** Reads the watch / stuck-detection tuning (see {@code StackMergeService.MergeWatch}). */
  private static com.sexidium.core.game.experience.compose.StackMergeService.MergeWatch readMergeWatch(
      ServerAdapter serverAdapter) {
    var defaults = com.sexidium.core.game.experience.compose.StackMergeService.MergeWatch.DEFAULT;
    var configuration = serverAdapter.configuration();
    return new com.sexidium.core.game.experience.compose.StackMergeService.MergeWatch(
        Math.max(1L, configuration.getLong("experiences.stack-merge.revalidate.watch-seconds", 60L)) * 20L,
        configuration.getInt("experiences.stack-merge.revalidate.min-mergeable", defaults.minMergeable()),
        configuration.getInt("experiences.stack-merge.revalidate.stalled-passes", defaults.stalledPasses()),
        (int) Math.max(2L, configuration.getLong("experiences.stack-merge.max-ground-items", 256L)));
  }

  private void runStackMergePump() {
    try {
      stackMergeService.pump();
    } catch (RuntimeException exception) {
      dependencies.serverAdapter().logger().warning("Stack merge pump failed", exception);
    }
  }

  /** Reads the merge-animation tuning (see {@code StackMergeService.MergeAnimation}). */
  private static com.sexidium.core.game.experience.compose.StackMergeService.MergeAnimation readMergeAnimation(
      ServerAdapter serverAdapter) {
    var defaults = com.sexidium.core.game.experience.compose.StackMergeService.MergeAnimation.DEFAULT;
    var configuration = serverAdapter.configuration();
    return new com.sexidium.core.game.experience.compose.StackMergeService.MergeAnimation(
        configuration.getBoolean("experiences.stack-merge.animate.enabled", defaults.enabled()),
        configuration.getInt("experiences.stack-merge.animate.max-entities", defaults.maxConcurrent()),
        configuration.getDouble("experiences.stack-merge.animate.cluster-cell", defaults.cellSize()),
        configuration.getDouble("experiences.stack-merge.animate.speed", defaults.speed()),
        configuration.getDouble("experiences.stack-merge.animate.arrive-distance", defaults.arriveDistance()),
        configuration.getInt("experiences.stack-merge.animate.max-travel-ticks", defaults.maxTravelTicks()),
        configuration.getInt("experiences.stack-merge.animate.instant-above-items", defaults.floodThreshold()));
  }



  /**
   * The steady re-validation beat: look at what is on the ground around every player and wake the merger
   * wherever there is work. Running this on a fixed interval — rather than only when a pile crosses the
   * flood ceiling — is what stops consolidation from ever silently giving up on an area. The merging
   * itself is pumped every tick.
   */
  private void runStackMerge() {
    try {
      revalidateNearPlayers();
    } catch (RuntimeException exception) {
      dependencies.serverAdapter().logger().warning("Stack merge tick failed", exception);
    }
  }

  /**
   * Safety valve: the extreme over-cap merge normally runs only in chunks a Break-One-Break-All / Chunk
   * Break sweep activated. This additionally activates ANY chunk whose dropped-item count near a player
   * has hit the configured ceiling, so a runaway item flood (a mob farm, a dropped pile) gets collapsed
   * to protect the server even outside those challenge modes. Below the ceiling, standard modes are left
   * completely untouched.
   */
  private void revalidateNearPlayers() {
    ServerAdapter serverAdapter = dependencies.serverAdapter();
    // The area each player can actually see, from the shared PlayerRadius rules, optionally capped so a
    // very long render distance does not turn the validation sweep into a whole-world scan.
    double cap = Math.max(4.0,
        serverAdapter.configuration().getDouble("experiences.stack-merge.revalidate.max-radius", 48.0));
    for (com.sexidium.core.platform.PlayerAdapter player : serverAdapter.onlinePlayers()) {
      com.sexidium.core.platform.WorldAdapter world = player.world();
      com.sexidium.core.platform.model.WorldPosition position = player.position();
      double radius = Math.min(cap,
          com.sexidium.core.world.PlayerRadius.blockRadius(player, 1, Integer.MAX_VALUE));
      if (world != null && position != null) {
        // Inside a match/experience, bundling is the point: wake on any mergeable work rather than
        // waiting for a flood, so a handful of logs consolidates exactly like a shower of apples.
        stackMergeService.validateNear(world, position, radius, gameManager.matchOf(player) != null);
      }
    }
  }

  private void stopWorldGarbageCollector() {
    if (worldGcTask != null) {
      worldGcTask.cancel();
      worldGcTask = null;
    }
  }

  private void collectWorldGarbage() {
    try {
      dependencies.serverAdapter().worlds().collectGarbage(gameManager.protectedWorldNames());
    } catch (RuntimeException exception) {
      dependencies.serverAdapter().logger().warning("World GC failed", exception);
    }
  }
}
