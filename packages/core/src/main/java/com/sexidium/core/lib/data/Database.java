package com.sexidium.core.lib.data;

import java.io.File;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ServiceLoader;

/**
 * Thin JDBC wrapper backing SQLite (embedded, default), MySQL, or PostgreSQL — chosen per
 * {@link DatabaseConfig} (i.e. per server). A single connection is guarded by {@link #lock()} since
 * the game thread, the DB executor and the HTTP bridge thread all share it; that serialized,
 * single-connection model is unchanged from the original SQLite design (no regression), and for a
 * shared "global" MySQL/PostgreSQL each server simply holds one connection while the database engine
 * arbitrates across servers.
 *
 * <p>Unlike an embedded SQLite file, a networked connection can be dropped server-side (e.g. MySQL
 * {@code wait_timeout}); {@link #connection()} therefore validates and transparently reopens the
 * connection for non-SQLite backends. Always call it while holding {@link #lock()}.
 */
public final class Database implements AutoCloseable {

  /** Default seconds-worth of millis a successful {@code isValid} answer is trusted for. */
  public static final long DEFAULT_VALIDATE_INTERVAL_MILLIS = 5_000L;

  private final Object lock = new Object();
  private final DatabaseConfig config;
  private final SqlDialect dialect;
  private final File file;
  private final long validateIntervalMillis;
  private Connection conn;
  /** When the connection last answered {@code isValid}. 0 means "never, so check". */
  private long validatedAt;

  /** Backward-compatible SQLite constructor (used by tests and the file-based default path). */
  public Database(File file) throws SQLException {
    this(DatabaseConfig.sqlite(file));
  }

  public Database(DatabaseConfig config) throws SQLException {
    this(config, DEFAULT_VALIDATE_INTERVAL_MILLIS);
  }

  public Database(DatabaseConfig config, long validateIntervalMillis) throws SQLException {
    this.config = config;
    this.dialect = config.dialect();
    this.file = config.sqliteFile();
    this.validateIntervalMillis = Math.max(0L, validateIntervalMillis);
    ensureDriverAvailable(dialect);
    synchronized (lock) {
      this.conn = open();
      SchemaMigrator.migrate(conn, dialect);
    }
  }

  /**
   * Find the JDBC driver for a URL using OUR classloader, without going through DriverManager.
   *
   * <p>DriverManager is unusable from a plugin. It scans for drivers exactly once, lazily, with
   * whichever classloader is current at that moment, and then filters results by whether the CALLER
   * can load them. Inside Velocity (and Paper) this plugin lives in an isolated classloader that is
   * created long after some other component has already triggered that one-time scan, so a driver
   * shipped in our own jar is never discovered — the symptom is "No JDBC driver for POSTGRES is on
   * the classpath" while the class is demonstrably present in the jar.</p>
   *
   * <p>Loading the service ourselves and calling {@link Driver#connect} directly sidesteps all of
   * it, and works identically whether the driver was relocated or not.</p>
   */
  private static Driver resolveDriver(String url, SqlDialect dialect) throws SQLException {
    for (Driver candidate : ServiceLoader.load(Driver.class, Database.class.getClassLoader())) {
      try {
        if (candidate.acceptsURL(url)) {
          return candidate;
        }
      } catch (SQLException ignored) {
        // A driver that cannot even parse the URL is simply not the one we want.
      }
    }
    // Fall back to the JVM-wide registry, which is the right answer when the driver came from the
    // system classpath rather than from this jar.
    try {
      return DriverManager.getDriver(url);
    } catch (SQLException noDriver) {
      throw new SQLException("No JDBC driver for " + dialect
          + " is available (expected something registering " + dialect.driverClass()
          + "; runtime library load failed?)", noDriver);
    }
  }

  /**
   * Confirm a usable JDBC driver is present, without naming its class.
   *
   * <p>Deliberately NOT {@code Class.forName(dialect.driverClass())}. The Velocity module has to
   * shade its JDBC drivers — Velocity has no {@code libraries:} loader — and shaded libraries in a
   * Velocity plugin must be relocated, because its PluginClassLoader falls back to sibling plugin
   * loaders and would otherwise hand our {@code org.postgresql} to another plugin, or take theirs.
   * Relocation rewrites the driver's package, so a hardcoded class name stops resolving.</p>
   *
   * <p>Every modern JDBC driver registers itself with {@link DriverManager} through
   * {@code META-INF/services/java.sql.Driver}, which shadow's {@code mergeServiceFiles()} preserves
   * across relocation. Asking DriverManager to resolve the URL therefore works relocated or not, and
   * the error message stays as specific as it was.</p>
   */
  private static void ensureDriverAvailable(SqlDialect dialect) throws SQLException {
    resolveDriver(dialect.probeUrl(), dialect);
  }

  private Connection open() throws SQLException {
    // Through the resolved driver, not DriverManager.getConnection: the same classloader
    // filtering that hides the driver would hide the connection.
    Driver driver = resolveDriver(config.jdbcUrl(), dialect);
    Connection opened = driver.connect(config.jdbcUrl(), config.connectionProperties());
    if (opened == null) {
      throw new SQLException("Driver " + driver.getClass().getName()
          + " refused the URL for " + dialect);
    }
    dialect.applyConnectionSetup(opened);
    return opened;
  }

  public Object lock() {
    return lock;
  }

  public SqlDialect dialect() {
    return dialect;
  }

  public Connection connection() {
    // SQLite's embedded connection never drops; networked backends can be closed server-side, so
    // validate + lazily reopen. Callers always hold lock(), so mutating conn here is safe.
    if (dialect != SqlDialect.SQLITE) {
      try {
        long now = System.currentTimeMillis();
        if (conn == null || conn.isClosed()) {
          closeQuietly();
          conn = open();
          validatedAt = now;
        } else if (revalidationDue(validatedAt, now, validateIntervalMillis)) {
          // isValid() is a round trip to the database server, and it was being made on EVERY
          // connection() call -- inside the one global monitor every subsystem queues behind. The
          // entry path alone makes six to ten of them per click. Trusting a recent "yes" for a few
          // seconds costs, at worst, one dropped statement that the next call reopens through.
          if (!conn.isValid(2)) {
            closeQuietly();
            conn = open();
          }
          validatedAt = now;
        }
      } catch (SQLException e) {
        throw new IllegalStateException("Failed to reconnect to the database", e);
      }
    }
    return conn;
  }

  /**
   * Whether a cached "the connection is valid" answer has gone stale. Pure, and package-visible, so
   * the caching rule can be tested without a networked database to disconnect.
   */
  static boolean revalidationDue(long lastValidatedAt, long now, long intervalMillis) {
    if (intervalMillis <= 0L) {
      return true; // interval 0 restores the old validate-every-time behaviour
    }
    if (lastValidatedAt <= 0L) {
      return true; // the sentinel for "this connection has never answered"
    }
    // now - lastValidatedAt, never the other way round: a clock that steps BACKWARDS must revalidate
    // rather than silently trust a timestamp from the future for the rest of the process's life.
    long elapsed = now - lastValidatedAt;
    return elapsed < 0L || elapsed >= intervalMillis;
  }

  /** The SQLite file, or {@code null} for networked backends (MySQL/PostgreSQL). */
  public File file() {
    return file;
  }

  private void closeQuietly() {
    try {
      if (conn != null && !conn.isClosed()) {
        conn.close();
      }
    } catch (SQLException ignored) {
      // closing best-effort
    }
  }

  @Override
  public void close() {
    synchronized (lock) {
      closeQuietly();
    }
  }
}
