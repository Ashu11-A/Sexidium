package com.sexidium.core.game.experience;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The thirty-second data-loss window at the end of every experience.
 *
 * <p>{@code stop()} used to run {@code commitOccupancy()}, {@code persistence.flushState()} and then
 * {@code releaseAndReset(player)} for every player — and {@code releaseAndReset} goes on to
 * {@code resetStatuses()}, which <b>clears the inventory</b>. The hook that actually captures a
 * player, {@code onParticipantLeaving -> persistence.capturePlayerState}, fires only on the per-player
 * leave path and never from {@code stop()}. So a match ending — an operator stopping the server, a
 * rolling update, a time limit — wrote whatever the last autosave had, and that cadence is
 * {@code experiences.common.autosave-ticks}, default 600 ticks = <b>thirty seconds</b>. Everything a
 * player did in that window was cleared rather than saved.</p>
 *
 * <p>This is a structural guard on the ORDER of three statements, in the style of
 * {@link ExperienceResetDesignTest}, and for the same reason: {@code ExperienceGame} is {@code final}
 * and needs the whole manager/context graph, so a live harness would be brittle and disproportionate
 * to what it adds. The invariant here is exactly an ordering, which is what a structural guard is
 * good at — and it fails the build for precisely the thing that went wrong.</p>
 */
class ExperienceStopCaptureTest {

  private static String source() {
    Path path = Path.of("src/main/java/com/sexidium/core/game/experience/ExperienceGame.java");
    try {
      return Files.readString(path);
    } catch (IOException fromModuleRoot) {
      try {
        return Files.readString(Path.of("packages/core").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /** The body of {@code stop()}, from its signature to the {@code clearParticipants()} that ends it. */
  private static String stopBody() {
    String source = source();
    int start = source.indexOf("public void stop(LocalizedText reason)");
    assertTrue(start > 0, "ExperienceGame.stop(LocalizedText) has been renamed or removed");
    int end = source.indexOf("clearParticipants();", start);
    assertTrue(end > start, "stop() no longer ends in clearParticipants()");
    return source.substring(start, end);
  }

  @Test
  @DisplayName("stop() captures every player BEFORE anything resets their inventory")
  void capturesPlayerStateBeforeReset() {
    String stop = stopBody();

    int flush = stop.indexOf("persistence.flushState();");
    int capture = stop.indexOf("persistence.capturePlayerState(player)");
    int release = stop.indexOf("releaseAndReset(player)");

    assertTrue(flush > 0, "stop() must still flush the shared challenge state");
    assertTrue(capture > 0, "stop() must capture every player's own state; without it, up to one"
        + " autosave interval (30 s by default) of their play is cleared instead of saved");
    assertTrue(capture > flush, "the capture belongs after the state flush, so the flush's own"
        + " writes are not undone by it");
    assertTrue(capture < release, "the capture must come BEFORE releaseAndReset, which reaches"
        + " resetStatuses() and clears the live inventory");
  }

  @Test
  @DisplayName("stop() clears an inventory only when the snapshot was actually saved")
  void clearsOnlyWhatWasSaved() {
    String stop = stopBody();
    int capture = stop.indexOf("if (persistence.capturePlayerState(player)");
    int clear = stop.indexOf("player.clearInventory();");

    assertTrue(capture > 0 && clear > capture,
        "the clear must be GUARDED on the capture having saved, exactly as onParticipantLeaving"
            + " guards it: a transient experience has no persistent folder and saves nothing, so"
            + " clearing there would destroy the items rather than park them");
  }
}
