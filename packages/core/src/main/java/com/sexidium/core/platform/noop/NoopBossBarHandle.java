package com.sexidium.core.platform.noop;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.PlayerAdapter;

public final class NoopBossBarHandle implements BossBarHandle {
  @Override
  public void title(LocalizedText localizedText) {
  }

  @Override
  public void progress(float progress) {
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
  }

  @Override
  public void close() {
  }
}
