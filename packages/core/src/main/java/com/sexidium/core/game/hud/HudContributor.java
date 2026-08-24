package com.sexidium.core.game.hud;

/**
 * One source's contribution to the unified per-player {@link GameHud} — a challenge's live readout, a
 * minigame's scoreboard, the shared team card, … Registered via {@link GameHud#register}; the host
 * calls {@link #describe} for each online participant on every HUD refresh and renders the appended
 * lines into the single center-right panel. Contributors run in ascending {@link #order()}.
 *
 * <p>A contributor reads {@link HudContext#view()} (or the {@link HudContext#compact()} shorthand) to
 * trim its output for the COMPACT view; it never sees a HIDDEN view (the panel is closed instead).</p>
 */
public interface HudContributor {
  /** Append this source's lines for {@code context.player()} in the context's current view. */
  void describe(HudContext context);

  /** Ascending; lower renders higher up the panel. */
  default int order() {
    return 0;
  }
}
