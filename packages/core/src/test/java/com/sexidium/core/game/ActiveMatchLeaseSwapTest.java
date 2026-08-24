package com.sexidium.core.game;

import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Re-pointing a live match at a world that replaced its own. Every world read in the framework and in
 * the mode resolves through the match, so this one write is what moves all of them at once — and the
 * snapshot has to move with them, or a restart a second later would reconnect players to a world that
 * has been deleted.
 */
class ActiveMatchLeaseSwapTest {

  @Test
  void replacingTheLeaseMovesEveryWorldReadAtOnce() {
    ActiveMatch match = match(lease("old_world"));
    assertEquals("old_world", match.worldName());

    match.replaceLease(lease("new_world"));

    assertEquals("new_world", match.worldName());
    assertEquals("new_world", match.world().name());
    assertEquals("new_world", match.lease().world().name());
  }

  @Test
  void theSnapshotFollowsTheNewWorld() {
    ActiveMatch match = match(lease("old_world"));

    match.replaceLease(lease("new_world"));
    MatchSnapshot snapshot = match.buildSnapshot();

    assertEquals("new_world", snapshot.worldName,
        "a restart must reconnect players to the world that exists, not the one that was deleted");
  }

  @Test
  void aMatchWithNoWorldStaysAnswerable() {
    ActiveMatch match = match(null);

    assertNull(match.world());
    assertNull(match.worldName());
  }

  private static ActiveMatch match(WorldLease worldLease) {
    return new ActiveMatch(UUID.randomUUID(), "experience", List.of(), stubGame(), worldLease);
  }

  private static StubGame stubGame() {
    return new StubGame(new GameContext(new com.sexidium.core.TestServerAdapter(),
        new com.sexidium.core.platform.noop.NoopKitAdapter(),
        com.sexidium.core.data.RankAwardPort.noop()));
  }

  private static WorldLease lease(String worldName) {
    return new WorldLease() {
      @Override
      public WorldAdapter world() {
        return new FakeWorldAdapter(worldName);
      }

      @Override
      public void close() {
      }
    };
  }

  private record FakeWorldAdapter(String name) implements WorldAdapter {
    @Override public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override public List<PlayerAdapter> players() {
      return List.of();
    }

    @Override public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {
    }

    @Override public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {
    }

    @Override public void setBorder(WorldBorderSpec worldBorderSpec) {
    }

    @Override public void resetBorder() {
    }

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) {
    }
  }
}
