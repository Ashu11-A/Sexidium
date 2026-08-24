package com.sexidium.paper.adapter.inventory;

import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class PaperKitAdapter implements KitAdapter {
  private final JavaPlugin plugin;
  private final Map<String, PaperKit> kits = new LinkedHashMap<>();
  private static Supplier<PaperKit> defaultKitFactory = PaperKitAdapter::createDefaultKit;

  public PaperKitAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
    reload();
  }

  static void setDefaultKitFactory(Supplier<PaperKit> factory) {
    defaultKitFactory = factory == null ? PaperKitAdapter::createDefaultKit : factory;
  }

  private static PaperKit createDefaultKit() {
    return new PaperKit(List.of(new ItemStack(Material.IRON_SWORD), new ItemStack(Material.GOLDEN_APPLE, 4)),
        Map.of(), Map.of(), List.of());
  }

  @Override
  public boolean apply(PlayerAdapter playerAdapter, String kitName) {
    if (!(playerAdapter instanceof PaperPlayerAdapter paperAdapter)) {
      return false;
    }
    PaperKit kit = kits.get(normalize(kitName));
    if (kit == null) {
      return false;
    }
    PlayerInventory inventory = paperAdapter.handle().getInventory();
    for (ItemStack itemStack : kit.items()) {
      inventory.addItem(itemStack.clone());
    }
    // Items that declared an explicit `slot` are pinned to that exact inventory index (set AFTER the
    // free-fill items above so a pinned slot always wins). 0-8 is the hotbar, 9-35 the main inventory.
    kit.slottedItems().forEach((slot, itemStack) -> inventory.setItem(slot, itemStack.clone()));
    // Armor pieces are worn (set into the armor slots), not just dropped into the inventory, so a kit's
    // helmet/leggings actually protect the player. Previously the whole `armor:` block was ignored.
    kit.armor().forEach((slot, itemStack) -> {
      switch (slot) {
        case "helmet" -> inventory.setHelmet(itemStack.clone());
        case "chestplate" -> inventory.setChestplate(itemStack.clone());
        case "leggings" -> inventory.setLeggings(itemStack.clone());
        case "boots" -> inventory.setBoots(itemStack.clone());
        default -> {
          // Unknown armor slot key — ignore.
        }
      }
    });
    for (PotionEffect effect : kit.effects()) {
      paperAdapter.handle().addPotionEffect(effect);
    }
    return true;
  }

  @Override
  public boolean exists(String kitName) {
    return kits.containsKey(normalize(kitName));
  }

  @Override
  public Set<String> names() {
    return kits.keySet();
  }

  @Override
  public void reload() {
    kits.clear();
    ConfigurationSection root = plugin.getConfig().getConfigurationSection("kits");
    if (root != null) {
      for (String key : root.getKeys(false)) {
        ConfigurationSection kitSection = root.getConfigurationSection(key);
        if (kitSection != null) {
          kits.put(normalize(key), parse(kitSection));
        }
      }
    }
    if (kits.isEmpty()) {
      kits.put("default", defaultKitFactory.get());
    }
  }

  private PaperKit parse(ConfigurationSection kitSection) {
    List<ItemStack> items = new ArrayList<>();
    Map<Integer, ItemStack> slottedItems = new LinkedHashMap<>();
    for (Map<?, ?> rawItem : kitSection.getMapList("items")) {
      ItemStack stack = parseStack(rawItem.get("material"), rawItem.get("amount"), rawItem.get("enchants"));
      if (stack == null) {
        continue;
      }
      if (rawItem.get("slot") instanceof Number slotNumber && slotNumber.intValue() >= 0) {
        slottedItems.put(slotNumber.intValue(), stack);
      } else {
        items.add(stack);
      }
    }
    Map<String, ItemStack> armor = new LinkedHashMap<>();
    ConfigurationSection armorSection = kitSection.getConfigurationSection("armor");
    if (armorSection != null) {
      for (String slot : armorSection.getKeys(false)) {
        ConfigurationSection piece = armorSection.getConfigurationSection(slot);
        if (piece == null) {
          continue;
        }
        ItemStack stack = parseStack(piece.get("material"), piece.get("amount"), piece.get("enchants"));
        if (stack != null) {
          armor.put(slot.toLowerCase(Locale.ROOT), stack);
        }
      }
    }
    List<PotionEffect> effects = new ArrayList<>();
    for (Map<?, ?> rawEffect : kitSection.getMapList("effects")) {
      PotionEffect effect = parseEffect(rawEffect);
      if (effect != null) {
        effects.add(effect);
      }
    }
    return new PaperKit(items, slottedItems, armor, effects);
  }

  private ItemStack parseStack(Object materialName, Object amountValue, Object enchantsValue) {
    Material material = materialName == null ? null : Material.matchMaterial(String.valueOf(materialName));
    if (material == null || material.isAir()) {
      return null;
    }
    int amount = amountValue instanceof Number number ? number.intValue() : 1;
    ItemStack stack = new ItemStack(material, Math.max(1, amount));
    if (enchantsValue instanceof Map<?, ?> enchantMap) {
      for (Map.Entry<?, ?> entry : enchantMap.entrySet()) {
        Enchantment enchantment = Enchantment.getByKey(
            NamespacedKey.minecraft(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT)));
        int level = entry.getValue() instanceof Number number ? number.intValue() : 1;
        if (enchantment != null) {
          stack.addUnsafeEnchantment(enchantment, Math.max(1, level));
        }
      }
    }
    return stack;
  }

  private PotionEffect parseEffect(Map<?, ?> rawEffect) {
    Object type = rawEffect.get("type");
    PotionEffectType effectType = type == null
        ? null
        : PotionEffectType.getByName(String.valueOf(type).toUpperCase(Locale.ROOT));
    if (effectType == null) {
      return null;
    }
    int amplifier = rawEffect.get("amplifier") instanceof Number number ? number.intValue() : 0;
    int durationSeconds = rawEffect.get("duration-seconds") instanceof Number number ? number.intValue() : 10;
    return new PotionEffect(effectType, Math.max(1, durationSeconds) * 20, Math.max(0, amplifier));
  }

  private String normalize(String kitName) {
    return kitName == null ? "" : kitName.toLowerCase(Locale.ROOT);
  }

  record PaperKit(List<ItemStack> items, Map<Integer, ItemStack> slottedItems, Map<String, ItemStack> armor,
                  List<PotionEffect> effects) {
  }

  public static PaperKit paperKitForTest(List<ItemStack> items) {
    return new PaperKit(items, Map.of(), Map.of(), List.of());
  }
}
