package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.WorldAdapter;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one predicate that decides whether a player sees the countdown once or twice.
 *
 * <h2>The bug this exists to stop coming back</h2>
 * {@code activeFor} is what the core stack hands the sidebar/title fallback as its suppression gate:
 * true means "the overlay has this player covered, stay quiet". For a POPUP it was answered by asking
 * BetterHud what the player was WEARING — and a popup is fired, not worn. BetterHud records a shown
 * popup in its iterator maps, never in the {@code getHudObjects()} map every "what are you wearing"
 * view is derived from, so that question was false for every popup that had ever been fired. The
 * fallback never stayed quiet, and the reset countdown drew a big white number through BetterHud with
 * a big red vanilla title straight through the middle of it, once a second for five seconds.
 *
 * <p>Everything below drives the real {@link BetterHudSurfaceHandle} against a ledger that behaves the
 * way BetterHud does. The previous guard on this behaviour read the class back as source text, which
 * is precisely why a permanently-false runtime predicate went unnoticed.</p>
 */
class BetterHudPopupGateTest {
  private static final Duration POPUP_LIFETIME = Duration.ofSeconds(45);

  private static final HudSurfaceSpec COUNTDOWN = HudSurfaceSpec.popup("resetcountdown")
      .anchor(HudAnchor.CENTER)
      .duration(POPUP_LIFETIME)
      .pulse("seconds", LocalizedText.of(MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER), 3.0d, 5.0d)
      .build();

  private static final HudSurfaceSpec WORN = HudSurfaceSpec.persistent("deathresets")
      .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
      .build();

  /** The bug, directly: a popup that has just been fired has to report itself as reaching the player. */
  @Test
  void aFiredPopupReportsItselfAsReachingThePlayer() {
    Fixture fixture = new Fixture();

    fixture.handle(COUNTDOWN).show(fixture.viewer);

    assertTrue(fixture.handle(COUNTDOWN).activeFor(fixture.viewer),
        "false here is what let the vanilla title fire a second number on top of this one");
  }

