package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DropContext;
import com.sexidium.core.game.experience.compose.DropContributor;
import com.sexidium.core.game.experience.compose.DropMultiplierService;
import com.sexidium.core.game.experience.compose.DropPhase;
import com.sexidium.core.game.experience.compose.DropSource;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;

/**
 * Every block broken drops a multiplying stack of itself; the multiplier doubles each break. The
 * multiplier is a single shared value for the whole experience — every connected player breaking
 * blocks pushes the same counter up — and it is persisted, so an experience that has reached, say, a
 * 6× multiplier keeps it across player disconnects and server restarts (shared persistent state).
 *
 * <p>Rather than handling block breaks itself, Double Drops registers a {@link DropPhase#TRANSFORM}
 * stage on the shared {@link com.sexidium.core.game.experience.compose.DropPipeline}. That is what
 * lets the multiplier apply to loot ANOTHER challenge produced — a Randomizer remap, or the bulk
 * blocks a Break-One-Break-All sweep removes — because every loot path runs through the same context.
 * It publishes {@link DropMultiplierService} so siblings can read the live multiplier.</p>
 */
public final class DoubleDropsChallenge extends Challenge {
  private static final String KEY_MULTIPLIER = "multiplier";

  public DoubleDropsChallenge() {
    super("doubledrops", "Double Drops");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.dropContributor(new Multiplier());
    registry.publish(DropMultiplierService.class, this::currentDropCount);
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gold>Drop multiplier:</gold> <white>x" + currentDropCount() + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Start / max", startingDropCount() + " / " + maxDropsPerBreak());
      context.debugStat("At cap", currentDropCount() >= maxDropsPerBreak());
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // Seed the shared multiplier only the first time the experience runs; a persisted value is kept.
    if (!stateHas(KEY_MULTIPLIER)) {
      setStateInt(KEY_MULTIPLIER, startingDropCount());
    }
  }

  /** Scales the accumulated loot by the shared multiplier; advances it only on a manual break. */
  private final class Multiplier implements DropContributor {
    @Override
    public DropPhase phase() {
      return DropPhase.TRANSFORM;
    }

    @Override
    public void contribute(DropContext context) {
      // Ensure there is something to multiply: for a bare manual break the source block is the loot.
      context.seedSourceItem();
      int amount = currentDropCount();
      context.multiply(amount, maxDropsPerBreak());
      // Pour the payout out over time instead of in one tick — at the cap that is thousands of item
      // entities, and spawning them all at once is what froze the server.
      context.spreadOver(streamTicks(amount));
      // Only a player's own manual break grows the multiplier; sweep/explosion loot must not, or a
      // single break-all wave would slam it straight to the cap.
      if (context.source() == DropSource.BLOCK_BREAK) {
        advanceDropCount(amount);
      }
    }
  }

  /**
   * How long this break's loot takes to pour out, in ticks. The duration is proportional to how close
   * the multiplier is to the configured cap: the cap takes {@link #streamSeconds()} (10s by default) and
   * everything below it takes its share — 65536 → 10s means 512 → 0.078s, i.e. still effectively
   * instant. Anchoring on the CONFIGURED cap rather than a constant is what keeps the rule correct when
   * a server lowers {@code max-drops-per-break}: its own maximum is always the 10-second payout.
   */
  private int streamTicks(int multiplier) {
    return streamTicks(multiplier, maxDropsPerBreak(), streamSeconds());
  }

  /**
   * The proportional rule, in one testable place: {@code maxMultiplier} pours for {@code capSeconds},
   * and {@code multiplier} pours for its share of that. Never less than a single tick (a payout still
   * has to land) and never more than the cap.
   */
  static int streamTicks(int multiplier, int maxMultiplier, double capSeconds) {
    int max = Math.max(1, maxMultiplier);
    long capTicks = Math.round(Math.max(0.0, capSeconds) * 20.0);
    if (capTicks <= 1L) {
      return 1; // streaming disabled (stream-seconds: 0) — everything drops at once
    }
    long ticks = Math.round(capTicks * (Math.min(Math.max(multiplier, 1), max) / (double) max));
    return (int) Math.max(1L, Math.min(capTicks, ticks));
  }

  private int currentDropCount() {
    return stateInt(KEY_MULTIPLIER, startingDropCount());
  }

  private void advanceDropCount(int amount) {
    setStateInt(KEY_MULTIPLIER, Math.min(maxDropsPerBreak(), Math.max(amount, 1) * 2));
  }

  private int startingDropCount() {
    return Math.max(1, cfg().getInt(configPath("starting-drop-count"), 1));
  }

  private int maxDropsPerBreak() {
    return Math.max(1, cfg().getInt(configPath("max-drops-per-break"), 4096));
  }

  /** Seconds a payout at the configured cap takes to finish pouring out. */
  private double streamSeconds() {
    return Math.max(0.0, cfg().getDouble(configPath("stream-seconds"), 10.0));
  }
}
