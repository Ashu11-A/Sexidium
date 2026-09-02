package com.sexidium.core.game.hud.surface;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Blanking a row — "this is switched off" — as distinct from never pushing one, which is "nobody has
 * told me yet". The two look the same from a caller and must not look the same on a screen.
 */
class HudValuesBlankTest {

  @Test
  void anUnpushedKeyIsNotABlankedOne() {
    HudValues values = new HudValues();

    assertFalse(values.blanked("bosses"), "nothing has been said about this key either way");
    assertEquals(HudValues.UNSET, values.plain("bosses"),
        "an unset key draws the dash, so a publisher that stopped publishing is visible");
  }

  /**
   * Un-blanking must restore the last value rather than the dash. A player toggling a section back on
   * would otherwise read a row of dashes until the next cadence — which, on a one-second publisher, is
   * a visible flicker of "broken" every time somebody presses the button.
   */
  @Test
  void blankingKeepsTheValueUnderneath() {
    HudValues values = new HudValues();
    values.number("bosses", 2);

    values.blank("bosses", true);
    assertTrue(values.blanked("bosses"));
    assertEquals("2", values.plain("bosses"), "the value is kept, only its visibility changed");

    values.blank("bosses", false);
    assertFalse(values.blanked("bosses"));
    assertEquals("2", values.plain("bosses"));
  }

  /** Keys normalize the same way everywhere, or a caller blanks one row and un-blanks another. */
  @Test
  void blankingIsCaseAndSpaceInsensitiveLikeEveryOtherSetter() {
    HudValues values = new HudValues();

    values.blank("  Boss_Warden  ", true);

    assertTrue(values.blanked("boss_warden"));
  }

  /** A spacer and a fixed icon have no key at all; asking about them must be a plain no. */
  @Test
  void aKeylessElementIsNeverBlanked() {
    HudValues values = new HudValues();

    assertFalse(values.blanked(null));
    assertFalse(values.blanked("   "));
  }

  /**
   * A change of visibility is a change, and has to stamp the key. An animated row derives its phase
   * from that stamp, so a row that reappeared without stamping would come back mid-animation — or,
   * worse, be treated as having never moved.
   */
  @Test
  void togglingVisibilityCountsAsAChange() {
    HudValues values = new HudValues();
    values.text("bosses", LocalizedText.of(MessageKey.GAME_HUD_LINE));
    long afterPush = values.changedAt("bosses");

    values.blank("bosses", true);

    assertTrue(values.changedAt("bosses") >= afterPush, "blanking stamps the key");
  }

  /** Re-blanking an already-blanked key is idempotent, like every other setter here. */
  @Test
  void blankingTwiceSaysTheSameThing() {
    HudValues values = new HudValues();

    values.blank("bosses", true);
    values.blank("bosses", true);

    assertTrue(values.blanked("bosses"));
  }
}
