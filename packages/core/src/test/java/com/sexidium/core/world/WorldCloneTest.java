package com.sexidium.core.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldCloneTest {
  @Test
  void copiesTreeButSkipsLockAndIdentityFiles(@TempDir Path root) throws IOException {
    Path source = root.resolve("template");
    Path region = source.resolve("region");
    Files.createDirectories(region);
    Files.writeString(source.resolve("level.dat"), "leveldata", StandardCharsets.UTF_8);
    Files.writeString(region.resolve("r.0.0.mca"), "chunkdata", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("uid.dat"), "uid", StandardCharsets.UTF_8);

    Path target = root.resolve("temp/clone");
    assertTrue(WorldClone.copy(source, target));

    assertEquals("leveldata", Files.readString(target.resolve("level.dat"), StandardCharsets.UTF_8));
    assertEquals("chunkdata", Files.readString(target.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
    assertFalse(Files.exists(target.resolve("session.lock")), "session.lock must not be cloned");
    assertFalse(Files.exists(target.resolve("uid.dat")), "uid.dat must not be cloned");
  }

  @Test
  void missingSourceReturnsFalse(@TempDir Path root) {
    assertFalse(WorldClone.copy(root.resolve("does-not-exist"), root.resolve("dest")));
  }

  @Test
  void copyChunkDataOverwritesChunksButKeepsTemplateIdentity(@TempDir Path root) throws IOException {
    // The edit world has new chunk data; the template keeps its own level.dat (spawn/version).
    Path edit = root.resolve("edit");
    Files.createDirectories(edit.resolve("region"));
    Files.createDirectories(edit.resolve("entities"));
    Files.writeString(edit.resolve("region/r.0.0.mca"), "edited-blocks", StandardCharsets.UTF_8);
    Files.writeString(edit.resolve("entities/r.0.0.mca"), "edited-entities", StandardCharsets.UTF_8);
    Files.writeString(edit.resolve("level.dat"), "void-clone", StandardCharsets.UTF_8);

    Path template = root.resolve("worlds/tntwar/map");
    Files.createDirectories(template.resolve("region"));
    Files.writeString(template.resolve("region/r.0.0.mca"), "old-blocks", StandardCharsets.UTF_8);
    Files.writeString(template.resolve("level.dat"), "ORIGINAL", StandardCharsets.UTF_8);

    assertTrue(WorldClone.copyChunkData(edit, template));
    assertEquals("edited-blocks", Files.readString(template.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
    assertEquals("edited-entities", Files.readString(template.resolve("entities/r.0.0.mca"), StandardCharsets.UTF_8));
    assertEquals("ORIGINAL", Files.readString(template.resolve("level.dat"), StandardCharsets.UTF_8),
        "the template keeps its own level.dat");
  }

  @Test
  void copyChunkDataRefusesWhenSourceHasNoOwnRegion(@TempDir Path root) throws IOException {
    // Source with region only NESTED under dimensions/ (e.g. getWorldFolder pointed at the world root):
    // copyChunkData must copy NOTHING rather than risk grabbing the wrong (possibly vanilla) world.
    Path edit = root.resolve("edit");
    Files.createDirectories(edit.resolve("dimensions/minecraft/overworld/region"));
    Files.writeString(edit.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"), "nested", StandardCharsets.UTF_8);

    Path template = root.resolve("template");
    Files.createDirectories(template.resolve("region"));
    Files.writeString(template.resolve("region/r.0.0.mca"), "ORIGINAL", StandardCharsets.UTF_8);

    assertFalse(WorldClone.copyChunkData(edit, template));
    assertEquals("ORIGINAL", Files.readString(template.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8),
        "the template must be untouched when the source has no top-level region");
  }

  @Test
  void missingTemplateIsRefusedWithAReason(@TempDir Path root) {
    WorldClone.ChunkCopyResult result = WorldClone.copyChunkDataChecked(root.resolve("nope"), root.resolve("clone"));

    assertFalse(result.ok());
    assertEquals(WorldClone.CloneFailure.TEMPLATE_MISSING, result.failure());
  }

  @Test
  void partialTemplateWithEmptyRegionIsRefused(@TempDir Path root) throws IOException {
    // A half-extracted map: the folder and region/ exist, but no chunk file was written yet. This used to
    // "clone" successfully (the old guard only checked that region/ was a directory) and the match then
    // ran on a world with no terrain at the map's coordinates.
    Path template = root.resolve("template");
    Files.createDirectories(template.resolve("region"));

    WorldClone.ChunkCopyResult result = WorldClone.copyChunkDataChecked(template, root.resolve("clone"));

    assertFalse(result.ok(), "a template with no chunk files must not clone");
    assertEquals(WorldClone.CloneFailure.EMPTY_REGION_DATA, result.failure());
    assertFalse(Files.exists(root.resolve("clone/region")),
        "nothing may be written when the source is refused");
  }

  @Test
  void templateRewrittenDuringTheCopyIsRefused(@TempDir Path root) throws IOException {
    // A3/A4 of the concurrency analysis: a map refresh (or an editor save) rewrites the template while a
    // match clones it, so the clone ends up truncated or stitched from two map versions. Both returned
    // TRUE before this check existed — no exception, no log, just a broken arena.
    Path template = root.resolve("template");
    Files.createDirectories(template.resolve("region"));
    Files.writeString(template.resolve("region/r.0.0.mca"), "old-map", StandardCharsets.UTF_8);
    Files.writeString(template.resolve("region/r.0.1.mca"), "old-map", StandardCharsets.UTF_8);
    Path clone = root.resolve("clone");

    WorldClone.ChunkCopyResult result = WorldClone.copyChunkDataChecked(template, clone,
        () -> writeQuietly(template.resolve("region/r.0.1.mca"), "NEW-map-version"));

    assertFalse(result.ok(), "a template that changed under the reader must not be reported as cloned");
    assertEquals(WorldClone.CloneFailure.TEMPLATE_CHANGED, result.failure());
    assertEquals("old-map", Files.readString(clone.resolve("region/r.0.1.mca"), StandardCharsets.UTF_8),
        "the clone did hold the stale bytes — which is exactly why it must be refused");
  }

  @Test
  void shortWriteInTheCloneIsRefused(@TempDir Path root) throws IOException {
    // The other half of a partial clone: the destination silently lost (or truncated) a region file.
    Path template = root.resolve("template");
    Files.createDirectories(template.resolve("region"));
    Files.writeString(template.resolve("region/r.0.0.mca"), "complete-region-file", StandardCharsets.UTF_8);
    Path clone = root.resolve("clone");

    WorldClone.ChunkCopyResult result = WorldClone.copyChunkDataChecked(template, clone,
        () -> writeQuietly(clone.resolve("region/r.0.0.mca"), "trunc"));

    assertFalse(result.ok());
    assertEquals(WorldClone.CloneFailure.INCOMPLETE_COPY, result.failure());
  }

  private static void writeQuietly(Path file, String content) {
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
