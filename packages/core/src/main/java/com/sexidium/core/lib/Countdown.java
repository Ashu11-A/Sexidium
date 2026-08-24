package com.sexidium.core.lib;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.IntConsumer;

public final class Countdown {
  private final GameContext gameContext;
  private final MessageKey titleKey;
  private final List<MessageArg> titleArguments;
  private final int totalSeconds;
  private final BossBarColor bossBarColor;
  private final IntConsumer onSecond;
  private final Runnable onComplete;
  private BossBarHandle bossBarHandle;
  private ScheduledTask scheduledTask;
  private int remainingSeconds;
  private boolean stopped;
  private boolean paused;

  /**
   * @param titleKey the boss bar's title, or {@code null} for a <b>bar-less</b> countdown — the clock
   *                 still ticks and {@code onSecond} still fires, but nothing is drawn. That is for a
   *                 caller that puts the count on screen itself: the reset countdown draws a big number
   *                 in the middle of the screen, and a boss bar counting the same seconds underneath it
   *                 is the same information twice.
   */
  public Countdown(
      GameContext gameContext,
      MessageKey titleKey,
      int seconds,
      BossBarColor bossBarColor,
      IntConsumer onSecond,
      Runnable onComplete,
      MessageArg... titleArguments
  ) {
    this.gameContext = gameContext;
    this.titleKey = titleKey;
    this.titleArguments = titleArguments == null ? List.of() : List.of(titleArguments);
    this.totalSeconds = Math.max(1, seconds);
    this.remainingSeconds = this.totalSeconds;
    this.bossBarColor = bossBarColor == null ? BossBarColor.WHITE : bossBarColor;
    this.onSecond = onSecond;
    this.onComplete = onComplete;
  }

  public void start(Collection<? extends PlayerAdapter> viewers) {
    // No title key means no bar at all — every path below already tolerates a null handle, so a
    // bar-less countdown is just a timer that nobody has to hide.
    bossBarHandle = titleKey == null ? null : gameContext.server().ui()
        .createBossBar(title(remainingSeconds), 1.0f, bossBarColor, BossBarOverlay.PROGRESS);
    if (viewers != null) {
      for (PlayerAdapter viewer : viewers) {
        addViewer(viewer);
      }
    }
    scheduledTask = gameContext.server().scheduler().runTimer(this::tick, 0L, 20L);
  }

  public void addViewer(PlayerAdapter viewer) {
    if (stopped || viewer == null || bossBarHandle == null) {
      return;
    }
    bossBarHandle.show(viewer);
  }

  /**
   * Hides this countdown's boss bar for a single viewer, e.g. when that player leaves the match while
   * the countdown is still running for everyone else.
   */
  public void removeViewer(PlayerAdapter viewer) {
    if (viewer == null || bossBarHandle == null) {
      return;
    }
    bossBarHandle.hide(viewer);
  }

  public void pause() {
    paused = true;
  }

  public void resume() {
    paused = false;
  }

  public int remaining() {
    return remainingSeconds;
  }

  public void stop() {
    if (stopped) {
      return;
    }
    stopped = true;
    if (scheduledTask != null) {
      scheduledTask.cancel();
    }
    if (bossBarHandle != null) {
      bossBarHandle.close();
    }
  }

  private void tick() {
    if (stopped || paused) {
      return;
    }
    if (remainingSeconds <= 0) {
      stop();
      if (onComplete != null) {
        onComplete.run();
      }
      return;
    }
    if (bossBarHandle != null) {
      float progress = Math.max(0.0f, Math.min(1.0f, (float) remainingSeconds / (float) totalSeconds));
      bossBarHandle.title(title(remainingSeconds));
      bossBarHandle.progress(progress);
    }
    if (onSecond != null) {
      onSecond.accept(remainingSeconds);
    }
    remainingSeconds--;
  }

  private LocalizedText title(int seconds) {
    List<MessageArg> arguments = new ArrayList<>(titleArguments);
    arguments.add(MessageArg.text("time", format(seconds)));
    return new LocalizedText(titleKey, arguments);
  }

  public static String format(int totalSeconds) {
    int minutes = totalSeconds / 60;
    int seconds = totalSeconds % 60;
    return minutes > 0 ? String.format("%d:%02d", minutes, seconds) : Integer.toString(seconds);
  }
}
