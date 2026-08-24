import { z } from "zod";
import {
  zLeaderboardEntry,
  zAuthLinkStatus,
  zAuthLinkResult,
  zAuthRequestKind,
  zAuthDecisionStatus,
  zAuthRequest,
  zAuthDecision,
  zAuthDecided,
  zAuthSession,
  zSkinData,
  zServerInfo,
  zPlayerRef,
  zConsoleLine,
  zRankChange,
  zServerStatus,
} from "./contract.js";

/**
 * Plain, inferred data types for every wire DTO. These replace the hand-mirrored `LeaderboardEntry`
 * interface that used to live in `lib/api.ts` and the inline auth-status unions in the commands —
 * the schema in `contract.ts` is now the only place a shape is declared.
 */
export type LeaderboardEntry = z.infer<typeof zLeaderboardEntry>;
export type AuthLinkStatus = z.infer<typeof zAuthLinkStatus>;
export type AuthLinkResult = z.infer<typeof zAuthLinkResult>;
export type AuthRequestKind = z.infer<typeof zAuthRequestKind>;
export type AuthDecisionStatus = z.infer<typeof zAuthDecisionStatus>;
export type AuthRequest = z.infer<typeof zAuthRequest>;
export type AuthDecision = z.infer<typeof zAuthDecision>;
export type AuthDecided = z.infer<typeof zAuthDecided>;
export type AuthSession = z.infer<typeof zAuthSession>;
export type SkinData = z.infer<typeof zSkinData>;
export type ServerInfo = z.infer<typeof zServerInfo>;
export type PlayerRef = z.infer<typeof zPlayerRef>;
export type ConsoleLine = z.infer<typeof zConsoleLine>;
export type RankChange = z.infer<typeof zRankChange>;
export type ServerStatus = z.infer<typeof zServerStatus>;
