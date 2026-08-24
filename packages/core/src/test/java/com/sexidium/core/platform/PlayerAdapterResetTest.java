package com.sexidium.core.platform;

import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the contract that a player leaving a match has their <em>entire</em> on-screen state reset:
 * {@link PlayerAdapter#resetStatuses()} must clear the inventory, effects, health scaling, body
 * scale, health, food and game mode AND the graphical overlays (boss bars, title/action bar,
 * compass).
 */
class PlayerAdapterResetTest {

  @Test
  void resetStatuses_clearsStateAndScreenOverlays() {
    RecordingPlayer player = new RecordingPlayer();
    player.resetStatuses();

    // Player state
    assertTrue(player.calls.contains("clearInventory"));
    assertTrue(player.calls.contains("clearPotionEffects"));
    assertTrue(player.calls.contains("resetHealthScale"));
    assertTrue(player.calls.contains("resetScale"), "body scale must be reset on reset");
    assertTrue(player.calls.contains("setHealth"));
    assertTrue(player.calls.contains("setFoodLevel"));
    assertTrue(player.calls.contains("setGameMode"));
    // Screen overlays
    assertTrue(player.calls.contains("clearBossBars"), "boss bars must be cleared on reset");
    assertTrue(player.calls.contains("clearTitle"), "titles/action bar must be cleared on reset");
    assertTrue(player.calls.contains("resetCompass"), "compass must be reset on reset");
    // Damage immunity is not a potion effect and is the one thing a death→respawn→world-swap leaves
    // behind that reads as "this player takes no damage" — so resetStatuses must clear it too.
    assertTrue(player.calls.contains("clearDamageImmunity"),
        "damage immunity must be cleared on reset or the player reads as invulnerable");
    // Including the BLANKET invulnerability flag, which is the only one that never expires by itself.
    // It is handed out for short grace windows and taken back by a scheduled task, so every path that
    // loses that schedule — a refused teleport, a disconnect mid-move, a match ending inside the window
    // and cancelling its own tasks — used to leave a player permanently unkillable. Because the flag is
    // persistent, that then followed them into the lobby and into every later match, and the only report
    // anyone could give was "players are not taking damage".
    assertTrue(player.calls.contains("setInvulnerable"),
        "resetting a player must clear blanket invulnerability, not just the expiring immunities");
  }

  /**
   * Resetting a player is not the same as taking them off a death screen, and a world reset needs both:
   * {@code resetStatuses} must not quietly respawn somebody, and {@code forceRespawn} must be safe to
   * call on a platform that cannot do it.
   */
  @Test
  void forceRespawn_isSeparateFromResetStatuses_andHarmlessByDefault() {
    RecordingPlayer player = new RecordingPlayer();

    player.resetStatuses();
    assertFalse(player.calls.contains("forceRespawn"), "a reset must not decide to respawn anybody");

    assertDoesNotThrow(player::forceRespawn, "the default must be a no-op, not a failure");
  }

  private static final class RecordingPlayer implements PlayerAdapter {
    final Set<String> calls = new LinkedHashSet<>();

    @Override public void clearInventory() { calls.add("clearInventory"); }
    @Override public void clearPotionEffects() { calls.add("clearPotionEffects"); }
    // Records AND delegates, so the default's own obligations are exercised rather than stubbed away —
    // this is the method whose contract grew to include the blanket invulnerability flag.
    @Override public void clearDamageImmunity() {
      calls.add("clearDamageImmunity");
      PlayerAdapter.super.clearDamageImmunity();
    }

    @Override public void setInvulnerable(boolean invulnerable) { calls.add("setInvulnerable"); }
    @Override public void resetHealthScale() { calls.add("resetHealthScale"); }
    @Override public void resetScale() { calls.add("resetScale"); }
    @Override public void setHealth(double health) { calls.add("setHealth"); }
    @Override public void setFoodLevel(int foodLevel) { calls.add("setFoodLevel"); }
    @Override public void setGameMode(GameModeType gameModeType) { calls.add("setGameMode"); }
    @Override public void clearBossBars() { calls.add("clearBossBars"); }
    @Override public void clearTitle() { calls.add("clearTitle"); }
    @Override public void resetCompass() { calls.add("resetCompass"); }

    @Override public UUID uniqueId() { return new UUID(0L, 0L); }
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
  }
}
