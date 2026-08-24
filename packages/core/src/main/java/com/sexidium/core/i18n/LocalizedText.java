package com.sexidium.core.i18n;

import java.util.Arrays;
import java.util.List;

public record LocalizedText(MessageKey messageKey, List<MessageArg> arguments) {
  public LocalizedText {
    arguments = arguments == null ? List.of() : List.copyOf(arguments);
  }

  public static LocalizedText of(MessageKey messageKey, MessageArg... messageArguments) {
    return new LocalizedText(messageKey, Arrays.asList(messageArguments));
  }
}
