package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScreenFrameworkTest {

  private static final class TestPlayer implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "TestUser"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public com.sexidium.core.platform.WorldAdapter world() { return null; }
    @Override public com.sexidium.core.platform.model.WorldPosition position() { return null; }
    @Override public void teleport(com.sexidium.core.platform.model.WorldPosition targetPosition) { }
    @Override public com.sexidium.core.platform.model.GameModeType gameMode() { return com.sexidium.core.platform.model.GameModeType.SURVIVAL; }
    @Override public void setGameMode(com.sexidium.core.platform.model.GameModeType gameModeType) { }
    @Override public double health() { return 20; }
    @Override public double maxHealth() { return 20; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public com.sexidium.core.platform.InventoryAdapter inventory() { return null; }
    @Override public void playSound(com.sexidium.core.platform.model.SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(com.sexidium.core.platform.model.TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(com.sexidium.core.platform.model.WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }

  @Test
  @DisplayName("SidebarScreen populates separator, sidebar, content, and back button")
  void sidebarScreenPopulation() {
    TestPlayer player = new TestPlayer();
    MenuSupport support = new MenuSupport(null, null, null, null, null, null);

    SidebarScreen screen = new SidebarScreen(support, null) {
      @Override public String title() { return "Test Sidebar Screen"; }
      @Override protected void buildSidebar(MenuView view, PlayerAdapter player) {
        view.set(ChestLayout.sidebarSlot(0), MenuButton.of(ItemKey.minecraft("diamond"), "Category 1", ctx -> {}));
      }
      @Override protected void buildContent(MenuView view, PlayerAdapter player) {
        view.set(ChestLayout.contentSlot(0), MenuButton.of(ItemKey.minecraft("emerald"), "Item 1", ctx -> {}));
      }
    };

    MenuView view = screen.build(player);

    assertEquals(ChestLayout.ROWS, view.rows());
    assertEquals(54, view.size());

    // Separators filled
    for (int slot : ChestLayout.SEPARATOR_SLOTS) {
      assertNotNull(view.button(slot), "Separator at slot " + slot + " must be populated");
    }

    // Sidebar button at slot 0
    assertNotNull(view.button(0));
    assertEquals("Category 1", view.button(0).name());

    // Content button at slot 2
    assertNotNull(view.button(2));
    assertEquals("Item 1", view.button(2).name());

    // Back button at slot 47
    assertNotNull(view.button(ChestLayout.SLOT_BACK));
  }

  @Test
  @DisplayName("PaginatedScreen handles multi-page rendering and navigation slots")
  void paginatedScreenPagination() {
    TestPlayer player = new TestPlayer();
    MenuSupport support = new MenuSupport(null, null, null, null, null, null);

    List<String> items = new ArrayList<>();
    for (int i = 0; i < 80; i++) {
      items.add("Item #" + i);
    }

    PaginatedScreen<String> screen = new PaginatedScreen<>(support, null) {
      @Override public String title() { return "Paginated List"; }
      @Override protected List<String> items(PlayerAdapter player) { return items; }
      @Override protected MenuButton renderItem(PlayerAdapter player, String item, int index) {
        return MenuButton.of(ItemKey.minecraft("stone"), item, ctx -> {});
      }
      @Override protected MenuButton buildPrimaryButton(PlayerAdapter player) {
        return MenuButton.of(ItemKey.minecraft("gold_block"), "Create", ctx -> {});
      }
    };

    // Page 0 (items 0..27)
    MenuView page0 = screen.build(player);
    assertEquals("Item #0", page0.button(ChestLayout.contentSlot(0)).name());
    assertEquals("Item #27", page0.button(ChestLayout.contentSlot(27)).name());
    assertNotNull(page0.button(ChestLayout.SLOT_NEXT), "Next page button should be present");
    assertNotNull(page0.button(ChestLayout.SLOT_PAGE), "Page indicator should be present");
    assertNotNull(page0.button(ChestLayout.SLOT_PRIMARY), "Primary button at 53 should be present");

    // Advance to Page 1 (items 28..55)
    screen.setPage(player, 1);
    MenuView page1 = screen.build(player);
    assertEquals("Item #28", page1.button(ChestLayout.contentSlot(0)).name());
    assertEquals("Item #55", page1.button(ChestLayout.contentSlot(27)).name());
    assertNotNull(page1.button(ChestLayout.SLOT_PREV), "Previous page button should be present");
  }

  @Test
  @DisplayName("PickerScreen handles selection callback")
  void pickerScreenSelection() {
    TestPlayer player = new TestPlayer();
    MenuSupport support = new MenuSupport(null, null, null, null, null, null);

    AtomicInteger selectedIndex = new AtomicInteger(-1);
    List<String> choices = List.of("Option A", "Option B", "Option C");

    PickerScreen<String> picker = PickerScreen.of(support, null, "Pick an Option",
        choices,
        (p, choice) -> MenuButton.of(ItemKey.minecraft("paper"), choice, ctx -> selectedIndex.set(choices.indexOf(choice))),
        p -> {});

    MenuView view = picker.build(player);
    assertNotNull(view.button(ChestLayout.contentSlot(0)));
    assertEquals("Option A", view.button(ChestLayout.contentSlot(0)).name());

    view.button(ChestLayout.contentSlot(1)).onClick().accept(new MenuContext(player, MenuContext.ClickType.LEFT));
    assertEquals(1, selectedIndex.get());
  }

  @Test
  @DisplayName("ConfirmableScreen helper handles armed token checks")
  void confirmableScreenUtilities() {
    TestPlayer player = new TestPlayer();
    MenuSupport support = new MenuSupport(null, null, null, null, null, null);

    ConfirmableScreen confirmable = new ConfirmableScreen() {};
    String token = "delete:world-123";

    assertFalse(confirmable.isArmed(support, player.uniqueId(), token));

    support.confirmStep(new MenuContext(player, MenuContext.ClickType.LEFT), token);
    assertTrue(confirmable.isArmed(support, player.uniqueId(), token));

    confirmable.clearConfirm(support, player.uniqueId());
    assertFalse(confirmable.isArmed(support, player.uniqueId(), token));
  }

  private static void assertFalse(boolean condition) {
    org.junit.jupiter.api.Assertions.assertFalse(condition);
  }
}
