import { BaseEntity, Column, Entity, Index, PrimaryColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, bigintNumber } from "../columns.js";

/** `friend_requests` — pending friend requests. Composite PK (`from_uuid`, `to_uuid`). */
@Entity({ name: "friend_requests" })
export class FriendRequest extends BaseEntity {
  @PrimaryColumn({ name: "from_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  fromUuid!: string;

  @Column({ name: "from_name", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  fromName!: string | null;

  @Index()
  @PrimaryColumn({ name: "to_uuid", type: KEY_TYPE, length: KEY_LENGTH })
  toUuid!: string;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;
}
