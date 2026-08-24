package com.sexidium.core.platform;

import com.sexidium.core.i18n.LocalizedText;

public interface BossBarHandle {
  void title(LocalizedText localizedText);

  void progress(float progress);

  void show(PlayerAdapter playerAdapter);

  void hide(PlayerAdapter playerAdapter);

  void close();
}
