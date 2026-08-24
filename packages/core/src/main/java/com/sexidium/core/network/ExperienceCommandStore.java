package com.sexidium.core.network;

import java.util.List;
import java.util.Optional;

/**
 * The durable half of an owner action that has to run on another node — the request AND its answer.
 *
 * <p>{@code ExperienceCommandRouter} used to publish a DELETE onto the bus and report success on the
 * spot. The bus cannot carry that promise: it is broadcast-only with no ack and no dedupe, and
 * {@link DbNetworkBus#start()} seeds its cursor to {@code MAX(id)}, so a command published while the
 * node holding that world was restarting is lost and the owner is told their map is gone. This is the
 * same answer the drain protocol already gives — <b>the acknowledgement is the row</b> — and the bus
 * stays what it is good at: a doorbell that saves the target a second of polling.</p>
 *
 * <p>An interface rather than the {@code Db...} class directly, because the router is unit-tested
 * without a database and standalone has no table at all.</p>
 */
public interface ExperienceCommandStore {

  /**
   * How long a request may sit in {@link State#RUNNING} before another attempt may take it.
   *
   * <p>{@link State#RUNNING} means "a node took this and is doing it", and a node that dies mid-delete
   * — a rolling-update SIGKILL, an OOM — leaves that sentence true forever. Without a window the row
   * is invisible to the drain (which wanted PENDING) and to the expiry sweep (same), so the delete is
   * lost while the owner has already been told the request stands. The sibling protocols spell this as
   * a {@code claim_expires_at} column; here the claim stamps {@code updated_at}, so its age is the
   * same fact without a migration.</p>
   *
   * <p>Generous on purpose, but generosity is not the guarantee. A RUNNING row is stale only if
   * nothing renews it, and the copy verbs hold one open for as long as the folder takes — a 290 MB
   * tree on shared storage is minutes, and a fixed window is a guess about the biggest world anybody
   * will ever own. The node running the work says so instead: see {@link #touch}, which the router
   * calls while a request is deferred, so "this row is stale" keeps meaning "the node holding it is
   * gone" rather than "the copy is taking a while".</p>
   */
  long RECLAIM_AFTER_MILLIS = 5L * 60L * 1000L;

  /** Where a request is in its life. Terminal states are {@link #DONE}, {@link #FAILED}, {@link #EXPIRED}. */
  enum State {
    /** Written, nobody has taken it yet. */
    PENDING,
    /** The target node won the claim and is running it. */
    RUNNING,
    /** The target node ran it and it worked. */
    DONE,
    /** The target node ran it and declined (players still inside, unload refused, content missing). */
    FAILED,
    /** Nobody took it before the deadline. */
    EXPIRED;

    static State parse(String value) {
      if (value == null) {
        return PENDING;
      }
      try {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        return PENDING;
      }
    }
  }

  /**
   * One request. {@code args} is the router's own {@code k=v;k=v} payload, stored verbatim, and
   * {@code id} is a random token minted by the requester — an autoincrement column would have to be
   * read back through three different dialects' idea of a generated key.
   */
  record Command(
      String id,
      String experienceId,
      String worldKey,
      String op,
      String args,
      String requestedBy,
      String targetNode,
      State state,
      String detail,
      long deadline,
      long createdAt,
      long updatedAt) {

    /** Whether this request has an answer, whatever the answer is. */
    public boolean terminal() {
      return state == State.DONE || state == State.FAILED || state == State.EXPIRED;
    }
  }

  /** Write a new PENDING request. FALSE means nothing was recorded, so nothing may be promised. */
  boolean insert(Command command);

  /** Read one request back. The requester's timeout path and the operator's SELECT both use it. */
  Optional<Command> byId(String id);

  /**
   * Take a request for {@code nodeId}: PENDING → RUNNING, or a RUNNING one abandoned longer than
   * {@link #RECLAIM_AFTER_MILLIS} ago.
   *
   * <p>Empty means somebody already has it — which is exactly what makes a duplicated or replayed bus
   * message harmless, and the reason this is a conditional UPDATE and not a read followed by a write.</p>
   */
  Optional<Command> claim(String id, String nodeId);

  /** Record the answer: RUNNING → DONE or FAILED. */
  boolean complete(String id, boolean ok, String detail);

  /**
   * Say that {@code nodeId} is still working on a RUNNING request: re-stamp {@code updated_at}.
   *
   * <p>{@code updated_at} is the age {@link #RECLAIM_AFTER_MILLIS} measures, and it used to be written
   * once, by the claim, and never touched again while the work ran. That was harmless only while every
   * handler answered synchronously. A deferred copy leaves the row RUNNING for the length of a folder
   * copy, so a copy that outlives the window is handed back to <em>this same node's</em> drain, which
   * re-runs it, is refused BUSY by the claim the copy still holds, and marks the row terminally FAILED
   * — while the copy goes on to succeed. The durable record then says the backup failed and the disk
   * says it did not.</p>
   *
   * <p>Conditional on the row still being RUNNING and still addressed here, like every other mutation
   * in this protocol: a heartbeat is not a way to take a row back.</p>
   *
   * <p>Defaulted to "nothing to renew" so an in-memory store — a test fake, standalone — needs no
   * change; the row it holds cannot be reclaimed by a node that never went away.</p>
   *
   * @return true when a row was renewed
   */
  default boolean touch(String id, String nodeId, long now) {
    return false;
  }

  /**
   * Point a request at a different node, leaving it PENDING for that node to drain.
   *
   * <p>The target is resolved when the owner clicks and the row can outlive the node being down by a
   * whole rolling update — during which an idle world may be adopted by another worker. Re-addressing
   * is what keeps such a request alive without letting its original target delete a folder somebody
   * else now has open. Only a PENDING row moves; one already being run is not ours to redirect.</p>
   */
  boolean retarget(String id, String nodeId);

  /**
   * Requests a node may take now, oldest first: its PENDING ones, plus any of its own left RUNNING by
   * an attempt that died (see {@link #RECLAIM_AFTER_MILLIS}).
   */
  List<Command> pendingFor(String nodeId, long now);

  /**
   * Mark every request past its deadline EXPIRED. Returns how many.
   *
   * <p>Sweeps RUNNING as well as PENDING: a row abandoned by a node that never came back would
   * otherwise sit in RUNNING forever, past its deadline, invisible to everything.</p>
   */
  int expire(long now);
}
