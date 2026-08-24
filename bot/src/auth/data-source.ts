import "reflect-metadata";
import { DataSource } from "typeorm";
import { entities } from "./entities.js";
import { applySharedMigrations } from "./migrations.js";

export async function getAuthDataSource(databasePath: string): Promise<DataSource> {
  if (!databasePath || databasePath.trim().length === 0) {
    throw new Error("DATABASE_PATH is required for auth database access.");
  }

  const dataSource = new DataSource({
    type: "sqljs",
    location: databasePath,
    autoSave: true,
    entities,
    synchronize: false,
    logging: false,
  });
  await dataSource.initialize();
  await applySharedMigrations(dataSource);
  return dataSource;
}
