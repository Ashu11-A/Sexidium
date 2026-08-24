import { existsSync, readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { DataSource } from "typeorm";

const here = dirname(fileURLToPath(import.meta.url));
const migrationCandidates = [
  join(here, "../../../packages/core/src/main/resources/db/migrations/001_auth.sql"),
  join(here, "../../../packages/lib/src/main/resources/db/migrations/001_auth.sql"),
  join(process.cwd(), "packages/core/src/main/resources/db/migrations/001_auth.sql"),
  join(process.cwd(), "packages/lib/src/main/resources/db/migrations/001_auth.sql"),
  join(process.cwd(), "../packages/core/src/main/resources/db/migrations/001_auth.sql"),
  join(process.cwd(), "../packages/lib/src/main/resources/db/migrations/001_auth.sql"),
];

export async function applySharedMigrations(dataSource: DataSource): Promise<void> {
  const migrationPath = migrationCandidates.find((candidate) => existsSync(candidate));
  if (!migrationPath) {
    throw new Error("Shared database migration 001_auth.sql was not found.");
  }
  const sql = readFileSync(migrationPath, "utf8");
  for (const statement of splitSql(sql)) {
    await dataSource.query(statement);
  }
  await addColumnIfMissing(dataSource, "players", "discord_user_id", "TEXT");
}

function splitSql(sql: string): string[] {
  return sql
    .split(";")
    .map((statement) => statement.trim())
    .filter((statement) => statement.length > 0);
}

async function addColumnIfMissing(
  dataSource: DataSource,
  table: string,
  column: string,
  definition: string
): Promise<void> {
  const rows = (await dataSource.query(`PRAGMA table_info(${table})`)) as Array<{ name?: string }>;
  if (rows.some((row) => row.name?.toLowerCase() === column.toLowerCase())) return;
  await dataSource.query(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
}
