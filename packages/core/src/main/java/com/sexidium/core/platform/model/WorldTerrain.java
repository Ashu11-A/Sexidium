package com.sexidium.core.platform.model;

import java.util.Locale;

/**
 * Which vanilla world-generation preset a world's terrain is built with — the same choice the vanilla
 * "world type" button offers when creating a single-player world. Decided once, at world creation, and
 * baked into the terrain from then on: changing it later would only affect chunks that have not been
 * generated yet, so an existing world never switches.
 *
 * <p>Distinct from {@link WorldDimension} (which of a world's linked dimensions you are in) and from a
 * VOID world (no natural terrain at all — see {@code WorldSettings.voidWorld}, used by the SkyBlock-style
 * experiences that build their own map). A platform that cannot select a preset simply generates
 * {@link #NORMAL}.</p>
 */
public enum WorldTerrain {
  /** Ordinary terrain generation — the default for every world. */
  NORMAL,
  /** Superflat: flat layers to the horizon (vanilla {@code FLAT}). */
  SUPERFLAT,
  /** Large Biomes: normal generation with every biome scaled up (vanilla {@code LARGE_BIOMES}). */
  LARGE_BIOMES,
  /** Amplified: extreme mountains and canyons (vanilla {@code AMPLIFIED}). */
  AMPLIFIED;

  /** Parses a terrain name (case-insensitive, {@code -}/{@code _} agnostic); unknown → {@link #NORMAL}. */
  public static WorldTerrain of(String value) {
    if (value == null || value.isBlank()) {
      return NORMAL;
    }
    String wanted = value.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    return switch (wanted) {
      case "superflat", "flat" -> SUPERFLAT;
      case "largebiomes", "large" -> LARGE_BIOMES;
      case "amplified" -> AMPLIFIED;
      default -> NORMAL;
    };
  }
}
