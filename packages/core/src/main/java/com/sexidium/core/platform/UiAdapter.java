package com.sexidium.core.platform;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.model.PopupType;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.TitleSpecs;

public interface UiAdapter {
  BossBarHandle createBossBar(
      LocalizedText localizedText,
      float progress,
      BossBarColor bossBarColor,
      BossBarOverlay bossBarOverlay
  );

  default void showPopup(PlayerAdapter playerAdapter, PopupType popupType, LocalizedText localizedText) {
    if (playerAdapter == null || localizedText == null || localizedText.messageKey() == null) {
      return;
    }
    String fallback = localizedText.messageKey().path();
    if (popupType == PopupType.COUNTDOWN) {
      playerAdapter.showTitle(TitleSpecs.countdown(fallback));
      return;
    }
    if (popupType == PopupType.WIN || popupType == PopupType.ELIMINATION || popupType == PopupType.OBJECTIVE) {
      playerAdapter.showTitle(new TitleSpec(fallback, "", 150L, 1200L, 350L));
      return;
    }
    playerAdapter.sendActionBar(fallback);
  }

  default HudPanelHandle createPanel(LocalizedText title) {
    return HudPanelHandle.NOOP;
  }

  /**
   * The platform's driver for free-floating on-screen surfaces, when it has one.
   *
   * <p>Never asked directly by a consumer — {@code AbstractGame} stacks this behind the core sidebar
   * driver, so a spec the platform will not draw (no backing plugin, a Bedrock viewer, a shader
   * mismatch) still reaches the player on the scoreboard. The default is {@link HudDriver#NOOP},
   * which is the honest answer for a platform with no such surface at all.</p>
   */
  default HudDriver hudDriver() {
    return HudDriver.NOOP;
  }

  /**
   * A numeric column beside every name in the tab player list, when the platform has one.
   *
   * <p>Like {@link #hudDriver}, never load-bearing: the default is {@link TabListHandle#NOOP}, and
   * the column draws a bare number with no label (see {@link TabListHandle}), so anything a player must
   * be able to interpret has to exist on the sidebar as well.</p>
   *
   * @param columnId a stable id naming the column, so two features cannot fight over the one slot
   */
  default TabListHandle createTabList(String columnId) {
    return TabListHandle.NOOP;
  }
}
