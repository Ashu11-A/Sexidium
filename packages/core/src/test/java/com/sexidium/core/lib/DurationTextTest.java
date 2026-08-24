package com.sexidium.core.lib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The short-duration formatter behind the Death Resets "Played" readout. */
class DurationTextTest {
  @Test
  void secondsOnlyBelowAMinute() {
    assertEquals("0s", DurationText.compact(0L));
    assertEquals("41s", DurationText.compact(41L));
    assertEquals("59s", DurationText.compact(59L));
  }

  @Test
  void minutesPadTheirSeconds() {
    // "12m 5s" beside "12m 45s" one second later reads as a typo, so the seconds are padded.
    assertEquals("1m 00s", DurationText.compact(60L));
    assertEquals("12m 05s", DurationText.compact(725L));
    assertEquals("59m 59s", DurationText.compact(3599L));
  }

  @Test
  void hoursDropTheSeconds() {
    assertEquals("1h 0m", DurationText.compact(3600L));
    assertEquals("3h 12m", DurationText.compact(11520L));
    assertEquals("23h 59m", DurationText.compact(86399L));
  }

  @Test
  void daysDropTheMinutes() {
    assertEquals("1d 0h", DurationText.compact(86400L));
    assertEquals("2d 4h", DurationText.compact(2 * 86400L + 4 * 3600L + 1799L));
  }

  @Test
  void negativeReadsAsZeroRatherThanCountingBackwards() {
    // A counter below zero is a bug upstream; rendering it as negative time only moves the confusion
    // onto the player.
    assertEquals("0s", DurationText.compact(-1L));
    assertEquals("0s", DurationText.compact(Long.MIN_VALUE));
  }
}
