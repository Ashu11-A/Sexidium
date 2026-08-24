import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/**
 * `player_identities` — the identity anchor. One row per LOWERCASE Minecraft name, mapping it to the
 * canonical uuid every other table is keyed by. Decided once and never recomputed, so `Foo` and
 * `foo` are one account and a premium login for the same human is not a stranger.
 *
 * Read-only from here: Java owns every write.
 */
@Entity({ name: "player_identities" })
export class PlayerIdentity extends BaseEntity {
  @PrimaryColumn({ name: "name_lower", type: KEY_TYPE, length: KEY_LENGTH })
  nameLower!: string;

  @Index()
  @Column({ name: "identity_id", type: KEY_TYPE, length: KEY_LENGTH })
  identityId!: string;

  @Column({ name: "display_name", type: KEY_TYPE, length: KEY_LENGTH })
  displayName!: string;

  @Column({ name: "premium_uuid", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  premiumUuid!: string | null;

  @Column({ name: "bedrock_xuid", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  bedrockXuid!: string | null;

  @Column({ name: "account_type", type: KEY_TYPE, length: KEY_LENGTH, default: "cracked" })
  accountType!: string;

  /** `unknown` | `premium` | `cracked` — what Mojang last said about this name. */
  @Column({ name: "premium_state", type: KEY_TYPE, length: KEY_LENGTH, default: "unknown" })
  premiumState!: string;

  @Column({ name: "premium_checked_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  premiumCheckedAt!: number;

  @Column({ name: "first_seen_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  firstSeenAt!: number;

  @Column({ name: "last_seen_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  lastSeenAt!: number;

  @Column({ name: "updated_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  updatedAt!: number;
}
