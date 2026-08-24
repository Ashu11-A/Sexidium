import { EntitySchema } from "typeorm";

// WARNING: These TypeORM schemas must always stay synchronized with the Java
// repositories and packages/lib/src/main/resources/db/migrations/001_auth.sql.
// All TypeScript stays under bot/src; the SQL migration remains the shared Java/TS schema source.

export interface PlayerEntity {
  uuid: string;
  name: string;
  discordUserId: string | null;
  points: number;
  level: number;
  wins: number;
  kills: number;
  games: number;
  updatedAt: number;
}

export interface DiscordAccountEntity {
  discordUserId: string;
  minecraftUuid: string;
  minecraftName: string;
  createdAt: number;
  updatedAt: number;
}

export interface AuthCodeEntity {
  codeHash: string;
  minecraftUuid: string;
  minecraftName: string;
  createdAt: number;
  expiresAt: number;
  consumedAt: number | null;
  consumedDiscordUserId: string | null;
}

export const PlayerSchema = new EntitySchema<PlayerEntity>({
  name: "Player",
  tableName: "players",
  columns: {
    uuid: { type: String, primary: true },
    name: { type: String },
    discordUserId: { type: String, name: "discord_user_id", nullable: true },
    points: { type: Number, default: 0 },
    level: { type: Number, default: 0 },
    wins: { type: Number, default: 0 },
    kills: { type: Number, default: 0 },
    games: { type: Number, default: 0 },
    updatedAt: { type: Number, name: "updated_at", default: 0 },
  },
});

export const DiscordAccountSchema = new EntitySchema<DiscordAccountEntity>({
  name: "DiscordAccount",
  tableName: "discord_accounts",
  columns: {
    discordUserId: { type: String, name: "discord_user_id", primary: true },
    minecraftUuid: { type: String, name: "minecraft_uuid", unique: true },
    minecraftName: { type: String, name: "minecraft_name" },
    createdAt: { type: Number, name: "created_at" },
    updatedAt: { type: Number, name: "updated_at" },
  },
});

export const AuthCodeSchema = new EntitySchema<AuthCodeEntity>({
  name: "AuthCode",
  tableName: "auth_codes",
  columns: {
    codeHash: { type: String, name: "code_hash", primary: true },
    minecraftUuid: { type: String, name: "minecraft_uuid" },
    minecraftName: { type: String, name: "minecraft_name" },
    createdAt: { type: Number, name: "created_at" },
    expiresAt: { type: Number, name: "expires_at" },
    consumedAt: { type: Number, name: "consumed_at", nullable: true },
    consumedDiscordUserId: { type: String, name: "consumed_discord_user_id", nullable: true },
  },
});

export const entities = [PlayerSchema, DiscordAccountSchema, AuthCodeSchema];
