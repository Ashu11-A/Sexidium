package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.platform.hud.HudSurfaceSpec;
import org.bukkit.entity.Player;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who is supposed to be wearing which of our surfaces, and the machinery that keeps BetterHud
 * agreeing with that.
 *
 * <h2>Why a ledger rather than fire-and-forget</h2>
 * BetterHud rebuilds a {@code HudPlayer} on join, on world change and on reload, re-applying its own
 * defaults and whatever it persisted to {@code .users/<uuid>.yml} — so a surface added once is not a
 * surface that stays added. Recording the intent and re-asserting it is the only way a claim survives
 * the plugin's own lifecycle.
 *
 * <p>The ledger is also what makes several surfaces at a time possible. Its predecessor held ONE
 * desired layout id per player, which was fine for the single consumer that existed and is the first
 * thing a second consumer would have broken.</p>
 *
 * <h2>Two sweeps, with deliberately different reach</h2>
 * <ul>
 *   <li><b>Exclusive mode</b> ({@link #reconcile}) removes everything a player wears that is not in
 *       the claim set — including other plugins' objects. That reach is the whole point: "show this
 *       player nothing except what Sexidium asked for" cannot be honoured while BetterHud's own demo
 *       hud and its {@code default: true} compass keep re-adding themselves beside the mode's readout.
 *       It runs on the empty claim set too, which is the common case — every player in the lobby.</li>
 *   <li><b>Teardown</b> ({@link #purgeOwned}, the driver-disabled path) is bounded by
 *       {@link HudSurfaceSpec#NAMESPACE} and can only ever undo our own work, because a switched-off
 *       driver has no business touching an operator's huds at all.</li>
 * </ul>
 */
final class BetterHudClaims implements SurfaceClaims {
  private final BetterHudLink link;
  private final BetterHudApi api;
  /** Persistent surfaces each player should be wearing. */
  private final Map<UUID, Set<String>> claims = new ConcurrentHashMap<>();
  /**
   * Popups a player is currently being shown, against the moment each stops being ours to protect.
   *
   * <p>A popup is fired rather than worn, so it is not a claim and is never re-asserted — but it IS on
   * screen, and the exclusive sweep strips everything not in the retain set. Without this, a toast
   * lived until the next sweep rather than for its own duration: on the shipped one-second cadence a
   * three-second announcement was taken down after one, and a countdown re-shown every second blinked
   * as the two raced. Held with a deadline rather than for ever so an id cannot accumulate — past it,
   * the popup is BetterHud's to expire and no longer ours to defend.</p>
   */
  private final Map<UUID, Map<String, Long>> firedPopups = new ConcurrentHashMap<>();
  private volatile boolean exclusive = true;

  BetterHudClaims(BetterHudLink link, BetterHudApi api) {
    this.link = link;
    this.api = api;
  }

  void exclusive(boolean value) {
    this.exclusive = value;
  }

  @Override
  public boolean available() {
    return link.available();
  }

  @Override
  public boolean exists(String id) {
    return link.available() && api.exists(id);
  }

  @Override
  public boolean showing(Player player, String id) {
    return link.available() && api.showing(player, id);
  }

  /**
   * Whether a popup of ours is on screen for this player right now.
   *
   * <p><b>Answered from our own ledger, and it has to be.</b> The obvious implementation — ask
   * BetterHud what the player is wearing, as {@link #showing} does — cannot work for a popup and never
   * could: {@code HudPlayer#getPopups()} is derived from {@code getHudObjects()}, which only
   * {@code HudObject#add} ever writes, while showing a popup goes through {@code Popup#show} and
   * writes somewhere else entirely (see {@code BetterHudApi#worn}). So that question returned false
   * for every popup ever fired.</p>
   *
   * <p>What that cost: {@code showing} is what tells the sidebar fallback to stay quiet for a player
   * the overlay is already reaching. Permanently false meant the fallback never stayed quiet for a
   * popup — the reset countdown drew its number twice, once through BetterHud and once as a vanilla
   * title, and the anti-stacking guard that also asked this question was dead code.</p>
   *
   * <p>{@link #firedPopups} already knows: it holds every popup we have fired against the moment it
   * stops being ours, and it is expired as it is read. Paired with "is BetterHud drawing to this
   * player at all", which is the part only the plugin can answer.</p>
   */
  @Override
  public boolean showingPopup(Player player, String id) {
    return link.available() && player != null && popupLive(player.getUniqueId(), id)
        && api.drawing(player);
  }

  /** Whether {@code id} is in this player's fired-popup ledger and has not yet expired. */
  boolean popupLive(UUID playerId, String id) {
    Map<String, Long> fired = firedPopups.get(playerId);
    if (fired == null) {
      return false;
    }
    Long deadline = fired.get(id);
    return deadline != null && deadline > System.currentTimeMillis();
  }

  /**
   * Records that a player should be wearing this surface, and applies it now.
   *
   * @param popupDurationMillis how long this is a popup for. Zero means a persistent surface: recorded
   *                            as a claim and re-asserted for as long as it is held. Anything positive
   *                            means a popup, which is fired rather than worn — never re-asserted, so
   *                            a reconcile pass cannot resurrect a toast the player already dismissed
   *                            by waiting, but defended from the exclusive strip until it expires
   */
  @Override
  public void claim(Player player, String id, long popupDurationMillis) {
    if (!link.available() || player == null) {
      return;
    }
    if (popupDurationMillis <= 0L) {
      claims.computeIfAbsent(player.getUniqueId(), key -> ConcurrentHashMap.newKeySet()).add(id);
    } else {
      firedPopups.computeIfAbsent(player.getUniqueId(), key -> new ConcurrentHashMap<>())
          .put(id, System.currentTimeMillis() + popupDurationMillis);
    }
    Set<String> owned = owned(player.getUniqueId());
    if (exclusive) {
      // Strip first, add second — and note that `owned` already carries the popup by the time we get
      // here, or this pass would sweep off the very thing it was called to show.
      api.retain(player, owned);
    }
    api.add(player, id);
  }

  /**
   * Drops one claim.
   *
   * <p>Removes the id from the set rather than clearing it: a stale hide arriving after the player
   * has been given a different surface must not take that one down with it.</p>
   */
  @Override
  public void release(UUID playerId, String id) {
    Map<String, Long> fired = firedPopups.get(playerId);
    if (fired != null) {
      fired.remove(id);
      if (fired.isEmpty()) {
        firedPopups.remove(playerId);
      }
    }
    Set<String> owned = claims.get(playerId);
    if (owned == null) {
      return;
    }
    owned.remove(id);
    if (owned.isEmpty()) {
      claims.remove(playerId);
    }
  }

  /** Forgets a player wholesale (they left). */
  void forget(UUID playerId) {
    claims.remove(playerId);
    firedPopups.remove(playerId);
  }

  @Override
  public void hidePopup(Player player, String id) {
    if (link.available()) {
      api.hidePopup(player, id);
    }
  }

  /** Publishes a viewer's rendered rows into their own BetterHud variable map. */
  @Override
  public void pushVariables(Player player, java.util.Map<String, String> values) {
    if (link.available()) {
      api.pushVariables(player, values);
    }
  }

  /** Takes this surface's variables back off a player who has stopped viewing it. */
  @Override
  public void clearVariables(Player player, java.util.Collection<String> keys) {
    if (link.linkable()) {
      api.clearVariables(player, keys);
    }
  }

  /**
   * Re-asserts what this player should be wearing, and in exclusive mode takes off what they should
   * not. Cheap and idempotent — driven from join/world-change/respawn and a slow sweep.
   */
  void reconcile(Player player) {
    if (player == null || !link.available()) {
      return;
    }
    Set<String> owned = owned(player.getUniqueId());
    if (exclusive) {
      // Note this runs even when nothing is claimed, and that empty case is the important one: it is
      // every player in the lobby, and every player in a mode that declares no surface. "Exclusive"
      // means they wear nothing — including BetterHud's own demo hud and its default: true compass,
      // which are exactly what an operator sees riding along otherwise.
      api.retain(player, owned);
    }
    // Re-asserted from the CLAIMS only, never from the wider retain set above. A popup is defended
    // from the strip while it is on screen but must never be re-fired here: doing so would restart a
    // toast whose whole contract is that it expires, and a player could never wait one out.
    for (String id : claims.getOrDefault(player.getUniqueId(), Set.of())) {
      api.add(player, id);
    }
  }


  /**
   * Takes every surface of ours off a player, whatever the operator switch says.
   *
   * <p>Gated on {@link BetterHudLink#linkable()} rather than {@code available()} on purpose: BetterHud
   * persists worn objects to {@code .users/<uuid>.yml} and re-applies them on join, so a claim made
   * while the driver was enabled outlives the operator switching it off. Turning the driver off has to
   * be able to clean up after the driver that was on.</p>
   */
  void purgeOwned(Player player) {
    if (player == null || !link.linkable()) {
      return;
    }
    forget(player.getUniqueId());
    api.purge(player, HudSurfaceSpec.NAMESPACE);
  }

  /**
   * Everything the exclusive strip must leave alone for this player: the surfaces they are wearing,
   * plus whatever popup is still on screen.
   *
   * <p>Expired popup entries are dropped as they are passed, which is the only place they need to be:
   * this is called on every claim and every reconcile, so the map cannot grow, and an entry outliving
   * its popup by one sweep costs nothing — the retain set only decides what NOT to remove.</p>
   */
  private Set<String> owned(UUID playerId) {
    Set<String> owned = new LinkedHashSet<>();
    Set<String> worn = claims.get(playerId);
    if (worn != null) {
      owned.addAll(worn);
    }
    Map<String, Long> fired = firedPopups.get(playerId);
    if (fired != null) {
      long now = System.currentTimeMillis();
      fired.entrySet().removeIf(entry -> entry.getValue() <= now);
      if (fired.isEmpty()) {
        firedPopups.remove(playerId);
      } else {
        owned.addAll(fired.keySet());
      }
    }
    return owned;
  }
}
