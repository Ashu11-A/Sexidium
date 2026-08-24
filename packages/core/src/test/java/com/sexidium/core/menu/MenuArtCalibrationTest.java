package com.sexidium.core.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * Locks the in-client calibration knob: a global GUI-pixel nudge that absorbs a client/version baseline
 * mismatch in the negative-space art. {@code +dx} adds to the title shift (moves art right) and is undone
 * for any trailing title text; {@code +dy} lowers the emitted pack-font ascent (moves art down). Also pins
 * the procedural calibration overlay's classification: a full-window {@code screen/} glyph, but
 * {@code kind: debug} so it never appears in the baked-screen ({@link MenuArt#SCREEN_BG_IDS}) checks.
 *
 * <p>The calibration state is a global static, so every test that touches it resets to (0,0) in a finally.</p>
 */
class MenuArtCalibrationTest {

  @Test
  void calibrationDxShiftsTheFrameAndUndoesItForTrailingTitle() {
    String chest = MenuArt.chestGlyphId(6);
    try {
      MenuArt.setCalibration(0, 0);
      int baseShift = MenuArt.frameShift(chest);
      int baseReturn = MenuArt.titleReturnShift(chest);

      MenuArt.setCalibration(3, 0);
      assertEquals(baseShift + 3, MenuArt.frameShift(chest), "+dx pushes the frame right by dx px");
      assertEquals(baseReturn - 3, MenuArt.titleReturnShift(chest),
          "the title-return shift must cancel the extra dx so following title text stays put");
    } finally {
      MenuArt.setCalibration(MenuArt.DEFAULT_CALIBRATE_DX, MenuArt.DEFAULT_CALIBRATE_DY);
    }
  }

  @Test
  void calibrationDyLowersTheEmittedAscentOnly() {
    MenuArt.Glyph chest = MenuArt.chestGlyph(6);
    try {
      MenuArt.setCalibration(0, 0);
      int baseAscent = MenuArt.renderAscent(chest);
      assertEquals(chest.ascent(), baseAscent, "with no calibration the emitted ascent is the registry ascent");

      MenuArt.setCalibration(0, 2);
      assertEquals(baseAscent - 2, MenuArt.renderAscent(chest), "+dy lowers the ascent (moves art down) by dy px");
      assertEquals(chest.ascent(), chest.ascent(), "the registry ascent itself is never mutated");
    } finally {
      MenuArt.setCalibration(MenuArt.DEFAULT_CALIBRATE_DX, MenuArt.DEFAULT_CALIBRATE_DY);
    }
  }

  @Test
  void calibrationIsClampedToTheLimit() {
    try {
      MenuArt.setCalibration(1000, -1000);
      assertEquals(64, MenuArt.calibrateDx(), "dx clamps to +/-64");
      assertEquals(-64, MenuArt.calibrateDy(), "dy clamps to +/-64");
    } finally {
      MenuArt.setCalibration(MenuArt.DEFAULT_CALIBRATE_DX, MenuArt.DEFAULT_CALIBRATE_DY);
    }
  }

  @Test
  void bakedBaselineCorrectionIsTheMeasuredClientOffset() {
    // The art is aligned out of the box on the live client by this baked nudge (config adds on top).
    assertEquals(-1, MenuArt.DEFAULT_CALIBRATE_DX, "baked horizontal baseline correction");
    assertEquals(-2, MenuArt.DEFAULT_CALIBRATE_DY, "baked vertical baseline correction");
  }

  @Test
  void calibrationOverlayIsAFullWindowDebugGlyphExcludedFromBakedScreens() {
    MenuArt.Glyph glyph = MenuArt.glyph(MenuArt.CALIBRATION_GLYPH_ID);
    assertNotNull(glyph, "the calibration overlay glyph must be registered");
    assertTrue(MenuArt.isScreenGlyph(MenuArt.CALIBRATION_GLYPH_ID),
        "it must ride the full-window screen placement path");
    assertEquals(13, glyph.ascent(), "screen ascent (source y=0 -> GUI y=0)");
    assertEquals(0, glyph.leftX(), "screen left_x (source x=0 -> GUI x=0)");
    assertFalse(Arrays.asList(MenuArt.SCREEN_BG_IDS).contains(MenuArt.CALIBRATION_SCENE_ID),
        "the debug overlay must NOT be listed as a baked screen (it ships no committed PNG)");
  }
}
