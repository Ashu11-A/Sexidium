-- WARNING: This migration is the canonical schema used by both Java repositories
-- and TypeScript TypeORM entities. Keep bot/src/auth/entities.ts and the
-- Java repositories synchronized with this file whenever columns or tables change.
-- TypeORM uses the sql.js driver, which loads/saves the main SQLite file directly;
-- keep rollback journal mode so Java writes are visible to sql.js immediately.

PRAGMA journal_mode=DELETE;

CREATE TABLE IF NOT EXISTS players (
  uuid            TEXT PRIMARY KEY,
  name            TEXT NOT NULL,
  discord_user_id TEXT,
  points          INTEGER NOT NULL DEFAULT 0,
  level           INTEGER NOT NULL DEFAULT 0,
  wins            INTEGER NOT NULL DEFAULT 0,
  kills           INTEGER NOT NULL DEFAULT 0,
  games           INTEGER NOT NULL DEFAULT 0,
  updated_at      INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS discord_accounts (
  discord_user_id TEXT PRIMARY KEY,
  minecraft_uuid  TEXT NOT NULL UNIQUE,
  minecraft_name  TEXT NOT NULL,
  created_at      INTEGER NOT NULL,
  updated_at      INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_codes (
  code_hash                 TEXT PRIMARY KEY,
  minecraft_uuid            TEXT NOT NULL,
  minecraft_name            TEXT NOT NULL,
  created_at                INTEGER NOT NULL,
  expires_at                INTEGER NOT NULL,
  consumed_at               INTEGER,
  consumed_discord_user_id  TEXT
);

CREATE TABLE IF NOT EXISTS command_queue (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  command      TEXT NOT NULL,
  requested_by TEXT,
  status       TEXT NOT NULL DEFAULT 'PENDING',
  created_at   INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_players_discord_user_id
ON players(discord_user_id)
WHERE discord_user_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_players_name_nocase
ON players(name COLLATE NOCASE);

CREATE INDEX IF NOT EXISTS idx_auth_codes_lookup
ON auth_codes(code_hash, expires_at, consumed_at);

CREATE INDEX IF NOT EXISTS idx_discord_accounts_minecraft_uuid
ON discord_accounts(minecraft_uuid);
