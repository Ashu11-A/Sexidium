package com.sexidium.paper.adapter.resource;

import com.sexidium.core.platform.ResourceAdapter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.InputStream;
import java.util.Optional;

public final class PaperResourceAdapter implements ResourceAdapter {
  private final JavaPlugin plugin;

  public PaperResourceAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public Optional<InputStream> openResource(String resourcePath) {
    return Optional.ofNullable(plugin.getResource(resourcePath));
  }
}
