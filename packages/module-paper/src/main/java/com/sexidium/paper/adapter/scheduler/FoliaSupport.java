package com.sexidium.paper.adapter.scheduler;

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
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException notFolia) {
      return false;
    }
  }
}
