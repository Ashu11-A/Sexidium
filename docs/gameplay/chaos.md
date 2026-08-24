# Chaos mode

The third game family beside **experiences** and **minigames**. One shared, open-ended survival world
where **each player** is independently assigned a random set of challenge twists whose effects apply
only to them, and a timer reshuffles every player's twists every few minutes — resetting the previous
effects first (size, potion effects, health scale, …).

- Start: `/sx start chaos` (player-accessible; brings the initiator + their online party into a fresh
  leased world). Others join the running match with `/sx join chaos`. Aliases: `roulette`, `random`.
- Registered in `CoreGameRegistryInitializer` (`CATEGORY_CHAOS`), so it auto-registers on Paper and
  NeoForge — the mode is platform-agnostic (challenges are).

## How per-player scoping works

`ChaosGame` (`com.sexidium.core.game.chaos`) reuses the experience composition layer wholesale: the
shared `DropPipeline` / `DamagePipeline` / `HealthModel` / `BlockBreakService` pipelines plus the
`ExperienceHud`. Each player's challenges are wired through:

- **`PlayerScope`** — the `ChallengeContext` a single player's challenges see: `online()` is just the
  owner, `isParticipant` true only for the owner, state and published capabilities are private to the
  scope, while the shared pipelines and `modePopulation()` (the full roster) come from the host.
- **`ScopedChallengeRegistry`** — wraps every contribution with an owner gate before the shared
  pipeline: a per-player Double Drops only multiplies the owner's breaks, a per-player Shared Life only
  governs the owner's health, a per-player HUD line only shows on the owner's panel.
- Per-player **state** is a fresh in-memory `ExperienceState` each cycle (no carried-over scale/
  multiplier), so two players running the same twist never collide.

Because unchanged challenges already gate on `isParticipant(...)` / loop `online()`, ~all of them work
per-player with no changes. The reset/reroll uses the same `ChallengeBinding` teardown machinery the
live experience editor uses (`onStop` → cancel timers/bars → unregister pipeline contributions →
`Challenge.resetPlayer`).

## Adapted challenges

| Challenge | In Chaos |
|---|---|
| Shared Life | The one challenge-class change: a single holder mirrors the **mode-wide average** current health (`SharedLifeChallenge.averagingMode`). In a 1-player experience this is just the player's own health (unchanged). |
| Shared Inventory | Per-player single holder → effectively the player's own inventory (harmless no-op). |
| Chained Together | Per-player single holder → no rope (needs ≥2). Harmless. |
| World-mutating (Walking Blocks, Random Chunks, Block Deleter, Break-/Chunk-break) | Triggered around the owner only; the resulting blocks are visible to all (one shared world). |
| Everything else | Naturally per-player (Growing, Jump Enchants, Cleave, Mob Duplication, Randomizer, Double Drops, XP Health, …). |

## Config

```yaml
chaos:
  cycle-minutes: 10            # how often every player's twists reset + reroll
  challenges-per-player: 3     # random twists assigned to each player per cycle
  pool: []                     # eligible twist ids; empty = the whole catalog
  announce-rolls: true
```

Each twist reads its own tuning from `experiences.modes.<id>` (no duplication).

## Persistence — Chaos worlds are experiences

Started from the experience builder's **🎲 Chaos** button, a Chaos world is created as a persistent,
owner-registered **experience** (`ExperienceManager.Experience` with `mode = "chaos"`, empty
`challenges`), so it:

- appears in **My Experiences** with its own identifying item (a nether star) and counts against the
  owner's `worlds.experiences.max-per-player` limit (`ExperienceService.createAndStartChaos`);
- runs in a never-GC'd persistent world via the shared `GameManager.startExperience(worldName, modeId, …)`
  path (the `modeId` overload lets that persistent path build a `ChaosGame` instead of an `ExperienceGame`);
- **saves each player's inventory + position** exactly like a hand-built experience: `ChaosGame` reuses
  `ExperiencePersistence` (decoupled from `ExperienceGame` via the `PersistenceHost` interface) to
  restore on entry, capture on disconnect / return-to-lobby (clearing the live inventory only once saved),
  autosave periodically, and flush on stop. The random per-player twists are intentionally **not**
  persisted — they reshuffle every cycle anyway — only inventory + position are.

Re-entering a Chaos world from My Experiences re-rolls fresh twists while restoring the saved
inventory/position (`ExperienceService.enterOrStart` branches on `Experience.isChaos()`). The `/sx start
chaos` command still runs a transient (unsaved) match in a leased world.

## Notes / follow-ups

- Inherently-shared twists (Shared Inventory / Chained) degrade gracefully to a single holder rather
  than forming "clubs" of co-rollers; a club grouping is a possible enhancement.
- A mid-restart Chaos *match* is not auto-rehydrated (the world + per-player `.yml` are preserved, so the
  owner simply re-enters from My Experiences); in-session disconnect/reconnect IS restored.
