import { BaseEntity, Column, Entity, PrimaryGeneratedColumn } from "typeorm";
import { INT_TYPE, KEY_LENGTH, KEY_TYPE, TEXT_TYPE, bigintNumber } from "../columns.js";

/** `command_queue` — auto-increment scaffold table (defined by the Java schema; currently unused). */
@Entity({ name: "command_queue" })
export class CommandQueue extends BaseEntity {
  @PrimaryGeneratedColumn({ name: "id", type: INT_TYPE })
  id!: string;

  @Column({ name: "command", type: TEXT_TYPE })
  command!: string;

  @Column({ name: "requested_by", type: KEY_TYPE, length: KEY_LENGTH, nullable: true })
  requestedBy!: string | null;

  @Column({ name: "status", type: KEY_TYPE, length: KEY_LENGTH, default: "PENDING" })
  status!: string;

  @Column({ name: "created_at", type: INT_TYPE, transformer: bigintNumber })
  createdAt!: number;
}
