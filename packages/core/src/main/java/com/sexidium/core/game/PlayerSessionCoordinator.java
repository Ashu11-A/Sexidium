package com.sexidium.core.game;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldNaming;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns per-player session transitions inside a running match: quit/reconnect window, rejoin, respawn,
 * world-change eviction, removal/release-to-lobby, and admitting a player into a specific match
 * (enter / join-in-progress). Mutates the shared {@link MatchState} and delegates teardown to
 * {@link MatchLifecycle} and pending rehydration/discard to {@link PendingMatchStore}.
 */
final class PlayerSessionCoordinator {
  private final GameContext gameContext;
  private final MatchState state;
  private final MatchLifecycle lifecycle;
  private final PendingMatchStore pendingStore;

  PlayerSessionCoordinator(
      GameContext gameContext,
      MatchState state,
      MatchLifecycle lifecycle,
      PendingMatchStore pendingStore
  ) {
    this.gameContext = gameContext;
    this.state = state;
    this.lifecycle = lifecycle;
    this.pendingStore = pendingStore;
  }

  GameManager.JoinResult enterMatch(PlayerAdapter playerAdapter, ActiveMatch activeMatch) {
    if (playerAdapter == null || !playerAdapter.online()) {
      return GameManager.JoinResult.PLAYER_OFFLINE;
    }
    if (activeMatch == null || !state.matches.containsKey(activeMatch.id())) {
      return GameManager.JoinResult.NOT_RUNNING;
    }
    if (state.matchOf(playerAdapter.uniqueId()) != null) {
      return GameManager.JoinResult.ALREADY_IN_MATCH;
    }
    return admit(playerAdapter, activeMatch);
  }

  void handleQuit(PlayerAdapter playerAdapter) {
    ActiveMatch activeMatch = state.matchOf(playerAdapter.uniqueId());
    if (activeMatch == null) {
      return;
    }
    Game game = activeMatch.game();
    // Drop the quitting player from this match's boss bars / HUD so a stale overlay is not re-shown if
    // they rejoin, and the server-side viewer sets do not retain the disconnected player.
    game.releasePlayerUi(playerAdapter);
    // BOTH marks go in before the isReconnectable() branch, so a mode that does not hold slots is still
    // covered. `markDisconnected` had been dead on this path since it was written -- only the restart
    // rehydration in AbstractGame.restore ever called it -- which left isDisconnected(...) answering
    // false for every player who dropped in a live session, while online() dropped them the same tick.
    if (game instanceof AbstractGame abstractGame) {
      abstractGame.markDisconnected(playerAdapter.uniqueId());
    }
    // Nobody is driving this character any more. Held until they rejoin or their slot is released, so a
    // death landing in the gap cannot be blamed on a player who was not there for it.
    gameContext.playerControl().markDisconnected(playerAdapter.uniqueId());
    if (!game.isReconnectable()) {
      state.playerIndex.remove(playerAdapter.uniqueId());
      lifecycle.checkEmpty(activeMatch);
      return;
    }
    UUID playerId = playerAdapter.uniqueId();
    game.onParticipantDisconnect(playerAdapter);
    lifecycle.persist(activeMatch);
    long windowTicks = Math.max(1L, gameContext.server().configuration().getLong("reconnect.timeout-seconds", 120L)) * 20L;
    gameContext.server().scheduler().runLater(() -> reconnectTimeout(activeMatch.id(), playerId), windowTicks);
  }

  /**
   * A cross-node arrival gate. Null standalone, where every participant was already in this JVM.
   *
   * <p>Set by SexidiumCore when networked. On arrival a player may belong to a match this node is
   * still ASSEMBLING — reserved before they were transferred — which the local match maps cannot
   * know about, because the match had no players when it was created.</p>
   */
  private volatile com.sexidium.core.network.MatchHandoffService handoffs;

  public void setHandoffs(com.sexidium.core.network.MatchHandoffService handoffs) {
    this.handoffs = handoffs;
  }

