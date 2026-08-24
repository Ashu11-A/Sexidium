package com.sexidium.core.game;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards how a mode declares the way a player ARRIVES. The rule that matters is that a policy is applied
 * by the framework and is applied LAST — a player's game mode used to be decided by whoever touched them
 * last on the way in, which let somebody walk back into a world they had permanently lost, in survival.
 */
class EntryPolicyTest {

  @Test
  void theDefaultIsExactlyWhatEveryModeDidBefore() {
    // A mode that says nothing must behave as it always has, or adding this API changes every mode.
    EntryPolicy policy = EntryPolicy.SURVIVAL;
    assertEquals(GameModeType.SURVIVAL, policy.gameMode());
    assertTrue(policy.heal());
    assertTrue(policy.feed());
    assertTrue(policy.clearInventory());
    assertTrue(policy.playable());
    assertNull(policy.notice());
  }

  @Test
  void aSpectatorPolicyTouchesNothingAboutTheBody() {
    // Nothing about a world you have permanently lost should look playable — including being healed and
    // fed on the way in, which would read as being handed the world back.
    EntryPolicy policy = EntryPolicy.spectator(LocalizedText.of(MessageKey.EXPERIENCE_HARDCORE_SPECTATING));
    assertEquals(GameModeType.SPECTATOR, policy.gameMode());
    assertFalse(policy.heal());
    assertFalse(policy.feed());
    assertFalse(policy.clearInventory(), "a lost world must not eat the inventory it is holding");
    assertFalse(policy.playable());
    assertNotNull(policy.notice());
  }

  @Test
  void applyingAPolicySetsTheModeAndOnlyWhatItAsksFor() {
    FakePlayer player = new FakePlayer();
    player.health = 3.0;
    player.food = 2;

    EntryPolicy.SURVIVAL.applyTo(player);
    assertEquals(GameModeType.SURVIVAL, player.gameMode);
    assertEquals(20.0, player.health, 1e-9, "survival heals");
    assertEquals(20, player.food);

    FakePlayer spectator = new FakePlayer();
    spectator.health = 3.0;
    spectator.food = 2;
    EntryPolicy.spectator(null).applyTo(spectator);
    assertEquals(GameModeType.SPECTATOR, spectator.gameMode);
    assertEquals(3.0, spectator.health, 1e-9, "spectating must not quietly heal you");
    assertEquals(2, spectator.food);
  }

  @Test
  void applyingIsIdempotentBecauseTheFrameworkAppliesItTwice() {
    // It runs on entry AND again after any saved snapshot is restored over it, so applying it repeatedly
    // has to be safe.
    FakePlayer player = new FakePlayer();
    EntryPolicy policy = EntryPolicy.spectator(null);
    policy.applyTo(player);
    policy.applyTo(player);
    policy.applyTo(player);
    assertEquals(GameModeType.SPECTATOR, player.gameMode);
    assertEquals(3, player.modeWrites, "each apply is a set, never a toggle");
  }

  @Test
  void anOfflineOrMissingPlayerIsSimplyIgnored() {
    FakePlayer offline = new FakePlayer();
    offline.online = false;
    EntryPolicy.spectator(null).applyTo(offline);
    assertEquals(0, offline.modeWrites, "never touch a player who is not there");
    EntryPolicy.SURVIVAL.applyTo(null);
  }

  @Test
  void everyGameModeIsExpressible() {
    // The API has to cover what a mode might actually want, not just the two cases that prompted it.
    assertEquals(GameModeType.CREATIVE, EntryPolicy.creative(null).gameMode());
    assertEquals(GameModeType.ADVENTURE, EntryPolicy.adventure(null).gameMode());
    assertTrue(EntryPolicy.creative(null).playable());
    assertTrue(EntryPolicy.adventure(null).playable());
    // A creative/build world must not empty the pockets of whoever walks in.
    assertFalse(EntryPolicy.creative(null).clearInventory());
    assertTrue(EntryPolicy.adventure(null).clearInventory());
  }

  @Test
  void aNullGameModeFallsBackToSurvivalRatherThanFailing() {
    assertEquals(GameModeType.SURVIVAL, new EntryPolicy(null, true, true, true, null).gameMode());
  }

  @Test
  void aSpectatorPolicyKeepsBeingApplied() {
    // The bug that made this necessary: entry-time enforcement is only as good as the next thing to
    // write a game mode, and in a lost hardcore world something always did.
    FakePlayer player = new FakePlayer();
    EntryPolicy policy = EntryPolicy.spectator(null);
    assertTrue(policy.enforced());

    assertTrue(policy.enforce(player), "the player was in survival in a world they may only watch");
    assertEquals(GameModeType.SPECTATOR, player.gameMode);
    assertFalse(policy.enforce(player), "…and once it is right, it writes nothing at all");
    assertEquals(1, player.modeWrites, "a timer must not re-issue a game mode it already agrees with");

    // Something hands the world back mid-session. The next pass takes it away again.
    player.setGameMode(GameModeType.SURVIVAL);
    assertTrue(policy.enforce(player));
    assertEquals(GameModeType.SPECTATOR, player.gameMode);
  }

