package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.game.modes.MinigameMode;
import com.sexidium.core.game.modes.minigames.race.RaceObjective;
import com.sexidium.core.game.team.Team;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

public final class RaceGame extends MinigameMode {
  private final Random random = new Random();
  private final List<RaceObjective> objectives = new ArrayList<>();
  // Scores/found-sets are keyed by a "score group": one entry per team when teamPlay(), else one per
  // player. See group(...) for the key format ("team:N" or "player:<uuid>").
  private final RaceScoreboard scoreboard = new RaceScoreboard();
  private final RaceObjectiveSelector selector = new RaceObjectiveSelector(random, new RaceObjectiveSelector.Config() {
    @Override
    public boolean getBoolean(String key, boolean fallback) {
      return cfg().getBoolean(configPath(key), fallback);
    }

    @Override
    public double getDouble(String key, double fallback) {
      return cfg().getDouble(configPath(key), fallback);
    }

    @Override
    public int getInt(String key, int fallback) {
      return cfg().getInt(configPath(key), fallback);
    }

    @Override
    public String getString(String key, String fallback) {
      return cfg().getString(configPath(key), fallback);
    }

    @Override
    public List<String> getStringList(String key) {
      return cfg().getStringList(configPath(key));
    }

    @Override
    public List<Map<String, Object>> getMapList(String key) {
      return cfg().getMapList(configPath(key));
    }
  });
  private final RaceObjectiveDisplay display =
      new RaceObjectiveDisplay(itemKey -> gameContext.server().itemTranslationKey(itemKey));
  // Team play: a random leader per team and a leader-controlled flag allowing live team switches.
  private final Map<Integer, UUID> teamLeaders = new HashMap<>();
  private final Map<Integer, Boolean> switchAllowed = new HashMap<>();
  // End-of-time overtime vote (extend by another hour).
  private final Map<UUID, Boolean> overtimeVotes = new HashMap<>();
  private boolean voting;
  private int extensions;
  private boolean decided;
  private WorldAdapter arena;
  private String arenaWorldName = "";

  public RaceGame(GameContext gameContext, List<String> modeArgs) {
    super(gameContext, "race", "Race for Item", 2, modeArgs);
  }

  // Race rolls its objectives into a freshly generated world by default; an operator may instead list
  // hand-built maps under minigames.race.maps to clone one per race.
  @Override
  public String worldTemplate() {
    return chooseConfiguredMapTemplate();
  }

  // Race owns the sidebar with its objectives board (which already shows team-credited finds), so it
  // does not also show the shared team panel.
  @Override
  protected boolean usesTeamSidebar() {
    return false;
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    objectives.clear();
    scoreboard.clear();
    teamLeaders.clear();
    switchAllowed.clear();
    overtimeVotes.clear();
    voting = false;
    extensions = 0;
    decided = false;
    chooseObjectives();
    super.start(participants);
    if (teamPlay()) {
      formTeams();
      assignTeamLeaders();
    }
    // Race runs in a disposable world, so drop the lease's world border: players may roam freely to far
    // structures, the Nether/End, and even kill the Ender Dragon as an objective. Reset it on the match
    // world directly — the players' async teleport into that world may not have landed yet at start(),
    // so reading player.world() here would target the lobby and leave the race border in place.
    if (cfg().getBoolean(configPath("remove-world-border"), true)) {
      WorldAdapter world = matchWorld();
      if (world != null) {
        world.resetBorder();
      }
    }
    for (PlayerAdapter player : online()) {
      scoreboard.ensureGroup(group(player));
      if (cfg().getBoolean(configPath("clear-inventory"), true)) {
        player.clearInventory();
      }
      giveKit(player, tunableString("starter-kit", "starter-kit", ""));
    }
    resolveArena();
    placeStructures();
    if (cfg().getBoolean(configPath("display.enabled"), true)) {
      // The Race board is a contributor on the unified HUD: it inherits the double-sneak / look-up
      // toggle (FULL → COMPACT → HIDDEN), the blank line-numbers and the self-healing panel lifecycle.
      long refresh = HudCadence.ticks(cfg(), configPath("display.refresh-ticks"));
      installHud(text(MessageKey.RACE_SCOREBOARD_TITLE), refresh, null, new RaceHud());
      for (PlayerAdapter player : online()) {
        hud().show(player);
      }
    }
    if (hasStructures()) {
      runTimer(this::checkStructureZones, 20L, 20L);
    }
    startRoundTimer((int) Math.max(0L, cfg().getLong(configPath("duration-seconds"), 3600L)));
    popupAll(PopupType.OBJECTIVE, MessageKey.RACE_START_TITLE);
  }

