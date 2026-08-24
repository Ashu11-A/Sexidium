package com.sexidium.paper.adapter.ui.betterhud;

import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * Everything one open surface asks of the claim ledger, and nothing else.
 *
 * <p>{@link BetterHudClaims} is the only implementation that ships. The seam exists so
 * {@link BetterHudSurfaceHandle} — where the decision that matters lives, "is this player already
 * seeing this, or does the sidebar have to draw it" — can be exercised for real on a test JVM with no
 * BetterHud on the classpath. Without it that decision was only ever pinned by reading the source back
 * as text, which is how it came to be permanently false in production while its tests passed.</p>
 */
interface SurfaceClaims {
  /** Whether the backing plugin is present and linked at all. */
  boolean available();

  /**
   * Whether an object with this id actually loaded.
   *
   * <p>Asked of the plugin rather than assumed from "we wrote the file": a layout that fails to parse
   * leaves nothing on screen, and a surface that called itself alive on the strength of a successful
   * file write would suppress the fallback in exactly that case.</p>
   */
  boolean exists(String id);

  /**
   * Whether the player is wearing a PERSISTENT surface with this id.
   *
   * <p>Never true of a popup, however recently it was fired: BetterHud does not record a fired popup
   * anywhere the "what are you wearing" query can reach. Ask {@link #showingPopup} for those.</p>
   */
  boolean showing(Player player, String id);

  /** Whether a popup with this id is on screen for the player right now. */
  boolean showingPopup(Player player, String id);

  /**
   * Records that the player should be seeing this surface and applies it.
   *
   * @param popupDurationMillis zero for a persistent surface (a claim, re-asserted for as long as it
   *                            is held); positive for a popup, which is fired once and expires
   */
  void claim(Player player, String id, long popupDurationMillis);

  /** Drops one claim, or takes one popup out of the live ledger. */
  void release(UUID playerId, String id);

  /** Retracts a popup before its duration is up. */
  void hidePopup(Player player, String id);

  /** Publishes a viewer's rendered rows into their own variable map. */
  void pushVariables(Player player, Map<String, String> values);

  /** Takes a surface's variables back off a player who has stopped viewing it. */
  void clearVariables(Player player, Collection<String> keys);
}
