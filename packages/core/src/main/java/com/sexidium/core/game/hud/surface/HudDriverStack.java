package com.sexidium.core.game.hud.surface;

import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.EnumSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The driver every consumer actually opens surfaces through: the platform's driver stacked over the
 * core {@link SidebarHudDriver}, so one declaration reaches every player on whichever surface can
 * carry it.
 *
 * <p>This is the whole modularity story in one class. A consumer declares a
 * {@link HudSurfaceSpec} and gets back a handle; whether that renders as a BetterHud layout in the
 * corner, as lines on the scoreboard, or as both for different players in the same match, is a
 * question it never has to ask.</p>
 */
public final class HudDriverStack implements HudDriver {
  private final HudDriver platform;
  private final SidebarHudDriver sidebar;

  public HudDriverStack(HudDriver platform, Supplier<GameHud> hudSupplier, Supplier<UiAdapter> uiSupplier) {
    this.platform = platform == null ? HudDriver.NOOP : platform;
    this.sidebar = new SidebarHudDriver(hudSupplier, uiSupplier);
  }

  /** Everything either half can draw — a spec only needs one of them to be renderable. */
  @Override
  public Set<HudCapability> capabilities() {
    EnumSet<HudCapability> capabilities = EnumSet.noneOf(HudCapability.class);
    capabilities.addAll(platform.capabilities());
    capabilities.addAll(sidebar.capabilities());
    return capabilities;
  }

  @Override
  public HudSurfaceHandle open(HudSurfaceSpec spec) {
    if (spec == null) {
      return HudSurfaceHandle.NOOP;
    }
    // supports() rather than a blind open: a driver that cannot draw every element of this spec would
    // render a partial readout, which is worse than the fallback rendering all of it.
    HudSurfaceHandle platformHandle =
        platform.supports(spec) ? platform.open(spec) : HudSurfaceHandle.NOOP;
    HudSurfaceHandle fallbackHandle = sidebar.open(spec, platformHandle::activeFor);
    return new CompositeHudSurface(platformHandle, fallbackHandle);
  }

  @Override
  public void close() {
    platform.close();
    sidebar.close();
  }
}
