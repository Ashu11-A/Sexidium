import "reflect-metadata";
import { BaseEntity, DataSource, type DataSourceOptions } from "typeorm";
import { env, dbPort } from "#env";
import { entities } from "./entities/index.js";

/**
 * TypeORM connection to the same networked database the Java server writes to. Java owns the schema
 * (`synchronize:false`), so this never emits DDL — it just gives the bot type-safe read/query access
 * to the live tables. Authoritative mutations (rank award, auth link) still flow through the RPC
 * engine so the Java server stays the single writer of record.
 */
const options: DataSourceOptions = {
  type: env.DB_TYPE,
  host: env.DB_HOST,
  port: dbPort,
  username: env.DB_USER,
  password: env.DB_PASSWORD,
  database: env.DB_NAME,
  entities,
  synchronize: false,
  logging: false,
} as DataSourceOptions;

export const AppDataSource = new DataSource(options);

let ready = false;

/**
 * Connect and bind the Active-Record `BaseEntity` to this data source. Returns `false` (instead of
 * throwing) when the DB is unreachable, so the bot still boots and serves everything that only needs
 * the RPC engine.
 */
export async function initDatabase(): Promise<boolean> {
  if (ready) return true;
  try {
    await AppDataSource.initialize();
    BaseEntity.useDataSource(AppDataSource);
    ready = true;
    console.log(`[db] connected to ${env.DB_TYPE}://${env.DB_HOST}:${dbPort}/${env.DB_NAME}`);
    return true;
  } catch (error) {
    console.warn(
      "[db] could not connect (continuing without direct DB access):",
      error instanceof Error ? error.message : error,
    );
    return false;
  }
}

export function isDatabaseReady(): boolean {
  return ready;
}