  boolean handleJoin(PlayerAdapter playerAdapter) {
    UUID playerId = playerAdapter.uniqueId();
    ActiveMatch activeMatch = state.matchOf(playerId);
    if (activeMatch == null && state.pendingIndex.containsKey(playerId)) {
      activeMatch = pendingStore.rehydrate(state.pendingIndex.get(playerId));
    }
    // Transferred in for a match being assembled here: record the arrival, then fall through to the
    // same admission path a reconnect uses. Deliberately AFTER the local lookups, so nothing about
    // an ordinary same-node join changes.
    if (activeMatch == null) {
      Arrival arrival = admitTransferredPlayer(playerId);
      activeMatch = arrival.match();
      if (activeMatch == null && !arrival.claimed()) {
        resumeRememberedExperience(playerAdapter);
      }
    }
    if (activeMatch == null) {
      return false;
    }
    WorldAdapter worldAdapter = activeMatch.world();
    if (worldAdapter != null && (playerAdapter.world() == null
        || !WorldNaming.sameWorld(worldAdapter.name(), playerAdapter.world().name()))) {
      // Bring a reconnecting player back into their match world via the game's VALIDATED entry position
      // (the experience returns the player's saved-and-world-checked spot, or a safe surface spawn) — never
      // the raw world spawn, which on a generated experience map can be inside a wall or under water.
      WorldPosition target = activeMatch.game().entrySpawn(playerAdapter, worldAdapter);
      if (target != null) {
        arrive(activeMatch, playerAdapter, worldAdapter, target);
      }
    }
    // They are back and driving again. Lifting it HERE rather than waiting for the idle sampler matters:
    // the sampler only ever looks at players who are online, so a mark left by a disconnect would
    // otherwise have nothing to clear it.
    gameContext.playerControl().clear(playerAdapter.uniqueId());
    activeMatch.game().onParticipantRejoin(playerAdapter);
    // LAST, exactly as on the other two ways in: a reconnect restores a snapshot carrying the game mode
    // the player had when they left, and a world that has been lost since must not hand it back.
    applyEntryPolicy(activeMatch, playerAdapter);
    lifecycle.persist(activeMatch);
    return true;
  }

  /**
   * Claim a player the network says was sent here.
   *
   * <p>ADDRESSED, and consumed atomically. What this replaces answered "is anybody expecting this
   * player anywhere?" — no {@code ORDER BY}, no {@code LIMIT 1}, no node filter, no epoch filter —
   * and took whichever row the database returned first. Live, one player had two rows in window: the
   * fresh experience rendezvous, and a UUID-keyed roster row from the previous launch that nothing
   * ever deleted. When the roster row won the coin flip the arrival was silently swallowed: nothing
   * opened a world, the real ticket was never consumed, and nothing was logged.</p>
   *
   * <p>Returns the match once it is live locally. While a roster is still arriving the match may not
   * exist yet, so a null match with {@code claimed=true} is a normal outcome, not a failure.</p>
   */
  private Arrival admitTransferredPlayer(UUID playerId) {
    // 1. The typed transfer ticket. One live ticket per player, addressed to (node, epoch).
    com.sexidium.core.network.transfer.ArrivalGate gate = arrivals;
    if (gate != null) {
      var ticket = gate.claimArrival(playerId, arrivalNodeId, arrivalNodeEpoch);
      if (ticket.isPresent()) {
        var transfer = ticket.get();
        gameContext.server().logger().info("Arrival claimed for " + playerId + ": token "
            + transfer.token() + ", reason " + transfer.reason() + ", world '"
            + transfer.worldKey() + "', from '" + transfer.sourceNode() + "'.");
        if (transfer.reason()
            == com.sexidium.core.network.transfer.TransferReason.EXPERIENCE) {
          // A typed field, not a "experience:" string prefix on a match id in a table shared with an
          // unrelated feature. The world load is asynchronous, so there is no match to return yet.
          var resume = transferResume;
          var destination = com.sexidium.core.world.WorldKey.tryParse(transfer.worldKey())
              .orElse(null);
          if (resume != null && destination != null) {
            gameContext.server().player(playerId)
                .ifPresent(player -> resume.accept(player, destination));
          }
          return new Arrival(null, true);
        }
        // LOBBY and MATCH both fall through to the roster below: a MATCH ticket says which node, the
        // handoff row says which match.
      }
    }

    // 2. The minigame roster, which is what match_handoffs is FOR and all it is for now.
    com.sexidium.core.network.MatchHandoffService service = handoffs;
    if (service == null) {
      return Arrival.NONE;
    }
    var expected = service.expectedMatchOf(playerId, arrivalNodeId, arrivalNodeEpoch);
    if (expected.isEmpty()) {
      return Arrival.NONE;
    }
    com.sexidium.core.network.MatchHandoffService.Handoff handoff = expected.get();
    service.markArrived(handoff.matchId(), playerId);

    ActiveMatch match = state.matchOf(playerId);
    if (match != null) {
      return new Arrival(match, true);
    }
    ActiveMatch matched;
    try {
      matched = state.matches.get(UUID.fromString(handoff.matchId()));
    } catch (IllegalArgumentException notAUuid) {
      matched = null;
    }
    if (matched == null) {
      gameContext.server().logger().info("Arrival claimed for " + playerId + " by roster handoff '"
          + handoff.matchId() + "' (mode '" + handoff.modeId() + "'), which is still assembling"
          + " here; they will be admitted when it starts.");
    }
    return new Arrival(matched, true);
  }

