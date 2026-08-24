package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Retention window and sweep interval are two numbers, not one.
 *
 * <p>They used to be the same value with {@code lastSweep = 0}, which meant the very first poll on
 * every node — on every boot — issued a DELETE across {@code network_messages}, and that raising
 * retention to keep an hour of history also meant sweeping once an hour.</p>
 */
class DbNetworkBusSweepTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @TempDir
  Path tmp;

  private Database database;

  private Database database() throws Exception {
    if (database == null) {
      database = new Database(new File(tmp.toFile(), "network.db"));
    }
    return database;
  }

  private int messageCount() throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement ps =
               database.connection().prepareStatement("SELECT COUNT(*) FROM network_messages");
           ResultSet rs = ps.executeQuery()) {
        return rs.next() ? rs.getInt(1) : 0;
      }
    }
  }

  /** Writes a message with an explicitly backdated timestamp, so retention can be exercised. */
  private void publishAged(String nodeId, long createdAt) throws Exception {
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "INSERT INTO network_messages (topic, message_key, payload, origin_node, created_at)"
              + " VALUES (?, ?, ?, ?, ?)")) {
        ps.setString(1, NetworkBus.Topics.RANK_CHANGED);
        ps.setString(2, "player-1");
        ps.setString(3, "{}");
        ps.setString(4, nodeId);
        ps.setLong(5, createdAt);
        ps.executeUpdate();
      }
    }
  }

  @Test
  @DisplayName("the first poll after start does NOT sweep")
  void firstPoll_doesNotSweep() throws Exception {
    database();
    // Old enough that retention would certainly drop it, if a sweep ran at all.
    publishAged("worker-9", System.currentTimeMillis() - 3_600_000L);

    DbNetworkBus bus =
        new DbNetworkBus(database, SILENT, "worker-1", poll -> () -> { }, 60_000L, 600_000L);
    bus.start();
    bus.poll();

    assertEquals(1, messageCount(),
        "a node's first poll must not open its life with a DELETE across the bus table");
  }

  @Test
  @DisplayName("once the sweep interval has elapsed, messages older than retention are dropped")
  void afterInterval_dropsExpiredMessages() throws Exception {
    database();
    long now = System.currentTimeMillis();
    publishAged("worker-9", now - 3_600_000L); // beyond retention
    publishAged("worker-9", now);              // inside retention

    // Sweep interval of 0 is clamped to 1ms, so the first poll is already past it.
    DbNetworkBus bus =
        new DbNetworkBus(database, SILENT, "worker-1", poll -> () -> { }, 60_000L, 0L);
    bus.start();
    Thread.sleep(5L);
    bus.poll();

    assertEquals(1, messageCount(), "only the message inside the retention window survives");
  }

  @Test
  @DisplayName("a long retention with a short sweep interval keeps history AND sweeps often")
  void shortIntervalLongRetention_keepsHistory() throws Exception {
    database();
    long now = System.currentTimeMillis();
    publishAged("worker-9", now - 3_600_000L);
    publishAged("worker-9", now);

    // The pairing that was impossible when the two were one number.
    DbNetworkBus bus =
        new DbNetworkBus(database, SILENT, "worker-1", poll -> () -> { }, 86_400_000L, 0L);
    bus.start();
    Thread.sleep(5L);
    bus.poll();

    assertEquals(2, messageCount(), "a 24h retention keeps an hour-old message however often we sweep");
  }
}
