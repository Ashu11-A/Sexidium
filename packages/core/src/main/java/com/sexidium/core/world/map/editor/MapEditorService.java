package com.sexidium.core.world.map.editor;

import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.team.TeamColor;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.map.BattleMap;
import com.sexidium.core.world.map.BattleMapStore;
import com.sexidium.core.world.map.Cuboid;
import com.sexidium.core.world.map.RegionRenderer;
import com.sexidium.core.world.map.TeamZone;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Drives the in-world battle-map editor / region debugger. An admin enters a session for a (mode, map)
 * via {@code /sx admin map edit}; the service loads the map's template world, switches the admin to <b>Creative</b>
 * (stashing their inventory) with a five-tool hotbar, and runs a repeating loop that renders every team's
 * region as a coloured wireframe ({@link RegionRenderer}).
 *
 * <p>Hotbar tools (identified by item id while the session is open): golden axe — left/right-click a block
 * = corner 1/2 of the focused team (WorldEdit semantics); iron pick — strike a block to delete its box;
 * clock — undo the last change; lime dye — confirm (save + exit); red dye — cancel (exit without saving).
 * Each mutating edit snapshots the map first so undo can step back. While a tool is held the editor vetoes
 * block breaking (via {@code GameEventRouter}) so Creative stays non-destructive, yet a normal held item
 * still builds.</p>
 *
 * <p>Interact (and block-break) events reach this service through hooks in {@code GameEventRouter} (they
 * otherwise only reach active matches), so the editor works outside of any running game. Sessions are
 * keyed by admin UUID.</p>
 */
public final class MapEditorService {
  // The editor hotbar. Each tool is a plain vanilla item identified by id while a session is open; none is
  // placeable, and the four non-axe tools fire on right-click so they never collide with axe corner picks.
  /** Corner selector (WorldEdit-style): left-click = corner 1, right-click = corner 2 of the focused team. */
  private static final String AXE_ITEM = "golden_axe";
  /** Strike a block inside a region (left-click) to delete that team's box. */
  private static final String DELETE_ITEM = "iron_pickaxe";
  /** Undo the last change (right-click). */
  private static final String UNDO_ITEM = "clock";
  /** Confirm: save the map and leave (right-click). */
  private static final String CONFIRM_ITEM = "lime_dye";
  /** Cancel: leave WITHOUT saving (right-click). */
  private static final String CANCEL_ITEM = "red_dye";
  /** Switch which team the axe edits (right-click cycles through the configured teams). */
  private static final String TEAM_ITEM = "name_tag";
  /** Set a spawn for the focused team at your exact position + facing (right-click). */
  private static final String SPAWN_ITEM = "ender_pearl";
  /** Every tool id, so block breaking is suppressed only while a tool is held (creative building still works). */
  private static final Set<String> TOOL_ITEMS =
      Set.of(AXE_ITEM, DELETE_ITEM, UNDO_ITEM, CONFIRM_ITEM, CANCEL_ITEM, TEAM_ITEM, SPAWN_ITEM);
  private static final long RENDER_PERIOD_TICKS = 10L;
  // Delay before unloading the edit clone on exit, so the admin's teleport to the (already-loaded) lobby
  // settles first — otherwise the unload sees a player and fails, orphaning the world. Kept short so a
  // Confirm's template write finishes well before an admin can manually re-run /sx admin map edit on the same map.
  private static final long UNLOAD_DELAY_TICKS = 5L;

  private final ServerAdapter server;
  private final Map<UUID, MapEditSession> sessions = new ConcurrentHashMap<>();

  public MapEditorService(ServerAdapter server) {
    this.server = server;
  }

  /** Outcome of an editor command, carrying a message to relay to the admin. */
  public record Result(boolean ok, String message) {
    static Result ok(String message) {
      return new Result(true, message);
    }

    static Result fail(String message) {
      return new Result(false, message);
    }
  }

