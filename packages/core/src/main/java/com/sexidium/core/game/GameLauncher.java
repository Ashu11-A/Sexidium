package com.sexidium.core.game;

import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceGame;
import com.sexidium.core.game.experience.ExperienceSetup;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldGeneration;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Owns the (possibly async) match-launch flow: validation, participant reservation against duplicate
 * starts, world acquisition (template clone / pooled ready / fresh / persistent experience), and the
 * final {@code launch} commit that creates the {@link ActiveMatch} and starts the game. Mutates the
 * shared {@link MatchState} and delegates teardown/persistence to {@link MatchLifecycle}.
 */
final class GameLauncher {
  private final GameContext gameContext;
  private final GameRegistry gameRegistry;
  private final MatchState state;
  private final MatchLifecycle lifecycle;

  GameLauncher(GameContext gameContext, GameRegistry gameRegistry, MatchState state, MatchLifecycle lifecycle) {
    this.gameContext = gameContext;
    this.gameRegistry = gameRegistry;
    this.state = state;
    this.lifecycle = lifecycle;
  }

  boolean start(
      String modeId,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    CommandSource audience = initiator == null ? gameContext.server().console() : initiator;
    Game game = create(modeId, modeArgs);
    if (game == null) {
      gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.GAME_UNKNOWN_MODE, MessageArg.text("mode", modeId)));
      return false;
    }
    List<PlayerAdapter> participantList = MatchState.sanitize(participants);
    if (participantList.size() < game.minPlayers()) {
      gameContext.server().messages().send(audience, LocalizedText.of(
          MessageKey.GAME_MIN_PLAYERS,
          MessageArg.text("game", game.displayName()),
          MessageArg.text("count", game.minPlayers())
      ));
      return false;
    }
    for (PlayerAdapter participant : participantList) {
      if (state.isReserved(participant.uniqueId())) {
        gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.GAME_ALREADY_RUNNING));
        return false;
      }
    }
    // Reserve every participant synchronously before the (possibly async) world creation, so a rapid
    // second start for the same player is rejected above instead of creating a duplicate match.
    for (PlayerAdapter participant : participantList) {
      state.reservingPlayers.add(participant.uniqueId());
    }

    try {
      state.starting = true;
      state.startingDisplayName = game.displayName();
      if (!gameContext.server().worlds().enabled()) {
        return launch(modeId, game, null, participantList, audience, modeArgs);
      }
      // A mode that wants a pre-built arena (TNT War, Combat) clones its template world; otherwise a pooled
      // ready world is used when available, and finally a fresh world is generated on demand.
      String template = game.worldTemplate();
      Runnable onWorldFailure = () -> {
        state.clearStarting();
        for (PlayerAdapter participant : participantList) {
          state.reservingPlayers.remove(participant.uniqueId());
        }
        reportWorldFailure(audience, participantList);
      };
      if (template != null && !template.isBlank()) {
        gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.TEMP_WORLD_QUEUED));
        gameContext.server().worlds().acquireOrCreateClone(
            template,
            participantList,
            worldLease -> launch(modeId, game, worldLease, participantList, audience, modeArgs),
            onWorldFailure
        );
        return true;
      }
      WorldLease readyLease = gameContext.server().worlds().acquireReady().orElse(null);
      if (readyLease != null) {
        return launch(modeId, game, readyLease, participantList, audience, modeArgs);
      }

      gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.TEMP_WORLD_QUEUED));
      gameContext.server().worlds().acquireOrCreate(
          participantList,
          worldLease -> launch(modeId, game, worldLease, participantList, audience, modeArgs),
          onWorldFailure
      );
      return true;
    } catch (RuntimeException failure) {
      // Mesma armadilha de startExperience, no irmão que serve minigames/quick-play: a
      // reserva é tomada acima e só os callbacks de mundo a devolviam. `onWorldFailure` já
      // é exatamente o resgate certo -- faltava alguém chamá-lo quando o que falha é uma
      // exceção em vez do mundo. Sem isto, um throw em worldTemplate()/acquireReady()
      // deixava o jogador impossibilitado de iniciar QUALQUER partida até o processo
      // reiniciar, e `state.starting` preso em true para todo mundo.
      state.clearStarting();
      for (PlayerAdapter participant : participantList) {
        state.reservingPlayers.remove(participant.uniqueId());
      }
      gameContext.server().logger().severe(
          "Could not start mode '" + modeId + "'; the reservation was released so the player can retry",
          failure);
      reportWorldFailure(audience, participantList);
      return false;
    }
  }

  boolean startExperience(
      com.sexidium.core.world.WorldKey worldKey,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> challengeIds
  ) {
    return startExperience(worldKey, ExperienceGame.MODE_ID, participants, initiator, challengeIds);
  }

  /**
   * Starts a persistent, never-GC'd experience-style world running an arbitrary {@code modeId} (the
   * composable {@link ExperienceGame} or the persistent Chaos mode). Both save/restore per-player
   * inventory + position via the same world-folder store, so this shared path is what makes a Chaos
   * world persist exactly like a hand-built experience.
   */
  boolean startExperience(
      com.sexidium.core.world.WorldKey worldKey,
      String modeId,
      Collection<? extends PlayerAdapter> participants,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    CommandSource audience = initiator == null ? gameContext.server().console() : initiator;
    Game game = create(modeId, modeArgs);
    if (game == null) {
      gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.GAME_UNKNOWN_MODE, MessageArg.text("mode", modeId)));
      return false;
    }
    // STRIPPED, não `modeArgs` cru. Os args de modo carregam os ids de desafio E os tokens de
    // opção (`world:`/`keepinv:`/`hardcore:`), e as três linhas abaixo os entregam a consultas
    // que agora EXIGEM ids conhecidos. Enquanto `create` só descartava o desconhecido em
    // silêncio isto passava; com `requireKnown` no lugar, um `world:normal` vira
    // UnknownChallengeException e a entrada na experiência morre inteira no clique.
    // ExperienceGame já fazia exatamente isto na sua própria construção -- aqui era a cópia
    // que faltava.
    List<String> challengeIds = List.copyOf(ExperienceSetup.stripArgs(modeArgs));
    List<PlayerAdapter> participantList = MatchState.sanitize(participants);
    if (participantList.isEmpty()) {
      gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.COMMAND_START_NO_PLAYERS));
      return false;
    }
    for (PlayerAdapter participant : participantList) {
      if (state.isReserved(participant.uniqueId())) {
        gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.GAME_ALREADY_RUNNING));
        return false;
      }
    }
    for (PlayerAdapter participant : participantList) {
      state.reservingPlayers.add(participant.uniqueId());
    }
    state.starting = true;
    state.startingDisplayName = game.displayName();
    try {
      return startReserved(worldKey, modeId, game, participantList, audience, modeArgs, challengeIds);
    } catch (RuntimeException failure) {
      // A reserva é tomada ACIMA e, até aqui, NADA a devolvia por exceção -- só os dois
      // callbacks de mundo, que uma exceção nunca alcança. O efeito é desproporcional ao
      // erro: o jogador fica reservado para sempre, e como `isReserved` é checado logo na
      // entrada, TODA tentativa seguinte devolve false e vira "Could not enter that
      // experience." Ou seja, uma falha de UMA entrada virava um jogador permanentemente
      // incapaz de entrar em qualquer coisa, até o processo reiniciar.
      //
      // Foi exatamente assim que um `UnknownChallengeException` numa lista de args virou um
      // incidente: o primeiro clique lançava em silêncio, e todos os cliques seguintes
      // batiam na reserva órfã.
      state.clearStarting();
      for (PlayerAdapter participant : participantList) {
        state.reservingPlayers.remove(participant.uniqueId());
      }
      gameContext.server().logger().severe(
          "Could not start mode '" + modeId + "' for " + participantList.size()
              + " player(s); the reservation was released so they can try again", failure);
      reportWorldFailure(audience, participantList);
      return false;
    }
  }

  /**
   * The half of {@link #startExperience} that runs with the players already reserved.
   *
   * <p>Split out for one reason: everything in here may throw, and the caller's {@code catch} is what
   * gives the reservation back. Inlining it would put the reservation and its release in the same
   * block and invite the next edit to slip between them again.</p>
   */
  private boolean startReserved(
      com.sexidium.core.world.WorldKey worldKey,
      String modeId,
      Game game,
      List<PlayerAdapter> participantList,
      CommandSource audience,
      List<String> modeArgs,
      List<String> challengeIds
  ) {
    if (!gameContext.server().worlds().enabled()) {
      return launch(modeId, game, null, participantList, audience, challengeIds);
    }
    gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.TEMP_WORLD_QUEUED));
    // The world's whole shape comes from the stored challenge ids plus the owner's options: a challenge
    // like Random Layers / Skyblock builds its own world so it must generate into empty void, Classic
    // Skyblock additionally wants a void Nether to mirror into, the map type decides the vanilla terrain
    // preset, and Death Resets forces hardcore on whatever the owner chose. Hardcore rides along with
    // generation because the client is told a world is hardcore when it is SENT the world — the flag has
    // to be on it before the entry teleport, not applied afterwards.
    ExperienceSetup options = ExperienceSetup.fromArgs(modeArgs).forChallenges(challengeIds);
    WorldGeneration generation = options.generationFor(challengeIds);
    gameContext.server().worlds().acquireOrCreatePersistent(
        worldKey,
        participantList,
        generation,
        worldLease -> launch(modeId, game, worldLease, participantList, audience, challengeIds),
        () -> {
          state.clearStarting();
          for (PlayerAdapter participant : participantList) {
            state.reservingPlayers.remove(participant.uniqueId());
          }
          // The gate refuses a world homed on another node, which lands here. Those players are
          // being transferred, not failed.
          reportWorldFailure(audience, participantList);
        }
    );
    return true;
  }

  /**
   * Cross-node roster reservation. Null standalone.
   *
   * <p>Written at match creation rather than at transfer time, because the row has to exist before
   * the player's connection reaches the target node — the arrival gate reads it to decide which
   * match an incoming player belongs to, and that lookup happens before any local state mentions
   * them.</p>
   */
  private volatile com.sexidium.core.network.MatchHandoffService handoffs;
  private volatile String nodeId = "standalone";
  private volatile long nodeEpoch;

  /**
   * Whether a player is at this instant being moved to another node. Never null once set.
   *
   * <p>Read on the world-acquisition FAILURE path, and only there. When a persistent world turns out
   * to be homed elsewhere the acquisition genuinely fails on this node — it must, because creating
   * the world here would generate an empty one over the player's save — but the player's request did
   * not fail: they are being taken to it. Announcing "could not create a temporary game world" to
   * someone mid-transfer is the single most confusing message the network produces, and it is also
   * wrong twice over: nothing temporary was involved and nothing needed creating.</p>
   */
  private volatile java.util.function.Predicate<UUID> beingTransferred = playerId -> false;

  void setBeingTransferred(java.util.function.Predicate<UUID> beingTransferred) {
    this.beingTransferred = beingTransferred == null ? playerId -> false : beingTransferred;
  }

  /**
   * Report a world-acquisition failure, unless every waiting player is on their way somewhere else.
   *
   * <p>All-or-nothing on purpose: a partially transferred group means something really did go wrong
   * for the ones left behind, and they should hear about it.</p>
   */
  private void reportWorldFailure(CommandSource audience, List<PlayerAdapter> participants) {
    boolean allTransferred = !participants.isEmpty()
        && participants.stream().allMatch(player -> beingTransferred.test(player.uniqueId()));
    if (allTransferred) {
      return;
    }
    gameContext.server().messages().send(audience, LocalizedText.of(MessageKey.TEMP_WORLD_FAILED));
  }

  public void setHandoffs(com.sexidium.core.network.MatchHandoffService handoffs, String nodeId, long nodeEpoch) {
    this.handoffs = handoffs;
    this.nodeId = nodeId == null ? "standalone" : nodeId;
    this.nodeEpoch = nodeEpoch;
  }

  private void reserveHandoffs(
      UUID matchId, String modeId, List<PlayerAdapter> participants, WorldLease worldLease) {
    com.sexidium.core.network.MatchHandoffService service = handoffs;
    if (service == null || participants.isEmpty()) {
      return;
    }
    // NOT for a persistent-world mode. This wrote a UUID-keyed row with mode_id="experience" on every
    // experience launch and immediately marked it ARRIVED -- a row nothing ever deleted, which then
    // sat in the arrival gate's window competing with the player's real transfer ticket. At the
    // observed 01:10:31 arrival the player had exactly two rows in window, and this was one of them.
    if (MatchLifecycle.isPersistentWorldMode(modeId)) {
      return;
    }
    long deadline = System.currentTimeMillis() + Math.max(5L,
        gameContext.server().configuration().getLong("network.match-handoff-seconds", 30L)) * 1000L;
    service.reserve(
        matchId.toString(),
        participants.stream().map(PlayerAdapter::uniqueId).toList(),
        nodeId, nodeEpoch, modeId,
        worldLease == null ? null : worldLease.world().name(),
        deadline);
    // Everyone already here has, by definition, arrived.
    for (PlayerAdapter participant : participants) {
      service.markArrived(matchId.toString(), participant.uniqueId());
    }
  }

  private boolean launch(
      String modeId,
      Game game,
      WorldLease worldLease,
      List<PlayerAdapter> participants,
      CommandSource initiator,
      List<String> modeArgs
  ) {
    // The reservation has served its purpose now that we are committing (or failing) the launch.
    for (PlayerAdapter participant : participants) {
      state.reservingPlayers.remove(participant.uniqueId());
    }
    List<PlayerAdapter> onlineParticipants = MatchState.sanitize(participants);
    if (onlineParticipants.size() < game.minPlayers()) {
      gameContext.server().messages().send(initiator, LocalizedText.of(
          MessageKey.GAME_MIN_PLAYERS,
          MessageArg.text("game", game.displayName()),
          MessageArg.text("count", game.minPlayers())
      ));
      state.clearStarting();
      if (worldLease != null) {
        worldLease.close();
      }
      return false;
    }

    UUID matchId = UUID.randomUUID();
    ActiveMatch activeMatch = new ActiveMatch(matchId, modeId, modeArgs, game, worldLease);
    state.matches.put(matchId, activeMatch);
    // Publish the roster so players still arriving from other nodes can be admitted into THIS match
    // on connect. No-op standalone, where every participant is already here.
    reserveHandoffs(matchId, modeId, onlineParticipants, worldLease);
    for (PlayerAdapter participant : onlineParticipants) {
      state.playerIndex.put(participant.uniqueId(), matchId);
    }
    state.clearStarting();
    gameContext.server().events().registerGame(game);
    try {
      if (worldLease != null && worldLease.world() != null) {
        for (PlayerAdapter participant : onlineParticipants) {
          // Teleport to the game's entry position. The experience host returns the player's SAVED
          // spot, so state is restored before this single teleport instead of re-teleporting after.
          WorldPosition target = game.entrySpawn(participant, worldLease.world());
          if (target != null) {
            // One operation, not two statements. This was the last entry path still hand-rolling
            // "prepareArrival, then teleport" — the exact pair EntryPolicy.arrive exists to keep
            // together, and which the join and world-reset paths were both moved off. Written apart it
            // skips the chunk warm, so the platform's teleport is still in flight when the next
            // statement runs; a caller that reads the player's position after it gets the world they
            // came FROM. That is why the "you started inside the terrain" rescue in ExperienceGame was
            // silently doing nothing: it was inspecting the lobby.
            arrive(game, participant, worldLease.world(), target);
          }
        }
      }
      game.start(onlineParticipants);
      // LAST: the mode's declared arrival, applied after start() has restored every participant's saved
      // state. A mode that wants somebody to arrive as a spectator (a lost hardcore world) must not be
      // undone by the snapshot restore that happens inside start().
      for (PlayerAdapter participant : onlineParticipants) {
        applyEntryPolicy(game, participant);
      }
    } catch (Exception exception) {
      gameContext.server().logger().severe("Failed to start mode '" + modeId + "'", exception);
      lifecycle.endMatch(activeMatch, LocalizedText.of(MessageKey.STOP_START_ERROR));
      return false;
    }
    lifecycle.persist(activeMatch);
    return true;
  }

  /**
   * Applies the mode's {@link EntryPolicy} to a starting player. Mirrors the join path in
   * {@code PlayerSessionCoordinator}, so the two ways into a world agree — a rule that only holds on one
   * of them is not a rule.
   */
  private void applyEntryPolicy(Game game, PlayerAdapter participant) {
    try {
      EntryPolicy policy = game.entryPolicy(participant);
      if (policy == null) {
        return;
      }
      policy.applyTo(participant);
      if (policy.notice() != null) {
        gameContext.server().messages().send(participant, policy.notice());
      }
    } catch (RuntimeException exception) {
      gameContext.server().logger().warning("Entry policy failed for a starting player", exception);
    }
  }

  /**
   * The half of the policy that has to happen BEFORE the entry teleport (the client's hardcore view).
   * Contained like the rest of it: a mode with a broken policy must never stop a player entering.
   */
  /**
   * Warms the destination, tells the client what kind of world it is getting, moves it there, and applies
   * the mode's arrival policy — as the single operation {@link EntryPolicy#arrive} defines.
   */
  private void arrive(Game game, PlayerAdapter participant, WorldAdapter world, WorldPosition target) {
    try {
      EntryPolicy policy = game.entryPolicy(participant);
      if (policy == null) {
        participant.teleport(target);
        return;
      }
      policy.arrive(participant, world, target, moved -> {
        if (!Boolean.TRUE.equals(moved)) {
          gameContext.server().logger().warning("Could not move " + participant.name()
              + " into the world for mode '" + game.id() + "'; they were left where they were.");
        }
      });
    } catch (RuntimeException exception) {
      gameContext.server().logger().warning("Entry preparation failed for a starting player", exception);
    }
  }

  private Game create(String modeId, List<String> modeArgs) {
    return gameRegistry.create(gameContext, modeId, modeArgs).orElse(null);
  }
}
