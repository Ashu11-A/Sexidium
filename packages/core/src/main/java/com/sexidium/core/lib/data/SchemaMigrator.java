package com.sexidium.core.lib.data;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Applies additive schema migrations across all three backends (SQLite, MySQL, PostgreSQL). The DDL is
 * built through {@link SqlDialect} type tokens so a single definition emits portable SQL: TEXT keys
 * become {@code VARCHAR(191)} on the networked dialects, {@code AUTOINCREMENT} becomes the right
 * identity column, {@code REAL} becomes {@code DOUBLE}/{@code DOUBLE PRECISION}, and partial / case
 * -insensitive indexes degrade where unsupported. Table/column existence is probed via JDBC
 * {@link DatabaseMetaData} rather than SQLite-only {@code PRAGMA}/{@code sqlite_master}.
 *
 * <p>The one-off legacy rebuild ({@code discord_accounts} one-to-one → one-to-many) only ever applied
 * to pre-existing SQLite files, so it is gated to the SQLite dialect; a fresh MySQL/PostgreSQL database
 * is created directly in the current shape.
 */
public final class SchemaMigrator {

  private SchemaMigrator() {}

  /**
   * Serialize the whole migration across simultaneously booting servers.
   *
   * <p>Tolerating duplicate-object errors (see createIndex and addColumnIfMissing) keeps a race from
   * being fatal, but it is a net rather than a design: it relies on every future DDL statement being
   * individually idempotent. Taking the engine's own advisory lock means only one node emits DDL at
   * a time and the others simply find the work already done.</p>
   *
   * <p>Best effort by design — a database that cannot grant the lock still migrates, falling back on
   * that per-statement tolerance, because refusing to boot would be a worse failure than a race.</p>
   */
  private static boolean acquireMigrationLock(Connection conn, SqlDialect dialect) {
    try (Statement st = conn.createStatement()) {
      switch (dialect) {
        case MYSQL -> st.executeQuery("SELECT GET_LOCK('sexidium_schema', 60)").close();
        case POSTGRES -> st.executeQuery("SELECT pg_advisory_lock(3153018)").close();
        // SQLite serializes writers with a file lock already; there is nothing to coordinate.
        case SQLITE -> {
          return false;
        }
      }
      return true;
    } catch (SQLException unavailable) {
      return false;
    }
  }

  private static void releaseMigrationLock(Connection conn, SqlDialect dialect) {
    try (Statement st = conn.createStatement()) {
      switch (dialect) {
        case MYSQL -> st.executeQuery("SELECT RELEASE_LOCK('sexidium_schema')").close();
        case POSTGRES -> st.executeQuery("SELECT pg_advisory_unlock(3153018)").close();
        case SQLITE -> { }
      }
    } catch (SQLException ignored) {
      // The lock is released by the connection closing anyway.
    }
  }

  static void migrate(Connection conn, SqlDialect dialect) throws SQLException {
    boolean locked = acquireMigrationLock(conn, dialect);
    try {
      migrateLocked(conn, dialect);
    } finally {
      if (locked) {
        releaseMigrationLock(conn, dialect);
      }
    }
  }

