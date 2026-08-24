package com.sexidium.core.game.experience.compose;

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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards world integrity: the blocks no challenge may ever destroy, checked in the one place every
 * block-editing mode goes through. The headline case is the End portal frame — losing it makes a world
 * unbeatable, and it used to be destroyed by world replication.
 */
class BlockGuardTest {
  private static final BlockPosition AT = new BlockPosition("w", 10, 64, 10);

  @Test
  void theEndPortalAndItsFrameAreNeverDestroyed() {
    BlockGuard guard = BlockGuard.defaults();
    assertTrue(guard.isProtected(ItemKey.minecraft("end_portal_frame")), "the reported bug");
    assertTrue(guard.isProtected(ItemKey.minecraft("end_portal")));
    assertTrue(guard.isProtected(ItemKey.minecraft("end_gateway")));
    // Case does not matter — ids arrive from several platforms and code paths.
    assertTrue(guard.isProtected(ItemKey.minecraft("END_PORTAL_FRAME")));
  }

  @Test
  void theWorldsFloorAndAdminBlocksAreProtectedToo() {
    BlockGuard guard = BlockGuard.defaults();
    for (String value : List.of("bedrock", "barrier", "reinforced_deepslate",
        "command_block", "structure_block", "jigsaw")) {
      assertTrue(guard.isProtected(ItemKey.minecraft(value)), value + " must be protected");
    }
    // …and everything else stays destructible: erasing what you needed is the mode working.
    for (String value : List.of("stone", "diamond_ore", "oak_log", "spawner", "obsidian")) {
      assertFalse(guard.isProtected(ItemKey.minecraft(value)), value + " must stay destructible");
    }
    assertFalse(guard.isProtected(null));
  }

  @Test
  void containersAreProtectedSoASweepNeverEatsAPlayersItems() {
    // Destroying terrain is the mode working; destroying a chest takes the player's THINGS with it.
    BlockGuard guard = BlockGuard.defaults();
    for (String value : List.of("chest", "trapped_chest", "ender_chest", "barrel", "shulker_box")) {
      assertTrue(guard.isProtected(ItemKey.minecraft(value)), value + " must be protected");
    }
    // Every colour variant counts, without listing sixteen of them in the protected set.
    assertTrue(guard.isProtected(ItemKey.minecraft("red_shulker_box")));
    assertTrue(guard.isProtected(ItemKey.minecraft("light_blue_shulker_box")));
    // A bulk sweep that names a container type has it filtered out.
    assertEquals(Set.of("stone"), guard.breakableTypes(Set.of("stone", "chest", "purple_shulker_box")));
    // A whole-chunk rewrite is told about the variants explicitly, since it can only match exact ids.
    Set<String> preserved = guard.preservedValues();
    assertTrue(preserved.contains("chest"));
    assertTrue(preserved.contains("magenta_shulker_box"), "a chunk rewrite must be told every variant");
  }

  @Test
  void aVariantIsOnlyProtectedWhileItsFamilyIs() {
    // A server that removes containers from the list must not still have the variants protected by the
    // suffix rule — the two must never disagree.
    BlockGuard custom = new BlockGuard(List.of("bedrock"));
    assertFalse(custom.isProtected(ItemKey.minecraft("chest")));
    assertFalse(custom.isProtected(ItemKey.minecraft("red_shulker_box")));
    assertFalse(custom.preservedValues().contains("red_shulker_box"));
  }

  @Test
  void aBulkSweepCannotIncludeAProtectedType() {
    // "Break every block of this type everywhere" hands the platform a SET, so the guard filters the set.
    BlockGuard guard = BlockGuard.defaults();
    Set<String> requested = Set.of("stone", "end_portal_frame", "dirt", "bedrock");
    Set<String> allowed = guard.breakableTypes(requested);

    assertEquals(Set.of("stone", "dirt"), allowed);
    // A clean set is returned untouched, so the common case allocates nothing.
    Set<String> clean = Set.of("stone", "dirt");
    assertSame(clean, guard.breakableTypes(clean));
    assertTrue(guard.breakableTypes(Set.of()).isEmpty());
  }

  @Test
  void theFunnelRefusesToModifyAProtectedPosition() {
    // The position-level check every one-block-at-a-time editor uses (replication copies, walking trails).
    FakeWorld world = new FakeWorld();
    world.put(AT, ItemKey.minecraft("end_portal_frame"));
    BlockBreakService service = new BlockBreakService(new DropPipeline(null));
    service.guard(BlockGuard.defaults());

    assertFalse(service.mayModify(world, AT), "an End portal frame is off-limits to every mode");
    world.put(AT, ItemKey.minecraft("stone"));
    assertTrue(service.mayModify(world, AT), "ordinary terrain is fair game");
    assertFalse(service.mayModify(null, AT));
    assertFalse(service.mayModify(world, null));
  }

  @Test
  void theGuardOutranksTheChallengeVetoes() {
    BlockBreakService service = new BlockBreakService(new DropPipeline(null));
    service.guard(BlockGuard.defaults());
    // A veto that allows absolutely everything must not be able to re-open a protected block.
    service.registerVeto(new BlockChangeVeto() {
      @Override public boolean allowsPlace(WorldPosition position, ItemKey type) { return true; }
      @Override public boolean allowsBreak(WorldPosition position, ItemKey type) { return true; }
    });
    WorldPosition at = BlockBreakService.center(AT);
    assertFalse(service.allowsBreak(at, ItemKey.minecraft("end_portal_frame")));
    assertFalse(service.allowsPlace(at, ItemKey.minecraft("bedrock")));
    assertTrue(service.allowsBreak(at, ItemKey.minecraft("stone")));
  }

  @Test
  void aServerCanExtendOrReplaceTheList() {
    BlockGuard extended = BlockGuard.defaults().with(List.of("diamond_block", " GOLD_BLOCK "));
    assertTrue(extended.isProtected(ItemKey.minecraft("diamond_block")));
    assertTrue(extended.isProtected(ItemKey.minecraft("gold_block")), "entries are trimmed and lowercased");
    assertTrue(extended.isProtected(ItemKey.minecraft("end_portal_frame")), "…without losing the defaults");

    // An explicit list replaces the defaults outright.
    BlockGuard custom = new BlockGuard(List.of("stone"));
    assertTrue(custom.isProtected(ItemKey.minecraft("stone")));
    assertFalse(custom.isProtected(ItemKey.minecraft("bedrock")));
    // An empty/absent list keeps the defaults rather than protecting nothing.
    assertTrue(new BlockGuard(List.of()).isProtected(ItemKey.minecraft("end_portal_frame")));
    assertTrue(new BlockGuard(null).isProtected(ItemKey.minecraft("end_portal_frame")));
  }

  @Test
  void aChunkRewriteIsToldWhatToLeaveAlone() {
    // convertChunk rewrites a whole chunk, so it has to be handed the protected ids up front.
    Set<String> preserved = BlockGuard.defaults().preservedValues();
    assertTrue(preserved.contains("end_portal_frame"));
    assertTrue(preserved.contains("bedrock"));
  }

  /** Minimal world that only answers "what block is here". */
  private static final class FakeWorld implements WorldAdapter {
    private final Map<BlockPosition, ItemKey> blocks = new HashMap<>();

    void put(BlockPosition at, ItemKey block) {
      blocks.put(at, block);
    }

    @Override
    public ItemKey blockTypeAt(BlockPosition blockPosition) {
      return blocks.getOrDefault(blockPosition, ItemKey.minecraft("air"));
    }

    @Override public String name() { return "w"; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition("w", 0, 64, 0, 0f, 0f); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }
}
