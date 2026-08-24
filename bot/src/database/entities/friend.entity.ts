import { BaseEntity, Column, Entity, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/** `friends` — accepted friendships. Composite PK (`player_uuid`, `friend_uuid`). */
@Entity({ name: "friends" })
export class Friend extends BaseEntity {
  @PrimaryColumn({ name: "player_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  playerUuid!: string;

  @PrimaryColumn({ name: "friend_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  friendUuid!: string;

  @Column({ name: "friend_name", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  friendName!: string | null;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;
}
