package com.sexidium.core.game.modes.minigames;

import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerDamageGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.GameEvents.PlayerRespawnGameEvent;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.modes.MinigameMode;
import com.sexidium.core.game.persist.Props;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BedrockPlayers;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

public final class FugitiveGame extends MinigameMode {
  // Item display names are passed to the inventory as literal MiniMessage (the give-time inventory write
  // has no per-player locale context, so we use the styled label directly, mirroring the map editor tools).
  private static final String TRACKER_ITEM_NAME = "<gold>Fugitive Tracker</gold>";
  private static final String DASH_ITEM_NAME = "<aqua><bold>Spear Dash</bold></aqua>";
  private static final String DASH_ITEM_ID = "feather";
  // Fixed hotbar placement for the items the game hands out on top of the configured kit.
  private static final int COMPASS_SLOT = 2;
  private static final int DASH_SLOT = 8;
  // Food handed to every participant (fugitive and hunters) so a long manhunt doesn't starve them out.
  private static final String FOOD_ITEM_ID = "bread";
  private static final int FOOD_COUNT = 32;
  // Normal free-cam flight speed for the head-start spectator camera (0..1; Bukkit default 0.1).
  private static final float SPECTATOR_FLY_SPEED = 0.1f;
  // Bedrock (mobile/console via Geyser) has no native spectator mode: Geyser emulates the free-cam and
  // honours the fly-speed attribute, but the emulated camera travels far slower per unit than Java's, so
  // at the normal 0.1 mobile players crawl. Give Bedrock spectators a higher speed so their camera keeps
  // pace with Java hunters during the head start; it is reset to the walking default when they drop back
  // into survival as hunters (see releaseHunt).
  private static final float BEDROCK_SPECTATOR_FLY_SPEED = 0.4f;

  private final Random random = new Random();
  private final Map<UUID, Long> lastDashMillis = new HashMap<>();
  private UUID fugitiveId;
  private WorldPosition fugitiveOrigin;
  private WorldPosition cachedStartSpawn;
  private boolean huntReleased;
  private boolean decided;
  private long headStartDeadlineMillis;
  private long huntDeadlineMillis;

  public FugitiveGame(GameContext gameContext) {
    this(gameContext, List.of());
  }

  public FugitiveGame(GameContext gameContext, List<String> args) {
    // args are accepted for registry compatibility but unused: the only win path is surviving the hunt.
    super(gameContext, "fugitive", "Fugitive", 2);
  }

  // Fugitive runs on a freshly generated world by default; an operator may instead list hand-built maps
  // under minigames.fugitive.maps to clone one per manhunt.
  @Override
  public String worldTemplate() {
    return chooseConfiguredMapTemplate();
  }

  // The manhunt owns its own respawns: a death mid-match keeps the player in the match world (hunters
  // are returned to the start; a fugitive death decides the match) instead of being ejected to the
  // lobby by the default GameManager respawn router. Releasing to the lobby must only happen at the
  // end of the match.
  @Override
  public boolean handlesOwnRespawn() {
    return true;
  }

