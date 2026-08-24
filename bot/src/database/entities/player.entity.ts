import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/**
 * `players` — the rank/score table. `points` is the raw score; rank class/level are derived from it
 * on the Java side. One Discord account may own several player rows (`discord_user_id` is non-unique).
 */
@Entity({ name: "players" })
export class Player extends BaseEntity {
  @PrimaryColumn({ name: "uuid", type: KEY_TYPE, length: KEY_LENGTH })
  uuid!: string;

  @Column({ name: "name", type: KEY_TYPE, length: KEY_LENGTH })
  name!: string;

  @Index()
  @Column({ name: "discord_user_id", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  discordUserId!: string | null;

  @Column({ name: "points", type: INT_TYPE, default: 0, transformer: bigintNumber })
  points!: number;

  @Column({ name: "level", type: INT_TYPE, default: 0, transformer: bigintNumber })
  level!: number;

  @Column({ name: "wins", type: INT_TYPE, default: 0, transformer: bigintNumber })
  wins!: number;

  @Column({ name: "kills", type: INT_TYPE, default: 0, transformer: bigintNumber })
  kills!: number;

  @Column({ name: "games", type: INT_TYPE, default: 0, transformer: bigintNumber })
  games!: number;

  @Column({ name: "updated_at", type: INT_TYPE, default: 0, transformer: bigintNumber })
  updatedAt!: number;
}
