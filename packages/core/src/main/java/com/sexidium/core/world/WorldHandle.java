package com.sexidium.core.world;

import com.sexidium.core.platform.WorldAdapter;

import java.nio.file.Path;

/**
 * A backend's opaque reference to one live, managed world. It pairs the platform-agnostic {@link
 * WorldAdapter} (used by games and challenges) with the bookkeeping the control layer needs to dispose
 * the world correctly: its canonical runtime name (what the rest of the core matches on), its kind, and
 * its real on-disk chunk folder.
 *
 * <p>The control layer never touches a native {@code World}/{@code ServerLevel}; it routes every raw
 * operation back through the backend keyed by this handle.</p>
 */
public interface WorldHandle {
  /** The canonical runtime name ({@code <namespace>/<key>}); equals {@link WorldAdapter#name()}. */
  String runtimeName();

  /** The lifecycle class of this world, decided when it was acquired. */
  WorldKind kind();

  /** The platform-agnostic per-world operations facade. */
  WorldAdapter adapter();

  /**
   * The real on-disk folder holding this world's chunk data, or {@code null} when the backend cannot
   * report it. On MC 26.1+ unified dimension storage this is {@code world/dimensions/<namespace>/<key>},
   * NOT a rebuilt top-level path — so a backend should resolve it from the live world handle, not from a
   * name. Used only to delete a disposable world's files on release.
   */
  Path canonicalFolder();
}