  // The temp overworld pins its spawn at a fixed (0,64,0) to skip the slow startup safe-spawn search,
  // which lands players at sea level — underwater on the common ocean-at-origin seeds, and always the
  // identical spot so every match looks like the same map. Resolve a real dry-land surface near the
  // origin instead. The scan is deterministic, so every participant starts together on the same ground.
  @Override
  public WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter) {
    if (worldAdapter == null) {
      return null;
    }
    WorldPosition land = landSpawn(worldAdapter);
    return land != null ? land : worldAdapter.safeSpawnPosition();
  }

  private WorldPosition landSpawn(WorldAdapter world) {
    if (cachedStartSpawn != null && cachedStartSpawn.worldName() != null
        && cachedStartSpawn.worldName().equals(world.name())) {
      return cachedStartSpawn;
    }
    String name = world.name();
    int step = 24;
    int maxRadius = 3000;
    int sampleBudget = 500;
    for (int radius = 0; radius <= maxRadius && sampleBudget > 0; radius += step) {
      for (int[] point : ringPoints(radius, step)) {
        if (sampleBudget-- <= 0) {
          break;
        }
        int x = point[0];
        int z = point[1];
        int surfaceY = world.highestSolidBlockY(name, x, z);
        if (surfaceY == Integer.MIN_VALUE) {
          continue;
        }
        // highestSolidBlockY (getHighestBlockYAt) returns the topmost NON-AIR block, which over an ocean
        // is the water surface — so test that the top block is actual land, not liquid, before spawning.
        if (isLandSurface(world.blockTypeAt(new BlockPosition(name, x, surfaceY, z)))) {
          cachedStartSpawn = new WorldPosition(name, x + 0.5, surfaceY + 1, z + 0.5, 0.0f, 0.0f);
          return cachedStartSpawn;
        }
      }
    }
    return null;
  }

  // Square ring of sample points at the given block radius from the origin (just the origin for radius 0),
  // so the scan spirals outward and picks the dry land nearest spawn.
  private List<int[]> ringPoints(int radius, int step) {
    List<int[]> points = new ArrayList<>();
    if (radius == 0) {
      points.add(new int[] {0, 0});
      return points;
    }
    for (int delta = -radius; delta <= radius; delta += step) {
      points.add(new int[] {delta, -radius});
      points.add(new int[] {delta, radius});
      points.add(new int[] {-radius, delta});
      points.add(new int[] {radius, delta});
    }
    return points;
  }

  // A column is dry land when its topmost block is solid ground rather than a liquid (water/lava) or
  // submerged plant (kelp/seagrass/bubble column), so the player drops onto ground rather than the sea.
  private boolean isLandSurface(ItemKey top) {
    if (top == null) {
      return false;
    }
    String value = top.value() == null ? "" : top.value().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.equals("air") || value.equals("cave_air") || value.equals("void_air")) {
      return false;
    }
    return !(value.contains("water") || value.contains("lava") || value.contains("kelp")
        || value.contains("seagrass") || value.equals("bubble_column"));
  }

  @Override
  public void start(List<PlayerAdapter> participants) {
    huntReleased = false;
    decided = false;
    fugitiveId = null;
    fugitiveOrigin = null;
    lastDashMillis.clear();
    List<PlayerAdapter> selectable = participants == null ? List.of() : participants.stream()
        .filter(player -> player != null && player.online())
        .toList();
    if (!selectable.isEmpty()) {
      fugitiveId = selectable.get(random.nextInt(selectable.size())).uniqueId();
    }
    super.start(participants);
    List<PlayerAdapter> roster = online();
    if (roster.size() < 2) {
      announce(MessageKey.FUGITIVE_NO_HUNTERS);
      requestEnd();
      return;
    }
    PlayerAdapter fugitive = fugitive().orElse(roster.get(0));
    fugitiveId = fugitive.uniqueId();
    // The entry teleport (GameLauncher) runs through teleportAsync, so right now the fugitive's live
    // position() is still the PRE-teleport lobby spot. Using it here would later strand the released
    // hunters in the lobby, because moveHunterToStart teleports them to fugitiveOrigin. Use the
    // deterministic match-world land spawn that entrySpawn already resolved for every participant before
    // start() ran (cachedStartSpawn); only fall back to the live position when no temp world was leased
    // (no entry teleport happened, so the position is already correct).
    fugitiveOrigin = cachedStartSpawn != null ? cachedStartSpawn : fugitive.position();
    equipSoon(fugitive.uniqueId(), cfg().getString(configPath("fugitive-kit"), ""), false);
    assignRoles();
    holdHuntersInCinematic();
    int headStart = Math.max(0, cfg().getInt(configPath("head-start-seconds"), 150));
    if (headStart > 0) {
      headStartDeadlineMillis = System.currentTimeMillis() + headStart * 1000L;
      timerBar(MessageKey.FUGITIVE_HEADSTART_TIMER, headStart, BossBarColor.GREEN, this::releaseHunt);
    } else {
      releaseHunt();
    }
    runTimer(this::trackFugitive, 20L, 20L);
    if (cfg().getBoolean(configPath("cinematic.enabled"), true)) {
      runTimer(this::cinematicHint, 20L, 40L);
    }
  }

  @Override
  public void handle(GameEvent gameEvent) {
    super.handle(gameEvent);
    if (!isRunning() || decided) {
      return;
    }
    if (gameEvent instanceof PlayerRespawnGameEvent event && isParticipant(event.playerAdapter())) {
      if (isFugitive(event.playerAdapter())) {
        onFugitiveRespawn(event.playerAdapter());
      } else {
        respawnHunter(event.playerAdapter());
      }
    } else if (gameEvent instanceof PlayerInteractGameEvent event
        && (event.actionType() == PlayerInteractGameEvent.ActionType.RIGHT_CLICK
            || event.actionType() == PlayerInteractGameEvent.ActionType.LEFT_CLICK)
        && isParticipant(event.playerAdapter())
        && usedDashItem(event)
        && (isFugitive(event.playerAdapter()) || huntReleased)) {
      // Dash on a right-click (use) OR a left-click / attack swing with the Spear Dash item, so it fires
      // reliably in mid-air (the arm-swing path is the reliable air signal but carries no item, so we
      // also check the held item). Hunters are frozen spectators during the head start, so only the
      // fugitive may dash until the hunt is on.
      dash(event.playerAdapter());
    }
  }

  @Override
  protected void announceStarted() {
    announce(MessageKey.FUGITIVE_START,
        MessageArg.text("fugitive", fugitive().map(PlayerAdapter::name).orElse("?")),
        MessageArg.text("hunters", Math.max(0, online().size() - 1)),
        MessageArg.localized("objective", LocalizedText.of(MessageKey.FUGITIVE_OBJECTIVE_SURVIVE)));
  }

  @Override
  protected void handleDamage(PlayerDamageGameEvent event) {
    PlayerAdapter victim = event.victim();
    if (!isParticipant(victim)) {
      return;
    }
    if (!tunableBoolean("friendly-fire", "friendly-fire", false)
        && event.attacker() != null
        && isParticipant(event.attacker())
        && !isFugitive(victim)
        && !isFugitive(event.attacker())) {
      event.setCancelled(true);
      return;
    }
    if (!isLethal(victim, event.finalDamage())) {
      return;
    }
    event.setCancelled(true);
    if (isFugitive(victim)) {
      if (!huntReleased) {
        heal(victim);
        return;
      }
      huntersWin(victim);
      return;
    }
    heal(victim);
    respawnHunter(victim);
    if (event.attacker() != null && isParticipant(event.attacker())) {
      awardKill(event.attacker());
    }
  }

  @Override
  public boolean allowsWorldChange(PlayerAdapter playerAdapter, String fromWorld, String toWorld) {
    return isParticipant(playerAdapter);
  }

  @Override
  public boolean isReconnectable() {
    return true;
  }

  @Override
  public void onParticipantDisconnect(PlayerAdapter playerAdapter) {
    if (isFugitive(playerAdapter) && !decided) {
      pause();
      announce(MessageKey.FUGITIVE_STANDBY_WAITING, MessageArg.text("fugitive", playerAdapter.name()));
    }
  }

  @Override
  public void onParticipantRejoin(PlayerAdapter playerAdapter) {
    // Re-apply saved state (after a restart) and re-show overlays via AbstractGame.
    super.onParticipantRejoin(playerAdapter);
    if (isFugitive(playerAdapter) && isPaused()) {
      resume();
      announce(MessageKey.FUGITIVE_STANDBY_RESUMED, MessageArg.text("fugitive", playerAdapter.name()));
    }
  }

  @Override
  protected String roleOf(UUID playerId) {
    return playerId != null && playerId.equals(fugitiveId) ? "fugitive" : "hunter";
  }

  @Override
  protected void writeModeData(Props data) {
    if (fugitiveId != null) {
      data.set("fugitiveId", fugitiveId.toString());
    }
    data.set("huntReleased", huntReleased);
    data.set("decided", decided);
    data.set("headStartDeadline", headStartDeadlineMillis);
    data.set("huntDeadline", huntDeadlineMillis);
    if (fugitiveOrigin != null) {
      data.set("origin.world", fugitiveOrigin.worldName() == null ? "" : fugitiveOrigin.worldName());
      data.set("origin.x", Double.toString(fugitiveOrigin.coordinateX()));
      data.set("origin.y", Double.toString(fugitiveOrigin.coordinateY()));
      data.set("origin.z", Double.toString(fugitiveOrigin.coordinateZ()));
      data.set("origin.yaw", Float.toString(fugitiveOrigin.yaw()));
      data.set("origin.pitch", Float.toString(fugitiveOrigin.pitch()));
    }
  }

  @Override
  protected void restoreModeData(Props data) {
    String storedFugitive = data.get("fugitiveId");
    if (storedFugitive != null) {
      try {
        fugitiveId = UUID.fromString(storedFugitive);
      } catch (IllegalArgumentException ignored) {
        fugitiveId = null;
      }
    }
    huntReleased = data.getBoolean("huntReleased", false);
    decided = data.getBoolean("decided", false);
    headStartDeadlineMillis = data.getLong("headStartDeadline", 0L);
    huntDeadlineMillis = data.getLong("huntDeadline", 0L);
    fugitiveOrigin = readOrigin(data);
    if (decided) {
      return;
    }
    long now = System.currentTimeMillis();
    if (!huntReleased) {
      int remainingHeadStart = (int) Math.max(0L, (headStartDeadlineMillis - now) / 1000L);
      if (remainingHeadStart > 0) {
        timerBar(MessageKey.FUGITIVE_HEADSTART_TIMER, remainingHeadStart, BossBarColor.GREEN, this::releaseHunt);
        holdHuntersInCinematic();
        if (cfg().getBoolean(configPath("cinematic.enabled"), true)) {
          runTimer(this::cinematicHint, 20L, 40L);
        }
      } else {
        releaseHunt();
      }
    } else {
      int remainingHunt = (int) Math.max(1L, (huntDeadlineMillis - now) / 1000L);
      timerBar(MessageKey.FUGITIVE_HUNT_TIMER, remainingHunt, BossBarColor.RED, this::fugitiveSurvived);
    }
    runTimer(this::trackFugitive, 20L, 20L);
  }

  private WorldPosition readOrigin(Props data) {
    String world = data.get("origin.world");
    if (world == null || world.isBlank()) {
      return null;
    }
    return new WorldPosition(
        world,
        parseDouble(data.get("origin.x")),
        parseDouble(data.get("origin.y")),
        parseDouble(data.get("origin.z")),
        parseFloat(data.get("origin.yaw")),
        parseFloat(data.get("origin.pitch")));
  }

  private double parseDouble(String value) {
    try {
      return value == null ? 0.0 : Double.parseDouble(value);
    } catch (NumberFormatException ignored) {
      return 0.0;
    }
  }

  private float parseFloat(String value) {
    try {
      return value == null ? 0.0f : Float.parseFloat(value);
    } catch (NumberFormatException ignored) {
      return 0.0f;
    }
  }

  @Override
  public void stop(LocalizedText reason) {
    if (!decided && isRunning()) {
      fugitiveSurvived();
    }
    super.stop(reason);
  }

  private void releaseHunt() {
    if (!isRunning() || decided) {
      return;
    }
    huntReleased = true;
    for (PlayerAdapter hunter : online()) {
      if (isFugitive(hunter)) {
        continue;
      }
      // Leave the cinematic: spectators become survival hunters again, with a clean slate of statuses
      // (the head-start boss bar closed itself on expiry; clear any lingering effects) before kitting.
      prepareSurvival(hunter);
      // Undo any Bedrock spectator fly-speed boost so the hunter walks at normal speed once back in
      // survival (no-op for Java hunters, who were never boosted).
      hunter.setFlySpeed(SPECTATOR_FLY_SPEED);
      hunter.clearPotionEffects();
      if (cfg().getBoolean(configPath("clear-hunter-inventory"), true)) {
        hunter.clearInventory();
      }
      moveHunterToStart(hunter);
      // Equip AFTER the teleport, on a short delay: a kit written in the same tick as a teleport/gamemode
      // change is sometimes silently dropped by the client inventory resync.
      equipSoon(hunter.uniqueId(), cfg().getString(configPath("hunter-kit"), ""), true);
    }
    announce(MessageKey.FUGITIVE_HUNT_BEGINS);
    popupAll(PopupType.OBJECTIVE, MessageKey.FUGITIVE_HUNT_TITLE);
    int huntSeconds = Math.max(1, cfg().getInt(configPath("hunt-seconds"), 1200));
    huntDeadlineMillis = System.currentTimeMillis() + huntSeconds * 1000L;
    timerBar(MessageKey.FUGITIVE_HUNT_TIMER, huntSeconds, BossBarColor.RED, this::fugitiveSurvived);
  }

  private void trackFugitive() {
    if (!isRunning() || !huntReleased || decided) {
      return;
    }
    Optional<PlayerAdapter> fugitive = fugitive();
    if (fugitive.isEmpty() || fugitive.get().position() == null) {
      return;
    }
    WorldPosition target = fugitive.get().position();
    // Aim every hunter's compass at the fugitive. No on-screen distance/other-world text — the compass
    // is the tracker; the per-second message was debug spam.
    for (PlayerAdapter hunter : online()) {
      if (!isFugitive(hunter)) {
        hunter.setCompassTarget(target);
      }
    }
  }

  private void cinematicHint() {
    if (!isRunning() || huntReleased || decided) {
      return;
    }
    // The hunters are held in spectator mode for the head start (see holdHuntersInCinematic); here we
    // keep their camera pointed at the fugitive and re-assert the cinematic in case a status reset
    // (e.g. a respawn) knocked them out of it. The "Hunter" role title is shown once at assignRoles —
    // not re-popped here, so it doesn't flash back onto the screen every couple of seconds.
    fugitive().ifPresent(fugitive -> {
      for (PlayerAdapter hunter : online()) {
        if (!isFugitive(hunter)) {
          enterCinematic(hunter, fugitive);
        }
      }
    });
  }

  /**
   * Freezes every hunter into a spectator "cinematic" for the head start so they cannot move, attack or
   * interfere while the fugitive flees. They leave spectator and become survival hunters in
   * {@link #releaseHunt()} when the hunt begins. Honours the {@code cinematic.enabled} switch.
   */
  private void holdHuntersInCinematic() {
    if (!cfg().getBoolean(configPath("cinematic.enabled"), true)) {
      return;
    }
    fugitive().ifPresent(fugitive -> {
      for (PlayerAdapter hunter : online()) {
        if (!isFugitive(hunter)) {
          enterCinematic(hunter, fugitive);
        }
      }
    });
  }

  // Bedrock spectators get a faster free-cam (their emulated camera crawls at the Java-normal speed);
  // Java spectators keep the vanilla 0.1.
  private float spectatorFlySpeed(PlayerAdapter player) {
    return BedrockPlayers.isBedrockUuid(player.uniqueId())
        ? BEDROCK_SPECTATOR_FLY_SPEED
        : SPECTATOR_FLY_SPEED;
  }

  private void enterCinematic(PlayerAdapter hunter, PlayerAdapter fugitive) {
    // Only (re-)enter spectator when the hunter isn't already in it. cinematicHint calls this every 2s as
    // a safety net; re-issuing setGameMode each time reset the client's spectator fly speed to the slow
    // base and stopped the player scrolling it up — which is what made the camera crawl. Set a normal
    // free-cam speed once on entry.
    if (hunter.gameMode() != GameModeType.SPECTATOR) {
      hunter.setGameMode(GameModeType.SPECTATOR);
      hunter.setFlySpeed(spectatorFlySpeed(hunter));
    }
    hunter.setCompassTarget(fugitive.position());
  }

  /**
   * Equips a participant a couple of ticks after their entry/respawn teleport, re-resolving them by id so
   * the inventory write lands AFTER the world change settles. Writing a kit in the same tick as the
   * teleport/gamemode change is the cause of items intermittently not being assigned. Hunters also get
   * the tracking compass here; everyone gets the Spear Dash item (sneak to dash forward).
   */
  private void equipSoon(UUID playerId, String kitName, boolean hunter) {
    runLater(() -> {
      if (!isRunning() || decided) {
        return;
      }
      gameContext.server().player(playerId).filter(PlayerAdapter::online).ifPresent(player -> {
        // The fugitive never passes through releaseHunt's inventory wipe, so clear any leftover lobby
        // items first; otherwise they linger in the hotbar and the kit lands scattered among them.
        // (Hunters are already cleared in releaseHunt, gated by clear-hunter-inventory.)
        if (!hunter) {
          player.clearInventory();
        }
        // Kit first (it pins the sword/pickaxe/blocks/pearls to their configured slots), then drop the
        // tracking compass and Spear Dash item into their fixed slots on top.
        giveKit(player, kitName);
        if (hunter && cfg().getBoolean(configPath("give-tracking-compass"), true)) {
          player.inventory().setSlot(COMPASS_SLOT, new ItemStackData(ItemKey.minecraft("compass"), 1,
              Map.of("name", TRACKER_ITEM_NAME)));
        }
        player.inventory().setSlot(DASH_SLOT, new ItemStackData(ItemKey.minecraft(DASH_ITEM_ID), 1,
            Map.of("name", DASH_ITEM_NAME)));
        // Drop in a stack of bread so nobody starves over a 20-minute hunt.
        player.inventory().add(new ItemStackData(ItemKey.minecraft(FOOD_ITEM_ID), FOOD_COUNT, Map.of()));
      });
    }, 2L);
  }

  /**
   * A real (uncancelled) fugitive death: once the hunt is on it counts as a capture and the hunters win;
   * during the head start the fugitive is simply healed and returned to their origin (kept in the match,
   * never released to the lobby).
   */
  private void onFugitiveRespawn(PlayerAdapter fugitive) {
    if (huntReleased) {
      huntersWin(fugitive);
      return;
    }
    heal(fugitive);
    if (fugitiveOrigin != null) {
      fugitive.teleport(fugitiveOrigin);
    }
    restorePlayerUi(fugitive);
  }

  private void dash(PlayerAdapter player) {
    long cooldownMillis = Math.max(0, cfg().getInt(configPath("dash-cooldown-seconds"), 8)) * 1000L;
    long now = System.currentTimeMillis();
    long last = lastDashMillis.getOrDefault(player.uniqueId(), 0L);
    if (now - last < cooldownMillis) {
      // A single left-click surfaces as BOTH an interact and an arm-swing in the same tick; suppress the
      // cooldown message for that immediate duplicate, only reporting a genuine later retry.
      if (now - last > 200L) {
        gameContext.server().messages().send(player, LocalizedText.of(MessageKey.FUGITIVE_DASH_COOLDOWN, MessageArg.text("seconds", Math.max(1, (cooldownMillis - (now - last) + 999) / 1000))));
      }
      return;
    }
    lastDashMillis.put(player.uniqueId(), now);
    // Show the native item-cooldown sweep on the Spear Dash item so the player can see the time left.
    player.setItemCooldown(ItemKey.minecraft(DASH_ITEM_ID), (int) (cooldownMillis / 50L));
    WorldPosition position = player.position();
    if (position == null) {
      return;
    }
    double yaw = Math.toRadians(position.yaw());
    double pitch = Math.toRadians(position.pitch());
    double strength = cfg().getDouble(configPath("dash-strength"), 2.5);
    double x = -Math.sin(yaw) * Math.cos(pitch) * strength;
    double y = Math.max(0.15, -Math.sin(pitch) * strength + 0.25);
    double z = Math.cos(yaw) * Math.cos(pitch) * strength;
    // launch (absolute) rather than setVelocity (additive) so the dash overrides fall velocity and works
    // in mid-air, not only off the ground.
    player.launch(x, y, z);
  }

  private void respawnHunter(PlayerAdapter hunter) {
    heal(hunter);
    moveHunterToStart(hunter);
    // The hunter stays in the manhunt, so restore the hunt-timer boss bar that the generic
    // respawn/reset path hid for them.
    restorePlayerUi(hunter);
    gameContext.server().messages().send(hunter, LocalizedText.of(MessageKey.FUGITIVE_HUNTER_RESPAWN));
  }

  private void moveHunterToStart(PlayerAdapter hunter) {
    if ("world-spawn".equalsIgnoreCase(cfg().getString(configPath("hunter-start"), "fugitive-origin"))) {
      if (hunter.world() != null && hunter.world().spawnPosition() != null) {
        hunter.teleport(hunter.world().spawnPosition());
      }
      return;
    }
    if (fugitiveOrigin != null) {
      hunter.teleport(fugitiveOrigin);
    }
  }

  private void assignRoles() {
    for (PlayerAdapter player : online()) {
      if (isFugitive(player)) {
        popup(player, PopupType.OBJECTIVE, MessageKey.FUGITIVE_ROLE_TITLE);
        gameContext.server().messages().send(player, LocalizedText.of(MessageKey.FUGITIVE_ROLE_SUBTITLE,
            MessageArg.localized("objective", LocalizedText.of(MessageKey.FUGITIVE_OBJECTIVE_SURVIVE))));
      } else {
        popup(player, PopupType.OBJECTIVE, MessageKey.FUGITIVE_HUNTER_ROLE_TITLE);
        gameContext.server().messages().send(player, LocalizedText.of(MessageKey.FUGITIVE_HUNTER_ROLE_SUBTITLE));
      }
    }
  }

  private void huntersWin(PlayerAdapter fugitive) {
    decided = true;
    announce(MessageKey.FUGITIVE_CAUGHT, MessageArg.text("fugitive", fugitive.name()));
    popupAll(PopupType.WIN, MessageKey.FUGITIVE_HUNTERS_WIN_TITLE, MessageArg.text("fugitive", fugitive.name()));
    for (PlayerAdapter hunter : remainingOnlineParticipants()) {
      if (!isFugitive(hunter)) {
        awardWin(hunter);
      }
    }
    requestEnd();
  }

  private void fugitiveSurvived() {
    if (decided) {
      return;
    }
    fugitive().ifPresent(fugitive -> {
      decided = true;
      announce(MessageKey.FUGITIVE_SURVIVED, MessageArg.text("fugitive", fugitive.name()));
      popupAll(PopupType.WIN, MessageKey.FUGITIVE_WIN_TITLE, MessageArg.text("fugitive", fugitive.name()));
      awardWin(fugitive);
      requestEnd();
    });
  }

  private Optional<PlayerAdapter> fugitive() {
    return fugitiveId == null ? Optional.empty() : gameContext.server().player(fugitiveId).filter(PlayerAdapter::online);
  }

  private boolean isFugitive(PlayerAdapter player) {
    return player != null && player.uniqueId().equals(fugitiveId);
  }

  private boolean isDashItem(ItemKey itemKey) {
    return itemKey != null && DASH_ITEM_ID.equals(itemKey.value());
  }

  // The interact event carries the used item for clicks, but the reliable air-swing signal carries no
  // item — so fall back to the player's main-hand item to recognise a swing of the Spear Dash feather.
  private boolean usedDashItem(PlayerInteractGameEvent event) {
    return isDashItem(event.itemKey()) || isDashItem(event.playerAdapter().heldItem());
  }
}
