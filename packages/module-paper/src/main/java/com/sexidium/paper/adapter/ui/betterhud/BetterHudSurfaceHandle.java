package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.game.hud.surface.HudValues;
import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudSurfaceKind;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * One surface drawn by BetterHud.
 *
 * <p>Values live here as a plain {@link HudValues} and are rendered into each viewer's own BetterHud
 * variable map on {@link #refresh()} (see {@link BetterHudRows}). The map holds pre-formatted strings,
 * but it is PER PLAYER — so rendering once per viewer is what keeps the readout translatable, and the
 * generated yml carries no words at all.</p>
 */
final class BetterHudSurfaceHandle implements HudSurfaceHandle {
  private final HudSurfaceSpec spec;
  private final SurfaceClaims claims;
  private final BetterHudRows rows;
  private final Function<PlayerAdapter, Player> nativePlayer;
  private final BetterHudDriver owner;
  private final HudValues values = new HudValues();
  private final Map<UUID, PlayerAdapter> viewers = new ConcurrentHashMap<>();
  /**
   * Each viewer's language, captured on the server thread when they were shown the surface.
   *
   * <p>Resolving it lazily would mean calling {@code Player#locale()} from the publisher thread, and
   * that is a Bukkit read from off-thread — the kind that works until it is a region-threaded server
   * and then does not. Captured once here instead; a client that changes its language mid-session
   * picks the new one up the next time the surface is shown to it (rejoin, respawn, world change).</p>
   */
  private final Map<UUID, Language> languages = new ConcurrentHashMap<>();
  private volatile boolean closed;

  BetterHudSurfaceHandle(
      HudSurfaceSpec spec,
      SurfaceClaims claims,
      BetterHudRows rows,
      Function<PlayerAdapter, Player> nativePlayer,
      BetterHudDriver owner
  ) {
    this.spec = spec;
    this.claims = claims;
    this.rows = rows;
    this.nativePlayer = nativePlayer;
    this.owner = owner;
  }

  HudSurfaceSpec spec() {
    return spec;
  }

  /**
   * Whether BetterHud has actually loaded the object our generated yml declares.
   *
   * <p>Asked of the plugin rather than assumed from "we wrote the file": a layout that fails to parse
   * leaves the hud missing, and a surface that reports itself alive on the strength of a successful
   * file write would suppress the sidebar fallback in exactly that case.</p>
   */
  @Override
  public boolean active() {
    return !closed && claims.available() && claims.exists(spec.id());
  }

  /**
   * Whether this player is being drawn to right now.
   *
   * <p>Asks what the player is actually seeing, not what we asked for. BetterHud disables itself for
   * Bedrock players, a player can toggle their HUD off, and a reload can drop a claim — all of which
   * make "we called show()" a different question from "they can see it", and the second one is the one
   * that decides whether their sidebar comes back.</p>
   *
   * <p>{@link #active()} is the first question asked, and for a POPUP it is the load-bearing one: a
   * popup's liveness is answered from OUR ledger rather than from BetterHud, and a ledger entry is
   * written whether or not the object it names ever loaded. Without this conjunct a layout that failed
   * to parse would leave the popup unclaimable by BetterHud, unrenderable by the suppressed fallback,
   * and therefore drawn by nobody — which is worse than the duplicate this gate exists to stop. A worn
   * surface gets the same check for free, because a player cannot be wearing an object that does not
   * exist.</p>
   */
  @Override
  public boolean activeFor(PlayerAdapter playerAdapter) {
    if (!active() || playerAdapter == null || !viewers.containsKey(playerAdapter.uniqueId())) {
      return false;
    }
    Player player = nativePlayer.apply(playerAdapter);
    return player != null && drawnFor(player);
  }

  /**
   * Whether this surface is on {@code player}'s screen — asked the way its KIND has to be asked.
   *
   * <p>A persistent surface is worn, so BetterHud can be asked directly. A popup is fired and is
   * recorded nowhere the "what are you wearing" query can see it, so asking that of a popup answered
   * false however recently it had been shown. That is not a detail: this predicate is what the sidebar
   * fallback is gated on, so a permanently-false answer meant EVERY popup drew twice — the reset
   * countdown as a big number in the middle of the screen from BetterHud and a vanilla title in the
   * middle of the screen from the fallback, both at once. See {@link SurfaceClaims#showingPopup}.</p>
   */
  private boolean drawnFor(Player player) {
    return spec.kind() == HudSurfaceKind.POPUP
        ? claims.showingPopup(player, spec.id())
        : claims.showing(player, spec.id());
  }

  @Override
  public void text(String key, LocalizedText value) {
    values.text(key, value);
  }

  @Override
  public void number(String key, double value) {
    values.number(key, value);
  }

  @Override
  public void flag(String key, boolean value) {
    values.flag(key, value);
  }

  @Override
  public void progress(String key, double value) {
    values.progress(key, value);
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    Player player = nativePlayer.apply(playerAdapter);
    if (closed || player == null) {
      return;
    }
    viewers.put(playerAdapter.uniqueId(), playerAdapter);
    languages.put(playerAdapter.uniqueId(), rows.languageOf(playerAdapter));
    // Publish BEFORE claiming, so the very first frame draws values rather than the literal <none>
    // BetterHud renders for a variable it cannot find. Done inline on the caller's thread rather than
    // waiting for the next publisher pass, so showing a surface is never a frame of placeholder text.
    publish(playerAdapter);
    // A popup is FIRED, not worn — and fired again while the first is still on screen, BetterHud draws
    // a SECOND copy of it beside the first rather than replacing it. A caller that re-shows every
    // second to keep the vanilla title alive (the reset countdown does exactly that, because a title
    // has no memory) was therefore stacking a new number on screen per second. The values are live and
    // already published above, so a popup this player is already being shown needs nothing further.
    if (spec.kind() == HudSurfaceKind.POPUP && claims.showingPopup(player, spec.id())) {
      return;
    }
    claims.claim(player, spec.id(),
        spec.kind() == HudSurfaceKind.POPUP ? Math.max(1L, spec.popupDuration().toMillis()) : 0L);
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    viewers.remove(playerAdapter.uniqueId());
    languages.remove(playerAdapter.uniqueId());
    claims.release(playerAdapter.uniqueId(), spec.id());
    Player player = nativePlayer.apply(playerAdapter);
    if (player == null) {
      return;
    }
    claims.clearVariables(player, BetterHudRows.keys(spec));
    if (spec.kind() == HudSurfaceKind.POPUP) {
      claims.hidePopup(player, spec.id());
    }
  }

  /**
   * Deliberately does nothing.
   *
   * <p>Callers push values from the game loop and call this every cadence; rendering them here would
   * put a MiniMessage parse per row per viewer on the server thread for no benefit, because BetterHud
   * does not read the result until its own redraw. {@link #publishAll()} does that work on the
   * publisher thread instead. The values themselves are already live — {@link HudValues} is
   * concurrent, so a push from the game loop is visible to the publisher the moment it lands.</p>
   */
  @Override
  public void refresh() {
  }

  /**
   * Renders every row for every viewer and publishes it into their own variable map.
   *
   * <p>Called from the driver's publisher thread. Safe off the server thread, and that is a property of
   * the two things it touches rather than an assumption: the values are a concurrent map, and
   * BetterHud's own {@code getVariableMap()} is a {@code ConcurrentHashMap} that BetterHud itself reads
   * from its async redraw. No Bukkit call happens here — the viewer's language was captured when they
   * were shown the surface.</p>
   */
  void publishAll() {
    if (closed) {
      return;
    }
    // One instant for the whole pass: an animated row measures its age against it, and reading the
    // clock per viewer would stagger the same animation across a match.
    long now = System.currentTimeMillis();
    for (PlayerAdapter viewer : viewers.values()) {
      publish(viewer, now);
    }
  }

  private void publish(PlayerAdapter viewer) {
    publish(viewer, System.currentTimeMillis());
  }

  private void publish(PlayerAdapter viewer, long nowMillis) {
    Player player = nativePlayer.apply(viewer);
    if (player != null) {
      claims.pushVariables(player, rows.render(spec, values, language(viewer), nowMillis));
    }
  }

  private Language language(PlayerAdapter viewer) {
    return languages.getOrDefault(viewer.uniqueId(), Language.EN);
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    owner.forget(this);
    for (Map.Entry<UUID, PlayerAdapter> viewer : Map.copyOf(viewers).entrySet()) {
      claims.release(viewer.getKey(), spec.id());
      Player player = nativePlayer.apply(viewer.getValue());
      if (player != null) {
        claims.clearVariables(player, BetterHudRows.keys(spec));
      }
    }
    viewers.clear();
    languages.clear();
  }
}
