package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.events.RandomEventCatalog;
import com.sexidium.core.game.experience.events.RandomEventContext;
import com.sexidium.core.game.experience.events.RandomEventEngine;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;

import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Random Events. Every so often (a random interval between {@code min-interval-seconds} and
 * {@code max-interval-seconds}) a chaotic {@link com.sexidium.core.game.experience.events.RandomEvent}
 * fires on the whole party — speed, levitation, a zombie siege, TNT rain, a position shuffle, item rain …
 *
 * <p>The event logic lives in the standalone {@link RandomEventEngine} (24 built-in events, extensible past
 * 20), so the same engine can be reused by any other challenge — this challenge is just the driver that
 * schedules it and shows the countdown on the shared HUD.</p>
 */
public final class RandomEventsChallenge extends Challenge {
  private static final String ROW_COUNTDOWN = "countdown";
  private static final String ROW_FIRED = "fired";

  private HudSurfaceHandle countdown = HudSurfaceHandle.NOOP;
  private HudSurfaceHandle announcement = HudSurfaceHandle.NOOP;
  private RandomEventEngine engine;
  /** The interval the current countdown was rolled from, so the bar has something to be a fraction of. */
  private int currentIntervalSeconds;
  private int minIntervalSeconds;
  private int maxIntervalSeconds;
  private int remainingSeconds;

  public RandomEventsChallenge() {
    super("randomevents", "Random Events");
  }

  /**
   * The countdown to the next event: a bar that drains as it approaches, with the remaining time
   * beside it.
   *
   * <p>A bar earns its place here more than anywhere else in the catalogue — the whole tension of the
   * mode is "how long have we got", and a proportion answers that faster than a clock does.</p>
   */
  public static HudSurfaceSpec eventSpec() {
    return HudSurfaceSpec.persistent("randomevents")
        .anchor(HudAnchor.TOP_LEFT)
        .bar(ROW_COUNTDOWN, LocalizedText.of(MessageKey.EXPERIENCE_RANDOMEVENTS_COUNTDOWN))
        .build();
  }

  /** A toast naming the event that just fired, so the cause of the chaos is legible for a moment. */
  public static HudSurfaceSpec announcementSpec() {
    return HudSurfaceSpec.popup("randomevents_fired")
        .anchor(HudAnchor.TOP_LEFT)
        .duration(Duration.ofSeconds(4))
        .text(ROW_FIRED, LocalizedText.of(MessageKey.EXPERIENCE_RANDOMEVENTS_FIRED))
        .build();
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeDebug);
    countdown = hudSurface(registry, eventSpec());
    announcement = hudSurface(registry, announcementSpec());
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    engine = RandomEventCatalog.engine();
    // Default: one event every 1 minute 30 seconds (90s), fixed.
    minIntervalSeconds = Math.max(5, cfg().getInt(configPath("min-interval-seconds"), 90));
    maxIntervalSeconds = Math.max(minIntervalSeconds, cfg().getInt(configPath("max-interval-seconds"), 90));
    remainingSeconds = rollInterval();
    currentIntervalSeconds = remainingSeconds;
    for (PlayerAdapter participant : participants) {
      countdown.show(participant);
    }
    runTimer(this::tick, 20L, 20L);
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    countdown.show(playerAdapter);
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    countdown.hide(playerAdapter);
  }

  private void tick() {
    if (remainingSeconds > 0) {
      remainingSeconds--;
      pushCountdown();
      return;
    }
    if (!online().isEmpty()) {
      engine.fire(new HostContext());
    }
    remainingSeconds = rollInterval();
    currentIntervalSeconds = remainingSeconds;
    pushCountdown();
  }

  /** Drains toward zero, so a full bar means "just fired" and an empty one means "now". */
  private void pushCountdown() {
    double fraction = currentIntervalSeconds <= 0
        ? 0.0
        : (double) Math.max(0, remainingSeconds) / currentIntervalSeconds;
    countdown.progress(ROW_COUNTDOWN, fraction);
    countdown.text(ROW_COUNTDOWN, LocalizedText.of(MessageKey.GAME_HUD_LINE,
        MessageArg.text("text", formatTime(Math.max(0, remainingSeconds)))));
    countdown.refresh();
  }

  private int rollInterval() {
    int span = maxIntervalSeconds - minIntervalSeconds;
    return minIntervalSeconds + (span > 0 ? ThreadLocalRandom.current().nextInt(span + 1) : 0);
  }

  private void describeDebug(HudContext context) {
    if (!context.debug()) {
      return;
    }
    context.debugHeader(displayName());
    context.debugStat("Events", engine == null ? 0 : engine.size());
    context.debugStat("Interval", minIntervalSeconds + "-" + maxIntervalSeconds + "s");
  }

  private static String formatTime(int totalSeconds) {
    return totalSeconds / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", totalSeconds % 60);
  }

  /** Binds the engine to this experience: its participants, world, randomness and chat broadcast. */
  private final class HostContext implements RandomEventContext {
    @Override
    public List<PlayerAdapter> players() {
      return online();
    }

    @Override
    public WorldAdapter world() {
      return RandomEventsChallenge.this.world();
    }

    @Override
    public Random random() {
      return ThreadLocalRandom.current();
    }

    /**
     * Chat, plus a toast naming the event.
     *
     * <p>Chat alone was easy to miss in the middle of what the event had just done to the world; the
     * toast is fired per player so it lands on the corner for anyone the driver reaches and on the
     * action bar for everyone else.</p>
     */
    @Override
    public void announce(String miniMessage) {
      announcement.text(ROW_FIRED, LocalizedText.of(MessageKey.GAME_HUD_LINE,
          MessageArg.mini("text", miniMessage)));
      for (PlayerAdapter player : online()) {
        player.sendMiniMessage(miniMessage);
        announcement.show(player);
      }
    }
  }
}
