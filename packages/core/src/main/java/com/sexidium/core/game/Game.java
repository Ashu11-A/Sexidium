package com.sexidium.core.game;

import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;
import java.util.UUID;

public interface Game {
  String id();

  String displayName();

  int minPlayers();

  void start(List<PlayerAdapter> participants);

  void stop(LocalizedText reason);

  GameState state();

  default void handle(GameEvent gameEvent) {
  }

  /**
   * Whether this game puts its own scoreboard sidebar on the given player's screen.
   *
   * <p>Almost every game does for everybody, and the lobby relies on it: when a player joins a match the
   * lobby drops its own sidebar WITHOUT clearing it, because clearing would race the match's board and
   * blank it a second after it appeared. A player the match draws no sidebar for (Death Resets renders
   * its counters in a corner overlay instead) breaks that assumption — nothing replaces the lobby's
   * board, so it stays on screen for the whole match, next to a readout that is supposed to be the
   * entire interface.</p>
   *
   * <p>Asked per PLAYER because a mode can only replace the board of the players its own surface
   * actually reaches — the Java client sees the corner overlay, the Bedrock client beside it cannot, and
   * keeps a real sidebar.</p>
   *
   * <p>Default true, which is the behaviour every existing mode already has.</p>
   */
  default boolean drawsSidebar(PlayerAdapter playerAdapter) {
    return true;
  }

  default boolean isEmpty() {
    return false;
  }

  default int onlineCount() {
    return 0;
  }

  default boolean isReconnectable() {
    return false;
  }

  default void onParticipantDisconnect(PlayerAdapter playerAdapter) {
  }

  default void onParticipantRejoin(PlayerAdapter playerAdapter) {
  }

  default void onParticipantAdded(PlayerAdapter playerAdapter) {
  }

  /**
   * Where a (re)entering participant should be teleported by the launcher. Default is the world spawn;
   * the experience host overrides this to return the player's SAVED position, so its state is retrieved
   * before the (single) entry teleport rather than re-teleporting after. Returning null skips the
   * teleport entirely.
   */
  default WorldPosition entrySpawn(PlayerAdapter playerAdapter, WorldAdapter worldAdapter) {
    return worldAdapter == null ? null : worldAdapter.spawnPosition();
  }

  /**
   * How this mode wants {@code playerAdapter} to ARRIVE — which game mode, and whether they are healed,
   * fed and emptied on the way in.
   *
   * <p>Declared, not enforced: the framework applies the returned {@link EntryPolicy} at every entry path
   * and <b>after</b> any saved state is restored, so a mode cannot be undone by whatever touched the
   * player last. Default {@link EntryPolicy#SURVIVAL}, which is what every mode did before this existed.
   * Consulted per player, so a mode can treat individuals differently (a lost hardcore world lets its
   * owner spectate; a build world hands its editor creative).</p>
   */
  default EntryPolicy entryPolicy(PlayerAdapter playerAdapter) {
    return EntryPolicy.SURVIVAL;
  }

  default void onParticipantRemoved(UUID playerId, boolean voluntary) {
  }

  /**
   * Hides every on-screen UI element this match is showing to the given player (boss bars, HUD/
   * scoreboard panels, countdown bars), so a player leaving the match no longer sees stale overlays.
   */
  default void releasePlayerUi(PlayerAdapter playerAdapter) {
  }

  default boolean allowsWorldChange(PlayerAdapter playerAdapter, String fromWorld, String toWorld) {
    return false;
  }

  /**
   * When true, the game keeps a respawning participant in its own world instead of letting
   * {@code GameManager} release them to the lobby. Used by open-ended experiences where a death must
   * not eject the player.
   */
  default boolean handlesOwnRespawn() {
    return false;
  }

  /**
   * Name of a template world this mode wants its match cloned from (resolved under the world root),
   * or null to use a freshly generated/pooled temp world. TNT War returns the chosen map's template so
   * {@code GameManager} clones the pre-built arena into the match world.
   */
  default String worldTemplate() {
    return null;
  }

  default void writeSnapshot(MatchSnapshot matchSnapshot) {
  }

  default void restore(MatchSnapshot matchSnapshot) {
  }
}
