import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/** `auth_codes` — short-lived `/sx auth` codes (PK is the SHA-256 hex of the normalized code). */
@Entity({ name: "auth_codes" })
export class AuthCode extends BaseEntity {
  @PrimaryColumn({ name: "code_hash", type: KEY_TYPE, length: KEY_LENGTH })
  codeHash!: string;

  @Column({ name: "minecraft_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  minecraftUuid!: string;

  @Column({ name: "minecraft_name", type: KEY_TYPE, length: KEY_LENGTH })
  minecraftName!: string;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "expires_at", type: INT_TYPE, transformer: bigintNumber })
  expiresAt!: number;

  @Column({ name: "consumed_at", type: INT_TYPE, nullable: true, transformer: bigintNumber })
  consumedAt!: number | null;

  @Column({ name: "consumed_discord_user_id", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  consumedDiscordUserId!: string | null;

  /**
   * The network the code was requested from, so consuming it can mint that network's first session
   * immediately. Null on rows written before sessions existed.
   */
  @Column({ name: "ip_hash", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  ipHash!: string | null;
}
