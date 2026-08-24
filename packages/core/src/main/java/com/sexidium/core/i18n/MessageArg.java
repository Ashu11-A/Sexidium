package com.sexidium.core.i18n;

import java.util.Locale;
import java.util.Objects;

public record MessageArg(
    String name,
    String value,
    MessageArgumentType argumentType,
    LocalizedText localizedText
) {
  public MessageArg {
    name = normalizeName(name);
    value = value == null ? "" : value;
    argumentType = argumentType == null ? MessageArgumentType.TEXT : argumentType;
  }

  public static MessageArg text(String name, Object value) {
    return new MessageArg(name, String.valueOf(value), MessageArgumentType.TEXT, null);
  }

  public static MessageArg mini(String name, String miniMessage) {
    return new MessageArg(name, miniMessage, MessageArgumentType.MINI_MESSAGE, null);
  }

  public static MessageArg localized(String name, LocalizedText localizedText) {
    return new MessageArg(name, "", MessageArgumentType.LOCALIZED, Objects.requireNonNull(localizedText));
  }

  String render(MessageService messageService, Language language) {
    return switch (argumentType) {
      case TEXT -> escapeMiniMessage(value);
      case MINI_MESSAGE -> value;
      case LOCALIZED -> messageService.renderMini(language, localizedText);
    };
  }

  private static String normalizeName(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Message placeholder name cannot be blank.");
    }
    return name.trim().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  private static String escapeMiniMessage(String value) {
    return value.replace("\\", "\\\\").replace("<", "\\<").replace(">", "\\>");
  }
}
