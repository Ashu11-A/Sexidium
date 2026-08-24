package com.sexidium.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorldClone#copyWorldFolderChecked} — the whole-folder copy the experience backup is built on.
 *
 * <p>Its reason to exist is precisely what {@link WorldClone#copyChunkDataChecked} leaves out. That one
 * copies {@code region/entities/poi/data} and nothing else, because both of its directions write into a
 * folder that already has an identity: the map editor writes an admin's blocks onto a template that
 * keeps its own spawn, and a match clone writes a template's blocks into a world the backend is about
 * to create. A backup has no such destination — it is making one — so everything that decides what the
 * world IS has to travel: {@code paper-world.yml}, and the {@code sexidium/} sidecar holding the run's
 * counters and every player's saved inventory and position. Each test here asserts the difference
 * against the chunk-data copy directly, so the two can never quietly converge.</p>
 */
class WorldFolderCloneTest {

  @Test
  @DisplayName("the whole folder travels — including everything the chunk-data copy leaves behind")
  void copiesWhatTheChunkCopyDoesNot(@TempDir Path root) throws IOException {
    Path source = world(root.resolve("source"));

    Path chunkOnly = root.resolve("chunk-only");
    assertTrue(WorldClone.copyChunkDataChecked(source, chunkOnly).ok());
    assertFalse(Files.exists(chunkOnly.resolve("paper-world.yml")),
        "negative control: the chunk-data copy deliberately does not carry paper-world.yml");
    assertFalse(Files.exists(chunkOnly.resolve("sexidium/state.yml")),
        "negative control: the chunk-data copy deliberately does not carry the sexidium sidecar");

    Path whole = root.resolve("whole");
    WorldClone.ChunkCopyResult result = WorldClone.copyWorldFolderChecked(source, whole);

    assertTrue(result.ok(), result.detail());
    assertEquals("blocks", Files.readString(whole.resolve("region/r.0.0.mca"), StandardCharsets.UTF_8));
    assertEquals("entities", Files.readString(whole.resolve("entities/r.0.0.mca"), StandardCharsets.UTF_8));
    assertEquals("seed+dimensions",
        Files.readString(whole.resolve("data/minecraft/world_gen_settings.dat"), StandardCharsets.UTF_8),
        "the seed and generator registry are the world's identity and live in data/");
    assertEquals("hardcore+spawn+clock",
        Files.readString(whole.resolve("data/paper/level_overrides.dat"), StandardCharsets.UTF_8));
    assertEquals("keep-spawn: false",
        Files.readString(whole.resolve("paper-world.yml"), StandardCharsets.UTF_8),
        "paper-world.yml is per-world settings; a copy without it is a differently configured world");
    assertEquals("deathresets.resets: \"7\"",
        Files.readString(whole.resolve("sexidium/state.yml"), StandardCharsets.UTF_8),
        "the run's counters live in the sidecar and are the whole point of a backup");
    assertEquals("inv: \"...\"",
        Files.readString(whole.resolve("sexidium/players/p.yml"), StandardCharsets.UTF_8),
        "every per-player save has to travel or the copy is an empty world with the right terrain");
  }

  @Test
  @DisplayName("lock and identity files still stay behind")
  void stillSkipsLockAndIdentityFiles(@TempDir Path root) throws IOException {
    Path source = world(root.resolve("source"));
    Files.writeString(source.resolve("session.lock"), "lock", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("uid.dat"), "uid", StandardCharsets.UTF_8);
    Files.writeString(source.resolve("something.lock"), "lock", StandardCharsets.UTF_8);

    Path whole = root.resolve("whole");
    assertTrue(WorldClone.copyWorldFolderChecked(source, whole).ok());

    // These say "this process has this world open". Copying one into a folder a second server may
    // open is the one thing a whole-folder copy must NOT widen from the chunk-data copy.
    assertFalse(Files.exists(whole.resolve("session.lock")));
    assertFalse(Files.exists(whole.resolve("uid.dat")));
    assertFalse(Files.exists(whole.resolve("something.lock")));
  }

  @Test
  @DisplayName("a source rewritten while it is being read is refused, sidecar included")
  void aSourceRewrittenMidCopyIsRefused(@TempDir Path root) throws IOException {
    Path source = world(root.resolve("source"));
    Path target = root.resolve("target");

    // The window a real concurrent writer occupies. For the backup that writer is the world itself:
    // an experience whose chunks are still being flushed, which is exactly what the loaded-world
    // refusal exists to keep out — and this is the second line of defence when it gets through.
    WorldClone.ChunkCopyResult result = WorldClone.copyWorldFolderChecked(source, target,
        () -> write(source.resolve("sexidium/state.yml"), "deathresets.resets: \"8\""));

    assertFalse(result.ok(), "a folder that changed under the reader must never be reported as copied");
    assertEquals(WorldClone.CloneFailure.TEMPLATE_CHANGED, result.failure());
    assertEquals("deathresets.resets: \"7\"",
        Files.readString(target.resolve("sexidium/state.yml"), StandardCharsets.UTF_8),
        "the copy did hold the stale counters — which is exactly why it must be refused");
  }

  @Test
  @DisplayName("a folder that is not a world save is refused before anything is written")
  void refusesAnythingThatIsNotAWorld(@TempDir Path root) throws IOException {
    assertEquals(WorldClone.CloneFailure.TEMPLATE_MISSING,
        WorldClone.copyWorldFolderChecked(root.resolve("nope"), root.resolve("a")).failure());

    Path empty = root.resolve("empty");
    Files.createDirectories(empty.resolve("region"));
    Files.writeString(empty.resolve("paper-world.yml"), "x", StandardCharsets.UTF_8);
    assertEquals(WorldClone.CloneFailure.EMPTY_REGION_DATA,
        WorldClone.copyWorldFolderChecked(empty, root.resolve("b")).failure(),
        "a world with no chunk files is a half-written folder, not a world");
    assertFalse(Files.exists(root.resolve("b")),
        "nothing may be written when the source is refused");
  }

  @Test
  @DisplayName("a short write in the copy is refused")
  void aShortWriteIsRefused(@TempDir Path root) throws IOException {
    Path source = world(root.resolve("source"));
    Path target = root.resolve("target");

    WorldClone.ChunkCopyResult result = WorldClone.copyWorldFolderChecked(source, target,
        () -> write(target.resolve("sexidium/players/p.yml"), ""));

    assertFalse(result.ok());
    assertEquals(WorldClone.CloneFailure.INCOMPLETE_COPY, result.failure());
  }

  /** A keyed dimension folder as it really is: no level.dat, no uid.dat, no session.lock. */
  private static Path world(Path folder) throws IOException {
    Files.createDirectories(folder.resolve("region"));
    Files.createDirectories(folder.resolve("entities"));
    Files.createDirectories(folder.resolve("data/minecraft"));
    Files.createDirectories(folder.resolve("data/paper"));
    Files.createDirectories(folder.resolve("sexidium/players"));
    write(folder.resolve("region/r.0.0.mca"), "blocks");
    write(folder.resolve("entities/r.0.0.mca"), "entities");
    write(folder.resolve("data/minecraft/world_gen_settings.dat"), "seed+dimensions");
    write(folder.resolve("data/paper/level_overrides.dat"), "hardcore+spawn+clock");
    write(folder.resolve("paper-world.yml"), "keep-spawn: false");
    write(folder.resolve("sexidium/state.yml"), "deathresets.resets: \"7\"");
    write(folder.resolve("sexidium/players/p.yml"), "inv: \"...\"");
    return folder;
  }

  private static void write(Path file, String content) {
    try {
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
