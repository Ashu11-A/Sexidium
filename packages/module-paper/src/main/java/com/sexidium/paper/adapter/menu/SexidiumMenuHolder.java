package com.sexidium.paper.adapter.menu;

import com.sexidium.core.menu.MenuView;
import com.sexidium.core.menu.MenuButton;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Marks a Bukkit chest inventory as a Sexidium menu and carries the backing {@link MenuView} so the
 * adapter's click listener can route slot clicks to the view's button handlers. Being a custom
 * {@link InventoryHolder} lets the listener cheaply and reliably distinguish Sexidium menus from any
 * other inventory the player might open.
 */
public final class SexidiumMenuHolder implements InventoryHolder {
  private final MenuView view;
  private final Map<Integer, MenuButton> buttons;
  private Inventory inventory;

  public SexidiumMenuHolder(MenuView view) {
    this(view, null);
  }

  public SexidiumMenuHolder(MenuView view, Map<Integer, MenuButton> buttons) {
    this.view = view;
    this.buttons = buttons == null ? null : new LinkedHashMap<>(buttons);
  }

  public MenuView view() {
    return view;
  }

  public MenuButton button(int slot) {
    return buttons == null ? view.button(slot) : buttons.get(slot);
  }

  void attach(Inventory inventory) {
    this.inventory = inventory;
  }

  @Override
  public Inventory getInventory() {
    if (inventory == null) {
      // Defensive: Bukkit can ask for the inventory before attach() runs (e.g. mocked tests). A
      // tiny placeholder keeps the contract non-null; the real menu inventory is set in attach().
      inventory = org.bukkit.Bukkit.createInventory(this, InventoryType.CHEST);
    }
    return inventory;
  }
}
