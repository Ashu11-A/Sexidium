package com.sexidium.core.world.lobby;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.platform.CommandSource;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The narrow slice of {@link com.sexidium.core.game.GameManager} that {@link LobbyManager} needs:
 * the mode catalogue, a player→match lookup, and the launch entry point. Depending on this interface
 * (which {@code GameManager} already satisfies) lets the queue be unit-tested with a tiny fake instead
 * of standing up the whole game/world stack.
 */
public interface MatchLauncher {
  List<GameModeDescriptor> descriptors();

  ActiveMatch matchOf(UUID playerId);

  boolean startWithPlayers(String modeId, Collection<UUID> participantIds, CommandSource initiator, List<String> modeArgs);
}
