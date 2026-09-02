package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.game.experience.ExperienceWorldReset;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.hud.HudAlign;
import com.sexidium.core.platform.hud.HudColor;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the geometry that used to live only as prose comments in a hand-authored yml file.
 *
 * <p>Every constant asserted here was, before this compiler existed, a warning in a comment that
 * nothing enforced — and at least one of them (the sign of the y axis) was documented precisely
 * because it had already been got wrong.</p>
 */
class BetterHudAssetCompilerTest {
  private static final HudSurfaceSpec THREE_ROWS = HudSurfaceSpec.persistent("deathresets")
      .anchor(HudAnchor.TOP_LEFT)
      .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
      .text("days", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
      .text("resets", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_RESETS))
      .build();

  /**
   * Every text object asks BetterHud to parse the published line's ampersand codes. That switch is
   * what lets ONE row carry more than one colour — a green tick beside a dimmed label — and it is
   * paired with {@code BetterHudRows}, which serializes to exactly this dialect. Drop either half and
   * the readouts do not break loudly: they either lose every colour a template declared, or start
   * printing raw {@code &a} at the player.
   */
  @Test
  void everyRowAsksForTheLineToBeParsedForColour() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertEquals(3, countOf(layout, "use-legacy-format: true"),
        "one per row, or a row silently loses its colours");
    assertEquals(3, countOf(layout, "legacy-serializer: ampersand"),
        "the dialect has to be named, or BetterHud falls back to the operator's global config");
  }

  /**
   * The declared colour survives alongside it, and that is not redundancy. It is what a span carrying
   * no code of its own is drawn in — which is every span of every template that declares no colour.
   */
  @Test
  void theDeclaredColourIsStillWrittenAsTheFloor() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertEquals(3, countOf(layout, "color: \"white\""));
  }

  private static int countOf(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
      count++;
    }
    return count;
  }

  @Test
  void compilesOneLayoutAndOneHudPerSurface() {
    Map<String, String> files = BetterHudAssetCompiler.compile(THREE_ROWS);

    assertEquals(
        java.util.Set.of("layouts/sexidium/deathresets.yml", "huds/sexidium/deathresets.yml"),
        files.keySet(),
        "a persistent surface is a layout plus a hud, both inside the subtree the store owns");
  }

  /**
   * BetterHud's own font is configured at twice vanilla's glyph height, so a layout asking for scale 1
   * renders at double chat size. A spec's scale 1.0 means "normal", which is scale 0.5 here.
   */
  @Test
  void textIsHalfScale_becauseTheFontIsDoubleHeight() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertTrue(layout.contains("scale: 0.5"), "expected the font correction to be applied:\n" + layout);
    assertFalse(layout.contains("scale: 1\n"), "scale 1 would render at twice chat size");
  }

  /**
   * Positive y is DOWN in a BetterHud layout. A third row is y:20, not y:-20 — the exact mistake the
   * old hand-written file carried a comment about, because a comment was the only place to put it.
   */
  @Test
  void rowsStackDownwardsAtATenPixelPitch() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertTrue(layout.contains("y: 0"), layout);
    assertTrue(layout.contains("y: 10"), layout);
    assertTrue(layout.contains("y: 20"), layout);
    assertFalse(layout.contains("y: -"), "a negative offset would stack the rows off the top of the screen");
  }

  /**
   * The generated yml must contain no words at all — every row is one variable lookup resolving to a
   * line already rendered in the viewer's language. A label baked in here would be untranslatable,
   * which is exactly what was wrong with the file this replaces.
   *
   * <p>The pattern must be a SINGLE token. BetterHud's string container falls back to a variable-map
   * lookup for a name it does not know, and that fallback takes exactly one argument — a multi-token
   * pattern silently resolves to the literal {@code <none>} on every row.</p>
   */
  @Test
  void rowsCarryOnlyAVariableLookup_neverALabel() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertTrue(layout.contains("pattern: \"[string:sexidium_deathresets_duration]\""), layout);
    assertFalse(layout.contains("Played"), "an English label in the yml can never be translated");
    assertFalse(layout.contains("Days:"), "same");
    assertFalse(layout.contains("[string:sexidium "),
        "a space makes this a multi-argument lookup, which BetterHud resolves to <none>");
  }

  /**
   * The hud object positions the layout, and must not opt itself into BetterHud's own default list.
   *
   * <p>{@code gui} is a percentage of the window and {@code pixel} is a real pixel offset from it, so a
   * top-left surface is pinned ten pixels down and ten across from the corner of the screen.</p>
   */
  @Test
  void aTopLeftSurfaceSitsTenPixelsFromTheCorner() {
    String hud = BetterHudAssetCompiler.compile(THREE_ROWS).get("huds/sexidium/deathresets.yml");

    assertTrue(hud.startsWith("# Generated by Sexidium"), hud);
    assertTrue(hud.contains("sexidium_deathresets:"), hud);
    assertTrue(hud.contains("gui: { x: 0, y: 0 }"), hud);
    assertTrue(hud.contains("pixel: { x: 10, y: 10 }"), hud);
    assertFalse(hud.contains("default: true"),
        "the driver decides per player who wears this; a default would put it on everyone");
  }

  /**
   * A bottom-anchored surface has to start its own height above the edge, because rows always stack
   * downward — otherwise the last row renders off the bottom of the screen.
   */
  @Test
  void aBottomAnchoredSurfaceIsLiftedByItsOwnHeight() {
    HudSurfaceSpec spec = HudSurfaceSpec.persistent("bottom")
        .anchor(HudAnchor.BOTTOM_RIGHT)
        .text("a", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
        .text("b", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
        .build();

    String hud = BetterHudAssetCompiler.compile(spec).get("huds/sexidium/bottom.yml");

    assertTrue(hud.contains("gui: { x: 100, y: 100 }"), hud);
    // 10px inset + two 10px rows.
    assertTrue(hud.contains("pixel: { x: -10, y: -30 }"), hud);
  }

  /** A popup compiles into BetterHud's popups folder and carries its own expiry. */
  @Test
  void aPopupBecomesAPopupObjectWithADuration() {
    HudSurfaceSpec spec = HudSurfaceSpec.popup("randomevents_fired")
        .duration(Duration.ofSeconds(4))
        .text("fired", LocalizedText.of(MessageKey.EXPERIENCE_RANDOMEVENTS_FIRED))
        .build();

    Map<String, String> files = BetterHudAssetCompiler.compile(spec);

    assertTrue(files.containsKey("popups/sexidium/randomevents_fired.yml"), files.keySet().toString());
    assertTrue(files.get("popups/sexidium/randomevents_fired.yml").contains("duration: 80"),
        "four seconds is eighty ticks");
  }

  /** Nothing drawable, nothing generated — a stray file BetterHud must load and validate for no reason. */
  @Test
  void aSurfaceWithNoDrawableRowsCompilesToNothing() {
    HudSurfaceSpec spec = HudSurfaceSpec.persistent("empty").spacer(10).build();

    assertTrue(BetterHudAssetCompiler.compile(spec).isEmpty());
  }

  // ===== the animated row =======================================================================

  private static final HudSurfaceSpec COUNTDOWN = HudSurfaceSpec.popup("resetcountdown")
      .anchor(HudAnchor.CENTER)
      .duration(Duration.ofSeconds(45))
      .pulse("seconds", LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER), 3.0d, 5.0d)
      .build();

  private static String countdownLayout() {
    return BetterHudAssetCompiler.compile(COUNTDOWN).get("layouts/sexidium/resetcountdown.yml");
  }

  /**
   * BetterHud has no scale equation — {@code scale} is fixed at parse time — so a size animation can
   * only be a stack of fixed-size rows with the line moving between them.
   */
  @Test
  void aPulseCompilesIntoOneRowPerFrame_eachReadingItsOwnVariable() {
    String layout = countdownLayout();

    for (int frame = 0; frame < BetterHudAssetCompiler.PULSE_FRAMES; frame++) {
      assertTrue(
          layout.contains("[string:sexidium_resetcountdown_seconds_f" + frame + "]"),
          "frame " + frame + " missing:\n" + layout);
    }
    assertFalse(layout.contains("[string:sexidium_resetcountdown_seconds]"),
        "the un-suffixed variable is never drawn for a pulse; a driver publishing to it would"
            + " silently show nothing");
  }

  /**
   * Peak on the first frame, resting size on the last, evenly spaced in between — with the font
   * correction applied to all of them.
   *
   * <p>Even spacing is the point of the check. The easing lives in the CLOCK; if it lived in the
   * ladder instead, the last few of these eight sizes would round to the same emitted number and the
   * animation would visibly stop several frames early.</p>
   */
  @Test
  void theFramesRunFromThePeakDownToTheRestingSizeInEvenSteps() {
    String layout = countdownLayout();

    // Peak 5.0 and rest 3.0, halved by the font correction, in eight even steps of 2/7 × 0.5.
    for (int frame = 0; frame < BetterHudAssetCompiler.PULSE_FRAMES; frame++) {
      double expected = (5.0d - 2.0d * frame / 7.0d) * 0.5d;
      String emitted = "scale: " + (Math.round(expected * 1000.0d) / 1000.0d);
      assertTrue(layout.contains(emitted), "frame " + frame + " wanted '" + emitted + "':\n" + layout);
    }
    assertFalse(layout.contains("scale: 5"), "the font correction was skipped somewhere:\n" + layout);
  }

  /**
   * Each frame is lifted by half the height it gained, so the number swells around a fixed centre.
   * Without this it would grow downward out of its own top edge and lurch as it settled.
   */
  @Test
  void aBiggerFrameIsLiftedByHalfWhatItGained() {
    String layout = countdownLayout();

    // Row pitch 10 at scale 1: peak 5.0 is 20px taller than resting 3.0, so it starts 10px higher.
    assertTrue(layout.contains("y: -10"), "the peak frame is not centred on the resting row:\n" + layout);
    assertTrue(layout.contains("y: 0"), "the resting frame sits on the row itself:\n" + layout);
  }

  /** Centred, because a row that grows out of its left edge slides sideways instead of swelling. */
  @Test
  void aPulseIsCentredByDefault() {
    assertTrue(countdownLayout().contains("align: center"), countdownLayout());
  }

  /**
   * A row is drawn in the colour its DECLARATION names, not white.
   *
   * <p>This renderer draws through a font atlas, so {@link BetterHudRows} flattens the template to
   * plain text and every {@code <red>} in the lang file is gone before BetterHud sees the line. The
   * colour therefore has to come from the layout, and it used to be hardcoded white — which is how the
   * reset countdown came out white here and red on the vanilla-title half of the same declaration.</p>
   */
  @Test
  void aRowIsDrawnInTheColourItsDeclarationNames() {
    HudSurfaceSpec red = HudSurfaceSpec.popup("resetcountdown")
        .anchor(HudAnchor.CENTER)
        .pulse("seconds", LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER),
            HudAlign.CENTER, 3.0d, 5.0d, Duration.ofMillis(420), HudColor.RED)
        .build();

    String layout = BetterHudAssetCompiler.compile(red).get("layouts/sexidium/resetcountdown.yml");

    assertFalse(layout.contains("color: \"white\""),
        "no frame of a red row may still be white:\n" + layout);
    assertEquals(BetterHudAssetCompiler.PULSE_FRAMES, occurrences(layout, "color: \"red\""),
        "every frame of the pulse is the same colour, or the number changes colour as it settles:\n"
            + layout);
  }

  /** A declaration that names no colour keeps the white every row had before colours existed. */
  @Test
  void aRowThatNamesNoColourIsStillWhite() {
    String layout = BetterHudAssetCompiler.compile(THREE_ROWS).get("layouts/sexidium/deathresets.yml");

    assertEquals(3, occurrences(layout, "color: \"white\""), layout);
  }

  /**
   * The shipped countdown — the actual declaration, not a copy of it — compiles to ONE red number.
   *
   * <p>Reaching into core for the real spec is the point: a test that rebuilt an equivalent spec here
   * would keep passing after someone changed the one that ships.</p>
   */
  @Test
  void theShippedResetCountdownCompilesToARedNumber() {
    String layout = BetterHudAssetCompiler.compile(ExperienceWorldReset.countdownSpec())
        .get("layouts/sexidium/resetcountdown.yml");

    assertNotNull(layout, "the reset countdown must still compile to a layout");
    assertTrue(layout.contains("color: \"red\""),
        "the countdown the reset actually opens must be red on this surface too:\n" + layout);
    assertFalse(layout.contains("color: \"white\""), layout);
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
      count++;
    }
    return count;
  }

  /**
   * The middle of the window is 50/50 in BetterHud's percentage space, and needs no edge inset at all
   * — a centred surface is not measured from an edge. It is lifted by half its own height so it
   * straddles the middle rather than hanging below it.
   */
  @Test
  void aCentredSurfaceStraddlesTheMiddleOfTheWindow() {
    String hud = BetterHudAssetCompiler.compile(COUNTDOWN).get("popups/sexidium/resetcountdown.yml");

    assertTrue(hud.contains("gui: { x: 50, y: 50 }"), hud);
    // One row at resting scale 3.0 measures 30px, so it starts 15 above the middle.
    assertTrue(hud.contains("pixel: { x: 0, y: -15 }"), hud);
  }

  /** Which frame holds the line at a given moment — the animation, in one function. */
  @Test
  void theActiveFrameWalksFromPeakToRestOverTheSettle() {
    HudElement.PulseRow row = (HudElement.PulseRow) COUNTDOWN.element("seconds");
    int last = BetterHudAssetCompiler.PULSE_FRAMES - 1;

    assertEquals(0, BetterHudAssetCompiler.pulseFrame(row, 0L), "the instant it changed, it is at the peak");
    assertEquals(last, BetterHudAssetCompiler.pulseFrame(row, 10_000L), "long settled");

    int previous = 0;
    for (long elapsed = 0; elapsed <= row.settle().toMillis(); elapsed += 20L) {
      int frame = BetterHudAssetCompiler.pulseFrame(row, elapsed);
      assertTrue(frame >= previous, "the animation must never run backwards");
      assertTrue(frame <= last, "frame " + frame + " has no row to draw it");
      previous = frame;
    }
    assertEquals(last, previous, "the settle has to actually reach the resting frame");
  }
}
