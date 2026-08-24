import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, REAL_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/** `match_players` — per-match player snapshots. Composite PK (`match_id`, `uuid`). */
@Entity({ name: "match_players" })
export class MatchPlayer extends BaseEntity {
  @PrimaryColumn({ name: "match_id", type: KEY_TYPE, length: KEY_LENGTH })
  matchId!: string;

  @Index()
  @PrimaryColumn({ name: "uuid", type: KEY_TYPE, length: KEY_LENGTH })
  uuid!: string;

  @Column({ name: "name", type: KEY_TYPE, length: KEY_LENGTH })
  name!: string;

  @Column({ name: "role", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  role!: string | null;

  @Column({ name: "status", type: KEY_TYPE, length: KEY_LENGTH })
  status!: string;

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

  @Column({ name: "data", type: TEXT_TYPE, nullable: true })
  data!: string | null;
}
