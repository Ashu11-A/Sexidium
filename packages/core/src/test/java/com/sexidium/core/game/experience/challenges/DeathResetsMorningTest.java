package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That a world nobody has lived in yet starts at dawn, and that winding its clock does not quietly eat
 * the first day off the counter.
 *
 * <p>The reset's replacement world comes out of the warm pool, which has been ticking since the server
 * booted — so about half the time it arrives at night. Everyone who just lost a world respawning into
 * the dark, on a mode where the next death costs the world too, is a punishment for the reset rather
 * than a fresh start.</p>
 */
class DeathResetsMorningTest {

  @Test
  void aReplacementWorldIsWoundToMorning() {
    RecordingWorld world = new RecordingWorld(37 * DeathResetsClock.VANILLA_DAY_TICKS + 13_000);

    challenge().onWorldReset(world);

    assertEquals(List.of(1000L), world.timesSet,
        "a world handed to players in the dark is the thing this exists to prevent");
  }

  /**
   * The ordering bug this locks down. {@code setTimeOfDay} moves the clock FORWARD — by up to a full day
   * when the world arrives just past dawn. Reading the baseline before that wind leaves it that far
   * behind, so the run's first day would be short by however dark the world happened to be, and the
   * counter would tick over to "day 1" early. Nothing about that is visible without this test.
   */
  @Test
  void theDayBaselineIsReadAfterTheClockHasMoved() {
    RecordingWorld world = new RecordingWorld(5 * DeathResetsClock.VANILLA_DAY_TICKS);

    challenge().onWorldReset(world);

    assertTrue(world.baselineReadAfterTimeSet,
        "fullTimeTicks() must be read after setTimeOfDay(), or the first day is short");
  }

  /** Wound to a real time of day, not a raw full-time value that would mean nothing to the platform. */
  @Test
  void morningIsATimeOfDayNotAnAbsoluteClock() {
    RecordingWorld world = new RecordingWorld(9_999_999L);

    challenge().onWorldReset(world);

    assertTrue(world.timesSet.get(0) >= 0 && world.timesSet.get(0) < DeathResetsClock.VANILLA_DAY_TICKS,
        "the seam takes 0-24000; anything else is the caller confusing time-of-day with full time");
  }

  /** A missing world is the reset having failed, not a reason to throw out of the reset callback. */
  @Test
  void aNullWorldIsSurvivable() {
    challenge().onWorldReset(null);
  }

  private static Challenge challenge() {
    return ChallengeCatalog.get("deathresets").factory().get();
  }

  /** Records the two calls whose ORDER is the thing under test. */
  private static final class RecordingWorld implements WorldAdapter {
    private final List<Long> timesSet = new ArrayList<>();
    private final long fullTime;
    private boolean baselineReadAfterTimeSet;

    RecordingWorld(long fullTime) {
      this.fullTime = fullTime;
    }

    @Override
    public void setTimeOfDay(long timeOfDayTicks) {
      timesSet.add(timeOfDayTicks);
    }

    @Override
    public long fullTimeTicks() {
      baselineReadAfterTimeSet = !timesSet.isEmpty();
      return fullTime;
    }

    @Override public String name() { return "test"; }
    @Override public WorldPosition spawnPosition() { return null; }
    @Override public int highestSolidBlockY(String worldName, int blockX, int blockZ) { return 0; }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) { }
    @Override public void playSound(WorldPosition target, SoundKey soundKey, float volume, float pitch) { }
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }
}
