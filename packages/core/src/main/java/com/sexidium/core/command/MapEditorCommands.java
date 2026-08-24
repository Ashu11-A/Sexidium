package com.sexidium.core.command;

import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.network.NetworkService;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.network.NodeRegistry;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.world.MapBundle;
import com.sexidium.core.world.map.editor.MapEditorService;

import java.util.ArrayList;
import java.util.List;

import static com.sexidium.core.command.CommandText.lower;

/**
 * {@code /sx admin map edit} — the in-world battle-map editor + region debugger for the team minigames
 * (TNT War, Gather &amp; Duel, Combat). Drops the admin into a map's template world in <b>Creative</b> with a
 * five-tool hotbar — golden axe (corner 1/2), iron pick (delete a box), clock (undo), lime dye (confirm:
 * save+exit), red dye (cancel: exit) — and renders every team's region as a coloured wireframe. Delegates
 * all state to {@link MapEditorService}; this handler is the grammar plus the dynamic map listing.
 *
 * <pre>
 *   /sx admin map edit                        list the bundled worlds + configured maps you can edit
 *   /sx admin map edit &lt;mode&gt; &lt;mapId|world&gt;   enter the editor for a map
 *   /sx admin map edit team &lt;n&gt;               focus the team the axe edits
 *   /sx admin map edit spawn                  add a spawn for the focused team at your position
 *   /sx admin map edit save                   write the sexidium-battlemap.yml sidecar
 *   /sx admin map edit list                   in a session: team status; otherwise: the available maps
 *   /sx admin map edit exit                   leave the editor
 * </pre>
 */
final class MapEditorCommands {
  private static final List<String> ACTIONS = List.of("team", "spawn", "save", "list", "worlds", "exit");

  private final CommandContext ctx;

  MapEditorCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  private MapEditorService editor() {
    return ctx.core.mapEditor();
  }

  void handle(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    boolean editing = editor().hasSession(player.uniqueId());
    if (args.length < 2) {
      if (editing) {
        relay(source, editor().status(player.uniqueId()));
      } else {
        listAvailable(source);
      }
      return;
    }
    switch (lower(args[1])) {
      case "team" -> handleTeam(source, player, args);
      case "spawn" -> {
        if (!lacksMapAuthority(ctx, source)) {
          relay(source, editor().addSpawn(player.uniqueId()));
        }
      }
      case "save" -> {
        if (!lacksMapAuthority(ctx, source)) {
          relay(source, editor().save(player.uniqueId()));
        }
      }
      case "list" -> {
        if (editing) {
          relay(source, editor().status(player.uniqueId()));
        } else {
          listAvailable(source);
        }
      }
      case "worlds" -> listAvailable(source);
      case "exit" -> {
        editor().exit(player.uniqueId());
        ctx.send(source, "<green>Left the map editor.</green>");
      }
      default -> handleEnter(source, player, args);
    }
  }

  private void handleEnter(CommandSource source, PlayerAdapter player, String[] args) {
    // Gated at the door, not only at save: entering puts the admin in Creative inside the TEMPLATE
    // world itself, and the Confirm tool copies that world back over the template. Refusing only at
    // save would let someone build for an hour on a node whose work can never be kept.
    if (lacksMapAuthority(ctx, source)) {
      return;
    }
    if (args.length < 3) {
      usage(source);
      return;
    }
    String modeArg = lower(args[1]);
    GameModeDescriptor descriptor = ctx.findMinigameDescriptor(modeArg);
    if (descriptor == null) {
      ctx.send(source, "<red>Unknown minigame '" + modeArg + "'. Try: " + String.join(", ", ctx.minigameIds()) + "</red>");
      return;
    }
    String modeId = descriptor.modeId();
    String mapId = args[2];
    // Prefer a configured map id (minigames.<mode>.maps); otherwise treat the argument as a world folder
    // name directly, so a bundled world (e.g. tntwar/tnt-wars) or a Gather duel arena is still editable.
    String world = ctx.configuredMapWorld("minigames." + modeId + ".maps", mapId);
    if (world == null) {
      world = mapId;
    }
    relay(source, editor().enter(player, modeId, mapId, world));
  }

  private void handleTeam(CommandSource source, PlayerAdapter player, String[] args) {
    if (args.length < 3) {
      ctx.send(source, "<red>Usage: /sx admin map edit team <index></red>");
      return;
    }
    int index;
    try {
      index = Integer.parseInt(args[2].trim());
    } catch (NumberFormatException exception) {
      ctx.send(source, "<red>Team index must be a number.</red>");
      return;
    }
    relay(source, editor().focusTeam(player.uniqueId(), index));
  }

