package com.sexidium.paper.adapter.menu;

import com.google.common.collect.ImmutableMultimap;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The single Paper materializer that turns a platform-agnostic {@link UiItem} into a real
 * {@link ItemStack}. Both the chest {@link PaperMenuAdapter} and the lobby hotbar
 * ({@link com.sexidium.paper.adapter.world.PaperLobbyGuard}) render through this, so item rendering —
 * MiniMessage name/lore (with the {@link PaperMenuArt} glyph translation), player-head skinning, and the
 * pack-gated custom {@code item_model} — lives in exactly one place. See {@code docs/interface/ui-interaction-system.md}.
 */
public final class PaperUiItemFactory {
  private PaperUiItemFactory() {
  }

  /** Builds a fresh stack for the given visual; {@code packLoaded} gates the custom item-model on the pack. */
  public static ItemStack build(UiItem item, boolean packLoaded) {
    ItemStack stack = new ItemStack(resolveMaterial(item.icon()), Math.max(1, item.amount()));
    ItemMeta meta = stack.getItemMeta();
    if (meta != null) {
      apply(meta, item, packLoaded);
      stack.setItemMeta(meta);
    }
    return stack;
  }

  /**
   * Applies a visual's name / lore / head-skin / item-model onto an existing meta (the caller sets it
   * back on the stack). Split out so a caller that needs to add its own metadata — e.g. the hotbar's PDC
   * routing tag — can do so on the same meta before committing it.
   */
  public static void apply(ItemMeta meta, UiItem item, boolean packLoaded) {
    stripVanillaTooltip(meta);
    Component name = PaperMenuArt.text(item.name(), packLoaded).decoration(TextDecoration.ITALIC, false);
    meta.displayName(name);
    List<String> loreLines = item.lore();
    if (loreLines != null && !loreLines.isEmpty()) {
      List<Component> lore = new ArrayList<>(loreLines.size());
      for (String line : loreLines) {
        lore.add(PaperMenuArt.text(line, packLoaded).decoration(TextDecoration.ITALIC, false));
      }
      meta.lore(lore);
    }
    // Texture a player-head with its owner's real skin (SkinsRestorer / live profile) so rosters and the
    // Players hotbar item show faces, not default Steve heads.
    if (item.headOwner() != null && meta instanceof SkullMeta skull) {
      PaperSkullSkins.apply(skull, item.headOwner());
    }
    // Custom item-model icon — only for pack-loaded players, else the vanilla material shows.
    if (packLoaded && item.model() != null) {
      applyItemModel(meta, item.model());
    }
  }

  /**
   * Strips every vanilla tooltip block from a UI item, so a button shows only its name and lore.
   *
   * <p>A menu icon is a picture, not gear: a {@code diamond_sword} button must not advertise "7 Attack
   * Damage", an enchanted-look icon must not list its enchantments, and a potion/book/banner icon must not
   * spill its contents. {@link ItemFlag#values()} covers every hide-flag the running server knows (so a
   * future flag is picked up for free), and the empty attribute-modifiers component is belt-and-braces:
   * it replaces the <i>material's own defaults</i>, which is what produces the "When in Main Hand" block
   * on weapons and tools even when the item carries no modifiers of its own.</p>
   *
   * <p>The {@code minecraft:<id>} and "N component(s)" lines are the client's F3+H advanced tooltips —
   * they are rendered locally and no server-side item data can suppress them.</p>
   */
  private static void stripVanillaTooltip(ItemMeta meta) {
    meta.setAttributeModifiers(ImmutableMultimap.of());
    meta.addItemFlags(ItemFlag.values());
  }

  /** Resolves an {@link ItemKey} to a Bukkit {@link Material}, falling back to PAPER for unknown ids. */
  public static Material resolveMaterial(ItemKey icon) {
    if (icon == null) {
      return Material.PAPER;
    }
    Material material = Material.matchMaterial(icon.qualifiedName());
    if (material == null) {
      material = Material.matchMaterial(icon.value());
    }
    return material == null ? Material.PAPER : material;
  }

  /** Applies a custom {@code item_model} key, degrading to the vanilla material on older servers. */
  public static void applyItemModel(ItemMeta meta, String model) {
    try {
      NamespacedKey key = NamespacedKey.fromString(model);
      if (key != null) {
        meta.setItemModel(key);
      }
    } catch (Throwable ignored) {
      // Older server without the item-model component: keep the vanilla material icon.
    }
  }
}