  /**
   * Re-showing every second — which is what the countdown does, because the title half has no memory —
   * updates the live popup instead of firing a second copy beside it, and still publishes the new
   * number.
   */
  @Test
  void reShowingALivePopupUpdatesItRatherThanFiringASecondOne() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);

    for (int second = 5; second > 0; second--) {
      handle.number("seconds", second);
      handle.show(fixture.viewer);
      fixture.claims.now += 1_000L;
    }

    assertEquals(1, fixture.claims.fired, "five shows of one popup are one popup");
    assertTrue(fixture.claims.pushes >= 5, "every second's number still has to reach the player");
  }

  /**
   * Once the popup's own duration is up, the surface stops claiming the player — which is what hands
   * them back to the fallback rather than leaving them with nothing on screen.
   */
  @Test
  void anExpiredPopupStopsClaimingThePlayer() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);
    handle.show(fixture.viewer);

    fixture.claims.now += POPUP_LIFETIME.toMillis() + 1L;

    assertFalse(handle.activeFor(fixture.viewer),
        "a popup nobody can see any more must not go on suppressing the surface that could");
  }

  /** Taking the countdown down explicitly is immediate: no waiting out the backstop duration. */
  @Test
  void hidingThePopupTakesItOffTheGateAtOnce() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);
    handle.show(fixture.viewer);

    handle.hide(fixture.viewer);

    assertFalse(handle.activeFor(fixture.viewer));
    assertTrue(fixture.claims.hidden.contains(COUNTDOWN.id()), "the popup is retracted, not left to age out");
  }

  /** A player with their HUD toggled off sees no popup, so the fallback has to draw for them. */
  @Test
  void aPlayerWithTheHudTurnedOffIsHandedToTheFallback() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);
    handle.show(fixture.viewer);
    fixture.claims.drawing = false;

    assertFalse(handle.activeFor(fixture.viewer),
        "BetterHud is drawing this player nothing; suppressing their title would leave them blind");
  }

  /** A persistent surface keeps being answered the other way — by what the player is wearing. */
  @Test
  void aPersistentSurfaceIsStillAnsweredByWhatThePlayerWears() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(WORN);

    handle.show(fixture.viewer);

    assertTrue(fixture.claims.worn.contains(WORN.id()), "a persistent surface is a claim, not a firing");
    assertEquals(0, fixture.claims.fired, "...and must never be recorded as a popup");
    assertTrue(handle.activeFor(fixture.viewer));
  }

  /**
   * A popup whose generated yml never loaded claims nobody.
   *
   * <p>The ledger records a popup because we FIRED it, not because BetterHud drew it — so a layout that
   * failed to parse leaves an entry naming an object that does not exist. Suppressing the fallback on
   * the strength of that entry would leave the countdown drawn by nobody at all, which is strictly
   * worse than the duplicate this gate exists to prevent. A worn surface has never had this hole: you
   * cannot wear an object that does not exist.</p>
   */
  @Test
  void aPopupWhoseObjectNeverLoadedHandsThePlayerToTheFallback() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);
    handle.show(fixture.viewer);
    fixture.claims.loaded = false;

    assertFalse(handle.activeFor(fixture.viewer),
        "BetterHud has no object by this name, so nothing is on screen — the title has to draw");
  }

  /** A player who was never shown the surface is never claimed by it, whatever the ledger says. */
  @Test
  void aPlayerWhoWasNeverShownTheSurfaceIsNeverClaimedByIt() {
    Fixture fixture = new Fixture();
    var handle = fixture.handle(COUNTDOWN);
    handle.show(fixture.viewer);

    assertFalse(handle.activeFor(new FakePlayer("Someone else")));
  }

  // ----- fixture --------------------------------------------------------------------------------

  private static final class Fixture {
    private final FakeClaims claims = new FakeClaims();
    private final FakePlayer viewer = new FakePlayer("Ana");
    private final Map<UUID, Player> natives = new HashMap<>();
    private final Map<String, BetterHudSurfaceHandle> handles = new HashMap<>();

    BetterHudSurfaceHandle handle(HudSurfaceSpec spec) {
      return handles.computeIfAbsent(spec.id(), id -> new BetterHudSurfaceHandle(
          spec, claims, new BetterHudRows(() -> null), this::nativePlayer, null));
    }

    private Player nativePlayer(PlayerAdapter adapter) {
      return natives.computeIfAbsent(adapter.uniqueId(), id -> {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
      });
    }
  }

  /**
   * The claim ledger, behaving the way BetterHud's does: a persistent surface is worn and can be asked
   * about; a popup is fired and lives on a deadline. The clock is a field so a test can move it.
   */
  private static final class FakeClaims implements SurfaceClaims {
    private final Set<String> worn = new HashSet<>();
    private final Map<UUID, Map<String, Long>> popups = new HashMap<>();
    private final Set<String> hidden = new HashSet<>();
    private long now = 1_000L;
    private int fired;
    private int pushes;
    private boolean drawing = true;
    /** Whether the generated yml actually parsed, i.e. whether BetterHud knows the object at all. */
    private boolean loaded = true;

    @Override public boolean available() { return true; }

    @Override public boolean exists(String id) { return loaded; }

    @Override
    public boolean showing(Player player, String id) {
      return drawing && worn.contains(id);
    }

    @Override
    public boolean showingPopup(Player player, String id) {
      Long deadline = popups.getOrDefault(player.getUniqueId(), Map.of()).get(id);
      return drawing && deadline != null && deadline > now;
    }

    @Override
    public void claim(Player player, String id, long popupDurationMillis) {
      if (popupDurationMillis <= 0L) {
        worn.add(id);
        return;
      }
      fired++;
      popups.computeIfAbsent(player.getUniqueId(), key -> new HashMap<>())
          .put(id, now + popupDurationMillis);
    }

    @Override
    public void release(UUID playerId, String id) {
      worn.remove(id);
      Map<String, Long> live = popups.get(playerId);
      if (live != null) {
        live.remove(id);
      }
    }

    @Override
    public void hidePopup(Player player, String id) {
      hidden.add(id);
    }

    @Override
    public void pushVariables(Player player, Map<String, String> values) {
      pushes++;
    }

    @Override
    public void clearVariables(Player player, Collection<String> keys) {
    }
  }

  /** Only the two members the handle touches matter; everything else is inert. */
  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final String name;

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
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }
}
