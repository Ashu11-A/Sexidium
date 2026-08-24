package com.sexidium.core.world;

import com.sexidium.core.platform.ResourceAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The provisioner's contract with this CLI, pinned: what it writes, what it prints, and — the part a
 * shell script actually branches on — what it exits with. {@code docker/provision.sh} is written against
 * these codes, so a change that breaks one of these tests breaks a deployment, not a test.
 */
class MapBundleCliTest {
  @Test
  void seedsAnEmptyDirectoryAndNamesEveryMapItWrote(@TempDir Path mapsDir) throws IOException {
    FakeResources resources = twoMaps();
    Run run = run(resources, mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_OK, run.code());
    assertTrue(Files.exists(mapsDir.resolve("tntwar/tnt-wars/level.dat")));
    assertEquals("red", Files.readString(mapsDir.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
    assertTrue(Files.exists(mapsDir.resolve("tntwar/summer-tntwars/level.dat")));
    assertTrue(run.out().contains("seeded    tntwar/tnt-wars"), run.out());
    assertTrue(run.out().contains("seeded=2 refreshed=0 current=0 failed=0"), run.out());
  }

  @Test
  void createsTheTargetDirectoryItself(@TempDir Path parent) {
    // The provisioner should not have to mkdir -p first, and more importantly should not be the one
    // deciding the layout of a directory this class owns.
    Path mapsDir = parent.resolve("shared/maps");
    Run run = run(twoMaps(), mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_OK, run.code());
    assertTrue(Files.isDirectory(mapsDir.resolve("tntwar/tnt-wars")));
  }

  @Test
  void aSecondRunChangesNothingAndSaysSo(@TempDir Path mapsDir) throws IOException {
    FakeResources resources = twoMaps();
    run(resources, mapsDir.toString());
    // Stand in for an operator's in-place edit: re-extracting would destroy it, so its survival is the
    // real assertion here — "idempotent" means the bytes are untouched, not merely that nothing crashed.
    Path region = mapsDir.resolve("tntwar/tnt-wars/region/r.0.0.mca");
    Files.writeString(region, "edited-in-game");

    Run second = run(resources, mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_OK, second.code(), "a no-op must still be a success by default");
    assertEquals("edited-in-game", Files.readString(region));
    assertTrue(second.out().contains("seeded=0 refreshed=0 current=2 failed=0"), second.out());
  }

  @Test
  void exitCodeFlagSeparatesNothingToDoFromSuccess(@TempDir Path mapsDir) {
    FakeResources resources = twoMaps();

    assertEquals(MapBundleCli.EXIT_OK, run(resources, mapsDir.toString(), "--exit-code").code());
    assertEquals(MapBundleCli.EXIT_NOTHING_TO_DO,
        run(resources, mapsDir.toString(), "--exit-code").code());
    // ...and without the flag the steady state stays 0, so `set -e` provisioning is safe.
    assertEquals(MapBundleCli.EXIT_OK, run(resources, mapsDir.toString()).code());
  }

  @Test
  void reportsARefreshWhenTheBundledMapChanged(@TempDir Path mapsDir) throws IOException {
    FakeResources first = new FakeResources();
    first.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    first.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    run(first, mapsDir.toString());

    FakeResources second = new FakeResources();
    second.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-two\n");
    second.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));
    Run refresh = run(second, mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_OK, refresh.code());
    assertTrue(refresh.out().contains("refreshed tntwar/tnt-wars"), refresh.out());
    assertTrue(refresh.out().contains("seeded=0 refreshed=1 current=0 failed=0"), refresh.out());
    assertEquals("v2", Files.readString(mapsDir.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
  }

  @Test
  void failsWhenAManifestedMapIsNotOnDiskAfterThePass(@TempDir Path mapsDir) {
    // The jar's manifest promises two maps but ships one zip: MapBundle logs and moves on, returning a
    // count that cannot tell this apart from "already current". Half-seeded is exactly what must not
    // reach a node, so the CLI derives the truth from disk and refuses to report success.
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\ntntwar/summer-tntwars sha-two\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("red"));

    Run run = run(resources, mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_FAILED, run.code());
    assertTrue(run.err().contains("FAILED    tntwar/summer-tntwars"), run.err());
    assertTrue(run.err().contains("failed=1"), run.err());
    assertTrue(Files.exists(mapsDir.resolve("tntwar/tnt-wars/level.dat")), "the map that did exist is kept");
  }

  @Test
  void failsWhenTheTargetCannotBeADirectory(@TempDir Path parent) throws IOException {
    Path file = parent.resolve("maps");
    Files.writeString(file, "not a directory");

    Run run = run(twoMaps(), file.resolve("shared").toString());

    assertEquals(MapBundleCli.EXIT_FAILED, run.code());
    assertTrue(run.err().contains("cannot use"), run.err());
  }

  @Test
  void tellsAJarWithoutMapsApartFromABrokenDisk(@TempDir Path mapsDir) {
    Run run = run(new FakeResources(), mapsDir.toString());

    assertEquals(MapBundleCli.EXIT_NO_BUNDLE, run.code());
    assertTrue(run.err().contains("prepareMapBundle"), run.err());
  }

  @Test
  void rejectsBadUsageWithItsOwnCode() {
    assertEquals(MapBundleCli.EXIT_USAGE, run(twoMaps()).code(), "no directory at all");
    assertEquals(MapBundleCli.EXIT_USAGE, run(twoMaps(), "/a", "--wat").code(), "unknown flag");
    assertEquals(MapBundleCli.EXIT_USAGE, run(twoMaps(), "/a", "/b").code(), "two directories");
    assertEquals(MapBundleCli.EXIT_OK, run(twoMaps(), "--help").code());
  }

  @Test
  void readsTheDirectoryAndTheRefreshSwitchFromTheEnvironment(@TempDir Path mapsDir) throws IOException {
    FakeResources first = new FakeResources();
    first.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    first.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    Map<String, String> env = new HashMap<>();
    env.put(MapBundleCli.ENV_MAPS_DIR, mapsDir.toString());
    assertEquals(MapBundleCli.EXIT_OK, run(first, env::get).code());

    FakeResources second = new FakeResources();
    second.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-two\n");
    second.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));
    env.put(MapBundleCli.ENV_REFRESH, "false");
    Run held = run(second, env::get);

