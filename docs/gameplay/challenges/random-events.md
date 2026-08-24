# Minecraft Game Mode: RANDOM EVENTS

## Core Mechanics
At random intervals a **chaotic event** fires on everyone at once. You never know what is coming: a burst of speed, sudden levitation, a zombie siege, TNT raining from the sky, a lightning bolt, every player's position shuffled, or a shower of free items. Events are short, loud, and funny — the fun is the constant "what just happened?!" interruptions to whatever you were doing. It is designed to be **layered on top of any other challenge** (a skyblock grind, a survival run) to keep it unpredictable.

## Impact on Gameplay
- **Nothing is safe:** you can be mid-build when TNT Rain or Thor's Wrath (lightning) goes off.
- **Swings both ways:** some events help (Second Wind full heal, Free Feast, Item Rain, Speed Demon), others hurt (Sudden Hunger, Toxic Cloud, Slug Mode, Creeper Party).
- **Group chaos:** Position Shuffle teleports everyone onto each other; a Zombie Siege or Cow Stampede drops a crowd on the whole party.

## Notable Strategies
- **Stay flexible:** don't commit to a risky spot right when an event is due.
- **Bank the good ones:** Item Rain and chests are worth grabbing fast before the next event flips the table.
- **Buddy up:** shared events (shuffle, sieges) are easier to survive as a group.

## Timing & selection
An event fires **every 1 minute 30 seconds** by default (`min-interval-seconds` / `max-interval-seconds`, both 90). Selection is a **weighted shuffle bag**: an event that just fired is heavily down-weighted (its weight ÷ 8) so events you **haven't seen yet** are strongly preferred; once **every** event has fired at least once the bag **resets** and all weights return to full. This spreads variety across the catalog while still allowing the occasional rare early repeat.

## The 64 built-in events (detailed)

### Original 24
| Event | What it does |
|---|---|
| Speed Demon | Speed II for 15s — you zoom around. |
| Slug Mode | Slowness II for 12s — you crawl. |
| Moon Walk | Levitation for 6s — you drift upward. |
| Flea Legs | Jump Boost IV for 15s — enormous jumps. |
| Hulk Smash | Strength II for 20s — you hit like a truck. |
| Noodle Arms | Weakness I for 15s — feeble hits. |
| Toxic Cloud | Poison I for 8s — ticking damage. |
| Lights Out | Blindness for 8s — you can't see. |
| Dizzy Spell | Nausea for 12s — the screen warps. |
| Ghost Mode | Invisibility for 15s. |
| Disco Fever | Glowing for 20s — outlined through walls. |
| Night Owl | Night Vision for 30s. |
| Floaty Feet | Slow Falling for 20s — gentle descent. |
| YEET! | You're launched into the air with random sideways velocity. |
| Second Wind | Full heal + fed + Regeneration. |
| Sudden Hunger | Food bar emptied to zero. |
| Free Feast | Food refilled + Saturation. |
| Zombie Siege | 5 zombies spawn on each player. |
| Creeper Party | 3 creepers on each player. |
| Cow Stampede | 6 cows on each player. |
| TNT Rain | 3 primed TNT fall from above each player. |
| Thor's Wrath | Lightning strikes each player. |
| Position Shuffle | Everyone is swapped onto the next player's spot. |
| Item Rain | 3 random goodies drop above each player. |

