package com.sexidium.core.network;

import java.util.List;

/**
 * What content a node must implement to host a given world.
 *
 * <p>This closes the reader-side gap. {@code PlacementPlanner.adoptableHere} is handed a world key
 * and a placement row and never sees the challenge list, so it cannot tell a world it can run from
 * one it would silently ruin — and the join it needs already exists, as a single indexed lookup on
 * the UNIQUE {@code ux_experiences_world} index.</p>
 *
 * <p>A functional seam rather than a dependency, mirroring {@code NetworkService.setLocalDiskIndex}:
 * the network layer must not import the experience layer, and an un-wired platform has to keep
 * planning normally. {@link #NONE} is that default and it means "anything capable may host this".</p>
 */
@FunctionalInterface
public interface WorldContentRequirements {

  /**
   * Content codes a node must implement to host {@code worldKey}.
   *
   * <p>Empty means unconstrained. Must never throw: it is consulted on the placement path, and a
   * lookup that fails has to degrade to "unconstrained", not to a refused world.</p>
   */
  List<String> requiredCodes(String worldKey);

  /** The default: nothing is constrained, so placement behaves exactly as it did before. */
  WorldContentRequirements NONE = worldKey -> List.of();
}