  /** The typed arrival gate, and this node's address on it. Null standalone. */
  private volatile com.sexidium.core.network.transfer.ArrivalGate arrivals;
  private volatile String arrivalNodeId = "standalone";
  private volatile long arrivalNodeEpoch;

  void setArrivalGate(
      com.sexidium.core.network.transfer.ArrivalGate arrivals, String nodeId, long nodeEpoch) {
    this.arrivals = arrivals;
    this.arrivalNodeId = nodeId == null ? "standalone" : nodeId;
    this.arrivalNodeEpoch = nodeEpoch;
  }

  /**
   * What the network had to say about an arriving player: the match to admit them to, if it is live here
   * yet, and whether a handoff CLAIMED them at all. The second answer is not implied by the first — a
   * handoff routinely resolves to no match (an experience world still opening, a roster still arriving) —
   * and telling those two nulls apart is what stops the fallback below from acting on a player the
   * transfer is already taking care of.
   */
  private record Arrival(ActiveMatch match, boolean claimed) {
    static final Arrival NONE = new Arrival(null, false);
  }

  /**
   * Last resort for a player nothing here knows: no local session, no pending match, no handoff.
   *
   * <p>On a network that is the ordinary shape of a return to an experience. The handoff row only exists
   * for the seconds around a transfer, while players come back hours later, after this node restarted, or
   * after a reset renamed their world while they were offline — and outside that window a worker had
   * nothing to go on and simply left them standing in its default overworld. The durable pointer written
   * on entry does, and the resume hook is asked for it with a null world key: "no destination was handed
   * to me, work out where this player belongs" (see {@code SexidiumCore#resumeTransferredExperience},
   * which is also where the decision NOT to do this on a node that has a lobby lives).</p>
   *
   * <p>The hook is null on a standalone server, so nothing here changes for one.</p>
   */
  private void resumeRememberedExperience(PlayerAdapter playerAdapter) {
    java.util.function.BiConsumer<PlayerAdapter, com.sexidium.core.world.WorldKey> resume =
        transferResume;
    if (resume != null) {
      resume.accept(playerAdapter, null);
    }
  }

  /**
   * Opens the experience a transferred player came for, given the world key they were sent for.
   * Null standalone, where nobody is ever transferred.
   */
  private volatile java.util.function.BiConsumer<PlayerAdapter, com.sexidium.core.world.WorldKey>
      transferResume;

  void setTransferResume(
      java.util.function.BiConsumer<PlayerAdapter, com.sexidium.core.world.WorldKey> transferResume) {
    this.transferResume = transferResume;
  }