  public boolean hasSession(UUID adminId) {
    return sessions.containsKey(adminId);
  }

  /**
   * Opens an edit session: <b>clones the real template map</b> ({@code worldName}) into a disposable edit
   * world so the admin actually loads the built map (the old code reacquired by name, which generated a
   * fresh vanilla world instead of loading the asset), switches them to Creative with the editor hotbar,
   * teleports them in, and starts the region debugger. The battle-map sidecar is read from — and saved
   * back to — the TEMPLATE folder, so edits persist to the source map, not the throwaway clone.
   *
   * <p>The clone is acquired asynchronously, so this returns an immediate ack and the session is wired up
   * in the callback.</p>
   */
  public Result enter(PlayerAdapter admin, String modeId, String mapId, String worldName) {
    if (admin == null) {
      return Result.fail("Only a player can enter the map editor.");
    }
    UUID id = admin.uniqueId();
    if (sessions.containsKey(id)) {
      return Result.fail("You are already editing a map. Use /sx admin map edit exit first.");
    }
    if (worldName == null || worldName.isBlank()) {
      return Result.fail("Unknown map for mode '" + modeId + "'.");
    }
    Path templateFolder = server.worlds().worldRoot().resolve(worldName);
    BattleMap map = BattleMapStore.loadOrImportTntWar(templateFolder, mapId);
    // Seed the first two teams so the admin can immediately focus/define red and blue.
    map.ensureZone(0, TeamColor.RED);
    map.ensureZone(1, TeamColor.BLUE);
    GameModeType priorMode = admin.gameMode();

    // Clone the actual built map (same path a match uses) rather than generating a blank world. STRICT:
    // if the template can't be cloned we abort instead of dropping the admin into a vanilla world they
    // would then save back over the real map.
    server.worlds().acquireCloneStrict(worldName, List.of(admin),
        lease -> startSession(admin, modeId, mapId, worldName, lease, map, priorMode),
        () -> admin.sendActionBar("<red>Could not load map '" + worldName + "' — the template is missing or "
            + "failed to clone. Nothing was changed.</red>"));
    return Result.ok("Loading '" + mapId + "' (" + modeId + ")… you'll drop into the map in Creative shortly.");
  }

  /** Async callback once the clone world is ready: builds the session, drops the admin in, starts rendering. */
  private void startSession(PlayerAdapter admin, String modeId, String mapId, String worldName,
      WorldLease lease, BattleMap map, GameModeType priorMode) {
    UUID id = admin.uniqueId();
    WorldAdapter world = lease == null ? null : lease.world();
    if (world == null || !admin.online() || sessions.containsKey(id)) {
      if (lease != null) {
        lease.close();
      }
      return;
    }
    map.setWorld(world.name());
    MapEditSession session = new MapEditSession(id, modeId, mapId, worldName, world, lease, map, priorMode);
    sessions.put(id, session);

    WorldPosition spawn = world.safeSpawnPosition();
    if (spawn == null) {
      spawn = world.spawnPosition();
    }
    if (spawn != null) {
      admin.teleport(spawn);
    }
    // Force Creative, then re-assert it over the next ~2.5s. A cross-world teleport into a freshly created,
    // Multiverse-imported world fires asynchronously and MV enforces THAT world's own game mode (Survival)
    // on entry — stomping a mode we set before/at the teleport. Re-asserting a few times wins that race
    // without coupling the core editor to the Multiverse API.
    admin.setGameMode(GameModeType.CREATIVE);
    for (long delay : new long[]{1L, 5L, 20L, 50L}) {
      server.scheduler().runLater(() -> {
        if (sessions.containsKey(id) && admin.online()) {
          admin.setGameMode(GameModeType.CREATIVE);
        }
      }, delay);
    }
    giveTools(admin, session);
    session.setRenderTask(server.scheduler().runTimer(() -> render(session), RENDER_PERIOD_TICKS, RENDER_PERIOD_TICKS));
    admin.sendActionBar("<green>Editing '" + mapId + "' in Creative — axe = corners, name tag = switch team, ender pearl = set spawn.</green>");
  }

