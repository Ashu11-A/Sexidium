# Base prompt: adding or modifying a command

You are working on Sexidium's **command tree** (`/sx`, `/sexidium`, plus the global aliases `/menu`,
`/exit`/`/leave`, `/lobby`, `/friend`). One platform-agnostic service owns every command; the adapters
only forward raw `String[]` argv — so a command added in core exists identically on Paper and Velocity.
Reference: [commands.md](../interface/commands.md).

## Key files

| Concern | File(s) |
|---|---|
| Root dispatch + permission buckets | `packages/core/src/main/java/com/sexidium/core/command/CoreCommandService.java` (`ROOT_ORDER`, `PLAYER_SUBCOMMANDS`, `ADMIN_SUBCOMMANDS`, `hasRootPermission`) |
| Focused handlers | `…/command/GameCommands.java`, `ExperienceCommands.java`, `LobbyCommands.java`, `FriendCommands.java`, `AdminCommands.java`, `BotCommands.java`, `NpcCommands.java`, `RaceCommands.java`, `TntWarCommands.java`, `CombatCommands.java`, `MapEditorCommands.java` |
| Shared context | `…/command/CommandContext.java` (`ctx.core`, `ctx.server`, `send`, `filter`, `requirePlayer`, `playerSource`, `participants`, `playerNames`), `CommandText.java` |
| Adapter bridges | Paper: command registration + `PaperAliasCommand` (global aliases prepend their token and forward); Velocity: the proxy-side command relay |
| i18n | `…/core/i18n/MessageKey.java` + `packages/core/src/main/resources/lang/en.properties` **and** `pt.properties` |

## How to add a subcommand

1. Pick the owning handler class (or create one composing `CommandContext` and route it from
   `CoreCommandService`'s switch). Handlers receive `(CommandSource source, String[] args)` with the
   **full argv** — each dispatch level reads its own token (`args[0]` = root subcommand, etc.).
2. Add the token to the right **permission bucket** in `CoreCommandService` (`PLAYER_SUBCOMMANDS` →
   `sexidium.play`; `ADMIN_SUBCOMMANDS` → `sexidium.admin`) and to `ROOT_ORDER` for suggestion ordering.
   The root gate only sees the first token — a whole admin tree hangs off the single `admin` entry.
3. **Admin tools go under `/sx admin <tool>`**, dispatched by `AdminCommands` with the **arg-reslice
   pattern**: slice the array so the focused handler sees the same shape it would as a top-level command.
   Do not invent new top-level admin commands.
4. **Mirror every branch in `suggest(source, args)`** — tab completion is hand-rolled per handler
   (`ctx.filter(candidates, prefix)`); a branch without suggestions is a bug, not a nicety. Suggest real
   data (own experience ids, online player names) where possible.
5. **Flags** use `--name=value` (see `ExperienceCommands.handleStart` for `--players=`, `--world=`,
   `--keep-inventory=`); unknown `--` flags are ignored for forward compatibility, unknown positional
   tokens get an explicit red error.
6. **Messages**: user-visible text is either a `MessageKey` (add to the enum + **both** lang files —
   the server is bilingual EN/PT) or inline MiniMessage for admin/debug-grade output. Escape player input
   with `CommandText.escape`.
7. A new **global alias** (top-level `/foo`) additionally needs the Paper alias registration
   (`PaperAliasCommand` prepends the token and calls the same service) and plugin.yml/mod metadata —
   check how `/lobby` is wired before adding one; prefer a `/sx` subcommand.

## Rules

- Console must not crash player-only commands: open with `ctx.requirePlayer(source)`.
- `execute` always returns handled — print usage yourself (`<red>Usage:</red> …` convention).
- Anything destructive or world-changing belongs behind `sexidium.admin`; player-facing commands must
  work for a bare `sexidium.play` holder.
- GUI-first: if a command only exists to open/drive a menu, keep the logic in `MenuService`/domain
  services and make the command a thin call — the same rule the codebase already follows
  (`/sx experience` opens the GUI).
- Update [commands.md](../interface/commands.md) — including its quick-reference tables — in the same change.

## Checklist

- [ ] Token in bucket + `ROOT_ORDER`; permission verified for both roles
- [ ] `suggest` branch added with real candidates
- [ ] i18n keys in **both** lang files (or intentional inline MiniMessage)
- [ ] Console/`requirePlayer` handled; input escaped
- [ ] [commands.md](../interface/commands.md) updated in the same change

---
*Keeping this current: tracks `CoreCommandService`, the handler classes, `CommandContext` and the adapter
bridges. Update it in the same change that alters the dispatch/suggestion workflow.*