  void removePlayer(UUID playerId, boolean voluntary) {
    // The slot is gone, so the mark has nothing left to describe. Forgetting rather than clearing leaves
    // no recovery tail behind either -- there is no player here to protect.
    gameContext.playerControl().forget(playerId);
    ActiveMatch activeMatch = state.matchOf(playerId);
    if (activeMatch == null) {
      UUID pendingMatchId = state.pendingIndex.remove(playerId);
      if (pendingMatchId != null) {
        pendingStore.discardPending(pendingMatchId, playerId);
      }
      return;
    }
    Game game = activeMatch.game();
    state.playerIndex.remove(playerId);
    game.onParticipantRemoved(playerId, voluntary);
    if (voluntary) {
      forgetRememberedExperience(playerId, activeMatch);
    }
    gameContext.server().player(playerId)
        .filter(PlayerAdapter::online)
        .ifPresent(player -> {
          // Hide this match's boss bars / HUD / countdown bars for the leaving player, then reset all
          // of their state and screen overlays before sending them back to the lobby.
          game.releasePlayerUi(player);
          releaseToLobby(player);
        });
    if (state.matches.containsKey(activeMatch.id()) && !lifecycle.checkEmpty(activeMatch)) {
      lifecycle.persist(activeMatch);
    }
  }

  /**
   * Drop the "this player is in that world" pointer when the player CHOSE to go — /leave, the menu's
   * exit, walking out of the world. Only then: an involuntary removal (the reconnect window expiring, a
   * shutdown, an eviction) has to KEEP the pointer, because as far as that player knows they are still
   * inside the experience and expect their next login to put them back in it. Dropping it there would
   * turn every timeout into a silent eviction to the lobby.
   *
   * <p>Deliberately after the game's own removal hook: that hook is what saves the player's state, and
   * saving writes this very pointer. Clearing it first would just be re-written a line later.</p>
   */
  private void forgetRememberedExperience(UUID playerId, ActiveMatch activeMatch) {
    // Only the persistent-world modes ever have a pointer. Checked before the lookup because resolving a
    // world to an experience costs a scan when it does not match, and a minigame world never will.
    if (!com.sexidium.core.game.experience.ExperienceGame.MODE_ID.equals(activeMatch.modeId())
        && !com.sexidium.core.game.chaos.ChaosGame.MODE_ID.equals(activeMatch.modeId())) {
      return;
    }
    com.sexidium.core.game.experience.ExperienceManager experiences = gameContext.experiences();
    WorldAdapter worldAdapter = activeMatch.world();
    if (experiences == null || !experiences.available() || worldAdapter == null) {
      return;
    }
    com.sexidium.core.game.experience.ExperienceManager.Experience experience =
        com.sexidium.core.world.WorldKey
            .fromRuntime(worldAdapter.name(), gameContext.server().worlds().experiencesSubdirName())
            .map(experiences::byWorld)
            .orElse(null);
    if (experience != null) {
      experiences.forgetPlayerWorld(experience.id(), playerId);
    }
  }

  void handleRespawn(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    ActiveMatch activeMatch = state.matchOf(playerAdapter.uniqueId());
    if (activeMatch == null) {
      return;
    }
    // Open-ended experiences keep the player in their world on death; the game handles the respawn
    // itself (via its PlayerRespawnGameEvent handler) instead of being released to the lobby.
    if (activeMatch.game().handlesOwnRespawn()) {
      return;
    }
    activeMatch.game().releasePlayerUi(playerAdapter);
    releaseToLobby(playerAdapter);
  }

