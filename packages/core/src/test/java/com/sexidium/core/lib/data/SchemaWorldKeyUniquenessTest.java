package com.sexidium.core.lib.data;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ux_experiences_world} has to be genuinely UNIQUE, and a database that already has duplicates
 * still has to boot.
 *
 * <p>It was not unique for its whole first life: the call read {@code createIndex(..., true, ...)} and
 * that {@code true} is {@code partialNotNull}, not {@code unique} — the method never emits the token
 * UNIQUE at all. Confirmed against the live server, where {@code SHOW CREATE TABLE} rendered it
 * {@code KEY} rather than {@code UNIQUE KEY}. A restore's worst failure mode is two rows naming one
 * world, so this is the guard that matters most, and it is the one that was never there.</p>
 *
 * <p>The second half matters just as much: <b>a boot that fails is infinitely worse than an index that
 * is only an index.</b> An operator can fix duplicate rows at their leisure; nobody can play on a
 * server that will not start, and a migration that aborts takes every other subsystem's schema with
 * it.</p>
 */
class SchemaWorldKeyUniquenessTest {

  @TempDir
  Path tmp;

  private static void insertExperience(Connection connection, String id, String worldKey)
      throws SQLException {
    try (Statement statement = connection.createStatement()) {
      statement.executeUpdate("INSERT INTO experiences(id, owner_uuid, owner_name, world_key,"
          + " display_name, challenges, is_public, created_at, updated_at) VALUES ('" + id
          + "', '00000000-0000-0000-0000-000000000001', 'Ashu11a', '" + worldKey + "', 'Map', '', 0,"
          + " 1, 1)");
    }
  }

  /**
   * Whether a second row on one {@code world_key} is actually refused.
   *
   * <p>Asked by TRYING it rather than by reading {@link java.sql.DatabaseMetaData}, because the SQLite
   * driver ignores the {@code unique} argument to {@code getIndexInfo} and reports every index as
   * unique — so a metadata-based test would pass against the very bug it exists to catch. The database
   * refusing the insert is the guarantee; anything else is a description of it.</p>
   */
  private static boolean refusesDuplicates(Connection connection) throws SQLException {
    insertExperience(connection, "probe-a", "probe_world");
    try {
      insertExperience(connection, "probe-b", "probe_world");
      return false;
    } catch (SQLException refused) {
      return true;
    } finally {
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("DELETE FROM experiences WHERE id IN ('probe-a', 'probe-b')");
      }
    }
  }

  @Test
  @DisplayName("a clean database refuses a SECOND row on one world_key")
  void aCleanDatabaseIsUnique() throws Exception {
    try (Database database = new Database(new File(tmp.toFile(), "clean.db"))) {
      Connection connection = database.connection();
      assertTrue(refusesDuplicates(connection),
          "the index the whole restore leans on must actually BE unique");
      insertExperience(connection, "aaaa1111", "map_aaaa1111");
      assertThrows(SQLException.class,
          () -> insertExperience(connection, "bbbb2222", "map_aaaa1111"),
          "two rows naming one world is the failure a restore cannot survive, and it has to be an"
              + " INSERT error rather than a silent second row");
    }
  }

  @Test
  @DisplayName("a database that ALREADY has duplicates still migrates, and keeps the plain index")
  void duplicatesDoNotStopTheBoot() throws Exception {
    File file = new File(tmp.toFile(), "dirty.db");
    // Build the pre-feature shape by hand: the old, non-unique index plus two rows that violate it.
    try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
         Statement statement = raw.createStatement()) {
      statement.executeUpdate("""
          CREATE TABLE experiences (
            id TEXT PRIMARY KEY, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL,
            world_key TEXT NOT NULL, display_name TEXT NOT NULL, challenges TEXT NOT NULL,
            is_public INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL)""");
      statement.executeUpdate("CREATE INDEX ux_experiences_world ON experiences(world_key)");
      insertExperience(raw, "aaaa1111", "map_dupe");
      insertExperience(raw, "bbbb2222", "map_dupe");
    }

    try (Database database = new Database(file)) {
      Connection connection = database.connection();
      assertFalse(refusesDuplicates(connection),
          "it CANNOT be made unique over rows that already break it, and pretending otherwise would"
              + " abort the migration and leave the server with no schema at all");
      try (Statement statement = connection.createStatement();
           ResultSet rs = statement.executeQuery(
               "SELECT COUNT(*) FROM experiences WHERE world_key='map_dupe'")) {
        assertTrue(rs.next());
        assertEquals(2, rs.getInt(1), "and it must not have deleted anybody's world to make room");
      }
      // The plain index survives, so every lookup that depended on it is still an index lookup.
      try (ResultSet rs = connection.getMetaData()
          .getIndexInfo(null, null, "experiences", false, false)) {
        boolean found = false;
        while (rs.next()) {
          found |= "ux_experiences_world".equalsIgnoreCase(rs.getString("INDEX_NAME"));
        }
        assertTrue(found, "dropping it would turn byWorld back into a table scan on the entry path");
      }
    }
  }

  @Test
  @DisplayName("migrating twice does not churn or lose the unique index")
  void migratingAgainIsANoOp() throws Exception {
    File file = new File(tmp.toFile(), "twice.db");
    try (Database database = new Database(file)) {
      assertTrue(refusesDuplicates(database.connection()));
    }
    try (Database database = new Database(file)) {
      assertTrue(refusesDuplicates(database.connection()),
          "a second boot must not quietly demote the index it created on the first");
    }
  }
}
