package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.GameEvents.BlockPlaceGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.game.modes.BattleMode;
import com.sexidium.core.game.modes.minigames.tntwar.BaseTracker;
import com.sexidium.core.game.modes.minigames.tntwar.TntWarConfig;
import com.sexidium.core.game.persist.Props;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.BattleMapStore;
import com.sexidium.core.world.map.Cuboid;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.menu.ChestLayout;
import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.MenuButton;
import com.sexidium.core.menu.MenuView;
import com.sexidium.core.menu.SidebarScreen;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.ArenaGen;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two-team (Red vs Blue) TNT War fought on a pre-built, configurable arena. Each match clones one of the
 * configured maps into a disposable world ({@link #worldTemplate()} drives the clone) so machines and
 * bases start fresh every time. Win by destroying the requested percentage of the enemy base
 * (tracked live via {@link BaseTracker}) or by exhausting the enemy team's shared lives; ties at the
 * time limit go to whoever wrecked more of the enemy base.
 *
 * <p>Players build with an infinite palette (the chest opener sits in the last hotbar slot and opens a
 * GUI of every allowed block/item), placed dispensers self-stock with TNT and cannot be opened, and any
 * configured TNT id (vanilla or modded) auto-primes. A right-hand HUD shows team lives, base-destruction
 * percentages, and each player's global stats/points (the same figures the Discord bot reports).</p>
 */
public final class TntWarGame extends BattleMode {
  private static final String RED = TntWarTeams.RED;
  private static final String BLUE = TntWarTeams.BLUE;
  /** Rotates the chosen map across consecutive matches so a server with several maps cycles them. */
  private static final AtomicInteger MAP_ROTATION = new AtomicInteger();
  private static final Set<String> SINGLE_STACK = Set.of(
      "diamond_pickaxe", "diamond_axe", "diamond_shovel", "water_bucket", "lava_bucket",
      "minecart", "bucket", "flint_and_steel");

  private final TntWarConfig config;
  private final TntWarConfig.MapEntry chosenMap;

  private final Map<UUID, String> teamOf = new HashMap<>();
  // Round-robins each team's members across that team's configured spawn points (so a map with several
  // per-team spawns spreads players out instead of stacking everyone on the first one).
  private final Map<String, Integer> spawnCursor = new HashMap<>();
  private final Map<UUID, Integer> kills = new HashMap<>();
  private final Map<UUID, Integer> killstreak = new HashMap<>();
  private final java.util.Set<BlockPosition> dispensers = new java.util.HashSet<>();

  private WorldAdapter arena;
  private String arenaWorldName = "";
  private BaseTracker redTracker;
  private BaseTracker blueTracker;
  private static final String ROW_RED = "red";
  private static final String ROW_BLUE = "blue";

  private BossBarHandle destructionBar;
  private HudSurfaceHandle destructionSurface = HudSurfaceHandle.NOOP;
  private boolean trackersBuilt;

  private int redLives;
  private int blueLives;
  private boolean fighting;

  public TntWarGame(GameContext gameContext, List<String> modeArgs) {
    super(gameContext, "tntwar", "TNT War", 2, modeArgs);
    this.config = new TntWarConfig(gameContext.server().configuration(), configPrefix());
    this.chosenMap = config.chooseMap(MAP_ROTATION.getAndIncrement());
    this.redLives = config.livesPerTeam();
    this.blueLives = config.livesPerTeam();
  }

  // TNT War runs its own Red/Blue board on the sidebar, so it does not show the shared team panel.
  @Override
  protected boolean usesTeamSidebar() {
    return false;
  }

  // TNT War rotates its own configured maps (TntWarConfig) rather than picking a random one, so it feeds
  // its chosen map into the shared BattleMode template resolution.
  @Override
  protected ConfiguredMap chooseBattleMap() {
    return chosenMap == null ? null : new ConfiguredMap(chosenMap.id(), chosenMap.world());
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    teamOf.clear();
    kills.clear();
    killstreak.clear();
    fighting = false;
    trackersBuilt = false;
    redLives = config.livesPerTeam();
    blueLives = config.livesPerTeam();
    super.start(participants);
    loadMapDefinition();
    assignTeams();
    giveLoadoutAll();
    teleportTeams();
    createDestructionBar();
    openDestructionSurface();
    // Red/Blue board on the unified HUD: inherits the double-sneak / look-up toggle (FULL → COMPACT →
    // HIDDEN), blank line-numbers and the self-healing panel lifecycle. Repainted by the HUD's own timer.
    installHud(text(MessageKey.TNTWAR_HUD_TITLE), config.refreshTicks(), null, new TntWarHud());
    for (PlayerAdapter player : online()) {
      hud().show(player);
    }
    runTimer(this::refresh, config.refreshTicks(), config.refreshTicks());
    if (config.lockDispensers()) {
      runTimer(this::restockDispensers, 40L, 40L);
    }
    if (config.warmupSeconds() > 0) {
      timerBar(MessageKey.TNTWAR_WARMUP, config.warmupSeconds(), BossBarColor.YELLOW, this::beginFight);
    } else {
      beginFight();
    }
    if (config.matchSeconds() > 0) {
      timerBar(MessageKey.TNTWAR_TIMER, config.matchSeconds(), BossBarColor.RED, this::onTimeUp);
    }
  }

  @Override
  protected void announceStarted() {
    announce(MessageKey.TNTWAR_START, MessageArg.mini("roster", "<red>Red</red> <gray>vs</gray> <blue>Blue</blue>"));
    popupAll(PopupType.OBJECTIVE, MessageKey.TNTWAR_TITLE_SUBTITLE);
  }

  @Override
  public void handle(GameEvent gameEvent) {
    super.handle(gameEvent);
    if (!isRunning()) {
      return;
    }
    if (gameEvent instanceof BlockPlaceGameEvent event) {
      handlePlace(event);
    } else if (gameEvent instanceof PlayerInteractGameEvent event) {
      handleInteract(event);
    } else if (gameEvent instanceof PlayerMoveGameEvent event
        && isParticipant(event.playerAdapter())
        && event.toPosition() != null
        && event.toPosition().coordinateY() <= config.voidLevel()) {
      onDeath(event.playerAdapter(), null);
    }
  }

  @Override
  protected void handleDamage(PlayerDamageGameEvent event) {
    PlayerAdapter victim = event.victim();
    if (!isParticipant(victim)) {
      return;
    }
    if (!fighting) {
      event.setCancelled(true);
      return;
    }
    PlayerAdapter attacker = event.attacker();
    if (!config.friendlyFire() && attacker != null && isParticipant(attacker) && sameTntTeam(victim, attacker)) {
      event.setCancelled(true);
      return;
    }
    if (isLethal(victim, event.finalDamage())) {
      // We own the "death": cancel the killing blow so vanilla never shows the death screen, then run
      // the lives/respawn logic ourselves.
      event.setCancelled(true);
      onDeath(victim, attacker);
    }
  }

  // ----- Death / lives / respawn -----------------------------------------------------------------

  private void onDeath(PlayerAdapter victim, PlayerAdapter attacker) {
    if (victim == null || !isParticipant(victim)) {
      return;
    }
    String team = team(victim);
    killstreak.put(victim.uniqueId(), 0);
    if (attacker != null && isParticipant(attacker) && !sameTntTeam(victim, attacker)) {
      kills.merge(attacker.uniqueId(), 1, Integer::sum);
      killstreak.merge(attacker.uniqueId(), 1, Integer::sum);
      awardKill(attacker);
    }
    int remaining = decrementLives(team);
    if (remaining > 0) {
      respawn(victim);
    } else {
      removeParticipant(victim);
      releaseAndReset(victim);
      announce(MessageKey.TNTWAR_ELIMINATED, MessageArg.text("victim", victim.name()), MessageArg.text("team", displayTeam(team)));
      popup(victim, PopupType.ELIMINATION, MessageKey.TNTWAR_ELIMINATED, MessageArg.text("victim", victim.name()));
    }
    checkOutcome();
  }

  private void respawn(PlayerAdapter player) {
    player.setHealth(player.maxHealth());
    player.setFoodLevel(20);
    WorldPosition spawn = nextTeamSpawn(team(player));
    if (spawn != null) {
      player.teleport(spawn);
    }
    restorePlayerUi(player);
    popup(player, PopupType.INFO, MessageKey.TNTWAR_RESPAWN);
  }

  private int decrementLives(String team) {
    if (RED.equals(team)) {
      return redLives = Math.max(0, redLives - 1);
    }
    return blueLives = Math.max(0, blueLives - 1);
  }

  // ----- Live refresh: destruction tracking, HUD, win checks -------------------------------------

  private void refresh() {
    if (!isRunning()) {
      return;
    }
    ensureTrackers();
    updateDestructionBar();
    checkOutcome();
  }

  private void ensureTrackers() {
    if (trackersBuilt) {
      return;
    }
    if (arena == null) {
      resolveArena();
    }
    if (arena == null) {
      return;
    }
    Cuboid redBase = teamRegion(0);
    Cuboid blueBase = teamRegion(1);
    if (redBase == null || blueBase == null) {
      trackersBuilt = true; // No bases configured: fall back to lives/timer, do not keep re-scanning.
      return;
    }
    redTracker = new BaseTracker(redBase, sampler(), config.maxScanBlocks());
    blueTracker = new BaseTracker(blueBase, sampler(), config.maxScanBlocks());
    trackersBuilt = true;
  }

  private BaseTracker.BlockSampler sampler() {
    WorldAdapter world = arena;
    String worldName = arenaWorldName;
    return (x, y, z) -> world.blockTypeAt(new BlockPosition(worldName, x, y, z));
  }

  private int redBaseDestroyed() {
    return redTracker == null ? 0 : redTracker.destructionPercent();
  }

  private int blueBaseDestroyed() {
    return blueTracker == null ? 0 : blueTracker.destructionPercent();
  }

  private void checkOutcome() {
    if (!isRunning() || !fighting) {
      return;
    }
    applyOutcome(TntWarOutcome.check(redLives, blueLives, redBaseDestroyed(), blueBaseDestroyed(),
        config.winDestructionPercent()));
  }

  private void onTimeUp() {
    if (!isRunning()) {
      return;
    }
    applyOutcome(TntWarOutcome.onTime(redBaseDestroyed(), blueBaseDestroyed()));
  }

  private void applyOutcome(TntWarOutcome.Result result) {
    switch (result) {
      case RED -> finish(RED);
      case BLUE -> finish(BLUE);
      case DRAW -> draw();
      case NONE -> {
        // Match continues.
      }
    }
  }

  private void finish(String winningTeam) {
    if (!isRunning()) {
      return;
    }
    announce(MessageKey.TNTWAR_WIN, MessageArg.mini("team", displayTeam(winningTeam)));
    popupAll(PopupType.WIN, MessageKey.TNTWAR_WIN_TITLE, MessageArg.mini("team", displayTeam(winningTeam)));
    for (PlayerAdapter player : remainingOnlineParticipants()) {
      if (winningTeam.equals(team(player))) {
        awardWin(player);
      }
    }
    requestEnd();
  }

  private void draw() {
    if (isRunning()) {
      announce(MessageKey.TNTWAR_DRAW);
      requestEnd();
    }
  }

  // ----- Build palette + dispensers + TNT --------------------------------------------------------

  private void handlePlace(BlockPlaceGameEvent event) {
    PlayerAdapter player = event.playerAdapter();
    if (!isParticipant(player)) {
      return;
    }
    ItemKey placed = event.blockKey();
    if (config.isCustomTnt(placed) && config.autoPrime()) {
      event.setCancelled(true);
      throwPrimedTnt(player, event.blockPosition());
      return;
    }
    if (placed != null && placed.equals(config.dispenserId()) && event.blockPosition() != null) {
      // Newly placed dispenser self-stocks with TNT so the machine fires endlessly; remember it so the
      // restock timer can top it back up.
      WorldAdapter world = player.world();
      if (world != null) {
        world.fillDispenserWithTnt(event.blockPosition());
        dispensers.add(event.blockPosition());
      }
    }
    if (config.infiniteBlocks() && config.isPalette(placed)) {
      refundLater(player, placed);
    }
  }

  private void throwPrimedTnt(PlayerAdapter player, BlockPosition position) {
    WorldAdapter world = player.world();
    if (world == null || position == null) {
      return;
    }
    world.spawnTnt(
        new WorldPosition(position.worldName(), position.blockX() + 0.5, position.blockY() + 0.5, position.blockZ() + 0.5, 0.0F, 0.0F),
        config.primeFuseTicks(), 0.0, 0.15, 0.0, 4.0F);
  }

  private void refundLater(PlayerAdapter player, ItemKey item) {
    UUID playerId = player.uniqueId();
    runLater(() -> gameContext.server().player(playerId).filter(PlayerAdapter::online)
        .ifPresent(online -> online.inventory().add(new ItemStackData(item, 1, Map.of()))), 1L);
  }

  private void handleInteract(PlayerInteractGameEvent event) {
    if (event.actionType() != PlayerInteractGameEvent.ActionType.RIGHT_CLICK) {
      return;
    }
    PlayerAdapter player = event.playerAdapter();
    if (!isParticipant(player)) {
      return;
    }
    if (config.buildMenuItem().equals(event.itemKey())) {
      event.setCancelled(true);
      openBuildMenu(player);
      return;
    }
    if (config.lockDispensers() && event.blockPosition() != null) {
      WorldAdapter world = player.world();
      if (world != null && config.dispenserId().equals(world.blockTypeAt(event.blockPosition()))) {
        // Block the dispenser GUI so its infinite TNT stock cannot be removed.
        event.setCancelled(true);
      }
    }
  }

  private void restockDispensers() {
    // Top every player-placed dispenser back up to TNT so machines never run dry. Bounded by the number
    // of placed dispensers (a tracked set), not the base volume; a destroyed cell is dropped from the set.
    if (!isRunning() || arena == null || dispensers.isEmpty()) {
      return;
    }
    dispensers.removeIf(position -> {
      if (config.dispenserId().equals(arena.blockTypeAt(position))) {
        arena.fillDispenserWithTnt(position);
        return false;
      }
      return true; // No longer a dispenser (blown up / mined): stop tracking it.
    });
  }

  private void openBuildMenu(PlayerAdapter player) {
    List<ItemKey> palette = config.buildPalette();
    SidebarScreen.Builder screen = SidebarScreen.of("<dark_red><bold>TNT War Build Menu</bold></dark_red>")
        .background(MenuArt.BG_MINIGAMES);

    // Sidebar: Category navigation
    screen.sidebarIndex(0, MenuButton.label(ItemKey.minecraft("chest"),
        "<gold><bold>All Blocks</bold></gold>",
        List.of("<gray>Full building palette</gray>", "<dark_gray>Infinite supply</dark_gray>")));

    screen.sidebarIndex(1, MenuButton.label(ItemKey.minecraft("stone_bricks"),
        "<aqua><bold>Building</bold></aqua>",
        List.of("<gray>Walls, stairs & slabs</gray>")));

    screen.sidebarIndex(2, MenuButton.label(ItemKey.minecraft("redstone"),
        "<red><bold>Redstone</bold></red>",
        List.of("<gray>Wiring, repeaters & levers</gray>")));

    screen.sidebarIndex(3, MenuButton.label(ItemKey.minecraft("tnt"),
        "<yellow><bold>Explosives</bold></yellow>",
        List.of("<gray>TNT, dispensers & pistons</gray>")));

    screen.sidebarIndex(4, MenuButton.label(ItemKey.minecraft("diamond_pickaxe"),
        "<green><bold>Tools</bold></green>",
        List.of("<gray>Buckets, carts & picks</gray>")));

    // Content: 35-slot content area (Columns 2–8, Rows 0–4)
    int contentIndex = 0;
    for (ItemKey item : palette) {
      if (contentIndex >= ChestLayout.CONTENT_CAPACITY) {
        break;
      }
      ItemKey icon = item;
      screen.contentIndex(contentIndex++, MenuButton.of(icon, "<white>" + prettyName(item) + "</white>",
          List.of("<gray>Click to grab — infinite during the war</gray>"),
          ctx -> {
            if (ctx.player() != null && ctx.player().inventory() != null) {
              ctx.player().inventory().add(new ItemStackData(icon, paletteAmount(icon), Map.of()));
            }
          }));
    }

    // Bottom Nav: Close button at slot 47
    screen.back(MenuButton.of(ItemKey.minecraft("barrier"),
        "<red><bold>✕ Close</bold></red>",
        List.of("<gray>Close the build menu</gray>"),
        ctx -> gameContext.server().menus().close(ctx.player()))
        .withModel(MenuArt.model(MenuArt.ICON_LEAVE)));

    gameContext.server().menus().open(player, screen.build());
  }

  private int paletteAmount(ItemKey item) {
    return SINGLE_STACK.contains(item.value()) ? 1 : 64;
  }

  private String prettyName(ItemKey item) {
    String[] parts = item.value().split("_");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append(' ');
      }
      builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
    }
    return builder.toString();
  }

  // ----- HUD ------------------------------------------------------------------------------------

  /**
   * The Red/Blue board as a unified-HUD contributor. FULL shows team lives, base-destruction and the
   * viewer's global stats; COMPACT trims to the live match state (lives + base percentages).
   */
  private final class TntWarHud implements HudContributor {
    @Override
    public void describe(HudContext context) {
      context.line(text(MessageKey.TNTWAR_HUD_LIVES_HEADER));
      context.line(text(MessageKey.TNTWAR_HUD_LIVES_RED, MessageArg.text("count", redLives)));
      context.line(text(MessageKey.TNTWAR_HUD_LIVES_BLUE, MessageArg.text("count", blueLives)));
      context.line(text(MessageKey.TNTWAR_HUD_SPACER));
      context.line(text(MessageKey.TNTWAR_HUD_BASES_HEADER));
      context.line(text(MessageKey.TNTWAR_HUD_RED_BASE, MessageArg.text("percent", redBaseDestroyed())));
      context.line(text(MessageKey.TNTWAR_HUD_BLUE_BASE, MessageArg.text("percent", blueBaseDestroyed())));
      if (context.compact()) {
        return;
      }
      PlayerAdapter player = context.player();
      LeaderboardEntry profile = player == null ? null : gameContext.ranks().lookup(player.name());
      UUID id = player == null ? null : player.uniqueId();
      context.line(text(MessageKey.TNTWAR_HUD_SPACER));
      context.line(text(MessageKey.TNTWAR_HUD_STATS_HEADER));
      context.line(text(MessageKey.TNTWAR_HUD_WINS, MessageArg.text("count", profile == null ? 0 : profile.wins())));
      context.line(text(MessageKey.TNTWAR_HUD_KILLS, MessageArg.text("count", kills.getOrDefault(id, 0))));
      context.line(text(MessageKey.TNTWAR_HUD_STREAK, MessageArg.text("count", killstreak.getOrDefault(id, 0))));
      context.line(text(MessageKey.TNTWAR_HUD_POINTS, MessageArg.text("count", profile == null ? 0 : profile.points())));
    }
  }

  /**
   * The two base-destruction percentages as facing bars.
   *
   * <p>The clearest case in the codebase for a driver-rendered bar, and a deliberate proof that the
   * driver is not experience-specific: this is a minigame, not a challenge, and it reaches the same
   * machinery through {@code AbstractGame#hudSurface} with no registry involved. {@code BaseTracker}
   * already computes both figures for the boss bar and the sidebar; this is the same numbers drawn as
   * the proportion they actually are.</p>
   */
  public static HudSurfaceSpec destructionSpec() {
    return HudSurfaceSpec.persistent("tntwar")
        .anchor(HudAnchor.TOP_RIGHT)
        .bar(ROW_RED, LocalizedText.of(MessageKey.TNTWAR_SURFACE_RED))
        .bar(ROW_BLUE, LocalizedText.of(MessageKey.TNTWAR_SURFACE_BLUE))
        .build();
  }

  private void openDestructionSurface() {
    destructionSurface = hudSurface(destructionSpec());
    for (PlayerAdapter player : online()) {
      destructionSurface.show(player);
    }
  }

  private void createDestructionBar() {
    destructionBar = track(gameContext.server().ui().createBossBar(
        text(MessageKey.TNTWAR_DESTRUCTION_BAR, MessageArg.text("red", 0), MessageArg.text("blue", 0)),
        0.0F, BossBarColor.WHITE, BossBarOverlay.PROGRESS));
    for (PlayerAdapter player : online()) {
      destructionBar.show(player);
    }
  }

  private void updateDestructionBar() {
    if (destructionBar == null) {
      return;
    }
    int redGone = redBaseDestroyed();
    int blueGone = blueBaseDestroyed();
    destructionBar.title(text(MessageKey.TNTWAR_DESTRUCTION_BAR,
        MessageArg.text("red", redGone), MessageArg.text("blue", blueGone)));
    destructionBar.progress(Math.max(redGone, blueGone) / 100.0F);
    destructionSurface.progress(ROW_RED, redGone / 100.0d);
    destructionSurface.progress(ROW_BLUE, blueGone / 100.0d);
    destructionSurface.refresh();
  }

  // ----- Teams / loadout / spawns ----------------------------------------------------------------

  private void assignTeams() {
    List<UUID> order = new ArrayList<>();
    for (PlayerAdapter player : online()) {
      order.add(player.uniqueId());
    }
    teamOf.putAll(TntWarTeams.assign(order, teamAssignmentsFromArgs()));
  }

  private void giveLoadoutAll() {
    for (PlayerAdapter player : online()) {
      giveLoadout(player);
    }
  }

  private void giveLoadout(PlayerAdapter player) {
    player.clearInventory();
    player.inventory().setStorageContents(TntWarLoadout.build(config, player.inventory().storageCapacity()));
  }

  private void teleportTeams() {
    spawnCursor.clear();
    for (PlayerAdapter player : online()) {
      WorldPosition spawn = nextTeamSpawn(team(player));
      if (spawn != null) {
        player.teleport(spawn);
      }
    }
  }

  /** The next spawn for {@code team}, advancing that team's cursor so its members fan out over all spawns. */
  private WorldPosition nextTeamSpawn(String team) {
    int slot = spawnCursor.merge(team, 1, Integer::sum) - 1;
    return teamSpawn(teamIndex(team), slot);
  }

  private static int teamIndex(String team) {
    return RED.equals(team) ? 0 : 1;
  }

  private void beginFight() {
    fighting = true;
  }

  private void resolveArena() {
    for (PlayerAdapter player : online()) {
      WorldAdapter world = player.world();
      if (world != null) {
        arena = world;
        arenaWorldName = world.name() == null ? "" : world.name();
        return;
      }
    }
  }

  private void loadMapDefinition() {
    loadBattleMap();
    // No configured (or no ready) map: procedurally build two opposing bases into the generated match
    // world so the war still has defined Red/Blue sides instead of every player piling onto world spawn.
    if (!battleMap.isReady() && config.generateMissingMap()) {
      generateMap();
    }
  }

  private void generateMap() {
    // Only build into a disposable leased world. With temp worlds disabled the match runs in-place
    // (possibly the lobby), which must never be reshaped.
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
    ArenaGen.TeamArena bases = ArenaGen.teamBases(world, center, config.generatedSeparation(),
        config.generatedBaseWidth(), config.generatedBaseHeight(),
        config.generatedBaseBlock(), config.generatedPlatformBlock());
    if (bases == null) {
      return;
    }
    BattleMap generated = new BattleMap("generated", world.name());
    generated.setCorner(0, 1, bases.redCornerA());
    generated.setCorner(0, 2, bases.redCornerB());
    generated.setCorner(1, 1, bases.blueCornerA());
    generated.setCorner(1, 2, bases.blueCornerB());
    generated.addSpawn(0, bases.redSpawn());
    generated.addSpawn(1, bases.blueSpawn());
    battleMap = generated;
    arena = world;
    arenaWorldName = world.name() == null ? "" : world.name();
  }

  private boolean sameTntTeam(PlayerAdapter first, PlayerAdapter second) {
    return first != null && second != null && team(first).equals(team(second));
  }

  private String team(PlayerAdapter player) {
    return player == null ? "" : teamOf.getOrDefault(player.uniqueId(), "");
  }

  private String displayTeam(String team) {
    return RED.equals(team) ? "<red>Red</red>" : "<blue>Blue</blue>";
  }

  // ----- Reconnect persistence -------------------------------------------------------------------

  @Override
  protected void writeModeData(Props data) {
    data.set("redLives", redLives).set("blueLives", blueLives).set("fighting", fighting);
    if (chosenMap != null) {
      data.set("mapId", chosenMap.id()).set("mapWorld", chosenMap.world());
    }
    StringBuilder roster = new StringBuilder();
    for (Map.Entry<UUID, String> entry : teamOf.entrySet()) {
      if (roster.length() > 0) {
        roster.append(';');
      }
      roster.append(entry.getKey()).append('=').append(entry.getValue());
    }
    data.set("teams", roster.toString());
  }

  @Override
  protected void restoreModeData(Props data) {
    redLives = data.getInt("redLives", config.livesPerTeam());
    blueLives = data.getInt("blueLives", config.livesPerTeam());
    fighting = data.getBoolean("fighting", true);
    String mapWorld = data.get("mapWorld");
    String mapId = data.get("mapId", "restored");
    if (mapWorld != null && !mapWorld.isBlank()) {
      Path templateFolder = gameContext.server().worlds().worldRoot().resolve(mapWorld);
      battleMap = BattleMapStore.loadOrImportTntWar(templateFolder, mapId);
    }
    teamOf.clear();
    String roster = data.get("teams", "");
    for (String pair : roster.split(";")) {
      int equals = pair.indexOf('=');
      if (equals <= 0) {
        continue;
      }
      try {
        teamOf.put(UUID.fromString(pair.substring(0, equals)), pair.substring(equals + 1).toLowerCase(Locale.ROOT));
      } catch (IllegalArgumentException ignored) {
        // Skip a malformed entry rather than aborting the whole restore.
      }
    }
    runLater(() -> {
      createDestructionBar();
    openDestructionSurface();
      runTimer(this::refresh, config.refreshTicks(), config.refreshTicks());
      if (config.lockDispensers()) {
        runTimer(this::restockDispensers, 40L, 40L);
      }
    }, 1L);
  }
}
