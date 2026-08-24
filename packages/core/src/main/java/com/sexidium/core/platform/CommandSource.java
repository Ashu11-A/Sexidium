package com.sexidium.core.platform;

import java.util.Locale;

public interface CommandSource {
  String name();

  Locale locale();

  boolean hasPermission(String permission);

  void sendMiniMessage(String miniMessage);

  void sendPlainMessage(String message);

  default boolean playerSource() {
    return false;
  }
}
