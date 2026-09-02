package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.menu.pack.SexidiumResourcePack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Holds the four copies of "which pack format is this project living on" to their agreement
 * (Plan.md Stage 0c). Each used to carry a comment telling you to keep it in step with the others,
 * and nothing enforced it — which is exactly the drift {@code PackFormats} was created to end, only
 * half-finished:
 *
 * <ol>
 *   <li>{@link SexidiumResourcePack#PACK_FORMAT} — the format the shipped art pack declares (84)</li>
 *   <li>{@link PackFormats#of(String)} — the version→format table (26.1→84, 26.2→88)</li>
 *   <li>{@code BetterHudLink.SUPPORTED_PACK_FORMAT_MIN/MAX} — the BetterHud shader window (84–87)</li>
 *   <li>{@code PAPER_VERSION} in {@code scripts/lib/paper.sh} — the pinned server (26.1.2 → 84)</li>
 * </ol>
 *
 * <p>The F62 relationship is asserted too, because it is the subtle one: the BetterHud window must end
 * BELOW the next known Minecraft format (87 &lt; 88) even though BetterHud itself claims more. If one
 * of these assertions fails, either a bump was half-applied or somebody changed a number without
 * reading docs/reference/known-issues.md F62.</p>
 */
class PackFormatConsistencyTest {

  @Test
  @DisplayName("the shipped pack declares the format of the pinned server version")
  void shippedPackMatchesPinnedServerFormat() {
    int pinned = PackFormats.of("26.1");
    assertTrue(pinned > 0, "PackFormats lost its 26.1 entry");
    assertEquals(pinned, SexidiumResourcePack.PACK_FORMAT,
        "SexidiumResourcePack.PACK_FORMAT and PackFormats('26.1') disagree — bump both together"
            + " (and PAPER_VERSION, and plugin.yml's api-version)");
  }

  @Test
  @DisplayName("the BetterHud window opens at our floor and stops before the next Minecraft")
  void betterHudWindowStaysDeliberatelyNarrower() {
    int nextKnown = PackFormats.of("26.2");
    assumeTrue(nextKnown > 0, "PackFormats no longer knows 26.2; re-derive this test from F62");
    assertEquals(PackFormats.of("26.1"), BetterHudLink.SUPPORTED_PACK_FORMAT_MIN,
        "BetterHud window min must equal the pinned server's format (84)");
    assertTrue(BetterHudLink.SUPPORTED_PACK_FORMAT_MAX < nextKnown,
        "BetterHud window max (" + BetterHudLink.SUPPORTED_PACK_FORMAT_MAX + ") must stay below the"
            + " next Minecraft format (" + nextKnown + ") — its own declared range lies; see F62");
  }

  @Test
  @DisplayName("PAPER_VERSION in scripts/lib/paper.sh resolves to the shipped pack format")
  @EnabledIf("repoRootPresent")
  void scriptPinResolvesToTheShippedFormat() throws IOException {
    Path paperSh = repoRoot().resolve("scripts").resolve("lib").resolve("paper.sh");
    String version = readPinnedPaperVersion(paperSh);
    assertEquals(SexidiumResourcePack.PACK_FORMAT, PackFormats.of(version),
        "PAPER_VERSION=" + version + " implies pack format " + PackFormats.of(version)
            + ", but SexidiumResourcePack ships " + SexidiumResourcePack.PACK_FORMAT
            + " — bump them in the same change (see scripts/lib/paper.sh's own comment)");
  }

  /** The default value inside {@code ${PAPER_VERSION:-26.1.2}}. */
  private static String readPinnedPaperVersion(Path paperSh) throws IOException {
    Pattern pattern = Pattern.compile("^\\s*PAPER_VERSION=\"\\$\\{PAPER_VERSION:-(?<version>[^}\"]+)\\}\"");
    for (String line : Files.readAllLines(paperSh)) {
      Matcher matcher = pattern.matcher(line);
      if (matcher.find()) {
        return matcher.group("version");
      }
    }
    throw new IllegalStateException("no PAPER_VERSION default found in " + paperSh);
  }

  private static Path repoRoot() {
    Path directory = Path.of("").toAbsolutePath();
    while (directory != null && !Files.exists(directory.resolve("scripts").resolve("lib").resolve("paper.sh"))) {
      directory = directory.getParent();
    }
    return directory;
  }

  private static boolean repoRootPresent() {
    return repoRoot() != null;
  }
}
