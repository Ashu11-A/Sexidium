# Minecraft Game Mode: Everything I Look At MULTIPLIES

## Core Mechanics
The rule, stated at the very start: "not just mobs, but everything I look at multiplies. Items, mobs, or even arrows can duplicate just by looking at them." Whatever entity sits in the player's crosshair is cloned on a repeating tick — a cow they stare at spawns a second cow, a dropped stack on the ground copies itself, an arrow in flight splits into a spread. Crucially, "the longer the video goes on, the more everything multiplies": the effect starts weak (one copy roughly every three seconds) and the player deliberately ramps it up. Progress is gated by **milestones** — the player calls them advancements/upgrades — and each milestone cleared bumps the **multiplier** so more copies appear, faster. The player narrates the multiplier the whole way ("now we're at 10 times", "x25", "x40", "x60+"), treating the climbing number as the real progression bar of the run. The stated ceiling is enormous: "it can get all the way to 1,000, so we haven't even started yet."

The multiplier is a double-edged sword: looking at a cow or an iron ingot makes you "super rich", but looking at a creeper, a spider, a blaze, or the Ender Dragon makes you "super running for my life" — so the whole game becomes about controlling *what* your crosshair is pointed at.

## Impact on Gameplay
- **Infinite resources from a single sample:** the player realizes "I basically just need one of every block I need on me at a time, and then I literally have infinite." One iron, one diamond, one steak, one obsidian — stare at it and you have a stack.
- **The multiplier is the progression:** clearing milestones (mine stone, hold three cobblestone, get iron, get a diamond, mine obsidian, enter the Nether, kill a blaze, get netherite, enchant, reach the End) is how the multiplier climbs. Early milestones tighten the interval (3s → 2s → 1.5s → 1.25s → 1s), later ones raise the copies-per-look outright (x2 → x5 → x10 → x25 → x40 → x60+).
- **Hostile mobs are lethal by accident:** "we need to not look at hostile mobs like at all. That will be literally the death of us." A single glance at a spider, creeper, skeleton, blaze, ghast, wither skeleton, Enderman, or the dragon spawns a swarm.
- **Suffocation from your own copies:** cramming duplicated piglins into a hole for infinite trades nearly kills the player — "I started suffocating because of too many mobs in there."
- **Arrows become free multishot:** "if I look at arrows, they also spread and will shoot multiple things… it's basically multishot for free," which is the only viable way to fight blazes without looking at them.
- **Performance is the final boss:** the framing question is literally "will the Ender Dragon fry my computer?" — the mode is meant to teeter on the edge of chaos.

## Notable Strategies
- **Duplicate the sample, not the source:** carry one of each key block/ingot and multiply it on demand rather than mining more.
- **Look away from danger:** fight blazes and the dragon by staring at the ground and letting multiplied arrows do the work ("let my arrows do the talking").
- **Milestone hunting:** actively chase the next required item (three cobblestone, one iron, one diamond, obsidian, a blaze rod, netherite, an enchant) because each one raises the multiplier.
- **Infinite trades:** drop one piglin into a hole and multiply it into an infinite bartering farm, fed by multiplied gold.
- **Sharpness forever:** duplicate an enchanted sword and combine copies on an anvil to stack the enchantment "basically forever."

## Endgame Adaptation
The player runs the full progression: overworld gear and an iron/diamond duplication economy, a Nether trip for blaze rods (x25), infinite piglin trades and netherite (x30), enchanting for x40, then the End at x50–x60+. The Ender Dragon fight is won "without looking at it" — arrows on autopilot while the crosshair stays pinned away from the dragon and the swarms of Endermen its glance would multiply.

## Memorable Moments
- Building "an entire army of golems" for a single iron, every one of them enraged.
- "Infinite diamond blocks… just to flex on the haters."
- A wall of burning zombies at the pillager outpost ("if you burn, you die").
- Nearly suffocating inside a pit of duplicated piglins while fishing out ender pearls.
- Killing the dragon at x60+ purely on multishot arrows, never once looking at it.

---

## Implementation Notes (sexidium)

Implemented as a composable experience challenge, `id: lookmultiplies`, display **"Look Multiplies"** — see `packages/core/src/main/java/com/sexidium/core/game/experience/challenges/LookMultipliesChallenge.java`. Like every twist it reads its tuning from `experiences.modes.lookmultiplies` and carries no match lifecycle of its own.

**The look-tick.** A tracked timer fires every `look-interval-ticks` (default 20 = once per second). For each online participant it asks the platform to duplicate whatever entity is in the player's crosshair, `multiplier` copies, out to `look-distance` blocks. This is a new platform seam, `PlayerAdapter.duplicateLookedAtEntity(maxDistance, copies, items, mobs, projectiles)`, which ray-traces from the player's eyes and clones the hit entity: a mob spawns same-type copies, a dropped item stack copies itself, an arrow spawns parallel arrows with slight spread (the "free multishot"). Paper implements it via `World.rayTraceEntities`; unported platforms inherit the no-op default (NeoForge deferred), matching the parity pattern.

**The multiplier & the milestone ladder.** A single shared, persisted `level` (state key `lookmultiplies.level`) indexes an ordered ladder of milestones, each `{ item, count, multiplier }`. The party's *collective* held count of the next milestone's item is checked every look-tick; once it reaches `count` the level advances (looping so several rungs can clear at once, as in the video) and the multiplier steps up. The ladder is shared and never regresses, so a persistent experience keeps its progress across disconnects and restarts. Mob spawns are bounded by `max-copies-per-tick` so the late-game "x60" snowball cannot fry the server — the same "bounded chaos" ethos as Mob Duplication's per-tick budget.

**The progress bar near the hotbar.** The climbing multiplier is surfaced on the vanilla **XP bar** directly above the hotbar (`PlayerAdapter.setExperienceBar(level, progress)`): the level number is the current multiplier (the "x" the player watches climb — x2, x10, x25, x60), and the green fill is the party's progress toward the next milestone (`held ÷ required`). It is not a boss bar. `resetPlayer` / the host's leave reset clear the bar so it never leaks into another mode.

**The next required item, on the right panel.** The challenge contributes HUD lines to the shared per-player info panel on the right (`registry.hud`): the current multiplier, and the **next item required to advance** rendered as a client-localized `<lang:item.*>` name with its required count and live collective progress (e.g. `Next: 3× Cobblestone (1/3)`). At the top of the ladder it reads as maxed out.

**Keeping this current:** the owning doc for the Look-Multiplies experience is this file; edit it here rather than adding a new page.
