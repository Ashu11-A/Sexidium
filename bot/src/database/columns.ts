import type { ValueTransformer } from "typeorm";
import { env } from "#env";

/**
 * Column-type tokens that mirror Java's `SqlDialect`, resolved for the configured backend. Java owns
 * the DDL (`synchronize:false`), so these only need to match what the running MySQL/Postgres server
 * created — see `packages/core/.../lib/data/SchemaMigrator.java`.
 *
 *   keyText()  -> VARCHAR(191)
 *   text()     -> LONGTEXT (mysql) / TEXT (postgres)
 *   intType()  -> BIGINT   (epoch-millis timestamps + counters)
 *   realType() -> DOUBLE   (mysql) / DOUBLE PRECISION (postgres)
 */
export const KEY_TYPE = "varchar" as const;
export const KEY_LENGTH = 191;
export const TEXT_TYPE = env.DB_TYPE === "mysql" ? "longtext" : "text";
export const REAL_TYPE = env.DB_TYPE === "mysql" ? "double" : "double precision";
export const INT_TYPE = "bigint" as const;

/**
 * BIGINT columns are returned as strings by the drivers (to avoid precision loss). Every value the
 * Java schema stores in a BIGINT — epoch-millis and small counters — fits safely in a JS number, so
 * this transformer hydrates them back to `number`.
 */
export const bigintNumber: ValueTransformer = {
  to: (value?: number | null) => value,
  from: (value?: string | number | null) =>
    value === null || value === undefined ? value : Number(value),
};
