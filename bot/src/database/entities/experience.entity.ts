import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/**
 * `experiences` — composable minecraftbut experiences. `is_public` is a 0/1 flag; `challenge_state`
 * and `mode` were added by later migrations (`mode` = "experience" default, or "chaos").
 */
@Entity({ name: "experiences" })
export class Experience extends BaseEntity {
  @PrimaryColumn({ name: "id", type: KEY_TYPE, length: KEY_LENGTH })
  id!: string;

  @Index()
  @Column({ name: "owner_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  ownerUuid!: string;

  @Column({ name: "owner_name", type: KEY_TYPE, length: KEY_LENGTH })
  ownerName!: string;

  @Column({ name: "world_name", type: KEY_TYPE, length: KEY_LENGTH })
  worldName!: string;

  @Column({ name: "display_name", type: KEY_TYPE, length: KEY_LENGTH })
  displayName!: string;

  @Column({ name: "challenges", type: TEXT_TYPE })
  challenges!: string;

  @Column({ name: "is_public", type: INT_TYPE, default: 0, transformer: bigintNumber })
  isPublic!: number;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "updated_at", type: INT_TYPE, transformer: bigintNumber })
  updatedAt!: number;

  @Column({ name: "challenge_state", type: TEXT_TYPE, nullable: true })
  challengeState!: string | null;

  @Column({ name: "mode", type: TEXT_TYPE, nullable: true })
  mode!: string | null;
}
