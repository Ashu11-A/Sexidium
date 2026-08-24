package com.sexidium.paper.adapter.ui;

import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.paper.adapter.util.PaperConverters;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperUiAdapterTest {

  private static final HudSurfaceSpec SPEC = HudSurfaceSpec.persistent("deathresets")
      .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
      .build();

  /**
   * The dev-server reality, and the default on most servers: BetterHud is not installed, so the driver
   * can draw nothing. It must SAY so — an empty capability set — because that answer is what makes the
   * core stack hand every surface to the sidebar renderer instead.
   *
   * <p>Every probe the driver makes has to fail softly here: there is no Bukkit server at all in a unit
   * test, so a plugin lookup, a class lookup and a version lookup all have to degrade rather than
   * throw.</p>
   */
  @Test
  void hudDriver_withoutBetterHud_reportsNoCapabilities() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    var driver = adapter.hudDriver();

    assertNotNull(driver, "a caller must never have to null-check the driver");
    assertTrue(driver.capabilities().isEmpty(),
        "a driver with no backing plugin must claim nothing, or the stack skips the fallback");
    assertFalse(driver.supports(HudCapability.TEXT));
    assertFalse(driver.supports(SPEC));
  }

  /**
   * Opening a surface the driver cannot draw must yield the shared NOOP, not a live handle.
   *
   * <p>The distinction decides a player's screen: a handle that reports itself active is a handle a
   * challenge will suppress the sidebar for, leaving the player with nothing at all. Every mutator has
   * to stay harmless too — callers push on every HUD refresh without checking.</p>
   */
  @Test
  void open_withoutBetterHud_isTheSharedNoopAndSafeToDrive() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    HudSurfaceHandle handle = adapter.hudDriver().open(SPEC);

    assertSame(HudSurfaceHandle.NOOP, handle,
        "an undrawable surface must never hand back a live handle");
    assertFalse(handle.activeFor(null),
        "the per-player answer must be no as well, or a challenge suppresses a sidebar it never replaced");
    assertDoesNotThrow(() -> {
      handle.text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION));
      handle.number("duration", 12);
      handle.flag("duration", true);
      handle.progress("duration", 0.5);
      handle.show(null);
      handle.refresh();
      handle.hide(null);
      handle.close();
    });
  }

  /**
   * The reconciler is what keeps BetterHud's bundled demo hud out of the lobby, so it has to be
   * constructible on a server that has no BetterHud at all — the majority of them. A plugin that failed
   * to enable there would be trading one broken HUD for a broken server.
   *
   * <p>Not started here: {@code start()} schedules on the live server, which a unit test has none of.</p>
   */
  @Test
  void reconciler_isBuildableWithoutBetterHudInstalled() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);
    adapter.betterHud().enabled(true);

    assertNotNull(adapter.betterHud().reconciler(null, 20L, false));
  }

  /**
   * {@code hud.betterhud.enabled: false} has to make the driver inert, not merely unpreferred.
   *
   * <p>This is the switch that keeps Sexidium off BetterHud on a Minecraft version whose shaders it has
   * no matching overlay for. Off means no capabilities, which is what routes every player down the
   * sidebar path Bedrock players were already taking.</p>
   */
  @Test
  void driver_claimsNothingWhileSwitchedOff() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);
    adapter.betterHud().declare(SPEC);
    adapter.betterHud().enabled(false);

    assertTrue(adapter.hudDriver().capabilities().isEmpty());
    assertSame(HudSurfaceHandle.NOOP, adapter.hudDriver().open(SPEC),
        "a disabled driver must never hand back a live surface handle");
  }

  @Test
  void createPanel_isAlwaysTheSidebar_whichIsWhereReadableStateHasToLive() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    assertInstanceOf(PaperScoreboardPanelHandle.class, adapter.createPanel(null),
        "the panel is the Bedrock-safe surface; the overlay is only ever additive to it");
  }

  @Test
  void createBossBar_clampsNegativeProgressToZero() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    MessageService service = mock(MessageService.class);
    when(messageAdapter.service()).thenReturn(service);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    var handle = adapter.createBossBar(null, -0.5f, BossBarColor.RED, BossBarOverlay.PROGRESS);
    assertNotNull(handle);
    handle.progress(0.0f);
  }

  @Test
  void createBossBar_clampsExcessiveProgressToOne() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    MessageService service = mock(MessageService.class);
    when(messageAdapter.service()).thenReturn(service);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    var handle = adapter.createBossBar(null, 5.0f, BossBarColor.BLUE, BossBarOverlay.PROGRESS);
    assertNotNull(handle);
    handle.progress(1.0f);
  }

  @Test
  void createBossBar_acceptsMidRangeProgress() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    MessageService service = mock(MessageService.class);
    when(messageAdapter.service()).thenReturn(service);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    var handle = adapter.createBossBar(null, 0.5f, BossBarColor.GREEN, BossBarOverlay.NOTCHED_6);
    assertNotNull(handle);
  }

  @Test
  void createBossBar_acceptsAllColors() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    MessageService service = mock(MessageService.class);
    when(messageAdapter.service()).thenReturn(service);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    for (BossBarColor color : BossBarColor.values()) {
      var handle = adapter.createBossBar(null, 0.5f, color, BossBarOverlay.PROGRESS);
      assertNotNull(handle);
    }
  }

  @Test
  void createBossBar_acceptsAllOverlays() {
    PaperMessageAdapter messageAdapter = mock(PaperMessageAdapter.class);
    MessageService service = mock(MessageService.class);
    when(messageAdapter.service()).thenReturn(service);
    PaperUiAdapter adapter = new PaperUiAdapter(messageAdapter);

    for (BossBarOverlay overlay : BossBarOverlay.values()) {
      var handle = adapter.createBossBar(null, 0.5f, BossBarColor.WHITE, overlay);
      assertNotNull(handle);
    }
  }
}
