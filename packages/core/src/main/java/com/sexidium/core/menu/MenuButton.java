package com.sexidium.core.menu;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One clickable slot in a {@link MenuView}. Display text is raw MiniMessage so menus can render rich,
 * dynamic content (mode names, friend names, counts) without a message key per button.
 *
 * <p>{@code headOwner} is the player a {@code player_head} icon represents. When set, the platform
 * {@link com.sexidium.core.platform.MenuAdapter} textures the skull with that player's real skin
 * (via SkinsRestorer / the live profile on Paper) instead of leaving it the default Steve head — this
 * is what makes the friends / party / picker rosters legible, especially for mobile players.</p>
 *
 * <p>{@code model} is an optional custom {@code item_model} id (e.g. {@code sexidium:menu/lobby} from
 * {@link MenuArt#model}). When set <i>and</i> the viewer loaded the Sexidium resource pack, the icon
 * wears that bespoke texture; otherwise the adapter falls back to the vanilla {@link #icon()}
 * material. This is the per-button half of the "Nexo-style" art layer and degrades gracefully on
 * Bedrock / no-pack clients.</p>
 */
public record MenuButton(ItemKey icon, int amount, String name, List<String> lore,
    Consumer<MenuContext> onClick, UUID headOwner, String model, List<String> nameFrames) {
  public MenuButton {
    amount = Math.max(1, amount);
    lore = lore == null ? List.of() : List.copyOf(lore);
    model = model == null || model.isBlank() ? null : model;
    nameFrames = nameFrames == null || nameFrames.isEmpty() ? null : List.copyOf(nameFrames);
  }

  /** Convenience constructor for the common non-animated button (no {@link #nameFrames}). */
  public MenuButton(ItemKey icon, int amount, String name, List<String> lore,
      Consumer<MenuContext> onClick, UUID headOwner, String model) {
    this(icon, amount, name, lore, onClick, headOwner, model, null);
  }

  public static MenuButton of(ItemKey icon, String name, Consumer<MenuContext> onClick) {
    return new MenuButton(icon, 1, name, List.of(), onClick, null, null);
  }

  public static MenuButton of(ItemKey icon, String name, List<String> lore, Consumer<MenuContext> onClick) {
    return new MenuButton(icon, 1, name, lore, onClick, null, null);
  }

  /** A non-interactive decorative button (filler / label). */
  public static MenuButton label(ItemKey icon, String name, List<String> lore) {
    return new MenuButton(icon, 1, name, lore, null, null, null);
  }

  /** A clickable player head textured with {@code owner}'s skin. */
  public static MenuButton head(UUID owner, String name, List<String> lore, Consumer<MenuContext> onClick) {
    return new MenuButton(ItemKey.minecraft("player_head"), 1, name, lore, onClick, owner, null);
  }

  /** A non-interactive player head textured with {@code owner}'s skin (roster rows that are not clickable). */
  public static MenuButton headLabel(UUID owner, String name, List<String> lore) {
    return new MenuButton(ItemKey.minecraft("player_head"), 1, name, lore, null, owner, null);
  }

  /** A copy of this button wearing the custom {@code item_model} {@code modelId} (null clears it). */
  public MenuButton withModel(String modelId) {
    return new MenuButton(icon, amount, name, lore, onClick, headOwner, modelId, nameFrames);
  }

  /**
   * A copy of this button carrying animation {@code frames} for its display name — a list of MiniMessage
   * name strings the platform cycles through in place while the menu is open (e.g. a moving per-mode
   * colour gradient). Ignored by renderers/tests that don't animate; the static {@link #name()} is always
   * a valid standalone label.
   */
  public MenuButton withNameFrames(List<String> frames) {
    return new MenuButton(icon, amount, name, lore, onClick, headOwner, model, frames);
  }

  /**
   * This button's platform-agnostic {@link UiItem} visual — the render half a chest button shares with a
   * lobby hotbar item, so both materialize through the one platform factory. Drops the click handler,
   * which is chest-specific (the hotbar routes clicks by id, not by a carried {@link MenuContext}).
   */
  public UiItem visual() {
    return new UiItem(icon, amount, name, lore, headOwner, model);
  }
}
