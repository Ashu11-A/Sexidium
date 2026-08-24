package com.sexidium.paper.adapter.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sexidium.core.menu.MenuButton;
import com.sexidium.core.menu.MenuContext;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

/**
 * What a chest click becomes by the time a {@link MenuButton} handler reads it.
 *
 * <p>This is the Paper half of the arm-then-confirm gesture that guards Restore, Refresh and Delete.
 * The core half ({@code MenuSupport.isTap}) accepts only {@link MenuContext.ClickType#LEFT} and
 * {@link MenuContext.ClickType#RIGHT} as the second tap, so which Bukkit clicks
 * {@code PaperMenuAdapter.mapClickType} is willing to turn into those two is the entire safety
 * property: every other kind of touch has to arrive as {@link MenuContext.ClickType#OTHER} and re-arm
 * instead of firing the verb.</p>
 *
 * <p>The regression this exists to catch is {@code DOUBLE_CLICK}. Bukkit delivers a double-click as a
 * LEFT event <em>followed by</em> a DOUBLE_CLICK one; while DOUBLE_CLICK mapped to LEFT, one flick of
 * the mouse handed the gesture both of its halves and ran the destructive verb with no confirmation
 * step the player could see. The test drives real {@code onClick} dispatch rather than inspecting the
 * adapter's source, so re-spelling the {@code switch} — folding the case into another arm, replacing
 * it with an {@code if}, mapping through {@code ClickType.isLeftClick()} (which answers true for
 * DOUBLE_CLICK) — fails here just the same as re-adding the literal case would.</p>
 */
class PaperMenuClickTypeTest {
  private static final int SLOT = 13;

  /** The clicks a menu button is allowed to read as the deliberate tap the confirm tiles ask for. */
  private static final Set<MenuContext.ClickType> TAPS =
      EnumSet.of(MenuContext.ClickType.LEFT, MenuContext.ClickType.RIGHT);

  @Test
  void realMouseClicksKeepTheirOwnIdentity() {
    assertEquals(MenuContext.ClickType.LEFT, clickTypeSeenBy(ClickType.LEFT));
    assertEquals(MenuContext.ClickType.RIGHT, clickTypeSeenBy(ClickType.RIGHT));
    assertEquals(MenuContext.ClickType.SHIFT_LEFT, clickTypeSeenBy(ClickType.SHIFT_LEFT));
    assertEquals(MenuContext.ClickType.SHIFT_RIGHT, clickTypeSeenBy(ClickType.SHIFT_RIGHT));
    assertEquals(MenuContext.ClickType.MIDDLE, clickTypeSeenBy(ClickType.MIDDLE));
  }

  @Test
  void aDoubleClickIsNotALeftClick() {
    MenuContext.ClickType seen = clickTypeSeenBy(ClickType.DOUBLE_CLICK);

    assertNotEquals(MenuContext.ClickType.LEFT, seen,
        "DOUBLE_CLICK must not arrive as LEFT: Bukkit already sent the LEFT event that preceded it, "
            + "so a second LEFT would be both halves of the confirm gesture from one flick of the mouse");
    assertEquals(MenuContext.ClickType.OTHER, seen);
  }

  @Test
  void keyboardAndDragInputsFoldIntoOther() {
    // None of these is a click the player aimed at the tile — they are whatever key was pressed while
    // the cursor happened to rest on the slot. Over an armed "⚠ Tap again to delete" they must re-arm.
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.NUMBER_KEY));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.DROP));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.CONTROL_DROP));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.SWAP_OFFHAND));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.CREATIVE));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.UNKNOWN));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.WINDOW_BORDER_LEFT));
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(ClickType.WINDOW_BORDER_RIGHT));
  }

  /**
   * The exhaustive form of the property, so a Bukkit click kind added by a future API bump cannot
   * quietly become a tap: across EVERY {@link ClickType} the server can deliver, exactly LEFT and
   * RIGHT may reach the handler as something {@code MenuSupport.isTap} accepts.
   */
  @Test
  void onlyPlainLeftAndRightCanEverBeTheSecondTap() {
    Set<ClickType> tappable = EnumSet.noneOf(ClickType.class);
    for (ClickType click : ClickType.values()) {
      if (TAPS.contains(clickTypeSeenBy(click))) {
        tappable.add(click);
      }
    }

    assertEquals(EnumSet.of(ClickType.LEFT, ClickType.RIGHT), tappable,
        "only a plain left or right click may confirm a destructive verb; shift-clicks, double-clicks "
            + "and keyboard inputs must re-arm the tile instead");
  }

  @Test
  void anAbsentClickKindIsOther() {
    // Defensive: a null getClick() (seen from odd client packets and from other plugins re-firing the
    // event) must not fall through to a tap.
    assertEquals(MenuContext.ClickType.OTHER, clickTypeSeenBy(null));
  }

  @Test
  void theClickerIsTheOneHandedToTheHandler() {
    Player player = mock(Player.class);
    MenuContext context = dispatch(ClickType.LEFT, player);

    assertNotNull(context);
    assertSame(player, ((PaperPlayerAdapter) context.player()).handle());
  }

  /** Fires a real click on a Sexidium menu slot and reports how the button handler saw it. */
  private MenuContext.ClickType clickTypeSeenBy(ClickType click) {
    MenuContext context = dispatch(click, mock(Player.class));
    assertNotNull(context, "the button handler must have run for " + click);
    return context.clickType();
  }

  /**
   * Drives {@code PaperMenuAdapter.onClick} end to end: a chest whose holder is a
   * {@link SexidiumMenuHolder} carrying one button, clicked in the top inventory by {@code player}.
   * Returns the {@link MenuContext} the button's handler was actually given.
   */
  private MenuContext dispatch(ClickType click, Player player) {
    AtomicReference<MenuContext> seen = new AtomicReference<>();
    MenuButton button = MenuButton.of(
        ItemKey.minecraft("barrier"), "<red>Delete</red>", List.of(), seen::set);
    SexidiumMenuHolder holder = new SexidiumMenuHolder(null, Map.of(SLOT, button));

    Inventory top = mock(Inventory.class);
    when(top.getHolder()).thenReturn(holder);

    InventoryView view = mock(InventoryView.class);
    when(view.getTopInventory()).thenReturn(top);

    InventoryClickEvent event = mock(InventoryClickEvent.class);
    when(event.getView()).thenReturn(view);
    when(event.getClickedInventory()).thenReturn(top);
    when(event.getWhoClicked()).thenReturn(player);
    when(event.getSlot()).thenReturn(SLOT);
    when(event.getClick()).thenReturn(click);

    new PaperMenuAdapter().onClick(event);
    return seen.get();
  }
}
