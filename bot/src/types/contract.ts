import { z } from "zod";

/**
 * The single source of truth for the Java <-> bot protocol.
 *
 * Everything that crosses the wire is described here as a Zod schema, so the TypeScript side is
 * 100% type-safe (inputs, outputs and event payloads are all inferred) and every frame is validated
 * at runtime before it reaches a handler. The Java side (`lib/net/BridgeRouter` +
 * `lib/net/BridgeClient`) hand-implements matching handlers/emitters against these same shapes.
 *
 * Two directions:
 *  - `procedures` — request/response, initiated by the bot, answered by Java (tRPC-style query/mutation).
 *  - `events`     — fire-and-forget, pushed by Java to the bot in real time (tRPC-style subscription).
 */

// ---------------------------------------------------------------------------
// Shared DTO schemas
// ---------------------------------------------------------------------------

/**
 * One leaderboard row. A single Discord account may own several Minecraft names — points/wins/kills/
 * games are summed across them and `names` lists each one. `rankClass`/`rankColor` are resolved
 * server-side from the summed level, so the bot never re-derives rank thresholds.
 */
export const zLeaderboardEntry = z.object({
  discordUserId: z.string().nullable(),
  name: z.string(),
  names: z.array(z.string()),
  points: z.number().int(),
  level: z.number().int(),
  wins: z.number().int(),
  kills: z.number().int(),
  games: z.number().int(),
  rankClass: z.string(),
  rankColor: z.string(),
});

/** Status tokens returned by `AuthService.consumeCode` (must match Java `AuthLinkResult.statusToken()`). */
export const zAuthLinkStatus = z.enum([
  "linked",
  "invalid",
  "expired",
  "already-used",
  "minecraft-already-linked",
  "discord-already-linked",
  "disabled",
]);

export const zAuthLinkResult = z.object({
  status: zAuthLinkStatus,
  minecraftName: z.string(),
});

/** Why we are asking. `premium-conflict` = a verified Mojang account claimed a name that is already linked. */
export const zAuthRequestKind = z.enum(["session", "premium-conflict"]);

/** Outcome tokens of `AuthSessionService.decide` (must match Java `DecisionOutcome.statusToken()`). */
export const zAuthDecisionStatus = z.enum([
  "approved",
  "denied",
  "expired",
  "already-decided",
  "not-found",
  "forbidden",
  "disabled",
]);

/**
 * A pending "is this you?" push, as Java hands it to the bot.
 *
 * `ipPrefix` is deliberately coarse (`187.61.*.*`): enough for a player to recognise their own
 * network, not enough to publish it. `firstSeen` flags a network this account has never used.
 */
export const zAuthRequest = z.object({
  requestId: z.string(),
  identityId: z.string(),
  minecraftName: z.string(),
  discordUserId: z.string(),
  ipPrefix: z.string(),
  kind: zAuthRequestKind,
  firstSeen: z.boolean(),
  createdAt: z.number().int(),
  expiresAt: z.number().int(),
});

export const zAuthDecision = z.object({
  status: zAuthDecisionStatus,
  minecraftName: z.string(),
  sessionHours: z.number().int(),
});

export const zAuthDecided = z.object({
  requestId: z.string(),
  minecraftName: z.string(),
  status: zAuthDecisionStatus,
});

/** One live IP+name session, as `/sessions` lists it. Carries no hash and no raw address. */
export const zAuthSession = z.object({
  sessionId: z.string(),
  minecraftName: z.string(),
  ipPrefix: z.string(),
  device: z.enum(["java", "premium", "bedrock"]),
  createdAt: z.number().int(),
  lastSeenAt: z.number().int(),
  expiresAt: z.number().int(),
});

/** A player's skin, resolved through SkinsRestorer on the Java side. `url` points at the Mojang texture PNG. */
export const zSkinData = z.object({
  url: z.string().nullable(),
  base64: z.string().nullable(),
  signature: z.string().nullable(),
  model: z.enum(["classic", "slim"]).default("classic"),
});

export const zServerInfo = z.object({
  ip: z.string(),
  port: z.number().int(),
  online: z.number().int(),
  max: z.number().int(),
  motd: z.string(),
  version: z.string(),
  tps: z.number().nullable(),
});

export const zPlayerRef = z.object({
  uuid: z.string(),
  name: z.string(),
});

export const zConsoleLine = z.object({
  level: z.string(),
  message: z.string(),
  ts: z.number(),
});

