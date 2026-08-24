package com.sexidium.core.command;

import com.sexidium.core.game.modes.minigames.tntwar.TntWarMap;
import com.sexidium.core.game.modes.minigames.tntwar.TntWarMapStore;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /sx admin map tntwar} — in-world TNT War map base/spawn capture and listing. */
final class TntWarCommands {
  private static final String TNTWAR_MAPS_PATH = "minigames.tntwar.maps";

  private final CommandContext ctx;

  TntWarCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    if (args.length < 2) {
      ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
      return;
    }
    String action = lower(args[1]);
    if (action.equals("list")) {
      tntWarList(source);
      return;
    }
    // Everything past "list" ends in TntWarMapStore.save() writing sexidium-tntwar.yml into the
    // template folder, so the gate goes here once instead of on each of create/corner/spawn/save.
    // Listing stays open on every node: reading which maps are ready is exactly what an admin needs
    // in order to understand the refusal below.
    if (MapEditorCommands.lacksMapAuthority(ctx, source)) {
      return;
    }
    if (action.equals("create")) {
      if (args.length < 3) {
        ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
        return;
      }
      tntWarCreate(source, args[2]);
      return;
    }
    // Any other first token is a map id: /sx admin map tntwar <id> <corner|spawn|save> ...
    String mapId = args[1];
    String world = ctx.configuredMapWorld(TNTWAR_MAPS_PATH, mapId);
    if (world == null) {
      ctx.send(source, MessageKey.TNTWAR_MAP_UNKNOWN, MessageArg.text("id", mapId));
      return;
    }
    if (args.length < 3) {
      ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
      return;
    }
    PlayerAdapter player = ctx.playerSource(source);
    if (player == null || player.position() == null) {
      ctx.send(source, MessageKey.TNTWAR_MAP_NEEDS_PLAYER);
      return;
    }
    java.nio.file.Path folder = ctx.server.worlds().worldRoot().resolve(world);
    TntWarMap map = TntWarMapStore.load(folder, mapId);
    map.setWorld(world);
    String sub = lower(args[2]);
    switch (sub) {
      case "corner" -> tntWarCorner(source, player, folder, map, args);
      case "spawn" -> tntWarSpawn(source, player, folder, map, args);
      case "save" -> tntWarSave(source, folder, map);
      default -> ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
    }
  }

  private void tntWarCreate(CommandSource source, String mapId) {
    String world = ctx.configuredMapWorld(TNTWAR_MAPS_PATH, mapId);
    if (world == null) {
      ctx.send(source, MessageKey.TNTWAR_MAP_UNKNOWN, MessageArg.text("id", mapId));
      return;
    }
    java.nio.file.Path folder = ctx.server.worlds().worldRoot().resolve(world);
    TntWarMap map = TntWarMapStore.load(folder, mapId);
    map.setWorld(world);
    if (tntWarSaveQuietly(folder, map)) {
      ctx.send(source, MessageKey.TNTWAR_MAP_CREATED, MessageArg.text("id", mapId), MessageArg.text("world", world));
    }
  }

  private void tntWarCorner(CommandSource source, PlayerAdapter player, java.nio.file.Path folder, TntWarMap map, String[] args) {
    if (args.length < 5 || !isTeamToken(args[3])) {
      ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
      return;
    }
    int corner = "2".equals(args[4].trim()) ? 2 : 1;
    WorldPosition position = player.position();
    BlockPosition block = new BlockPosition(map.world(),
        (int) Math.floor(position.coordinateX()), (int) Math.floor(position.coordinateY()), (int) Math.floor(position.coordinateZ()));
    map.setCorner(args[3], corner, block);
    if (tntWarSaveQuietly(folder, map)) {
      ctx.send(source, MessageKey.TNTWAR_MAP_CORNER_SET,
          MessageArg.mini("team", teamLabel(args[3])), MessageArg.text("corner", corner),
          MessageArg.text("x", block.blockX()), MessageArg.text("y", block.blockY()), MessageArg.text("z", block.blockZ()));
    }
  }

  private void tntWarSpawn(CommandSource source, PlayerAdapter player, java.nio.file.Path folder, TntWarMap map, String[] args) {
    if (args.length < 4 || !isTeamToken(args[3])) {
      ctx.send(source, MessageKey.TNTWAR_MAP_USAGE);
      return;
    }
    map.setSpawn(args[3], player.position().withWorldName(map.world()));
    if (tntWarSaveQuietly(folder, map)) {
      ctx.send(source, MessageKey.TNTWAR_MAP_SPAWN_SET, MessageArg.mini("team", teamLabel(args[3])));
    }
  }

  private void tntWarSave(CommandSource source, java.nio.file.Path folder, TntWarMap map) {
    if (tntWarSaveQuietly(folder, map)) {
      ctx.send(source, MessageKey.TNTWAR_MAP_SAVED, MessageArg.text("id", map.id()));
    }
  }

  private boolean tntWarSaveQuietly(java.nio.file.Path folder, TntWarMap map) {
    try {
      TntWarMapStore.save(folder, map);
      return true;
    } catch (java.io.IOException exception) {
      ctx.server.logger().warning("Failed to save TNT War map " + map.id(), exception);
      return false;
    }
  }

  private void tntWarList(CommandSource source) {
    List<Map<String, Object>> configured = ctx.server.configuration().getMapList(TNTWAR_MAPS_PATH);
    if (configured == null || configured.isEmpty()) {
      ctx.send(source, MessageKey.TNTWAR_MAP_NONE);
      return;
    }
    ctx.send(source, MessageKey.TNTWAR_MAP_LIST);
    for (Map<String, Object> entry : configured) {
      String id = entry.get("id") == null ? "" : String.valueOf(entry.get("id"));
      if (id.isBlank()) {
        continue;
      }
      String world = entry.get("world") == null || String.valueOf(entry.get("world")).isBlank() ? id : String.valueOf(entry.get("world"));
      TntWarMap map = TntWarMapStore.load(ctx.server.worlds().worldRoot().resolve(world), id);
      String ready = map.isReady() ? "ready" : "incomplete";
      ctx.send(source, MessageKey.TNTWAR_MAP_LIST_ENTRY,
          MessageArg.text("id", id), MessageArg.text("world", world), MessageArg.text("ready", ready));
    }
  }

  private boolean isTeamToken(String value) {
    return value != null && (value.equalsIgnoreCase("red") || value.equalsIgnoreCase("blue"));
  }

  private String teamLabel(String team) {
    return team != null && team.equalsIgnoreCase("red") ? "<red>Red</red>" : "<blue>Blue</blue>";
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      List<String> options = new ArrayList<>(List.of("list", "create"));
      options.addAll(ctx.configuredMapIds(TNTWAR_MAPS_PATH));
      return ctx.filter(options, args[1]);
    }
    if (args.length == 3 && !equalsAny(args[1], "list", "create")) {
      return ctx.filter(List.of("corner", "spawn", "save"), args[2]);
    }
    if (args.length == 4 && equalsAny(args[2], "corner", "spawn")) {
      return ctx.filter(List.of("red", "blue"), args[3]);
    }
    if (args.length == 5 && equalsAny(args[2], "corner")) {
      return ctx.filter(List.of("1", "2"), args[4]);
    }
    return List.of();
  }
}
