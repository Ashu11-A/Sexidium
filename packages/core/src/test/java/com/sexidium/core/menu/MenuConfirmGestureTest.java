package com.sexidium.core.menu;

import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.PlayerAdapter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gesture that guards every destructive tile in the chest GUI: tap once to arm, tap again to run.
 *
 * <p>What is pinned here is everything that must NOT run it. This is a chest, and a chest receives far
 * more than clicks — a shift-click (the reflex gesture for "move this item"), a hotbar number key, a
 * {@code Q} drop, an {@code F} swap, the second half of a double-click. Every one of those arrives as
 * an {@code InventoryClickEvent} on whatever slot the cursor is over, and on the Backups screen the
 * slots under the cursor are Restore, Refresh and Delete. Each of them destroys a world's worth of
 * work, and the copy screen's own lore says the contents "cannot be got back".</p>
 */
class MenuConfirmGestureTest {

  private static final String TOKEN = "deletebackup:copy-1";

  private final MenuSupport support = new MenuSupport(null, null, null, null, null, null);
  private final Clicker player = new Clicker();

  private boolean tap(MenuContext.ClickType clickType) {
    return support.confirmStep(new MenuContext(player, clickType), TOKEN);
  }

  @Test
  @DisplayName("two taps run it: one arms, the second confirms")
  void twoTapsConfirm() {
    assertFalse(tap(MenuContext.ClickType.LEFT), "the first tap only arms — that is the whole point");
    assertTrue(tap(MenuContext.ClickType.LEFT), "the second tap on the same token is the confirm");
  }

  @Test
  @DisplayName("a right-click is a tap too: Geyser sends LEFT, but a Java player may use either")
  void rightClicksAreTapsAsWell() {
    assertFalse(tap(MenuContext.ClickType.RIGHT));
    assertTrue(tap(MenuContext.ClickType.RIGHT));
  }

  @Test
  @DisplayName("a shift-click never confirms — not on the first one, and not on the second either")
  void shiftClickIsNotAShortcut() {
    // It used to confirm INSTANTLY, documented as a Java power-user shortcut. In a chest, shift-click
    // is the reflex gesture for moving an item, and on the copy screen it lands on Restore (swaps the
    // live world) and Refresh (overwrites the copy with no way back) with no confirmation at all.
    assertFalse(tap(MenuContext.ClickType.SHIFT_LEFT), "a shift-click must not be a shortcut past the"
        + " confirmation: it is the gesture a player makes to move an item, not to delete a world");
    assertFalse(tap(MenuContext.ClickType.SHIFT_LEFT), "and it is not the tap the tile asks for, so it"
        + " cannot be the second half of the gesture either");
    assertFalse(tap(MenuContext.ClickType.SHIFT_RIGHT));
  }

  @Test
  @DisplayName("a number key, a drop or a swap over an ARMED tile does not confirm it")
  void otherClicksDoNotConfirmAnArmedTile() {
    // PaperMenuAdapter folds NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND, CREATIVE, DOUBLE_CLICK and
    // UNKNOWN into OTHER, and dispatches the button handler for every one of them. The owner taps
    // "Delete this copy" once, deliberately, and then — with the cursor still over slot 16 — presses Q
    // to drop something, or 1 to grab a hotbar item. That keypress used to delete the backup.
    assertFalse(tap(MenuContext.ClickType.LEFT), "armed by a deliberate tap");
    assertFalse(tap(MenuContext.ClickType.OTHER), "a keypress that happened to land on the slot is not"
        + " the second tap the tile promised, and this one is not recoverable");
    assertFalse(tap(MenuContext.ClickType.MIDDLE));
    assertTrue(tap(MenuContext.ClickType.LEFT), "the real second tap still confirms afterwards");
  }

  @Test
  @DisplayName("a number key, a drop or a swap over an IDLE tile does not ARM it either")
  void otherClicksDoNotArmAnIdleTile() {
    // The other half of the same defect. The owner sweeps the cursor along the row of a copy's screen
    // and presses 1 or Q over slot 12: that keypress used to arm `refresh:<id>`, and the tile redrew
    // itself as "⧉ Tap again to refresh". The single deliberate tap they then made on Refresh --
    // believing they were arming it -- was the confirming tap of a verb whose lore says the contents
    // "cannot be got back".
    assertFalse(tap(MenuContext.ClickType.OTHER), "a keypress the owner never aimed at the tile");
    assertFalse(tap(MenuContext.ClickType.LEFT),
        "so the first deliberate tap is still the ARMING one, not the confirming one");
    assertTrue(tap(MenuContext.ClickType.LEFT), "and it takes a second real tap to run the verb");
  }

  @Test
  @DisplayName("an armed tile goes cold: the confirming tap has to land inside the window")
  void theArmedWindowExpires() {
    long[] now = {1_000L};
    support.clock = () -> now[0];

    assertFalse(tap(MenuContext.ClickType.LEFT), "armed at t=0");
    now[0] += MenuSupport.CONFIRM_WINDOW_MS + 1;
    assertFalse(tap(MenuContext.ClickType.LEFT), "past the window the tile is cold again: a tap that"
        + " lands on a screen left open for a minute must arm, not delete");
    assertTrue(tap(MenuContext.ClickType.LEFT), "and that tap re-armed it, so the next one confirms");
  }

  @Test
  @DisplayName("a stray keypress over an armed tile does not push the window out")
  void otherClicksDoNotExtendTheWindow() {
    long[] now = {1_000L};
    support.clock = () -> now[0];

    assertFalse(tap(MenuContext.ClickType.LEFT), "armed at t=0");
    now[0] += 3_000L;
    assertFalse(tap(MenuContext.ClickType.OTHER), "a keypress that landed on the slot");
    now[0] += 3_000L;
    // Six seconds after the arming tap. The stray event used to re-arm with a FRESH stamp, so a hand
    // resting on the hotbar keys kept a "⚠ Tap again to delete" alive indefinitely.
    assertFalse(tap(MenuContext.ClickType.LEFT),
        "the window is measured from the arming tap, and nothing but a tap may restart it");
  }

  @Test
  @DisplayName("arming a different verb does not confirm the one that was already armed")
  void armingAnotherVerbDoesNotConfirmThisOne() {
    // There is exactly one pending-confirm slot per player, so this is what keeps a half-armed Delete
    // on the screen behind from being fired by a tap meant for Restore.
    assertFalse(tap(MenuContext.ClickType.LEFT));
    assertFalse(support.confirmStep(new MenuContext(player, MenuContext.ClickType.LEFT), "restore:copy-1"));
    assertFalse(tap(MenuContext.ClickType.LEFT), "the other verb took the slot; this one re-arms");
  }

  @Test
  @DisplayName("the per-player menu state is concurrent, like every other map written from two regions")
  void perPlayerStateIsConcurrent() {
    // These maps are written from the player's own region thread (a click) and from the callbacks of a
    // routed delete/restore, which answer on the global region — the same split that already made
    // liveScreens concurrent. A plain HashMap resized under two threads loses entries with no error.
    String source = read(Path.of("src/main/java/com/sexidium/core/menu/MenuSupport.java"));
    assertFalse(source.contains("new HashMap<>()"),
        "every per-player map in MenuSupport is touched from more than one thread; the armed-confirm"
            + " one decides whether a world is deleted");
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (IOException fromRepoRoot) {
      // The canonical path, for a run started at the repo root rather than the module root.
      try {
        return Files.readString(Path.of("packages/core").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /** The clicker. Only the uuid is ever read: the confirm state is keyed by it. */
  private static final class Clicker implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Clicker"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return false; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }
}