  /** Dynamic catalogue: each minigame's configured maps, plus any bundled worlds not already configured. */
  private void listAvailable(CommandSource source) {
    StringBuilder builder = new StringBuilder("<gold><b>Map editor — available maps</b></gold>");
    // Track the template worlds the configured maps already point at, so a bundled world that is also a
    // configured map (the common case — the tntwar maps are both) is not listed twice.
    java.util.Set<String> configuredWorlds = new java.util.HashSet<>();
    for (String modeId : ctx.minigameIds()) {
      List<String> maps = ctx.configuredMapIds("minigames." + modeId + ".maps");
      for (String mapId : maps) {
        String world = ctx.configuredMapWorld("minigames." + modeId + ".maps", mapId);
        configuredWorlds.add(world == null ? mapId : world);
      }
      builder.append("\n<gray>").append(modeId).append(" maps:</gray> ")
          .append(maps.isEmpty() ? "<dark_gray>(none configured)</dark_gray>" : "<white>" + String.join(", ", maps) + "</white>");
    }
    List<String> extraBundled = new ArrayList<>();
    for (String worldPath : MapBundle.bundledWorldPaths(ctx.server.resources())) {
      if (!configuredWorlds.contains(worldPath)) {
        extraBundled.add(worldPath);
      }
    }
    if (!extraBundled.isEmpty()) {
      builder.append("\n<gray>other bundled worlds:</gray> <white>").append(String.join(", ", extraBundled)).append("</white>");
    }
    builder.append("\n<yellow>Enter with /sx admin map edit <mode> <mapId>; a bundled world path also works.</yellow>");
    ctx.send(source, builder.toString());
  }

  List<String> suggest(String[] args) {
    if (args.length == 2) {
      List<String> options = new ArrayList<>(ACTIONS);
      options.addAll(ctx.minigameIds());
      return ctx.filter(options, args[1]);
    }
    if (args.length == 3) {
      String action = lower(args[1]);
      if (action.equals("team")) {
        return ctx.filter(List.of("0", "1", "2", "3"), args[2]);
      }
      GameModeDescriptor descriptor = ctx.findMinigameDescriptor(action);
      if (descriptor != null) {
        String mapsPath = "minigames." + descriptor.modeId() + ".maps";
        List<String> configured = ctx.configuredMapIds(mapsPath);
        List<String> options = new ArrayList<>(configured);
        // The template worlds the configured ids already resolve to, so a bundled world that IS a
        // configured map is not offered twice.
        java.util.Set<String> configuredWorlds = new java.util.HashSet<>();
        for (String mapId : configured) {
          String world = ctx.configuredMapWorld(mapsPath, mapId);
          configuredWorlds.add(world == null ? mapId : world);
        }
        for (String worldPath : MapBundle.bundledWorldPaths(ctx.server.resources())) {
          if (worldPath.startsWith(descriptor.modeId() + "/") && !configuredWorlds.contains(worldPath)) {
            options.add(worldPath);
          }
        }
        return ctx.filter(options, args[2]);
      }
    }
    return List.of();
  }

  /**
   * The one gate for every command that writes a map template or one of its sidecars — the editor's
   * enter/spawn/save, {@code /sx admin map tntwar} and {@code /sx admin map combat}.
   *
   * <p>Lives here, package-private and static, because the three handlers are siblings in this
   * package and the refusal has to be <em>identical</em> in all of them: an admin who is bounced
   * from one has to learn the same fact from the next one they try. It is not on
   * {@code CommandContext} to keep the map-authority rule with the map commands that own it.</p>
   *
   * <p>Returns true when the caller must stop, having already been told why. It is never silent —
   * a silent refusal here is worse than no gate at all, because the admin would go on believing the
   * bases were captured.</p>
   */
  static boolean lacksMapAuthority(CommandContext ctx, CommandSource source) {
    NetworkService network = ctx.core.network();
    NodeIdentity node = network.identity();
    if (node.can(NodeCapability.MAP_AUTHORITY)) {
      return false;
    }
    // Naming the authority is the whole point of the message. "You can't do that here" without
    // "do it there" just relocates the confusion.
    ctx.send(source, MessageKey.MAP_AUTHORITY_REQUIRED,
        MessageArg.text("node", node.displayName()),
        MessageArg.text("authority", mapAuthorityNodes(network)));
    return true;
  }

  /**
   * Who to send the admin to. Asks the live registry rather than assuming "the lobby", so a network
   * that moved the capability elsewhere still points at the right place. Falls back to the role name
   * when there is no registry (standalone never reaches here — it holds every capability) or when no
   * authority node is currently alive, in which case saying which role to bring up is still useful.
   */
  private static String mapAuthorityNodes(NetworkService network) {
    NodeRegistry registry = network.registry();
    if (registry == null) {
      return "lobby";
    }
    List<String> names = registry.with(NodeCapability.MAP_AUTHORITY).stream()
        .map(peer -> peer.displayName() == null || peer.displayName().isBlank() ? peer.nodeId() : peer.displayName())
        .toList();
    return names.isEmpty() ? "lobby (offline)" : String.join(", ", names);
  }

  private void relay(CommandSource source, MapEditorService.Result result) {
    String color = result.ok() ? "green" : "red";
    ctx.send(source, "<" + color + ">" + result.message() + "</" + color + ">");
  }

  private void usage(CommandSource source) {
    ctx.send(source, "<yellow>/sx admin map edit [<mode> <mapId>] | team <n> | spawn | save | list | worlds | exit</yellow>");
  }
}
