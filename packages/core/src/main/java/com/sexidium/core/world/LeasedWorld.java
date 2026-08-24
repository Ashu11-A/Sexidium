package com.sexidium.core.world;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;

/**
 * The single {@link WorldLease} implementation, shared by every platform. It carries the backend {@link
 * WorldHandle} and routes {@link #close()} back into the owning {@link AbstractWorldControl}, which
 * applies the kind-specific disposal policy (delete a temp world, unload-and-keep a persistent one).
 * Disposal logic no longer lives in each platform's lease — it is decided once, in the control layer.
 */
public final class LeasedWorld implements WorldLease {
  private final AbstractWorldControl control;
  private final WorldHandle handle;
  private boolean closed;

  LeasedWorld(AbstractWorldControl control, WorldHandle handle) {
    this.control = control;
    this.handle = handle;
  }

  @Override
  public WorldAdapter world() {
    return handle.adapter();
  }

  /** The backend handle this lease wraps (its kind drives disposal). */
  public WorldHandle handle() {
    return handle;
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    control.onLeaseClosed(handle);
  }
}
