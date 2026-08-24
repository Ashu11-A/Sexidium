package com.sexidium.core.game.hud.surface;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.game.hud.HudHost;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.BossBarColor;
import com.sexidium.core.platform.model.BossBarOverlay;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The modularity claim, held to account: one declaration, rendered wherever each individual player can
 * actually see it, and never twice for the same player.
 *
 * <p>This is the behaviour that replaces a hand-written duplicate readout per consumer. Exactly one
 * challenge ever paid that cost before, which is a fair measure of how well it scaled — so these tests
 * are less about the happy path than about the split staying honest as the platform driver comes and
 * goes underneath it.</p>
 */
class HudDriverStackTest {
  private static final HudSurfaceSpec SPEC = HudSurfaceSpec.persistent("deathresets")
      .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
      .build();

  /** A popup, which takes the other route entirely: a vanilla title rather than a sidebar row. */
  private static final HudSurfaceSpec POPUP = HudSurfaceSpec.popup("resetcountdown")
      .anchor(HudAnchor.CENTER)
      .pulse("seconds", LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER), 3.0d, 5.0d)
      .build();

  /**
   * The ordinary case on a server with no overlay plugin: the platform half draws for nobody, so the
   * sidebar half draws for everybody.
   */
  @Test
  void withNoPlatformDriver_theSidebarCarriesTheWholeReadout() {
    Fixture fixture = new Fixture(HudDriver.NOOP);
    PlayerAdapter player = fixture.player("Ana");

    HudSurfaceHandle surface = fixture.stack.open(SPEC);
    surface.show(player);
    surface.text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION));
    fixture.render();

    assertTrue(fixture.drewFor(player), "the readout has to land somewhere");
    assertFalse(surface.activeFor(player),
        "activeFor answers for the PLATFORM half only — saying yes here would suppress the very "
            + "sidebar the fallback just rendered onto");
  }

  /**
   * A mixed match, which is the case that made a per-player answer necessary in the first place: the
   * platform driver reaches one player and not the other, and each gets the readout exactly once.
   */
  @Test
  void inAMixedMatch_eachPlayerIsDrawnToExactlyOnce() {
    ReachingDriver platform = new ReachingDriver();
    Fixture fixture = new Fixture(platform);
    PlayerAdapter java = fixture.player("Java");
    PlayerAdapter bedrock = fixture.player("Bedrock");
    platform.reaches.add(java);

    HudSurfaceHandle surface = fixture.stack.open(SPEC);
    surface.show(java);
    surface.show(bedrock);
    fixture.render();

    assertTrue(surface.activeFor(java), "the platform half reaches this one");
    assertFalse(fixture.drewFor(java), "...so the sidebar must NOT also draw it — that is a duplicate");

    assertFalse(surface.activeFor(bedrock), "the platform half does not reach this one");
    assertTrue(fixture.drewFor(bedrock), "...so the sidebar has to, or they see nothing at all");
  }

  /**
   * The platform driver going away mid-session — an operator switching it off, its plugin failing a
   * reload — must move players onto the sidebar without anything being re-opened.
   */
  @Test
  void whenThePlatformHalfStopsDrawing_theSidebarPicksTheSamePlayerUp() {
    ReachingDriver platform = new ReachingDriver();
    Fixture fixture = new Fixture(platform);
    PlayerAdapter player = fixture.player("Ana");
    platform.reaches.add(player);

    HudSurfaceHandle surface = fixture.stack.open(SPEC);
    surface.show(player);
    fixture.render();
    assertFalse(fixture.drewFor(player));

    platform.reaches.clear();
    fixture.render();

    assertTrue(fixture.drewFor(player),
        "nothing re-opened the surface; the fallback simply started answering for this player");
  }

  /**
   * A driver that cannot draw every element of a spec must not be handed a partial one. Three rows in
   * the corner and a fourth silently missing is worse than four on the sidebar.
   */
  @Test
  void aSpecThePlatformCannotFullyDrawGoesEntirelyToTheFallback() {
    ReachingDriver platform = new ReachingDriver();
    Fixture fixture = new Fixture(platform);
    PlayerAdapter player = fixture.player("Ana");
    platform.reaches.add(player);

    HudSurfaceSpec withIcon = HudSurfaceSpec.persistent("icons")
        .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
        .icon(ItemKey.minecraft("clock"))
        .build();

    HudSurfaceHandle surface = fixture.stack.open(withIcon);
    surface.show(player);
    fixture.render();

    assertFalse(surface.activeFor(player),
        "the platform driver claims no ICON capability, so it must never have been offered this spec");
    assertTrue(fixture.drewFor(player), "the fallback renders what it can and skips the icon");
  }

  /**
   * The same split, for a POPUP — the kind that produced two countdowns on one screen.
   *
   * <p>A popup's fallback is not a sidebar row but a vanilla title, drawn in the same place the overlay
   * draws its own big centred number. So a popup whose platform half reaches a player and whose
   * fallback fires anyway is not a duplicated readout tucked away in a corner; it is two numbers on top
   * of each other in the middle of the screen. This is the case the stack's tests never covered, and
   * the case where the gate was broken: the platform handle answered "not reaching this player" for
   * every popup it had just fired, because it asked the overlay what the player was WEARING and a
   * popup is fired rather than worn.</p>
   */
  @Test
  void aPopupThePlatformIsAlreadyShowingMustNotAlsoFireAsATitle() {
    ReachingDriver platform = new ReachingDriver();
    Fixture fixture = new Fixture(platform);
    FakePlayer java = fixture.player("Java");
    FakePlayer bedrock = fixture.player("Bedrock");
    platform.reaches.add(java);

    HudSurfaceHandle surface = fixture.stack.open(POPUP);
    surface.number("seconds", 5);
    surface.show(java);
    surface.show(bedrock);

    assertTrue(java.titles.isEmpty(),
        "the overlay is already drawing this player a centred number; a title puts a second one on top"
            + " of it");
    assertEquals(1, bedrock.titles.size(),
        "the overlay does not reach this one, so the title is the only countdown they get");
  }

  /**
   * A popup re-shown every second — which is what a countdown does, because a title has no memory —
   * must keep reaching a player the platform is not drawing to, and must keep NOT reaching one it is.
   */
  @Test
  void aPopupReShownEverySecondStaysOnExactlyOneSurfacePerPlayer() {
    ReachingDriver platform = new ReachingDriver();
    Fixture fixture = new Fixture(platform);
    FakePlayer java = fixture.player("Java");
    FakePlayer bedrock = fixture.player("Bedrock");
    platform.reaches.add(java);

    HudSurfaceHandle surface = fixture.stack.open(POPUP);
    for (int second = 5; second > 0; second--) {
      surface.number("seconds", second);
      surface.show(java);
      surface.show(bedrock);
    }

    assertTrue(java.titles.isEmpty(), "five seconds of re-showing must not leak one title");
    assertEquals(5, bedrock.titles.size(), "...and must re-send one every second to the other player");
  }

  /** Closing the surface has to take the sidebar contributor down with it, not merely stop pushing. */
  @Test
  void closingTheSurfaceRemovesTheSidebarRows() {
    Fixture fixture = new Fixture(HudDriver.NOOP);
    PlayerAdapter player = fixture.player("Ana");

    HudSurfaceHandle surface = fixture.stack.open(SPEC);
    surface.show(player);
    fixture.render();
    assertTrue(fixture.drewFor(player));

    surface.close();
    fixture.render();

    assertFalse(fixture.drewFor(player), "a closed surface must leave nothing behind on the panel");
  }

  // ----- fakes ----------------------------------------------------------------------------------

  /** A platform driver that draws for an explicit set of players and nobody else. */
  private static final class ReachingDriver implements HudDriver {
    private final Set<PlayerAdapter> reaches = new HashSet<>();

    @Override
    public Set<HudCapability> capabilities() {
      return Set.of(HudCapability.TEXT, HudCapability.BAR, HudCapability.POPUP);
    }

    @Override
    public HudSurfaceHandle open(HudSurfaceSpec spec) {
      return new HudSurfaceHandle() {
        @Override public boolean active() { return true; }
        @Override public boolean activeFor(PlayerAdapter player) { return reaches.contains(player); }
        @Override public void text(String key, LocalizedText value) { }
        @Override public void number(String key, double value) { }
        @Override public void flag(String key, boolean value) { }
        @Override public void progress(String key, double value) { }
        @Override public void show(PlayerAdapter player) { }
        @Override public void hide(PlayerAdapter player) { }
        @Override public void close() { }
      };
    }
  }

  /** A host with a live {@link GameHud} whose panel writes are readable. */
  private static final class Fixture implements HudHost {
    private final RecordingUi ui = new RecordingUi();
    private final GameContext gameContext =
        new GameContext(new RecordingServer(ui), new NoopKitAdapter(), RankAwardPort.noop());
    private final List<PlayerAdapter> online = new ArrayList<>();
    private final GameHud hud = new GameHud(this, LocalizedText.of(MessageKey.GAME_HUD_TITLE));
    private final HudDriverStack stack;

    Fixture(HudDriver platform) {
      this.stack = new HudDriverStack(platform, () -> hud, () -> ui);
    }

    FakePlayer player(String name) {
      FakePlayer player = new FakePlayer(name);
      online.add(player);
      return player;
    }

    void render() {
      ui.panels.forEach(RecordingPanel::clear);
      hud.render();
    }

    /** Whether the sidebar drew any row for this player on the last render pass. */
    boolean drewFor(PlayerAdapter player) {
      return ui.panelFor(player) != null && !ui.panelFor(player).lines.isEmpty();
    }

    @Override public GameContext gameContext() { return gameContext; }
    @Override public List<PlayerAdapter> online() { return online; }
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

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final String name;
    /** Every title this player was sent — the popup fallback's only visible effect. */
    private final List<TitleSpec> titles = new ArrayList<>();

    FakePlayer(String name) {
      this.name = name;
    }

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String plain) { }
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
    @Override public void showTitle(TitleSpec titleSpec) { titles.add(titleSpec); }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }

  private static final class RecordingUi implements UiAdapter {
    private final List<RecordingPanel> panels = new ArrayList<>();
    private final Map<PlayerAdapter, RecordingPanel> byViewer = new HashMap<>();

    RecordingPanel panelFor(PlayerAdapter player) {
      return byViewer.get(player);
    }

    @Override
    public com.sexidium.core.platform.BossBarHandle createBossBar(
        LocalizedText text, float progress, BossBarColor color, BossBarOverlay overlay) {
      throw new UnsupportedOperationException("this test never asks for a boss bar");
    }

    @Override
    public HudPanelHandle createPanel(LocalizedText title) {
      RecordingPanel panel = new RecordingPanel(this);
      panels.add(panel);
      return panel;
    }
  }

  private static final class RecordingPanel implements HudPanelHandle {
    private final RecordingUi owner;
    private final List<String> lines = new ArrayList<>();

    RecordingPanel(RecordingUi owner) {
      this.owner = owner;
    }

    void clear() {
      lines.clear();
    }

    @Override
    public void line(int index, LocalizedText localizedText) {
      lines.add(localizedText == null ? "" : localizedText.messageKey().path());
    }

    @Override public void removeLine(int index) { }
    @Override public void show(PlayerAdapter playerAdapter) { owner.byViewer.put(playerAdapter, this); }
    @Override public void hide(PlayerAdapter playerAdapter) { }
    @Override public void close() { }
  }
}
