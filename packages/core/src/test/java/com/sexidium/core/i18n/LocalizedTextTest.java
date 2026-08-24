package com.sexidium.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LocalizedTextTest {

  @Test
  void of_noArgs_hasEmptyArguments() {
    LocalizedText text = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    assertEquals(MessageKey.COMMAND_RELOAD, text.messageKey());
    assertTrue(text.arguments().isEmpty());
  }

  @Test
  void of_withArgs_hasArguments() {
    MessageArg arg = MessageArg.text("player", "Steve");
    LocalizedText text = LocalizedText.of(MessageKey.COMMAND_RELOAD, arg);
    assertEquals(1, text.arguments().size());
    assertEquals(arg, text.arguments().get(0));
  }

  @Test
  void nullArgs_becomesEmptyList() {
    LocalizedText text = new LocalizedText(MessageKey.COMMAND_RELOAD, null);
    assertNotNull(text.arguments());
    assertTrue(text.arguments().isEmpty());
  }

  @Test
  void arguments_isImmutable() {
    LocalizedText text = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    assertThrows(UnsupportedOperationException.class, () ->
        text.arguments().add(MessageArg.text("x", "y")));
  }

  @Test
  void equality_byValue() {
    LocalizedText a = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    LocalizedText b = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    assertEquals(a, b);
  }
}