  @Override
  public void handle(GameEvent gameEvent) {
    super.handle(gameEvent);
    if (!isRunning() || decided) {
      return;
    }
    if (gameEvent instanceof PlayerRespawnGameEvent event && isParticipant(event.playerAdapter())) {
      respawnInArena(event.playerAdapter());
    } else if (gameEvent instanceof BlockBreakGameEvent event && isParticipant(event.playerAdapter())) {
      markItem(event.playerAdapter(), event.blockKey());
    } else if (gameEvent instanceof InventoryChangeGameEvent event && isParticipant(event.playerAdapter())) {
      scanInventory(event.playerAdapter());
    }
  }

  // Race is NOT an elimination mode: a death must never remove a player or end the match. Override the
  // BaseTimedGame lethal-damage handler (which eliminates + checks for a winner) to do nothing — damage
  // happens vanilla-style and the player simply respawns and keeps racing until the timer.
  @Override
  protected void handleDamage(PlayerDamageGameEvent event) {
  }

  // Own the respawn so a dead racer is kept in the match (returned to the arena spawn) instead of being
  // released to the lobby by GameManager.handleRespawn.
  @Override
  public boolean handlesOwnRespawn() {
    return true;
  }

  /** Returns a respawning racer to the arena spawn and restores the race overlays, so they keep playing. */
  private void respawnInArena(PlayerAdapter player) {
    WorldAdapter world = arena != null ? arena : matchWorld();
    if (world != null && world.spawnPosition() != null) {
      player.teleport(world.spawnPosition());
    }
    restorePlayerUi(player);
  }

  @Override
  protected void announceStarted() {
    announce(MessageKey.RACE_STARTED, MessageArg.mini("targets", display.targetSummary(objectives)));
  }

  @Override
  public void stop(LocalizedText reason) {
    if (!decided && isRunning()) {
      announce(MessageKey.RACE_ENDED_NO_WINNER);
    }
    super.stop(reason);
  }

  // ----- Item objectives -------------------------------------------------------------------------

  private void scanInventory(PlayerAdapter player) {
    InventoryAdapter inventory = player.inventory();
    if (inventory == null) {
      return;
    }
    for (RaceObjective objective : objectives) {
      if (objective.isItem() && inventory.count(objective.itemKey()) >= objective.amount()) {
        complete(player, objective);
      }
    }
  }

  private void markItem(PlayerAdapter player, ItemKey itemKey) {
    for (RaceObjective objective : objectives) {
      if (objective.isItem() && objective.itemKey().equals(itemKey)) {
        complete(player, objective);
        return;
      }
    }
  }

  // ----- Structure objectives --------------------------------------------------------------------

  private boolean hasStructures() {
    return objectives.stream().anyMatch(RaceObjective::isStructure);
  }

