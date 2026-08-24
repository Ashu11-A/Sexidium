package com.sexidium.paper.adapter.ui;

import com.sexidium.paper.adapter.util.PaperConverters;
import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.PopupType;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.ui.betterhud.BetterHudDriver;
import org.bukkit.entity.Player;

public final class PaperUiAdapter implements UiAdapter {
  private final Server server;
  private final PaperMessageAdapter messageAdapter;
  private final BetterHudDriver betterHudDriver;

  public PaperUiAdapter(PaperMessageAdapter messageAdapter) {
    this.server = Bukkit.getServer();
    this.messageAdapter = messageAdapter;
    this.betterHudDriver = new BetterHudDriver(
        messageAdapter::service, PaperUiAdapter::nativePlayer, this::logDriver);
  }

  @Override
  public BossBarHandle createBossBar(
      LocalizedText localizedText,
      float progress,
      BossBarColor bossBarColor,
      BossBarOverlay bossBarOverlay
  ) {
    MessageService messageService = messageAdapter.service();
    BossBar bossBar = BossBar.bossBar(
        Component.empty(),
        Math.max(0.0f, Math.min(1.0f, progress)),
        PaperConverters.toNative(bossBarColor),
        PaperConverters.toNative(bossBarOverlay)
    );
    return new PaperBossBarHandle(server, messageService, bossBar, localizedText);
  }

  @Override
  public void showPopup(PlayerAdapter playerAdapter, PopupType popupType, LocalizedText localizedText) {
    if (playerAdapter == null) {
      return;
    }
    // Native surfaces only. There WAS a BetterHud popup path here, but it probed for methods
    // (showPopup/sendPopup/popup) that exist in no version of the API, so it returned false on every
    // call and fell through to exactly this code. Beyond being dead, it could not have worked in
    // principle: a BetterHud popup renders its own configured layout and has no way to accept an
    // arbitrary rendered string, and Sexidium ships no popup layouts. Titles and the action bar say
    // what these need to say, on Java and Bedrock alike.
    String rendered = render(playerAdapter, localizedText);
    if (popupType == PopupType.COUNTDOWN) {
      // Re-sent every second by whatever is counting, so it carries no fade: see TitleSpecs.countdown.
      playerAdapter.showTitle(com.sexidium.core.platform.model.TitleSpecs.countdown(rendered));
      return;
    }
    if (popupType == PopupType.WIN || popupType == PopupType.ELIMINATION || popupType == PopupType.OBJECTIVE) {
      playerAdapter.showTitle(new com.sexidium.core.platform.model.TitleSpec(rendered, "", 150L, 1200L, 350L));
      return;
    }
    playerAdapter.sendActionBar(rendered);
  }

  @Override
  public HudPanelHandle createPanel(LocalizedText title) {
    // Always the per-player scoreboard sidebar (right side of the screen): it is what actually renders
    // our dynamic per-viewer lines. The old BetterHud panel path only forwarded lines to this same
    // (suppressed) sidebar while showing BetterHud's static placeholder, so it hid all live content.
    // Declared surfaces (hudDriver) are the only thing Sexidium drives through BetterHud. Carry
    // the LocalizedText so the title localizes per viewer, the same way each line does.
    return new PaperScoreboardPanelHandle(messageAdapter, title);
  }

  /**
   * The platform HUD driver, which on Paper means BetterHud or nothing.
   *
   * <p>Handed straight out rather than wrapped: core stacks it behind its own sidebar renderer, so a
   * driver that reports no capabilities — no plugin, not opted in, shaders that do not match the
   * client — costs a caller nothing but a fallback.</p>
   */
  @Override
  public HudDriver hudDriver() {
    return betterHudDriver;
  }

  /**
   * A numeric column in the tab player list — vanilla scoreboards, not BetterHud, so unlike the corner
   * overlay this one works everywhere including Bedrock.
   */
  @Override
  public com.sexidium.core.platform.TabListHandle createTabList(String columnId) {
    return new PaperTabListCounter(columnId);
  }

  /** The concrete BetterHud driver, for the plugin's own startup wiring (assets, reconcile, probe). */
  public BetterHudDriver betterHud() {
    return betterHudDriver;
  }

  /**
   * The Bukkit player behind a core adapter, or null for anything that is not one of ours.
   *
   * <p>Static so it can be handed to the driver as a function at construction time, before {@code this}
   * is fully built.</p>
   */
  private static Player nativePlayer(PlayerAdapter playerAdapter) {
    return playerAdapter instanceof PaperPlayerAdapter paper ? paper.handle() : null;
  }

  private void logDriver(String message) {
    Bukkit.getLogger().warning("[sexidium] " + message);
  }

  private String render(PlayerAdapter playerAdapter, LocalizedText localizedText) {
    if (localizedText == null || localizedText.messageKey() == null) {
      return "";
    }
    try {
      MessageService service = messageAdapter.service();
      return playerAdapter == null
          ? service.renderMini(Language.EN, localizedText)
          : service.renderMini(playerAdapter, localizedText);
    } catch (IllegalStateException exception) {
      return localizedText.messageKey().path();
    }
  }
}
