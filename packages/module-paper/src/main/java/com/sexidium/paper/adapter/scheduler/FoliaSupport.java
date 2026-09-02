package com.sexidium.paper.adapter.scheduler;

import com.sexidium.paper.adapter.util.PlatformProbes;

/**
 * Runtime detection of Folia, the region-threaded Paper fork. Detected once by the presence of a
 * Folia-only server class. Used to branch only where genuinely necessary — the schedulers themselves are
 * available on both regular Paper and Folia, so most code needs no branch.
 */
public final class FoliaSupport {
  private static final boolean FOLIA = detect();

  private FoliaSupport() {}

  public static boolean isFolia() {
    return FOLIA;
  }

  private static boolean detect() {
    // Through the shared probe rather than a local try/catch: initialize=false (linking is the
    // question, not running the class's static work) and LinkageError caught alongside
    // ClassNotFoundException, because "present but unloadable" is still not-Folia for our purposes.
    return PlatformProbes.linkable("io.papermc.paper.threadedregions.RegionizedServer",
        FoliaSupport.class.getClassLoader());
  }
}
