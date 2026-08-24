package com.sexidium.paper.adapter.resource;

import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaperResourceAdapterTest {

  @Test
  void openResource_returnsStreamWhenPresent() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    InputStream stream = new ByteArrayInputStream("hello".getBytes());
    when(plugin.getResource("lang.yml")).thenReturn(stream);
    PaperResourceAdapter adapter = new PaperResourceAdapter(plugin);
    Optional<InputStream> result = adapter.openResource("lang.yml");
    assertTrue(result.isPresent());
    assertEquals(stream, result.get());
  }

  @Test
  void openResource_returnsEmptyWhenMissing() {
    JavaPlugin plugin = mock(JavaPlugin.class);
    when(plugin.getResource("missing.yml")).thenReturn(null);
    PaperResourceAdapter adapter = new PaperResourceAdapter(plugin);
    Optional<InputStream> result = adapter.openResource("missing.yml");
    assertFalse(result.isPresent());
  }
}