  private void placeStructures() {
    if (arena == null) {
      return;
    }
    int minRadius = Math.max(16, cfg().getInt(configPath("structures.min-radius"), 200));
    int maxRadius = Math.max(minRadius + 16, cfg().getInt(configPath("structures.max-radius"), 900));
    int zoneRadius = Math.max(2, cfg().getInt(configPath("structures.zone-radius"), 10));
    int markerHeight = Math.max(1, cfg().getInt(configPath("structures.marker-height"), 12));
    ItemKey marker = itemKey(cfg().getString(configPath("structures.marker-block"), "sea_lantern"));
    WorldPosition origin = arena.spawnPosition();
    for (RaceObjective objective : objectives) {
      if (!objective.isStructure()) {
        continue;
      }
      double angle = random.nextDouble() * Math.PI * 2.0;
      double distance = minRadius + random.nextDouble() * (maxRadius - minRadius);
      int centerX = (int) Math.round(origin.coordinateX() + Math.cos(angle) * distance);
      int centerZ = (int) Math.round(origin.coordinateZ() + Math.sin(angle) * distance);
      // Place the marker on the actual TERRAIN SURFACE at this far X/Z, not at the arena spawn Y — at
      // 200-900 blocks out the real surface is nothing like spawn height, so a spawn-Y tower would be
      // buried or floating and the player could never find/reach the zone (the reported bug).
      int surface = arena.highestSolidBlockY(arenaWorldName, centerX, centerZ);
      int groundY = surface == Integer.MIN_VALUE ? (int) Math.round(origin.coordinateY()) : surface;
      int baseY = Math.max(arena.minBuildHeight(), Math.min(groundY + 1, arena.maxBuildHeight() - markerHeight - 1));
      WorldPosition center = new WorldPosition(arenaWorldName, centerX + 0.5, baseY, centerZ + 0.5, 0.0f, 0.0f);
      objective.place(center, zoneRadius);
      // A visible marker tower (rooted on the surface) stands in for the "structure" so the zone is
      // findable + reachable on any platform.
      for (int dy = 0; dy < markerHeight; dy++) {
        arena.setBlock(new BlockPosition(arenaWorldName, centerX, baseY + dy, centerZ), marker);
      }
      announce(MessageKey.RACE_STRUCTURE_SPAWNED,
          MessageArg.text("structure", RaceObjectiveDisplay.prettyName(objective.structureId())),
          MessageArg.text("x", centerX), MessageArg.text("z", centerZ));
    }
  }

  private void checkStructureZones() {
    if (!isRunning() || decided) {
      return;
    }
    for (PlayerAdapter player : online()) {
      WorldPosition position = player.position();
      if (position == null) {
        continue;
      }
      for (RaceObjective objective : objectives) {
        if (!objective.isStructure() || objective.center() == null) {
          continue;
        }
        if (scoreboard.found(group(player)).contains(objective.id())) {
          continue;
        }
        if (withinZone(position, objective)) {
          complete(player, objective);
        }
      }
    }
  }

  private boolean withinZone(WorldPosition position, RaceObjective objective) {
    WorldPosition center = objective.center();
    // Same world only: the world border is removed so players may roam to the Nether/End, where their
    // X/Z can coincide with an Overworld structure's — that must not count as reaching it.
    String playerWorld = position.worldName() == null ? "" : position.worldName();
    String centerWorld = center.worldName() == null ? "" : center.worldName();
    if (!playerWorld.equals(centerWorld)) {
      return false;
    }
    double dx = position.coordinateX() - center.coordinateX();
    double dz = position.coordinateZ() - center.coordinateZ();
    return dx * dx + dz * dz <= (double) objective.radius() * objective.radius();
  }

  // ----- Completion / win ------------------------------------------------------------------------

