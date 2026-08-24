package com.sexidium.core.network;

import com.sexidium.core.platform.ResourceAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildIdentityTest {

  private static ResourceAdapter resource(String contents) {
    return path -> contents == null ? Optional.empty()
        : Optional.<InputStream>of(new ByteArrayInputStream(contents.getBytes(StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("a missing build stamp is 'unknown', not a crash and not an invented version")
  void buildInfoFallsBackWhenResourceMissing() {
    BuildIdentity identity = BuildIdentity.load(resource(null), "");
    assertEquals("0.0.0", identity.version());
    assertEquals(BuildIdentity.UNKNOWN_BUILD, identity.buildId());
    assertFalse(identity.stamped());
    // A node on an older build publishes exactly this, and a reader must be able to tell.
    assertEquals("0.0.0+unknown", identity.publishedVersion());
  }

  @Test
  @DisplayName("a null resource adapter is survivable — a unit test has no resources at all")
  void nullResourcesAreSurvivable() {
    assertEquals(BuildIdentity.unknown(), BuildIdentity.load(null, ""));
  }

  @Test
  @DisplayName("the stamped version and build id become plugin_version")
  void stampedBuildIsPublished() {
    BuildIdentity identity =
        BuildIdentity.load(resource("version=1.0.0\nbuildId=a1b2c3d4e5\nbuiltAt=1786012345\n"), "");
    assertEquals("1.0.0+a1b2c3d4e5", identity.publishedVersion());
    assertEquals(1786012345L, identity.builtAt());
    assertTrue(identity.stamped());
  }

  @Test
  @DisplayName("the RUNTIME build id wins over the stamp — it is the value the pipeline chose")
  void runtimeBuildIdOverridesTheStamp() {
    // -Dsexidium.build.id is written per node by whatever pinned that node's jar, so it cannot race
    // a rebuild the way the baked-in stamp can.
    BuildIdentity identity =
        BuildIdentity.load(resource("version=1.0.0\nbuildId=stale\n"), "FRESH-7f3a");
    assertEquals("1.0.0+fresh-7f3a", identity.publishedVersion());
  }

  @Test
  @DisplayName("a blank runtime id leaves the stamp alone rather than blanking it")
  void blankRuntimeIdKeepsTheStamp() {
    assertEquals("1.0.0+stamped",
        BuildIdentity.load(resource("version=1.0.0\nbuildId=stamped\n"), "   ").publishedVersion());
  }

  @Test
  @DisplayName("a build id is normalised, because an orchestrator compares it for equality")
  void buildIdsAreNormalised() {
    // Two spellings of one build must not compare unequal, and the value lands in a key column.
    assertEquals("1.0.0+abc-123.4_x",
        BuildIdentity.load(resource("version=1.0.0\n"), " ABC-123.4_x ").publishedVersion());
    assertEquals("1.0.0+abcdef",
        BuildIdentity.load(resource("version=1.0.0\n"), "ab/cd ef").publishedVersion());
  }

  @Test
  @DisplayName("a malformed builtAt is ignored rather than failing the whole identity")
  void malformedTimestampIsIgnored() {
    BuildIdentity identity =
        BuildIdentity.load(resource("version=2.0.0\nbuildId=x\nbuiltAt=not-a-number\n"), "");
    assertEquals(0L, identity.builtAt());
    assertEquals("2.0.0+x", identity.publishedVersion());
  }

  @Test
  @DisplayName("published version fits network_nodes.plugin_version by construction")
  void publishedVersionIsBounded() {
    assertTrue(BuildIdentity.load(resource("version=" + "9".repeat(300) + "\n"), "x")
        .publishedVersion().length() <= 191);
  }
}
