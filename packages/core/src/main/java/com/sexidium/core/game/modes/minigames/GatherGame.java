package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.modes.BattleMode;
import com.sexidium.core.game.team.Team;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.ArenaGen;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.BattleMapStore;
import com.sexidium.core.world.map.TeamZone;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public final class GatherGame extends BattleMode {
  private final Queue<UUID> duelQueue = new ArrayDeque<>();
  private final Set<UUID> activeDuel = new HashSet<>();
  private final List<WorldPosition> generatedDuelSpawns = new ArrayList<>();
  private WorldAdapter borderedWorld;
  private boolean dueling;
  private boolean fighting;
  private boolean oneVsOne;

  public GatherGame(GameContext gameContext, List<String> modeArgs) {
    super(gameContext, "gather", "Gather and Duel", 2, modeArgs);
  }

  // Gather plays in a large generated/pooled world (the shrinking-border gather phase), so it does NOT
  // clone a small template map. The battle map is loaded only to source per-team duel spawns/regions for
  // a configured duel arena.
  @Override
  public String worldTemplate() {
    return null;
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    dueling = false;
    fighting = true;
    oneVsOne = false;
    duelQueue.clear();
    activeDuel.clear();
    super.start(participants);
    if (teamPlay()) {
      formTeams();
      announceTeams();
    }
    for (PlayerAdapter player : online()) {
      giveKit(player, tunableString("starter-kit", "starter-kit", ""));
    }
    setupBorder();
    int gatherSeconds = Math.max(1, cfg().getInt(configPath("gather-seconds"), 3600));
    timerBar(MessageKey.GATHER_UNTIL_DUEL, gatherSeconds, BossBarColor.GREEN, this::beginDuel);
  }

  @Override
  protected void announceStarted() {
    int gatherSeconds = Math.max(1, cfg().getInt(configPath("gather-seconds"), 3600));
    int barrier = Math.max(1, cfg().getInt(configPath("border.size"), 500));
    announce(MessageKey.GATHER_GATHER_STARTED,
        MessageArg.text("size", barrier),
        MessageArg.text("time", gatherSeconds + "s"));
    popupAll(PopupType.OBJECTIVE, MessageKey.GATHER_GATHER_STARTED, MessageArg.text("size", barrier), MessageArg.text("time", gatherSeconds + "s"));
  }

  @Override
  protected void handleDamage(PlayerDamageGameEvent event) {
    if (!isParticipant(event.victim())) {
      return;
    }
    // Friendly fire: teammates never damage (nor eliminate) each other, in either the gather or duel phase.
    if (teamPlay() && event.attacker() != null && sameTeam(event.victim(), event.attacker())) {
      event.setCancelled(true);
      return;
    }
    if (!dueling) {
      if (!cfg().getBoolean(configPath("pvp-during-gather"), false) && event.attacker() != null && isParticipant(event.attacker())) {
        event.setCancelled(true);
      }
      return;
    }
    if (!fighting || (oneVsOne && (!activeDuel.contains(event.victim().uniqueId()) || (event.attacker() != null && !activeDuel.contains(event.attacker().uniqueId()))))) {
      event.setCancelled(true);
      return;
    }
    if (!isLethal(event.victim(), event.finalDamage())) {
      return;
    }
    event.setCancelled(true);
    eliminate(event.victim(), event.attacker());
  }

  @Override
  protected void eliminate(PlayerAdapter victim, PlayerAdapter attacker) {
    if (victim == null || !isParticipant(victim)) {
      return;
    }
    removeParticipant(victim);
    activeDuel.remove(victim.uniqueId());
    releaseAndReset(victim);
    announce(MessageKey.GATHER_ELIMINATED,
        MessageArg.text("victim", victim.name()),
        MessageArg.text("by", attacker == null ? "" : " by " + attacker.name()),
        MessageArg.text("left", remainingOnlineParticipants().size()));
    popup(victim, PopupType.ELIMINATION, MessageKey.GATHER_ELIMINATED, MessageArg.text("victim", victim.name()));
    if (attacker != null && isParticipant(attacker)) {
      awardKill(attacker);
      if (oneVsOne) {
        duelQueue.add(attacker.uniqueId());
      }
    }
    if (oneVsOne) {
      nextMatch();
    } else {
      checkForWinner();
    }
  }

  @Override
  protected void checkForWinner() {
    if (teamPlay()) {
      checkForTeamWinner();
      return;
    }
    List<PlayerAdapter> remaining = remainingOnlineParticipants();
    if (remaining.size() == 1 && players.size() <= 1) {
      announce(MessageKey.GATHER_DUEL_WIN,
          MessageArg.text("winner", remaining.get(0).name()),
          MessageArg.localized("descriptor", LocalizedText.of(oneVsOne ? MessageKey.GATHER_DESCRIPTOR_TOURNAMENT_CHAMPION : MessageKey.GATHER_DESCRIPTOR_LAST_STANDING)));
      popupAll(PopupType.WIN, MessageKey.GATHER_DUEL_WIN_TITLE, MessageArg.text("winner", remaining.get(0).name()));
    } else if (remaining.isEmpty() && players.isEmpty()) {
      announce(MessageKey.GATHER_DUEL_NO_WINNER);
    }
    super.checkForWinner();
  }

  private void announceTeams() {
    for (PlayerAdapter player : online()) {
      Team team = teams.teamOf(player.uniqueId());
      if (team != null) {
        player.sendActionBar("<gray>Team: </gray>" + team.coloredName());
      }
    }
  }

  /** Team duel win condition: the round ends as soon as a single team is the last with a living member. */
  private void checkForTeamWinner() {
    if (!remainingOnlineParticipants().isEmpty()) {
      Team winner = lastTeamStanding();
      if (winner != null) {
        awardTeamWin(winner);
        // Reuse the existing duel-win key, passing the coloured team name where a player name would go.
        announce(MessageKey.GATHER_DUEL_WIN,
            MessageArg.text("winner", winner.coloredName()),
            MessageArg.localized("descriptor", LocalizedText.of(MessageKey.GATHER_DESCRIPTOR_LAST_STANDING)));
        for (PlayerAdapter player : online()) {
          player.sendActionBar("<bold>" + winner.coloredName() + " <green>wins!</green></bold>");
        }
        requestEnd();
      }
    } else if (players.isEmpty()) {
      announce(MessageKey.GATHER_DUEL_NO_WINNER);
      requestEnd();
    }
  }

  @Override
  public void stop(LocalizedText reason) {
    if (borderedWorld != null) {
      borderedWorld.resetBorder();
      borderedWorld = null;
    }
    super.stop(reason);
  }

  private void beginDuel() {
    if (!isRunning()) {
      return;
    }
    dueling = true;
    fighting = false;
    // The 1v1 ladder only applies to non-team free-for-all. With teams, a 1v1 ladder makes no sense
    // (it would queue individuals, ignoring team membership), so we run the duel as a team free-for-all:
    // everyone fights at once and the round ends on last-team-standing.
    oneVsOne = !teamPlay() && "ONE_VS_ONE".equalsIgnoreCase(cfg().getString(configPath("duel.format"), "FFA"));
    if (cfg().getBoolean(configPath("duel.heal"), true)) {
      for (PlayerAdapter player : online()) {
        prepareSurvival(player);
      }
    }
    loadDuelBattleMap();
    ensureDuelArena();
    announce(MessageKey.GATHER_TIME_UP);
    if (oneVsOne) {
      duelQueue.clear();
      for (PlayerAdapter player : online()) {
        duelQueue.add(player.uniqueId());
      }
      announce(MessageKey.GATHER_ONE_VS_ONE_START, MessageArg.text("fighters", duelQueue.size()));
      nextMatch();
    } else {
      teleportFfa();
      announce(MessageKey.GATHER_FFA_START, MessageArg.text("fighters", remainingOnlineParticipants().size()));
      warmupThenFight(MessageKey.GATHER_DUEL_ENDS, this::finishDuelByHealth);
    }
  }

  private void nextMatch() {
    if (!isRunning()) {
      return;
    }
    activeDuel.clear();
    List<UUID> stillQueued = new ArrayList<>();
    while (!duelQueue.isEmpty()) {
      UUID id = duelQueue.poll();
      if (players.contains(id)) {
        stillQueued.add(id);
      }
    }
    duelQueue.addAll(stillQueued);
    if (duelQueue.size() <= 1) {
      checkForWinner();
      return;
    }
    UUID first = duelQueue.poll();
    UUID second = duelQueue.poll();
    activeDuel.add(first);
    activeDuel.add(second);
    PlayerAdapter a = gameContext.server().player(first).orElse(null);
    PlayerAdapter b = gameContext.server().player(second).orElse(null);
    teleportDuelists(a, b);
    String firstName = a == null ? "?" : a.name();
    String secondName = b == null ? "?" : b.name();
    announce(MessageKey.GATHER_MATCH, MessageArg.text("first", firstName), MessageArg.text("second", secondName));
    warmupThenFight(MessageKey.GATHER_MATCH_ENDS, this::finishMatchByHealth,
        MessageArg.text("first", firstName), MessageArg.text("second", secondName));
  }

  private void warmupThenFight(MessageKey timerKey, Runnable timeout, MessageArg... warmupArguments) {
    int warmup = Math.max(0, tunableInt("duel.warmup-seconds", "warmup-seconds", 5));
    if (warmup > 0) {
      MessageKey warmupKey = oneVsOne ? MessageKey.GATHER_MATCH_COUNTDOWN : MessageKey.GATHER_DUEL_BEGINS;
      timerBar(warmupKey, warmup, BossBarColor.YELLOW, () -> startFight(timerKey, timeout), warmupArguments);
    } else {
      startFight(timerKey, timeout);
    }
  }

  private void startFight(MessageKey timerKey, Runnable timeout) {
    fighting = true;
    int duration = Math.max(0, cfg().getInt(configPath("duel.duration-seconds"), 300));
    if (duration > 0) {
      timerBar(timerKey, duration, BossBarColor.RED, timeout);
    }
  }

  private void finishMatchByHealth() {
    if (!isRunning() || !oneVsOne || activeDuel.size() < 2) {
      return;
    }
    PlayerAdapter best = null;
    double bestHealth = -1.0;
    for (UUID id : activeDuel) {
      PlayerAdapter player = gameContext.server().player(id).orElse(null);
      if (player != null && player.health() > bestHealth) {
        best = player;
        bestHealth = player.health();
      }
    }
    for (UUID id : List.copyOf(activeDuel)) {
      PlayerAdapter player = gameContext.server().player(id).orElse(null);
      if (player != null && (best == null || !player.uniqueId().equals(best.uniqueId()))) {
        eliminate(player, best);
        return;
      }
    }
  }

  private void finishDuelByHealth() {
    if (!isRunning()) {
      return;
    }
    if (teamPlay()) {
      finishDuelByTeamHealth();
      return;
    }
    PlayerAdapter best = null;
    double bestHealth = -1.0;
    boolean tied = false;
    for (PlayerAdapter player : remainingOnlineParticipants()) {
      if (player.health() > bestHealth) {
        best = player;
        bestHealth = player.health();
        tied = false;
      } else if (player.health() == bestHealth) {
        tied = true;
      }
    }
    if (best != null && !tied) {
      announce(MessageKey.GATHER_TIME_UP, MessageArg.text("winner", best.name()));
      finishWithWinner(best);
    } else {
      announce(MessageKey.GATHER_DUEL_NO_WINNER);
      requestEnd();
    }
  }

  /** Team-play tiebreak when the FFA duel timer runs out: the surviving team with the most total health wins. */
  private void finishDuelByTeamHealth() {
    Team best = null;
    double bestHealth = -1.0;
    boolean tied = false;
    for (Team team : teams.all()) {
      double total = 0.0;
      boolean anyAlive = false;
      for (PlayerAdapter player : remainingOnlineParticipants()) {
        if (team.contains(player.uniqueId())) {
          total += player.health();
          anyAlive = true;
        }
      }
      if (!anyAlive) {
        continue;
      }
      if (total > bestHealth) {
        best = team;
        bestHealth = total;
        tied = false;
      } else if (total == bestHealth) {
        tied = true;
      }
    }
    if (best != null && !tied) {
      awardTeamWin(best);
      announce(MessageKey.GATHER_DUEL_WIN,
          MessageArg.text("winner", best.coloredName()),
          MessageArg.localized("descriptor", LocalizedText.of(MessageKey.GATHER_DESCRIPTOR_LAST_STANDING)));
      for (PlayerAdapter player : online()) {
        player.sendActionBar("<bold>" + best.coloredName() + " <green>wins!</green></bold>");
      }
      requestEnd();
    } else {
      announce(MessageKey.GATHER_DUEL_NO_WINNER);
      requestEnd();
    }
  }

  private void setupBorder() {
    borderedWorld = online().isEmpty() ? null : online().get(0).world();
    if (borderedWorld == null) {
      return;
    }
    WorldPosition spawn = borderedWorld.spawnPosition();
    double centerX = cfg().getBoolean(configPath("border.use-world-spawn"), true) && spawn != null ? spawn.coordinateX() : cfg().getDouble(configPath("border.center-x"), 0.0);
    double centerZ = cfg().getBoolean(configPath("border.use-world-spawn"), true) && spawn != null ? spawn.coordinateZ() : cfg().getDouble(configPath("border.center-z"), 0.0);
    borderedWorld.setBorder(new WorldBorderSpec(centerX, centerZ, Math.max(1.0, cfg().getDouble(configPath("border.size"), 500.0)), cfg().getInt(configPath("border.warning-distance"), 15), cfg().getDouble(configPath("border.damage-per-block"), 0.2)));
  }

  private void teleportFfa() {
    // A configured duel-arena battle map wins over the legacy flat spawn list / generated ring: spread
    // fighters round-robin across every team zone's spawns (each carries its own arena world name).
    List<WorldPosition> battle = battleMapSpawns();
    if (!battle.isEmpty()) {
      int i = 0;
      for (PlayerAdapter player : online()) {
        player.teleport(battle.get(i++ % battle.size()));
      }
      return;
    }
    List<Map<String, Object>> spawns = cfg().getMapList(configPath("duel.arena.spawns"));
    if (!spawns.isEmpty()) {
      int index = 0;
      for (PlayerAdapter player : online()) {
        player.teleport(position(spawns.get(index++ % spawns.size()), player));
      }
      return;
    }
    if (generatedDuelSpawns.isEmpty()) {
      return;
    }
    int index = 0;
    for (PlayerAdapter player : online()) {
      player.teleport(generatedDuelSpawns.get(index++ % generatedDuelSpawns.size()));
    }
  }

  private void teleportDuelists(PlayerAdapter first, PlayerAdapter second) {
    // Prefer the configured duel-arena battle map's first two team spawns (team 0 vs team 1).
    List<WorldPosition> battle = battleMapSpawns();
    if (battle.size() >= 2) {
      if (first != null) {
        first.teleport(battle.get(0));
      }
      if (second != null) {
        second.teleport(battle.get(1));
      }
      return;
    }
    List<Map<String, Object>> spawns = cfg().getMapList(configPath("duel.arena.spawns"));
    if (spawns.size() >= 2) {
      if (first != null) {
        first.teleport(position(spawns.get(0), first));
      }
      if (second != null) {
        second.teleport(position(spawns.get(1), second));
      }
      return;
    }
    if (generatedDuelSpawns.size() < 2) {
      return;
    }
    if (first != null) {
      first.teleport(generatedDuelSpawns.get(0));
    }
    if (second != null) {
      second.teleport(generatedDuelSpawns.get(1));
    }
  }

  /**
   * When no duel arena is hand-configured, procedurally builds a clean fighting platform into the
   * generated match world (the "duel map" complementing the gathered-in random map) and caches its
   * spawn ring. A 1v1 ladder gets two opposite spawns; an FFA gets one per fighter.
   */
  private void ensureDuelArena() {
    generatedDuelSpawns.clear();
    if (!cfg().getMapList(configPath("duel.arena.spawns")).isEmpty()) {
      return;
    }
    if (!cfg().getString(configPath("duel.arena.world"), "").isBlank()) {
      return;
    }
    if (!cfg().getBoolean(configPath("duel.arena.generated.enabled"), true)) {
      return;
    }
    // Only reshape a disposable leased world, never an in-place world (e.g. lobby).
    if (!gameContext.server().worlds().enabled()) {
      return;
    }
    WorldAdapter world = borderedWorld;
    if (world == null) {
      for (PlayerAdapter player : online()) {
        if (player.world() != null) {
          world = player.world();
          break;
        }
      }
    }
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
    int radius = Math.max(4, cfg().getInt(configPath("duel.arena.generated.radius"), 12));
    int wallHeight = Math.max(0, cfg().getInt(configPath("duel.arena.generated.wall-height"), 3));
    ItemKey floor = orDefault(ItemKey.parse(cfg().getString(configPath("duel.arena.generated.floor-block"), "smooth_stone")), "smooth_stone");
    ItemKey wall = wallHeight > 0 ? orDefault(ItemKey.parse(cfg().getString(configPath("duel.arena.generated.wall-block"), "stone_bricks")), "stone_bricks") : null;
    int count = oneVsOne ? 2 : Math.max(2, online().size());
    generatedDuelSpawns.addAll(ArenaGen.ringArena(world, center, count, radius, floor, wall, wallHeight, 6));
  }

  private static ItemKey orDefault(ItemKey key, String fallback) {
    return key != null ? key : ItemKey.minecraft(fallback);
  }

  /** Loads a battle map from the configured {@code duel.arena.world} (if any) to source duel spawns/regions. */
  private void loadDuelBattleMap() {
    String world = cfg().getString(configPath("duel.arena.world"), "");
    if (world.isBlank()) {
      battleMap = new BattleMap("", "");
      return;
    }
    Path folder = gameContext.server().worlds().worldRoot().resolve(world);
    battleMap = BattleMapStore.loadOrImportTntWar(folder, world);
  }

  /** Flattened duel spawns from the battle map's team zones (each carries its own stored arena world name). */
  private List<WorldPosition> battleMapSpawns() {
    List<WorldPosition> spawns = new ArrayList<>();
    for (TeamZone zone : battleMap.zones()) {
      spawns.addAll(zone.spawns());
    }
    return spawns;
  }

  private WorldPosition position(Map<String, Object> map, PlayerAdapter player) {
    String configuredWorld = cfg().getString(configPath("duel.arena.world"), "");
    String world = configuredWorld.isBlank() ? player.world() == null ? null : player.world().name() : configuredWorld;
    return new WorldPosition(world, dbl(map.get("x"), 0.5), dbl(map.get("y"), 100.0), dbl(map.get("z"), 0.5), (float) dbl(map.get("yaw"), 0.0), (float) dbl(map.get("pitch"), 0.0));
  }

  private double dbl(Object value, double fallback) {
    return value instanceof Number number ? number.doubleValue() : fallback;
  }
}