  private static void migrateLocked(Connection conn, SqlDialect dialect) throws SQLException {
    String keyText = dialect.keyText();
    String text = dialect.text();
    String intType = dialect.intType();
    String real = dialect.realType();

    try (Statement st = conn.createStatement()) {
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS players (
            uuid            %1$s PRIMARY KEY,
            name            %1$s NOT NULL,
            discord_user_id %1$s,
            points          %2$s NOT NULL DEFAULT 0,
            level           %2$s NOT NULL DEFAULT 0,
            wins            %2$s NOT NULL DEFAULT 0,
            kills           %2$s NOT NULL DEFAULT 0,
            games           %2$s NOT NULL DEFAULT 0,
            updated_at      %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));
      // One Discord account links to MANY Minecraft names (one-to-many): minecraft_uuid is the primary
      // key (each MC account links once) while discord_user_id is a non-unique column that repeats
      // across a user's alts. Legacy SQLite databases (discord_user_id as PRIMARY KEY) are rebuilt below.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS discord_accounts (
            minecraft_uuid       %1$s PRIMARY KEY,
            discord_user_id      %1$s NOT NULL,
            minecraft_name       %1$s NOT NULL,
            discord_username     %2$s,
            discord_global_name  %2$s,
            discord_avatar       %2$s,
            created_at           %3$s NOT NULL,
            updated_at           %3$s NOT NULL
          )""".formatted(keyText, text, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS auth_codes (
            code_hash                 %1$s PRIMARY KEY,
            minecraft_uuid            %1$s NOT NULL,
            minecraft_name            %1$s NOT NULL,
            created_at                %2$s NOT NULL,
            expires_at                %2$s NOT NULL,
            consumed_at               %2$s,
            consumed_discord_user_id  %1$s
          )""".formatted(keyText, intType));
      // The identity ANCHOR. An offline uuid is derived from the name -- case-sensitively -- so `Foo`
      // and `foo` were two accounts with two point balances, and a premium login for the same human
      // arrives with a Mojang uuid that matches nothing anywhere. One row per LOWERCASE name closes
      // both: identity_id is decided ONCE (adopted from an existing players row when there is one, so
      // no data moves) and every connection is rewritten to it at the proxy's GameProfileRequestEvent.
      //
      // name_lower is the PRIMARY KEY rather than a unique index because createIndex() below cannot
      // emit UNIQUE on every dialect -- so uniqueness has to be part of the table, not next to it.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS player_identities (
            name_lower         %1$s PRIMARY KEY,
            identity_id        %1$s NOT NULL,
            display_name       %1$s NOT NULL,
            premium_uuid       %1$s,
            bedrock_xuid       %1$s,
            account_type       %1$s NOT NULL DEFAULT 'cracked',
            premium_state      %1$s NOT NULL DEFAULT 'unknown',
            premium_checked_at %2$s NOT NULL DEFAULT 0,
            first_seen_at      %2$s NOT NULL DEFAULT 0,
            last_seen_at       %2$s NOT NULL DEFAULT 0,
            updated_at         %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS command_queue (
            id           %1$s,
            command      %2$s NOT NULL,
            requested_by %3$s,
            status       %3$s NOT NULL DEFAULT 'PENDING',
            created_at   %4$s NOT NULL
          )""".formatted(dialect.autoIncrementPrimaryKey(), text, keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS matches (
            id          %1$s PRIMARY KEY,
            node_id     %1$s NOT NULL DEFAULT '',
            mode_id     %1$s NOT NULL,
            mode_args   %2$s,
            world_name  %1$s,
            state       %1$s NOT NULL,
            data        %2$s,
            created_at  %3$s NOT NULL,
            updated_at  %3$s NOT NULL
          )""".formatted(keyText, text, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS match_players (
            match_id    %1$s NOT NULL,
            uuid        %1$s NOT NULL,
            name        %1$s NOT NULL,
            role        %1$s,
            status      %1$s NOT NULL,
            world       %1$s,
            x           %2$s,
            y           %2$s,
            z           %2$s,
            yaw         %2$s,
            pitch       %2$s,
            gamemode    %1$s,
            health      %2$s,
            food        %3$s,
            inv         %4$s,
            data        %4$s,
            PRIMARY KEY (match_id, uuid)
          )""".formatted(keyText, real, intType, text));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS friends (
            player_uuid %1$s NOT NULL,
            friend_uuid %1$s NOT NULL,
            friend_name %1$s,
            created_at  %2$s NOT NULL,
            PRIMARY KEY (player_uuid, friend_uuid)
          )""".formatted(keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS friend_requests (
            from_uuid   %1$s NOT NULL,
            from_name   %1$s,
            to_uuid     %1$s NOT NULL,
            created_at  %2$s NOT NULL,
            PRIMARY KEY (from_uuid, to_uuid)
          )""".formatted(keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS experiences (
            id           %1$s PRIMARY KEY,
            owner_uuid   %1$s NOT NULL,
            owner_name   %1$s NOT NULL,
            world_key    %1$s NOT NULL,
            display_name %1$s NOT NULL,
            challenges   %2$s NOT NULL,
            is_public    %3$s NOT NULL DEFAULT 0,
            created_at   %3$s NOT NULL,
            updated_at   %3$s NOT NULL
          )""".formatted(keyText, text, intType));
    }

    createIndex(conn, dialect, "idx_experiences_owner", "experiences", "owner_uuid", false, null);
    // UNIQUE, and that is the point: with one spelling per world a divergent key becomes an INSERT
    // failure instead of a second silent row for a world that already exists somewhere else.
    //
    // It was NOT unique for its whole first life. This call read `createIndex(..., true, "world_key")`
    // and the `true` is `partialNotNull`, not `unique` -- the method never emits the token UNIQUE at
    // all. Verified against the live database: `SHOW CREATE TABLE` rendered it `KEY`, not `UNIQUE KEY`.
    // Restore leans its whole safety story on one row per world_key, so this is the guard that matters
    // most, and it is the one that was never there.
    createUniqueIndex(conn, dialect, "ux_experiences_world", "experiences", "world_key");
    createIndex(conn, dialect, "idx_match_players_uuid", "match_players", "uuid", false, null);
    // Every node used to read the WHOLE table and import every other node's live matches — and then
    // delete them ten minutes after every boot. A match belongs to the node running it.
    createIndex(conn, dialect, "idx_matches_node", "matches", "node_id", false, null);
    createIndex(conn, dialect, "idx_friend_requests_to", "friend_requests", "to_uuid", false, null);
    // One Discord may own MANY players, so this index must NOT be unique. Older SQLite files carry a
    // legacy UNIQUE index of the same name — drop it (SQLite only; fresh networked DBs never had it).
    if (dialect.isSqlite()) {
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("DROP INDEX IF EXISTS idx_players_discord_user_id");
      }
    }
    createIndex(conn, dialect, "idx_players_discord_user_id", "players", "discord_user_id", true, "discord_user_id");
    createIndex(conn, dialect, "idx_players_name_nocase", "players",
        dialect.caseInsensitiveIndexExpression("name"), false, null);
    createIndex(conn, dialect, "idx_auth_codes_lookup", "auth_codes", "code_hash, expires_at, consumed_at", false, null);
    createIndex(conn, dialect, "idx_player_identities_identity", "player_identities", "identity_id", false, null);
    createIndex(conn, dialect, "idx_player_identities_premium", "player_identities", "premium_uuid", true, "premium_uuid");
    createIndex(conn, dialect, "idx_player_identities_recheck", "player_identities",
        "premium_state, premium_checked_at", false, null);

    if (dialect.isSqlite()) {
      migrateLegacyDiscordAccounts(conn);
    }
    createIndex(conn, dialect, "idx_discord_accounts_user", "discord_accounts", "discord_user_id", false, null);

    addColumnIfMissing(conn, "players", "discord_user_id", keyText);
    addColumnIfMissing(conn, "discord_accounts", "discord_username", text);
    addColumnIfMissing(conn, "discord_accounts", "discord_global_name", text);
    addColumnIfMissing(conn, "discord_accounts", "discord_avatar", text);
    // Shared, persistent gameplay state for an experience's challenges (e.g. the Double Drops
    // multiplier, the Randomizer drop mappings): one encoded Props blob per experience.
    addColumnIfMissing(conn, "experiences", "challenge_state", text);
    // Discriminates a normal composable experience ("experience") from a persistent Chaos world
    // ("chaos"); older rows read null and default to "experience".
    addColumnIfMissing(conn, "experiences", "mode", text);
    // The chosen MAP TYPE of an experience (normal / nether / end / one of the generated SkyBlock maps).
    // Older rows read null and resolve to "normal" — or to their map-generating challenge, if they run one.
    addColumnIfMissing(conn, "experiences", "world_type", text);
    // Whether deaths keep items + XP in this experience. Older rows read NULL, which means ENABLED —
    // the behaviour every experience had before the toggle existed.
    addColumnIfMissing(conn, "experiences", "keep_inventory", intType);
    // HARDCORE: hardcore hearts, hard difficulty, and one death ends the world for good. "dead" records
    // that it HAS been lost — a one-way flag, since the point of hardcore is that it cannot be undone.
    // Older rows read NULL for both, which means a normal, living experience.
    addColumnIfMissing(conn, "experiences", "hardcore", intType);
    addColumnIfMissing(conn, "experiences", "dead", intType);
    // The experience this row is a BACKUP of, or NULL for a normal experience. A backup is a real
    // experience — its own id, its own world_key, its own folders — so nothing at read time is a
    // special case; this column is only what lets the owner see where a copy came from, what caps the
    // number of copies per experience, and what keeps backups out of the per-player experience cap.
    addColumnIfMissing(conn, "experiences", "backup_of", keyText);
    // Partial where the dialect allows it: the overwhelming majority of rows are NOT backups, and the
    // only queries are "the backups OF this experience" and "count them".
    createIndex(conn, dialect, "idx_experiences_backup_of", "experiences", "backup_of", true, "backup_of");
    // The network a /auth code was requested from, so consuming the code can mint that network's
    // first session immediately -- which removes a reconnect from the very first join.
    addColumnIfMissing(conn, "auth_codes", "ip_hash", keyText);
    createExperiencePlayersTable(conn, dialect);
    createEconomyTables(conn, dialect);
    createNetworkTables(conn, dialect);
  }

  /**
   * The money ledger. A SEPARATE table from {@code players} on purpose, and the reason is not tidiness:
   *
   * <ul>
   *   <li>{@code players} rows are created lazily by {@code RankService.upsert}, only when points are
   *       first awarded. A player who has never finished a match has no row — and money has to exist
   *       for everybody from their first join, or the starting balance is a lie.</li>
   *   <li>{@code players} is READ AGGREGATED BY DISCORD ACCOUNT ({@code RankService.aggregate}). Money
   *       must never be aggregated across alts: two names summed into one pot means a player can pay
   *       themselves.</li>
   *   <li>Vault's shared accounts and account deletion need an {@code is_player} flag and an
   *       owner/member model that has no business inside the progression table.</li>
   * </ul>
   *
   * <p>{@code balance} is MINOR UNITS (cents) in an intType column — never a decimal, never a REAL.
   * {@link SqlDialect} has no decimal token, and SQLite (the default backend, and the one every test
   * runs against) has no real DECIMAL type, so a decimal column would round through a double on
   * exactly the backend nobody would notice it on. Same call, same reason, as {@code network_nodes.tps}
   * being scaled ×100.</p>
   *
   * <p>The indexes are created HERE and not in the index block of {@code migrateLocked}: that block
   * runs before this method is called, and an index on a table that does not exist yet is an error on
   * every dialect.</p>
   */
  private static void createEconomyTables(Connection conn, SqlDialect dialect) throws SQLException {
    String keyText = dialect.keyText();
    String intType = dialect.intType();
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS economy_accounts (
            account_id  %1$s PRIMARY KEY,
            name        %1$s NOT NULL,
            is_player   %2$s NOT NULL DEFAULT 1,
            balance     %2$s NOT NULL DEFAULT 0,
            created_at  %2$s NOT NULL DEFAULT 0,
            updated_at  %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));
      // Why a ledger and not just a balance: "an admin says a player's money vanished" is otherwise
      // unanswerable. amount is SIGNED minor units; balance_after is what the row settled at, so a
      // gap in the chain is visible without recomputing anything.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS economy_ledger (
            id            %1$s,
            account_id    %2$s NOT NULL,
            counterparty  %2$s,
            op            %2$s NOT NULL,
            amount        %3$s NOT NULL,
            balance_after %3$s NOT NULL,
            reason        %2$s,
            actor         %2$s,
            node_id       %2$s,
            created_at    %3$s NOT NULL DEFAULT 0
          )""".formatted(dialect.autoIncrementPrimaryKey(), keyText, intType));
    }

    // /balance <name> and every offline /pay target resolve by NAME, so it must be a seek.
    //
    // Deliberately NOT unique. Names are not unique OVER TIME — a player renames, somebody else takes
    // the old name — and createUniqueIndex logs SEVERE and degrades to a plain index forever after the
    // first collision, which would turn an ordinary rename into a permanent boot-time complaint.
    createIndex(conn, dialect, "idx_economy_accounts_name", "economy_accounts",
        dialect.caseInsensitiveIndexExpression("name"), false, null);
    // /baltop is ORDER BY balance DESC LIMIT n and nothing else.
    createIndex(conn, dialect, "idx_economy_accounts_balance", "economy_accounts", "balance", false, null);
    createIndex(conn, dialect, "idx_economy_ledger_account", "economy_ledger",
        "account_id, created_at", false, null);
    // The retention sweep reads created_at across every account.
    createIndex(conn, dialect, "idx_economy_ledger_created", "economy_ledger", "created_at", false, null);
  }

  /**
   * Tables that exist only to let several server processes agree on shared state.
   *
   * <p>Created unconditionally, including on SQLite. They are inert on a standalone server — the
   * Local* implementations of every network port short-circuit before touching JDBC — but creating
   * them anyway means one migrator, one schema, and a standalone server that can be promoted into a
   * node without a migration step.</p>
   *
   * <p>Deliberately NOT here: cooldowns, HUD state, per-tick game state, the world-lease registry.
   * Those are node-local by nature — the player is on exactly one node and their cooldown lives
   * there — and the failure mode of "make everything network-global" is a DB write on a hot path.</p>
   */
  private static void createNetworkTables(Connection conn, SqlDialect dialect) throws SQLException {
    String keyText = dialect.keyText();
    String text = dialect.text();
    String intType = dialect.intType();

    try (Statement st = conn.createStatement()) {
      // Node registry + liveness. `epoch` is bumped on every boot and is the FENCING token: it stops a
      // node that crashed and came back from adopting rows written after its death.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS network_nodes (
            node_id        %1$s PRIMARY KEY,
            display_name   %1$s,
            role           %1$s,
            capabilities   %3$s,
            address        %1$s,
            port           %2$s NOT NULL DEFAULT 0,
            state          %1$s NOT NULL,
            players        %2$s NOT NULL DEFAULT 0,
            max_players    %2$s NOT NULL DEFAULT 0,
            worlds         %2$s NOT NULL DEFAULT 0,
            max_worlds     %2$s NOT NULL DEFAULT 0,
            protocol       %2$s NOT NULL DEFAULT 0,
            plugin_version %1$s,
            epoch          %2$s NOT NULL DEFAULT 0,
            started_at     %2$s NOT NULL DEFAULT 0,
            heartbeat_at   %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType, text));

      // The durable message bus. Topics are fire-and-forget but PERSISTED, so a node that was down
      // (or paused in GC) still sees what it missed, and "why did that not propagate" is answerable
      // with a SELECT. A socket mesh cannot offer either.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS network_messages (
            id          %1$s,
            topic       %2$s NOT NULL,
            message_key %2$s,
            payload     %3$s,
            origin_node %2$s NOT NULL,
            created_at  %4$s NOT NULL DEFAULT 0
          )""".formatted(dialect.autoIncrementPrimaryKey(), keyText, text, intType));

      // Which node physically holds a world folder.
      //
      // node_id is a durable ASSIGNMENT and is authoritative for routing; lease_expires_at is only a
      // load mutual-exclusion lease. Expiry marks the placement ORPHANED and must NEVER reassign it:
      // the chunks are on that machine's disk, and reassigning makes the new node see no world on
      // disk and generate a fresh empty one over the player's saved map.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS world_placements (
            world_key        %1$s PRIMARY KEY,
            experience_id    %1$s,
            generation       %2$s NOT NULL DEFAULT 0,
            kind             %1$s NOT NULL,
            node_id          %1$s NOT NULL,
            node_epoch       %2$s NOT NULL DEFAULT 0,
            fence            %2$s NOT NULL DEFAULT 0,
            owner_uuid       %1$s,
            state            %1$s NOT NULL,
            players          %2$s NOT NULL DEFAULT 0,
            lease_expires_at %2$s NOT NULL DEFAULT 0,
            updated_at       %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));

      // ONE owner action against ONE world, and its answer.
      //
      // The bus cannot be the response channel of an owner action: it is broadcast-only with no ack
      // and no dedupe, and DbNetworkBus.start() seeds its cursor to MAX(id) -- so a DELETE published
      // while the worker holding that world was restarting is lost forever and the owner is told it
      // worked. This row is the request AND the acknowledgement, exactly as node_drains is for the
      // drain protocol: the conditional UPDATE that takes it PENDING -> RUNNING is what makes a
      // replayed message a no-op, and a target node that was down drains its own pending rows at
      // boot instead of missing them.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS experience_commands (
            id            %1$s PRIMARY KEY,
            experience_id %1$s NOT NULL,
            world_key     %1$s,
            op            %1$s NOT NULL,
            args          %3$s,
            requested_by  %1$s NOT NULL,
            target_node   %1$s NOT NULL,
            state         %1$s NOT NULL,
            detail        %3$s,
            deadline      %2$s NOT NULL DEFAULT 0,
            created_at    %2$s NOT NULL DEFAULT 0,
            updated_at    %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType, text));

      // A minigame roster being assembled on one node from players arriving from others.
      //
      // The row exists BEFORE the player does, which is the whole point: the worker must be able to
      // hold a match open for someone who is still in flight, and must know at a deadline whether
      // enough of them made it. node_epoch fences a worker that died and came back mid-handoff.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS match_handoffs (
            match_id    %1$s NOT NULL,
            player_uuid %1$s NOT NULL,
            node_id     %1$s NOT NULL,
            node_epoch  %2$s NOT NULL DEFAULT 0,
            mode_id     %1$s NOT NULL,
            world_key   %1$s,
            state       %1$s NOT NULL,
            deadline    %2$s NOT NULL DEFAULT 0,
            created_at  %2$s NOT NULL DEFAULT 0,
            PRIMARY KEY (match_id, player_uuid)
          )""".formatted(keyText, intType));

      // Party / lobby membership, shared so a group survives its members being on different nodes.
      //
      // `lobby_id` is the LEADER's uuid, matching the in-memory model, and `node_id` records where
      // the group is currently coordinated from so a restart of that node can be detected.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS lobby_groups (
            lobby_id    %1$s PRIMARY KEY,
            leader_uuid %1$s NOT NULL,
            mode_id     %1$s,
            state       %1$s NOT NULL,
            visibility  %1$s,
            team_count  %2$s NOT NULL DEFAULT 0,
            node_id     %1$s,
            version     %2$s NOT NULL DEFAULT 0,
            created_at  %2$s NOT NULL DEFAULT 0,
            updated_at  %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS lobby_members (
            lobby_id      %1$s NOT NULL,
            player_uuid   %1$s NOT NULL,
            player_name   %1$s,
            selected_team %2$s NOT NULL DEFAULT -1,
            joined_at     %2$s NOT NULL DEFAULT 0,
            PRIMARY KEY (lobby_id, player_uuid)
          )""".formatted(keyText, intType));
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS lobby_queue_tickets (
            lobby_id             %1$s PRIMARY KEY,
            mode_id              %1$s NOT NULL,
            size                 %2$s NOT NULL DEFAULT 0,
            enqueued_at          %2$s NOT NULL DEFAULT 0,
            countdown_started_at %2$s NOT NULL DEFAULT 0,
            state                %1$s NOT NULL
          )""".formatted(keyText, intType));

      // ONE transfer of one player, end to end.
      //
      // The primary key is player_uuid, and that IS the invariant: a player cannot have two live
      // transfers however many times anything asks. What this replaces was two uncorrelated rows --
      // a match_handoffs row with a 120s TTL and a player_routes row with a 30s TTL -- with no token
      // joining them, no target check at the destination, no idempotency, no acknowledgement and no
      // attempt bound. The observed result was one player transferred eight times in 45 seconds with
      // nothing in the system aware it was happening.
      //
      // claimed_by/claim_expires_at make the proxy half claim-then-complete rather than
      // select-then-delete, so a proxy that dies mid-transfer leaves a ticket another one reclaims.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS player_transfers (
            player_uuid      %1$s PRIMARY KEY,
            token            %1$s NOT NULL,
            source_node      %1$s,
            target_node      %1$s NOT NULL,
            target_epoch     %2$s NOT NULL DEFAULT 0,
            reason           %1$s NOT NULL,
            world_key        %1$s,
            state            %1$s NOT NULL,
            attempts         %2$s NOT NULL DEFAULT 0,
            claimed_by       %1$s,
            claim_expires_at %2$s NOT NULL DEFAULT 0,
            created_at       %2$s NOT NULL DEFAULT 0,
            expires_at       %2$s NOT NULL DEFAULT 0,
            updated_at       %2$s NOT NULL DEFAULT 0,
            detail           %3$s
          )""".formatted(keyText, intType, text));

      // The loop breaker's rolling window. Per (player, node), because a party of six entering one
      // experience is six players with one transfer each -- which no sane bound refuses.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS transfer_attempts (
            player_uuid   %1$s NOT NULL,
            target_node   %1$s NOT NULL,
            window_start  %2$s NOT NULL DEFAULT 0,
            attempts      %2$s NOT NULL DEFAULT 0,
            blocked_until %2$s NOT NULL DEFAULT 0,
            PRIMARY KEY (player_uuid, target_node)
          )""".formatted(keyText, intType));

      // Singleton election (the Discord bot, the matchmaker, the reaper). Compare-and-swap on
      // expires_at; `priority` lets a preferred holder reclaim at the next renewal boundary.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS network_leases (
            name        %1$s PRIMARY KEY,
            holder      %1$s NOT NULL,
            priority    %2$s NOT NULL DEFAULT 0,
            acquired_at %2$s NOT NULL DEFAULT 0,
            expires_at  %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType));

      // A node's rolling-update drain, as a ROW rather than as another `network_nodes.state` value.
      //
      // `state` is read as an opaque string by the planner, the proxy's backend directory and the
      // liveness check, so a new value there would be silently misread by any node that predates it —
      // and DRAINING already means exactly the right thing (excluded from placement, still routable,
      // still heartbeating), so the drain keeps using it for the whole window and records its PROGRESS
      // here. A node that has never heard of this table simply never selects from it.
      //
      // (node_epoch, fence) is the same guard `world_placements` uses: a node that restarted mid-drain
      // cannot mutate its predecessor's row, it reads it and replaces it.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS node_drains (
            node_id      %1$s PRIMARY KEY,
            node_epoch   %2$s NOT NULL DEFAULT 0,
            fence        %2$s NOT NULL DEFAULT 0,
            phase        %1$s NOT NULL,
            reason       %1$s,
            requested_by %1$s,
            deadline     %2$s NOT NULL DEFAULT 0,
            worlds_total %2$s NOT NULL DEFAULT 0,
            worlds_left  %2$s NOT NULL DEFAULT 0,
            players_left %2$s NOT NULL DEFAULT 0,
            matches_left %2$s NOT NULL DEFAULT 0,
            detail       %3$s,
            started_at   %2$s NOT NULL DEFAULT 0,
            updated_at   %2$s NOT NULL DEFAULT 0
          )""".formatted(keyText, intType, text));

      // "This name was approved from this network recently."
      //
      // There is deliberately NO client-side token -- a token would need a client mod, which excludes
      // every vanilla player this project exists for -- so there is nothing here to steal in transit.
      // The binding IS (identity, ip), which is why an IP alone authorises nothing (one Brazilian
      // CGNAT address is a whole neighbourhood) and why the TTL is hours rather than forever.
      //
      // Raw IPs are never stored: ip_hash is SHA-256(pepper||ip) and ip_prefix is a redacted display
      // string. absolute_expires_at is the cap a sliding renewal can never push past.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS auth_sessions (
            identity_id         %1$s NOT NULL,
            ip_hash             %1$s NOT NULL,
            session_id          %1$s NOT NULL,
            name_lower          %1$s NOT NULL,
            ip_prefix           %1$s,
            device              %1$s NOT NULL DEFAULT 'java',
            origin_node         %1$s,
            approved_by         %1$s,
            created_at          %2$s NOT NULL DEFAULT 0,
            last_seen_at        %2$s NOT NULL DEFAULT 0,
            expires_at          %2$s NOT NULL DEFAULT 0,
            absolute_expires_at %2$s NOT NULL DEFAULT 0,
            revoked_at          %2$s,
            PRIMARY KEY (identity_id, ip_hash)
          )""".formatted(keyText, intType));

      // One pending "is this you?" push, end to end. The row is written by the node holding the
      // connection (the proxy, or a backend during a hold), claimed by the BOT_HOST node's courier,
      // decided by the bot through the RPC bridge, and observed again by the waiting node. Same
      // claim-then-complete shape as player_transfers, for the same reason: a courier that dies
      // mid-notification must free the row rather than swallow it.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS auth_requests (
            request_id       %1$s PRIMARY KEY,
            identity_id      %1$s NOT NULL,
            name_lower       %1$s NOT NULL,
            display_name     %1$s NOT NULL,
            discord_user_id  %1$s NOT NULL,
            ip_hash          %1$s NOT NULL,
            ip_prefix        %1$s,
            kind             %1$s NOT NULL,
            state            %1$s NOT NULL,
            hold             %2$s NOT NULL DEFAULT 0,
            waiting_node     %1$s,
            claimed_by       %1$s,
            claim_expires_at %2$s NOT NULL DEFAULT 0,
            notified_at      %2$s,
            decided_at       %2$s,
            decided_by       %1$s,
            attempts         %2$s NOT NULL DEFAULT 0,
            created_at       %2$s NOT NULL DEFAULT 0,
            expires_at       %2$s NOT NULL DEFAULT 0,
            detail           %3$s
          )""".formatted(keyText, intType, text));

      // The durable half of pressing Deny. Per (identity, network) and never per network alone:
      // blocking an address outright would punish everyone who shares it with whoever tried.
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS auth_ip_blocks (
            identity_id %1$s NOT NULL,
            ip_hash     %1$s NOT NULL,
            reason      %1$s,
            created_at  %2$s NOT NULL DEFAULT 0,
            expires_at  %2$s NOT NULL DEFAULT 0,
            PRIMARY KEY (identity_id, ip_hash)
          )""".formatted(keyText, intType));
    }

    // Telemetry a rolling update reads to decide whether a node is the build it asked for, and
    // whether it is well enough to be handed a new world. All additive, all defaulted: a node running
    // the previous build writes NULL/0 here and every reader treats that as "unknown", never as "bad".
    //
    // build_sha is the sha256 of the loaded plugin jar, so an orchestrator can join straight against
    // the artifact it staged; plugin_version is the race-free identity and is what a roll should gate
    // on (the jar can be swapped between JVM start and plugin enable).
    addColumnIfMissing(conn, "network_nodes", "build_sha", keyText);
    // 16 hex chars over the sorted content-code list: equal digests mean an identical challenge+mode
    // surface, which is the O(1) form of "may this node take that world over?".
    addColumnIfMissing(conn, "network_nodes", "content_digest", keyText);
    // The codes themselves, comma-joined and sorted (~700 bytes today). TEXT, not keyText: this grows
    // with the catalog and a VARCHAR(191) would silently truncate the answer into a wrong one.
    addColumnIfMissing(conn, "network_nodes", "content_codes", text);
    // Integer-scaled ×100 rather than a float column, because SqlDialect already has to straddle
    // sqlite/mysql/postgres and their DOUBLE behaviour is the one thing that differs between them.
    // -1 means "this node cannot answer" (the proxy has no tick loop), which is NOT the same as 0.
    addColumnIfMissing(conn, "network_nodes", "tps", intType + " DEFAULT -1");
    addColumnIfMissing(conn, "network_nodes", "mspt", intType + " DEFAULT -1");
    addColumnIfMissing(conn, "network_nodes", "heap_used_mb", intType + " DEFAULT 0");
    addColumnIfMissing(conn, "network_nodes", "heap_max_mb", intType + " DEFAULT 0");

    // The bus is read as "everything newer than my cursor", so the index is on id order; retention
    // sweeps read created_at.
    createIndex(conn, dialect, "idx_network_messages_created", "network_messages", "created_at", false, null);
    createIndex(conn, dialect, "idx_network_messages_topic", "network_messages", "topic", false, null);
    createIndex(conn, dialect, "idx_wp_node_state", "world_placements", "node_id, state", false, null);
    createIndex(conn, dialect, "idx_world_placements_owner", "world_placements", "owner_uuid", false, null);
    // Generation allocation is a SELECT MAX(generation) over one lineage, not a directory walk of the
    // whole shared tree (which was up to 3000 stats on the tick a player died).
    createIndex(conn, dialect, "idx_wp_lineage", "world_placements",
        "experience_id, generation", false, null);
    // The reaper reads "expired leases", which must be an index seek and not a table scan at 30 workers.
    createIndex(conn, dialect, "idx_wp_lease", "world_placements",
        "state, lease_expires_at", false, null);
    createIndex(conn, dialect, "idx_network_nodes_state", "network_nodes", "state", false, null);
    // "What was asked of me and never answered?" -- read on every boot and on the recovery tick, so
    // it must be a seek and not a scan of every command the fleet has ever run.
    createIndex(conn, dialect, "idx_experience_commands_target", "experience_commands",
        "target_node, state", false, null);
    createIndex(conn, dialect, "ux_pt_token", "player_transfers", "token", true, "token");
    createIndex(conn, dialect, "idx_pt_claim", "player_transfers",
        "state, claim_expires_at, created_at", false, null);
    createIndex(conn, dialect, "idx_pt_target", "player_transfers", "target_node, state", false, null);
    // The reverse index the in-memory model calls playerToLobby: "which group is this player in?"
    createIndex(conn, dialect, "idx_lobby_members_player", "lobby_members", "player_uuid", false, null);
    createIndex(conn, dialect, "idx_match_handoffs_player", "match_handoffs", "player_uuid", false, null);
    createIndex(conn, dialect, "idx_match_handoffs_deadline", "match_handoffs", "deadline", false, null);
    // The arrival lookup is (player, node, newest-first) now that it is addressed, so index it that way.
    createIndex(conn, dialect, "idx_mh_arrival", "match_handoffs",
        "player_uuid, node_id, deadline", false, null);
    createIndex(conn, dialect, "idx_lobby_queue_mode", "lobby_queue_tickets", "mode_id, enqueued_at", false, null);
    // rememberedWorldOf reads "newest row for this player" and had no index at all behind it.
    createIndex(conn, dialect, "idx_ep_player", "experience_players", "player_uuid, updated_at", false, null);
    // The gate reads one session by its primary key; the sweeper reads expiries; /sessions reads by
    // identity, newest first; a revoke reads by session_id.
    createIndex(conn, dialect, "idx_auth_sessions_expiry", "auth_sessions", "expires_at", false, null);
    createIndex(conn, dialect, "idx_auth_sessions_identity", "auth_sessions",
        "identity_id, last_seen_at", false, null);
    createIndex(conn, dialect, "idx_auth_sessions_session", "auth_sessions", "session_id", false, null);
    createIndex(conn, dialect, "idx_auth_requests_claim", "auth_requests",
        "state, claim_expires_at, created_at", false, null);
    createIndex(conn, dialect, "idx_auth_requests_identity", "auth_requests",
        "identity_id, state, created_at", false, null);
    createIndex(conn, dialect, "idx_auth_requests_waiting", "auth_requests", "waiting_node, state", false, null);
    createIndex(conn, dialect, "idx_auth_requests_expiry", "auth_requests", "expires_at", false, null);
    createIndex(conn, dialect, "idx_auth_ip_blocks_expiry", "auth_ip_blocks", "expires_at", false, null);
  }

  /**
   * Per-(experience, player) saved location + inventory so a player who leaves an experience returns to
   * the exact spot with their items. Independent of the live match lifecycle, so it survives the match
   * ending and a server restart.
   */
  private static void createExperiencePlayersTable(Connection conn, SqlDialect dialect) throws SQLException {
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("""
          CREATE TABLE IF NOT EXISTS experience_players (
            experience_id %1$s NOT NULL,
            player_uuid   %1$s NOT NULL,
            world_key     %1$s,
            x             %2$s,
            y             %2$s,
            z             %2$s,
            yaw           %2$s,
            pitch         %2$s,
            gamemode      %1$s,
            health        %2$s,
            food          %3$s,
            inv           %4$s,
            updated_at    %3$s NOT NULL,
            PRIMARY KEY (experience_id, player_uuid)
          )""".formatted(dialect.keyText(), dialect.realType(), dialect.intType(), dialect.text()));
    }
  }

  /**
   * Rebuilds {@code discord_accounts} from the legacy SQLite schema (where {@code discord_user_id} was
   * the PRIMARY KEY — one Discord ↔ one Minecraft) to the current one-to-many schema keyed by
   * {@code minecraft_uuid}. Detected by the absence of the {@code discord_username} metadata column.
   * SQLite only; the rename/drop sequencing is SQLite-specific and a fresh networked DB is already
   * in the new shape.
   */
  private static void migrateLegacyDiscordAccounts(Connection conn) throws SQLException {
    if (!tableExists(conn, "discord_accounts") || hasColumn(conn, "discord_accounts", "discord_username")) {
      return;
    }
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("ALTER TABLE discord_accounts RENAME TO discord_accounts_legacy");
      st.executeUpdate("""
          CREATE TABLE discord_accounts (
            minecraft_uuid       TEXT PRIMARY KEY,
            discord_user_id      TEXT NOT NULL,
            minecraft_name       TEXT NOT NULL,
            discord_username     TEXT,
            discord_global_name  TEXT,
            discord_avatar       TEXT,
            created_at           INTEGER NOT NULL,
            updated_at           INTEGER NOT NULL
          )""");
      st.executeUpdate("""
          INSERT INTO discord_accounts(minecraft_uuid, discord_user_id, minecraft_name, created_at, updated_at)
          SELECT minecraft_uuid, discord_user_id, minecraft_name, created_at, updated_at
          FROM discord_accounts_legacy""");
      st.executeUpdate("DROP TABLE discord_accounts_legacy");
    }
  }

  /**
   * Creates an index if it does not already exist, portably. SQLite/Postgres use
   * {@code CREATE INDEX IF NOT EXISTS} (and a partial {@code WHERE ... IS NOT NULL} where requested);
   * MySQL has neither, so existence is probed via {@link DatabaseMetaData} and the partial predicate is
   * dropped (a plain index).
   */
  private static void createIndex(Connection conn, SqlDialect dialect, String indexName, String table,
                                  String columnsExpression, boolean partialNotNull, String partialColumn)
      throws SQLException {
    boolean partial = partialNotNull && dialect.supportsPartialIndex();
    String where = partial ? " WHERE " + partialColumn + " IS NOT NULL" : "";
    if (dialect.supportsCreateIndexIfNotExists()) {
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + table
            + "(" + columnsExpression + ")" + where);
      }
      return;
    }
    if (indexExists(conn, table, indexName)) {
      return;
    }
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("CREATE INDEX " + indexName + " ON " + table + "(" + columnsExpression + ")");
    } catch (SQLException e) {
      // MySQL error 1061 = duplicate key name: benign if a concurrent/previous run already created it.
      if (e.getErrorCode() != 1061) {
        throw e;
      }
    }
  }

  /**
   * Creates a genuinely UNIQUE index on {@code column} — but only once it has proved there is nothing
   * in the table that would make it a lie.
   *
   * <p>Order matters and is the whole design. The duplicates are counted FIRST; with none, the unique
   * index is created and from then on a second row on one value is an INSERT failure instead of a
   * silent corruption. With any, the duplicates are named at SEVERE and the plain index is created (or
   * left) instead — because <b>a boot that fails is infinitely worse than an index that is only an
   * index</b>. An operator can fix duplicate rows; nobody can play on a server that will not start.</p>
   *
   * <p>The unique index is also created UNCONDITIONALLY-tolerantly: a concurrent node may have created
   * it a microsecond earlier, and every one of those errors means the index now exists, which is what
   * was asked for. A failure that is NOT benign (a duplicate that appeared between the count and the
   * DDL) degrades to the plain index by the same rule.</p>
   *
   * <p>Note for MySQL/MariaDB: {@link SqlDialect#supportsPartialIndex()} is false there, so a partial
   * {@code WHERE ... IS NOT NULL} clause would be silently dropped. This index carries none — every
   * experience has a world.</p>
   */
  private static void createUniqueIndex(Connection conn, SqlDialect dialect, String indexName,
                                        String table, String column) throws SQLException {
    if (!dialect.supportsCreateIndexIfNotExists() && indexIsUnique(conn, table, indexName)) {
      // MySQL's driver reports NON_UNIQUE honestly, so this saves a needless DROP + CREATE. SQLite's
      // does NOT -- Xerial's getIndexInfo ignores the `unique` argument and reports every index as
      // unique -- so trusting it there would skip the upgrade for ever on exactly the databases that
      // still carry the old plain index. Those dialects re-create it below instead, which is cheap:
      // both support CREATE INDEX IF NOT EXISTS, and this table holds tens of rows, not millions.
      return;
    }
    java.util.List<String> duplicates = duplicateValues(conn, table, column);
    if (!duplicates.isEmpty()) {
      java.util.logging.Logger.getLogger("Sexidium").severe(
          "Cannot make " + indexName + " UNIQUE: " + table + "." + column
              + " already holds duplicate values " + duplicates
              + ". Leaving the plain index in place and continuing to boot. Two rows naming one world"
              + " is the failure a restore cannot survive -- fix these rows by hand, then restart to"
              + " get the real guard.");
      createIndex(conn, dialect, indexName, table, column, false, null);
      return;
    }
    if (dialect.supportsCreateIndexIfNotExists()) {
      try (Statement st = conn.createStatement()) {
        // A pre-existing NON-unique index of this name would keep CREATE UNIQUE INDEX IF NOT EXISTS
        // from doing anything at all, so the old one goes first. Safe: it is redundant either way.
        st.executeUpdate("DROP INDEX IF EXISTS " + indexName);
        st.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS " + indexName + " ON " + table
            + "(" + column + ")");
        return;
      } catch (SQLException raced) {
        java.util.logging.Logger.getLogger("Sexidium").warning(
            "Could not create the unique index " + indexName + " (" + raced.getMessage()
                + "); falling back to a plain index.");
        createIndex(conn, dialect, indexName, table, column, false, null);
        return;
      }
    }
    if (indexExists(conn, table, indexName)) {
      try (Statement st = conn.createStatement()) {
        st.executeUpdate("DROP INDEX " + indexName + " ON " + table);
      } catch (SQLException stubborn) {
        java.util.logging.Logger.getLogger("Sexidium").warning(
            "Could not drop the non-unique " + indexName + " before replacing it: "
                + stubborn.getMessage());
        return;
      }
    }
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("CREATE UNIQUE INDEX " + indexName + " ON " + table + "(" + column + ")");
    } catch (SQLException e) {
      if (e.getErrorCode() == 1061) {
        return; // duplicate key name: another node won the race and the index exists
      }
      java.util.logging.Logger.getLogger("Sexidium").warning(
          "Could not create the unique index " + indexName + " (" + e.getMessage()
              + "); falling back to a plain index.");
      createIndex(conn, dialect, indexName, table, column, false, null);
    }
  }

  /** Up to ten values that appear more than once in {@code table.column}. Empty when the table is clean. */
  private static java.util.List<String> duplicateValues(Connection conn, String table, String column)
      throws SQLException {
    java.util.List<String> duplicates = new java.util.ArrayList<>();
    if (!tableExists(conn, table) || !hasColumn(conn, table, column)) {
      return duplicates;
    }
    try (Statement st = conn.createStatement();
         ResultSet rs = st.executeQuery("SELECT " + column + " FROM " + table + " WHERE " + column
             + " IS NOT NULL GROUP BY " + column + " HAVING COUNT(*) > 1 LIMIT 10")) {
      while (rs.next()) {
        duplicates.add(rs.getString(1));
      }
    }
    return duplicates;
  }

  private static boolean indexIsUnique(Connection conn, String table, String indexName)
      throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    for (String candidate : tableNameCandidates(table)) {
      try (ResultSet rs = metaData.getIndexInfo(conn.getCatalog(), conn.getSchema(), candidate, true, false)) {
        while (rs.next()) {
          String name = rs.getString("INDEX_NAME");
          if (name != null && name.equalsIgnoreCase(indexName)) {
            return true;
          }
        }
      } catch (SQLException ignored) {
        // try the next name casing
      }
    }
    return false;
  }

  private static boolean indexExists(Connection conn, String table, String indexName) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    for (String candidate : tableNameCandidates(table)) {
      try (ResultSet rs = metaData.getIndexInfo(conn.getCatalog(), conn.getSchema(), candidate, false, false)) {
        while (rs.next()) {
          String name = rs.getString("INDEX_NAME");
          if (name != null && name.equalsIgnoreCase(indexName)) {
            return true;
          }
        }
      } catch (SQLException ignored) {
        // try the next name casing
      }
    }
    return false;
  }

  private static boolean tableExists(Connection conn, String table) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    for (String candidate : tableNameCandidates(table)) {
      try (ResultSet rs = metaData.getTables(conn.getCatalog(), conn.getSchema(), candidate, null)) {
        if (rs.next()) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean hasColumn(Connection conn, String table, String column) throws SQLException {
    DatabaseMetaData metaData = conn.getMetaData();
    for (String candidate : tableNameCandidates(table)) {
      try (ResultSet rs = metaData.getColumns(conn.getCatalog(), conn.getSchema(), candidate, null)) {
        while (rs.next()) {
          if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  private static void addColumnIfMissing(Connection conn, String table, String column, String definition)
      throws SQLException {
    if (hasColumn(conn, table, column)) {
      return;
    }
    try (Statement st = conn.createStatement()) {
      st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    } catch (SQLException e) {
      // Same tolerance createIndex already applies to duplicate keys, for the same reason.
      //
      // hasColumn() then ALTER is a check-then-act: several servers booting at once against one
      // shared database all see the column missing and all issue the ALTER. The losers get
      // "Duplicate column name", which used to abort their whole migration and disable the plugin --
      // observed for real when four backends started in parallel against a fresh MySQL, where only
      // the first node came up and the other three had no database at all.
      //
      // MySQL 1060 = duplicate column; Postgres 42701 = duplicate_column. Both mean the column now
      // exists, which is precisely the outcome this method was asked for.
      if (e.getErrorCode() != 1060 && !"42701".equals(e.getSQLState())) {
        throw e;
      }
    }
  }

  /**
   * JDBC metadata is case-sensitive about identifiers and drivers disagree on folding (Postgres lowers
   * unquoted names, MySQL on Linux is case-sensitive, SQLite stores as created). Probe the literal name
   * and its upper/lower variants.
   */
  private static String[] tableNameCandidates(String table) {
    return new String[] {table, table.toLowerCase(Locale.ROOT), table.toUpperCase(Locale.ROOT)};
  }
}