  private void complete(PlayerAdapter player, RaceObjective objective) {
    String group = group(player);
    Team team = teamPlay() ? teams.teamOf(player.uniqueId()) : null;
    if (!scoreboard.markCompleted(group, objective.id(), objective.points())) {
      return;
    }
    MessageArg actor = team != null
        ? MessageArg.mini("player", team.coloredName())
        : MessageArg.text("player", player.name());
    MessageKey key = objective.isStructure() ? MessageKey.RACE_STRUCTURE_REACHED : MessageKey.RACE_FOUND_ITEM;
    announce(key, actor, objectiveNameArg(objective), MessageArg.text("points", objective.points()));
    popup(player, PopupType.OBJECTIVE, key, objectiveNameArg(objective), MessageArg.text("points", objective.points()));
    refreshHud();
    if (scoreboard.found(group).size() >= objectives.size()) {
      decided = true;
      if (team != null) {
        announce(MessageKey.RACE_WIN, MessageArg.mini("winner", team.coloredName()),
            MessageArg.text("score", scoreboard.score(group)));
        popupAll(PopupType.WIN, MessageKey.RACE_WIN_TITLE, MessageArg.mini("winner", team.coloredName()));
        awardTeamWin(team);
        requestEnd();
      } else {
        announce(MessageKey.RACE_WIN, MessageArg.text("winner", player.name()), MessageArg.text("score", scoreboard.score(group)));
        popupAll(PopupType.WIN, MessageKey.RACE_WIN_TITLE, MessageArg.text("winner", player.name()));
        finishWithWinner(player);
      }
    }
  }

  // ----- Round timer + overtime vote -------------------------------------------------------------

  private void startRoundTimer(int seconds) {
    if (seconds > 0) {
      timerBar(MessageKey.RACE_TIMER_TITLE, seconds, BossBarColor.YELLOW, this::onTimeExpired);
    }
  }

  private void onTimeExpired() {
    if (!isRunning() || decided) {
      return;
    }
    int maxExtensions = Math.max(0, cfg().getInt(configPath("overtime.max-extensions"), 24));
    if (cfg().getBoolean(configPath("overtime.enabled"), true) && extensions < maxExtensions && !online().isEmpty()) {
      beginOvertimeVote();
      return;
    }
    awardTimeout();
  }

  private void beginOvertimeVote() {
    voting = true;
    overtimeVotes.clear();
    int voteSeconds = Math.max(15, cfg().getInt(configPath("overtime.vote-seconds"), 300));
    announce(MessageKey.RACE_VOTE_START, MessageArg.text("seconds", voteSeconds));
    popupAll(PopupType.OBJECTIVE, MessageKey.RACE_VOTE_START, MessageArg.text("seconds", voteSeconds));
    timerBar(MessageKey.RACE_VOTE_BAR, voteSeconds, BossBarColor.GREEN, this::tallyOvertimeVote);
  }

  private void tallyOvertimeVote() {
    if (!isRunning() || decided || !voting) {
      return;
    }
    voting = false;
    int yes = 0;
    int no = 0;
    for (boolean vote : overtimeVotes.values()) {
      if (vote) {
        yes++;
      } else {
        no++;
      }
    }
    int onlineCount = online().size();
    int quorum = Math.max(1, (onlineCount + 1) / 2); // a simple majority of players must vote at all
    if (overtimeVotes.size() < quorum || yes <= no) {
      announce(MessageKey.RACE_VOTE_FAILED, MessageArg.text("yes", yes), MessageArg.text("no", no));
      awardTimeout();
      return;
    }
    extensions++;
    int extensionSeconds = Math.max(60, cfg().getInt(configPath("overtime.extension-seconds"), 3600));
    announce(MessageKey.RACE_VOTE_EXTENDED, MessageArg.text("yes", yes), MessageArg.text("no", no),
        MessageArg.text("minutes", extensionSeconds / 60));
    startRoundTimer(extensionSeconds);
  }

  /** Records a player's overtime vote; called from {@code /sx race vote <yes|no>}. */
  public boolean castOvertimeVote(PlayerAdapter player, boolean yes) {
    if (!voting || player == null || !isParticipant(player)) {
      return false;
    }
    overtimeVotes.put(player.uniqueId(), yes);
    gameContext.server().messages().send(player, text(MessageKey.RACE_VOTE_CAST, MessageArg.text("vote", yes ? "yes" : "no")));
    return true;
  }

