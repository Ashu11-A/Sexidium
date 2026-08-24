package com.sexidium.core.menu;

import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.UUID;

/**
 * The platform-agnostic visual spec shared by every Sexidium UI item. A chest-menu {@link MenuButton}
 * and a lobby {@link com.sexidium.core.world.hotbar.HotbarItem} both render as one of these: it carries
 * only what an item <i>looks like</i> — icon material, stack amount (used as a badge count), MiniMessage
 * name, MiniMessage lore, an optional player-head owner (skull skin), and an optional custom
 * {@code item_model} id (pack-gated). Behaviour — click handling, hotbar slot, visibility — lives on the
 * specialization. A single platform materializer turns a {@code UiItem} into a real item stack, so item
 * rendering lives in exactly one place across the whole UI system.
 *
 * <p>{@code headOwner} textures a {@code player_head} icon with that player's real skin (Bedrock-legible
 * rosters); {@code model} wears a bespoke Sexidium texture for pack-loaded Java viewers and degrades to
 * the vanilla {@link #icon()} material otherwise. See {@code docs/ui-interaction-system.md}.</p>
 */
public record UiItem(ItemKey icon, int amount, String name, List<String> lore, UUID headOwner, String model) {
  public UiItem {
    amount = Math.max(1, amount);
    lore = lore == null ? List.of() : List.copyOf(lore);
    model = model == null || model.isBlank() ? null : model;
  }

  /** A plain icon with a name and lore (no head skin, no custom model). */
  public static UiItem of(ItemKey icon, String name, List<String> lore) {
    return new UiItem(icon, 1, name, lore, null, null);
  }

  /** A copy of this visual wearing the custom {@code item_model} {@code modelId} (null clears it). */
  public UiItem withModel(String modelId) {
    return new UiItem(icon, amount, name, lore, headOwner, modelId);
  }
}
