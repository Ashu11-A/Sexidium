package com.sexidium.core.testing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The golden API-surface check (Plan.md Stage 0d), shared by every platform adapter.
 *
 * <p>Every {@code com.sexidium.core} type an adapter module names in its main sources is diffed against
 * a checked-in list. Nothing else stops the adapter/core boundary widening by one type per commit until
 * it has swallowed everything; this makes each widening a REVIEWED one-line diff instead of an
 * invisible one. It is 90% of what a separate {@code core-api} Gradle module would buy, at 1% of the
 * cost — an internal boundary is a package plus a test, not a Maven coordinate.</p>
 *
 * <h2>Why a base class and not a copy per module</h2>
 * It <em>was</em> a copy per module, and the copies were already diverging (one had extracted helpers,
 * the other had the same code inline). Both carried the same two holes, so both had to be found twice
 * and fixed twice. A subclass now supplies three facts — where its sources are, where its golden file
 * is, what to call it — and inherits one implementation of the part that is easy to get subtly wrong.
 *
 * <h2>Names, not imports</h2>
 * The scan matches every fully-qualified {@code com.sexidium.core} name in the source text, not just
 * {@code import} lines. Import-only scanning has a hole wide enough to drive the whole boundary
 * through: a type used by its fully-qualified name needs no import and was therefore invisible, and
 * eight core types were already through it — a field type in the Velocity plugin, a return type and an
 * anonymous implementation in the Paper adapter, and more. Anyone who wanted to dodge a golden-file
 * review only had to write the long name. Now the long name is exactly what is recorded.
 *
 * <h2>When it fails</h2>
 * You added or removed a reference to a core type. If that is intended, regenerate with
 * {@code -Dsexidium.updateGolden=true} (the module's test task forwards it to the test JVM) and commit
 * the golden diff alongside the change. If it is NOT intended, keep the core type out of the adapter
 * and push the seam down into core instead.
 */
public abstract class AbstractCoreApiSurfaceTest {

  /**
   * Every fully-qualified core name: the package prefix, any number of lowercase package segments, then
   * one or more capitalised type segments. Stopping at capitalisation is what keeps
   * {@code WorldKey.of(...)} from being recorded as a type called {@code of}.
   */
  private static final Pattern CORE_NAME = Pattern.compile(
      "\\bcom\\.sexidium\\.core\\.(?:[a-z][A-Za-z0-9_]*\\.)*[A-Z][A-Za-z0-9_]*(?:\\.[A-Z][A-Za-z0-9_]*)*");

  /** A wildcard import of a core package — one golden entry that then licenses unlimited widening. */
  private static final Pattern WILDCARD_IMPORT = Pattern.compile(
      "^\\s*import\\s+(?:static\\s+)?com\\.sexidium\\.core\\.[A-Za-z0-9_.]*\\*\\s*;");

  /** System property that makes a run rewrite its golden file instead of asserting against it. */
  private static final String UPDATE_GOLDEN = "sexidium.updateGolden";

  /** Where this module's {@code src/main/java} lives. Absolute, so the test does not depend on cwd. */
  protected abstract Path mainSources();

  /** Where this module's checked-in golden list lives. Absolute. */
  protected abstract Path goldenFile();

  /** How this module is named in failure messages, e.g. {@code :packages:module-paper}. */
  protected abstract String moduleName();

  @Test
  void coreSurfaceStaysGolden() throws IOException {
    Path sources = mainSources();
    assertTrue(Files.isDirectory(sources),
        moduleName() + ": main sources not found at " + sources + ". The test resolves them from the"
            + " sexidium.moduleDir system property the build sets; if that is missing it falls back to"
            + " the working directory, which is what this failure usually means.");

    List<String> actual = scan(sources);
    if (Boolean.getBoolean(UPDATE_GOLDEN)) {
      writeGolden(actual);
      return;
    }
    assertNoDrift(readGolden(), actual);
  }

  /** Every distinct core type named under {@code sources}, sorted. Rejects wildcard imports outright. */
  private List<String> scan(Path sources) throws IOException {
    try (Stream<Path> files = Files.walk(sources)) {
      Set<String> names = new TreeSet<>();
      List<String> wildcards = new ArrayList<>();
      files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
        String text = read(path);
        text.lines()
            .filter(line -> WILDCARD_IMPORT.matcher(line).find())
            .forEach(line -> wildcards.add(sources.relativize(path) + ": " + line.trim()));
        Matcher matcher = CORE_NAME.matcher(text);
        while (matcher.find()) {
          names.add(trimConstants(matcher.group()));
        }
      });
      if (!wildcards.isEmpty()) {
        // A wildcard would record ONE golden entry and then license every type in that package, which
        // is the opposite of what this test is for. There are none today; keep it that way.
        fail(moduleName() + ": wildcard imports of core packages defeat the golden surface — one entry"
            + " would cover every type in the package. Import the types you use:\n  "
            + String.join("\n  ", wildcards));
      }
      return List.copyOf(names);
    }
  }

  /**
   * Drops trailing ALL-CAPS segments, which are constants rather than nested types:
   * {@code MessageKey.AUTH_LOGIN_ERROR} is a use of {@code MessageKey}, not of a type by that name.
   * A real nested type ({@code NodeRegistry.Node}) has a lowercase letter and survives.
   */
  private static String trimConstants(String name) {
    List<String> parts = new ArrayList<>(List.of(name.split("\\.")));
    // Never trim past "com.sexidium.core.X" — the first four segments are the package prefix and the
    // one segment that is guaranteed to be a type.
    while (parts.size() > 4 && parts.getLast().chars().noneMatch(Character::isLowerCase)) {
      parts.removeLast();
    }
    return String.join(".", parts);
  }

  private static String read(Path source) {
    try {
      return Files.readString(source, StandardCharsets.UTF_8);
    } catch (IOException unreadable) {
      throw new IllegalStateException("could not read " + source, unreadable);
    }
  }

  private List<String> readGolden() throws IOException {
    Path golden = goldenFile();
    if (!Files.isRegularFile(golden)) {
      throw new IllegalStateException(moduleName() + ": missing golden file " + golden);
    }
    return Files.readAllLines(golden, StandardCharsets.UTF_8).stream()
        .map(String::trim)
        .filter(line -> !line.isEmpty())
        .sorted()
        .toList();
  }

  private void writeGolden(List<String> names) throws IOException {
    Path golden = goldenFile();
    Files.createDirectories(golden.getParent());
    Files.writeString(golden, String.join("\n", names) + "\n", StandardCharsets.UTF_8);
  }

  /**
   * The failure message IS the point: it names exactly which types appeared or disappeared, so the
   * reviewer reads the diff without running anything.
   */
  private void assertNoDrift(List<String> golden, List<String> actual) {
    List<String> added = actual.stream().filter(name -> !golden.contains(name)).toList();
    List<String> removed = golden.stream().filter(name -> !actual.contains(name)).toList();
    assertTrue(added.isEmpty() && removed.isEmpty(),
        () -> moduleName() + "'s surface onto com.sexidium.core changed.\n"
            + "ADDED   (" + added.size() + "):\n  " + String.join("\n  ", added) + "\n"
            + "REMOVED (" + removed.size() + "):\n  " + String.join("\n  ", removed) + "\n"
            + "If intended, regenerate the golden file:\n"
            + "  ./gradlew " + moduleName() + ":test --tests '*ApiSurface*' -D" + UPDATE_GOLDEN + "=true\n"
            + "and commit " + goldenFile().getFileName() + " together with your change.");
  }

  /**
   * This module's directory, from the {@code sexidium.moduleDir} property the build sets, falling back
   * to the working directory.
   *
   * <p>The fallback is not the primary path on purpose. Gradle's {@code Test} task happens to default
   * its working directory to the project directory, but the root build already overrides
   * {@code workingDir} for other task types here — the day someone does it for tests, a relative
   * {@code Path.of("src", "main", "java")} turns into a {@code NoSuchFileException} that reads like a
   * missing file rather than the configuration change it is.</p>
   */
  protected static Path moduleDir() {
    return Path.of(System.getProperty("sexidium.moduleDir", System.getProperty("user.dir", ".")))
        .toAbsolutePath()
        .normalize();
  }
}
