package com.sexidium.paper.adapter.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sexidium.core.platform.model.WorldPosition;
import java.util.List;
import java.util.Set;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Silverfish;
import org.junit.jupiter.api.Test;

class PaperRopeTest {

  private static final String MARKER_TAG = "sx_rope_marker";

  @Test
  void drawRope_spawnsDustParticlesAlongTheLine() {
    World world = mock(World.class);
    PaperWorldAdapter adapter = new PaperWorldAdapter(world);

    adapter.drawRope(new WorldPosition("w", 0, 64, 0, 0, 0), new WorldPosition("w", 4, 64, 0, 0, 0), 0.2);

    // ~4 blocks at one point per 0.4 → ≈11 dust particles, all of type DUST.
    verify(world, atLeast(5)).spawnParticle(
        eq(Particle.DUST), any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0), eq(0.0), any());
  }

  @Test
  void drawRope_isNoopForZeroLength() {
    World world = mock(World.class);
    PaperWorldAdapter adapter = new PaperWorldAdapter(world);
    WorldPosition same = new WorldPosition("w", 1, 64, 1, 0, 0);
    adapter.drawRope(same, same, 0.2);
    verify(world, never()).spawnParticle(
        any(Particle.class), any(Location.class), eq(1), eq(0.0), eq(0.0), eq(0.0), eq(0.0), any());
  }

  @Test
  void removeRopeMarkers_removesTaggedAndInertSilverfishOnly() {
    World world = mock(World.class);
    Silverfish tagged = mock(Silverfish.class);          // tagged marker (build 2)
    Silverfish inert = mock(Silverfish.class);            // untagged no-AI marker (build 1)
    Silverfish wild = mock(Silverfish.class);             // real silverfish: AI on → keep
    Entity bystander = mock(Entity.class);                // unrelated entity → keep
    when(tagged.getScoreboardTags()).thenReturn(Set.of(MARKER_TAG));
    when(inert.getScoreboardTags()).thenReturn(Set.of());
    when(inert.hasAI()).thenReturn(false);
    when(wild.getScoreboardTags()).thenReturn(Set.of());
    when(wild.hasAI()).thenReturn(true);
    when(bystander.getScoreboardTags()).thenReturn(Set.of());
    when(world.getEntities()).thenReturn(List.of(tagged, inert, wild, bystander));

    int removed = new PaperWorldAdapter(world).removeRopeMarkers();

    assertEquals(2, removed);
    verify(tagged).remove();
    verify(inert).remove();
    verify(wild, never()).remove();
    verify(bystander, never()).remove();
  }
}