  /** Sets the focused team (the one the axe edits). Auto-creates the team zone with its palette colour. */
  public Result focusTeam(UUID adminId, int teamIndex) {
    MapEditSession session = sessions.get(adminId);
    if (session == null) {
      return notEditing();
    }
    if (teamIndex < 0 || teamIndex >= TeamColor.maxTeams()) {
      return Result.fail("Team index must be 0.." + (TeamColor.maxTeams() - 1) + ".");
    }
    session.setCurrentTeam(teamIndex);
    TeamColor color = session.battleMap().ensureZone(teamIndex).color();
    return Result.ok("Now editing team " + teamIndex + " (" + color.displayName() + ").");
  }

  /** Adds a spawn for the focused team at the admin's current position (the {@code /sx admin map edit spawn} path). */
  public Result addSpawn(UUID adminId) {
    MapEditSession session = sessions.get(adminId);
    if (session == null) {
      return notEditing();
    }
    PlayerAdapter admin = server.player(adminId).filter(PlayerAdapter::online).orElse(null);
    if (admin == null) {
      return Result.fail("You must be online to set a spawn.");
    }
    return recordSpawn(session, admin);
  }

  /**
   * Captures the admin's exact position + facing as a spawn for the focused team. Shared by the
   * {@code /sx admin map edit spawn} command and the spawn hotbar tool. The stored {@link WorldPosition} carries
   * yaw/pitch, so each team's players spawn looking the way the admin was standing.
   */
  private Result recordSpawn(MapEditSession session, PlayerAdapter admin) {
    WorldPosition position = admin.position();
    if (position == null) {
      return Result.fail("Could not read your position.");
    }
    session.pushUndo();
    session.battleMap().addSpawn(session.currentTeam(), position);
    int count = session.battleMap().ensureZone(session.currentTeam()).spawns().size();
    return Result.ok("Set spawn #" + count + " for team " + session.currentTeam() + " (facing "
        + Math.round(position.yaw()) + "°).");
  }

  /**
   * Routes an editor hotbar click while a session is open. Returns true (and cancels the event) when a tool
   * consumed the click. Axe: left/right = corner 1/2. Iron pick: left-strike a block deletes its box. Clock:
   * undo. Lime dye: confirm (save + exit). Red dye: cancel (exit, no save). The non-axe tools act on
   * right-click so they never collide with the axe's corner picks.
   */
  public boolean onInteract(PlayerInteractGameEvent event) {
    if (event == null || event.playerAdapter() == null) {
      return false;
    }
    MapEditSession session = sessions.get(event.playerAdapter().uniqueId());
    if (session == null) {
      return false;
    }
    ItemKey item = event.itemKey();
    if (item == null || item.value() == null || !TOOL_ITEMS.contains(item.value())) {
      return false;
    }
    PlayerAdapter admin = event.playerAdapter();
    boolean left = event.actionType() == PlayerInteractGameEvent.ActionType.LEFT_CLICK;
    boolean right = event.actionType() == PlayerInteractGameEvent.ActionType.RIGHT_CLICK;
    return switch (item.value()) {
      case AXE_ITEM -> handleCorner(session, admin, event, left, right);
      case DELETE_ITEM -> handleDelete(session, admin, event, left);
      case UNDO_ITEM -> right && consume(event, () -> handleUndo(session, admin));
      case CONFIRM_ITEM -> right && consume(event, () -> confirm(session, admin));
      case CANCEL_ITEM -> right && consume(event, () -> cancel(admin));
      case TEAM_ITEM -> right && consume(event, () -> cycleTeam(session, admin));
      case SPAWN_ITEM -> right && consume(event, () -> admin.sendActionBar(colour(recordSpawn(session, admin))));
      default -> false;
    };
  }

