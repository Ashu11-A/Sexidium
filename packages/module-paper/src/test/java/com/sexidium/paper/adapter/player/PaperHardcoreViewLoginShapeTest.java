package com.sexidium.paper.adapter.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps {@code KNOWN_LOGIN_COMPONENTS} and the {@code switch} in {@code buildLoginPacket} from drifting
 * apart (Plan.md Stage 0e). The enable-time shape check diffs the running server's record components
 * against the SET — so the set must name exactly what the builder knows how to fill, or the check
 * lies in one direction or the other. This is enforced by reading the source itself: the switch is
 * data as much as the set is.
 */
class PaperHardcoreViewLoginShapeTest {

  /**
   * Absolute, from the {@code sexidium.moduleDir} property the build sets. A relative path here worked
   * only because Gradle's {@code Test} task happens to default its working directory to the project
   * directory — and this build already overrides {@code workingDir} for other task types, so the day
   * someone does it for tests this would have failed as a missing file rather than as the
   * configuration change it was.
   */
  private static final Path SOURCE =
      Path.of(System.getProperty("sexidium.moduleDir", System.getProperty("user.dir", ".")))
          .resolve(Path.of("src", "main", "java", "com", "sexidium", "paper", "adapter", "player",
              "PaperHardcoreView.java"))
          .toAbsolutePath()
          .normalize();

  @Test
  @DisplayName("KNOWN_LOGIN_COMPONENTS names exactly the fields buildLoginPacket can fill")
  void theKnownSetMatchesTheSwitchCases() throws IOException {
    String source = readSource();
    assertEquals(caseLabelsOfBuildLoginPacket(source), knownComponents(source),
        "add every new field to BOTH places: a case in buildLoginPacket (deriving the value from"
            + " the server, never a literal) and its name in KNOWN_LOGIN_COMPONENTS");
  }

  @Test
  @DisplayName("the enable-time shape report answers quietly when NMS is absent")
  void theReportFailsSoftWithoutNms() {
    List<String> report = PaperHardcoreView.loginPacketShapeReport();

    assertNotNull(report);
    assertTrue(report.isEmpty(),
        "no net.minecraft classes exist on this classpath, so there is no SHAPE surprise to report");
  }

  @Test
  @DisplayName("but the CAPABILITY is unavailable there, and says why")
  void theCapabilityIsHonestWithoutNms() {
    // The distinction the registry depends on. An empty shape report means "no disagreement about the
    // shape", which on a server with no ClientboundLoginPacket at all is trivially true — and used to
    // be read as "capability supported", turning a server where bind() had already given up into a
    // green line in the boot log and in /sx admin capabilities.
    Optional<String> reason = PaperHardcoreView.unavailableReason();

    assertTrue(reason.isPresent(), "no NMS on this classpath means no hardcore-view packet");
    assertTrue(reason.get().contains("ClientboundLoginPacket"),
        "the reason an operator reads should name what is missing: " + reason.get());
  }

  @Test
  @DisplayName("a component the set knows but this server lacks is NOT a problem (the set is a union)")
  void aMissingKnownComponentIsNotDrift() {
    // 26.1.2's login packet has 11 components; 26.2's has 12 — it added onlineMode. One jar serves
    // both, so KNOWN_LOGIN_COMPONENTS is the union and the older version is always "missing" one.
    // Reporting that as a shape problem fed straight into the capability gate and put a WORKING
    // hardcore toggle in the boot log as unavailable on the pinned production version.
    List<String> report = PaperHardcoreView.loginPacketShapeReport();

    assertTrue(report.stream().noneMatch(line -> line.contains("no longer has")),
        "the shape check must not report a known-but-absent component: " + report);
  }

  private static String readSource() throws IOException {
    assertTrue(Files.isRegularFile(SOURCE), "PaperHardcoreView.java not found at " + SOURCE);
    return Files.readString(SOURCE);
  }

  /**
   * Every {@code case "<name>" ->} label inside {@code buildLoginPacket} — scoped to that method
   * rather than to the whole file, so a second string switch added to the class later fails with a
   * message about itself instead of about login-packet components.
   */
  private static Set<String> caseLabelsOfBuildLoginPacket(String source) {
    int start = source.indexOf("private static Object buildLoginPacket(");
    assertTrue(start >= 0, "buildLoginPacket went missing");
    int end = source.indexOf("\n  private ", start + 1);
    if (end < 0) {
      end = source.length();
    }
    Set<String> cases = new HashSet<>();
    Matcher matcher = Pattern.compile("case \"([A-Za-z]+)\" ->").matcher(source.substring(start, end));
    while (matcher.find()) {
      cases.add(matcher.group(1));
    }
    assertTrue(!cases.isEmpty(), "no case labels found inside buildLoginPacket");
    return cases;
  }

  /** Every string literal inside the {@code KNOWN_LOGIN_COMPONENTS} initialiser. */
  private static Set<String> knownComponents(String source) {
    int start = source.indexOf("KNOWN_LOGIN_COMPONENTS = Set.of(");
    assertTrue(start >= 0, "KNOWN_LOGIN_COMPONENTS went missing");
    int end = source.indexOf(");", start);
    assertTrue(end > start, "malformed KNOWN_LOGIN_COMPONENTS initialiser");
    Set<String> names = new HashSet<>();
    Matcher matcher = Pattern.compile("\"([A-Za-z]+)\"").matcher(source.substring(start, end));
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }
}
