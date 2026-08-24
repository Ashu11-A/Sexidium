package com.sexidium.core.data;

import com.sexidium.core.auth.AuthService;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.lib.data.LeaderboardEntry;
import com.sexidium.core.lib.data.Profile;
import com.sexidium.core.data.RankClass;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public final class RankService implements RankAwardPort {
  private final ConfigurationAdapter configurationAdapter;
  private final LoggerAdapter loggerAdapter;
  private final MessageAdapter messageAdapter;
  private final Database database;
  private final AuthService authService;
  private final BooleanSupplier authEnabled;
  private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
    Thread writerThread = new Thread(runnable, "Sexidium-DB");
    writerThread.setDaemon(true);
    return writerThread;
  });

  /** Notified (on the DB writer thread) after a player's points change, so the bridge can push a live rank update. */
  public interface RankChangeListener {
    void onChange(String minecraftUuid, String minecraftName, String discordUserId);
  }

  private volatile RankChangeListener rankChangeListener = (uuid, name, discordUserId) -> { };

  public void setRankChangeListener(RankChangeListener listener) {
    this.rankChangeListener = listener == null ? (uuid, name, discordUserId) -> { } : listener;
  }

  public RankService(
      ConfigurationAdapter configurationAdapter,
      LoggerAdapter loggerAdapter,
      MessageAdapter messageAdapter,
      Database database,
      AuthService authService,
      BooleanSupplier authEnabled
  ) {
    this.configurationAdapter = configurationAdapter;
    this.loggerAdapter = loggerAdapter;
    this.messageAdapter = messageAdapter;
    this.database = database;
    this.authService = authService;
    this.authEnabled = authEnabled == null ? () -> true : authEnabled;
  }

  public int levelFor(int points) {
    return Math.max(0, points / pointsPerLevel());
  }

  @Override
  public LeaderboardEntry lookup(String playerName) {
    if (playerName == null || playerName.isBlank()) {
      return null;
    }
    return aggregateByName(playerName);
  }

  @Override
  public void awardParticipation(PlayerAdapter playerAdapter) {
    award(playerAdapter, configurationAdapter.getInt("ranks.award.participate", 10), 0, 0, 1);
  }

  @Override
  public void awardKill(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    award(playerAdapter, configurationAdapter.getInt("ranks.award.kill", 15), 1, 0, 0);
  }

  @Override
  public void awardWin(PlayerAdapter playerAdapter, String modeId) {
    award(playerAdapter, winPoints(modeId), 0, 1, 0);
  }

  /**
   * Awards time-in-world points for the open-ended experience modes. Converts whole seconds into points
   * at the {@code ranks.award.experience-per-minute} rate (integer-truncated per call), counting neither
   * a game nor a win/kill. A no-op when the rate or the elapsed time resolves to zero points.
   */
  @Override
  public void awardPlaytime(PlayerAdapter playerAdapter, long secondsElapsed) {
    if (playerAdapter == null || secondsElapsed <= 0L) {
      return;
    }
    int perMinute = Math.max(0, configurationAdapter.getInt("ranks.award.experience-per-minute", 2));
    long points = secondsElapsed * perMinute / 60L;
    if (points <= 0L) {
      return;
    }
    award(playerAdapter, (int) Math.min(points, Integer.MAX_VALUE), 0, 0, 0);
  }

  public void award(PlayerAdapter playerAdapter, int pointsDelta, int killsDelta, int winsDelta, int gamesDelta) {
    if (playerAdapter == null) {
      return;
    }
    UUID playerId = playerAdapter.uniqueId();
    String playerName = playerAdapter.name();
    // Always resolve the Discord link best-effort so a linked player's alts keep aggregating, even when
    // a link is no longer REQUIRED to earn points. Only block (and optionally remind) when the operator
    // still mandates a linked account via auth.require-linked-for-points.
    String discordUserId = null;
    try {
      discordUserId = authService == null ? null : authService.linkedDiscordId(playerId.toString());
    } catch (SQLException exception) {
      loggerAdapter.warning("Failed to check auth link for " + playerName, exception);
    }
    if (requiresLinkedAccount() && (discordUserId == null || discordUserId.isBlank())) {
      if (configurationAdapter.getBoolean("auth.remind-unlinked-on-award", true) && messageAdapter != null) {
        messageAdapter.send(playerAdapter, LocalizedText.of(MessageKey.AUTH_POINTS_REQUIRE_LINK));
      }
      return;
    }
    String linkedDiscordUserId = discordUserId;
    writer.execute(() -> {
      try {
        upsert(playerId.toString(), playerName, linkedDiscordUserId, pointsDelta, killsDelta, winsDelta, gamesDelta);
        try {
          rankChangeListener.onChange(playerId.toString(), playerName, linkedDiscordUserId);
        } catch (RuntimeException listenerFailure) {
          loggerAdapter.warning("Rank change listener failed for " + playerName, listenerFailure);
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to award rank points to " + playerName, exception);
      }
    });
  }

  public List<Profile> top(int limit) {
    List<Profile> profiles = new ArrayList<>();
    synchronized (database.lock()) {
      String whereClause = requiresLinkedAccount() ? "WHERE discord_user_id IS NOT NULL " : "";
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT uuid,name,discord_user_id,points,level,wins,kills,games FROM players "
              + whereClause + "ORDER BY points DESC, level DESC LIMIT ?")) {
        statement.setInt(1, Math.max(1, Math.min(100, limit)));
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            profiles.add(read(resultSet));
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to read leaderboard", exception);
      }
    }
    return profiles;
  }

  public Profile byName(String playerName) {
    synchronized (database.lock()) {
      String linkedFilter = requiresLinkedAccount() ? " AND discord_user_id IS NOT NULL" : "";
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT uuid,name,discord_user_id,points,level,wins,kills,games FROM players "
              + "WHERE LOWER(name) = LOWER(?)" + linkedFilter + " LIMIT 1")) {
        statement.setString(1, playerName);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return read(resultSet);
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to read profile for " + playerName, exception);
      }
    }
    return null;
  }

  /**
   * Leaderboard aggregated by Discord account: every player linked to the same Discord user is summed
   * into one row (points/wins/kills/games), so a user who plays under several Minecraft names sees a
   * single combined score. Unlinked players are excluded (they never accrue points while
   * require-linked-for-points is on). Ordered by total points descending.
   */
  public List<LeaderboardEntry> topAggregated(int limit) {
    int safeLimit = Math.max(1, Math.min(100, limit));
    List<LeaderboardEntry> entries = aggregate("WHERE discord_user_id IS NOT NULL", null);
    return entries.size() > safeLimit ? new ArrayList<>(entries.subList(0, safeLimit)) : entries;
  }

  /**
   * Aggregated totals for the Discord account that owns the given Minecraft name (summing all of that
   * account's names). Falls back to the single player's row when the name is not linked to Discord.
   * Returns null when the name is unknown.
   */
  public LeaderboardEntry aggregateByName(String playerName) {
    String discordUserId;
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT discord_user_id FROM players WHERE LOWER(name) = LOWER(?) LIMIT 1")) {
        statement.setString(1, playerName);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          discordUserId = resultSet.getString("discord_user_id");
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to read profile for " + playerName, exception);
        return null;
      }
    }
    if (discordUserId == null || discordUserId.isBlank()) {
      Profile single = byName(playerName);
      if (single == null) {
        return null;
      }
      return entryOf(null, List.of(new NameScore(single.name(), single.points())),
          single.points(), single.wins(), single.kills(), single.games());
    }
    List<LeaderboardEntry> matches = aggregate("WHERE discord_user_id = ?", discordUserId);
    return matches.isEmpty() ? null : matches.get(0);
  }

  /** Aggregated totals for a Discord account (summed across all its linked Minecraft names). */
  public LeaderboardEntry aggregateByDiscordId(String discordUserId) {
    if (discordUserId == null || discordUserId.isBlank()) {
      return null;
    }
    List<LeaderboardEntry> matches = aggregate("WHERE discord_user_id = ?", discordUserId);
    return matches.isEmpty() ? null : matches.get(0);
  }

  private List<LeaderboardEntry> aggregate(String whereClause, String discordUserIdBinding) {
    Map<String, List<NameScore>> namesByDiscord = new LinkedHashMap<>();
    Map<String, int[]> totalsByDiscord = new LinkedHashMap<>();
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT discord_user_id,name,points,wins,kills,games FROM players "
              + whereClause + " ORDER BY points DESC, level DESC")) {
        if (discordUserIdBinding != null) {
          statement.setString(1, discordUserIdBinding);
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            String discordUserId = resultSet.getString("discord_user_id");
            String key = discordUserId == null ? "" : discordUserId;
            int points = resultSet.getInt("points");
            namesByDiscord.computeIfAbsent(key, ignored -> new ArrayList<>())
                .add(new NameScore(resultSet.getString("name"), points));
            int[] totals = totalsByDiscord.computeIfAbsent(key, ignored -> new int[4]);
            totals[0] += points;
            totals[1] += resultSet.getInt("wins");
            totals[2] += resultSet.getInt("kills");
            totals[3] += resultSet.getInt("games");
          }
        }
      } catch (SQLException exception) {
        loggerAdapter.warning("Failed to aggregate leaderboard", exception);
      }
    }
    List<LeaderboardEntry> entries = new ArrayList<>();
    for (Map.Entry<String, List<NameScore>> mapEntry : namesByDiscord.entrySet()) {
      String discordUserId = mapEntry.getKey().isEmpty() ? null : mapEntry.getKey();
      int[] totals = totalsByDiscord.get(mapEntry.getKey());
      entries.add(entryOf(discordUserId, mapEntry.getValue(), totals[0], totals[1], totals[2], totals[3]));
    }
    entries.sort((left, right) -> Integer.compare(right.points(), left.points()));
    return entries;
  }

  private LeaderboardEntry entryOf(String discordUserId, List<NameScore> names, int points, int wins, int kills, int games) {
    List<String> orderedNames = new ArrayList<>();
    for (NameScore nameScore : names) {
      orderedNames.add(nameScore.name());
    }
    int level = levelFor(points);
    RankClass rankClass = RankClass.forLevel(level);
    String representative = orderedNames.isEmpty() ? "" : orderedNames.get(0);
    return new LeaderboardEntry(
        discordUserId, representative, orderedNames, points, level, wins, kills, games,
        rankClass.displayName(), rankClass.hexColor());
  }

  private record NameScore(String name, int points) {
  }

  /** Stop taking writes and WAIT for the queued ones. See {@link com.sexidium.core.lib.data.WriterQueues}. */
  public void shutdown() {
    com.sexidium.core.lib.data.WriterQueues.shutdown(writer, "rank", loggerAdapter);
  }

  /** Wait for the queue without stopping the writer — the reversible half, used by a drain. */
  public void flushWrites() {
    com.sexidium.core.lib.data.WriterQueues.flush(writer, "rank", loggerAdapter);
  }

  @Override
  public int pointsPerLevel() {
    return Math.max(1, configurationAdapter.getInt("ranks.points-per-level", 100));
  }

  private int winPoints(String modeId) {
    String configPath = switch (modeId) {
      case "race" -> "ranks.award.race-win";
      case "gather" -> "ranks.award.duel-win";
      case "tntwar" -> "ranks.award.tntwar-win";
      case "combat" -> "ranks.award.combat-win";
      case "fugitive" -> "ranks.award.fugitive-win";
      default -> "ranks.award.participate";
    };
    // An unmatched mode (e.g. a future one) falls back to the participation amount and ITS default, so it
    // never silently earns the 100-point win default; real win modes keep the 100 default.
    int fallback = "ranks.award.participate".equals(configPath)
        ? configurationAdapter.getInt("ranks.award.participate", 10)
        : 100;
    return configurationAdapter.getInt(configPath, fallback);
  }

  private boolean requiresLinkedAccount() {
    return authEnabled.getAsBoolean() && configurationAdapter.getBoolean("auth.require-linked-for-points", false);
  }

  private void upsert(
      String playerId,
      String playerName,
      String discordUserId,
      int pointsDelta,
      int killsDelta,
      int winsDelta,
      int gamesDelta
  ) throws SQLException {
    synchronized (database.lock()) {
      // Accumulate-on-conflict: the counters are added to, not replaced, so this cannot use the generic
      // SqlDialect.upsert helper. SQLite and Postgres share the ON CONFLICT/excluded form; MySQL uses
      // ON DUPLICATE KEY UPDATE with VALUES(). Both bind the same 12 parameters in the same order.
      String sql = database.dialect().isMysql()
          ? """
            INSERT INTO players(uuid, name, discord_user_id, points, level, wins, kills, games, updated_at)
            VALUES(?, ?, ?, ?, 0, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
              name=VALUES(name),
              discord_user_id=COALESCE(VALUES(discord_user_id), discord_user_id),
              points=points + ?,
              wins=wins + ?,
              kills=kills + ?,
              games=games + ?,
              updated_at=VALUES(updated_at)"""
          : """
            INSERT INTO players(uuid, name, discord_user_id, points, level, wins, kills, games, updated_at)
            VALUES(?, ?, ?, ?, 0, ?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
              name=excluded.name,
              discord_user_id=COALESCE(excluded.discord_user_id, players.discord_user_id),
              points=players.points + ?,
              wins=players.wins + ?,
              kills=players.kills + ?,
              games=players.games + ?,
              updated_at=excluded.updated_at""";
      long currentTimeMillis = System.currentTimeMillis();
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        statement.setString(1, playerId);
        statement.setString(2, playerName);
        statement.setString(3, discordUserId);
        statement.setInt(4, pointsDelta);
        statement.setInt(5, winsDelta);
        statement.setInt(6, killsDelta);
        statement.setInt(7, gamesDelta);
        statement.setLong(8, currentTimeMillis);
        statement.setInt(9, pointsDelta);
        statement.setInt(10, winsDelta);
        statement.setInt(11, killsDelta);
        statement.setInt(12, gamesDelta);
        statement.executeUpdate();
      }
      try (PreparedStatement statement = database.connection().prepareStatement(
          "UPDATE players SET level = points / ? WHERE uuid = ?")) {
        statement.setInt(1, pointsPerLevel());
        statement.setString(2, playerId);
        statement.executeUpdate();
      }
    }
  }

  private Profile read(ResultSet resultSet) throws SQLException {
    return new Profile(
        resultSet.getString("uuid"),
        resultSet.getString("name"),
        resultSet.getString("discord_user_id"),
        resultSet.getInt("points"),
        resultSet.getInt("level"),
        resultSet.getInt("wins"),
        resultSet.getInt("kills"),
        resultSet.getInt("games")
    );
  }
}