  /** Wraps a {@link Result} message in green/red for an action-bar reply. */
  private static String colour(Result result) {
    return (result.ok() ? "<green>" : "<red>") + result.message() + (result.ok() ? "</green>" : "</red>");
  }

  /** Advances the focused team to the next configured zone (wrapping), and recolours the name-tag tool. */
  private void cycleTeam(MapEditSession session, PlayerAdapter admin) {
    int teams = Math.max(1, session.battleMap().teamCount());
    int next = (session.currentTeam() + 1) % teams;
    session.setCurrentTeam(next);
    TeamColor color = session.battleMap().ensureZone(next).color();
    admin.inventory().setSlot(TEAM_SLOT, teamTool(next, color));
    admin.sendActionBar("<" + color.miniMessageColor() + ">Now editing team " + next + " (" + color.displayName() + ")</"
        + color.miniMessageColor() + ">");
  }

  /** Cancels the interact, runs the action, and reports it consumed. Keeps the right-click tools terse. */
  private static boolean consume(PlayerInteractGameEvent event, Runnable action) {
    event.setCancelled(true);
    action.run();
    return true;
  }

  private boolean handleCorner(MapEditSession session, PlayerAdapter admin, PlayerInteractGameEvent event,
      boolean left, boolean right) {
    BlockPosition block = event.blockPosition();
    if (block == null) {
      // A swing at the air with the axe: nothing to pick, but consume it so it never breaks/places.
      event.setCancelled(true);
      return true;
    }
    int corner = left ? 1 : right ? 2 : 0;
    if (corner == 0) {
      return false;
    }
    session.pushUndo();
    session.battleMap().setCorner(session.currentTeam(), corner, block);
    event.setCancelled(true);
    admin.sendActionBar("<gray>Team " + session.currentTeam() + " corner " + corner + " set at "
        + block.blockX() + ", " + block.blockY() + ", " + block.blockZ() + "</gray>");
    return true;
  }

  private boolean handleDelete(MapEditSession session, PlayerAdapter admin, PlayerInteractGameEvent event,
      boolean left) {
    event.setCancelled(true);
    BlockPosition block = event.blockPosition();
    if (!left || block == null) {
      return true; // hold the strike for a real block left-click; consume the rest
    }
    TeamZone target = null;
    for (TeamZone zone : session.battleMap().zones()) {
      Cuboid region = zone.region();
      if (region != null && region.contains(block.blockX(), block.blockY(), block.blockZ())) {
        target = zone;
        break;
      }
    }
    if (target == null) {
      admin.sendActionBar("<gray>No team box here to delete.</gray>");
      return true;
    }
    session.pushUndo();
    target.setCorner(1, null);
    target.setCorner(2, null);
    admin.sendActionBar("<red>Deleted team " + target.index() + "'s box.</red>");
    return true;
  }

  private void handleUndo(MapEditSession session, PlayerAdapter admin) {
    admin.sendActionBar(session.undo() ? "<yellow>Undid the last change.</yellow>" : "<gray>Nothing to undo.</gray>");
  }

  private void confirm(MapEditSession session, PlayerAdapter admin) {
    // Write the team zones/spawns sidecar now (in-memory state), then tear down WITH world persistence:
    // the edit clone is unloaded-with-save and its blocks copied onto the base template (reliable, unlike
    // saving the still-loaded world whose chunk writes are async).
    Result sidecar = saveSidecar(session);
    admin.sendActionBar(colour(sidecar) + " <gray>Baking your block edits into the map…</gray>");
    sessions.remove(session.adminId());
    teardown(session, true);
  }

  private void cancel(PlayerAdapter admin) {
    MapEditSession session = sessions.remove(admin.uniqueId());
    if (session != null) {
      teardown(session, false);
    }
    admin.sendActionBar("<red>Left the map editor (changes not saved).</red>");
  }