  private void awardTimeout() {
    if (!isRunning() || decided) {
      return;
    }
    popupAll(PopupType.OBJECTIVE, MessageKey.RACE_TIMER_EXPIRED_TITLE);
    if (cfg().getBoolean(configPath("timeout-awards-leading-player"), true)) {
      if (teamPlay()) {
        Team leader = untiedLeaderTeam();
        if (leader != null) {
          decided = true;
          announce(MessageKey.RACE_TIMEOUT_WIN, MessageArg.mini("winner", leader.coloredName()),
              MessageArg.text("score", scoreboard.score(group(leader))));
          awardTeamWin(leader);
          requestEnd();
          return;
        }
      } else {
        PlayerAdapter leader = untiedLeader();
        if (leader != null) {
          decided = true;
          announce(MessageKey.RACE_TIMEOUT_WIN, MessageArg.text("winner", leader.name()), MessageArg.text("score", scoreboard.score(group(leader))));
          finishWithWinner(leader);
          return;
        }
      }
    }
    announce(MessageKey.RACE_ENDED_NO_WINNER);
    requestEnd();
  }

  // ----- Team leaders + live switching -----------------------------------------------------------

  private void assignTeamLeaders() {
    for (Team team : teams.all()) {
      List<UUID> members = new ArrayList<>(team.members());
      if (members.isEmpty()) {
        continue;
      }
      UUID leader = members.get(random.nextInt(members.size()));
      teamLeaders.put(team.index(), leader);
      switchAllowed.put(team.index(), cfg().getBoolean(configPath("teams.allow-switch-by-default"), false));
      gameContext.server().player(leader).ifPresent(leaderPlayer ->
          announce(MessageKey.RACE_TEAM_LEADER, MessageArg.text("player", leaderPlayer.name()), MessageArg.mini("team", team.coloredName())));
    }
  }

  private boolean isLeader(PlayerAdapter player) {
    if (player == null) {
      return false;
    }
    Team team = teams.teamOf(player.uniqueId());
    return team != null && player.uniqueId().equals(teamLeaders.get(team.index()));
  }

  /** Leader toggles whether players may switch INTO their team; called from {@code /sx race allow}. */
  public boolean setSwitchAllowed(PlayerAdapter player, boolean allow) {
    if (!teamPlay() || !isLeader(player)) {
      gameContext.server().messages().send(player, text(MessageKey.RACE_NOT_LEADER));
      return false;
    }
    Team team = teams.teamOf(player.uniqueId());
    switchAllowed.put(team.index(), allow);
    announce(allow ? MessageKey.RACE_SWITCH_OPENED : MessageKey.RACE_SWITCH_CLOSED, MessageArg.mini("team", team.coloredName()));
    return true;
  }

  /** Live team switch (if the target team's leader allows); called from {@code /sx race switch <team>}. */
  public boolean switchTeam(PlayerAdapter player, String teamArg) {
    if (!teamPlay() || player == null || !isParticipant(player)) {
      return false;
    }
    Team target = teamByArg(teamArg);
    if (target == null) {
      gameContext.server().messages().send(player, text(MessageKey.RACE_TEAM_UNKNOWN, MessageArg.text("team", teamArg == null ? "" : teamArg)));
      return false;
    }
    Team current = teams.teamOf(player.uniqueId());
    if (current != null && current.index() == target.index()) {
      return false;
    }
    if (!switchAllowed.getOrDefault(target.index(), false)) {
      gameContext.server().messages().send(player, text(MessageKey.RACE_SWITCH_DENIED, MessageArg.mini("team", target.coloredName())));
      return false;
    }
    teams.remove(player.uniqueId());
    target.add(player.uniqueId());
    scoreboard.ensureGroup(group(target));
    announce(MessageKey.RACE_SWITCHED, MessageArg.text("player", player.name()), MessageArg.mini("team", target.coloredName()));
    refreshHud();
    return true;
  }

  private Team teamByArg(String teamArg) {
    if (teamArg == null || teamArg.isBlank()) {
      return null;
    }
    String normalized = teamArg.trim().toLowerCase(Locale.ROOT);
    for (Team team : teams.all()) {
      if (team.name().toLowerCase(Locale.ROOT).equals(normalized)
          || team.color().displayName().toLowerCase(Locale.ROOT).equals(normalized)
          || String.valueOf(team.index() + 1).equals(normalized)) {
        return team;
      }
    }
    return null;
  }

