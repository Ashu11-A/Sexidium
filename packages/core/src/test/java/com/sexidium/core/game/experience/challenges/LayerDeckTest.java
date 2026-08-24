package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.challenges.LayerDeck.Kind;
import com.sexidium.core.game.experience.challenges.LayerDeck.Layer;
import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerDeckTest {
  private static final List<ItemKey> PALETTE = List.of(
      ItemKey.minecraft("stone"), ItemKey.minecraft("dirt"), ItemKey.minecraft("diamond_block"));
  private static final List<String> MOBS = List.of("zombie", "skeleton");

  private static LayerDeck deck() {
    return new LayerDeck(PALETTE, MOBS, 0.12, 0.15, 4, 10);
  }

  @Test
  void kindFor_partitionsTheRollRange_tntThenMobThenBlock() {
    LayerDeck deck = deck();
    assertEquals(Kind.TNT_TRAP, deck.kindFor(0.0));
    assertEquals(Kind.TNT_TRAP, deck.kindFor(0.119));
    assertEquals(Kind.MOB_SPAWN, deck.kindFor(0.12));   // first tick past the TNT band
    assertEquals(Kind.MOB_SPAWN, deck.kindFor(0.26));   // still within tnt+mob = 0.27
    assertEquals(Kind.BLOCK, deck.kindFor(0.27));       // past both bands
    assertEquals(Kind.BLOCK, deck.kindFor(0.999));
  }

  @Test
  void kindFor_withNoMobTypes_neverReturnsMobSpawn() {
    LayerDeck noMobs = new LayerDeck(PALETTE, List.of(), 0.12, 0.15, 4, 10);
    assertEquals(Kind.TNT_TRAP, noMobs.kindFor(0.05));
    // The mob band collapses to a block layer when there are no mob types to spawn.
    assertEquals(Kind.BLOCK, noMobs.kindFor(0.20));
  }

  @Test
  void roll_tntLayer_isTntWithNoMob() {
    // A Random that yields 0.0 first lands in the TNT band.
    Layer layer = deck().roll(fixedFirstDouble(0.0));
    assertEquals(Kind.TNT_TRAP, layer.kind());
    assertEquals(ItemKey.minecraft("tnt"), layer.block());
    assertNull(layer.mob());
  }

  @Test
  void roll_mobLayer_carriesAFloorBlockAndABoundedMobCount() {
    Layer layer = deck().roll(fixedFirstDouble(0.20)); // inside the mob band
    assertEquals(Kind.MOB_SPAWN, layer.kind());
    assertNotNull(layer.block());                       // a floor to stand the mobs on
    assertTrue(MOBS.contains(layer.mob()));
    assertTrue(layer.mobCount() >= 4 && layer.mobCount() <= 10, "count in [min,max]: " + layer.mobCount());
  }

  @Test
  void roll_blockLayer_drawsFromThePalette() {
    Layer layer = deck().roll(fixedFirstDouble(0.9));
    assertEquals(Kind.BLOCK, layer.kind());
    assertTrue(PALETTE.contains(layer.block()));
    assertNull(layer.mob());
  }

  @Test
  void constructor_rejectsAnEmptyPalette() {
    assertThrows(IllegalArgumentException.class, () -> new LayerDeck(List.of(), MOBS, 0.1, 0.1, 1, 2));
  }

  /** A Random whose first nextDouble() is fixed (steering kindFor); later calls are ordinary. */
  private static Random fixedFirstDouble(double first) {
    return new Random(1234L) {
      private boolean used;

      @Override
      public double nextDouble() {
        if (!used) {
          used = true;
          return first;
        }
        return super.nextDouble();
      }
    };
  }
}