  /**
   * Whether a block break by {@code player} should be vetoed: true only while they have an editor session
   * AND are holding one of the editor tools, so the tools stay non-destructive in Creative while ordinary
   * block building (holding a normal item) still works. Called by {@code GameEventRouter}.
   */
  public boolean shouldCancelBlockBreak(PlayerAdapter player) {
    if (player == null || !sessions.containsKey(player.uniqueId())) {
      return false;
    }
    ItemKey held = player.heldItem();
    return held != null && held.value() != null && TOOL_ITEMS.contains(held.value());
  }

  /**
   * Saves the team zones/spawns sidecar ({@code /sx admin map edit save}). Block edits to the world are NOT
   * persisted here — they are baked into the base template only by Confirm (which unloads-with-save), since
   * the edit world's chunk writes are async and copying its region mid-session would miss recent edits.
   */
  public Result save(UUID adminId) {
    MapEditSession session = sessions.get(adminId);
    if (session == null) {
      return notEditing();
    }
    Result sidecar = saveSidecar(session);
    return sidecar.ok()
        ? Result.ok(sidecar.message() + " Use the Confirm tool to bake block edits into the map.")
        : sidecar;
  }

  /** Writes the in-memory team zones/spawns to the {@code sexidium-battlemap.yml} sidecar in the template. */
  private Result saveSidecar(MapEditSession session) {
    try {
      BattleMapStore.save(server.worlds().worldRoot().resolve(session.worldName()), session.battleMap());
    } catch (IOException exception) {
      server.logger().warning("Failed to save battle map " + session.mapId(), exception);
      return Result.fail("Failed to save the map: " + exception.getMessage());
    }
    boolean ready = session.battleMap().isReady();
    return Result.ok("Saved zones for '" + session.mapId() + "'."
        + (ready ? "" : " (warning: not every team has a region + spawn yet)"));
  }

  /** A short status line per configured team (corners set, spawn count). */
  public Result status(UUID adminId) {
    MapEditSession session = sessions.get(adminId);
    if (session == null) {
      return notEditing();
    }
    StringBuilder builder = new StringBuilder("Editing '" + session.mapId() + "' (" + session.modeId() + "):");
    for (TeamZone zone : session.battleMap().zones()) {
      builder.append("\n<gray>- team ").append(zone.index()).append(" (").append(zone.color().displayName())
          .append("): region ").append(zone.region() != null ? "set" : "unset")
          .append(", spawns ").append(zone.spawns().size()).append("</gray>");
    }
    return Result.ok(builder.toString());
  }

  /** Ends the session WITHOUT saving block edits ({@code /sx admin map edit exit}, disconnect). */
  public void exit(UUID adminId) {
    MapEditSession session = sessions.remove(adminId);
    if (session != null) {
      teardown(session, false);
    }
  }

  /**
   * Restores the admin (inventory/game mode/lobby), then disposes the edit clone. The dispose is deferred
   * so the admin's cross-world teleport settles first — closing immediately races it: the clone still shows
   * a player, Bukkit refuses the unload ("Could not unload temporary world"), and the map stays "locked"
   * as an orphan until the world-GC reclaims it minutes later. When {@code persistWorld} the dispose also
   * saves the clone's blocks onto the base template (unload-with-save → copy), otherwise it just discards.
   */
  private void teardown(MapEditSession session, boolean persistWorld) {
    ScheduledTask renderTask = session.renderTask();
    if (renderTask != null) {
      renderTask.cancel();
    }
    UUID adminId = session.adminId();
    server.player(adminId).filter(PlayerAdapter::online).ifPresent(admin -> {
      restoreInventory(admin, session);
      admin.setGameMode(session.priorGameMode());
      server.worlds().lobbySpawn().ifPresent(admin::teleport);
    });
    WorldLease lease = session.lease();
    if (lease == null) {
      return;
    }
    String worldName = session.worldName();
    server.scheduler().runLater(() -> {
      if (persistWorld) {
        boolean saved = server.worlds().saveTemplateAndDispose(lease, worldName);
        server.player(adminId).filter(PlayerAdapter::online).ifPresent(admin -> admin.sendActionBar(
            saved ? "<green>Map '" + session.mapId() + "' saved — block edits baked in.</green>"
                : "<yellow>Saved zones for '" + session.mapId() + "'; block edits could not be written.</yellow>"));
      } else {
        lease.close();
      }
    }, UNLOAD_DELAY_TICKS);
  }

