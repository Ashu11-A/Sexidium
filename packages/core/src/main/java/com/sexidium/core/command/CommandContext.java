package com.sexidium.core.command;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.game.CoreGameRegistryInitializer;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.sexidium.core.command.CommandText.normalizeCategory;

/**
 * Shared dependencies and cross-cutting helpers (messaging, suggestion filtering, player resolution,
 * descriptor/category and map-config lookups) used by every command handler. Holds the same {@code core},
 * {@code server} and reload callback the facade was built with.
 */
final class CommandContext {
  final SexidiumCore core;
  final ServerAdapter server;
  final Runnable reloadCallback;

  CommandContext(SexidiumCore core, ServerAdapter server, Runnable reloadCallback) {
    this.core = core;
    this.server = server;
    this.reloadCallback = reloadCallback;
  }

  // ----- messaging ---------------------------------------------------------------------------------

  void send(CommandSource source, MessageKey messageKey, MessageArg... messageArguments) {
    server.messages().send(source, LocalizedText.of(messageKey, messageArguments));
  }

  void send(CommandSource source, String miniMessage) {
    server.messages().send(source, miniMessage);
  }

  // ----- suggestion filtering ----------------------------------------------------------------------

  List<String> filter(List<String> values, String prefix) {
    String normalizedPrefix = prefix == null ? "" : prefix.toLowerCase(Locale.ROOT);
    return values.stream()
        .filter(Objects::nonNull)
        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
        .distinct()
        .toList();
  }

  List<String> playerNames() {
    return server.onlinePlayers().stream().map(PlayerAdapter::name).toList();
  }

  // ----- player resolution -------------------------------------------------------------------------

  PlayerAdapter onlinePlayerOrUsage(CommandSource source, String playerName) {
    if (playerName == null || playerName.isBlank()) {
      send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", ""));
      return null;
    }
    PlayerAdapter target = server.playerExact(playerName).orElse(null);
    if (target == null) {
      send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", playerName));
    }
    return target;
  }

  PlayerAdapter requirePlayer(CommandSource source) {
    PlayerAdapter player = playerSource(source);
    if (player == null) {
      send(source, MessageKey.COMMAND_PLAYER_ONLY);
    }
    return player;
  }

  PlayerAdapter playerSource(CommandSource source) {
    return source instanceof PlayerAdapter playerAdapter ? playerAdapter : null;
  }

  List<PlayerAdapter> participants(List<String> names, CommandSource source) {
    if (names != null && !names.isEmpty()) {
      List<PlayerAdapter> participants = new ArrayList<>();
      for (String name : names) {
        server.playerExact(name).ifPresent(participants::add);
      }
      return participants;
    }
    // No explicit roster: a player starting a match brings themselves + their online lobby group (never
    // the whole server); console/admin with no names falls back to every online player.
    PlayerAdapter initiator = playerSource(source);
    if (initiator == null) {
      return new ArrayList<>(server.onlinePlayers());
    }
    Map<UUID, PlayerAdapter> roster = new LinkedHashMap<>();
    roster.put(initiator.uniqueId(), initiator);
    if (core.lobbies() != null) {
      Lobby lobby = core.lobbies().lobbyOf(initiator.uniqueId());
      if (lobby != null) {
        for (PlayerAdapter member : lobby.onlineMembers(server)) {
          roster.put(member.uniqueId(), member);
        }
      }
    }
    return new ArrayList<>(roster.values());
  }

  String playerName(UUID playerId) {
    return server.player(playerId).map(PlayerAdapter::name).orElse("?");
  }

  // ----- descriptors / categories ------------------------------------------------------------------

  Map<String, List<GameModeDescriptor>> descriptorsByCategory() {
    Map<String, List<GameModeDescriptor>> grouped = new LinkedHashMap<>();
    for (GameModeDescriptor descriptor : core.games().descriptors()) {
      grouped.computeIfAbsent(normalizeCategory(descriptor.category()), ignored -> new ArrayList<>()).add(descriptor);
    }
    return grouped;
  }

  List<String> categories() {
    return new ArrayList<>(descriptorsByCategory().keySet());
  }

  boolean categoryExists(String category) {
    return categories().contains(normalizeCategory(category));
  }

  List<String> modeIdsInCategory(String category) {
    List<GameModeDescriptor> descriptors = descriptorsByCategory().get(normalizeCategory(category));
    return descriptors == null ? List.of() : descriptors.stream().map(GameModeDescriptor::modeId).toList();
  }

  GameModeDescriptor descriptor(String modeId) {
    for (GameModeDescriptor descriptor : core.games().descriptors()) {
      if (descriptor.modeId().equalsIgnoreCase(modeId)) {
        return descriptor;
      }
    }
    return null;
  }

  List<String> minigameIds() {
    List<String> ids = new ArrayList<>();
    for (GameModeDescriptor descriptor : core.games().descriptors()) {
      if (CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(descriptor.category())) {
        ids.add(descriptor.modeId());
      }
    }
    return ids;
  }

  /** Resolves a minigame id/alias to its descriptor, or null when it is not a registered minigame. */
  GameModeDescriptor findMinigameDescriptor(String modeId) {
    if (modeId == null) {
      return null;
    }
    String wanted = modeId.toLowerCase(Locale.ROOT).trim();
    for (GameModeDescriptor descriptor : core.games().descriptors()) {
      if (!CoreGameRegistryInitializer.CATEGORY_MINIGAMES.equals(descriptor.category())) {
        continue;
      }
      if (descriptor.modeId().equals(wanted) || descriptor.aliases().contains(wanted)) {
        return descriptor;
      }
    }
    return null;
  }

  // ----- map config lookups (shared by TNT War + Combat) -------------------------------------------

  /** Resolves a configured map id to its template world folder under {@code mapsPath}, or null when unknown. */
  String configuredMapWorld(String mapsPath, String mapId) {
    if (mapId == null || mapId.isBlank()) {
      return null;
    }
    for (Map<String, Object> entry : server.configuration().getMapList(mapsPath)) {
      String id = entry.get("id") == null ? "" : String.valueOf(entry.get("id"));
      if (id.equalsIgnoreCase(mapId)) {
        String world = entry.get("world") == null ? "" : String.valueOf(entry.get("world"));
        return world.isBlank() ? id : world;
      }
    }
    return null;
  }

  List<String> configuredMapIds(String mapsPath) {
    List<String> ids = new ArrayList<>();
    for (Map<String, Object> entry : server.configuration().getMapList(mapsPath)) {
      String id = entry.get("id") == null ? "" : String.valueOf(entry.get("id"));
      if (!id.isBlank()) {
        ids.add(id);
      }
    }
    return ids;
  }
}
