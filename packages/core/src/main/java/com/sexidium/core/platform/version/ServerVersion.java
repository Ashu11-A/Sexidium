package com.sexidium.core.platform.version;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Minecraft version of the running server, parsed into comparable parts.
 *
 * <p>An immutable value carried through {@link ServerVersionPort}. {@code raw} preserves exactly what
 * the platform reported; {@code major}/{@code minor}/{@code patch} are what little structure can be
 * trusted to compare. A version that cannot be parsed is {@link #UNKNOWN} — never a guess.
 *
 * <p>Version numbers are the tiebreaker for what cannot be probed (pack formats), never the primary
 * key: wherever a capability can be probed at runtime, probe it instead of comparing here.
 */
public record ServerVersion(int major, int minor, int patch, String raw) {

  /** The version nothing could read. {@link #known()} is false; every comparison answers false. */
  public static final ServerVersion UNKNOWN = new ServerVersion(0, 0, 0, "");

  private static final Pattern LEADING_NUMBERS = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?");

  /**
   * Parses {@code "26.1.2"}, {@code "26.2"} or {@code "1.21.4-R0.1-SNAPSHOT"} alike: up to three
   * leading dot-separated integers, trailing build metadata ignored. Anything else is {@link #UNKNOWN}.
   *
   * <p>"Anything else" includes a component too long to be a version number. The regex group is
   * unbounded, so {@code "99999999999.1"} matches and {@code Integer.parseInt} would throw — out of a
   * method whose whole contract is that it does not. A component that cannot be an int is simply not a
   * version, so it takes the {@link #UNKNOWN} path like every other unparseable string.</p>
   */
  public static ServerVersion parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return UNKNOWN;
    }
    Matcher matcher = LEADING_NUMBERS.matcher(raw.trim());
    if (!matcher.find() || matcher.group(1) == null) {
      return UNKNOWN;
    }
    int major = part(matcher.group(1));
    int minor = part(matcher.group(2));
    int patch = part(matcher.group(3));
    if (major < 0 || minor < 0 || patch < 0) {
      return UNKNOWN;
    }
    return new ServerVersion(major, minor, patch, raw.trim());
  }

  /** One dot-separated component: absent is 0, unparseable (overflow) is -1. Never throws. */
  private static int part(String group) {
    if (group == null) {
      return 0;
    }
    try {
      return Integer.parseInt(group);
    } catch (NumberFormatException tooLongToBeAVersion) {
      return -1;
    }
  }

  /** False only for {@link #UNKNOWN}: a version was actually read and parsed. */
  public boolean known() {
    return major > 0;
  }

  /** Whether this version is {@code major.minor} or newer. Unknown versions are never "at least". */
  public boolean atLeast(int major, int minor) {
    if (!known()) {
      return false;
    }
    if (this.major != major) {
      return this.major > major;
    }
    return this.minor >= minor;
  }

  @Override
  public String toString() {
    return known() ? raw : "unknown";
  }
}
