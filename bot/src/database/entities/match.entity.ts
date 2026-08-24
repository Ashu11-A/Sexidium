import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/** `matches` — persisted live matches (`state` = GameState enum name, `data` = Props-encoded blob). */
@Entity({ name: "matches" })
export class Match extends BaseEntity {
  @PrimaryColumn({ name: "id", type: KEY_TYPE, length: KEY_LENGTH })
  id!: string;

  @Column({ name: "mode_id", type: KEY_TYPE, length: KEY_LENGTH })
  modeId!: string;

  @Column({ name: "mode_args", type: TEXT_TYPE, nullable: true })
  modeArgs!: string | null;

  @Column({ name: "world_name", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  worldName!: string | null;

  @Column({ name: "state", type: KEY_TYPE, length: KEY_LENGTH })
  state!: string;

  @Column({ name: "data", type: TEXT_TYPE, nullable: true })
  data!: string | null;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;

  @Column({ name: "updated_at", type: INT_TYPE, transformer: bigintNumber })
  updatedAt!: number;
}
