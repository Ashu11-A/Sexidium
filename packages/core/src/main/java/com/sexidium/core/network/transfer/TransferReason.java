package com.sexidium.core.network.transfer;

/**
 * Why a player is being moved. A typed field, not a string prefix.
 *
 * <p>The previous design smuggled the experience case through {@code match_handoffs} behind a
 * synthetic {@code "experience:"} match id, so one table held two unrelated things — a minigame roster
 * and a world rendezvous — and the arrival gate told them apart by {@code String.startsWith}. That is
 * the proximate cause of the live transfer loop: the gate picked whichever row the database happened
 * to return first, and the two kinds of row have completely different meanings.</p>
 */
public enum TransferReason {
  /** The player is going to the node that holds an experience world's claim. */
  EXPERIENCE,
  /** The player is going back to a lobby node — a worker has no lobby world to teleport them to. */
  LOBBY,
  /** The player is being gathered onto the node assembling a minigame match. */
  MATCH;

  /** Parses a stored value, defaulting to {@link #LOBBY} — the safe destination for an unknown reason. */
  public static TransferReason parse(String value) {
    if (value == null) {
      return LOBBY;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return LOBBY;
    }
  }
}
