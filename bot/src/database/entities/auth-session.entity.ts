import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/**
 * `auth_sessions` — "this name was approved from this network recently".
 *
 * The composite key `(identity_id, ip_hash)` is the security property: an IP with no matching
 * identity authorises nothing. `ipHash` is `SHA-256(pepper||ip)` and `ipPrefix` is a redacted
 * display string — no raw address is ever stored.
 *
 * Read-only from here: every mutation goes through the `auth.revoke` procedure so Java stays the
 * single writer of record.
 */
@Entity({ name: "auth_sessions" })
export class AuthSession extends BaseEntity {
  @PrimaryColumn({ name: "identity_id", type: KEY_TYPE, length: KEY_LENGTH })
  identityId!: string;

  @PrimaryColumn({ name: "ip_hash", type: KEY_TYPE, length: KEY_LENGTH })
  ipHash!: string;

  @Column({ name: "session_id", type: KEY_TYPE, length: KEY_LENGTH })
  sessionId!: string;

  @Column({ name: "name_lower", type: KEY_TYPE, length: KEY_LENGTH })
  nameLower!: string;

  @Column({ name: "ip_prefix", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  ipPrefix!: string | null;

  /** `java` | `premium` | `bedrock`. The last two re-verify every login and never rely on this row. */
  @Column({ name: "device", type: KEY_TYPE, length: KEY_LENGTH, default: "java" })
  device!: string;

  @Column({ name: "origin_node", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  originNode!: string | null;

  @Column({ name: "approved_by", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  approvedBy!: string | null;

  @Column({ name: "created_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "last_seen_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  lastSeenAt!: number;

  @Column({ name: "expires_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  expiresAt!: number;

  /** The cap a sliding renewal can never push past. */
  @Column({ name: "absolute_expires_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  absoluteExpiresAt!: number;

  @Column({ name: "revoked_at", type: INT_TYPE, nullable: true, transformer: bigintNumber })
  revokedAt!: number | null;
}
