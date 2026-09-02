# Minecraft Game Mode: Jumping Multiplies EVERYTHING

## Core Mechanics
One rule: **every time the player jumps, every entity around them duplicates once.** Not the thing they
are aiming at — *everything* in range, at the same instant. A jump beside a herd doubles the herd; a jump
beside a herd and a dropped stack and a creeper doubles all three. Because each jump doubles what the last
jump already doubled, the count is exponential in jumps, not linear: one becomes two, two becomes four, and
four jumps later the number is no longer something a player can undo.

The trigger is the **jump input**, not upward motion. That distinction is the whole feel of the mode — the
player is punished for something they *chose*, never for something a mob did to them:

| Situation | Multiplies? |
|---|---|
| Normal jump from the ground | **Yes** |
| Jump-attacking (a critical hit) | **Yes** — the crit *is* a jump, so every fight is a mass-spawn |
| Jumping out of water onto land | **Yes** |
| Swimming, or rising through water | No |
| Knocked into the air by a mob, an explosion or a piston | No — the player did not jump |
| Falling, elytra, or riding an entity that jumps | No |

**The entity layer only.** Mobs (passive and hostile, babies included), dropped item entities, projectiles
still in flight, primed TNT, tamed pets and bosses are all in scope. **Placed blocks are not** — a TNT
*block* sitting on the ground is a block, and jumping beside it does nothing at all; the same TNT once lit
is an entity and duplicates like anything else. Nothing about a player duplicates, and players themselves
are never copies.

**Copies are real, not decorative.** A duplicated hostile keeps its aggro, so a mob that was chasing you is
now several mobs chasing you. Duplicated skeletons crossfire each other. Duplicated wolves stay tamed and
stay yours. A duplicated projectile keeps flying. A duplicated boss is a second boss.

**Item stacks copy per entity, not per item.** A dropped stack of sixty-four logs is *one* item entity, so a
jump beside it yields one more entity — not sixty-four more logs, unless the copy is configured to carry the
whole stack. The exploit that falls out of this is the mode's first real discovery: throw the stack out as
many separate one-item drops first, *then* jump, and every drop doubles.

## Impact on Gameplay
- **Movement becomes a tax.** Jumping is how Minecraft is played, and here it costs something every time.
  Getting up a single block is solved with slabs and stairs laid as ramps, which turns routine travel into
  construction. Ender pearls are the mid-game mobility unlock and genuinely change the run.
- **Combat is inverted.** The strongest attack in the game — the crit — is the one move that must never be
  used, so fights become slow, flat-footed and defensive. The real answer is not to fight at all: duplicate
  one tamed wolf into an army and let it do the work.
- **Any resource is infinite from one sample.** One log, one iron, one diamond, one bed. Get a single unit
  out on the ground and the only limit is how many times you are willing to jump.
- **Reflex is the enemy.** The jump key is muscle memory. Most disasters in this mode are accidents — a jump
  taken without thinking, in the dark, next to something that should not have been doubled.
- **The antagonist is entity count, not death.** What actually ends a run is the world becoming unplayable:
  sound stacking into noise, frames collapsing, mobs packed thick enough that the player cannot move through
  their own loot. Dying is survivable; a jump beside the Ender Dragon is not.

## Notable Strategies
- **Split before you multiply:** break a stack into individual ground drops so one jump doubles the *count*
  of entities rather than adding one more stack.
- **Slab-and-stair ramps:** never gain a block by jumping — build the terrain up to you instead.
- **Pearl everywhere:** duplicate one ender pearl into a bottomless supply and teleport in place of jumping.
- **Jump where nothing is:** pick a spot with nothing alive nearby, do all the duplicating there, and leave.
- **Wolf army over swordplay:** copies stay tamed, so one wolf becomes a pack that fights without crits.
- **Duplicate the consumables that matter:** beds and TNT copy exactly like anything else, which turns
  boss-damage strategies that normally cost a whole trip's resources into something you carry one of.

## Endgame Adaptation
The full progression is a normal run with one hand tied: gear up without jumping, pearl instead of climbing,
and reach the Nether and the stronghold with a duplicated stock of everything needed. The End is where the
rule becomes a hard constraint rather than an inconvenience — the Ender Dragon is an entity, so a single jump
in the fight spawns a second dragon, and every jump after that doubles again. The fight has to be won
completely flat-footed, with pets and pre-duplicated supplies substituting for mobility, and the mode's own
punchline is the jump taken *after* the win.

## Memorable Moments
- Realising mid-village that every casual jump so far had been quietly manufacturing a cow herd.
- A cave descent that turns into a swarm of bats, then spiders, then zombies, purely from routine movement.
- Duplicated skeletons immediately turning on each other and fighting the player's battle for them.
- A stack of beds copied into a boss-damage supply that would normally take a whole run to gather.
- Killing the dragon, celebrating, jumping — and watching a second dragon appear.

---

## Implementation Notes (sexidium)

Implemented as a composable experience challenge, `id: jumpmultiplies`, display **"Jump Multiplies"** — see
`packages/core/src/main/java/com/sexidium/core/game/experience/challenges/JumpMultipliesChallenge.java`. Like
every twist it reads its tuning from `experiences.modes.jumpmultiplies` and carries no match lifecycle of its
own. It composes with everything: it never claims loot, never touches the drop pipeline, and never cancels an
event.

