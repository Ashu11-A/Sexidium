package com.sexidium.core.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class LanguageTest {

  @Test
  void fromCode_english() {
    assertEquals(Language.EN, Language.fromCode("en", Language.EN));
    assertEquals(Language.EN, Language.fromCode("EN", Language.EN));
    assertEquals(Language.EN, Language.fromCode("en_US", Language.EN));
    assertEquals(Language.EN, Language.fromCode("en-US", Language.EN));
  }

  @Test
  void fromCode_portuguese() {
    assertEquals(Language.PT, Language.fromCode("pt", Language.EN));
    assertEquals(Language.PT, Language.fromCode("pt-BR", Language.EN));
    assertEquals(Language.PT, Language.fromCode("PT_BR", Language.EN));
  }

  @Test
  void fromCode_null_returnsFallback() {
    assertEquals(Language.EN, Language.fromCode(null, Language.EN));
    assertEquals(Language.PT, Language.fromCode(null, Language.PT));
  }

  @Test
  void fromCode_blank_returnsFallback() {
    assertEquals(Language.EN, Language.fromCode("  ", Language.EN));
  }

  @Test
  void fromCode_unknown_returnsFallback() {
    assertEquals(Language.EN, Language.fromCode("zh", Language.EN));
    assertEquals(Language.PT, Language.fromCode("fr", Language.PT));
  }

  @Test
  void fromLocale_english() {
    assertEquals(Language.EN, Language.fromLocale(Locale.ENGLISH, Language.PT));
    assertEquals(Language.EN, Language.fromLocale(Locale.US, Language.PT));
  }

  @Test
  void fromLocale_portuguese() {
    assertEquals(Language.PT, Language.fromLocale(Locale.forLanguageTag("pt-BR"), Language.EN));
  }

  @Test
  void fromLocale_null_returnsFallback() {
    assertEquals(Language.EN, Language.fromLocale(null, Language.EN));
  }

  @Test
  void code_returnsLowercaseCode() {
    assertEquals("en", Language.EN.code());
    assertEquals("pt", Language.PT.code());
  }
}
