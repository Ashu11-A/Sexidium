import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, REAL_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/** `experience_players` — per-experience player snapshots. Composite PK (`experience_id`, `player_uuid`). */
@Entity({ name: "experience_players" })
export class ExperiencePlayer extends BaseEntity {
  @PrimaryColumn({ name: "experience_id", type: KEY_TYPE, length: KEY_LENGTH })
  experienceId!: string;

  @PrimaryColumn({ name: "player_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  playerUuid!: string;

  @Column({ name: "world", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  world!: string | null;

  @Column({ name: "x", type: REAL_TYPE, nullable: true })
  x!: number | null;

  @Column({ name: "y", type: REAL_TYPE, nullable: true })
  y!: number | null;

  @Column({ name: "z", type: REAL_TYPE, nullable: true })
  z!: number | null;

  @Column({ name: "yaw", type: REAL_TYPE, nullable: true })
  yaw!: number | null;

  @Column({ name: "pitch", type: REAL_TYPE, nullable: true })
  pitch!: number | null;

  @Column({ name: "gamemode", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  gamemode!: string | null;

  @Column({ name: "health", type: REAL_TYPE, nullable: true })
  health!: number | null;

  @Column({ name: "food", type: INT_TYPE, nullable: true, transformer: bigintNumber })
  food!: number | null;

  @Column({ name: "inv", type: TEXT_TYPE, nullable: true })
  inv!: string | null;

  @Column({ name: "updated_at", type: INT_TYPE, transformer: bigintNumber })
  updatedAt!: number;
}