  void handleChangedWorld(PlayerAdapter playerAdapter, String fromWorld, String toWorld) {
    if (playerAdapter == null) {
      return;
    }
    ActiveMatch activeMatch = state.matchOf(playerAdapter.uniqueId());
    if (activeMatch == null) {
      return;
    }
    WorldAdapter worldAdapter = activeMatch.world();
    String matchWorld = worldAdapter == null ? null : worldAdapter.name();
    if (matchWorld == null) {
      return;
    }
    // Compare with WorldNaming.sameWorld, not equals: the match world's canonical name ("<ns>/<key>")
    // and the player's live world label ("<ns>_<key>") are the same world in two forms — a raw equals
    // here would evict a player the instant they are teleported into their own freshly-leased match world.
    if ((toWorld == null || !WorldNaming.sameWorld(matchWorld, toWorld)) && !activeMatch.game().allowsWorldChange(playerAdapter, fromWorld, toWorld)) {
      // INVOLUNTARY. A player who ends up in a world their match does not allow did not CHOOSE to
      // leave -- they were teleported, or their world was swapped under them, or a transfer landed
      // them somewhere unexpected. Passing voluntary=true here also ran forgetRememberedExperience,
      // which deletes the durable experience_players pointer: the one piece of state that could have
      // put them back. That is how a bounce became unrecoverable rather than merely annoying.
      // /leave and the menu's exit stay voluntary; they call removePlayer directly.
      removePlayer(playerAdapter.uniqueId(), false);
    }
  }

  GameManager.JoinResult joinInProgress(PlayerAdapter playerAdapter, String modeId, Set<UUID> relatedPlayerIds) {
    if (playerAdapter == null || !playerAdapter.online()) {
      return GameManager.JoinResult.PLAYER_OFFLINE;
    }
    if (state.matchOf(playerAdapter.uniqueId()) != null) {
      return GameManager.JoinResult.ALREADY_IN_MATCH;
    }
    if (modeId == null) {
      return GameManager.JoinResult.NOT_RUNNING;
    }
    boolean modeRunning = false;
    ActiveMatch target = null;
    for (ActiveMatch activeMatch : state.matches.values()) {
      if (!modeId.equals(activeMatch.modeId())) {
        continue;
      }
      modeRunning = true;
      if (relatedPlayerIds == null || intersectsParticipants(activeMatch, relatedPlayerIds)) {
        target = activeMatch;
        break;
      }
    }
    if (target == null) {
      return modeRunning && relatedPlayerIds != null ? GameManager.JoinResult.NOT_RELATED : GameManager.JoinResult.NOT_RUNNING;
    }
    return admit(playerAdapter, target);
  }

  private boolean intersectsParticipants(ActiveMatch activeMatch, Set<UUID> relatedPlayerIds) {
    for (Map.Entry<UUID, UUID> entry : state.playerIndex.entrySet()) {
      if (entry.getValue().equals(activeMatch.id()) && relatedPlayerIds.contains(entry.getKey())) {
        return true;
      }
    }
    return false;
  }

  private GameManager.JoinResult admit(PlayerAdapter playerAdapter, ActiveMatch activeMatch) {
    UUID playerId = playerAdapter.uniqueId();
    playerAdapter.resetStatuses();
    state.playerIndex.put(playerId, activeMatch.id());
    WorldAdapter worldAdapter = activeMatch.world();
    if (worldAdapter != null) {
      // Entry teleport via the game's entry position (experience host returns the saved spot).
      WorldPosition target = activeMatch.game().entrySpawn(playerAdapter, worldAdapter);
      if (target != null) {
        arrive(activeMatch, playerAdapter, worldAdapter, target);
      }
    }
    try {
      activeMatch.game().onParticipantAdded(playerAdapter);
    } catch (Exception exception) {
      gameContext.server().logger().warning("Error while adding participant to running match", exception);
    }
    // LAST: the mode's declared arrival, applied after everything the game did while admitting them —
    // including restoring a saved snapshot, which carries the game mode they had when they left. Without
    // this being last, a player re-entering a world they permanently lost arrived in survival.
    applyEntryPolicy(activeMatch, playerAdapter);
    lifecycle.persist(activeMatch);
    return GameManager.JoinResult.JOINED;
  }

