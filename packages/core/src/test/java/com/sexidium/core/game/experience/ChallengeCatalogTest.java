package com.sexidium.core.game.experience;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChallengeCatalogTest {
  @Test
  void available_listsEverySelectableChallengeAndNotRemovedOnes() {
    List<String> ids = ChallengeCatalog.available().stream().map(ChallengeCatalog.Entry::id).toList();

    assertEquals(27, ids.size());
    assertTrue(ids.containsAll(List.of(
        "doubledrops", "randomizer", "sharedlife",
        "sharedinventory", "xphealth", "shrinkingachievements")));
    // The nine YouTuber-style challenges added to the experience catalog, plus chunkbreak, the two
    // multiplier twists and randomlayers.
    assertTrue(ids.containsAll(List.of(
        "breakonebreakall", "chunkbreak", "blockdeleter", "randomchunks", "walkingblocks", "chained",
        "cleave", "growing", "jumpenchants", "mobduplication", "lookmultiplies", "jumpmultiplies",
        "randomlayers")));
    // The Random-Item-Giver skyblock family + the Random Events engine challenge + Classic Skyblock.
    assertTrue(ids.containsAll(List.of(
        "randomskyblock", "randommob", "randomdrops", "randomevents", "classicskyblock")));
    // Omni Chunk: an edit in one chunk repeats in the same slot of every other chunk.
    assertTrue(ids.contains("omnichunk"));
    // Layered Dimensions: one chunk of stacked layers in the Overworld AND the Nether.
    assertTrue(ids.contains("layereddimensions"));
    // Death Resets: hardcore, no goals, and any death regenerates the world.
    assertTrue(ids.contains("deathresets"));
    // lavafloor, midastouch, fleeingblocks, nogreen and jumpgravity were removed: not selectable.
    assertFalse(ids.contains("lavafloor"));
    assertFalse(ids.contains("midastouch"));
    assertFalse(ids.contains("fleeingblocks"));
    assertFalse(ids.contains("nogreen"));
    assertFalse(ids.contains("jumpgravity"));
    // tntmobs and evolvingmobs were removed: not selectable.
    assertFalse(ids.contains("tntmobs"));
    assertFalse(ids.contains("evolvingmobs"));
  }

  @Test
  void create_resolvesInOrder_dedupes_andSkipsUnknown() {
    List<Challenge> challenges = ChallengeCatalog.create(
        List.of("xphealth", "sharedlife", "XPHEALTH", "does-not-exist", "sharedinventory"));

    assertEquals(List.of("xphealth", "sharedlife", "sharedinventory"),
        challenges.stream().map(Challenge::id).toList());
  }

  @Test
  void create_empty_yieldsNoChallenges() {
    assertTrue(ChallengeCatalog.create(List.of()).isEmpty());
    assertTrue(ChallengeCatalog.create(null).isEmpty());
  }

  @Test
  void anyRequiresVoidWorld_trueOnlyWhenAVoidChallengeIsPresent() {
    // Random Layers and Random Skyblock build their own worlds, so their experiences must generate as void.
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("randomlayers")));
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("randomskyblock")));
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("classicskyblock")));
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("doubledrops", "randomlayers")));
    // Every generated map except Random Skyblock also asks for a void Nether.
    assertTrue(ChallengeCatalog.anyRequiresVoidNether(List.of("classicskyblock")));
    // Layered Dimensions builds a column in its Nether too, so that must generate void as well.
    assertTrue(ChallengeCatalog.anyRequiresVoidWorld(List.of("layereddimensions")));
    assertTrue(ChallengeCatalog.anyRequiresVoidNether(List.of("layereddimensions")));
    // Random Layers grows a column in its Nether too, so that must generate void as well — before this
    // its Nether was ordinary terrain and the mode simply stopped existing past a portal.
    assertTrue(ChallengeCatalog.anyRequiresVoidNether(List.of("randomlayers")));
    // Random Skyblock is the one generated map whose Nether stays a normal Nether.
    assertFalse(ChallengeCatalog.anyRequiresVoidNether(List.of("randomskyblock")));
    assertFalse(ChallengeCatalog.anyRequiresVoidNether(List.of()));
    // Ordinary twists run in a normal terrain world.
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(List.of("doubledrops", "sharedlife")));
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(List.of()));
    assertFalse(ChallengeCatalog.anyRequiresVoidWorld(null));
  }

  @Test
  void lookups_areCaseInsensitive() {
    assertTrue(ChallengeCatalog.contains("XpHealth"));
    assertFalse(ChallengeCatalog.contains("lavafloor"));
    assertEquals("XP Health", ChallengeCatalog.displayNameFor("xphealth"));
    assertEquals("minecraft:experience_bottle", ChallengeCatalog.iconFor("xphealth").qualifiedName());
  }
}
