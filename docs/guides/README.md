# Guides

Task-oriented **how-to-change-it** documents, one per module. Every other folder in these docs
describes how a system *behaves*; a guide prescribes the *workflow* — which files to touch, in what
order, which drift-guard tests will fail if you skip a step, and which page to update in the same
change.

They are written to be loaded as **base context** before the work starts: hand one to a contributor,
or paste it in front of an agent, and the resulting change should already follow the repo's rules
instead of being corrected afterwards. Each ends in a checklist; a change is not finished until its
guide's checklist passes.

## The eight guides

| Guide | Workflow it prescribes |
|-------|------------------------|
| [add-a-challenge.md](add-a-challenge.md) | Add or modify an experience challenge: catalog registration, the composition pipelines and the rules for interoperating with other challenges, HUD/scoreboard contribution, world-generating (SkyBlock-style) challenges, chest-GUI/icon wiring, config + drift guards. |
| [add-a-minigame.md](add-a-minigame.md) | Add or modify a minigame: descriptor registration, choosing the base class, match lifecycle rules, battle maps + the in-world editor, HUD, awards/win flow, menu icons, config. |
| [add-a-menu-screen.md](add-a-menu-screen.md) | Add or modify a chest-GUI screen: the `MenuService` facade rules, the non-negotiable cross-play (Bedrock/Geyser) constraints, custom art tables, per-player state hygiene. |
| [add-a-command.md](add-a-command.md) | Add or modify a command: dispatch buckets, the `/sx admin` arg-reslice pattern, tab completion, bilingual i18n. |
| [add-a-platform-capability.md](add-a-platform-capability.md) | Add an SPI seam: the default-method pattern, capability flags, Paper/NeoForge parity, POJO-fake testing. |
| [work-on-worlds.md](work-on-worlds.md) | Managed worlds: leasing seams, naming, linked dimensions, void generation, safe spawn, the structure/loot generation engine, bundled worlds, and the map-editing tooling. |
| [work-on-the-bot.md](work-on-the-bot.md) | The Discord bot and the Java↔bot bridge: the Zod contract as the single source of truth, slash-command registration, rendered cards, supervision rules. |
| [research-a-youtube-challenge.md](research-a-youtube-challenge.md) | Turn a YouTuber "Minecraft, but…" format into a challenge: harvest with `yt-dlp`, mine for the rule and its edge cases, design the server-safe bound (radius + per-tick budget + catch-up), then implement and record the provenance. Front-end to `add-a-challenge.md`. |

## Renamed from `Prompt.*.md`

These files used to live at `docs/Prompt.<topic>.md`. They are now named for the **verb** — what you
are about to do — rather than the module. If your muscle memory (or an old link, comment, or agent
prompt) says `Prompt.X.md`, this is where it went:

| Old path | New path |
|----------|----------|
| `docs/Prompt.challenge.md` | [`guides/add-a-challenge.md`](add-a-challenge.md) |
| `docs/Prompt.minigames.md` | [`guides/add-a-minigame.md`](add-a-minigame.md) |
| `docs/Prompt.menus.md` | [`guides/add-a-menu-screen.md`](add-a-menu-screen.md) |
| `docs/Prompt.commands.md` | [`guides/add-a-command.md`](add-a-command.md) |
| `docs/Prompt.platform.md` | [`guides/add-a-platform-capability.md`](add-a-platform-capability.md) |
| `docs/Prompt.worlds.md` | [`guides/work-on-worlds.md`](work-on-worlds.md) |
| `docs/Prompt.bot.md` | [`guides/work-on-the-bot.md`](work-on-the-bot.md) |
| `docs/Prompt.youtube-challenge.md` | [`guides/research-a-youtube-challenge.md`](research-a-youtube-challenge.md) |
