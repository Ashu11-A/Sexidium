package com.sexidium.core.world;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The LOCAL suffocation escape: {@link SafeSpawn#nearestFree}.
 *
 * <h2>The bug this pins</h2>
 * {@code ExperienceGame.settleOne} detects a suffocating player correctly and then rescued them through
 * the ordinary spawn search, which judges a column by scanning DOWN from the heightmap and therefore
 * always resolves to that column's OUTDOOR SURFACE. A player suffocating in a cave was lifted out of the
 * cave and dropped on the hillside above it; a player sealed inside a building was put on its roof. The
 * detection was never the problem — the recovery was.
 *
 * <p>So these tests are about WHERE the answer is, not merely that there is one: near the player's own Y,
 * inside the cave, under the roof. The world double is the {@code PaperLikeWorld} shape from
 * {@code SafeSpawnVoidWorldTest} — an empty column answers {@code minBuildHeight() - 1} and a block below
 * the floor answers {@code void_air}, the way Paper really answers.</p>
 */
class SafeSpawnNearestFreeTest {
  private static final String W = "exp";
  private static final ItemKey STONE = ItemKey.minecraft("stone");
  private static final ItemKey CAVE_AIR = ItemKey.minecraft("cave_air");
  private static final ItemKey WATER = ItemKey.minecraft("water");
  private static final ItemKey LAVA = ItemKey.minecraft("lava");

  /**
   * The reported bug, in the shape it took: buried deep underground with an open cave pocket a few
   * blocks away and daylight two hundred blocks up. The rescue must be the pocket, not the sky.
   */
  @Test
  void aPlayerSealedInStoneUndergroundIsMovedIntoTheNearbyCaveRatherThanOntoTheSurface() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    // Surface terrain far above, so the heightmap-driven search would answer ~101 if it were consulted.
    WorldPosition buried = new WorldPosition(W, 0.5, 20, 0.5, 90, 0);
    carve(world, 4, 4, 19, 21, 0, 0, CAVE_AIR); // a pocket four blocks east, at the player's own level

    WorldPosition escape = SafeSpawn.nearestFree(world, buried);

    assertNotNull(escape, "there is a pocket right there");
    assertTrue(Math.abs(escape.coordinateY() - 20) <= 2,
        "the rescue has to stay in the cave; y=" + escape.coordinateY() + " is not near the player");
    assertTrue(escape.coordinateY() < 90,
        "and must never be the surface at ~101 — that is the reported bug");
    assertEquals(4.5, escape.coordinateX(), 1e-9, "the pocket, centred in its block");
  }

  /** Walled into a room with a roof: the way out is through a wall, never over the roof. */
  @Test
  void aPlayerInsideARoofedBuildingIsMovedToOpenSpaceBesideItAndNotOntoTheRoof() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    // Ground plane at y=63, open sky above it.
    for (int x = -12; x <= 12; x++) {
      for (int z = -12; z <= 12; z++) {
        world.setBlock(new BlockPosition(W, x, 63, z), STONE);
      }
    }
    // A solid 5x5x5 blob of building sitting on the ground, roof at y=68, with the player packed inside.
    for (int x = -2; x <= 2; x++) {
      for (int y = 64; y <= 68; y++) {
        for (int z = -2; z <= 2; z++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    WorldPosition insideTheWall = new WorldPosition(W, 0.5, 64, 0.5, 0, 0);

    WorldPosition escape = SafeSpawn.nearestFree(world, insideTheWall);

    assertNotNull(escape);
    assertTrue(escape.coordinateY() < 68,
        "below the roof at 68, not standing on it; got y=" + escape.coordinateY());
    assertEquals(64, escape.coordinateY(), 1e-9, "the floor beside the building, at the player's own level");
    assertTrue(Math.max(Math.abs(escape.coordinateX()), Math.abs(escape.coordinateZ())) <= 3.5,
        "and immediately beside it: " + escape);
  }

  /**
   * Whatever it answers, the answer is by construction somewhere a player can breathe — and it is a spot
   * near the player rather than wherever the terrain happens to open up.
   *
   * <p>The "not buried" half alone is satisfied by any sane implementation, including the heightmap-driven
   * one this method exists to replace, so it is paired here with a fixture that tells the two apart: the
   * only pocket in range is BELOW the player's feet. Downwards is a direction the heightmap cannot answer
   * at all — {@code standingY} scans down from {@code highestSolidBlockY} and so resolves to the surface
   * crust at y=101 no matter what the player is currently entombed in. An answer under the player can only
   * have come from the local three-dimensional search, so the two assertions together pin both halves of
   * the contract: breathable, and still where the player was.</p>
   */
  @Test
  void theAnswerIsNeverItselfABuriedPositionAndIsFoundEvenWhenTheOnlyPocketIsBelowThePlayer() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 0, 0, 14, 15, 0, 0, CAVE_AIR); // straight down, six blocks under the player's feet

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape, "there is a pocket directly below");
    assertFalse(SafeSpawn.buried(world, escape), "rescued into another wall: " + escape);
    assertEquals(14, escape.coordinateY(), 1e-9,
        "the pocket below the player; y=" + escape.coordinateY() + " means the search looked upwards");
    assertTrue(escape.coordinateY() < 20,
        "a rescue that resolves the column instead of searching locally answers the surface at ~101");
  }

  /** A flooded pocket is not a rescue — the same liquid rule the rest of the class uses. */
  @Test
  void aPocketFullOfWaterIsNotOfferedAsAnEscape() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 2, 2, 19, 21, 0, 0, WATER);   // close, but drowning
    carve(world, 6, 6, 19, 21, 0, 0, CAVE_AIR); // farther, but breathable

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(6.5, escape.coordinateX(), 1e-9, "the air pocket, not the water one at x=2");
    assertFalse(SafeSpawn.buried(world, escape));
  }

  /** Encased in solid rock for further than the box reaches: say so rather than inventing a spot. */
  @Test
  void aPlayerEncasedWithNoFreeBlockInRangeGetsNoAnswerAtAll() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);

    assertNull(SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0)),
        "null is the honest answer; the caller then falls back to its entry position");
  }

  /** Two pockets the same distance out: the one with a floor wins, so the rescue is not a fall. */
  @Test
  void aPocketWithSolidGroundUnderItBeatsOneHangingInMidAir() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    // A floating shaft to the west, open all the way down: every spot in it has nothing underfoot.
    carve(world, -5, -5, -20, 21, 0, 0, CAVE_AIR);
    // A supported pocket to the east at the same distance: feet at 20, stone still at 19.
    carve(world, 5, 5, 20, 21, 0, 0, CAVE_AIR);

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(5.5, escape.coordinateX(), 1e-9,
        "the supported pocket; the western one would drop the player straight back out");
    assertEquals(20, escape.coordinateY(), 1e-9);
  }

  /**
   * Lava reads as "not free", so footing judged through {@code isFree} alone called a lava lake solid
   * ground and stood the rescued player on it. The pocket has to be refused outright, not merely ranked
   * below the honest one: a rescue that ends in lava is the death this whole path exists to prevent.
   */
  @Test
  void aPocketWhoseFloorIsLavaIsRefusedAndTheStoneFlooredOneFartherOutIsChosen() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 2, 2, 20, 21, 0, 0, CAVE_AIR); // breathable, close — and standing on a lava lake
    carve(world, 2, 2, 19, 19, 0, 0, LAVA);
    carve(world, 5, 5, 20, 21, 0, 0, CAVE_AIR); // farther out, but the floor is stone

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(5.5, escape.coordinateX(), 1e-9,
        "the stone-floored pocket; x=2 would seat the player on lava");
    assertEquals(20, escape.coordinateY(), 1e-9);
  }

  /** Water underfoot is not a floor: it breaks a fall, it does not hold anybody up. Ground wins. */
  @Test
  void aPocketStandingOnWaterCountsAsUnsupportedSoRealGroundFartherOutWins() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 2, 2, 20, 21, 0, 0, CAVE_AIR); // close, but the block underfoot is a water surface
    carve(world, 2, 2, 19, 19, 0, 0, WATER);
    carve(world, 5, 5, 20, 21, 0, 0, CAVE_AIR);

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(5.5, escape.coordinateX(), 1e-9, "solid footing outranks the surface of the water");
  }

  /**
   * The SkyBlock shape: a pillar in an otherwise empty world. Every free block within the box is open
   * void beside it, and the top of the pillar is out of range — so "the nearest free block" is a fall out
   * of the world. Answering null hands the rescue back to the caller's entry spawn, which is on ground.
   */
  @Test
  void aFloatingSpotWithNothingUnderneathIsRefusedRatherThanDroppingThePlayerIntoTheVoid() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    for (int x = -1; x <= 1; x++) {
      for (int z = -1; z <= 1; z++) {
        for (int y = 40; y <= 100; y++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    WorldPosition insideThePillar = new WorldPosition(W, 0.5, 70, 0.5, 0, 0);

    assertNull(SafeSpawn.nearestFree(world, insideThePillar),
        "beside the pillar there is nothing but void, and the pillar top at 101 is 31 blocks up");
  }

  /**
   * Shells are Chebyshev, the ranking is Euclidean, and a shell-2 diagonal is 3.46 away while a shell-3
   * neighbour on the axis is 3.0 — so stopping at the first shell that produced footing answered the
   * farther spot. The search has to keep going while the next shell could still hold something nearer.
   */
  @Test
  void aFartherShellStillWinsWhenItsCandidateIsEuclideanNearerThanTheDiagonalOne() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 2, 2, 22, 23, 2, 2, CAVE_AIR); // Chebyshev 2, Euclidean 3.464
    carve(world, 3, 3, 20, 21, 0, 0, CAVE_AIR); // Chebyshev 3, Euclidean 3.0

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(3.5, escape.coordinateX(), 1e-9, "the genuinely nearest pocket, not the first shell's");
    assertEquals(20, escape.coordinateY(), 1e-9);
    assertEquals(0.5, escape.coordinateZ(), 1e-9);
  }

  /**
   * The same rule, for a player who is not standing neatly on a block boundary — on a slab, on snow, or
   * caught mid-fall, all of which give a fractional Y. Distance is measured to the MIDDLE of a candidate
   * block, so a player a whole block off that centre makes a shell-s candidate as near as {@code s - 1}:
   * stopping as soon as the next shell's naive lower bound loses still answers the farther pocket.
   */
  @Test
  void theNearestPocketStillWinsWhenThePlayerStandsAtAFractionalHeight() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, 2, 2, 22, 23, 0, 0, CAVE_AIR); // Chebyshev 2, Euclidean 2.441
    carve(world, 0, 0, 23, 24, 0, 0, CAVE_AIR); // Chebyshev 3, Euclidean 2.400 — nearer

    WorldPosition escape = SafeSpawn.nearestFree(world, new WorldPosition(W, 0.5, 20.6, 0.5, 0, 0));

    assertNotNull(escape);
    assertEquals(23, escape.coordinateY(), 1e-9, "the pocket straight above, which is the nearer one");
    assertEquals(0.5, escape.coordinateX(), 1e-9);
  }

  /** The head block has to be legal too, so the search stops two below the exclusive build ceiling. */
  @Test
  void theSearchStaysInsideTheWorldsBuildRange() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    // Sealed in at the very top of the world: everything free is at or above the ceiling.
    for (int x = -10; x <= 10; x++) {
      for (int y = 300; y <= 319; y++) {
        for (int z = -10; z <= 10; z++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    WorldPosition nearTheCeiling = new WorldPosition(W, 0.5, 318, 0.5, 0, 0);

    WorldPosition escape = SafeSpawn.nearestFree(world, nearTheCeiling);

    assertNull(escape, "nothing legal is left up there — 319 is the last block and its head would be 320");

    // And at the floor: never below minBuildHeight, where there can be no block at all.
    PaperLikeWorld cellar = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    for (int x = -10; x <= 10; x++) {
      for (int y = -64; y <= -50; y++) {
        for (int z = -10; z <= 10; z++) {
          cellar.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
    cellar.setBlock(new BlockPosition(W, 0, -60, 0), ItemKey.minecraft("air"));
    cellar.setBlock(new BlockPosition(W, 0, -59, 0), ItemKey.minecraft("air"));

    WorldPosition fromTheFloor = SafeSpawn.nearestFree(cellar, new WorldPosition(W, 0.5, -62, 0.5, 0, 0));

    assertNotNull(fromTheFloor);
    assertTrue(fromTheFloor.coordinateY() >= cellar.minBuildHeight(),
        "never below the world floor: " + fromTheFloor);
    assertTrue(fromTheFloor.coordinateY() <= cellar.maxBuildHeight() - 2,
        "and never so high the head block is illegal: " + fromTheFloor);
    assertEquals(-60, fromTheFloor.coordinateY(), 1e-9, "the carved gap");
  }

  /**
   * Already standing somewhere breathable: the radius-0 case answers with the player's own block.
   *
   * <p>The ledge is deliberately a floor inside a cave, with sixty blocks of rock and then a surface crust
   * above it, because "their own block" only says anything when it DIFFERS from what the column would
   * resolve to. Stand the same player on open ground and the two answers coincide, so the test passes
   * against a rescue that ignores the player entirely and lifts them to the surface — which is the bug.
   * Here the column resolves to y=101 and the honest answer is y=20, so the height is the discriminator
   * and the settled player is left exactly where they already were.</p>
   *
   * <p>The centring and the carried facing matter for their own reasons: a player left on the seam between
   * two blocks inside a tight cave clips into the wall, and a rescue that also spins the camera reads as a
   * second bug stacked on the one it just fixed.</p>
   */
  @Test
  void aPlayerWhoIsAlreadyStandingFreeOnACaveFloorIsAnsweredWithTheirOwnBlock() {
    PaperLikeWorld world = solidBlock(-20, 40, 100);
    carve(world, -2, 2, 20, 21, -2, 2, CAVE_AIR); // a room hollowed out deep inside the rock

    WorldPosition standing = new WorldPosition(W, 2.3, 20, -1.7, 33, 11);

    WorldPosition escape = SafeSpawn.nearestFree(world, standing);

    assertNotNull(escape);
    assertEquals(2.5, escape.coordinateX(), 1e-9, "their own block, centred");
    assertEquals(20, escape.coordinateY(), 1e-9,
        "where they already stood; y=" + escape.coordinateY() + " means they were moved to the surface");
    assertEquals(-1.5, escape.coordinateZ(), 1e-9);
    assertEquals(33f, escape.yaw(), "facing preserved");
    assertEquals(11f, escape.pitch());
  }

  /** Solid stone from {@code fromY} to {@code toY} over a wide area, with open sky above {@code surfaceY}. */
  private static PaperLikeWorld solidBlock(int fromY, int toY, int surfaceY) {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    for (int x = -20; x <= 20; x++) {
      for (int z = -20; z <= 20; z++) {
        for (int y = fromY; y <= toY; y++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
        // A thin crust at the surface so the heightmap has a far-away answer to be tempted by.
        for (int y = surfaceY - 3; y <= surfaceY; y++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }
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
   * {@code SafeSpawnVoidWorldTest} on purpose — a double that models the interface contract instead of
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
