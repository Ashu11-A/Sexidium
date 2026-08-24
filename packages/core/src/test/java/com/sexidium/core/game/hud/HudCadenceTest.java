package com.sexidium.core.game.hud;

import com.sexidium.core.platform.ConfigurationAdapter;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The knob that stops five HUDs drifting to five different cadences.
 *
 * <p>They already had: 20 ticks for the experience HUD, 40 for the lobby card, 10 for the Race board,
 * 20 for TNT War. The lobby's two seconds was the one people noticed.</p>
 */
class HudCadenceTest {

  @Test
  void withNothingConfigured_everyHudRepaintsOnceASecond() {
    assertEquals(20L, HudCadence.ticks(new FakeConfig()));
    assertEquals(20L, HudCadence.ticks(new FakeConfig(), "minigames.race.display.refresh-ticks"));
  }

  /** Moving the shared knob has to move every HUD that never asked to opt out. */
  @Test
  void theSharedKnobMovesAModeThatDeclaresNoOverride() {
    FakeConfig config = new FakeConfig().with(HudCadence.DEFAULT_PATH, 5L);

    assertEquals(5L, HudCadence.ticks(config, "minigames.race.display.refresh-ticks"));
  }

  /**
   * An explicit per-mode key still wins — Race genuinely wants a faster board.
   *
   * <p>Resolved by {@code contains()} rather than by a sentinel default, which is the whole reason an
   * override set to the same number as the shared cadence is still recognised as an override.</p>
   */
  @Test
  void anExplicitOverrideStillWins() {
    FakeConfig config = new FakeConfig()
        .with(HudCadence.DEFAULT_PATH, 20L)
        .with("minigames.race.display.refresh-ticks", 10L);

    assertEquals(10L, HudCadence.ticks(config, "minigames.race.display.refresh-ticks"));
  }

  /** A zero or negative period would schedule a timer that never fires, or fires forever. */
  @Test
  void aNonsensicalPeriodIsClampedRatherThanScheduled() {
    assertEquals(1L, HudCadence.ticks(new FakeConfig().with(HudCadence.DEFAULT_PATH, 0L)));
    assertEquals(1L, HudCadence.ticks(new FakeConfig().with(HudCadence.DEFAULT_PATH, -40L)));
  }

  /** Headless hosts and test doubles have no config; the default has to stand rather than throw. */
  @Test
  void aMissingConfigurationIsTheDefault() {
    assertEquals(HudCadence.DEFAULT_TICKS, HudCadence.ticks(null));
    assertEquals(HudCadence.DEFAULT_TICKS, HudCadence.ticks(null, "anything"));
  }

  private static final class FakeConfig implements ConfigurationAdapter {
    private final Map<String, Long> values = new HashMap<>();

    FakeConfig with(String path, long value) {
      values.put(path, value);
      return this;
    }

    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public long getLong(String path, long fallback) { return values.getOrDefault(path, fallback); }

    @Override public boolean getBoolean(String path, boolean fallback) { return fallback; }
    @Override public int getInt(String path, int fallback) { return fallback; }
    @Override public double getDouble(String path, double fallback) { return fallback; }
    @Override public String getString(String path, String fallback) { return fallback; }
    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public java.util.Set<String> keys(String path) { return java.util.Set.of(); }
    @Override public Object get(String path) { return null; }
    @Override public void set(String path, Object value) { }
    @Override public void reload() { }
    @Override public void save() { }
  }
}