export const zRankChange = z.object({
  uuid: z.string(),
  discordUserId: z.string().nullable(),
  name: z.string(),
  rankClass: z.string(),
  rankColor: z.string(),
});

export const zServerStatus = z.object({
  online: z.number().int(),
  max: z.number().int(),
});

/** Generic acknowledgement for side-effecting procedures. */
export const zOk = z.object({
  ok: z.boolean(),
  command: z.string().optional(),
});

// ---------------------------------------------------------------------------
// Procedures (bot -> Java, request/response)
// ---------------------------------------------------------------------------

export const procedures = {
  "rank.top": {
    input: z.object({ limit: z.number().int().min(1).max(100).default(10) }),
    output: z.array(zLeaderboardEntry),
  },
  "rank.byName": {
    input: z.object({ name: z.string() }),
    output: zLeaderboardEntry.nullable(),
  },
  "rank.byDiscord": {
    input: z.object({ id: z.string() }),
    output: zLeaderboardEntry.nullable(),
  },
  "server.command": {
    input: z.object({ command: z.string() }),
    output: zOk,
  },
  "server.info": {
    input: z.object({}),
    output: zServerInfo,
  },
  "server.restart": {
    input: z.object({}),
    output: zOk,
  },
  "server.stop": {
    input: z.object({}),
    output: zOk,
  },
  "console.tail": {
    input: z.object({ n: z.number().int().min(1).max(200).default(50) }),
    output: z.array(zConsoleLine),
  },
  "skin.get": {
    input: z.object({ target: z.string() }),
    output: zSkinData,
  },
  "auth.link": {
    input: z.object({
      code: z.string(),
      discordUserId: z.string(),
      discordUsername: z.string().optional(),
      discordGlobalName: z.string().optional(),
      discordAvatar: z.string().optional(),
    }),
    output: zAuthLinkResult,
  },
  /**
   * Apply one Approve/Deny press. `discordUserId` is a CLAIM: Java re-checks it against the request
   * row's own owner, so a forwarded message or a hand-crafted customId comes back `forbidden`.
   */
  "auth.decide": {
    input: z.object({
      requestId: z.string().min(1).max(64),
      action: z.enum(["approve", "deny"]),
      discordUserId: z.string().min(1),
    }),
    output: zAuthDecision,
  },
  "auth.sessions": {
    input: z.object({ discordUserId: z.string().min(1) }),
    output: z.array(zAuthSession),
  },
  /** Omit `sessionId` to revoke every session this Discord user owns. */
  "auth.revoke": {
    input: z.object({ discordUserId: z.string().min(1), sessionId: z.string().optional() }),
    output: z.object({ revoked: z.number().int() }),
  },
} as const;

// ---------------------------------------------------------------------------
// Events (Java -> bot, push)
// ---------------------------------------------------------------------------

export const events = {
  "player.join": zPlayerRef,
  "player.leave": zPlayerRef,
  "rank.changed": zRankChange,
  "console.line": zConsoleLine,
  "server.status": zServerStatus,
  "auth.request": zAuthRequest,
  "auth.decided": zAuthDecided,
} as const;

// ---------------------------------------------------------------------------
// Wire envelope
// ---------------------------------------------------------------------------

/**
 * Every WebSocket frame is one envelope. `hello` carries the shared-secret handshake, `req`/`res`
 * are correlated by `id`, `event` is a Java push, `err` reports a failed request.
 */
export const zEnvelope = z.object({
  id: z.string().optional(),
  type: z.enum(["hello", "req", "res", "event", "err"]),
  method: z.string(),
  payload: z.unknown().optional(),
  error: z.string().optional(),
});
export type Envelope = z.infer<typeof zEnvelope>;

// ---------------------------------------------------------------------------
// Inferred protocol types (100% type-safe surface for the client + event bus)
// ---------------------------------------------------------------------------

export type Procedures = typeof procedures;
export type Events = typeof events;

export type ProcedureName = keyof Procedures;
export type EventName = keyof Events;

/** Caller-facing input (pre-defaults) for a procedure. */
export type ProcedureInput<M extends ProcedureName> = z.input<Procedures[M]["input"]>;
/** Validated return type of a procedure. */
export type ProcedureOutput<M extends ProcedureName> = z.output<Procedures[M]["output"]>;
/** Validated payload of an event. */
export type EventPayload<E extends EventName> = z.output<Events[E]>;
