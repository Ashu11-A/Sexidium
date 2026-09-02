package com.sexidium.core.game.hud.surface;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;

/**
 * One surface drawn by two drivers: the platform's where it reaches a player, the sidebar's where it
 * does not. The consumer holds a single handle and never learns which is which.
 *
 * <h2>The split, and why it is per player</h2>
 * {@link #activeFor(PlayerAdapter)} answers for the PLATFORM half only, which is deliberate and is
 * the contract everything downstream depends on. It is the answer to "is this player's readout being
 * drawn somewhere other than the sidebar", which is what decides whether that player's sidebar should
 * be suppressed. If it also counted the fallback it would always be true, every sidebar would be
 * suppressed, and the fallback would be suppressing the very surface it renders on.
 *
 * <p>The fallback is handed exactly that predicate as its own suppression gate, so the two halves can
 * never both draw for the same player, and every player is covered by one of them.</p>
 */
final class CompositeHudSurface implements HudSurfaceHandle {
  private final HudSurfaceHandle platform;
  private final HudSurfaceHandle fallback;

  /**
   * @param platform the platform driver's handle — may be {@link HudSurfaceHandle#NOOP}, which is the
   *                 ordinary case on a server with no overlay plugin
   * @param fallback the sidebar handle, already gated on {@code platform::activeFor}
   */
  CompositeHudSurface(HudSurfaceHandle platform, HudSurfaceHandle fallback) {
    this.platform = platform == null ? HudSurfaceHandle.NOOP : platform;
    this.fallback = fallback == null ? HudSurfaceHandle.NOOP : fallback;
  }

  @Override
  public boolean active() {
    return platform.active();
  }

  @Override
  public boolean activeFor(PlayerAdapter playerAdapter) {
    return platform.activeFor(playerAdapter);
  }

  /** Whether this surface is reaching the player at all, by either half. */
  boolean drawnFor(PlayerAdapter playerAdapter) {
    return platform.activeFor(playerAdapter) || fallback.activeFor(playerAdapter);
  }

  @Override
  public void text(String key, LocalizedText value) {
    platform.text(key, value);
    fallback.text(key, value);
  }

  @Override
  public void number(String key, double value) {
    platform.number(key, value);
    fallback.number(key, value);
  }

  @Override
  public void flag(String key, boolean value) {
    platform.flag(key, value);
    fallback.flag(key, value);
  }

  @Override
  public void blank(String key, boolean blanked) {
    platform.blank(key, blanked);
    fallback.blank(key, blanked);
  }

  @Override
  public void progress(String key, double value) {
    platform.progress(key, value);
    fallback.progress(key, value);
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    // Both halves are shown unconditionally. Which one actually draws is decided per render pass by
    // the fallback's gate, so a player whose platform surface fails, or comes up late, or goes away
    // when the operator switches the plugin off mid-session, moves between the two on their own.
    platform.show(playerAdapter);
    fallback.show(playerAdapter);
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    platform.hide(playerAdapter);
    fallback.hide(playerAdapter);
  }

  @Override
  public void refresh() {
    platform.refresh();
    fallback.refresh();
  }

  @Override
  public void close() {
    platform.close();
    fallback.close();
  }
}
