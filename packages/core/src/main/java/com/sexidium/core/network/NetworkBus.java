package com.sexidium.core.network;

/**
 * Cross-node notification.
 *
 * <p>One interface, and the <em>call</em> picks the transport rather than a config key:</p>
 *
 * <ul>
 *   <li>{@link #publish(String, String, String)} — durable. The message is persisted, so a node that
 *       was restarting still sees it, and "why did that not propagate?" is answerable with a SELECT.
 *       This is the floor: it works with no proxy and no extra service.</li>
 *   <li>{@link #subscribe(String, BusListener)} — a node never receives its own publications; the
 *       publisher already applied the change locally, and echoing it back is how cache-invalidation
 *       loops start.</li>
 * </ul>
 *
 * <p><b>Why not Redis.</b> Unanimous across the analyses: at four nodes a database poll costs about
 * 4 queries/second against an indexed autoincrement, which is noise next to the rank and experience
 * traffic already flowing. Redis would add a fifth process to monitor, secure, back up and version,
 * plus an independent partial-failure mode where Redis is up and the database is down — caches
 * invalidating against data nobody can read. Revisit only if a second proxy appears.</p>
 *
 * <p><b>Why not Velocity plugin messaging.</b> Plugin messages ride a player's connection, so a
 * worker with zero players online can neither send nor receive — which is exactly the node that most
 * needs to hear "the friend graph changed".</p>
 */
public interface NetworkBus extends AutoCloseable {

  /** Receives messages published by OTHER nodes. */
  @FunctionalInterface
  interface BusListener {
    void onMessage(String topic, String key, String payload, String originNode);
  }

  /**
   * Publish to a topic.
   *
   * @param topic   a well-known name; see {@link Topics}
   * @param key     what the message is about (usually a player or world id); may be null
   * @param payload opaque to the bus
   */
  void publish(String topic, String key, String payload);

  /** Subscribe to a topic. The returned handle unsubscribes. */
  AutoCloseable subscribe(String topic, BusListener listener);

  /** Begin delivering messages. No-op if already started. */
  void start();

  @Override
  void close();

  /** Topic names, kept in one place so a publisher and a subscriber cannot disagree by typo. */
  final class Topics {
    public static final String RANK_CHANGED = "rank.changed";
    public static final String AUTH_LINKED = "auth.linked";
    /**
     * A Discord Approve/Deny landed. Keyed by {@code identity_id}, so the node holding that player
     * frozen can release (or kick) them without waiting for its own poll of {@code auth_requests}.
     *
     * <p>Advisory, like {@link #NODE_DRAIN}: the row is still the truth, and a holding node re-reads
     * it on a one-second tick anyway — a dropped bus message must never strand a player.</p>
     */
    public static final String AUTH_DECIDED = "auth.decided";
    public static final String FRIEND_CHANGED = "friend.changed";
    public static final String EXPERIENCE_UPDATED = "experience.updated";
    public static final String LOBBY_CHANGED = "lobby.changed";
    public static final String QUEUE_CHANGED = "queue.changed";
    public static final String SESSION_MOVED = "session.moved";
    public static final String NODE_STATE = "node.state";
    /**
     * An owner action that has to happen where the WORLD is, not where the owner is.
     *
     * <p>The manage menu lives in the lobby and the lobby never holds an experience world, so every
     * one of delete / end-match / live-edit silently did nothing to the running world — and delete
     * did something far worse than nothing. See {@code ExperienceCommandRouter}.</p>
     */
    public static final String EXPERIENCE_COMMAND = "experience.command";
    /**
     * The answer to one {@link #EXPERIENCE_COMMAND}, addressed back to the node that asked.
     *
     * <p>Advisory, exactly like {@link #NODE_DRAIN}: the {@code experience_commands} row is the
     * truth and the requester re-reads it on a one-second tick anyway. This message only spares the
     * owner a second of watching a "Deleting…" line that has already finished.</p>
     */
    public static final String EXPERIENCE_COMMAND_RESULT = "experience.command.result";
    /**
     * A node announcing that it is draining for a restart. <b>Purely advisory</b> — nothing derives
     * state from it.
     *
     * <p>The drain protocol's response channel is the database, not the bus: a draining node
     * releases its fenced claims and peers "answer" by taking them through the conditional UPDATE
     * that already arbitrates ownership, so the acknowledgement <em>is</em> the row. That is also
     * what makes the protocol work across versions — a node that has never heard of this topic
     * simply has no subscriber, which is the one forward-compat mechanism the bus really has.</p>
     */
    public static final String NODE_DRAIN = "node.drain";

    private Topics() {
    }
  }
}
