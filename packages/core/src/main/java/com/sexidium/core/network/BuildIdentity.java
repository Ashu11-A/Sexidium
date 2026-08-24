package com.sexidium.core.network;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Which build this process is actually running.
 *
 * <p>There was no build stamp at all: the version is a literal in {@code build.gradle.kts}, and the
 * only real identity in the system was a jar sha256 in {@code server/.provisioned} that the JVM could
 * neither read nor publish. So "did that node come back on the build I staged?" was unanswerable from
 * inside, which is the one question a rolling update has to answer before it moves to the next node.</p>
 *
 * <p>Two halves, each doing what only it can:</p>
 * <ul>
 *   <li>the <b>version</b> comes from {@code build-info.properties}, generated into core's resources
 *       at build time, so it tracks the Gradle version without a second literal to forget;</li>
 *   <li>the <b>build id</b> comes from the runtime property {@code -Dsexidium.build.id} (read through
 *       {@code build.id} in configuration), because that is the value the pipeline that pinned this
 *       node's jar chose. Where the two disagree the runtime property wins — it is race-free and it
 *       is what the orchestrator will correlate on.</li>
 * </ul>
 */
public record BuildIdentity(String version, String buildId, long builtAt) {

  /** What a node publishes when it knows nothing about itself. Never null anywhere. */
  public static final String UNKNOWN_BUILD = "unknown";

  private static final String RESOURCE = "build-info.properties";
  /** {@code network_nodes.plugin_version} is a key column; keep the value inside it by construction. */
  private static final int MAX_PUBLISHED = 191;

  public BuildIdentity {
    version = blankTo(version, "0.0.0");
    buildId = blankTo(buildId, UNKNOWN_BUILD);
  }

  /** The fallback identity: honest about knowing nothing rather than inventing a version. */
  public static BuildIdentity unknown() {
    return new BuildIdentity("0.0.0", UNKNOWN_BUILD, 0L);
  }

  /**
   * Read the generated stamp, then let the runtime build id override it.
   *
   * @param resources     the plugin's own resources; a missing file is not an error
   * @param runtimeBuildId {@code build.id} from configuration, or blank/null when unset
   */
  public static BuildIdentity load(
      com.sexidium.core.platform.ResourceAdapter resources, String runtimeBuildId) {
    BuildIdentity stamped = fromResource(resources);
    String override = normalizeBuildId(runtimeBuildId);
    if (override.isEmpty()) {
      return stamped;
    }
    return new BuildIdentity(stamped.version(), override, stamped.builtAt());
  }

  private static BuildIdentity fromResource(com.sexidium.core.platform.ResourceAdapter resources) {
    if (resources == null) {
      return unknown();
    }
    try (InputStream stream = resources.openResource(RESOURCE).orElse(null)) {
      if (stream == null) {
        // A jar built before this task existed, or a unit test with no resources at all. Both are
        // fine; the node simply publishes "unknown" and an orchestrator sees it did not get a stamp.
        return unknown();
      }
      Properties properties = new Properties();
      properties.load(stream);
      long builtAt;
      try {
        builtAt = Long.parseLong(properties.getProperty("builtAt", "0").trim());
      } catch (NumberFormatException malformed) {
        builtAt = 0L;
      }
      return new BuildIdentity(
          properties.getProperty("version", ""),
          normalizeBuildId(properties.getProperty("buildId", "")),
          builtAt);
    } catch (IOException | RuntimeException unreadable) {
      return unknown();
    }
  }

  /**
   * What goes into {@code network_nodes.plugin_version}, e.g. {@code 1.0.0+a1b2c3d4e5}.
   *
   * <p>This is the race-free build identity and the value a roll should gate on. {@code build_sha} is
   * the cross-check: the jar can be swapped between JVM start and plugin enable, and this cannot.</p>
   */
  public String publishedVersion() {
    String published = version + "+" + buildId;
    return published.length() <= MAX_PUBLISHED ? published : published.substring(0, MAX_PUBLISHED);
  }

  /** Whether this build was stamped at all, i.e. whether {@link #publishedVersion()} means anything. */
  public boolean stamped() {
    return !UNKNOWN_BUILD.equals(buildId);
  }

  /**
   * Build ids cross the wire, land in a key column and are compared for equality by an orchestrator,
   * so they are trimmed, lowercased and stripped of anything that would make two spellings of the
   * same build compare unequal.
   */
  private static String normalizeBuildId(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim().toLowerCase(Locale.ROOT);
    if (trimmed.isEmpty() || UNKNOWN_BUILD.equals(trimmed)) {
      return "";
    }
    StringBuilder cleaned = new StringBuilder(trimmed.length());
    for (int index = 0; index < trimmed.length() && cleaned.length() < 64; index++) {
      char character = trimmed.charAt(index);
      if (Character.isLetterOrDigit(character) || character == '-' || character == '.'
          || character == '_') {
        cleaned.append(character);
      }
    }
    return cleaned.toString();
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }
}
