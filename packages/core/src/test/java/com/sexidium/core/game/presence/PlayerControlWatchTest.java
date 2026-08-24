package com.sexidium.core.game.presence;

import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rules a player's control state has to obey.
 *
 * <p>No scheduler anywhere, on purpose: this state is DERIVED, not scheduled, and that is the property
 * that makes it fail safe. A test that needed a timer would be evidence the design had drifted back
 * towards the persistent-flag approach the class exists to avoid.</p>
 */
class PlayerControlWatchTest {

  private final AtomicLong now = new AtomicLong(1_000L);
  private final PlayerControlWatch watch = new PlayerControlWatch(now::get);

  @Test
  @DisplayName("a player is marked at the threshold, not before, and only the first pass reports the edge")
  void marksAtThreshold_andReportsOnlyTheEdge() {
    FakePlayer player = new FakePlayer(3_000L);

    assertFalse(watch.sample(player, 5_000L), "3s of silence is not yet 5s");
    assertFalse(watch.downed(player.uniqueId()));

    player.idle = 5_000L;
    assertTrue(watch.sample(player, 5_000L), "crossing the threshold is the edge the caller acts on");
    assertTrue(watch.downed(player.uniqueId()));

    player.idle = 9_000L;
    assertFalse(watch.sample(player, 5_000L), "still down is not a new edge; the sweep must not repeat");
    assertTrue(watch.downed(player.uniqueId()));
  }

  @Test
  @DisplayName("an unreportable platform never marks anybody")
  void idleOfMinusOne_neverMarks() {
    // The single most important assertion here. A platform without the seam answers -1, and reading that
    // as "infinitely idle" would make every player on that server permanently unkillable — a far worse
    // failure than the one this class prevents.
    FakePlayer player = new FakePlayer(-1L);

    assertFalse(watch.sample(player, 1L));
    assertFalse(watch.downed(player.uniqueId()));
    assertFalse(watch.anyDowned());
  }

  @Test
  @DisplayName("input lifts an idle mark at once")
  void inputLiftsIdle() {
    FakePlayer player = new FakePlayer(9_000L);
    watch.sample(player, 5_000L);

    watch.sawInput(player.uniqueId());

    assertFalse(watch.downed(player.uniqueId()), "acting is proof somebody is at the controls");
  }

  @Test
  @DisplayName("input never lifts a disconnect")
  void inputNeverLiftsDisconnect() {
    UUID playerId = UUID.randomUUID();
    watch.markDisconnected(playerId);

    watch.sawInput(playerId);

    assertTrue(watch.downed(playerId), "only coming back lifts a disconnect");
    assertEquals(PlayerControlWatch.Loss.DISCONNECTED, watch.reason(playerId));
  }

  @Test
  @DisplayName("sampling cannot downgrade a disconnect into an idle mark")
  void samplingLeavesADisconnectAlone() {
    FakePlayer player = new FakePlayer(0L);
    watch.markDisconnected(player.uniqueId());

    assertFalse(watch.sample(player, 5_000L), "an offline player's idle time is not ours to read");
    assertEquals(PlayerControlWatch.Loss.DISCONNECTED, watch.reason(player.uniqueId()));
  }

  @Test
  @DisplayName("the recovery tail keeps a rescued player safe for a moment, then lapses")
  void recoveryTail() {
    FakePlayer player = new FakePlayer(9_000L);
    watch.sample(player, 5_000L);

    watch.sawInput(player.uniqueId());

    assertTrue(watch.downed(player.uniqueId(), 2_000L), "still covered inside the tail");
    assertFalse(watch.downed(player.uniqueId(), 0L), "a tail of zero means the protection lifts at once");

    now.addAndGet(2_000L);
    assertFalse(watch.downed(player.uniqueId(), 2_000L), "and the tail lapses on its own");
  }

  @Test
  @DisplayName("clear lifts and starts the tail; forget leaves nothing at all")
  void clearVersusForget() {
    UUID playerId = UUID.randomUUID();

    watch.markDisconnected(playerId);
    watch.clear(playerId);
    assertTrue(watch.downed(playerId, 2_000L), "a reconnecting player gets the same moment to react");

    watch.markDisconnected(playerId);
    watch.forget(playerId);
    assertFalse(watch.downed(playerId, 2_000L), "a released slot leaves no mark and no tail");
    assertFalse(watch.anyDowned());
  }

  @Test
  @DisplayName("anyDowned is the cheap gate the platform's target guard asks first")
  void anyDowned() {
    FakePlayer player = new FakePlayer(9_000L);
    assertFalse(watch.anyDowned());

    watch.sample(player, 5_000L);
    assertTrue(watch.anyDowned());
    assertEquals(1, watch.downedPlayers().size());

    watch.sawInput(player.uniqueId());
    assertFalse(watch.anyDowned());
  }

  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private long idle;

    private FakePlayer(long idle) {
      this.idle = idle;
    }

    @Override public long idleMillis() { return idle; }
    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Tester"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public int foodLevel() { return 20; }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() {}
    @Override public void clearPotionEffects() {}
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public void setHealth(double health) {}
    @Override public void setFoodLevel(int foodLevel) {}
  }
}
