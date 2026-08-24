package com.sexidium.core.game.experience.compose;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.experience.ExperienceHost;
import com.sexidium.core.game.experience.ExperienceState;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run-lifetime counters: the statistics that describe the RUN rather than the world it happens to be
 * in, and so are the ones a world regeneration has to carry forward.
 */
class ExperienceRunStatsTest {
  private static final UUID ASHU = UUID.randomUUID();
  private static final UUID BOB = UUID.randomUUID();

  @Test
  void runSecondsAccumulateForTheRunAndForEachPlayer() {
    ExperienceState state = ExperienceState.empty();
    ExperienceStats stats = new ExperienceStats(host(state));
    stats.addRunSeconds(10L, Map.of(ASHU, 10L, BOB, 6L));
    stats.addRunSeconds(5L, Map.of(ASHU, 5L));
    assertEquals(15L, stats.runSeconds());
    assertEquals(15L, stats.runSeconds(ASHU));
    assertEquals(6L, stats.runSeconds(BOB));
  }

  @Test
  void deathsAreCountedPerPlayerAndInTotal() {
    ExperienceState state = ExperienceState.empty();
    ExperienceStats stats = new ExperienceStats(host(state));
    stats.recordRunDeath(player(ASHU));
    stats.recordRunDeath(player(ASHU));
    stats.recordRunDeath(player(BOB));
    assertEquals(3L, stats.runDeaths());
    assertEquals(2L, stats.runDeaths(ASHU));
    assertEquals(1L, stats.runDeaths(BOB));
  }

  @Test
  void everyPlayerWhoHasEverBeenHereCanBeListed() {
    // "A list of durations for all players" — there is no separate index of who has been in the run;
    // the keys ARE the index, which is what keeps an offline player's history readable.
    ExperienceState state = ExperienceState.empty();
    ExperienceStats stats = new ExperienceStats(host(state));
    stats.addRunSeconds(10L, Map.of(ASHU, 10L, BOB, 4L));
    stats.recordRunDeath(player(BOB));
    assertEquals(Map.of(ASHU, 10L, BOB, 4L), stats.runSecondsByPlayer());
    assertEquals(Map.of(BOB, 1L), stats.runDeathsByPlayer());
  }

  @Test
  void runCountersLiveUnderTheCarriedPrefixAndWorldCountersDoNot() {
    // This is the invariant that makes one carry rule ("stats.run.*") both complete and safe.
    ExperienceState state = ExperienceState.empty();
    ExperienceStats stats = new ExperienceStats(host(state));
    stats.addRunSeconds(3L, Map.of(ASHU, 3L));
    stats.recordRunDeath(player(ASHU));
    stats.recordDeath(player(ASHU));
    stats.recordBlocksBroken(5L);

    long carried = state.values().keySet().stream()
        .filter(key -> key.startsWith(ExperienceStats.RUN_KEY_PREFIX)).count();
    assertEquals(4L, carried, "run seconds (total + player) and run deaths (total + player)");
    assertTrue(state.values().containsKey("stats.deaths.total"),
        "the world-scoped tally still exists…");
    assertTrue(state.values().keySet().stream()
            .noneMatch(key -> key.startsWith(ExperienceStats.RUN_KEY_PREFIX) && key.contains("blocks")),
        "…and is not smuggled into the carried namespace");
  }

  @Test
  void zeroAndNullInputsWriteNothing() {
    ExperienceState state = ExperienceState.empty();
    ExperienceStats stats = new ExperienceStats(host(state));
    stats.addRunSeconds(0L, null);
    stats.addRunSeconds(0L, Map.of(ASHU, 0L));
    assertTrue(state.values().isEmpty());
    assertEquals(0L, stats.runSeconds(null));
    assertEquals(0L, stats.runDeaths(null));
  }

  // ----- fakes ---------------------------------------------------------------------------------

  private static ExperienceHost host(ExperienceState state) {
    return new ExperienceHost() {
      @Override public GameContext gameContext() { return null; }
      @Override public List<PlayerAdapter> online() { return List.of(); }
      @Override public boolean isParticipant(PlayerAdapter playerAdapter) { return true; }
      @Override public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) { return null; }
      @Override public ScheduledTask runLater(Runnable runnable, long delayTicks) { return null; }
      @Override public BossBarHandle track(BossBarHandle bossBarHandle) { return bossBarHandle; }
      @Override public HudPanelHandle track(HudPanelHandle hudPanelHandle) { return hudPanelHandle; }
      @Override public WorldAdapter world() { return null; }
      @Override public ExperienceState sharedState() { return state; }
      @Override public void softRespawn(PlayerAdapter playerAdapter) { }
      @Override public void killParticipant(PlayerAdapter playerAdapter) { }
    };
  }

  private static PlayerAdapter player(UUID id) {
    return new PlayerAdapter() {
      @Override public UUID uniqueId() { return id; }
      @Override public String name() { return "Player"; }
      @Override public Locale locale() { return Locale.ENGLISH; }
      @Override public boolean hasPermission(String permission) { return false; }
      @Override public void sendMiniMessage(String miniMessage) { }
      @Override public void sendPlainMessage(String message) { }
      @Override public boolean online() { return true; }
      @Override public boolean dead() { return false; }
      @Override public WorldAdapter world() { return null; }
      @Override public WorldPosition position() { return null; }
      @Override public void teleport(WorldPosition targetPosition) { }
      @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
      @Override public void setGameMode(GameModeType gameModeType) { }
      @Override public double health() { return 20.0; }
      @Override public double maxHealth() { return 20.0; }
      @Override public void setHealth(double health) { }
      @Override public int foodLevel() { return 20; }
      @Override public void setFoodLevel(int foodLevel) { }
      @Override public InventoryAdapter inventory() { return null; }
      @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
      @Override public void showTitle(TitleSpec titleSpec) { }
      @Override public void sendActionBar(String miniMessage) { }
      @Override public void setCompassTarget(WorldPosition targetPosition) { }
      @Override public void clearInventory() { }
      @Override public void clearPotionEffects() { }
    };
  }
}
