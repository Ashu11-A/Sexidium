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
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the shared damage pipeline + health model — the life-cluster composition mechanism. */
class DamagePipelineTest {

  @Test
  void contributorsRunInAscendingOrder() {
    DamagePipeline pipeline = new DamagePipeline();
    List<Integer> seen = new ArrayList<>();
    pipeline.register(orderRecorder(30, seen));
    pipeline.register(orderRecorder(10, seen));
    pipeline.register(orderRecorder(20, seen));
    pipeline.process(new DamageContext(null, null, DamageCauseType.UNKNOWN, 5.0));
    assertEquals(List.of(10, 20, 30), seen);
  }

  @Test
  void poolAndXpBothChargeTheSameHit() {
    DamagePipeline pipeline = new DamagePipeline();
    double[] poolDrained = {0.0};
    double[] xpBurned = {0.0};
    // Shared Life style (order 20): drain the pool + absorb the native hit, but do NOT consume the
    // damage — XP Health is an independent system that must also see the hit.
    pipeline.register(new DamageContributor() {
      @Override
      public int order() {
        return 20;
      }

      @Override
      public void onDamage(DamageContext context) {
        poolDrained[0] += context.amount();
        context.absorb();
      }
    });
    // XP style (order 30): burns XP from the same hit, in parallel (not a residual).
    pipeline.register(new DamageContributor() {
      @Override
      public int order() {
        return 30;
      }

      @Override
      public void onDamage(DamageContext context) {
        if (context.amount() > 0.0) {
          xpBurned[0] += context.amount();
        }
        context.absorb();
      }
    });

    DamageContext context = pipeline.process(new DamageContext(null, null, DamageCauseType.UNKNOWN, 6.0));

    assertTrue(context.absorbed());
    assertEquals(6.0, poolDrained[0], "the shared pool drains the hit");
    assertEquals(6.0, xpBurned[0], "XP burns the SAME hit too — pool and XP both deplete together");
  }

  @Test
  void onlyOneContributorClaimsTheDeath() {
    DamagePipeline pipeline = new DamagePipeline();
    int[] resets = {0};
    // Shared Life style (order 20): claims the death.
    pipeline.register(new DamageContributor() {
      @Override
      public int order() {
        return 20;
      }

      @Override
      public void onDamage(DamageContext context) {
        context.markFatalHandled();
        resets[0]++;
      }
    });
    // Chained style (order 40): defers when the death was already handled.
    pipeline.register(new DamageContributor() {
      @Override
      public int order() {
        return 40;
      }

      @Override
      public void onDamage(DamageContext context) {
        if (!context.fatalHandled() && !context.absorbed()) {
          resets[0]++;
        }
      }
    });

    pipeline.process(new DamageContext(null, null, DamageCauseType.UNKNOWN, 100.0));
    assertEquals(1, resets[0], "a single fatal hit must reset the team exactly once");
  }

  @Test
  void healthModel_highestPriorityValueWins_andScaleApplies() {
    FakePlayer player = new FakePlayer();
    HealthModel model = new HealthModel(host(player));
    // XP source: lower priority value (full bar) + heart scale.
    model.register(new HealthSource() {
      @Override
      public int priority() {
        return 5;
      }

      @Override
      public OptionalDouble value(PlayerAdapter p) {
        return OptionalDouble.of(20.0);
      }

      @Override
      public OptionalDouble scale(PlayerAdapter p) {
        return OptionalDouble.of(2.0);
      }
    });
    // Pool source: higher priority value.
    model.register(new HealthSource() {
      @Override
      public int priority() {
        return 10;
      }

      @Override
      public OptionalDouble value(PlayerAdapter p) {
        return OptionalDouble.of(7.0);
      }
    });

    assertEquals(7.0, model.effective(player), "higher-priority pool value must win");
    model.writeAll();
    assertEquals(7.0, player.health, "single writer applies the pool value");
    assertEquals(2.0, player.scale, "heart scale comes from the scale-providing source");
  }

  // --- helpers ---

  private static DamageContributor orderRecorder(int order, List<Integer> sink) {
    return new DamageContributor() {
      @Override
      public int order() {
        return order;
      }

      @Override
      public void onDamage(DamageContext context) {
        sink.add(order);
      }
    };
  }

  private static ExperienceHost host(PlayerAdapter player) {
    return new ExperienceHost() {
      @Override
      public GameContext gameContext() {
        return null;
      }

      @Override
      public List<PlayerAdapter> online() {
        return List.of(player);
      }

      @Override
      public boolean isParticipant(PlayerAdapter playerAdapter) {
        return true;
      }

      @Override
      public ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks) {
        return null;
      }

      @Override
      public ScheduledTask runLater(Runnable runnable, long delayTicks) {
        return null;
      }

      @Override
      public BossBarHandle track(BossBarHandle bossBarHandle) {
        return bossBarHandle;
      }

      @Override
      public HudPanelHandle track(HudPanelHandle hudPanelHandle) {
        return hudPanelHandle;
      }

      @Override
      public WorldAdapter world() {
        return null;
      }

      @Override
      public ExperienceState sharedState() {
        return ExperienceState.empty();
      }

      @Override
      public void softRespawn(PlayerAdapter playerAdapter) {
      }

      @Override
      public void killParticipant(PlayerAdapter playerAdapter) {
      }
    };
  }

  /** Minimal player capturing health/scale writes. */
  private static final class FakePlayer implements PlayerAdapter {
    double health = 20.0;
    double scale = 1.0;

    @Override public void setHealth(double value) { this.health = value; }
    @Override public void setHealthScale(double value) { this.scale = value; }
    @Override public double health() { return health; }
    @Override public double maxHealth() { return 20.0; }
    @Override public boolean online() { return true; }

    @Override public UUID uniqueId() { return new UUID(0L, 0L); }
    @Override public String name() { return "Tester"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
    @Override public void resetHealthScale() {}
    @Override public void resetScale() {}
    @Override public void clearBossBars() {}
    @Override public void clearTitle() {}
    @Override public void resetCompass() {}
  }
}
