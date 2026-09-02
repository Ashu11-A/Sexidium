package com.sexidium.paper.adapter.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.google.common.collect.Multimap;
import com.sexidium.core.menu.UiItem;
import com.sexidium.core.platform.model.ItemKey;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * A menu button is a picture, not gear: nothing about the icon's <em>material</em> may leak into its
 * tooltip.
 *
 * <p>The regression this exists to catch is a chest button rendered on a weapon or tool material — the
 * Minigames tab on a {@code diamond_sword} — advertising "7 Attack Damage / 1.6 Attack Speed" under its
 * lore. Those lines come from the material's <em>default</em> attribute modifiers, not from anything the
 * {@link UiItem} carries, so hiding them takes an explicit empty {@code attribute_modifiers} component;
 * the hide-flags alone are what keep enchantments, potion/book/banner contents, dye and trims out. The
 * test asserts on {@link ItemFlag#values()} rather than a hard-coded list so a hide-flag added by a
 * future server version has to be picked up here too.</p>
 */
class PaperUiItemTooltipTest {

  @Test
  void applyStripsDefaultAttributesAndEveryHideFlag() {
    ItemMeta meta = mock(ItemMeta.class);

    PaperUiItemFactory.apply(meta, UiItem.of(new ItemKey("minecraft", "diamond_sword"), "<gold>Minigames",
        List.of("<gray>Play competitive & casual minigames")), false);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Multimap<Attribute, AttributeModifier>> modifiers =
        ArgumentCaptor.forClass(Multimap.class);
    verify(meta).setAttributeModifiers(modifiers.capture());
    assertTrue(modifiers.getValue().isEmpty(), "the icon must carry an explicit empty modifier set");

    ArgumentCaptor<ItemFlag[]> flags = ArgumentCaptor.forClass(ItemFlag[].class);
    verify(meta).addItemFlags(flags.capture());
    Set<ItemFlag> hidden = EnumSet.noneOf(ItemFlag.class);
    hidden.addAll(List.of(flags.getValue()));
    assertEquals(EnumSet.allOf(ItemFlag.class), hidden, "every hide-flag the server defines must be set");
  }
}
