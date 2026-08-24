package com.sexidium.core.world;

import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelDataSpawnTest {
  @Test
  void readsTheModernSpawnCompound(@TempDir Path folder) throws IOException {
    Path levelDat = folder.resolve("level.dat");
    Files.write(levelDat, gzip(modernLevelData(8, 47, 8, 90.0f, 5.0f)));

    WorldPosition spawn = LevelDataSpawn.read(levelDat, "lobby").orElseThrow();

    // Centred on the spawn block so a joiner lands on it, not on its corner.
    assertEquals("lobby", spawn.worldName());
    assertEquals(8.5, spawn.coordinateX());
    assertEquals(47.0, spawn.coordinateY());
    assertEquals(8.5, spawn.coordinateZ());
    assertEquals(90.0f, spawn.yaw());
    assertEquals(5.0f, spawn.pitch());
  }

  @Test
  void readsTheClassicSpawnFields(@TempDir Path folder) throws IOException {
    Path levelDat = folder.resolve("level.dat");
    Files.write(levelDat, gzip(classicLevelData(-120, 64, 33)));

    WorldPosition spawn = LevelDataSpawn.read(levelDat, "lobby").orElseThrow();

    assertEquals(-119.5, spawn.coordinateX());
    assertEquals(64.0, spawn.coordinateY());
    assertEquals(33.5, spawn.coordinateZ());
  }

  @Test
  void readsUncompressedNbtToo(@TempDir Path folder) throws IOException {
    Path levelDat = folder.resolve("level.dat");
    Files.write(levelDat, modernLevelData(0, 70, -4, 0.0f, 0.0f));

    assertEquals(-3.5, LevelDataSpawn.read(levelDat, "lobby").orElseThrow().coordinateZ());
  }

  @Test
  void yieldsEmptyForMissingUnreadableOrSpawnlessFiles(@TempDir Path folder) throws IOException {
    Path missing = folder.resolve("nope.dat");
    Path garbage = folder.resolve("garbage.dat");
    Files.write(garbage, "not nbt at all".getBytes(StandardCharsets.UTF_8));
    Path spawnless = folder.resolve("spawnless.dat");
    Files.write(spawnless, gzip(levelData(out -> {
      out.writeByte(8);
      writeName(out, "LevelName");
      out.writeUTF("no spawn here");
    })));

    assertTrue(LevelDataSpawn.read(missing, "lobby").isEmpty());
    assertTrue(LevelDataSpawn.read(garbage, "lobby").isEmpty());
    assertTrue(LevelDataSpawn.read(spawnless, "lobby").isEmpty());
    assertEquals(Optional.empty(), LevelDataSpawn.read(null, "lobby"));
  }

  // ===== NBT fixtures ============================================================================

  /** MC 26.1+ shape: {@code Data.spawn = {pos: int[3], yaw: f, pitch: f}}. */
  private static byte[] modernLevelData(int x, int y, int z, float yaw, float pitch) throws IOException {
    return levelData(out -> {
      out.writeByte(10); // TAG_Compound "spawn"
      writeName(out, "spawn");
      out.writeByte(11); // TAG_Int_Array "pos"
      writeName(out, "pos");
      out.writeInt(3);
      out.writeInt(x);
      out.writeInt(y);
      out.writeInt(z);
      out.writeByte(5); // TAG_Float "yaw"
      writeName(out, "yaw");
      out.writeFloat(yaw);
      out.writeByte(5); // TAG_Float "pitch"
      writeName(out, "pitch");
      out.writeFloat(pitch);
      out.writeByte(0); // end of "spawn"
    });
  }

  /** Classic shape: {@code Data.SpawnX/SpawnY/SpawnZ}. */
  private static byte[] classicLevelData(int x, int y, int z) throws IOException {
    return levelData(out -> {
      for (var entry : new Object[][]{{"SpawnX", x}, {"SpawnY", y}, {"SpawnZ", z}}) {
        out.writeByte(3); // TAG_Int
        writeName(out, (String) entry[0]);
        out.writeInt((Integer) entry[1]);
      }
    });
  }

  /** Wraps {@code body} in the {@code root -> Data} compound pair every level.dat uses. */
  private static byte[] levelData(NbtBody body) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(bytes);
    out.writeByte(10); // root TAG_Compound
    writeName(out, "");
    out.writeByte(10); // TAG_Compound "Data"
    writeName(out, "Data");
    body.write(out);
    out.writeByte(0); // end of "Data"
    out.writeByte(0); // end of root
    out.flush();
    return bytes.toByteArray();
  }

  private static void writeName(DataOutputStream out, String name) throws IOException {
    byte[] raw = name.getBytes(StandardCharsets.UTF_8);
    out.writeShort(raw.length);
    out.write(raw);
  }

  private static byte[] gzip(byte[] raw) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
      gzip.write(raw);
    }
    return bytes.toByteArray();
  }

  @FunctionalInterface
  private interface NbtBody {
    void write(DataOutputStream out) throws IOException;
  }
}
