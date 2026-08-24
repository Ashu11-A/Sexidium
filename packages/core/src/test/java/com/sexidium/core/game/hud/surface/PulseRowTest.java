package com.sexidium.core.game.hud.surface;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The animated row, and the change stamp that drives it.
 *
 * <p>The whole design rests on one distinction: a pulse fires when a value BECOMES DIFFERENT, not when
 * it is written. Every consumer in this codebase pushes its values unconditionally on every cadence —
 * that is what lets a readout come up mid-match with something to draw on its first frame — so a stamp
 * that counted writes would leave an animation permanently at its peak, retriggered every tick and
 * never once allowed to settle.</p>
 */
class PulseRowTest {
  private static final LocalizedText TEMPLATE =
      LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER);

  @Test
  void aRepeatedPushIsNotAChange_soAnIdempotentConsumerDoesNotRetriggerTheAnimation() {
    AtomicLong now = new AtomicLong(1_000L);
    HudValues values = new HudValues(now::get);

    values.number("seconds", 5);
    long first = values.changedAt("seconds");

    now.set(1_500L);
    values.number("seconds", 5);

    assertEquals(first, values.changedAt("seconds"),
        "re-pushing the same value is what every consumer does every cadence; treating it as a change"
            + " would pin the pulse at its peak for ever");

    now.set(2_000L);
    values.number("seconds", 4);
    assertEquals(2_000L, values.changedAt("seconds"), "a different value IS a change");
  }

  @Test
  void aValueNeverPushedReadsAsAtRest_notMidAnimation() {
    HudValues values = new HudValues();

    assertEquals(0L, values.changedAt("seconds"));

    HudElement.PulseRow row = new HudElement.PulseRow("seconds", TEMPLATE, 3.0d, 5.0d);
    // Whatever "now" is, the gap from an epoch stamp of zero is enormous, so the phase saturates.
    assertEquals(1.0d, row.easedProgress(System.currentTimeMillis()), 1.0e-9d,
        "an unpushed surface must not open with a pop nobody asked for");
  }

  @Test
  void theSizeStartsAtThePeakAndEndsAtRest() {
    HudElement.PulseRow row = new HudElement.PulseRow("seconds", TEMPLATE, 3.0d, 5.0d);

    assertEquals(5.0d, row.scaleAt(0.0d), 1.0e-9d, "the instant it changes, it is at the peak");
    assertEquals(3.0d, row.scaleAt(1.0d), 1.0e-9d, "once settled, it is at the resting size");
  }

  /**
   * The sizes are evenly spaced and the CLOCK is eased, never the other way round.
   *
   * <p>A driver renders a pulse as a fixed handful of sizes. Putting the ease-out into the size ladder
   * bunches most of that handful within a percent or two of the resting size — visually identical
   * rungs, so an animation that jumps twice and then appears to stop. Even rungs stepped through on an
   * eased clock give the same curve with every rung visible.</p>
   */
  @Test
  void theSizeLadderIsEvenlySpaced_soNoTwoStepsLookAlike() {
    HudElement.PulseRow row = new HudElement.PulseRow("seconds", TEMPLATE, 3.0d, 5.0d);

    assertEquals(4.0d, row.scaleAt(0.5d), 1.0e-9d, "half way down the fall is half way down the sizes");
    double gap = row.scaleAt(0.0d) - row.scaleAt(0.125d);
    for (int step = 1; step < 8; step++) {
      assertEquals(gap, row.scaleAt(step / 8.0d) - row.scaleAt((step + 1) / 8.0d), 1.0e-9d,
          "step " + step + " is a different size jump from the first; the ladder is not even");
    }
  }

  /** Eased in TIME: half way through the settle, the fall is already most of the way done. */
  @Test
  void theFallIsFastFirstAndSlowLast() {
    HudElement.PulseRow row =
        new HudElement.PulseRow("seconds", TEMPLATE, null, 3.0d, 5.0d, Duration.ofMillis(400));

    assertTrue(row.easedProgress(200L) > 0.5d,
        "a cubic ease-out is past the midpoint by half time; got " + row.easedProgress(200L));
    assertEquals(0.0d, row.easedProgress(0L), 1.0e-9d);
    assertEquals(1.0d, row.easedProgress(400L), 1.0e-9d);

    // Monotone all the way down: progress that went backwards would read as a stutter.
    double previous = 0.0d;
    for (long elapsed = 0L; elapsed <= 400L; elapsed += 10L) {
      double current = row.easedProgress(elapsed);
      assertTrue(current >= previous, "the fall must only ever advance; " + current + " < " + previous);
      previous = current;
    }
  }

  @Test
  void thePhaseIsClampedAtBothEnds() {
    HudElement.PulseRow row =
        new HudElement.PulseRow("seconds", TEMPLATE, null, 3.0d, 5.0d, Duration.ofMillis(400));

    assertEquals(0.0d, row.phase(-50L), 1.0e-9d, "a stamp in the future is the start, not a negative size");
    assertEquals(0.0d, row.phase(0L), 1.0e-9d);
    assertEquals(0.5d, row.phase(200L), 1.0e-9d);
    assertEquals(1.0d, row.phase(4_000L), 1.0e-9d, "long past the settle is still just settled");
  }

  @Test
  void aPeakSmallerThanRestIsRejected() {
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> new HudElement.PulseRow("seconds", TEMPLATE, 5.0d, 3.0d));

    assertTrue(failure.getMessage().contains("peaks at"), failure.getMessage());
  }

  /**
   * A driver that cannot animate must still be offered the surface. The animation is a flourish on a
   * text row; demanding a capability for it would take a perfectly legible countdown away from every
   * player without the overlay plugin.
   */
  @Test
  void aPulseNeedsOnlyTheTextCapability() {
    HudSurfaceSpec spec = HudSurfaceSpec.popup("resetcountdown")
        .anchor(HudAnchor.CENTER)
        .pulse("seconds", TEMPLATE, 3.0d, 5.0d)
        .build();

    assertTrue(spec.drawableBy(Set.of(HudCapability.TEXT, HudCapability.POPUP)));
    assertTrue(spec.animated(), "a driver decides its repaint rate from this");

    assertFalse(HudSurfaceSpec.persistent("plain").text("a", TEMPLATE).build().animated(),
        "a static readout must not be dragged onto the animation cadence");
  }

  /** The line itself is a plain text row; only the size is animated, and the size is not the driver's
   *  business to put into words. */
  @Test
  void aPulseRendersAsAnOrdinaryLine() {
    HudValues values = new HudValues();
    HudElement.PulseRow row = new HudElement.PulseRow("seconds", TEMPLATE, 3.0d, 5.0d);
    values.number("seconds", 4);

    LocalizedText line = values.render(row);

    assertNotNull(line);
    assertEquals(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER, line.messageKey());
    assertTrue(line.arguments().stream().anyMatch(argument -> "value".equals(argument.name())),
        "the pushed number is substituted the same way a text row's is");
  }
}
