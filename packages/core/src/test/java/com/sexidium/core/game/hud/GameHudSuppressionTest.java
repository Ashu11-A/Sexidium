package com.sexidium.core.game.hud;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That suppressing the scoreboard panel is a decision made about ONE player, not about the match.
 *
 * <p>The mode this exists for is Death Resets, whose readout lives in BetterHud's top-left corner. That
 * corner is a boss bar underneath, which Geyser does not pass on — so in a match with a Java player and
 * a Bedrock player, exactly one of them can see it. The old all-or-nothing flag meant the Java player's
 * overlay took the sidebar away from the Bedrock player too, leaving them with a genuinely empty screen
 * and no way to find out how many days their world had lasted.
 */
class GameHudSuppressionTest {

  @Test
  void suppressedPlayerLosesTheirPanelWhileTheOneBesideThemKeepsIt() {
    FakePlayer reached = new FakePlayer("Java");
    FakePlayer unreached = new FakePlayer("Bedrock");
    FakeHost host = new FakeHost(reached, unreached);
    GameHud hud = new GameHud(host, null);
    hud.register(context -> context.line("Days: 3"));

    hud.suppressPanel(player -> player == reached);
    hud.render();

    assertTrue(hud.panelSuppressed(reached), "the overlay reaches them, so the sidebar is redundant");
    assertFalse(hud.panelSuppressed(unreached), "nothing reaches them, so the sidebar is all they have");
    assertEquals(1, host.ui.panels.size(),
        "one panel, for the player who is keeping their sidebar — never one for the suppressed player");
    assertTrue(reached.sidebarCleared,
        "the board they walked in from the lobby wearing has to be taken down, not just ours");
    assertFalse(unreached.sidebarCleared, "and the player who is keeping their sidebar must keep it");
  }

  /**
   * The un-suppressed player is not merely left alone — they get the full panel, because the information
   * the corner overlay would have carried has to reach them somehow.
   */
  @Test
  void theUnreachedPlayerStillGetsTheLines() {
    FakePlayer reached = new FakePlayer("Java");
    FakePlayer unreached = new FakePlayer("Bedrock");
    FakeHost host = new FakeHost(reached, unreached);
    GameHud hud = new GameHud(host, null);
    hud.register(context -> {
      context.line("Days: 3");
      context.line("Resets: 1");
    });

    hud.suppressPanel(player -> player == reached);
    hud.render();

    assertEquals(1, host.ui.panels.size(), "exactly one panel: the player who cannot see the overlay");
    assertEquals(List.of("Days: 3", "Resets: 1"), host.ui.panels.get(0).lines,
        "and it carries the same readout the corner would have");
  }

  /**
   * The overlay comes and goes during a match — BetterHud is reloaded, its last Java viewer leaves — so
   * the same player can be suppressed, un-suppressed and suppressed again. The second suppression has to
   * strip their board again: by then it is the panel we drew while they were un-suppressed, and skipping
   * the strip on the grounds that we already did it once leaves that panel on screen for good.
   */
  @Test
  void suppressionStripsTheBoardAgainAfterTheOverlayFlickers() {
    FakePlayer player = new FakePlayer("Java");
    FakeHost host = new FakeHost(player);
    GameHud hud = new GameHud(host, null);
    hud.register(context -> context.line("Days: 3"));

    hud.suppressPanel(candidate -> true);
    hud.render();
    assertTrue(player.sidebarCleared);

    player.sidebarCleared = false;
    hud.suppressPanel(candidate -> false);
    hud.render();
    assertFalse(hud.panelSuppressed(player), "the overlay stopped drawing, so the sidebar is back");

    hud.suppressPanel(candidate -> true);
    hud.render();
    assertTrue(player.sidebarCleared, "and when it starts again the board it replaces must come down");
  }

