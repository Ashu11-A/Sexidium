package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.modes.BattleMode;
import com.sexidium.core.game.team.Team;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.ArenaGen;
import com.sexidium.core.world.map.TeamZone;

import java.util.ArrayList;
import java.util.List;

public final class CombatGame extends BattleMode {
  private boolean fighting;

  public CombatGame(GameContext gameContext, List<String> modeArgs) {
    super(gameContext, "combat", "Combat Item Mode", 2, modeArgs);
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    fighting = false;
    super.start(participants);
    loadBattleMap();
    if (teamPlay()) {
      formTeams();
      announceTeams();
    }
    teleportToArena();
    int warmup = Math.max(0, tunableInt("warmup-seconds", "warmup-seconds", 5));
    if (warmup > 0) {
      timerBar(MessageKey.COMBAT_WARMUP, warmup, BossBarColor.YELLOW, this::beginFight);
    } else {
      beginFight();
    }
    int duration = Math.max(0, cfg().getInt(configPath("duration-seconds"), 300));
    if (duration > 0) {
      timerBar(MessageKey.COMBAT_ROUND_ENDS, duration, BossBarColor.RED, this::finishByHealth);
    }
  }

  @Override
  protected String configuredKit() {
    return cfg().getString(configPath("kit"), "default");
  }

  @Override
  protected void announceStarted() {
    announce(MessageKey.COMBAT_START,
        MessageArg.text("kit", configuredKit().isBlank() ? "default" : configuredKit()),
        MessageArg.text("fighters", online().size()));
    popupAll(PopupType.OBJECTIVE, MessageKey.COMBAT_TITLE_SUBTITLE);
  }

  @Override
  protected void handleDamage(PlayerDamageGameEvent event) {
    if (!fighting && isParticipant(event.victim())) {
      event.setCancelled(true);
      return;
    }
    if (event.attacker() != null && isParticipant(event.victim()) && isParticipant(event.attacker()) && event.victim().uniqueId().equals(event.attacker().uniqueId())) {
      return;
    }
    if (teamPlay() && event.attacker() != null && sameTeam(event.victim(), event.attacker())) {
      event.setCancelled(true);
      return;
    }
    super.handleDamage(event);
  }

  @Override
  protected void eliminate(PlayerAdapter victim, PlayerAdapter attacker) {
    if (victim == null || !isParticipant(victim)) {
      return;
    }
    removeParticipant(victim);
    releaseAndReset(victim);
    announce(MessageKey.COMBAT_ELIMINATED,
        MessageArg.text("victim", victim.name()),
        MessageArg.text("by", attacker == null ? "" : " by " + attacker.name()),
        MessageArg.text("left", remainingOnlineParticipants().size()));
    popup(victim, PopupType.ELIMINATION, MessageKey.COMBAT_ELIMINATED, MessageArg.text("victim", victim.name()));
    if (attacker != null && isParticipant(attacker)) {
      awardKill(attacker);
    }
    checkForWinner();
  }

  @Override
  protected void checkForWinner() {
    if (teamPlay()) {
      checkForTeamWinner();
      return;
    }
    List<PlayerAdapter> remaining = remainingOnlineParticipants();
    if (remaining.size() == 1 && players.size() <= 1) {
      announce(MessageKey.COMBAT_WIN, MessageArg.text("winner", remaining.get(0).name()));
      popupAll(PopupType.WIN, MessageKey.COMBAT_WIN_TITLE, MessageArg.text("winner", remaining.get(0).name()));
    } else if (remaining.isEmpty() && players.isEmpty()) {
      announce(MessageKey.COMBAT_NO_WINNER);
    }
    super.checkForWinner();
  }

  private void beginFight() {
    if (isRunning()) {
      fighting = true;
    }
  }

  private void announceTeams() {
    for (PlayerAdapter player : online()) {
      Team team = teams.teamOf(player.uniqueId());
      if (team != null) {
        player.sendActionBar("<gray>Team: </gray>" + team.coloredName());
      }
    }
  }

