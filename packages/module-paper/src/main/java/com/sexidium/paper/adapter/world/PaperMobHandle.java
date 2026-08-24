package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.paper.adapter.util.PaperConverters;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Locale;

public final class PaperMobHandle implements MobHandle {
  private final LivingEntity entity;

  public PaperMobHandle(LivingEntity entity) {
    this.entity = entity;
  }

  @Override
  public String type() {
    return entity.getType().name().toLowerCase(Locale.ROOT);
  }

  @Override
  public java.util.UUID id() {
    return entity.getUniqueId();
  }

  @Override
  public boolean valid() {
    return entity.isValid();
  }

  @Override
  public WorldPosition position() {
    return PaperConverters.toCore(entity.getLocation());
  }

  @Override
  public double health() {
    return entity.getHealth();
  }

  @Override
  public void setHealth(double health) {
    entity.setHealth(Math.max(0.0, Math.min(entity.getMaxHealth(), health)));
  }

  @Override
  public void setVelocity(double velocityX, double velocityY, double velocityZ) {
    entity.setVelocity(entity.getVelocity().add(new Vector(velocityX, velocityY, velocityZ)));
  }

  @Override
  public void equip(String slot, ItemStackData itemStackData) {
    EntityEquipment equipment = entity.getEquipment();
    if (equipment == null || slot == null) {
      return;
    }
    EquipmentSlot equipmentSlot = switch (slot.toLowerCase(Locale.ROOT)) {
      case "head", "helmet" -> EquipmentSlot.HEAD;
      case "chest", "chestplate" -> EquipmentSlot.CHEST;
      case "legs", "leggings" -> EquipmentSlot.LEGS;
      case "feet", "boots" -> EquipmentSlot.FEET;
      case "offhand", "off_hand" -> EquipmentSlot.OFF_HAND;
      default -> EquipmentSlot.HAND;
    };
    equipment.setItem(equipmentSlot, toItemStack(itemStackData));
  }

  @Override
  public void addEffect(String effectKey, int amplifier, int durationTicks) {
    PotionEffectType effectType = effectType(effectKey);
    if (effectType != null) {
      entity.addPotionEffect(new PotionEffect(effectType, Math.max(1, durationTicks), Math.max(0, amplifier)));
    }
  }

  @Override
  public void setGlowing(boolean glowing) {
    entity.setGlowing(glowing);
  }

  @Override
  public void damage(double amount) {
    entity.damage(Math.max(0.0, amount));
  }

  @Override
  public void duplicate() {
    // Entity#copy is a full NBT-level clone with a fresh UUID, so the copy keeps equipment, baby state,
    // taming and its owner, custom name and health for free. Respawning by type (what this used to do)
    // produced a same-species stranger: an armoured baby zombie duplicated into a plain adult.
    org.bukkit.entity.Entity copy = entity.copy(entity.getLocation());
    // copy() is not documented to carry the attack target, so re-assert it — a duplicate that forgets it
    // was chasing the player is not the same mob.
    if (copy instanceof org.bukkit.entity.Mob copyMob && entity instanceof org.bukkit.entity.Mob sourceMob) {
      copyMob.setTarget(sourceMob.getTarget());
    }
  }

  @Override
  public java.util.UUID targetId() {
    return entity instanceof org.bukkit.entity.Mob mob && mob.getTarget() != null
        ? mob.getTarget().getUniqueId()
        : null;
  }

  @Override
  public void clearTarget() {
    if (entity instanceof org.bukkit.entity.Mob mob) {
      mob.setTarget(null);
    }
  }

  private ItemStack toItemStack(ItemStackData itemStackData) {
    if (itemStackData == null) {
      return null;
    }
    Material material = PaperConverters.toNative(itemStackData.itemKey());
    return material == Material.AIR ? null : new ItemStack(material, itemStackData.amount());
  }

  private PotionEffectType effectType(String effectKey) {
    if (effectKey == null || effectKey.isBlank()) {
      return null;
    }
    String normalized = effectKey.contains(":") ? effectKey.substring(effectKey.indexOf(':') + 1) : effectKey;
    normalized = normalized.trim().toLowerCase(Locale.ROOT);
    PotionEffectType byKey = org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(normalized));
    return byKey != null ? byKey : PotionEffectType.getByName(normalized.toUpperCase(Locale.ROOT));
  }
}
