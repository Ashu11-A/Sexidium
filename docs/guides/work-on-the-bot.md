# Base prompt: working on the Discord bot & the Java↔bot bridge

You are working on Sexidium's **Discord bot** — a separate TypeScript codebase (`bot/`, runs on Bun,
supervised as a child process by the Java server) — and/or its **typed RPC bridge** to the server.
Reference: [networking-bot-ranks.md](networking-bot-ranks.md).

## Key files

| Concern | File(s) |
|---|---|
| **Protocol source of truth** | `bot/src/types/contract.ts` — every procedure/event as a Zod schema; TS types are inferred from it, and every frame is runtime-validated |
| Java side of the bridge | `packages/core/src/main/java/com/sexidium/core/lib/net/BridgeRouter.java` (answers procedures), `BridgeClient.java` (emits events), `JsonReader.java`/`Json.java` |
| Bot process supervision | `packages/core/src/main/java/com/sexidium/core/bot/BotManager.java` (downloads a per-OS `bun` at startup — **no bundled runtimes/jars**), `/sx admin bot` (`…/command/BotCommands.java`) |
| HTTP API (non-RPC surface) | `…/core/lib/net/ApiServer.java` — `GET /rank`, `GET /player`, `GET /discord`; token-gated `POST /command`, `POST /auth/link` |
| Slash commands | `bot/src/discord/commands/*.ts` (`rank`, `leaderboard`, `mc`, `auth`, `event`, `announce`, `syncranks`, `serverinfo`, `restart`, `console`, `ping`) — **must be imported in `commands/index.ts`** |
| Discord events / gateway | `bot/src/discord/events/`, `bot/src/discord/index.ts` |
| Minecraft-side client | `bot/src/minecraft/` (`client.ts`, `codec.ts`, `events.ts`, auth flow) |
| Database (TypeORM) | `bot/src/database/` (`data-source.ts`, `entities/`) — shares the server DB; **SQLite is rejected when the bot is enabled** (MySQL/Postgres required) |
| Rendered card images | `bot/src/ui/components/` (satori React components) + `bot/src/ui/images/`; helpers in `bot/src/lib/` (`ranks.ts`, `rank-sync.ts`, `skin.ts`, theme/fonts) |

## The one rule that governs everything

**`contract.ts` is the single source of truth for the wire.** Any new data crossing Java↔bot means, in
one change:

1. Add the Zod schema (procedure or event) to `bot/src/types/contract.ts`.
2. Implement the Java side against the *same shape*: a handler in `BridgeRouter` (bot-initiated
   request/response) or an emit in `BridgeClient` (Java-pushed event). The Java side is hand-written —
   there is no codegen — so field names/optionality must match the schema exactly.
3. Consume it in the bot with the inferred types; never re-declare the shape locally.

Never bypass the bridge with ad-hoc HTTP endpoints for bot features; `ApiServer` is for the small stable
surface listed above.

## Adding a slash command

1. Create `bot/src/discord/commands/<name>.ts` following an existing one (`rank.ts` for an
   image-replying command, `serverinfo.ts` for an RPC query, `console.ts`/`restart.ts` for admin-gated).
2. **Import it in `commands/index.ts`** — registration is import-driven; forgetting this is the classic
   silent failure.
3. Staff/admin gating goes through `bot/src/lib/staff.ts` helpers, not inline ID checks.
4. Rich replies are **rendered images**: build a React component under `ui/components/` (satori — a
   supported CSS subset, test what you use) and render via the existing image helpers; keep the visual
   language of the current cards (theme in `lib/discord-theme.ts`).
5. Data comes from TypeORM entities (read-mostly; the **Java server owns schema migrations** — never
   add a migration on the bot side) or from an RPC procedure.

## Operational rules

- The bot must **degrade, not crash**, when the server is down: the minecraft client reconnects; commands
  answer with a friendly unavailable message.
- Environment/config: `bot/src/env.ts` validates env vars; new settings go there and into
  `bot/.env.example`.
- Java-side lifecycle: `BotManager` start/stop/restart is the only supervisor — never spawn bun
  elsewhere. Logs surface through `/sx admin bot logs`.
- Run `bun install` in `bot/` after dependency changes and commit `bun.lock`.

## Checklist

- [ ] Wire changes made in `contract.ts` first, Java matched to it
- [ ] New command imported in `commands/index.ts`; gated appropriately
- [ ] No bot-side schema migrations; no SQLite assumptions
- [ ] Bot behaves with the server offline
- [ ] [networking-bot-ranks.md](networking-bot-ranks.md) updated in the same change

---
*Keeping this current: tracks `bot/src/` layout, `contract.ts`, `BridgeRouter`/`BridgeClient`,
`BotManager` and `ApiServer`. Update it in the same change that alters those workflows.*
