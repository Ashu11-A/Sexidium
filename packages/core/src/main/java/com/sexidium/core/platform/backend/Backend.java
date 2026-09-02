package com.sexidium.core.platform.backend;

import java.util.Set;

/**
 * One swappable implementation behind a runtime-selected seam — the generalised form of what
 * {@code HudDriverStack} does for HUD surfaces: a plugin-backed implementation on Paper (BetterHud,
 * FancyNpcs, …), a core-native one elsewhere, and an inert no-op wherever neither exists.
 *
 * <h2>Honest capabilities</h2>
 * {@link #capabilities()} must describe what THIS backend can serve <em>right now</em> — never what
 * the underlying plugin advertises. A backend whose backing plugin is installed but mis-matched
 * (BetterHud serving 26.1 shaders to a 26.2 client) reports nothing, and whoever consumes the stack
 * takes the fallback, instead of the operator getting garbage drawn by something that should have
 * known better. This distinction — installed vs capable — is the whole point of the abstraction.
 *
 * @param <T> the capability vocabulary this backend reports in (e.g. {@code HudCapability})
 */
public interface Backend<T> {

  /** What this backend can serve right now. Empty means it serves nothing and must not be asked to. */
  Set<T> capabilities();

  /** Whether this backend can serve {@code capability} right now. Null answers false. */
  default boolean supports(T capability) {
    return capability != null && capabilities().contains(capability);
  }

  /**
   * Releases anything the backend holds that outlives individual requests (spawned entities,
   * generated assets, reconcile timers). Called once on shutdown; individual requests are released
   * through their own handles.
   */
  default void close() {
  }
}
