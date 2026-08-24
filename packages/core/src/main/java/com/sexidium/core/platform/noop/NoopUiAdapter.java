package com.sexidium.core.platform.noop;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;

public final class NoopUiAdapter implements UiAdapter {
  @Override
  public BossBarHandle createBossBar(
      LocalizedText localizedText,
      float progress,
      BossBarColor bossBarColor,
      BossBarOverlay bossBarOverlay
  ) {
    return new NoopBossBarHandle();
  }
}
