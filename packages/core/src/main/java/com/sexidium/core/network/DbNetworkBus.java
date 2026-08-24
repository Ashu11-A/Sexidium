package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The networked bus: a durable table plus a per-node cursor.
 *
 * <p>Each node remembers the highest {@code id} it has seen and polls for anything newer. That gives
 * three properties a fire-and-forget socket cannot: a node that was restarting still receives what it
 * missed, delivery order is total and identical on every node, and the whole history is inspectable
 * with {@code SELECT * FROM network_messages ORDER BY id DESC}.</p>
 *
 * <p><b>The cursor starts at the current maximum, not at zero.</b> A node joining an established
 * network must not replay every historical message as if it were news — that would re-fire every
 * cache invalidation and every announcement at boot.</p>
 *
 * <p>Messages this node published are filtered out by {@code origin_node}: the publisher already
 * applied the change locally.</p>
 */
public final class DbNetworkBus implements NetworkBus {

  /** Schedules the repeating poll. Kept as a port so tests can drive it by hand. */
  @FunctionalInterface
  public interface Poller {
    AutoCloseable schedule(Runnable poll);
  }

  private final Database database;
  private final LoggerAdapter logger;
  private final String nodeId;
  private final Poller poller;
  private final long retentionMillis;
  private final long sweepIntervalMillis;

  private final Map<String, List<BusListener>> listeners = new ConcurrentHashMap<>();
  private volatile long cursor = -1L;
  private volatile AutoCloseable pollHandle;
  private long lastSweep;

  /** Retention doubling as the sweep interval — kept for callers that do not care to separate them. */
  public DbNetworkBus(Database database, LoggerAdapter logger, String nodeId, Poller poller, long retentionMillis) {
    this(database, logger, nodeId, poller, retentionMillis, retentionMillis);
  }

  public DbNetworkBus(Database database, LoggerAdapter logger, String nodeId, Poller poller,
      long retentionMillis, long sweepIntervalMillis) {
    this.database = database;
    this.logger = logger;
    this.nodeId = nodeId;
    this.poller = poller;
    this.retentionMillis = retentionMillis;
    this.sweepIntervalMillis = Math.max(1L, sweepIntervalMillis);
    // Seeded to construction time, NOT left at 0. With 0 the very first poll on every node is always
    // past the interval, so every node opened its life with a DELETE across network_messages -- N
    // pointless writes into the one table the bus reads on its hot path, on every boot of every node.
    this.lastSweep = System.currentTimeMillis();
  }

  @Override
  public void start() {
    if (pollHandle != null) {
      return;
    }
    cursor = currentMaxId();
    pollHandle = poller.schedule(this::poll);
  }

  private long currentMaxId() {
    synchronized (database.lock()) {
      try (PreparedStatement ps =
               database.connection().prepareStatement("SELECT MAX(id) FROM network_messages");
           ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getLong(1) : 0L;
      } catch (SQLException failed) {
        logger.warning("Network bus could not read its starting cursor: " + failed.getMessage());
        return 0L;
      }
    }
  }

  @Override
  public void publish(String topic, String key, String payload) {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO network_messages (topic, message_key, payload, origin_node, created_at)"
              + " VALUES (?, ?, ?, ?, ?)")) {
        ps.setString(1, topic);
        ps.setString(2, key);
        ps.setString(3, payload);
        ps.setString(4, nodeId);
        ps.setLong(5, System.currentTimeMillis());
        ps.executeUpdate();
      } catch (SQLException failed) {
        // A dropped notification degrades a cache into staleness; it must not take down the caller.
        logger.warning("Network bus publish failed for topic " + topic + ": " + failed.getMessage());
      }
    }
  }

  /** Reads everything newer than the cursor and dispatches it. Package-visible so tests can pump it. */
  void poll() {
    List<Delivery> batch = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT id, topic, message_key, payload, origin_node FROM network_messages"
              + " WHERE id > ? ORDER BY id")) {
        ps.setLong(1, cursor);
        try (ResultSet rs = ps.executeQuery()) {
          while (rs.next()) {
            long id = rs.getLong(1);
            cursor = Math.max(cursor, id);
            String origin = rs.getString(5);
            if (nodeId.equals(origin)) {
              continue; // our own publication
            }
            batch.add(new Delivery(rs.getString(2), rs.getString(3), rs.getString(4), origin));
          }
        }
      } catch (SQLException failed) {
        logger.warning("Network bus poll failed: " + failed.getMessage());
        return;
      }
    }

    // Dispatch OUTSIDE the database monitor: a listener may itself hit the database, and holding
    // the lock across user code is how a single slow handler stalls every other subsystem.
    for (Delivery delivery : batch) {
      List<BusListener> topicListeners = listeners.get(delivery.topic());
      if (topicListeners == null) {
        continue;
      }
      for (BusListener listener : topicListeners) {
        try {
          listener.onMessage(delivery.topic(), delivery.key(), delivery.payload(), delivery.origin());
        } catch (RuntimeException listenerFailed) {
          logger.warning("Network bus listener for " + delivery.topic() + " threw: "
              + listenerFailed.getMessage());
        }
      }
    }

    sweep();
  }

  /**
   * Drops messages older than the retention window.
   *
   * <p>Any node may sweep; the DELETE is idempotent and racing sweeps simply both succeed. A node
   * whose cursor falls behind retention has to drop its caches wholesale rather than replay — correct,
   * and far simpler than trying to guarantee delivery to an absent node forever.</p>
   *
   * <p>How OFTEN to sweep and how FAR BACK to keep are two separate numbers. They used to be one, so
   * raising retention to keep an hour of history also meant sweeping once an hour — and lowering it
   * to sweep often also threw away the history. They are independent knobs now.</p>
   */
  private void sweep() {
    long now = System.currentTimeMillis();
    if (now - lastSweep < sweepIntervalMillis) {
      return;
    }
    lastSweep = now;
    synchronized (database.lock()) {
      try (PreparedStatement ps =
               database.connection().prepareStatement("DELETE FROM network_messages WHERE created_at < ?")) {
        ps.setLong(1, now - retentionMillis);
        ps.executeUpdate();
      } catch (SQLException failed) {
        logger.warning("Network bus retention sweep failed: " + failed.getMessage());
      }
    }
  }

  @Override
  public AutoCloseable subscribe(String topic, BusListener listener) {
    listeners.computeIfAbsent(topic, unused -> new CopyOnWriteArrayList<>()).add(listener);
    return () -> {
      List<BusListener> topicListeners = listeners.get(topic);
      if (topicListeners != null) {
        topicListeners.remove(listener);
      }
    };
  }

  @Override
  public void close() {
    AutoCloseable handle = pollHandle;
    pollHandle = null;
    if (handle != null) {
      try {
        handle.close();
      } catch (Exception ignored) {
        // Cancelling a scheduled task must never fail a shutdown.
      }
    }
    listeners.clear();
  }

  private record Delivery(String topic, String key, String payload, String origin) {
  }
}