  // ----- Leaders for timeout (highest score) -----------------------------------------------------

  private PlayerAdapter untiedLeader() {
    return scoreboard.untiedLeader(online(), this::group);
  }

  private Team untiedLeaderTeam() {
    return scoreboard.untiedLeader(teams.all(), this::group);
  }

  private String group(PlayerAdapter player) {
    if (teamPlay()) {
      Team team = teams.teamOf(player.uniqueId());
      if (team != null) {
        return group(team);
      }
    }
    return "player:" + player.uniqueId();
  }

  private String group(Team team) {
    return "team:" + team.index();
  }

  // ----- HUD -------------------------------------------------------------------------------------

  /** Repaints the Race board now (after a find / team switch); no-op when the display is disabled. */
  private void refreshHud() {
    if (hud() != null) {
      hud().render();
    }
  }

  /**
   * The Race board as a unified-HUD contributor. FULL lists every objective and every group's score;
   * COMPACT trims to the viewer's own group progress. Item names come from {@code objectiveDisplayMini}'s
   * translatable {@code <lang:item.*>} tags, so they localize to each client's own language.
   */
  private final class RaceHud implements HudContributor {
    @Override
    public void describe(HudContext context) {
      if (context.compact()) {
        describeCompact(context);
        return;
      }
      for (RaceObjective objective : objectives) {
        context.line(LocalizedText.of(MessageKey.RACE_ITEM_POINTS,
            objectiveNameArg(objective),
            MessageArg.text("amount", objective.amount()),
            MessageArg.text("points", objective.points())));
      }
      if (teamPlay()) {
        for (Team team : teams.all()) {
          context.line(foundLine(MessageArg.mini("player", team.coloredName()), group(team)));
        }
      } else {
        for (PlayerAdapter player : online()) {
          context.line(foundLine(MessageArg.text("player", player.name()), group(player)));
        }
      }
    }

    private void describeCompact(HudContext context) {
      PlayerAdapter viewer = context.player();
      if (viewer == null) {
        return;
      }
      Team team = teamPlay() ? teams.teamOf(viewer.uniqueId()) : null;
      MessageArg who = team != null
          ? MessageArg.mini("player", team.coloredName())
          : MessageArg.text("player", viewer.name());
      context.line(foundLine(who, group(viewer)));
    }

    private LocalizedText foundLine(MessageArg who, String group) {
      return LocalizedText.of(MessageKey.RACE_SCOREBOARD_FOUND,
          who,
          MessageArg.text("found", scoreboard.found(group).size()),
          MessageArg.text("total", objectives.size()),
          MessageArg.text("points", scoreboard.score(group)));
    }
  }

  private MessageArg objectiveNameArg(RaceObjective objective) {
    return MessageArg.mini("item", display.objectiveDisplayMini(objective));
  }

  // ----- Objective selection ---------------------------------------------------------------------

  private void chooseObjectives() {
    objectives.addAll(selector.chooseObjectives());
  }

  private void resolveArena() {
    WorldAdapter matchWorld = matchWorld();
    if (matchWorld != null) {
      arena = matchWorld;
      arenaWorldName = matchWorld.name() == null ? "" : matchWorld.name();
      return;
    }
    for (PlayerAdapter player : online()) {
      WorldAdapter world = player.world();
      if (world != null) {
        arena = world;
        arenaWorldName = world.name() == null ? "" : world.name();
        return;
      }
    }
  }

  /** The match's leased world — reliable even before the players' async teleport into it has landed. */
  private WorldAdapter matchWorld() {
    if (gameContext.games() == null) {
      return null;
    }
    com.sexidium.core.game.ActiveMatch match = gameContext.games().matchByGame(this);
    return match == null ? null : match.world();
  }

  private ItemKey itemKey(String value) {
    return RaceObjectiveSelector.itemKey(value);
  }
}
