package com.sexidium.core.game.hud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Accumulates the HUD lines every {@link HudContributor} wants to show for one player during a render
 * pass. A contributor appends styled MiniMessage fragments here; the host renders them into the single
 * per-player {@link GameHud} panel. Keeping every source's info on one panel is the point — no more
 * scattered boss bars and action bars.
 *
 * <p>{@link #view()} carries the player's current {@link HudView} so a contributor can render a trimmed
 * COMPACT section (e.g. only the viewer's own team) from the same {@link HudContributor#describe}
 * method; {@link #compact()} is the convenience shorthand.</p>
 */
public final class HudContext {
  private final PlayerAdapter player;
  private final boolean debug;
  private final HudView view;
  // Each line is a LocalizedText so a contributor can emit a per-viewer localized MessageKey template
  // (Race scores, the team card) OR a raw MiniMessage fragment (wrapped in GAME_HUD_LINE). The panel
  // renders each LocalizedText in the viewer's own language, and embedded <lang:item.*> tags still
  // localize client-side.
  private final List<LocalizedText> lines = new ArrayList<>();

  public HudContext(PlayerAdapter player) {
    this(player, false, HudView.FULL);
  }

  public HudContext(PlayerAdapter player, boolean debug) {
    this(player, debug, HudView.FULL);
  }

  public HudContext(PlayerAdapter player, boolean debug, HudView view) {
    this.player = player;
    this.debug = debug;
    this.view = view == null ? HudView.FULL : view;
  }

  public PlayerAdapter player() {
    return player;
  }

  /** The viewer's current HUD view — FULL or COMPACT (a HIDDEN panel is never rendered/described). */
  public HudView view() {
    return view;
  }

  /** Shorthand for {@code view() == HudView.COMPACT}: render the trimmed "just my info" variant. */
  public boolean compact() {
    return view == HudView.COMPACT;
  }

  /**
   * Whether the global {@code debug} flag is on for this render pass. Sources gate their internal
   * diagnostic readouts (tuning knobs, live force/timer state) on this so players never see the
   * implementation detail unless an operator is debugging.
   */
  public boolean debug() {
    return debug;
  }

  /** Append a raw MiniMessage line (wrapped in the shared GAME_HUD_LINE pass-through). */
  public void line(String miniMessage) {
    if (miniMessage != null && !miniMessage.isBlank()) {
      lines.add(LocalizedText.of(MessageKey.GAME_HUD_LINE, MessageArg.mini("text", miniMessage)));
    }
  }

  /**
   * Append a fully localized line — a MessageKey template rendered per viewer (so static labels are
   * translated, not just embedded {@code <lang:item.*>} item names). Used by the minigame scoreboard and
   * the shared team card, whose labels live in the language files.
   */
  public void line(LocalizedText localizedText) {
    if (localizedText != null) {
      lines.add(localizedText);
    }
  }

  /** Append a {@code label: value} line in the shared HUD style. */
  public void stat(String label, Object value) {
    line("<gray>" + label + ":</gray> <white>" + value + "</white>");
  }

  /**
   * Append a debug-only {@code label: value} line — dimmed and prefixed so it reads as a diagnostic,
   * not a player-facing stat. No-op unless {@link #debug()} is on, so callers can list every
   * internal knob unconditionally and trust it stays hidden in production.
   */
  public void debugStat(String label, Object value) {
    if (debug) {
      line("<dark_gray>⚙ " + label + ":</dark_gray> <gray>" + value + "</gray>");
    }
  }

  /** Append a raw MiniMessage line only when {@link #debug()} is on. */
  public void debugLine(String miniMessage) {
    if (debug) {
      line(miniMessage);
    }
  }

  /** Append a dimmed debug section header for a source's diagnostics. No-op unless {@link #debug()}. */
  public void debugHeader(String name) {
    if (debug) {
      line("<dark_gray>⚙ " + name + " · debug</dark_gray>");
    }
  }

  /** Append a section header for a source. */
  public void header(String name) {
    line("<dark_gray>▎</dark_gray> <#b06bff>" + name + "</#b06bff>");
  }

  public List<LocalizedText> lines() {
    return lines;
  }
}
