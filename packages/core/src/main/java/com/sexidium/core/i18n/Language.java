package com.sexidium.core.i18n;

import java.util.Locale;

public enum Language {
  EN("en"),
  PT("pt");

  private final String code;

  Language(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }

  public static Language fromCode(String rawCode, Language fallbackLanguage) {
    if (rawCode == null || rawCode.isBlank()) {
      return fallbackLanguage;
    }
    String normalizedCode = rawCode.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    if (normalizedCode.startsWith("pt")) {
      return PT;
    }
    if (normalizedCode.startsWith("en")) {
      return EN;
    }
    return fallbackLanguage;
  }

  public static Language fromLocale(Locale locale, Language fallbackLanguage) {
    if (locale == null) {
      return fallbackLanguage;
    }
    return fromCode(locale.toLanguageTag(), fallbackLanguage);
  }
}
