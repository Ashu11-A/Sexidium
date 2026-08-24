package com.sexidium.core.platform;

import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.Set;

/**
 * Renders {@link HudSurfaceSpec}s. The seam every consumer that wants its own on-screen surface talks
 * to, and the reason "add a HUD element" is now a Java change rather than a yml-authoring exercise.
 *
 * <h2>Drivers are stacked, not chosen</h2>
 * There is a platform driver (BetterHud on Paper) and a core one that renders onto the scoreboard
 * sidebar. Consumers do not pick: they open a surface through the composite, which asks the platform
 * driver first and falls back to the sidebar for every player the platform driver is not reaching.
 * So one declaration covers the Java player with BetterHud installed, the Bedrock player beside them
 * whom BetterHud will not draw for, and the server that never installed it.
 *
 * <h2>Honest capabilities</h2>
 * {@link #capabilities()} is asked before a spec is opened and must describe what this driver can
 * draw <em>right now</em> — not what the underlying plugin advertises. A driver whose backing plugin
 * is installed but mis-matched (BetterHud serving 26.1 shaders to a 26.2 client) reports nothing, and
 * the fallback takes over, instead of the operator getting a row of white boxes.
 */
public interface HudDriver {
  /** The driver every platform that cannot draw a surface returns. Opens nothing, supports nothing. */
  HudDriver NOOP = new HudDriver() {
    @Override
    public Set<HudCapability> capabilities() {
      return Set.of();
    }

    @Override
    public HudSurfaceHandle open(HudSurfaceSpec spec) {
      return HudSurfaceHandle.NOOP;
    }
  };

  /** What this driver can draw right now. Empty means it draws nothing and should not be asked to. */
  Set<HudCapability> capabilities();

  /** Whether this driver can draw one particular thing right now. */
  default boolean supports(HudCapability capability) {
    return capability != null && capabilities().contains(capability);
  }

  /** Whether this driver can draw every element the spec declares. */
  default boolean supports(HudSurfaceSpec spec) {
    return spec != null && spec.drawableBy(capabilities());
  }

  /**
   * Opens a surface. Returns {@link HudSurfaceHandle#NOOP} rather than null when the spec cannot be
   * drawn, so callers chain without a null check — the same contract the tracked-handle family uses.
   */
  HudSurfaceHandle open(HudSurfaceSpec spec);

  /**
   * Releases anything the driver holds that outlives individual surfaces (registered placeholders,
   * generated assets, reconcile timers). Called on shutdown; individual surfaces are closed through
   * their own handles.
   */
  default void close() {
  }
}
