package com.sexidium.core.network;

/**
 * The seam every drain caller goes through: the HTTP API, the console command and the boot-time
 * reclaim all speak to this and never to the coordinator that implements it.
 *
 * <p>It exists so the control surface (endpoints, commands, log lines — the things an orchestrator
 * has already been written against) can ship and be exercised before the state machine behind it
 * does. {@link #INERT} is a complete, honest implementation for a node with no coordinator wired:
 * it reports NONE and refuses every request with {@code UNAVAILABLE}, which is a 409 an orchestrator
 * can act on rather than a 500 it has to guess about.</p>
 *
 * <p><b>Never throws.</b> A drain request arrives from a machine that is mid-rollout; the answer to
 * anything unexpected is a refusal code, not an exception.</p>
 */
public interface DrainControlPort {

  /**
   * Machine-readable reasons a drain request was refused. The same word appears in the HTTP 409 body
   * and in the console message, so an operator and a script are reading the same vocabulary.
   */
  final class Refusal {
    /** This node is standalone: there are no peers to hand anything to. */
    public static final String STANDALONE = "STANDALONE";
    /** Another node holds the network-wide drain lease. The detail names the holder. */
    public static final String BUSY = "BUSY";
    /** This node is already draining at this epoch. Idempotent: the current state is returned. */
    public static final String ALREADY = "ALREADY";
    /**
     * This node holds LOBBY and no other live, non-draining node does. Draining it would disconnect
     * every player on the network, because the proxy's fallback points at the node that is dying.
     * Overridable only by an explicit force, never automatically.
     */
    public static final String LOBBY_SINGLETON = "LOBBY_SINGLETON";
    /** Undrain was asked for on a node that is not draining. */
    public static final String NOT_DRAINING = "NOT_DRAINING";
    /** No drain coordinator is wired on this node (see {@link DrainControlPort#INERT}). */
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private Refusal() {
    }
  }

  /**
   * The outcome of a drain/undrain request.
   *
   * @param accepted   whether the request changed (or already matched) the requested state
   * @param refusal    one of {@link Refusal}, or null when accepted
   * @param detail     a human sentence for the operator; may be null
   * @param state      the drain state after the request, never null
   */
  record DrainResult(boolean accepted, String refusal, String detail, DrainState state) {
    public static DrainResult accepted(DrainState state) {
      return new DrainResult(true, null, null, state == null ? DrainState.idle() : state);
    }

    public static DrainResult refused(String refusal, String detail, DrainState state) {
      return new DrainResult(false, refusal, detail, state == null ? DrainState.idle() : state);
    }
  }

  /** This node's current drain state. Never null; {@link DrainState#idle()} when not draining. */
  DrainState state();

  /**
   * Advance the drain by one step. Called from the existing network heartbeat, so the coordinator
   * owns no timer of its own and a test can drive every transition by hand with no sleeping.
   *
   * <p>Must never throw: it runs inside the heartbeat, and a heartbeat that dies gets this node
   * reaped and its worlds handed away.</p>
   */
  default void tick() {
  }

  /**
   * Ask this node to drain.
   *
   * @param reason      free text recorded on the row and logged, e.g. {@code rolling-update}
   * @param force       override {@link Refusal#LOBBY_SINGLETON}. This WILL disconnect players
   * @param requestedBy {@code console}, {@code api} or a player name, for the audit trail
   */
  DrainResult drain(String reason, boolean force, String requestedBy);

  /** Cancel a drain and return this node to UP. Idempotent past {@link Refusal#NOT_DRAINING}. */
  DrainResult undrain();

  /**
   * The answer for a node with no coordinator: never draining, always refuses.
   *
   * <p>Not a silent no-op — a script that asks a node to drain and gets "accepted" from a node that
   * cannot drain would restart it with players inside, which is the exact failure the whole protocol
   * exists to prevent.</p>
   */
  DrainControlPort INERT = new DrainControlPort() {
    @Override
    public DrainState state() {
      return DrainState.idle();
    }

    @Override
    public DrainResult drain(String reason, boolean force, String requestedBy) {
      return DrainResult.refused(Refusal.UNAVAILABLE,
          "This node has no drain coordinator wired; it cannot be drained.", DrainState.idle());
    }

    @Override
    public DrainResult undrain() {
      return DrainResult.refused(Refusal.NOT_DRAINING, "This node is not draining.",
          DrainState.idle());
    }
  };
}
