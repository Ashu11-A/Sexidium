import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/**
 * `auth_requests` — one pending "is this you?" push, end to end.
 *
 * Written by the node holding the connection, claimed by the bot host's courier, decided through the
 * `auth.decide` procedure. Read-only from here; the bot never writes a decision itself, because the
 * ownership and replay checks live on the Java side against this row.
 */
@Entity({ name: "auth_requests" })
export class AuthRequest extends BaseEntity {
  @PrimaryColumn({ name: "request_id", type: KEY_TYPE, length: KEY_LENGTH })
  requestId!: string;

  @Column({ name: "identity_id", type: KEY_TYPE, length: KEY_LENGTH })
  identityId!: string;

  @Column({ name: "name_lower", type: KEY_TYPE, length: KEY_LENGTH })
  nameLower!: string;

  @Column({ name: "display_name", type: KEY_TYPE, length: KEY_LENGTH })
  displayName!: string;

  @Column({ name: "discord_user_id", type: KEY_TYPE, length: KEY_LENGTH })
  discordUserId!: string;

  @Column({ name: "ip_hash", type: KEY_TYPE, length: KEY_LENGTH })
  ipHash!: string;

  @Column({ name: "ip_prefix", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  ipPrefix!: string | null;

  /** `session` | `premium-conflict`. */
  @Column({ name: "kind", type: KEY_TYPE, length: KEY_LENGTH })
  kind!: string;

  /** `pending` | `notified` | `approved` | `denied` | `expired` | `consumed`. */
  @Column({ name: "state", type: KEY_TYPE, length: KEY_LENGTH })
  state!: string;

  /** 1 when a player is frozen in-world waiting on this request rather than sitting on a kick screen. */
  @Column({ name: "hold", type: INT_TYPE, default: 0, transformer: bigintNumber })
  hold!: number;

  @Column({ name: "waiting_node", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  waitingNode!: string | null;

  @Column({ name: "claimed_by", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  claimedBy!: string | null;

  @Column({ name: "claim_expires_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  claimExpiresAt!: number;

  @Column({ name: "notified_at", type: INT_TYPE, nullable: true, transformer: bigintNumber })
  notifiedAt!: number | null;

  @Column({ name: "decided_at", type: INT_TYPE, nullable: true, transformer: bigintNumber })
  decidedAt!: number | null;

  @Column({ name: "decided_by", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  decidedBy!: string | null;

  @Column({ name: "attempts", type: INT_TYPE, default: 0, transformer: bigintNumber })
  attempts!: number;

  @Column({ name: "created_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "expires_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  expiresAt!: number;

  @Column({ name: "detail", type: TEXT_TYPE, nullable: true })
  detail!: string | null;
}
