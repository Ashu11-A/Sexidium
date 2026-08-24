import { createHash } from "node:crypto";
import type { DataSource, EntityManager } from "typeorm";
import {
  AuthCodeSchema,
  DiscordAccountSchema,
  PlayerSchema,
  type AuthCodeEntity,
  type DiscordAccountEntity,
  type PlayerEntity,
} from "./entities.js";

export type ConsumeAuthCodeResult =
  | { status: "linked"; minecraftName: string }
  | { status: "invalid" }
  | { status: "expired"; minecraftName: string }
  | { status: "already-used"; minecraftName: string }
  | { status: "minecraft-already-linked"; minecraftName: string }
  | { status: "discord-already-linked"; minecraftName: string };

export class AuthRepository {
  constructor(private readonly dataSource: DataSource) {}

  async consumeCode(code: string, discordUserId: string): Promise<ConsumeAuthCodeResult> {
    const codeHash = hashCode(code);
    const now = Date.now();

    return await this.dataSource.transaction(async (manager) => {
      const authCodes = manager.getRepository<AuthCodeEntity>(AuthCodeSchema);
      const players = manager.getRepository<PlayerEntity>(PlayerSchema);
      const accounts = manager.getRepository<DiscordAccountEntity>(DiscordAccountSchema);

      const authCode = await authCodes.findOne({ where: { codeHash } });
      if (!authCode) return { status: "invalid" };
      if (authCode.consumedAt != null) {
        return { status: "already-used", minecraftName: authCode.minecraftName };
      }
      if (authCode.expiresAt < now) {
        return { status: "expired", minecraftName: authCode.minecraftName };
      }

      const existingMinecraft = await accounts.findOne({
        where: { minecraftUuid: authCode.minecraftUuid },
      });
      if (existingMinecraft) {
        return { status: "minecraft-already-linked", minecraftName: authCode.minecraftName };
      }

      const existingDiscord = await accounts.findOne({ where: { discordUserId } });
      if (existingDiscord) {
        return { status: "discord-already-linked", minecraftName: existingDiscord.minecraftName };
      }

      await saveLink(manager, authCode, discordUserId, now);
      return { status: "linked", minecraftName: authCode.minecraftName };
    });
  }
}

async function saveLink(
  manager: EntityManager,
  authCode: AuthCodeEntity,
  discordUserId: string,
  now: number
): Promise<void> {
  await manager.getRepository<DiscordAccountEntity>(DiscordAccountSchema).save({
    discordUserId,
    minecraftUuid: authCode.minecraftUuid,
    minecraftName: authCode.minecraftName,
    createdAt: now,
    updatedAt: now,
  });

  const players = manager.getRepository<PlayerEntity>(PlayerSchema);
  const existingPlayer = await players.findOne({ where: { uuid: authCode.minecraftUuid } });
  if (existingPlayer) {
    await players.update(
      { uuid: authCode.minecraftUuid },
      { name: authCode.minecraftName, discordUserId, updatedAt: now }
    );
  } else {
    await players.save({
      uuid: authCode.minecraftUuid,
      name: authCode.minecraftName,
      discordUserId,
      points: 0,
      level: 0,
      wins: 0,
      kills: 0,
      games: 0,
      updatedAt: now,
    });
  }

  await manager.getRepository<AuthCodeEntity>(AuthCodeSchema).update(
    { codeHash: authCode.codeHash },
    { consumedAt: now, consumedDiscordUserId: discordUserId }
  );
}

export function hashCode(code: string): string {
  return createHash("sha256").update(normalizeCode(code)).digest("hex");
}

function normalizeCode(code: string): string {
  return code.trim().replaceAll("-", "").replaceAll(" ", "").toUpperCase();
}
