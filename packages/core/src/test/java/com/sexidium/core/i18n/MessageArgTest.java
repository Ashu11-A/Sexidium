package com.sexidium.core.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageArgTest {

  @Test
  void text_factory_setsFields() {
    MessageArg arg = MessageArg.text("player", "Steve");
    assertEquals("player", arg.name());
    assertEquals("Steve", arg.value());
    assertEquals(MessageArgumentType.TEXT, arg.argumentType());
  }

  @Test
  void mini_factory_setsFields() {
    MessageArg arg = MessageArg.mini("title", "<red>Hello</red>");
    assertEquals("title", arg.name());
    assertEquals("<red>Hello</red>", arg.value());
    assertEquals(MessageArgumentType.MINI_MESSAGE, arg.argumentType());
  }

  @Test
  void localized_factory_requiresNonNull() {
    assertThrows(NullPointerException.class, () -> MessageArg.localized("key", null));
  }

  @Test
  void localized_factory_setsFields() {
    LocalizedText lt = LocalizedText.of(MessageKey.COMMAND_RELOAD);
    MessageArg arg = MessageArg.localized("msg", lt);
    assertEquals("msg", arg.name());
    assertEquals(MessageArgumentType.LOCALIZED, arg.argumentType());
    assertEquals(lt, arg.localizedText());
  }

  @Test
  void name_isNormalized_toLowercase() {
    MessageArg arg = MessageArg.text("PLAYER_NAME", "Steve");
    assertEquals("player-name", arg.name());
  }

  @Test
  void name_underscores_convertedToDashes() {
    MessageArg arg = MessageArg.text("player_count", "5");
    assertEquals("player-count", arg.name());
  }

  @Test
  void name_blank_throwsException() {
    assertThrows(IllegalArgumentException.class, () -> MessageArg.text("", "val"));
    assertThrows(IllegalArgumentException.class, () -> MessageArg.text("  ", "val"));
    assertThrows(IllegalArgumentException.class, () -> MessageArg.text(null, "val"));
  }

  @Test
  void nullObjectValue_becomesStringNull() {
    // text() calls String.valueOf(null) = "null"
    MessageArg arg = MessageArg.text("k", null);
    assertEquals("null", arg.value());
  }

  @Test
  void nullStringInConstructor_becomesEmpty() {
    // direct constructor with null value → compact constructor sets ""
    MessageArg arg = new MessageArg("k", null, MessageArgumentType.TEXT, null);
    assertEquals("", arg.value());
  }

  @Test
  void nullArgumentType_defaultsToText() {
    MessageArg arg = new MessageArg("k", "v", null, null);
    assertEquals(MessageArgumentType.TEXT, arg.argumentType());
  }
}
