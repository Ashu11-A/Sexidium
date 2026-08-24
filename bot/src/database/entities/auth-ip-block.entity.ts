import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/**
 * `auth_ip_blocks` — the durable half of pressing Deny.
 *
 * Per `(identity, network)` and never per network alone: blocking an address outright would, under
 * CGNAT, punish every stranger who happens to share it.
 */
@Entity({ name: "auth_ip_blocks" })
export class AuthIpBlock extends BaseEntity {
  @PrimaryColumn({ name: "identity_id", type: KEY_TYPE, length: KEY_LENGTH })
  identityId!: string;

  @PrimaryColumn({ name: "ip_hash", type: KEY_TYPE, length: KEY_LENGTH })
  ipHash!: string;

  @Column({ name: "reason", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  reason!: string | null;

  @Column({ name: "created_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "expires_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  expiresAt!: number;
}
