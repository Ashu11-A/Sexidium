# Gameplay

What players actually play. Sexidium ships **three game families** plus the pre-match layer they all
launch from. The engine underneath them is documented in
[`../architecture/game-framework.md`](../architecture/game-framework.md); this folder covers content
and rules only.

## The three families

| Family | Document | Shape | Scope of effects | Ends when | Launch |
|--------|----------|-------|------------------|-----------|--------|
| **Minigames** | [minigames.md](minigames.md) | Timed, round-based, **competitive — there is a winner** | Whole match | A win condition fires or the timer expires | `/sx start minigames <mode> [players]` (admin) |
| **Experiences** | [experiences.md](experiences.md) | **Persistent** player-owned survival world, **composed** from any set of challenges | Whole world, all players | Never auto-ends — the owner owns it | `/sx start experience <challenge…>` (any player) |
| **Chaos** | [chaos.md](chaos.md) | One shared open world, twists reshuffled on a timer | **Per player** — each player gets their own random twist set | Open-ended | `/sx start chaos` (aliases `roulette`, `random`) |

The distinction that matters: minigames are *matches with a result*; experiences are *places that keep
existing*; chaos reuses the experience composition layer but scopes every effect to a single player and
rerolls it periodically, resetting the previous effects first.

Chaos and experiences share the same composition machinery (`compose/`, `ChallengeContext`), so a
challenge written once works in both.

## Before the match

| Document | Covers |
|----------|--------|
| [lobby-worlds-and-social.md](lobby-worlds-and-social.md) | The layer every family arrives through: the unified `LobbyManager` state machine (party + queue + friends + invites), disposable temp-world leasing and the warm pool, reconnect/cleanup, world naming and generation, on-disk layout, lobby protection/nav items/HUD, and lobby NPCs. |

Nothing starts without this page's machinery — read it before assuming how players get into a world.

## Challenge source material

[`challenges/`](challenges/) holds the 15 researched YouTube "Minecraft, but…" formats that seeded
the challenge catalog — one page per format, with the rule, its edge cases, and (where present) the
implementation notes recording how it was bound to the code. It is research input, not a code
reference; the authoritative catalog lives in [experiences.md](experiences.md).

## Changing it

| I want to… | Guide |
|-----------|-------|
| Add or modify a challenge | [`../guides/add-a-challenge.md`](../guides/add-a-challenge.md) |
| Add or modify a minigame | [`../guides/add-a-minigame.md`](../guides/add-a-minigame.md) |
| Turn a YouTube format into a challenge | [`../guides/research-a-youtube-challenge.md`](../guides/research-a-youtube-challenge.md) |
| Work on worlds / world generation | [`../guides/work-on-worlds.md`](../guides/work-on-worlds.md) |
