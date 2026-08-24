package com.sexidium.core.platform;

import com.sexidium.core.i18n.LocalizedText;

public interface MessageAdapter {
  void send(CommandSource commandSource, LocalizedText localizedText);

  void send(CommandSource commandSource, String miniMessage);

  void raw(CommandSource commandSource, LocalizedText localizedText);

  void raw(CommandSource commandSource, String miniMessage);

  void broadcast(LocalizedText localizedText);

  void broadcast(String miniMessage);
}
