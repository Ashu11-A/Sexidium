package com.sexidium.core.game.experience.challenges;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SphereSweepTest {

  @Test
  void shellSize_matchesClosedForm() {
    assertEquals(1, SphereSweep.shellSize(0));
    assertEquals(26, SphereSweep.shellSize(1)); // 24*1 + 2
    assertEquals(98, SphereSweep.shellSize(2)); // 24*4 + 2
    assertEquals(218, SphereSweep.shellSize(3)); // 24*9 + 2
  }

  @Test
  void everyOffsetOnShellHasChebyshevRadiusR_andNoDuplicates() {
    int[] out = new int[3];
    for (int r = 0; r <= 12; r++) {
      Set<Long> seen = new HashSet<>();
      int size = SphereSweep.shellSize(r);
      for (int index = 0; index < size; index++) {
        SphereSweep.offsetAt(r, index, out);
        int chebyshev = Math.max(Math.abs(out[0]), Math.max(Math.abs(out[1]), Math.abs(out[2])));
        assertEquals(r, chebyshev, "offset " + index + " of shell " + r + " is off-shell");
        assertTrue(seen.add(pack(out)), "duplicate offset on shell " + r);
      }
      assertEquals(size, seen.size(), "shell " + r + " yielded the wrong number of distinct offsets");
    }
  }

  @Test
  void shellsTileTheWholeCubeExactlyOnce() {
    int radius = 8;
    int[] out = new int[3];
    Set<Long> visited = new HashSet<>();
    for (int r = 0; r <= radius; r++) {
      int size = SphereSweep.shellSize(r);
      for (int index = 0; index < size; index++) {
        SphereSweep.offsetAt(r, index, out);
        assertTrue(visited.add(pack(out)), "offset visited twice across shells");
      }
    }
    // The union of shells 0..radius must be exactly the cube [-radius, radius]^3.
    int expected = (2 * radius + 1) * (2 * radius + 1) * (2 * radius + 1);
    assertEquals(expected, visited.size());
    for (int x = -radius; x <= radius; x++) {
      for (int y = -radius; y <= radius; y++) {
        for (int z = -radius; z <= radius; z++) {
          assertTrue(visited.contains(pack(x, y, z)), "cube cell missing: " + x + "," + y + "," + z);
        }
      }
    }
  }

  private static long pack(int[] o) {
    return pack(o[0], o[1], o[2]);
  }

  private static long pack(int x, int y, int z) {
    return ((long) (x + 1024) << 22) | ((long) (y + 1024) << 11) | (z + 1024);
  }
}
