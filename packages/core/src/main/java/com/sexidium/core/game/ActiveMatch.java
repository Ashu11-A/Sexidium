package com.sexidium.core.game;

import com.sexidium.core.game.persist.MatchSnapshot;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLease;

import java.util.List;
import java.util.UUID;

public final class ActiveMatch {
  private final UUID matchId;
  private final String modeId;
  private final List<String> modeArgs;
  private final Game game;
  // Not final: a mode may regenerate its world underneath itself (Death Resets), which produces a new
  // lease for the same match. See replaceLease.
  private WorldLease worldLease;
  private final long createdAt;

  public ActiveMatch(UUID matchId, String modeId, List<String> modeArgs, Game game, WorldLease worldLease) {
    this.matchId = matchId;
    this.modeId = modeId;
    this.modeArgs = modeArgs == null ? List.of() : List.copyOf(modeArgs);
    this.game = game;
    this.worldLease = worldLease;
    this.createdAt = System.currentTimeMillis();
  }

  public UUID id() {
    return matchId;
  }

  public String modeId() {
    return modeId;
  }

  public List<String> modeArgs() {
    return modeArgs;
  }

  public Game game() {
    return game;
  }

  public WorldLease lease() {
    return worldLease;
  }

  /**
   * Points this match at a different world, for a mode that regenerated its own world while running.
   *
   * <p>Swapping here rather than at each call site is the point: every world read in the framework and
   * in the mode resolves through {@link #world()} on this object, so one write moves all of them at
   * once and there is no window in which half the system is looking at a world that no longer exists.
   * The OLD lease is not closed — the reset path has already deleted the world it pointed at, and
   * closing a lease on a deleted world would put the disposal machinery to work on nothing.</p>
   */
  public void replaceLease(WorldLease replacement) {
    this.worldLease = replacement;
  }

  public WorldAdapter world() {
    return worldLease == null ? null : worldLease.world();
  }

  public String worldName() {
    WorldAdapter worldAdapter = world();
    return worldAdapter == null ? null : worldAdapter.name();
  }

  public long createdAt() {
    return createdAt;
  }

  public MatchSnapshot buildSnapshot() {
    MatchSnapshot matchSnapshot = new MatchSnapshot(matchId, modeId);
    matchSnapshot.modeArgs.addAll(modeArgs);
    matchSnapshot.worldName = worldName();
    matchSnapshot.state = game.state();
    matchSnapshot.createdAt = createdAt;
    game.writeSnapshot(matchSnapshot);
    return matchSnapshot;
  }
}