### Negative (20) — 50% of the extended set
| Event | What it does |
|---|---|
| Spring Cleaning | 💥 Your entire inventory is wiped. |
| Molasses | Slowness III + Mining Fatigue II for 12s. |
| Wilting | Wither II for 6s — damage through armor. |
| Rumbly Tummy | Hunger for 15s — food drains fast. |
| Earthquake | Bounced into the air + Nausea. |
| Spider Swarm | 6 cave spiders per player. |
| Bone Zone | 5 skeletons per player. |
| Phantom Menace | 4 phantoms per player. |
| Endermania | 3 endermen per player. |
| TNT Gift | 💥 A primed TNT spawns at your feet. |
| Ring of Fire | 💥 A ring of fire blocks surrounds you. |
| Cobweb Prison | 💥 Cobwebs box you in. |
| Into Darkness | Blindness + Darkness for 8s. |
| Venom | Poison II for 10s. |
| XP Thief | All your XP is set to zero. |
| Butterfingers | 💥 Your held item takes heavy durability damage. |
| Witch Hunt | 3 witches per player. |
| Silverfish Swarm | 8 silverfish per player. |
| Jelly Legs | Weakness II + Slowness II for 15s. |
| Sinkhole | 💥 The block under your feet vanishes — mind the void! |

### Neutral (10) — 25%
| Event | What it does |
|---|---|
| Disco Lights | A burst of coloured particles around you. |
| Oink Oink | 5 pigs per player. |
| Mini Me | You shrink to ~half size. |
| Embiggen | You grow to ~1.6× size. |
| Blink | Teleported a few blocks in a random direction. |
| BOO! | A jump-scare message + enderman scream. |
| Batty | 6 bats per player. |
| Frosty Friends | 3 snow golems per player. |
| Axolotl Party | 4 axolotls per player. |
| Sparkles | A firework twinkle + particle shower. |

### Positive (10) — 25%
| Event | What it does |
|---|---|
| Sugar Rush | Speed II + Haste II for 20s. |
| Wolverine | Regeneration II for 10s. |
| Tank Mode | Resistance II for 12s. |
| Hero Time | Strength + Speed + Resistance for 20s. |
| Midas Touch | 3 gold items drop above you. |
| Care Package | 2 useful items (diamond/steak/tools) drop above you. |
| Asbestos Suit | Fire Resistance for 30s. |
| Golden Heart | Absorption II + Regeneration. |
| XP Jackpot | +100 XP. |
| Pogo Stick | Jump Boost V + Slow Falling for 20s. |

The 💥 events change the world or inventory (destructive), so they carry lower selection weights than the harmless ones.

---

## Implementation Notes (sexidium)

A brand-new, **reusable random-event engine** in `com.sexidium.core.game.experience.events`, deliberately decoupled from any one challenge so **any challenge can drive it**:
- `RandomEvent` — a value (id, name, weight, action) — new events are just list entries, so the engine "supports more than 20" by construction.
- `RandomEventContext` — the small surface an event acts on: the players to affect, the world, a `Random`, and a broadcast channel. A challenge supplies a concrete context; a test supplies a fake one.
- `RandomEventEngine` — holds a weighted catalog and picks one via a **weighted shuffle bag**: fired events are down-weighted (÷8) so unfired events are preferred, and the bag resets once all have fired (`firedCount()` exposes progress). Announces and runs the pick. Fully unit-tested (100% line/branch).
- `RandomEventCatalog` — the 24 default events above, each built from existing platform seams (potion effects, launches, `spawnMob`/`spawnTnt`/`strikeLightning`, teleports, item drops) so they run on any backend and are null-safe (no world / no players / no position = no-op). World events act in each **player's own world** (`player.world()`, via the shared `forEachInWorld` helper) rather than a single context world — that is what fixed the item-drop (Midas Touch, Item Rain, Care Package) and particle (Disco Lights, Sparkles) events, which were silently no-oping. Potion effects resolve by their **modern registry key** (`Registry.EFFECT`, with a legacy `getByName` fallback), so every effect event actually applies.

The **`randomevents` — Random Events** challenge (`RandomEventsChallenge`) is just the driver: it schedules the engine on the interval between `min-interval-seconds` and `max-interval-seconds` (both **90s = 1m30s** by default), fires an event on the party, and shows a **"Next event in m:ss"** countdown on the sidebar HUD. Because the engine is standalone, another challenge could embed it directly without this challenge.

**Keeping this current:** this file owns the Random-Events engine + challenge; edit it here.
