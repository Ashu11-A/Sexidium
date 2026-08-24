package com.sexidium.core.world;

/**
 * The lifecycle class of a managed world. Determines how a lease is disposed when it closes and whether
 * a stale-world sweep may remove its folder.
 */
public enum WorldKind {
  /** The persistent lobby. Never disposed, never deleted, always preserved. */
  LOBBY,
  /** A disposable, freshly generated game world. Unloaded and deleted on release. */
  TEMP,
  /** A disposable game world cloned from a pre-built template map. Disposed like {@link #TEMP}. */
  CLONE,
  /** A persistent, player-owned experience world. Unloaded-and-saved on release; never auto-deleted. */
  PERSISTENT;

  /** True when releasing a lease of this kind must keep the world's files on disk. */
  public boolean persistsOnRelease() {
    return this == LOBBY || this == PERSISTENT;
  }

  /** True when a release saves the world rather than discarding it. */
  public boolean savesOnRelease() {
    return this == LOBBY || this == PERSISTENT;
  }
}