  @Test
  void anOrdinaryPolicyNeverFightsOverAPlayersGameMode() {
    // Survival is the default for every mode, so enforcing it would mean yanking a builder out of
    // creative in any world at any moment. Only a mode that ASKS to be enforced is.
    FakePlayer player = new FakePlayer();
    player.setGameMode(GameModeType.CREATIVE);
    assertFalse(EntryPolicy.SURVIVAL.enforced());
    assertFalse(EntryPolicy.SURVIVAL.enforce(player));
    assertEquals(GameModeType.CREATIVE, player.gameMode);

    // …unless it is asked for explicitly.
    assertTrue(EntryPolicy.SURVIVAL.alwaysEnforced().enforce(player));
    assertEquals(GameModeType.SURVIVAL, player.gameMode);
  }

  @Test
  void theHardcoreViewIsOnlySentWhenTheWorldActuallyChanges() {
    // Re-telling a client what world it is in costs it the world it has loaded, so it may only be said
    // when a world change is about to hand it a new one. A "teleport" inside the same world is not one.
    FakePlayer player = new FakePlayer();
    player.world = new FakeWorld("experiences/ashu/run_ab12");
    EntryPolicy hardcore = EntryPolicy.SURVIVAL.withHardcoreView(true);

    hardcore.prepareArrival(player, new WorldPosition("experiences/ashu/run_ab12", 0, 64, 0, 0f, 0f));
    assertTrue(player.hardcoreViews.isEmpty(), "already in that world — nothing to re-tell");

    hardcore.prepareArrival(player, new WorldPosition("lobby", 0, 64, 0, 0f, 0f));
    assertEquals(List.of(Boolean.TRUE), player.hardcoreViews);
  }

  @Test
  void leavingForTheLobbyAlwaysGivesUpTheHardcoreHearts() {
    // The other half of the same bug: hardcore hearts followed a player out of a lost world and stayed
    // with them in the lobby, because nothing ever told the client otherwise.
    FakePlayer player = new FakePlayer();
    player.world = new FakeWorld("experiences/ashu/run_ab12");

    EntryPolicy.leaveHardcoreWorld(player, new WorldPosition("lobby", 0, 64, 0, 0f, 0f));
    assertEquals(List.of(Boolean.FALSE), player.hardcoreViews);

    // Nowhere to go is not a world change: say nothing rather than something the client cannot act on.
    FakePlayer stranded = new FakePlayer();
    stranded.world = new FakeWorld("lobby");
    EntryPolicy.leaveHardcoreWorld(stranded, null);
    EntryPolicy.leaveHardcoreWorld(stranded, new WorldPosition("lobby", 0, 64, 0, 0f, 0f));
    assertTrue(stranded.hardcoreViews.isEmpty());
  }

  @Test
  void aNoticeCanBeAttachedToAnyPolicy() {
    LocalizedText notice = LocalizedText.of(MessageKey.EXPERIENCE_HARDCORE_SPECTATING);
    EntryPolicy withNotice = EntryPolicy.SURVIVAL.withNotice(notice);
    assertEquals(notice, withNotice.notice());
    assertEquals(GameModeType.SURVIVAL, withNotice.gameMode(), "…without changing anything else");
    assertTrue(withNotice.heal());
  }

  /** A world that knows only its name — which is all the hardcore-view guard compares. */
  private record FakeWorld(String name) implements com.sexidium.core.platform.WorldAdapter {
    @Override public WorldPosition spawnPosition() { return null; }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition target, com.sexidium.core.platform.model.ItemStackData item) { }
    @Override public void playSound(WorldPosition target, com.sexidium.core.platform.model.SoundKey sound, float volume, float pitch) { }
    @Override public void setBorder(com.sexidium.core.platform.model.WorldBorderSpec border) { }
    @Override public void resetBorder() { }
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }

  /** Records what was done to it, so a policy's exact effects are observable. */
  private static final class FakePlayer implements PlayerAdapter {
    private final UUID uniqueId = UUID.randomUUID();
    private final List<Boolean> hardcoreViews = new java.util.ArrayList<>();
    private GameModeType gameMode = GameModeType.SURVIVAL;
    private double health = 20.0;
    private int food = 20;
    private boolean online = true;
    private int modeWrites;
    private com.sexidium.core.platform.WorldAdapter world;

    @Override public UUID uniqueId() { return uniqueId; }
    @Override public String name() { return "tester"; }
    @Override public boolean online() { return online; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return world; }
    @Override public void setHardcoreView(boolean hardcore) { hardcoreViews.add(hardcore); }
    @Override public com.sexidium.core.platform.model.WorldPosition position() { return null; }
    @Override public void teleport(com.sexidium.core.platform.model.WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return gameMode; }

    @Override
    public void setGameMode(GameModeType gameModeType) {
      this.gameMode = gameModeType;
      this.modeWrites++;
    }

    @Override public double health() { return health; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double value) { this.health = value; }
    @Override public int foodLevel() { return food; }
    @Override public void setFoodLevel(int value) { this.food = value; }
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(com.sexidium.core.platform.model.SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(com.sexidium.core.platform.model.TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(com.sexidium.core.platform.model.WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
    @Override public java.util.Locale locale() { return java.util.Locale.ROOT; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
  }
}