  /**
   * Applies the mode's {@link EntryPolicy} to an arriving player. Kept here, in the framework, rather
   * than in each game: enforcement that every mode has to remember is enforcement that some mode will
   * forget. Any failure is contained — a mode with a broken policy must not stop a player entering.
   */
  private void applyEntryPolicy(ActiveMatch activeMatch, PlayerAdapter playerAdapter) {
    try {
      EntryPolicy policy = activeMatch.game().entryPolicy(playerAdapter);
      if (policy == null) {
        return;
      }
      policy.applyTo(playerAdapter);
      if (policy.notice() != null) {
        gameContext.server().messages().send(playerAdapter, policy.notice());
      }
    } catch (RuntimeException exception) {
      gameContext.server().logger().warning("Entry policy failed for an arriving player", exception);
    }
  }

  /**
   * The half of the policy that has to happen BEFORE the entry teleport (the client's hardcore view),
   * mirroring {@code GameLauncher}. Contained the same way: a broken policy must not block entry.
   */
  /**
   * Moves a player into their match world as one atomic entry.
   *
   * <p>This used to be "prepare, then teleport" written as two statements. That is the bug behind
   * "entities are frozen and I cannot break anything, until I relog": preparing an arrival can tell the
   * client to throw away its loaded world, and the teleport that was supposed to hand it a new one
   * immediately is asynchronous. {@link EntryPolicy#arrive} warms the destination chunk first so the two
   * really are one step, and applies the policy only once the player is actually there.</p>
   */
  private void arrive(ActiveMatch activeMatch, PlayerAdapter playerAdapter, WorldAdapter worldAdapter,
      WorldPosition target) {
    try {
      EntryPolicy policy = activeMatch.game().entryPolicy(playerAdapter);
      if (policy == null) {
        playerAdapter.teleport(target);
        return;
      }
      policy.arrive(playerAdapter, worldAdapter, target, moved -> {
        if (!Boolean.TRUE.equals(moved)) {
          gameContext.server().logger().warning("Could not move " + playerAdapter.name()
              + " into their match world; they were left where they were.");
        }
      });
    } catch (RuntimeException exception) {
      gameContext.server().logger().warning("Entry preparation failed for an arriving player", exception);
    }
  }

  private void reconnectTimeout(UUID matchId, UUID playerId) {
    ActiveMatch activeMatch = state.matches.get(matchId);
    if (activeMatch == null) {
      return;
    }
    if (!matchId.equals(state.playerIndex.get(playerId))) {
      return;
    }
    if (gameContext.server().player(playerId).filter(PlayerAdapter::online).isPresent()) {
      return;
    }
    removePlayer(playerId, false);
  }

  private void releaseToLobby(PlayerAdapter playerAdapter) {
    playerAdapter.resetStatuses();
    // A node with no lobby world (every worker) returns players to the lobby by TRANSFERRING them.
    // Checked before the spawn lookup because that lookup is empty there by design, and its warning
    // would report a misconfiguration that is actually the intended topology.
    if (gameContext.routeToLobby(playerAdapter)) {
      return;
    }
    Optional<WorldPosition> lobbySpawn = gameContext.server().worlds().lobbySpawn();
    if (lobbySpawn.isEmpty()) {
      // No resolvable lobby spawn (lobby world unconfigured / not loaded): without this the player would be
      // left standing in a match world that is about to be discarded — appearing "sent" to a stale spot.
      // Surface the misconfiguration loudly rather than silently stranding them.
      gameContext.server().logger().warning("Cannot release player " + playerAdapter.name()
          + " to the lobby: no lobby spawn is configured or loaded.");
      return;
    }
    WorldPosition spawn = lobbySpawn.get();
    // The lobby is never hardcore. Told to the client BEFORE the teleport that takes it there, because a
    // client is only ever told what a world is when it is sent one — which is why hardcore hearts used to
    // follow a player out of a lost world and stay with them in the lobby.
    EntryPolicy.leaveHardcoreWorld(playerAdapter, spawn);
    playerAdapter.teleport(spawn);
    // Re-point the compass at the lobby after the teleport (resetStatuses resolved it against the match
    // world, which is about to be discarded).
    playerAdapter.setCompassTarget(spawn);
  }
}
