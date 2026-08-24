package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.SafeSpawn;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a RETURNING player is put back down: {@link ExperiencePersistence#resolveEntryPosition}.
 *
 * <h2>The bug this pins</h2>
 * Every entry with a saved spot in this experience was handed to {@code safePositionNear}, on the belief
 * that it keeps a player where they were whenever that spot works. It does not. It judges a column by
 * scanning DOWN from the heightmap, so it resolves every column to its OUTDOOR SURFACE — and it does that
 * whether or not there was anything wrong with where the player was standing. A player who logged out
 * safely on a cave floor at y=24 came back at y=121, on the hillside two hundred blocks above their base;
 * a player standing inside their own roofed house at y=64 came back at y=70, ON THE ROOF. Neither of them
 * was suffocating. Neither of them needed rescuing.
 *
 * <p>So these tests are about a NEGATIVE: a breathing player's coordinates come back untouched, down to
 * the fractional X/Z and the facing, because the old path rewrote those too (it re-centres a candidate in
 * its block). The one case that still moves somebody is the one the guard was written for — genuinely
 * buried — and it has to move them LOCALLY, the way {@code ExperienceGame.settleOne} does, or it is the
 * rooftop bug again wearing a different hat.</p>
 *
 * <p>The world double is the {@code PaperLikeWorld} shape from {@code SafeSpawnNearestFreeTest} — an empty
 * column answers {@code minBuildHeight() - 1} and a block below the floor answers {@code void_air}, the way
 * Paper really answers. A double that models the interface contract instead of the server cannot catch a
 * bug that lives in the difference between them.</p>
 */
class ExperienceEntryPositionTest {
  private static final String W = "experiences/ashu/map_ab12";
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final ItemKey CAVE_AIR = ItemKey.minecraft("cave_air");

  /**
   * The cave dweller. Standing safely in an open pocket deep underground, with a hundred blocks of rock
   * and a surface at y=120 above them: the heightmap-driven answer would be y=121, on the hill over their
   * base. They were breathing, so the answer has to be the spot they left, unchanged.
   */
  @Test
  void aPlayerWhoLoggedOutSafelyInsideACaveIsReturnedToThatExactSpotAndNotToTheSurfaceAboveIt() {
    PaperLikeWorld world = undergroundWithFarSurface();
    carve(world, -6, 6, 24, 26, -10, 0, CAVE_AIR); // the pocket they are standing in, floor at y=23
    WorldPosition saved = new WorldPosition(W, 0.31, 24, -4.67, 137.5f, -12.25f);

    WorldPosition entry = resolve(saved);

    assertFalse(SafeSpawn.buried(world, saved), "fixture check: this player is not suffocating");
    assertSamePosition(saved, entry, "the surface of this column is y=120, so a relocation shows up as y=121");
  }

  /**
   * The reported symptom, exactly as it was reported: a player standing on the floor of their own base at
   * y=64 with a solid roof at y=69 was returned y=70 — outside, on the roof. The heightmap of that column
   * IS the roof, which is the whole reason the old path could not tell a home from a hazard.
   */
  @Test
  void aPlayerStandingInsideTheirOwnRoofedBaseIsReturnedToTheFloorAndNeverPlacedOnTheRoof() {
    PaperLikeWorld world = plainWithRoofedBuilding();
    WorldPosition saved = new WorldPosition(W, -1.42, 64, 2.13, 210.5f, 8.75f);

    WorldPosition entry = resolve(saved);

    assertFalse(SafeSpawn.buried(world, saved), "fixture check: there is head room inside the base");
    assertSamePosition(saved, entry, "the roof of this base is y=69, so a relocation shows up as y=70");
    assertNotEquals(70.0, entry.coordinateY(), "put back ON THE ROOF — this is the user's report");
  }

  /**
   * The fix is not a special case for underground: an ordinary player standing on ordinary ground is not
   * touched either. Here the heightmap agrees about the HEIGHT — outdoors it always does — and the old
   * path still rewrote the position, because it re-centres its answer in the block (x.5 / z.5) and throws
   * the player's real sub-block coordinates away. Verbatim means verbatim.
   */
  @Test
  void aPlayerStandingOnOrdinaryOpenGroundKeepsEvenTheirSubBlockCoordinatesAndTheirFacing() {
    PaperLikeWorld world = flatPlain();
    WorldPosition saved = new WorldPosition(W, 3.27, 64, -8.91, 45.5f, -3.25f);

    WorldPosition entry = resolve(saved);

    assertFalse(SafeSpawn.buried(world, saved), "fixture check: open sky above the plain at y=63");
    assertSamePosition(saved, entry, "a re-centred answer would read x=3.5 / z=-8.5 with the facing kept");
  }

  /**
   * The case the guard was actually written for, and the reason it cannot simply be deleted: a position
   * that was autosaved while the player was inside rock would otherwise be handed back forever, and in
   * Death Resets that is an endless death-and-regenerate loop. They do get moved — but into the pocket
   * beside them, not up to the daylight at y=120, or the rescue has re-created the bug above.
   */
  @Test
  void aPlayerWhoseSavedSpotIsInsideSolidRockIsFreedIntoTheNearestPocketRatherThanLiftedToTheSurface() {
    PaperLikeWorld world = undergroundWithFarSurface();
    carve(world, 3, 4, 24, 25, 0, 0, CAVE_AIR); // an open pocket three blocks east, at their own level
    WorldPosition saved = new WorldPosition(W, 0.5, 24, 0.5, 0f, 0f);

    WorldPosition entry = resolve(saved);

    assertTrue(SafeSpawn.buried(world, saved), "fixture check: this player really is encased");
    assertNotNull(entry);
    assertTrue(Math.abs(entry.coordinateY() - 24) <= 2,
        "the rescue has to stay underground; expected y near 24 but got y=" + entry.coordinateY());
    assertTrue(entry.coordinateY() < 100,
        "and must never be the surface at y=121; got y=" + entry.coordinateY());
    assertFalse(SafeSpawn.buried(world, entry), "freed into another wall: " + entry);
  }

  /**
   * The foreign-world guard this method has always carried, re-asserted because the new short-circuit
   * runs BEFORE it would ever be reached if it were mis-ordered: lobby coordinates captured mid-transition
   * are not a place in this experience, so they must route to the world's own safe spawn. Forcing them
   * through would drop the player at those coordinates INSIDE the experience world — in a wall, under water.
   */
  @Test
  void aSnapshotCapturedInTheLobbyIsIgnoredAndTheEntryFallsBackToTheExperiencesOwnSafeSpawn() {
    PaperLikeWorld world = flatPlain();
    WorldPosition lobby = new WorldPosition("lobby", 2000.5, 71, -1500.5, 90f, 0f);

    WorldPosition entry = resolve(world, lobby);

    assertNotNull(entry);
    assertEquals(W, entry.worldName(), "the experience world, never the lobby the snapshot named");
    assertNotEquals(2000.5, entry.coordinateX(),
        "lobby coordinates forced into the experience world: " + entry);
    assertEquals(64, entry.coordinateY(), 1e-9,
        "the safe spawn stands on the plain at y=63; got " + entry.coordinateY());
    assertFalse(SafeSpawn.buried(world, entry), "the fallback spawn is itself unsafe: " + entry);
  }

  // ----- helpers ---------------------------------------------------------------------------------

  /** Resolves an entry for a snapshot saved at {@code saved}, in the world {@code saved} names. */
  private static WorldPosition resolve(WorldPosition saved) {
    return resolve(worldOf(saved), saved);
  }

  private static WorldPosition resolve(PaperLikeWorld world, WorldPosition saved) {
    return new ExperiencePersistence(null, null).resolveEntryPosition(snapshotAt(saved), world);
  }

  /**
   * The last world any fixture built. The tests build their terrain first and then resolve against it, and
   * every fixture here is the same single world, so this keeps the call sites reading as one line.
   */
  private static PaperLikeWorld lastBuilt;

  private static PaperLikeWorld worldOf(WorldPosition ignored) {
    return lastBuilt;
  }

  private static PlayerSnapshot snapshotAt(WorldPosition saved) {
    PlayerSnapshot snapshot = new PlayerSnapshot(UUID.randomUUID(), "Tester", null);
    snapshot.worldName = saved.worldName();
    snapshot.coordinateX = saved.coordinateX();
    snapshot.coordinateY = saved.coordinateY();
    snapshot.coordinateZ = saved.coordinateZ();
    snapshot.yaw = saved.yaw();
    snapshot.pitch = saved.pitch();
    return snapshot;
  }

  /** Every field of the saved position, so "nearly the same place" cannot pass for "not moved". */
  private static void assertSamePosition(WorldPosition expected, WorldPosition actual, String why) {
    assertNotNull(actual, "no entry position at all; " + why);
    assertEquals(expected.worldName(), actual.worldName(), "world: " + why);
    assertEquals(expected.coordinateX(), actual.coordinateX(), 0.0,
        "x: expected " + expected.coordinateX() + " but got " + actual.coordinateX() + "; " + why);
    assertEquals(expected.coordinateY(), actual.coordinateY(), 0.0,
        "y: expected " + expected.coordinateY() + " but got " + actual.coordinateY() + "; " + why);
    assertEquals(expected.coordinateZ(), actual.coordinateZ(), 0.0,
        "z: expected " + expected.coordinateZ() + " but got " + actual.coordinateZ() + "; " + why);
    assertEquals(expected.yaw(), actual.yaw(),
        "yaw: expected " + expected.yaw() + " but got " + actual.yaw() + "; " + why);
    assertEquals(expected.pitch(), actual.pitch(),
        "pitch: expected " + expected.pitch() + " but got " + actual.pitch() + "; " + why);
  }

  /** Solid rock from y=-20 to y=40 with a thin crust at y=117..120, so the heightmap has a far temptation. */
  private static PaperLikeWorld undergroundWithFarSurface() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0f, 0f));
    for (int x = -20; x <= 20; x++) {
      for (int z = -20; z <= 20; z++) {
        for (int y = -20; y <= 40; y++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
        for (int y = 117; y <= 120; y++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    lastBuilt = world;
    return world;
  }

  /** A plain at y=63 with a hollow 7x7 building on it: floor y=63, head room y=64..68, roof y=69. */
  private static PaperLikeWorld plainWithRoofedBuilding() {
    PaperLikeWorld world = flatPlain();
    for (int x = -3; x <= 3; x++) {
      for (int y = 64; y <= 69; y++) {
        for (int z = -3; z <= 3; z++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    carve(world, -2, 2, 64, 68, -2, 2, CAVE_AIR);
    return world;
  }

  /** Open sky over a stone plain at y=63, so a player standing on it is at y=64. */
  private static PaperLikeWorld flatPlain() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0f, 0f));
    for (int x = -20; x <= 20; x++) {
      for (int z = -20; z <= 20; z++) {
        world.setBlock(new BlockPosition(W, x, 63, z), STONE);
      }
    }
    lastBuilt = world;
    return world;
  }

  private static void carve(PaperLikeWorld world, int fromX, int toX, int fromY, int toY, int fromZ,
      int toZ, ItemKey fill) {
    for (int x = fromX; x <= toX; x++) {
      for (int y = fromY; y <= toY; y++) {
        for (int z = fromZ; z <= toZ; z++) {
          world.setBlock(new BlockPosition(W, x, y, z), fill);
        }
      }
    }
  }

  /**
   * Answers the way Paper answers: an empty column reports {@code minBuildHeight() - 1} rather than
   * {@link Integer#MIN_VALUE}, and a block below the floor is {@code void_air}. Copied from
   * {@code SafeSpawnNearestFreeTest} on purpose — a double that models the interface contract instead of
   * the server cannot catch a bug that lives in the difference between them.
   */
  private static final class PaperLikeWorld implements WorldAdapter {
    private final Map<BlockPosition, ItemKey> blocks = new HashMap<>();
    private final WorldPosition spawn;

    PaperLikeWorld(WorldPosition spawn) {
      this.spawn = spawn;
    }

    @Override public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
      if (itemKey != null && "air".equals(itemKey.value())) {
        blocks.remove(blockPosition);
        return;
      }
      blocks.put(blockPosition, itemKey);
    }

    @Override public int highestSolidBlockY(String worldName, int blockX, int blockZ) {
      int highest = minBuildHeight() - 1; // Paper's answer for an empty column
      for (BlockPosition position : blocks.keySet()) {
        if (position.blockX() == blockX && position.blockZ() == blockZ) {
          highest = Math.max(highest, position.blockY());
        }
      }
      return highest;
    }

    @Override public ItemKey blockTypeAt(BlockPosition blockPosition) {
      if (blockPosition.blockY() < minBuildHeight() || blockPosition.blockY() > maxBuildHeight()) {
        return ItemKey.minecraft("void_air");
      }
      ItemKey block = blocks.get(blockPosition);
      return block == null ? ItemKey.minecraft("air") : block;
    }

    @Override public int minBuildHeight() { return -64; }
    @Override public int maxBuildHeight() { return 320; }
    @Override public String name() { return W; }
    @Override public WorldPosition spawnPosition() { return spawn; }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) { }
    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) { }
    @Override public void resetBorder() { }
  }
}
