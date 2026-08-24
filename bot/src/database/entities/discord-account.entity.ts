import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/**
 * `discord_accounts` — one Discord account → many Minecraft accounts. Keyed by `minecraft_uuid`
 * (each MC account links once); `discord_user_id` repeats across a user's alts (non-unique index).
 * This is the corrected one-to-many shape (the old `bot/src/auth/entities.ts` modelled one-to-one).
 */
@Entity({ name: "discord_accounts" })
export class DiscordAccount extends BaseEntity {
  @PrimaryColumn({ name: "minecraft_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  minecraftUuid!: string;

  @Index()
  @Column({ name: "discord_user_id", type: KEY_TYPE, length: KEY_LENGTH })
  discordUserId!: string;

  @Column({ name: "minecraft_name", type: KEY_TYPE, length: KEY_LENGTH })
  minecraftName!: string;

  @Column({ name: "discord_username", type: TEXT_TYPE, nullable: true })
  discordUsername!: string | null;

  @Column({ name: "discord_global_name", type: TEXT_TYPE, nullable: true })
  discordGlobalName!: string | null;

  @Column({ name: "discord_avatar", type: TEXT_TYPE, nullable: true })
  discordAvatar!: string | null;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "updated_at", type: INT_TYPE, transformer: bigintNumber })
  updatedAt!: number;
}
