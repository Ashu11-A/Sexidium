package com.sexidium.core.network;

/**
 * A snapshot of one node's drain, as the {@code node_drains} row records it.
 *
 * <p>This is the shape {@code GET /node/drain} serialises and the console report prints, so the field
 * names are a frozen contract with the orchestrator. A node that is not draining answers
 * {@link #idle()} rather than null, so every caller can read {@link #phase()} without a null check.</p>
 */
public record DrainState(
    DrainPhase phase,
    String reason,
    String requestedBy,
    long startedAt,
    long deadline,
    int worldsTotal,
    int worldsLeft,
    int playersLeft,
    int matchesLeft,
    String detail) {

  private static final DrainState IDLE =
      new DrainState(DrainPhase.NONE, null, null, 0L, 0L, 0, 0, 0, 0, null);

  /** The answer for a node that is not draining, and the safe default before anything is wired. */
  public static DrainState idle() {
    return IDLE;
  }

  /** Whether the drain is finished with the work and the process may be stopped. */
  public boolean quiesced() {
    return phase == DrainPhase.QUIESCENT || phase == DrainPhase.READY;
  }

  /** Whether the drain gave up. An orchestrator must abort the roll rather than restart the node. */
  public boolean stalled() {
    return phase == DrainPhase.STALLED;
  }
}
