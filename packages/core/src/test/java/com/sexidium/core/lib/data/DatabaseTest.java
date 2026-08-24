package com.sexidium.core.lib.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {

    @Test
    void opensAndMigratesFreshSqliteDatabase() throws Exception {
        Path dir = Files.createTempDirectory("sexidium-db-test");
        try (Database db = new Database(dir.resolve("sexidium.db").toFile())) {
            try (Statement st = db.connection().createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                assertTrue(rs.next());
                assertEquals("delete", rs.getString(1).toLowerCase());
            }

            try (PreparedStatement ps = db.connection().prepareStatement("""
                    INSERT INTO auth_codes(code_hash, minecraft_uuid, minecraft_name, created_at, expires_at)
                    VALUES(?, ?, ?, ?, ?)""")) {
                ps.setString(1, "hash");
                ps.setString(2, "uuid");
                ps.setString(3, "Steve");
                ps.setLong(4, 1L);
                ps.setLong(5, 2L);
                ps.executeUpdate();
            }
        }
    }
}
