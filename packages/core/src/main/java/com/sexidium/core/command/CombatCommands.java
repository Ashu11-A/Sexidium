package com.sexidium.core.command;

import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.world.map.SpawnPointStore;
import com.sexidium.core.world.map.SpawnPoints;

import java.util.ArrayList;
import java.util.List;

import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.escape;
import static com.sexidium.core.command.CommandText.lower;

/**
 * Handles {@code /sx admin map combat} — Combat arena map spawn-point capture.
 *
 * <p>Configures a Combat arena map's player spawn points, persisted to {@code sexidium-combat.yml} in the
 * map's world folder (the same flat sidecar TNT War uses for its bases). The map ids come from the
 * {@code minigames.combat.maps} array list; {@code /sx admin map combat <id> spawn} appends the player's current
 * position so fighters are later spread across every captured point, mirroring {@code /sx admin map tntwar}.
 */
final class CombatCommands {
  private static final String COMBAT_MAPS_PATH = "minigames.combat.maps";
  private static final String COMBAT_USAGE =
      "<gray>/sx admin map combat <list | <id> <spawn|clear>></gray> <dark_gray>— capture arena spawn points</dark_gray>";

  private final CommandContext ctx;
  private final SpawnPointStore combatSpawns;

  CombatCommands(CommandContext ctx, SpawnPointStore combatSpawns) {
    this.ctx = ctx;
    this.combatSpawns = combatSpawns;
  }

  void handle(CommandSource source, String[] args) {
    if (args.length < 2) {
      ctx.send(source, COMBAT_USAGE);
      return;
    }
    if (lower(args[1]).equals("list")) {
      combatList(source);
      return;
    }
    // Same rule as the editor and TNT War: every remaining branch (spawn, clear) persists
    // sexidium-combat.yml into the template folder, which on a non-authority node would only ever
    // apply to that node's copy. Listing stays open everywhere -- it writes nothing.
    if (MapEditorCommands.lacksMapAuthority(ctx, source)) {
      return;
    }
    String mapId = args[1];
    String world = ctx.configuredMapWorld(COMBAT_MAPS_PATH, mapId);
    if (world == null) {
      ctx.send(source, "<red>No Combat map '<white>" + escape(mapId) + "</white>'. Add it to "
          + "<white>minigames.combat.maps</white> in config.yml first.</red>");
      return;
    }
    if (args.length < 3) {
      ctx.send(source, COMBAT_USAGE);
      return;
    }
    java.nio.file.Path folder = ctx.server.worlds().worldRoot().resolve(world);
    SpawnPoints spawns = combatSpawns.load(folder, world);
    spawns.setWorld(world);
    switch (lower(args[2])) {
      case "spawn" -> combatAddSpawn(source, folder, world, spawns);
      case "clear" -> {
        spawns.clear();
        if (combatSave(source, folder, spawns)) {
          ctx.send(source, "<yellow>Cleared the spawn points for Combat map <white>" + escape(mapId) + "</white>.</yellow>");
        }
      }
      default -> ctx.send(source, COMBAT_USAGE);
    }
  }

  private void combatAddSpawn(CommandSource source, java.nio.file.Path folder, String world, SpawnPoints spawns) {
    PlayerAdapter player = ctx.playerSource(source);
    if (player == null || player.position() == null) {
      ctx.send(source, "<red>Stand in the arena where a fighter should spawn, then run this command.</red>");
      return;
    }
    spawns.add(player.position().withWorldName(world));
    if (combatSave(source, folder, spawns)) {
      ctx.send(source, "<green>Added Combat spawn <white>#" + spawns.size() + "</white> to map <white>"
          + escape(world) + "</white>.</green>");
    }
  }

  private void combatList(CommandSource source) {
    List<String> ids = ctx.configuredMapIds(COMBAT_MAPS_PATH);
    if (ids.isEmpty()) {
      ctx.send(source, "<gray>No Combat maps configured. Combat runs on a generated arena until you add "
          + "entries to <white>minigames.combat.maps</white>.</gray>");
      return;
    }
    ctx.send(source, "<aqua><bold>Combat maps:</bold></aqua>");
    for (String id : ids) {
      String world = ctx.configuredMapWorld(COMBAT_MAPS_PATH, id);
      SpawnPoints spawns = combatSpawns.load(ctx.server.worlds().worldRoot().resolve(world), world);
      String state = spawns.isReady() ? "<green>" + spawns.size() + " spawn(s)</green>" : "<red>no spawns</red>";
      ctx.send(source, " <white>" + escape(id) + "</white> <gray>(" + escape(world) + ")</gray> " + state);
    }
  }

  private boolean combatSave(CommandSource source, java.nio.file.Path folder, SpawnPoints spawns) {
    try {
      combatSpawns.save(folder, spawns);
      return true;
    } catch (java.io.IOException exception) {
      ctx.server.logger().warning("Failed to save Combat arena spawns", exception);
      ctx.send(source, "<red>Could not save the arena spawns: " + exception.getMessage() + "</red>");
      return false;
    }
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      List<String> options = new ArrayList<>(List.of("list"));
      options.addAll(ctx.configuredMapIds(COMBAT_MAPS_PATH));
      return ctx.filter(options, args[1]);
    }
    if (args.length == 3 && !equalsAny(args[1], "list")) {
      return ctx.filter(List.of("spawn", "clear"), args[2]);
    }
    return List.of();
  }
}
