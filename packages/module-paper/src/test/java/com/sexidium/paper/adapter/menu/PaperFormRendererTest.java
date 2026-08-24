package com.sexidium.paper.adapter.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the Bedrock-form text projection. The Bedrock client cannot render {@code §x} hex colour
 * sequences (it shows them as garbage), so Sexidium's MiniMessage gradient/hex titles must be flattened
 * to clean plain text for mobile players.
 */
class PaperFormRendererTest {
  @Test
  void gradientTitleBecomesCleanPlainTextWithNoSectionCodes() {
    String result = PaperFormRenderer.plain("<gradient:#ff5f6d:#ffc371><bold>Sexidium</bold></gradient>");

    assertEquals("Sexidium", result);
    assertFalse(result.indexOf('§') >= 0, "plain form text must contain no section/hex colour codes");
  }

  @Test
  void namedColoursAndTagsAreStripped() {
    assertEquals("Competitive game modes",
        PaperFormRenderer.plain("<gray>Competitive game modes</gray>"));
    assertEquals("Minigames", PaperFormRenderer.plain("<aqua><bold>Minigames</bold></aqua>"));
  }

  @Test
  void blankAndNullCollapseToEmpty() {
    assertTrue(PaperFormRenderer.plain(null).isEmpty());
    assertTrue(PaperFormRenderer.plain("   ").isEmpty());
  }
}
