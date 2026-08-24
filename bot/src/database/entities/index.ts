import { Player } from "./player.entity.js";
import { DiscordAccount } from "./discord-account.entity.js";
import { AuthCode } from "./auth-code.entity.js";
import { CommandQueue } from "./command-queue.entity.js";
import { Match } from "./match.entity.js";
import { MatchPlayer } from "./match-player.entity.js";
import { Friend } from "./friend.entity.js";
import { FriendRequest } from "./friend-request.entity.js";
import { Experience } from "./experience.entity.js";
import { ExperiencePlayer } from "./experience-player.entity.js";
import { PlayerIdentity } from "./player-identity.entity.js";
import { AuthSession } from "./auth-session.entity.js";
import { AuthRequest } from "./auth-request.entity.js";
import { AuthIpBlock } from "./auth-ip-block.entity.js";

export {
  Player,
  DiscordAccount,
  AuthCode,
  CommandQueue,
  Match,
  MatchPlayer,
  Friend,
  FriendRequest,
  Experience,
  ExperiencePlayer,
  PlayerIdentity,
  AuthSession,
  AuthRequest,
  AuthIpBlock,
};

/** Every entity, in the order TypeORM should register them. Mirrors the Java `SchemaMigrator` tables. */
export const entities = [
  Player,
  DiscordAccount,
  AuthCode,
  CommandQueue,
  Match,
  MatchPlayer,
  Friend,
  FriendRequest,
  Experience,
  ExperiencePlayer,
  PlayerIdentity,
  AuthSession,
  AuthRequest,
  AuthIpBlock,
];
