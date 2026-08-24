package com.sexidium.core.world.hotbar;

import com.sexidium.core.menu.UiItem;

/**
 * One inheritance-based lobby hotbar item — the hotbar twin of a chest {@link com.sexidium.core.menu.MenuButton}.
 *
 * <p>A concrete subclass declares its fixed hotbar {@link #slot()} and a stable {@link #id()} (the token
 * the platform tags onto the item and later hands to {@link HotbarController#handleClick} to route a
 * click), decides per-player whether it is {@link #visibleFor} the current context, builds its
 * {@link UiItem} icon, and handles a click in {@link #onClick}. Both a button and a hotbar item render
 * through the one platform materializer via their {@code UiItem}, which is what "unifies" the chest and
 * hotbar systems. To add an item: subclass this and register it in a {@link HotbarProfile}. See
 * {@code docs/ui-interaction-system.md}.</p>
 */
public abstract class HotbarItem {
  /** A stable, unique-within-its-profile routing token (also its PDC tag on the platform item). */
  public abstract String id();

  /** The fixed hotbar slot (0–8 for the main hotbar row) this item occupies when visible. */
  public abstract int slot();

  /** Whether this item shows for the given context right now (e.g. only when friend requests exist). */
  public boolean visibleFor(HotbarContext context) {
    return true;
  }

  /** Builds this item's current visual (name, lore, icon, badge count, head skin, model). */
  public abstract UiItem build(HotbarContext context);

  /** Runs this item's behaviour when the player interacts with it in the hotbar. */
  public abstract void onClick(HotbarContext context);
}
