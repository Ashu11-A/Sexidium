package com.sexidium.core.game.experience;

import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards where a dead participant comes back: an experience never ejects and never changes dimension on
 * death — die in the Nether, respawn in the Nether — and an experience whose chosen start world is the
 * Nether or The End never dumps a player into an Overworld they did not pick.
 */
class ExperienceRespawnTest {
  private static final String OVERWORLD = "experiences/ashu/map_ab12";
  private static final String NETHER = OVERWORLD + "_nether";
  private static final String END = OVERWORLD + "_end";

  @Test
  void deathInTheNetherRespawnsInTheNether() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(world.dimension(WorldDimension.NETHER));
    persistence.recordDimension(player, world); // sampled while alive, in the Nether

    // Vanilla would have put them at the Overworld spawn — redirect back into the Nether.
    WorldPosition target = persistence.respawnPosition(player, world, ExperienceWorldType.NORMAL,
        new WorldPosition(OVERWORLD, 0.5, 64, 0.5, 0f, 0f));
    assertEquals(NETHER, target.worldName());
  }

  @Test
  void anEndExperienceRespawnsInTheEndEvenWithNoSample() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(world);

    // Nothing was sampled (e.g. a restart), so the experience's chosen START world decides.
    WorldPosition target = persistence.respawnPosition(player, world, ExperienceWorldType.END,
        new WorldPosition(OVERWORLD, 0.5, 64, 0.5, 0f, 0f));
    assertEquals(END, target.worldName());
  }

  @Test
  void aBedOrAnchorInTheRightDimensionIsRespected() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(world.dimension(WorldDimension.NETHER));
    persistence.recordDimension(player, world);

    // The platform already lands them at their respawn anchor in the Nether — do not override it.
    assertNull(persistence.respawnPosition(player, world, ExperienceWorldType.NORMAL,
        new WorldPosition(NETHER, 12.5, 70, -8.5, 0f, 0f)));
  }

  @Test
  void aSoftResetAlwaysResolvesToTheDimensionSpawn() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(world.dimension(WorldDimension.NETHER));
    persistence.recordDimension(player, world);

    // No platform placement to respect (a challenge soft-reset): the Nether spawn, not the Overworld.
    WorldPosition target = persistence.respawnPosition(player, world, ExperienceWorldType.NORMAL, null);
    assertEquals(NETHER, target.worldName());
  }

  @Test
  void aPlayerOutsideTheExperienceIsNeverSampled() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(new FakeWorld("lobby", WorldDimension.OVERWORLD, null));
    persistence.recordDimension(player, world); // lobby coordinates must not become a respawn dimension

    // With no sample, an Overworld experience resolves to its Overworld.
    WorldPosition target = persistence.respawnPosition(player, world, ExperienceWorldType.NORMAL, null);
    assertEquals(OVERWORLD, target.worldName());
  }

  @Test
  void leavingForgetsTheTrackedDimension() {
    ExperiencePersistence persistence = new ExperiencePersistence(null, null);
    FakeWorld world = experience();
    FakePlayer player = new FakePlayer(world.dimension(WorldDimension.NETHER));
    persistence.recordDimension(player, world);
    persistence.forgetDimension(player.uniqueId());

    // Back to the experience's start world, not the stale Nether sample.
    assertEquals(OVERWORLD,
        persistence.respawnPosition(player, world, ExperienceWorldType.NORMAL, null).worldName());
  }

  @Test
  void keepInventoryReachesEveryDimensionOfTheExperience() {
    FakeWorld overworld = experience();

    overworld.setKeepInventoryEverywhere(false);
    assertFalse(overworld.keepInventory);
    assertFalse(overworld.dimension(WorldDimension.NETHER).keepInventory, "the Nether must follow the rule");
    assertFalse(overworld.dimension(WorldDimension.END).keepInventory, "The End must follow the rule");

    // …and back on again, from a sibling this time: the whole experience flips, not just that world.
    overworld.dimension(WorldDimension.NETHER).setKeepInventoryEverywhere(true);
    assertTrue(overworld.keepInventory);
    assertTrue(overworld.dimension(WorldDimension.NETHER).keepInventory);
    assertTrue(overworld.dimension(WorldDimension.END).keepInventory);
  }

  /** An experience overworld with its two linked siblings. */
  private static FakeWorld experience() {
    FakeWorld overworld = new FakeWorld(OVERWORLD, WorldDimension.OVERWORLD, null);
    overworld.siblings = new FakeWorld[] {
        new FakeWorld(NETHER, WorldDimension.NETHER, overworld),
        new FakeWorld(END, WorldDimension.END, overworld)};
    return overworld;
  }

  private static final class FakeWorld implements WorldAdapter {
    private final String name;
    private final WorldDimension dimension;
    private final FakeWorld parent;
    private FakeWorld[] siblings = new FakeWorld[0];
    boolean keepInventory = true;

    FakeWorld(String name, WorldDimension dimension, FakeWorld parent) {
      this.name = name;
      this.dimension = dimension;
      this.parent = parent;
    }

    @Override public String name() { return name; }
    @Override public void setKeepInventory(boolean keep) { this.keepInventory = keep; }
    @Override public boolean isNether() { return dimension == WorldDimension.NETHER; }
    @Override public boolean isEnd() { return dimension == WorldDimension.END; }

    @Override
    public FakeWorld dimension(WorldDimension wanted) {
      if (wanted == null || wanted == dimension) {
        return this;
      }
      FakeWorld root = parent != null ? parent : this;
      if (wanted == WorldDimension.OVERWORLD) {
        return root;
      }
      for (FakeWorld sibling : root.siblings) {
        if (sibling.dimension == wanted) {
          return sibling;
        }
      }
      return null;
    }

    @Override
    public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }

  /** A player that is only ever asked which world it is in (all the respawn rule reads). */
  private static final class FakePlayer implements PlayerAdapter {
    private static final UUID ID = UUID.randomUUID();
    private final WorldAdapter world;

    FakePlayer(WorldAdapter world) {
      this.world = world;
    }

    @Override public UUID uniqueId() { return ID; }
    @Override public WorldAdapter world() { return world; }
    @Override public WorldPosition position() { return world.spawnPosition(); }

    @Override public String name() { return "Tester"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public int foodLevel() { return 20; }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
    @Override public void resetHealthScale() {}
    @Override public void resetScale() {}
    @Override public void setHealth(double health) {}
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public void clearBossBars() {}
    @Override public void clearTitle() {}
    @Override public void resetCompass() {}
  }
}
