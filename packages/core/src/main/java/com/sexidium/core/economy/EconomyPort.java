package com.sexidium.core.economy;

import java.util.UUID;

/**
 * The narrow, never-null seam every non-economy subsystem reads money through.
 *
 * <p>It exists so the lobby sidebar can show a balance without the HUD holding a nullable
 * {@link EconomyService}: a node with no database has no economy at all, and every consumer would
 * otherwise carry its own {@code if (economy != null)} — which is precisely the shape that
 * eventually forgets one. {@link #noop()} answers instead, and answers safely.</p>
 *
 * <p>The two reads are declared as never blocking, never throwing and never returning null because
 * one of their callers is a render tick. A balance that is a few seconds stale on a sidebar costs
 * nothing; a sidebar that throws takes the whole HUD frame with it.</p>
 */
public interface EconomyPort {

  /** THE method the scoreboard sidebar calls. Never blocks, never throws, never returns null. */
  Money balance(UUID playerId);

  /**
   * The formatted, display-ready balance ({@code "$1,234.56"}). One call for a HUD row.
   *
   * <p>MUST return PLAIN TEXT with no MiniMessage tags — the HUD passes it straight through
   * {@code MessageArg.text}, which escapes what it is given, so a tag here would be shown to the
   * player as literal angle brackets.</p>
   */
  String formattedBalance(UUID playerId);

  /** Whether money is available at all on this node (a database, and {@code economy.enabled}). */
  default boolean enabled() {
    return false;
  }

  default boolean has(UUID playerId, Money amount) {
    return balance(playerId).compareTo(amount) >= 0;
  }

  default EconomyResult deposit(UUID playerId, Money amount, String reason) {
    return EconomyResult.failure(EconomyResult.Status.DISABLED, "economy is not available");
  }

  default EconomyResult withdraw(UUID playerId, Money amount, String reason) {
    return EconomyResult.failure(EconomyResult.Status.DISABLED, "economy is not available");
  }

  /** The port a node without a database gets: zero balance, blank display, every mutation DISABLED. */
  static EconomyPort noop() {
    return new EconomyPort() {
      @Override
      public Money balance(UUID playerId) {
        return Money.ZERO;
      }

      @Override
      public String formattedBalance(UUID playerId) {
        // Blank, not "$0.00": a HUD row that renders an amount nobody has is a lie, and the sidebar
        // can drop an empty row. See LobbyHud.
        return "";
      }
    };
  }
}