**The trigger is a real jump signal, not a Y-delta.** `jumpenchants` detects jumps by sampling upward
movement (`jump-detection-y-delta`), which is fine for a mode where a false positive costs one enchantment
roll. It is *wrong* here: mob knockback, explosion punt and a piston all produce the same upward delta, and
the video's rule is explicitly that being launched does not count. The trigger is therefore a dedicated core
event, `GameEvents.PlayerJumpGameEvent` (non-cancellable — Paper rubber-bands a refused jump back to where it
started), with `jump-cooldown-ms` only as a debounce so one physical jump can never register twice. Unported
platforms never fire it and inherit no-op defaults, matching the parity pattern.

Paper's own `PlayerJumpEvent` is *nearly* that signal: it is raised from the movement-packet handler on the
predicate "was on the ground, is now airborne, Y went up", which cannot distinguish a keypress from a creeper
punt. `PaperEventBridge` closes the gap with a **knockback veto** — it records via `PlayerVelocityEvent` the
last time the *server* pushed each player upwards (a player's own jump never passes through there) and
suppresses any jump inside ~250 ms of one.

**The sweep.** On each accepted jump the challenge scans `radius` blocks around the jumper (clamped down to
what that player can actually see) and asks the platform to clone every eligible entity `copies-per-entity`
times, through the new seam `PlayerAdapter#duplicateNearbyEntities(radius, copiesPerEntity, maxSpawns,
kinds)`. Eligibility is a set of independent switches (`multiply-mobs` / `-items` / `-projectiles` / `-tnt` /
`-bosses`) — all on by default except bosses, because being indiscriminate *is* the mechanic but a second
Ender Dragon should be a choice. Players are not a switch at all: there is no `DuplicableKind` for them.

Paper implements the seam with `Entity#copy(Location)` — a full NBT-level clone with a fresh UUID — so aggro,
taming and its owner, baby state, equipment, custom names, item-stack NBT, a projectile's shooter and a TNT
fuse all carry over in one call, for every entity kind. The old seams (`spawnMob`, `spawnItemEntity`,
`MobHandle#duplicate`, `duplicateLookedAtEntity`) all rebuild from a type string or an `ItemKey` and lose
every one of those, which is why this is a new seam rather than a flag on an old one. The attack target is
re-asserted explicitly, primed-TNT fuses are jittered a tick per copy (N copies on one fuse is one N-fold
blast), and the source entity list is **snapshotted before anything is spawned** — streaming a live
nearby-entities walk into a cloner is an unbounded self-amplifying loop in this mode specifically. Item
entities copy per ENTITY, carrying the whole stack, which is what makes the split-then-jump exploit work.

**Bounding, in the fun-first spirit.** The exponential blow-up is the entertainment, so the caps sit where a
server genuinely breaks rather than where the fun starts — the same reasoning as
`doubledrops.max-drops-per-break: 65536`. Three of them cascade, and nothing else:

| Cap | Default | What it protects |
|---|---|---|
| `max-clones-per-jump` | `1024` | one catastrophic jump cannot spawn unbounded entities in a single tick |
| `max-per-tick` | `256` | the shared `MobRegistry` spawn budget; the challenge sets the **minimum** of it and its own, so it never widens a cap Mob Duplication or Cleave asked for |
| `max-live-entities` | `4000` | a live-entity ceiling over the swept area, measured by `WorldAdapter#countNearbyEntities` |

At the ceiling the mode **refuses to clone and shows "saturated" on the panel**. There is deliberately no
`cull-oldest`: nothing in the repo tracks clone identity, and building it would need per-entity tagging that
survives a chunk unload — so the honest degradation is to stop and to say so. Duplicated item entities are
still consolidated by the server-wide `StackMergeService` exactly as any other item flood, which is what makes
item duplication cheap in practice.

All of the arithmetic — the budget cascade, the reach clamp, the debounce (with an **injected clock**) and the
eligibility set — lives in the host-free `JumpMultiplierRule`, unit-tested by `JumpMultiplierRuleTest`. The
sweep itself runs **inline in the jump's call stack**, never on `runTimer`/`runLater`: those map to Folia's
*global* region scheduler, while the jump listener is already on the player's own region thread.

**HUD.** Lines on the shared per-player info panel (`registry.hud`): total entities this experience has
duplicated with the jump count, the live population against the ceiling, and a "saturated" line when the
ceiling is refusing. A platform without the seam says so instead. Debug adds the radius, the budgets, the
cooldown, the eligible kinds, the tracked jumpers and the last jump's actual clone count. Player-facing text is
localized (`experience.jumpmultiplies.hud.*`) with **en + pt** `MessageKey` entries; no English string lives
in the challenge class.

**Catalog wiring.** A `ChallengeCatalog.register` entry (icon `rabbit_foot`), the `ChallengeCatalogTest` count
raised to **27**, `MenuArt`/`MenuArtIcons` mappings (art shared with `mobduplication`), and an
`experiences.modes.jumpmultiplies` block in `config.yml`.

**Keeping this current:** the owning doc for the Jump-Multiplies experience is this file; edit it here rather
than adding a new page.
