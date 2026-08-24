package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * A network-wide named lease: "exactly one node may be doing this at a time".
 *
 * <p>{@code network_leases} was created at {@code SchemaMigrator:444} with <b>zero users</b>. This is
 * what it was created for. The only holder today is {@code drain}: two nodes draining at once would
 * each hand its worlds to the other, and both would restart with players inside.</p>
 *
 * <p>Same discipline as {@link NodeRegistry#reapStaleNodes}: check-then-<em>guarded</em>-act, so two
 * nodes racing for the same lease is settled by the database and not by whichever read came back
 * first. Every write carries its own precondition in the {@code WHERE} clause, so there is no
 * read-decide-write window anywhere in this class.</p>
 *
 * <p><b>Expiry is a fact about the row, never about a timer.</b> A holder that dies mid-drain leaves
 * its lease behind; the next asker takes it once {@code expires_at} has passed. Nothing has to notice
 * the death, which is what makes this safe against the failure it exists for.</p>
 */
public final class DbNetworkLeases {

  /** The one lease this system takes today: the right to drain a node. */
  public static final String DRAIN = "drain";

  private final Database database;
  private final LoggerAdapter logger;
  /** Injected so an expiry test advances a number instead of sleeping. */
  private final LongSupplier clock;

  public DbNetworkLeases(Database database, LoggerAdapter logger) {
    this(database, logger, System::currentTimeMillis);
  }

  public DbNetworkLeases(Database database, LoggerAdapter logger, LongSupplier clock) {
    this.database = database;
    this.logger = logger;
    this.clock = clock == null ? System::currentTimeMillis : clock;
  }

  /**
   * Take {@code name} for {@code holder} until {@code now + ttlMillis}.
   *
   * <p>Succeeds when the lease is free, expired, or already this holder's — the last case makes the
   * call idempotent, which matters because a drain re-acquires on every retry of the same request.
   * Fails when a <em>different</em> live holder has it.</p>
   */
  public boolean acquire(String name, String holder, long ttlMillis) {
    if (name == null || holder == null) {
      return false;
    }
    long now = clock.getAsLong();
    synchronized (database.lock()) {
      try {
        // UPDATE first, INSERT second: after the first ever acquisition the row exists forever (it is
        // deleted only on an explicit release), so the common path is one guarded UPDATE.
        try (PreparedStatement update = database.connection().prepareStatement(
            "UPDATE network_leases SET holder = ?, acquired_at = ?, expires_at = ?"
                + " WHERE name = ? AND (holder = ? OR expires_at <= ?)")) {
          update.setString(1, holder);
          update.setLong(2, now);
          update.setLong(3, now + Math.max(0L, ttlMillis));
          update.setString(4, name);
          update.setString(5, holder);
          update.setLong(6, now);
          if (update.executeUpdate() > 0) {
            return true;
          }
        }
        // No row matched. Either there is no row at all (insert wins) or a live holder has it (the
        // primary key refuses the insert, which is the correct answer and not an error).
        try (PreparedStatement insert = database.connection().prepareStatement(
            "INSERT INTO network_leases (name, holder, priority, acquired_at, expires_at)"
                + " VALUES (?, ?, 0, ?, ?)")) {
          insert.setString(1, name);
          insert.setString(2, holder);
          insert.setLong(3, now);
          insert.setLong(4, now + Math.max(0L, ttlMillis));
          insert.executeUpdate();
          return true;
        } catch (SQLException taken) {
          // A live holder exists. Not logged as a warning: losing a lease race is the mechanism
          // working, not a fault.
          return false;
        }
      } catch (SQLException failed) {
        logger.warning("Could not acquire the '" + name + "' lease: " + failed.getMessage());
        return false;
      }
    }
  }

  /**
   * Extend a lease this node already holds. False means it was lost — expired and taken by someone
   * else — and the caller must treat itself as no longer holding it.
   *
   * <p>Deliberately NOT guarded on {@code expires_at}: a holder whose renewal was late but whose
   * lease nobody else has taken keeps it. Guarding on the expiry would drop a drain on the floor
   * because one heartbeat was slow, which is the same class of bug as evicting a healthy world
   * holder over one missed renewal.</p>
   */
  public boolean renew(String name, String holder, long ttlMillis) {
    if (name == null || holder == null) {
      return false;
    }
    long now = clock.getAsLong();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "UPDATE network_leases SET expires_at = ? WHERE name = ? AND holder = ?")) {
        ps.setLong(1, now + Math.max(0L, ttlMillis));
        ps.setString(2, name);
        ps.setString(3, holder);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not renew the '" + name + "' lease: " + failed.getMessage());
        return false;
      }
    }
  }

  /** Give a lease back. Guarded on the holder, so a successor cannot be released by its predecessor. */
  public boolean release(String name, String holder) {
    if (name == null || holder == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "DELETE FROM network_leases WHERE name = ? AND holder = ?")) {
        ps.setString(1, name);
        ps.setString(2, holder);
        return ps.executeUpdate() > 0;
      } catch (SQLException failed) {
        logger.warning("Could not release the '" + name + "' lease: " + failed.getMessage());
        return false;
      }
    }
  }

  /**
   * Who holds {@code name} right now, or empty when it is free or expired.
   *
   * <p>Read for one purpose only: naming the holder in a {@code BUSY} refusal. An operator told
   * "somebody else is draining" without being told <em>who</em> has to go and look at four containers.</p>
   */
  public Optional<String> holder(String name) {
    if (name == null) {
      return Optional.empty();
    }
    long now = clock.getAsLong();
    synchronized (database.lock()) {
      try (PreparedStatement ps = database.connection().prepareStatement(
          "SELECT holder FROM network_leases WHERE name = ? AND expires_at > ?")) {
        ps.setString(1, name);
        ps.setLong(2, now);
        try (ResultSet rs = ps.executeQuery()) {
          return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
        }
      } catch (SQLException failed) {
        logger.warning("Could not read the '" + name + "' lease: " + failed.getMessage());
        return Optional.empty();
      }
    }
  }
}
