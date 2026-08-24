package com.sexidium.core.world.map;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionRendererTest {
  @Test
  void outlineDrawsEveryEdgeInTheGivenColour() {
    RecordingWorld world = new RecordingWorld();
    // A 1x1x1 box: each of the 12 edges has world length 1, so step=1 yields 2 points/edge = 24 dust.
    RegionRenderer.outline(world, new Cuboid(0, 0, 0, 0, 0, 0), 0xFF5555);
    assertEquals(24, world.dust.size());
    assertTrue(world.dust.stream().allMatch(d -> d.rgb == 0xFF5555));
    assertTrue(world.dust.stream().allMatch(d -> "arena".equals(d.position.worldName())));
  }

  @Test
  void outlineStepDensityIncreasesPointCount() {
    RecordingWorld coarse = new RecordingWorld();
    RecordingWorld fine = new RecordingWorld();
    Cuboid box = new Cuboid(0, 0, 0, 9, 0, 0);
    RegionRenderer.outline(coarse, box, 0x00FF00, 5.0, 1.0f);
    RegionRenderer.outline(fine, box, 0x00FF00, 1.0, 1.0f);
    assertTrue(fine.dust.size() > coarse.dust.size());
  }

  @Test
  void markerDrawsAVerticalColumn() {
    RecordingWorld world = new RecordingWorld();
    RegionRenderer.marker(world, new WorldPosition("arena", 5.0, 64.0, 5.0, 0f, 0f), 0x5555FF, 1.0f);
    // dy 0.0 .. 2.0 stepping 0.5 => 5 points.
    assertEquals(5, world.dust.size());
    assertTrue(world.dust.stream().allMatch(d -> d.rgb == 0x5555FF));
    assertEquals(64.0, world.dust.get(0).position.coordinateY(), 1e-9);
    assertEquals(66.0, world.dust.get(4).position.coordinateY(), 1e-9);
  }

  @Test
  void nullArgumentsAreNoOps() {
    RecordingWorld world = new RecordingWorld();
    RegionRenderer.outline(world, null, 0xFFFFFF);
    RegionRenderer.outline(null, new Cuboid(0, 0, 0, 1, 1, 1), 0xFFFFFF);
    RegionRenderer.marker(world, null, 0xFFFFFF, 1.0f);
    assertTrue(world.dust.isEmpty());
    assertFalse(world.dust.size() > 0);
  }

  private record Dust(WorldPosition position, int rgb, float size) {
  }

  /** Minimal {@link WorldAdapter} that records every {@link #spawnDust} call. */
  private static final class RecordingWorld implements WorldAdapter {
    private final List<Dust> dust = new ArrayList<>();

    @Override public void spawnDust(WorldPosition at, int rgb, float size) {
      dust.add(new Dust(at, rgb, size));
    }

    @Override public String name() { return "arena"; }
    @Override public WorldPosition spawnPosition() { return new WorldPosition("arena", 0, 64, 0, 0, 0); }
    @Override public List<PlayerAdapter> players() { return List.of(); }
    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {}
    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {}
    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {}
    @Override public void resetBorder() {}
    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {}
  }
}