  /** A null predicate is "suppress nobody" — the state every mode but Death Resets is in. */
  @Test
  void byDefaultNobodyIsSuppressed() {
    FakePlayer player = new FakePlayer("Java");
    FakeHost host = new FakeHost(player);
    GameHud hud = new GameHud(host, null);

    assertFalse(hud.panelSuppressed(player));
    assertFalse(hud.panelSuppressed(null), "and a null player is nobody, not everybody");

    hud.suppressPanel(null);
    hud.render();

    assertFalse(hud.panelSuppressed(player));
    assertEquals(1, host.ui.panels.size());
  }

  // ----- fakes ----------------------------------------------------------------------------------

  private static final class FakeHost implements HudHost {
    private final RecordingUi ui = new RecordingUi();
    private final List<PlayerAdapter> online;
    private final GameContext gameContext;

    FakeHost(PlayerAdapter... players) {
      this.online = List.of(players);
      this.gameContext = new GameContext(new RecordingServer(ui), new NoopKitAdapter(), RankAwardPort.noop());
    }

    @Override
    public GameContext gameContext() {
      return gameContext;
    }

    @Override
    public List<PlayerAdapter> online() {
      return online;
    }
  }

  /** Delegates everything to the shared test adapter except the one seam this test is about. */
  private record RecordingServer(RecordingUi recordingUi) implements ServerAdapter {
    private static final TestServerAdapter DELEGATE = new TestServerAdapter();

    @Override public String serverName() { return DELEGATE.serverName(); }
    @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
    @Override public Path dataDirectory() { return DELEGATE.dataDirectory(); }
    @Override public ConfigurationAdapter configuration() { return DELEGATE.configuration(); }
    @Override public LoggerAdapter logger() { return DELEGATE.logger(); }
    @Override public ResourceAdapter resources() { return DELEGATE.resources(); }
    @Override public SchedulerAdapter scheduler() { return DELEGATE.scheduler(); }
    @Override public UiAdapter ui() { return recordingUi; }
    @Override public MessageAdapter messages() { return DELEGATE.messages(); }
    @Override public EventDispatcherAdapter events() { return DELEGATE.events(); }
    @Override public CommandDispatcherAdapter commands() { return DELEGATE.commands(); }
    @Override public WorldLeaseService worlds() { return DELEGATE.worlds(); }
    @Override public CommandSource console() { return null; }
    @Override public Collection<PlayerAdapter> onlinePlayers() { return List.of(); }
    @Override public Optional<PlayerAdapter> player(UUID id) { return Optional.empty(); }
    @Override public Optional<PlayerAdapter> playerExact(String name) { return Optional.empty(); }
  }

  private static final class RecordingUi implements UiAdapter {
    private final List<RecordingPanel> panels = new ArrayList<>();

    @Override
    public com.sexidium.core.platform.BossBarHandle createBossBar(
        LocalizedText text, float progress, BossBarColor color, BossBarOverlay overlay) {
      throw new UnsupportedOperationException("this test never asks for a boss bar");
    }

    @Override
    public HudPanelHandle createPanel(LocalizedText title) {
      RecordingPanel panel = new RecordingPanel();
      panels.add(panel);
      return panel;
    }
  }

  private static final class RecordingPanel implements HudPanelHandle {
    private final List<String> lines = new ArrayList<>();

    @Override
    public void line(int index, LocalizedText localizedText) {
      while (lines.size() <= index) {
        lines.add(null);
      }
      // HudContext#line wraps the caller's MiniMessage in GAME_HUD_LINE with a single "text" argument;
      // reading that back is the closest a headless test gets to what a viewer sees.
      lines.set(index, localizedText == null || localizedText.arguments().isEmpty()
          ? null
          : localizedText.arguments().get(0).value());
    }

    @Override public void removeLine(int index) { }
    @Override public void show(PlayerAdapter playerAdapter) { }
    @Override public void hide(PlayerAdapter playerAdapter) { }
    @Override public void close() { }
  }

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final String name;
    private boolean sidebarCleared;

    FakePlayer(String name) {
      this.name = name;
    }

    @Override public void clearSidebar() { sidebarCleared = true; }

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }
}
