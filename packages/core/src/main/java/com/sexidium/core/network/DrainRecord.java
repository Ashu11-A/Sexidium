package com.sexidium.core.network;

/**
 * One {@code node_drains} row, in full — including the two fields {@link DrainState} deliberately
 * does not carry.
 *
 * <p>{@link DrainState} is the <em>operator</em> shape: what {@code GET /node/drain} serialises and
 * the console prints. {@code node_epoch} and {@code fence} are the <em>fencing</em> shape: they never
 * leave the network layer, because nothing outside it can do anything correct with them. Keeping them
 * off the published record is what stops an orchestrator from ever being tempted to send one back.</p>
 *
 * <p>The guard is the same pair {@code world_placements} uses. A node that restarted mid-drain cannot
 * mutate its predecessor's row: it reads it, CASes it to its own epoch, and replaces it (see
 * {@code DrainCoordinator.reclaim}).</p>
 */
public record DrainRecord(
    String nodeId,
    long nodeEpoch,
    long fence,
    DrainPhase phase,
    String reason,
    String requestedBy,
    long deadline,
    int worldsTotal,
    int worldsLeft,
    int playersLeft,
    int matchesLeft,
    String detail,
    long startedAt,
    long updatedAt) {

  /** The published shape, for the API, the console and {@link DrainControlPort#state()}. */
  public DrainState toState() {
    return new DrainState(phase, reason, requestedBy, startedAt, deadline, worldsTotal, worldsLeft,
        playersLeft, matchesLeft, detail);
  }

  /** The same row at a new phase, stamped {@code now}. Every other field is carried through. */
  public DrainRecord at(DrainPhase newPhase, String newDetail, long now) {
    return new DrainRecord(nodeId, nodeEpoch, fence, newPhase, reason, requestedBy, deadline,
        worldsTotal, worldsLeft, playersLeft, matchesLeft, newDetail, startedAt, now);
  }

  /** The same row with fresh progress counters, stamped {@code now}. */
  public DrainRecord withCounts(int worlds, int players, int matches, long now) {
    return new DrainRecord(nodeId, nodeEpoch, fence, phase, reason, requestedBy, deadline,
        worldsTotal, worlds, players, matches, detail, startedAt, now);
  }

  /** Whether every kind of work this drain has to do is finished. */
  public boolean idleOfWork() {
    return worldsLeft <= 0 && playersLeft <= 0 && matchesLeft <= 0;
  }
}