  private void render(MapEditSession session) {
    WorldAdapter world = session.world();
    if (world == null) {
      return;
    }
    for (TeamZone zone : session.battleMap().zones()) {
      int rgb = zone.color().rgb();
      Cuboid region = zone.region();
      if (region != null) {
        RegionRenderer.outline(world, region, rgb);
      }
      for (WorldPosition spawn : zone.spawns()) {
        RegionRenderer.marker(world, spawn, rgb, 1.0f);
      }
    }
  }

  /** Hotbar slot the team-switch tool lives in (recoloured per focused team). */
  private static final int TEAM_SLOT = 3;

  /** Stashes the admin's inventory, then lays out the editor tools across the hotbar. */
  private void giveTools(PlayerAdapter admin, MapEditSession session) {
    InventoryAdapter inventory = admin.inventory();
    session.setSavedInventory(new ArrayList<>(inventory.storageSlots()));
    inventory.clear();
    inventory.setSlot(0, tool(AXE_ITEM, "<gold><b>Corner Selector</b></gold> <gray>L=corner 1, R=corner 2</gray>"));
    inventory.setSlot(1, tool(DELETE_ITEM, "<red><b>Delete Box</b></red> <gray>strike a region</gray>"));
    inventory.setSlot(2, tool(UNDO_ITEM, "<yellow><b>Undo</b></yellow> <gray>right-click (Ctrl+Z)</gray>"));
    TeamColor current = session.battleMap().ensureZone(session.currentTeam()).color();
    inventory.setSlot(TEAM_SLOT, teamTool(session.currentTeam(), current));
    inventory.setSlot(4, tool(SPAWN_ITEM, "<aqua><b>Set Team Spawn</b></aqua> <gray>right-click: spawn here (with facing)</gray>"));
    inventory.setSlot(7, tool(CONFIRM_ITEM, "<green><b>Confirm</b></green> <gray>right-click: save + exit</gray>"));
    inventory.setSlot(8, tool(CANCEL_ITEM, "<red><b>Cancel</b></red> <gray>right-click: discard + exit</gray>"));
  }

  private static ItemStackData tool(String itemId, String miniMessageName) {
    return new ItemStackData(ItemKey.minecraft(itemId), 1, Map.of("name", miniMessageName));
  }

  /** The team-switch tool, labelled in the focused team's colour so the admin sees which team is active. */
  private static ItemStackData teamTool(int teamIndex, TeamColor color) {
    String name = "<" + color.miniMessageColor() + "><b>Team " + teamIndex + " — " + color.displayName()
        + "</b></" + color.miniMessageColor() + "> <gray>right-click to switch</gray>";
    return new ItemStackData(ItemKey.minecraft(TEAM_ITEM), 1, Map.of("name", name));
  }

  /** Restores the admin's pre-edit inventory (clearing the tools); a no-op snapshot just clears the tools. */
  private void restoreInventory(PlayerAdapter admin, MapEditSession session) {
    InventoryAdapter inventory = admin.inventory();
    List<ItemStackData> saved = session.savedInventory();
    if (saved != null) {
      inventory.setStorageContents(saved);
      return;
    }
    List<ItemStackData> contents = new ArrayList<>(inventory.storageContents());
    contents.removeIf(stack -> stack != null && stack.itemKey() != null && TOOL_ITEMS.contains(stack.itemKey().value()));
    inventory.setStorageContents(contents);
  }

  private static Result notEditing() {
    return Result.fail("You are not editing a map. Use /sx admin map edit <mode> <mapId>.");
  }
}
