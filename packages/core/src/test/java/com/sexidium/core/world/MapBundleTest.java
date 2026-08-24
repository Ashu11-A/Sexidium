package com.sexidium.core.world;

import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapBundleTest {
  @Test
  void extractsEveryManifestedMapIntoTheWorldRoot(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars\ntntwar/summer-tntwars\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("hello-red"));
    resources.put("bundled/maps/tntwar/summer-tntwars.zip", worldZip("hello-blue"));

    int extracted = bundle(resources).seedBundledMaps(worldsRoot);

    assertEquals(2, extracted);
    // level.dat sits at the world-folder root (clone template keeps it) and the marker survives.
    assertTrue(Files.exists(worldsRoot.resolve("tntwar/tnt-wars/level.dat")));
    assertTrue(Files.isDirectory(worldsRoot.resolve("tntwar/tnt-wars/region")));
    assertEquals("hello-red", Files.readString(worldsRoot.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
    assertTrue(Files.exists(worldsRoot.resolve("tntwar/summer-tntwars/level.dat")));
  }

  @Test
  void neverClobbersAMapThatAlreadyHasWorldData(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    // A manifest line with no digest: what older jars wrote, and there is nothing to compare against.
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));
    // Operator already has this map (with its own edits) on disk.
    Path existing = worldsRoot.resolve("tntwar/tnt-wars");
    Files.createDirectories(existing);
    Files.writeString(existing.resolve("level.dat"), "operator");
    Files.writeString(existing.resolve("sexidium-tntwar.yml"), "red-corner1-x: 5");

    int extracted = bundle(resources).seedBundledMaps(worldsRoot);

    assertEquals(0, extracted);
    assertEquals("operator", Files.readString(existing.resolve("level.dat")));
    assertTrue(Files.exists(existing.resolve("sexidium-tntwar.yml")), "the operator's bases must survive");
    assertFalse(Files.exists(existing.resolve("region")), "the bundle was not extracted over it");
  }

  @Test
  void stampsTheDigestOfWhatItExtracted(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));

    bundle(resources).seedBundledMaps(worldsRoot);

    Path stamp = worldsRoot.resolve("tntwar/tnt-wars").resolve(MapBundle.STAMP_FILE);
    assertEquals("sha-one", Files.readString(stamp).trim());
  }

  @Test
  void leavesAMapAloneWhileItsBundleIsUnchanged(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    bundle(resources).seedBundledMaps(worldsRoot);
    // The operator edits the seeded map in-game: bases captured, a region rewritten.
    Path map = worldsRoot.resolve("tntwar/tnt-wars");
    Files.writeString(map.resolve("sexidium-tntwar.yml"), "red-corner1-x: 5");
    Files.writeString(map.resolve("region/r.0.0.mca"), "edited-in-game");

    int written = bundle(resources).seedBundledMaps(worldsRoot);

    assertEquals(0, written, "an unchanged bundle must not rewrite the map");
    assertEquals("edited-in-game", Files.readString(map.resolve("region/r.0.0.mca")));
    assertTrue(Files.exists(map.resolve("sexidium-tntwar.yml")), "the operator's bases must survive");
  }

  @Test
  void replacesAMapWhoseBundleChanged(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    bundle(resources).seedBundledMaps(worldsRoot);

    // The map is re-exported into assets/worlds: new content, new digest.
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-two\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));
    int written = bundle(resources).seedBundledMaps(worldsRoot);

    Path map = worldsRoot.resolve("tntwar/tnt-wars");
    assertEquals(1, written);
    assertEquals("v2", Files.readString(map.resolve("region/r.0.0.mca")));
    assertEquals("sha-two", Files.readString(map.resolve(MapBundle.STAMP_FILE)).trim());
    // Moved, never deleted: the superseded copy is still there with its old content.
    Path replaced = onlyReplacedCopy(worldsRoot.resolve("tntwar"));
    assertEquals("v1", Files.readString(replaced.resolve("region/r.0.0.mca")));
  }

  @Test
  void keepsOnlyTheNewestSupersededCopy(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    bundle(resources).seedBundledMaps(worldsRoot);
    // Two more rounds of edit -> re-export -> boot.
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-two\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));
    bundle(resources).seedBundledMaps(worldsRoot);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-three\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v3"));
    bundle(resources).seedBundledMaps(worldsRoot);

    assertEquals("v3", Files.readString(worldsRoot.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
    // Only the copy superseded most recently (v2) is kept; iterating must not fill the disk.
    Path replaced = onlyReplacedCopy(worldsRoot.resolve("tntwar"));
    assertEquals("v2", Files.readString(replaced.resolve("region/r.0.0.mca")));
  }

  @Test
  void adoptsAMapSeededBeforeStampsExisted(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("from-bundle"));
    // An older jar seeded this map, so there is no stamp to compare — and it may hold months of edits.
    Path existing = worldsRoot.resolve("tntwar/tnt-wars");
    Files.createDirectories(existing.resolve("region"));
    Files.writeString(existing.resolve("level.dat"), "operator");
    Files.writeString(existing.resolve("region/r.0.0.mca"), "long-standing-edits");

    int written = bundle(resources).seedBundledMaps(worldsRoot);

    assertEquals(0, written, "an upgrade must not replace maps that predate the stamp");
    assertEquals("long-standing-edits", Files.readString(existing.resolve("region/r.0.0.mca")));
    assertEquals("sha-one", Files.readString(existing.resolve(MapBundle.STAMP_FILE)).trim(),
        "and it is stamped, so the NEXT change is detected");
  }

  @Test
  void refreshCanBeTurnedOff(@TempDir Path worldsRoot) throws IOException {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v1"));
    bundle(resources).seedBundledMaps(worldsRoot, false);
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-two\n");
    resources.put("bundled/maps/tntwar/tnt-wars.zip", worldZip("v2"));

    int written = bundle(resources).seedBundledMaps(worldsRoot, false);

    assertEquals(0, written);
    assertEquals("v1", Files.readString(worldsRoot.resolve("tntwar/tnt-wars/region/r.0.0.mca")));
  }

  @Test
  void bundledWorldPathsIgnoreTheDigestColumn() {
    FakeResources resources = new FakeResources();
    resources.put("bundled/maps/manifest.txt", "tntwar/tnt-wars sha-one\ntntwar/summer-tntwars sha-two\n");

    assertEquals(List.of("tntwar/tnt-wars", "tntwar/summer-tntwars"), MapBundle.bundledWorldPaths(resources));
  }

  @Test
  void noManifestIsANoOp(@TempDir Path worldsRoot) {
    assertEquals(0, bundle(new FakeResources()).seedBundledMaps(worldsRoot));
  }

  /** The single {@code <name>.replaced-<timestamp>} folder expected beside a refreshed map. */
  private static Path onlyReplacedCopy(Path mapParent) throws IOException {
    try (Stream<Path> siblings = Files.list(mapParent)) {
      List<Path> replaced = siblings
          .filter(path -> path.getFileName().toString().contains(".replaced-"))
          .toList();
      assertEquals(1, replaced.size(), "exactly one superseded copy should be kept: " + replaced);
      return replaced.get(0);
    }
  }

  private static MapBundle bundle(ResourceAdapter resources) {
    return new MapBundle(resources, new StdoutLoggerAdapter("Test"));
  }

  /** A minimal world zip: level.dat at the root plus one region file carrying a marker payload. */
  private static byte[] worldZip(String regionMarker) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("level.dat"));
      zip.write("level".getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      zip.putNextEntry(new ZipEntry("region/r.0.0.mca"));
      zip.write(regionMarker.getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
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

    @Override public Optional<InputStream> openResource(String resourcePath) {
      byte[] data = entries.get(resourcePath);
      return data == null ? Optional.empty() : Optional.of(new ByteArrayInputStream(data));
    }
  }
}
