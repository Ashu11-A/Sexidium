# Challenge research notes

Fifteen YouTube **"Minecraft, but…"** formats, one page per format: the rule as the videos play it,
the edge cases that break it, and — on the pages that have been implemented and written back — an
*Implementation Notes (sexidium)* section recording the server-safe bound that was actually shipped
(radius, per-tick budget, catch-up behaviour).

These are **research input, not a code reference.** The authoritative list of what exists is
[`ChallengeCatalog.java`](../../../packages/core/src/main/java/com/sexidium/core/game/experience/ChallengeCatalog.java),
described in [`../experiences.md`](../experiences.md). The catalog holds **27** challenges; the 15
below are the subset that came from a researched video format.

## The formats

**Status** — *impl notes* means the page carries an `Implementation Notes (sexidium)` section tying it
to the shipped code; *research only* means the challenge is registered but the page is still just the
format study. Every id below is registered in the catalog.

| File | Format name | `ChallengeCatalog` id | Status |
|------|-------------|-----------------------|--------|
| [break-one-break-all.md](break-one-break-all.md) | Breaking a Block BREAKS THEM ALL | `breakonebreakall` | research only |
| [chained-together-multiplayer.md](chained-together-multiplayer.md) | Chained Together (Multiplayer) | `chained` | research only |
| [classic-skyblock.md](classic-skyblock.md) | CLASSIC Skyblock | `classicskyblock` | impl notes |
| [every-chunk-is-random.md](every-chunk-is-random.md) | Every Chunk Is Random | `randomchunks` | research only |
| [every-minute-block-deleted.md](every-minute-block-deleted.md) | Every Minute a Random Block is Deleted | `blockdeleter` | research only |
| [every-swing-hits-everything.md](every-swing-hits-everything.md) | Every Swing Hits Everything | `cleave` | research only |
| [everything-i-look-at-multiplies.md](everything-i-look-at-multiplies.md) | Everything I Look At MULTIPLIES | `lookmultiplies` | impl notes |
| [grow-every-minute.md](grow-every-minute.md) | I GROW Every Minute | `growing` | research only |
| [jumping-enchants-things.md](jumping-enchants-things.md) | Jumping Enchants Things | `jumpenchants` | research only |
| [jumping-multiplies-everything.md](jumping-multiplies-everything.md) | Jumping Multiplies EVERYTHING | `jumpmultiplies` | impl notes |
| [mobs-duplicate-when-hit.md](mobs-duplicate-when-hit.md) | Mobs Duplicate When Hit | `mobduplication` | research only |
| [random-blocks-spawn-when-walking.md](random-blocks-spawn-when-walking.md) | Random Blocks Spawn When You Walk | `walkingblocks` | research only |
| [random-events.md](random-events.md) | RANDOM EVENTS | `randomevents` | impl notes |
| [random-item-giver-skyblock.md](random-item-giver-skyblock.md) | RANDOM ITEM GIVER Skyblock (One Block) | `randomskyblock` | impl notes |
| [random-layer-one-chunk.md](random-layer-one-chunk.md) | RANDOM LAYER One Chunk | `randomlayers` | impl notes |

## Adding one

Do not hand-write a page here. Follow
[`../../guides/research-a-youtube-challenge.md`](../../guides/research-a-youtube-challenge.md): harvest
the videos with `yt-dlp`, mine titles/descriptions/transcripts for the rule and its edge cases, design
the server-safe bound *before* writing code, then implement via
[`../../guides/add-a-challenge.md`](../../guides/add-a-challenge.md) and record the provenance back
into the page as an *Implementation Notes (sexidium)* section.
