import { validateEnv } from "@constatic/base";
import { z } from "zod";
import "./constants.js";

/**
 * Environment contract. In production every value is injected by the Java plugin (`BotManager`) from
 * `config.yml`; the defaults here keep the bot runnable standalone during development (see `.env.example`).
 */
export const env = await validateEnv(z.looseObject({
    BOT_TOKEN: z.string("Discord Bot Token is required").min(1),

    // Legacy loopback HTTP bridge address (kept for reference/health checks). The live channel is the
    // WebSocket RPC engine below.
    API_URL: z.url().default("http://127.0.0.1:8787"),
    // Shared secret used as the WebSocket handshake token (must match config.yml -> api.token).
    API_TOKEN: z.string().optional(),

    // WebSocket RPC engine — the bot listens on loopback:RPC_PORT and the Java server connects to it.
    RPC_PORT: z.coerce.number().int().min(1).max(65535).default(8789),

    // TypeORM connection to the same networked database the Java server writes to. Java owns the DDL
    // (synchronize:false), so these only need to point at the running MySQL/Postgres instance.
    DB_TYPE: z.enum(["mysql", "postgres"]).default("postgres"),
    DB_HOST: z.string().default("127.0.0.1"),
    DB_PORT: z.coerce.number().int().min(1).max(65535).optional(),
    DB_NAME: z.string().default("sexidium"),
    DB_USER: z.string().default("sexidium"),
    DB_PASSWORD: z.string().default(""),

    // Optional slash-command guild + staff role.
    GUILD_ID: z.string().optional(),
    ADMIN_ROLE_ID: z.string().optional(),

    // Optional channels for real-time event relays (console tail, join/leave). Unset = relay disabled.
    LOG_CHANNEL_ID: z.string().optional(),
    EVENTS_CHANNEL_ID: z.string().optional(),

    // Fallback channel for login-approval pushes when a player's Discord DMs are closed (very
    // common). Unset = DM only, and a player with closed DMs falls back to the /auth <code> flow.
    AUTH_CHANNEL_ID: z.string().optional(),

    // Legacy embedded-SQLite path (unused once the bot talks to a networked DB).
    DATABASE_PATH: z.string().optional(),
}));

/** Resolved DB port, defaulting to the standard port for the chosen backend. */
export const dbPort = env.DB_PORT ?? (env.DB_TYPE === "mysql" ? 3306 : 5432);
