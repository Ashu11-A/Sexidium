package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.ItemEntityHandle;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.paper.adapter.util.PaperConverters;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/** {@link ItemEntityHandle} backed by a Bukkit {@link Item} (a dropped item entity on the ground). */
public final class PaperItemEntityHandle implements ItemEntityHandle {
  private final Item entity;

  public PaperItemEntityHandle(Item entity) {
    this.entity = entity;
  }

  @Override
  public UUID id() {
    return entity.getUniqueId();
  }

  @Override
  public boolean valid() {
    return entity.isValid() && !entity.isDead();
  }

  @Override
  public ItemKey itemKey() {
    return PaperConverters.toCore(entity.getItemStack().getType());
  }

  @Override
  public int amount() {
    return entity.getItemStack().getAmount();
  }

  @Override
  public void setAmount(int amount) {
    if (amount <= 0) {
      return; // caller follows up with remove(); leave the stack intact otherwise
    }
    ItemStack stack = entity.getItemStack();
    stack.setAmount(amount); // may exceed the vanilla 64-cap for a merged stack
    entity.setItemStack(stack);
  }

  @Override
  public void remove() {
    entity.remove();
  }

  @Override
  public WorldPosition position() {
    return PaperConverters.toCore(entity.getLocation());
  }

  @Override
  public boolean setVelocity(double velocityX, double velocityY, double velocityZ) {
    if (!valid()) {
      return false;
    }
    entity.setVelocity(new org.bukkit.util.Vector(velocityX, velocityY, velocityZ));
    return true;
  }

  @Override
  public String worldName() {
    return entity.getWorld().getName();
  }
}
