package com.sexidium.paper.adapter.ui.betterhud;

/**
 * Maps a Minecraft version string to its resource-pack format number.
 *
 * <p>Pure and tiny on purpose: the only reason it exists is that the pack format is what decides
 * whether BetterHud's bundled shaders match the client, and that decision was previously encoded as
 * prose in four separate places — a javadoc block, a startup warning string, a config comment and a
 * shell {@code case} statement — with nothing keeping them in step.</p>
 *
 * <p>Only the versions this project has actually been run against are listed. Anything else returns
 * {@code -1}, meaning "no opinion": a version we cannot place must not be read as a failure, because
 * refusing to draw on the strength of an unrecognised string would break a working server over a
 * parsing detail.</p>
 */
final class PackFormats {
  private PackFormats() {
  }

  /** @return the pack format for {@code minecraftVersion}, or -1 when it is not one we can place. */
  static int of(String minecraftVersion) {
    if (minecraftVersion == null || minecraftVersion.isBlank()) {
      return -1;
    }
    String version = minecraftVersion.trim();
    return switch (majorMinor(version)) {
      case "26.1" -> 84;
      case "26.2" -> 88;
      default -> -1;
    };
  }

  /** {@code 26.1.2} and {@code 26.1} alike reduce to {@code 26.1}; the patch never moves the format. */
  private static String majorMinor(String version) {
    int first = version.indexOf('.');
    if (first < 0) {
      return version;
    }
    int second = version.indexOf('.', first + 1);
    return second < 0 ? version : version.substring(0, second);
  }
}
