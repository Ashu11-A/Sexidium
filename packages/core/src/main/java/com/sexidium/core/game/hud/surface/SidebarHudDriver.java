package com.sexidium.core.game.hud.surface;

import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceKind;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Renders {@link HudSurfaceSpec}s onto the surfaces every platform already has: the scoreboard
 * sidebar for a persistent surface, the action bar / title for a popup.
 *
 * <h2>Why a second driver instead of a nicer first one</h2>
 * No free-floating overlay reaches everybody. BetterHud rides boss bars, which Geyser does not render
 * and which BetterHud itself disables for Floodgate players, so a Bedrock player in the same match as
 * a Java player sees nothing from it — and a server that never installed it sees nothing at all. The
 * old answer was for each consumer to write its HUD twice: once as values pushed at the overlay, once
 * as lines appended to the sidebar. Exactly one consumer ever paid that cost, which is a fair measure
 * of how well it scaled.
 *
 * <p>This driver renders the SAME declaration, so a consumer writes it once. It is never chosen
 * instead of the platform driver — {@link CompositeHudSurface} stacks the two and lets this one draw
 * for precisely the players the platform driver is not reaching.</p>
 *
 * <h2>Honest about what it cannot do</h2>
 * A sidebar has lines, not pixels. {@link #capabilities()} therefore claims TEXT, BAR and POPUP but
 * not ICON or CONDITION: a bar degrades honestly to a run of block glyphs, an icon does not degrade
 * to anything a line of text can carry. Icons and spacers are skipped rather than approximated, and
 * the surrounding rows still render — a decorative element must never cost a consumer its readout.
 */
public final class SidebarHudDriver implements HudDriver {
  private static final Set<HudCapability> CAPABILITIES =
      Set.of(HudCapability.TEXT, HudCapability.BAR, HudCapability.POPUP);

  private final Supplier<GameHud> hudSupplier;
  private final Supplier<UiAdapter> uiSupplier;

  /**
   * @param hudSupplier the game's HUD, resolved lazily — a challenge can open a surface before the
   *                    host has installed one, and a mode with no HUD never installs one at all
   * @param uiSupplier  the platform UI, for the popup path
   */
  public SidebarHudDriver(Supplier<GameHud> hudSupplier, Supplier<UiAdapter> uiSupplier) {
    this.hudSupplier = hudSupplier == null ? () -> null : hudSupplier;
    this.uiSupplier = uiSupplier == null ? () -> null : uiSupplier;
  }

  @Override
  public Set<HudCapability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public HudSurfaceHandle open(HudSurfaceSpec spec) {
    return open(spec, player -> false);
  }

  /**
   * Opens a surface that draws only for the players {@code suppressed} rejects.
   *
   * <p>The predicate is how this driver stays out of the platform driver's way: the composite passes
   * the platform handle's own per-player {@code activeFor}, so a Java player with the corner readout
   * up does not also get the same rows duplicated onto their sidebar.</p>
   */
  public HudSurfaceHandle open(HudSurfaceSpec spec, Predicate<PlayerAdapter> suppressed) {
    if (spec == null) {
      return HudSurfaceHandle.NOOP;
    }
    if (spec.kind() == HudSurfaceKind.POPUP) {
      return new SidebarPopupSurface(spec, uiSupplier, suppressed);
    }
    return new SidebarHudSurface(spec, hudSupplier, suppressed);
  }
}