  private void checkForTeamWinner() {
    if (!remainingOnlineParticipants().isEmpty()) {
      Team winner = lastTeamStanding();
      if (winner != null) {
        awardTeamWin(winner);
        for (PlayerAdapter player : online()) {
          player.sendActionBar("<bold>" + winner.coloredName() + " <green>wins!</green></bold>");
        }
        requestEnd();
      }
    } else if (players.isEmpty()) {
      announce(MessageKey.COMBAT_NO_WINNER);
      requestEnd();
    }
  }

  private void finishByHealth() {
    if (!isRunning()) {
      return;
    }
    PlayerAdapter winner = null;
    double best = -1.0;
    boolean tied = false;
    for (PlayerAdapter player : remainingOnlineParticipants()) {
      if (player.health() > best) {
        winner = player;
        best = player.health();
        tied = false;
      } else if (player.health() == best) {
        tied = true;
      }
    }
    if (winner != null && !tied) {
      announce(MessageKey.COMBAT_WIN, MessageArg.text("winner", winner.name()));
      finishWithWinner(winner);
    } else {
      announce(MessageKey.COMBAT_NO_WINNER);
      requestEnd();
    }
  }

  private void teleportToArena() {
    // A configured battle map (cloned into this match world) places fighters from its team zones: each
    // team to its own side when teams are formed, otherwise everyone spread across all configured spawns.
    if (placeFromBattleMap()) {
      return;
    }
    // No ready battle map: build a procedural spread arena into the generated match world so fighters do
    // not all spawn stacked on the raw world spawn.
    if (cfg().getBoolean(configPath("generated.enabled"), true)) {
      teleportToGeneratedArena();
    }
  }

  /**
   * Places fighters from the chosen map's {@link com.sexidium.core.world.map.BattleMap}: teams to their
   * own side ({@link BattleMode#teleportTeamsToZones()}) when teams are formed, otherwise everyone spread
   * round-robin across every team zone's spawns. The stored template coordinates are re-stamped with the
   * cloned match world's name. Returns false when no zones/spawns are configured, so the caller falls back
   * to a generated arena.
   */
  private boolean placeFromBattleMap() {
    if (battleMap().teamCount() == 0) {
      return false;
    }
    if (teamPlay() && teleportTeamsToZones()) {
      return true;
    }
    List<WorldPosition> allSpawns = new ArrayList<>();
    for (TeamZone zone : battleMap().zones()) {
      allSpawns.addAll(zone.spawns());
    }
    if (allSpawns.isEmpty()) {
      return false;
    }
    String runtimeWorld = arenaWorldName();
    int index = 0;
    for (PlayerAdapter player : online()) {
      WorldPosition spawn = allSpawns.get(index++ % allSpawns.size());
      player.teleport(runtimeWorld.isBlank() ? spawn : spawn.withWorldName(runtimeWorld));
    }
    return true;
  }

  private void teleportToGeneratedArena() {
    // Only reshape a disposable leased world; never the in-place world (e.g. lobby) used when temp
    // worlds are disabled.
    if (!gameContext.server().worlds().enabled()) {
      return;
    }
    WorldAdapter world = matchWorld();
    if (world == null) {
      return;
    }
    WorldPosition center = world.safeSpawnPosition();
    if (center == null) {
      center = world.spawnPosition();
    }
    if (center == null) {
      return;
    }
    int radius = Math.max(4, cfg().getInt(configPath("generated.radius"), 14));
    int wallHeight = Math.max(0, cfg().getInt(configPath("generated.wall-height"), 3));
    ItemKey floor = orDefault(ItemKey.parse(cfg().getString(configPath("generated.floor-block"), "smooth_stone")), "smooth_stone");
    ItemKey wall = wallHeight > 0 ? orDefault(ItemKey.parse(cfg().getString(configPath("generated.wall-block"), "stone_bricks")), "stone_bricks") : null;
    List<WorldPosition> generated = ArenaGen.ringArena(world, center, online().size(), radius, floor, wall, wallHeight, 6);
    if (generated.isEmpty()) {
      return;
    }
    int index = 0;
    for (PlayerAdapter player : online()) {
      player.teleport(generated.get(index++ % generated.size()));
    }
  }

  private static ItemKey orDefault(ItemKey key, String fallback) {
    return key != null ? key : ItemKey.minecraft(fallback);
  }
}
