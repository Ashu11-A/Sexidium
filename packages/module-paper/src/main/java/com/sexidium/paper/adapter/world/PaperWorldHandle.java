package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.world.WorldHandle;
import com.sexidium.core.world.WorldKind;
import org.bukkit.World;

import java.nio.file.Path;

/** Paper {@link WorldHandle}: a live Bukkit {@link World} plus its Sexidium kind + canonical name. */
public final class PaperWorldHandle implements WorldHandle {
  private final World world;
  private final WorldKind kind;
  private final String runtimeName;
  private final PaperWorldAdapter adapter;

  public PaperWorldHandle(World world, WorldKind kind, String runtimeName) {
    this.world = world;
    this.kind = kind;
    this.runtimeName = runtimeName;
    this.adapter = new PaperWorldAdapter(world, runtimeName);
  }

  public World world() {
    return world;
  }

  @Override
  public String runtimeName() {
    return runtimeName;
  }

  @Override
  public WorldKind kind() {
    return kind;
  }

  @Override
  public WorldAdapter adapter() {
    return adapter;
  }

  @Override
  public Path canonicalFolder() {
    return world.getWorldFolder() == null ? null : world.getWorldFolder().toPath();
  }
}
