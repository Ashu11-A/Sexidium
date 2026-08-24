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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spawn search against a world that answers like PAPER answers, not like the interface says.
 *
 * <h2>The gap this closes</h2>
 * {@code WorldAdapter.highestSolidBlockY} documents {@link Integer#MIN_VALUE} for "no terrain", and the
 * existing {@code SafeSpawnTest} double returns exactly that — which is why every test passed while
 * players were being dropped into the void. Paper does something else: {@code getHighestBlockYAt} on an
 * empty column returns {@code minBuildHeight() - 1}, an ordinary number, and a block query below the world
 * floor answers {@code void_air}.
 *
 * <p>Read through the old code that combination matched the "this backend models heights but not blocks"
 * branch, which lifts to {@code surfaceY + 1} — for an overworld that is −64, so the {@code Math.max}
 * against the spawn kept the raw spawn and handed it straight back. The ring search never ran. Every
 * player was teleported into thin air at the pinned spawn, fell, and in a hardcore mode that death
 * regenerated the world and did it again.</p>
 *
 * <p>So this double models Paper. A test that only models the contract cannot catch a bug that lives in
 * the difference between them.</p>
 */
class SafeSpawnVoidWorldTest {
  private static final String W = "exp";
  private static final ItemKey STONE = ItemKey.minecraft("stone");

  /** A void world with a one-chunk column somewhere else entirely must not strand the player at spawn. */
  @Test
  void anEmptySpawnColumnSearchesForTerrainInsteadOfReturningThinAir() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    // Terrain 12 blocks away — nothing at all above the spawn itself.
    fillColumnTop(world, 12, 12, 200);

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(201, (int) landed.coordinateY(),
        "the search has to find the terrain; 64 here is the pinned spawn hanging in empty space");
  }

  /**
   * The exact shape that killed the run: a single 16-wide column, and a spawn that is not on it.
   *
   * <p>Reported as "fell out of the world" immediately after a Death Resets regeneration.</p>
   */
  @Test
  void aOneChunkColumnBuiltAwayFromSpawnIsFoundRatherThanFallenPast() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    for (int x = 32; x < 48; x++) {
      for (int z = 32; z < 48; z++) {
        fillColumnTop(world, x, z, 312);
      }
    }

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(313, (int) landed.coordinateY(), "on top of the column");
    assertTrue(landed.coordinateX() >= 32 && landed.coordinateX() < 48, "and actually over it: " + landed);
    assertTrue(landed.coordinateZ() >= 32 && landed.coordinateZ() < 48, "and actually over it: " + landed);
  }

  /** A column centred ON the spawn is the normal case and must still land exactly there. */
  @Test
  void aColumnCentredOnTheSpawnLandsThePlayerOnTheSpawnColumn() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    for (int x = -8; x < 8; x++) {
      for (int z = -8; z < 8; z++) {
        fillColumnTop(world, x, z, 312);
      }
    }

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(313, (int) landed.coordinateY());
    assertEquals(0.5, landed.coordinateX(), 1e-9, "the spawn column itself, centred in the block");
    assertEquals(0.5, landed.coordinateZ(), 1e-9);
  }

  /**
   * A backend that genuinely models heights but not blocks keeps its old behaviour: its reported surface
   * is INSIDE the build range, which is what separates it from an empty column.
   */
  @Test
  void aHeightsOnlyBackendStillGetsTheLegacyLift() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    world.heightsOnly(0, 0, 70); // claims a surface at 70 but reports air there

    WorldPosition landed = SafeSpawn.resolve(world);

    assertEquals(71, (int) landed.coordinateY(), "lifted to the reported surface, as before");
  }

  /** A backend that cannot report terrain at all is still left alone — nothing better exists to offer. */
  @Test
  void aBackendWithNoTerrainInformationIsUntouched() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    world.silent = true;

    assertEquals(64, (int) SafeSpawn.resolve(world).coordinateY());
  }

  /**
   * The first-spawn failure, in the shape it actually took.
   *
   * <p>Layered Dimensions fills its chunk from y 312 down to y 13. Players are teleported into the world
   * BEFORE the challenges build it, so a first entry waits at the pinned spawn (0, 64, 0) and the column
   * is then written straight through them: two hundred and fifty blocks underground, in the dark,
   * suffocating. The SkyBlock modes hid this for months because their island lands one block under the
   * spawn, so the same waiting player ends up standing neatly on the new grass.</p>
   */
  @Test
  void aPlayerWaitingAtSpawnIsDetectedAsBuriedOnceTheColumnIsWrittenThroughThem() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    WorldPosition waiting = new WorldPosition(W, 0.5, 64, 0.5, 0, 0);

    assertTrue(!SafeSpawn.buried(world, waiting), "empty void: nothing is on top of them yet");

    for (int y = 13; y <= 312; y++) {
      for (int x = -8; x < 8; x++) {
        for (int z = -8; z < 8; z++) {
          world.setBlock(new BlockPosition(W, x, y, z), STONE);
        }
      }
    }

    assertTrue(SafeSpawn.buried(world, waiting),
        "the column was written through them and nothing noticed");
    assertEquals(313, (int) SafeSpawn.resolve(world).coordinateY(),
        "and the spot to move them to is the top of what was just built");
  }

  /** A player standing normally on the surface is never treated as buried — that would move them home. */
  @Test
  void aPlayerStandingOnTheSurfaceIsNotBuried() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 64, 0, 0, 0));
    fillColumnTop(world, 0, 0, 63);

    assertTrue(!SafeSpawn.buried(world, new WorldPosition(W, 0.5, 64, 0.5, 0, 0)));
    // Head room matters too: a one-block gap under an overhang is still burial.
    world.setBlock(new BlockPosition(W, 0, 65, 0), STONE);
    assertTrue(SafeSpawn.buried(world, new WorldPosition(W, 0.5, 64, 0.5, 0, 0)));
  }

  /**
   * The live bug: a column that HAS terrain, whose height reads back as 0, must never seat the player
   * at y=1 inside it.
   *
   * <p>This is what put a player at {@code Block 0 1 0} in {@code r.0.0.mca} while the server had just
   * logged "Spawn placed on land at 0, 83, 0". An experience dimension folder carries no
   * {@code level.dat}, so the pinned spawn is not what the world answers with afterwards; combine a
   * low anchor with a height reading of 0 and {@code Math.max(anchorY, surfaceY + 1)} is exactly 1.
   * y=1 in an overworld is deepslate — the player suffocates, and under Death Resets that death
   * regenerates the world and does it again. One run reached Reset 10 this way.</p>
   */
  @Test
  void aColumnWhoseHeightReadsZeroNeverSeatsThePlayerInsideIt() {
    PaperLikeWorld world = new PaperLikeWorld(new WorldPosition(W, 0, 0, 0, 0, 0));
    // Real terrain in the spawn column, surface at 83 — exactly the world the log described.
    for (int y = 83; y >= -64; y--) {
      world.setBlock(new BlockPosition(W, 0, y, 0), STONE);
    }
    // ...but the heightmap answers 0, the way an unread column does.
    world.heightsOnly(0, 0, 0);

    WorldPosition landed = SafeSpawn.resolve(world);

    assertTrue(landed.coordinateY() > 1,
        "a player must never be seated at y=" + landed.coordinateY() + " inside solid terrain;"
            + " that is the void-spawn death loop");
    assertEquals(84, landed.coordinateY(),
        "and the honest answer is the top of the column it was standing over");
  }

  private static void fillColumnTop(PaperLikeWorld world, int x, int z, int topY) {
    // Only the top few blocks matter to the scan; a real column is solid all the way down.
    for (int y = topY; y > topY - 4; y--) {
      world.setBlock(new BlockPosition(W, x, y, z), STONE);
    }
  }

  /**
   * Answers the way Paper answers: an empty column reports {@code minBuildHeight() - 1} rather than
   * {@link Integer#MIN_VALUE}, and a block below the floor is {@code void_air}.
   */
  private static final class PaperLikeWorld implements WorldAdapter {
    private final Map<BlockPosition, ItemKey> blocks = new HashMap<>();
    private final Map<String, Integer> claimedHeights = new HashMap<>();
    private final WorldPosition spawn;
    boolean silent;

    PaperLikeWorld(WorldPosition spawn) {
      this.spawn = spawn;
    }

    /** Claims a surface height with no block behind it — the NeoForge/heights-only shape. */
    void heightsOnly(int x, int z, int y) {
      claimedHeights.put(x + ":" + z, y);
    }

    @Override public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
      if (itemKey != null && "air".equals(itemKey.value())) {
        blocks.remove(blockPosition);
        return;
      }
      blocks.put(blockPosition, itemKey);
    }

    @Override public int highestSolidBlockY(String worldName, int blockX, int blockZ) {
      if (silent) {
        return Integer.MIN_VALUE;
      }
      Integer claimed = claimedHeights.get(blockX + ":" + blockZ);
      if (claimed != null) {
        return claimed;
      }
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