    assertEquals(MapBundleCli.EXIT_OK, held.code());
    assertEquals("v1", Files.readString(mapsDir.resolve("tntwar/tnt-wars/region/r.0.0.mca")),
        MapBundleCli.ENV_REFRESH + "=false must hold the on-disk copy");
    assertTrue(held.out().contains("current=1"), held.out());
  }

  /**
   * The provisioner can be run twice — a retry, two operators, a stack redeploy racing itself — and two
   * JVMs inflating into one directory is the corruption scenario the whole shared-folder design is
   * judged on. This forks two REAL processes, because the thing being trusted is an OS-level
   * {@code FileLock}: in-JVM threads would prove the in-process lock and quietly skip the one that
   * matters between containers. Nothing about the lock lives in this class — that is the point of the
   * test: it proves the CLI inherits it rather than bypassing it.
   */
  @Test
  void twoProcessesSeedingTheSameDirectoryDoNotCorruptIt(@TempDir Path work) throws Exception {
    Path javaBinary = Path.of(System.getProperty("java.home"), "bin", "java");
    assumeTrue(Files.isExecutable(javaBinary), "no java binary to fork");
    // Big enough that the inflate takes long enough for the second process to arrive mid-flight;
    // incompressible so the zip cannot shrink the window away.
    byte[] payload = new byte[6 * 1024 * 1024];
    new Random(7).nextBytes(payload);
    Path bundleJar = work.resolve("bundled-maps.jar");
    writeResourceJar(bundleJar, Map.of(
        "bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n".getBytes(StandardCharsets.UTF_8),
        "bundled/maps/tntwar/tnt-wars.zip", worldZip(payload)));
    Path mapsDir = work.resolve("maps");
    // FIRST on the classpath: the build's own generated resources carry the real 92 MB TNT War bundle,
    // and the first match wins for getResourceAsStream — without this the fork would seed the real maps
    // and this test would measure the wrong thing (slowly).
    String classpath = bundleJar + File.pathSeparator + System.getProperty("java.class.path");

    List<String> command = List.of(javaBinary.toString(), "-cp", classpath,
        MapBundleCli.class.getName(), mapsDir.toString());
    Process left = new ProcessBuilder(command).redirectErrorStream(true).start();
    Process right = new ProcessBuilder(command).redirectErrorStream(true).start();
    String leftLog = drain(left);
    String rightLog = drain(right);
    assumeTrue(!leftLog.contains("Could not find or load main class"),
        "test classpath is not forkable in this environment");
    assertTrue(left.waitFor(120, TimeUnit.SECONDS) && right.waitFor(120, TimeUnit.SECONDS));

    assertEquals(0, left.exitValue(), leftLog);
    assertEquals(0, right.exitValue(), rightLog);
    // Exactly one of them inflated it; the other queued on the file lock, then found the stamp the
    // winner had written and did nothing. MapBundle's own "Seeded bundled map" line is the authorship
    // record here — two of them would mean two inflates onto one folder, which is the corruption.
    long seeders = Stream.of(leftLog, rightLog).filter(log -> log.contains("Seeded bundled map")).count();
    assertEquals(1, seeders, "exactly one process may write the map\nA:\n" + leftLog + "\nB:\n" + rightLog);
    // ...and the loser says so rather than claiming the write. (The OR is only for a machine slow
    // enough to serialise the two launches entirely: verified locally that the first arm fires, i.e.
    // the loser really did observe the map absent before its own pass and present after — it was
    // parked on the file lock while the winner inflated.)
    assertTrue((leftLog + rightLog).contains("seeded by a concurrent pass")
        || (leftLog + rightLog).contains("current   tntwar/tnt-wars"),
        "A:\n" + leftLog + "\nB:\n" + rightLog);
    assertArrayEquals(payload, Files.readAllBytes(mapsDir.resolve("tntwar/tnt-wars/region/r.0.0.mca")),
        "the map on disk must be byte-identical to the bundled one");
    assertEquals("sha-one", Files.readString(mapsDir.resolve("tntwar/tnt-wars").resolve(MapBundle.STAMP_FILE)).trim());
    try (Stream<Path> siblings = Files.list(mapsDir.resolve("tntwar"))) {
      assertFalse(siblings.anyMatch(path -> path.getFileName().toString().contains(".incoming-")),
          "no staging folder may be left behind");
    }
  }

  // --- helpers -------------------------------------------------------------------------------------

  private record Run(int code, String out, String err) {
  }

  private Run run(ResourceAdapter resources, String... args) {
    return run(resources, key -> null, args);
  }

  private Run run(ResourceAdapter resources, Function<String, String> env, String... args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int code = MapBundleCli.run(args, env, resources,
        new PrintStream(out, true, StandardCharsets.UTF_8),
        new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Run(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static FakeResources twoMaps() {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-red\ntntwar/summer-tntwars sha-blue\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("red"));
    resources.put("bundled/maps/tntwar/summer-tntwars.zip", worldZip("blue"));
    return resources;
  }

  private static String drain(Process process) throws IOException {
    try (InputStream stream = process.getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** A jar holding only resources, appended to the classpath so a forked JVM sees a "bundled" map. */
  private static void writeResourceJar(Path jar, Map<String, byte[]> entries) throws IOException {
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
  }

  private static byte[] worldZip(String regionContent) {
    return worldZip(regionContent.getBytes(StandardCharsets.UTF_8));
  }

  /** The shape prepareMapBundle produces: level.dat at the zip root, region data beside it. */
  private static byte[] worldZip(byte[] regionContent) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("level.dat"));
      zip.write("level".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("region/r.0.0.mca"));
      zip.write(regionContent);
      zip.closeEntry();
    } catch (IOException impossible) {
      throw new IllegalStateException(impossible);
    }
    return bytes.toByteArray();
  }

  private static final class FakeResources implements ResourceAdapter {
    private final Map<String, byte[]> entries = new HashMap<>();

    void put(String path, String content) {
      entries.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    void put(String path, byte[] content) {
      entries.put(path, content);
    }

    @Override
    public Optional<InputStream> openResource(String resourcePath) {
      byte[] content = entries.get(resourcePath);
      return content == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(content));
    }
  }
}
