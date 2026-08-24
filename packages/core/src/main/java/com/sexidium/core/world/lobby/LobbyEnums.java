package com.sexidium.core.world.lobby;

/**
 * The {@link Lobby}'s small enums, grouped in one place (formerly one file each). Each keeps its original
 * simple name, so a call site only switches its import to {@code com.sexidium.core.world.lobby.LobbyEnums.<Name>}.
 */
public final class LobbyEnums {
  private LobbyEnums() {
  }

  /** Who may join a {@link Lobby} from the browser. Replaces the old open/private boolean. */
  public enum LobbyVisibility {
    /** Anyone may join from the browser. */
    PUBLIC,
    /** Only the host's friends (and explicitly invited players) may join. */
    FRIENDS_ONLY,
    /** Only explicitly invited players may join. */
    INVITE_ONLY
  }

  /**
   * The single pre-match state of a {@link Lobby}. Exactly one at a time, which dissolves the old "in a
   * party XOR in a host-lobby XOR in a queue" bookkeeping into one field.
   */
  public enum LobbyState {
    /** Just a social group (the old "party"): no mode chosen, joined via invite/accept. */
    IDLE,
    /** The whole roster submitted one quick-play matchmaking ticket for {@link Lobby#modeId()}. */
    QUEUED,
    /** The leader picked a mode + team shape and is staging a host match. */
    CONFIGURED
  }
}
