package com.sexidium.core.network.transfer;

/**
 * Where one transfer has got to.
 *
 * <pre>
 *   PENDING ──(a proxy claims it)──▶ DISPATCHED ──(the connect succeeded)──▶ LANDED
 *        │                                 │
 *        │                                 └──(the connect failed)────────▶ FAILED
 *        └──(nobody acted before expires_at)─────────────────────────────▶ EXPIRED
 * </pre>
 *
 * <p>There were no states at all before. The proxy SELECTed the intent and DELETEd it in one breath,
 * then attempted the connect — so every failure after the delete lost the intent silently, the
 * requesting node was never told, and a proxy restart between the two dropped the player's transfer on
 * the floor with nothing anywhere recording that it had happened.</p>
 */
public enum TransferState {
  PENDING,
  DISPATCHED,
  LANDED,
  FAILED,
  EXPIRED;

  /** Whether this transfer is over, one way or another. */
  public boolean terminal() {
    return this == LANDED || this == FAILED || this == EXPIRED;
  }

  /** Whether a proxy may still act on it. */
  public boolean live() {
    return this == PENDING || this == DISPATCHED;
  }

  public static TransferState parse(String value) {
    if (value == null) {
      return EXPIRED;
    }
    try {
      return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return EXPIRED;
    }
  }
}
