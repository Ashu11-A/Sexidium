package com.sexidium.core.data;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.lib.data.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FriendService implements FriendGraph {
  public record Entry(UUID playerId, String playerName) {
  }

  private final LoggerAdapter loggerAdapter;
  private final Database database;
  private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
    Thread writerThread = new Thread(runnable, "Sexidium-Friends-DB");
    writerThread.setDaemon(true);
    return writerThread;
  });

  /**
   * Notified after the friend graph changes for a pair of players, so a network can tell the node
   * hosting each of them to refresh. Mirrors RankService's listener seam.
   *
   * <p>Fires for BOTH players: a friendship is symmetric and they are usually on different nodes,
   * so notifying only the actor would leave the other side's list stale until they reconnect.</p>
   */
  @FunctionalInterface
  public interface FriendChangeListener {
    void onChange(UUID firstPlayerId, UUID secondPlayerId);
  }

  private volatile FriendChangeListener friendChangeListener = (first, second) -> { };

  public void setFriendChangeListener(FriendChangeListener listener) {
    this.friendChangeListener = listener == null ? (first, second) -> { } : listener;
  }

  private void fireChanged(UUID first, UUID second) {
    try {
      friendChangeListener.onChange(first, second);
    } catch (RuntimeException listenerFailed) {
      // A notification failure must never fail the database operation that succeeded.
      loggerAdapter.warning("Friend change listener threw: " + listenerFailed.getMessage());
    }
  }

  public FriendService(LoggerAdapter loggerAdapter, Database database) {
    this.loggerAdapter = loggerAdapter;
    this.database = database;
  }

  public void requestAsync(UUID requesterId, String requesterName, UUID targetId) {
    writer.execute(() -> {
      try {
        synchronized (database.lock()) {
          try (PreparedStatement statement = database.connection().prepareStatement(database.dialect().upsert(
              "friend_requests",
              new String[] {"from_uuid", "from_name", "to_uuid", "created_at"},
              new String[] {"from_uuid", "to_uuid"},
              new String[] {"from_name"}))) {
            statement.setString(1, requesterId.toString());
            statement.setString(2, requesterName);
            statement.setString(3, targetId.toString());
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to store friend request", exception);
      }
          fireChanged(requesterId, targetId);
    });
  }

  public boolean accept(UUID accepterId, String accepterName, UUID requesterId, String requesterName) {
    synchronized (database.lock()) {
      try {
        boolean pendingRequest;
        try (PreparedStatement statement = database.connection().prepareStatement(
            "SELECT 1 FROM friend_requests WHERE from_uuid=? AND to_uuid=?")) {
          statement.setString(1, requesterId.toString());
          statement.setString(2, accepterId.toString());
          try (ResultSet resultSet = statement.executeQuery()) {
            pendingRequest = resultSet.next();
          }
        }
        if (!pendingRequest) {
          return false;
        }
        long currentTimeMillis = System.currentTimeMillis();
        insertFriend(accepterId.toString(), requesterId.toString(), requesterName, currentTimeMillis);
        insertFriend(requesterId.toString(), accepterId.toString(), accepterName, currentTimeMillis);
        try (PreparedStatement statement = database.connection().prepareStatement(
            "DELETE FROM friend_requests WHERE (from_uuid=? AND to_uuid=?) OR (from_uuid=? AND to_uuid=?)")) {
          statement.setString(1, requesterId.toString());
          statement.setString(2, accepterId.toString());
          statement.setString(3, accepterId.toString());
          statement.setString(4, requesterId.toString());
          statement.executeUpdate();
        }
        fireChanged(accepterId, requesterId);
        return true;
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to accept friend request", exception);
        return false;
      }
    }
  }

  /** Declines an incoming friend request (deletes the pending {@code from->to} row). */
  public void denyRequestAsync(UUID requesterId, UUID targetId) {
    writer.execute(() -> {
      try {
        synchronized (database.lock()) {
          try (PreparedStatement statement = database.connection().prepareStatement(
              "DELETE FROM friend_requests WHERE from_uuid=? AND to_uuid=?")) {
            statement.setString(1, requesterId.toString());
            statement.setString(2, targetId.toString());
            statement.executeUpdate();
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to deny friend request", exception);
      }
          fireChanged(requesterId, targetId);
    });
  }

  public void removeAsync(UUID firstPlayerId, UUID secondPlayerId) {
    writer.execute(() -> {
      try {
        synchronized (database.lock()) {
          try (PreparedStatement statement = database.connection().prepareStatement(
              "DELETE FROM friends WHERE (player_uuid=? AND friend_uuid=?) OR (player_uuid=? AND friend_uuid=?)")) {
            statement.setString(1, firstPlayerId.toString());
            statement.setString(2, secondPlayerId.toString());
            statement.setString(3, secondPlayerId.toString());
            statement.setString(4, firstPlayerId.toString());
            statement.executeUpdate();
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to remove friend", exception);
      }
          fireChanged(firstPlayerId, secondPlayerId);
    });
  }

  public boolean areFriends(UUID firstPlayerId, UUID secondPlayerId) {
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT 1 FROM friends WHERE player_uuid=? AND friend_uuid=?")) {
        statement.setString(1, firstPlayerId.toString());
        statement.setString(2, secondPlayerId.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next();
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to check friendship", exception);
        return false;
      }
    }
  }

  public List<Entry> friends(UUID ownerId) {
    return query("SELECT friend_uuid AS player_id, friend_name AS player_name FROM friends WHERE player_uuid=? ORDER BY friend_name", ownerId);
  }

  public List<Entry> incomingRequests(UUID ownerId) {
    return query("SELECT from_uuid AS player_id, from_name AS player_name FROM friend_requests WHERE to_uuid=? ORDER BY created_at", ownerId);
  }

  /** Stop taking writes and WAIT for the queued ones. See {@link com.sexidium.core.lib.data.WriterQueues}. */
  public void shutdown() {
    com.sexidium.core.lib.data.WriterQueues.shutdown(writer, "friend", loggerAdapter);
  }

  /** Wait for the queue without stopping the writer — the reversible half, used by a drain. */
  public void flushWrites() {
    com.sexidium.core.lib.data.WriterQueues.flush(writer, "friend", loggerAdapter);
  }

  private void insertFriend(String ownerId, String friendId, String friendName, long currentTimeMillis) throws SQLException {
    try (PreparedStatement statement = database.connection().prepareStatement(database.dialect().upsert(
        "friends",
        new String[] {"player_uuid", "friend_uuid", "friend_name", "created_at"},
        new String[] {"player_uuid", "friend_uuid"},
        new String[] {"friend_name"}))) {
      statement.setString(1, ownerId);
      statement.setString(2, friendId);
      statement.setString(3, friendName);
      statement.setLong(4, currentTimeMillis);
      statement.executeUpdate();
    }
  }

  private List<Entry> query(String sql, UUID ownerId) {
    List<Entry> entries = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        statement.setString(1, ownerId.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            entries.add(new Entry(UUID.fromString(resultSet.getString("player_id")), resultSet.getString("player_name")));
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to read friends", exception);
      }
    }
    return entries;
  }
}
