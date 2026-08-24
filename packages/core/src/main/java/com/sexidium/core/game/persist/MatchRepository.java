package com.sexidium.core.game.persist;

import com.sexidium.core.game.GameState;
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

public final class MatchRepository {
  private final LoggerAdapter loggerAdapter;
  private final Database database;
  /**
   * Which node these rows belong to.
   *
   * <p>{@code matches} had no node column at all, so {@code loadAll()} read the whole shared table and
   * every node imported every other node's live matches — indexing a worker's players as pending on
   * the lobby, and then, ten minutes after every boot, deleting every other node's live rows as
   * "stale". Standalone keeps writing the empty string, which is its own scope.</p>
   */
  private volatile String nodeId = "";
  private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
    Thread writerThread = new Thread(runnable, "Sexidium-Match-DB");
    writerThread.setDaemon(true);
    return writerThread;
  });

  public MatchRepository(LoggerAdapter loggerAdapter, Database database) {
    this.loggerAdapter = loggerAdapter;
    this.database = database;
  }

  /** Scopes every read and write to this node. Called once, at startup, from the core. */
  public void setNodeId(String nodeId) {
    this.nodeId = nodeId == null ? "" : nodeId;
  }

  public void saveAsync(MatchSnapshot matchSnapshot) {
    if (matchSnapshot == null) {
      return;
    }
    writer.execute(() -> {
      try {
        save(matchSnapshot);
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to persist match " + matchSnapshot.matchId, exception);
      }
    });
  }

  public void deleteAsync(UUID matchId) {
    if (matchId == null) {
      return;
    }
    writer.execute(() -> {
      try {
        delete(matchId);
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to delete match " + matchId, exception);
      }
    });
  }

  public void saveBlocking(MatchSnapshot matchSnapshot) {
    if (matchSnapshot == null) {
      return;
    }
    try {
      save(matchSnapshot);
    } catch (SQLException exception) {
      loggerAdapter.warning("Failed to persist match " + matchSnapshot.matchId, exception);
    }
  }

  /** Every match THIS node was running. Never another node's — see {@link #nodeId}. */
  public List<MatchSnapshot> loadAll() {
    List<MatchSnapshot> matchSnapshots = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT id, mode_id, mode_args, world_name, state, data, created_at FROM matches"
              + " WHERE node_id = ?")) {
        statement.setString(1, nodeId);
        ResultSet resultSet = statement.executeQuery();
        while (resultSet.next()) {
          MatchSnapshot matchSnapshot = new MatchSnapshot(UUID.fromString(resultSet.getString("id")), resultSet.getString("mode_id"));
          String modeArgs = resultSet.getString("mode_args");
          if (modeArgs != null && !modeArgs.isBlank()) {
            for (String modeArg : modeArgs.split(",")) {
              if (!modeArg.isBlank()) {
                matchSnapshot.modeArgs.add(modeArg);
              }
            }
          }
          matchSnapshot.worldName = resultSet.getString("world_name");
          matchSnapshot.state = parseState(resultSet.getString("state"));
          matchSnapshot.data.putAll(Props.decode(resultSet.getString("data")));
          matchSnapshot.createdAt = resultSet.getLong("created_at");
          matchSnapshots.add(matchSnapshot);
        }
        resultSet.close();
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to load persisted matches", exception);
        return matchSnapshots;
      }
      for (MatchSnapshot matchSnapshot : matchSnapshots) {
        loadPlayers(matchSnapshot);
      }
    }
    return matchSnapshots;
  }

  /** Stop taking writes and WAIT for the queued ones. See {@link com.sexidium.core.lib.data.WriterQueues}. */
  public void shutdown() {
    com.sexidium.core.lib.data.WriterQueues.shutdown(writer, "match", loggerAdapter);
  }

  /** Wait for the queue without stopping the writer — the reversible half, used by a drain. */
  public void flushWrites() {
    com.sexidium.core.lib.data.WriterQueues.flush(writer, "match", loggerAdapter);
  }

  private void save(MatchSnapshot matchSnapshot) throws SQLException {
    synchronized (database.lock()) {
      long currentTimeMillis = System.currentTimeMillis();
      try (PreparedStatement statement = database.connection().prepareStatement(database.dialect().upsert(
          "matches",
          new String[] {"id", "node_id", "mode_id", "mode_args", "world_name", "state", "data", "created_at", "updated_at"},
          new String[] {"id"},
          new String[] {"node_id", "mode_id", "mode_args", "world_name", "state", "data", "updated_at"}))) {
        statement.setString(1, matchSnapshot.matchId.toString());
        statement.setString(2, nodeId);
        statement.setString(3, matchSnapshot.modeId);
        statement.setString(4, String.join(",", matchSnapshot.modeArgs));
        statement.setString(5, matchSnapshot.worldName);
        statement.setString(6, matchSnapshot.state.name());
        statement.setString(7, matchSnapshot.data.encode());
        statement.setLong(8, matchSnapshot.createdAt > 0 ? matchSnapshot.createdAt : currentTimeMillis);
        statement.setLong(9, currentTimeMillis);
        statement.executeUpdate();
      }
      try (PreparedStatement deleteStatement = database.connection().prepareStatement(
          "DELETE FROM match_players WHERE match_id = ?")) {
        deleteStatement.setString(1, matchSnapshot.matchId.toString());
        deleteStatement.executeUpdate();
      }
      try (PreparedStatement statement = database.connection().prepareStatement("""
          INSERT INTO match_players(match_id, uuid, name, role, status, world, x, y, z, yaw, pitch,
                                    gamemode, health, food, inv, data)
          VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""")) {
        for (PlayerSnapshot playerSnapshot : matchSnapshot.players) {
          statement.setString(1, matchSnapshot.matchId.toString());
          statement.setString(2, playerSnapshot.playerId.toString());
          statement.setString(3, playerSnapshot.playerName);
          statement.setString(4, playerSnapshot.role);
          statement.setString(5, playerSnapshot.status.name());
          statement.setString(6, playerSnapshot.worldName);
          statement.setDouble(7, playerSnapshot.coordinateX);
          statement.setDouble(8, playerSnapshot.coordinateY);
          statement.setDouble(9, playerSnapshot.coordinateZ);
          statement.setFloat(10, playerSnapshot.yaw);
          statement.setFloat(11, playerSnapshot.pitch);
          statement.setString(12, playerSnapshot.gameMode);
          statement.setDouble(13, playerSnapshot.health);
          statement.setInt(14, playerSnapshot.foodLevel);
          statement.setString(15, playerSnapshot.inventoryPayload);
          statement.setString(16, playerSnapshot.data.encode());
          statement.addBatch();
        }
        statement.executeBatch();
      }
    }
  }

  private void delete(UUID matchId) throws SQLException {
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement("DELETE FROM match_players WHERE match_id = ?")) {
        statement.setString(1, matchId.toString());
        statement.executeUpdate();
      }
      try (PreparedStatement statement = database.connection().prepareStatement("DELETE FROM matches WHERE id = ?")) {
        statement.setString(1, matchId.toString());
        statement.executeUpdate();
      }
    }
  }

  private void loadPlayers(MatchSnapshot matchSnapshot) {
    try (PreparedStatement statement = database.connection().prepareStatement("SELECT * FROM match_players WHERE match_id = ?")) {
      statement.setString(1, matchSnapshot.matchId.toString());
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          PlayerSnapshot playerSnapshot = new PlayerSnapshot(
              UUID.fromString(resultSet.getString("uuid")),
              resultSet.getString("name"),
              resultSet.getString("role")
          );
          playerSnapshot.status = "DISCONNECTED".equals(resultSet.getString("status"))
              ? PlayerSnapshot.Status.DISCONNECTED
              : PlayerSnapshot.Status.CONNECTED;
          playerSnapshot.worldName = resultSet.getString("world");
          playerSnapshot.coordinateX = resultSet.getDouble("x");
          playerSnapshot.coordinateY = resultSet.getDouble("y");
          playerSnapshot.coordinateZ = resultSet.getDouble("z");
          playerSnapshot.yaw = resultSet.getFloat("yaw");
          playerSnapshot.pitch = resultSet.getFloat("pitch");
          playerSnapshot.gameMode = resultSet.getString("gamemode");
          playerSnapshot.health = resultSet.getDouble("health");
          playerSnapshot.foodLevel = resultSet.getInt("food");
          playerSnapshot.inventoryPayload = resultSet.getString("inv");
          playerSnapshot.data.putAll(Props.decode(resultSet.getString("data")));
          matchSnapshot.players.add(playerSnapshot);
        }
      }
    } catch (SQLException exception) {
      loggerAdapter.warning("Failed to load players for match " + matchSnapshot.matchId, exception);
    }
  }

  private GameState parseState(String rawState) {
    try {
      return GameState.valueOf(rawState);
    } catch (RuntimeException exception) {
      return GameState.RUNNING;
    }
  }
}
