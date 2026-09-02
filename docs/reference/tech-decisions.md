# Reference: UI Techniques & Library Decisions

A condensed decision-log for two research efforts: which **GUI surfaces** can serve
Sexidium's three render targets, and which **third-party libraries / gameplay
mechanics** survive the project's hard rules. It records what shipped, what was
rejected and why, and what is deferred — so the same options aren't re-litigated.

**The two governing rules** every entry is judged against:

- **Two adapters, one core.** Sexidium ships a **Paper** plugin (MC 26.1.2) for every game server
  and a **Velocity** proxy module over one platform-agnostic `core`. A technique must have a
  defined answer on every audience that can see it, or live in `core` as an abstract model rendered
  with each adapter's native primitives (the `CoreCommandService` pattern).
  > **History:** a third adapter — a reflection-only **NeoForge** port (`packages/module-neoforge`)
  — was built once and later dropped from the build. Several rejections below cite its absence;
  the reasoning stands, but "no NeoForge build" is no longer a live constraint — the module does
  not exist.
- **Cross-play via Geyser.** A large slice of players join from mobile/console on a
  **vanilla Bedrock client** through Geyser + Floodgate. Bedrock loads no mod and no
  Java resource pack, so any Java-pack-dependent visual is invisible to them.
- **No fat-jars.** A dependency is acceptable only as an optional **softdepend**
  (present → delegate, absent → native fallback) or a **true cross-platform library**
  (one API covering every supported platform). Everything is `compileOnly`.

The single load-bearing finding: **no UI surface renders richly on all three targets.**
Forms are invisible to Java; native `Screen`s and font-pack GUIs are invisible to
Bedrock. So menu *logic* lives in `core` ([`MenuView`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java),
a sparse `slot → MenuButton` map) and each adapter renders it with whatever its
target supports. See [menu system](../interface/menus.md) for the shipped architecture and
[platform abstraction](../architecture/platform-and-adapters.md) for the adapter seams.

---

## Section A — GUI techniques

What a **Bedrock-via-Geyser** player actually sees is the load-bearing column.
`[IMPLEMENTED]` = wired into the shipped menu system; `[FUTURE]` = evaluated, not built.

| Technique | Java (Paper) | Bedrock-via-Geyser | Status |
|---|---|---|---|
| **Chest / container GUI** | native | fake-chest, click-only | **[IMPLEMENTED]** the universal floor |
| **Bedrock Cumulus Forms** | invisible | native touch UI | **[IMPLEMENTED]** Paper adapter |
| **Native font/glyph art** (custom item-model icons + bitmap titles) | rich (with pack) | blank/tofu | **[IMPLEMENTED]** Java-only, graceful fallback |
| **Map-item pixel canvas** | native | held-map renders | **[FUTURE]** |
| **`text_display` + interaction entity** | rich | text degraded, tap works | **[FUTURE]** |
| **Vanilla `/dialog`** (MC 26.x) | native | translation buggy | **[FUTURE]** |
| **Negative-space font GUI** (ItemsAdder/Oraxen/Nexo) | rich (with pack) | blank/tofu | **REJECTED** |
| **ModelEngine / BetterModel 3D props** | rich (with pack) | invisible | **REJECTED** for UI |

> Withdrawn from this table: a **native NeoForge `Screen`** renderer — it was a `[FUTURE]`
> candidate only while the (since-dropped) NeoForge adapter existed. It was never built.

### A.1 Chest / container GUI — the floor `[IMPLEMENTED]`

The baseline every renderer falls back to: a real container whose slots are
`ItemStack` buttons. It is the **only** GUI surface Geyser translates to Bedrock —
by spawning a temporary fake chest block and force-opening it. Consequences a
skeptic must accept: it **silently fails in void/lobby space** (no block to place),
Geyser **can't distinguish left vs right click**, and touch players **can't hover
items** (lore tooltips unreadable). So Sexidium menus are **single-tap, click-only**
with info in item *names* not lore — see [menu system](../interface/menus.md). No GUI
library is shaded (`core` holds only the abstract model; the Paper adapter renders natively).

### A.2 Bedrock Cumulus Forms — the Bedrock answer `[IMPLEMENTED, Paper only]`

The correct touch UI: a native Bedrock form (tappable buttons with images, dropdowns,
toggles, sliders), command-free and **position-independent** (works in lobby/void
where chest GUIs fail). Shipped on the **Paper adapter only** via
[`PaperFormRenderer`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperFormRenderer.java),
which projects the same `MenuView` through
[`MenuForms`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuForms.java)
(`MenuForms.java:30` — clickable buttons → form buttons in slot order; `null`-handler
buttons → body text). Selection is gated inline, **not** by a generic router:
`PaperMenuAdapter#open` routes to the form only when
`player.isBedrock() && PaperGeyser.bedrockUiAvailable()`
([`PaperMenuAdapter.java:86`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperMenuAdapter.java)),
else the chest renders.

> **Correction vs old GUI report.** Forms are **Paper-only**, not the cross-platform
> win an early report's "byte-for-byte identical on both adapters via Floodgate-Modded"
> claim implied — and there is **no `MenuRouter`/`MenuModel` SPI**: routing is the
> adapter's `open()` method over the concrete `MenuView`.

Dependency hygiene matches the rule: `compileOnly` Floodgate + Cumulus pinned to the
versions Floodgate's signatures were compiled against (`floodgate:api:2.2.4-SNAPSHOT`,
`cumulus:1.1.2` — Cumulus 2.0.0 removed the legacy classes that `sendForm` overload
resolution needs), restricted to the `org.geysermc.*` repo groups so it never shadows
other artifacts. Forms are dispatched through `PaperGeyser.sendForm`, which uses
Floodgate when present and the Geyser API otherwise; text is flattened to plain (no
`§x` hex garbage on Bedrock) and roster buttons carry mc-heads avatar URLs.

### A.3 Native font/glyph art — Java polish `[IMPLEMENTED, Java only]`

Sexidium renders its **own** menu art natively (no third-party pack plugin): custom
item-model icons and Nexo-style shift/bitmap-font titles authored under
[`core/menu/scene`](../../packages/core/src/main/java/com/sexidium/core/menu/scene) and
served by an auto-generated pack ([`MenuArt`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuArt.java),
[`SexidiumResourcePack`](../../packages/core/src/main/java/com/sexidium/core/menu/pack/SexidiumResourcePack.java)).
This is **Java-only by design** with a graceful fallback: Geyser doesn't convert Java
packs and Bedrock has no per-codepoint custom fonts, so a Bedrock player would see
tofu — but Bedrock players get a Form instead (A.2), and a Java player who declines
the pack gets plain vanilla items. See [menu art reference](../interface/menus.md).

### A.4 Negative-space font GUIs (ItemsAdder / Oraxen / Nexo / CraftEngine) — `REJECTED`

The flashy "fake a bespoke screen with resource-pack fonts" approach. **Rejected**:
**Bedrock-blank** (Geyser delivers no Java font → tofu title, the background art
never appears). Most are also proprietary/paid. Sexidium's own glyph pipeline (A.3)
gives the Java payoff without the hard third-party pack dependency.

### A.5 Evaluated but not built `[FUTURE]`

- **Map-item pixel canvas.** The myth that "Bedrock can't see custom maps" is wrong —
  Geyser has translated map packets since 2020; **held-in-hand** maps render reliably
  on mobile (item-frame murals stay flaky). The rare cross-play *pixel* surface
  (leaderboards, lobby art, minimaps). Paper softdepend MapEngine (AGPL → external
  only) over a native `MapCanvas` fallback.
- **`text_display` + interaction entity.** The one cross-play-safe *worldspace* menu
  primitive: text shows on Bedrock (degraded/flat), and a **tap reaches the server**.
  `item_display`/`block_display` are **invisible** on Bedrock (no Geyser definition) —
  never use them as Bedrock-facing UI.
- **Vanilla `/dialog`.** Conceptually the best packless primitive (native to MC 26.x,
  form-like widgets on Paper), but Geyser's dialog translation is incomplete
  (button clicks can error). Adopt for the Java side opportunistically; keep Forms as
  the Bedrock path until Geyser stabilizes.
- **3D models (ModelEngine / BetterModel).** Invisible on
  Bedrock without a brittle third-party bridge. At most an optional Paper cosmetic
  prop with a vanilla fallback — **never** route a menu through clicking a model.

---

## Section B — Modrinth libraries & mechanics

Only **two** libraries surveyed natively cover more than one platform with one API:
**WorldEdit** and **VeinMiner** (Paper + NeoForge — relevant while a NeoForge build was
planned; that adapter has since been dropped). Everything else is single-side, so the default
answer is **native scoreboard teams / vanilla entities / Bukkit primitives** in `core`,
with an optional softdepend when the richer library happens to be installed.

| Library / mechanic | Cross-loader? | Decision | Reason |
|---|---|---|---|
| **Native scoreboard Teams** (rank tags) | 1:1 native both sides | **ADOPTED** | Zero deps; one mechanism drives nametag color + tablist sort |
| **BetterHud** (rich Java HUD) | Paper only | **ADOPTED (softdepend, Sexidium-managed)** | The only top-left text surface; present → corner readout, absent → native scoreboard |
| **Floodgate + Cumulus** (Bedrock forms) | Paper softdepend | **ADOPTED (Paper)** | Native Bedrock touch UI; see A.2 |
| **WorldEdit** | one API, both loaders | **FUTURE** | Cross-platform; for schematic-paste arenas — not yet wired (its API is already what `assets/` tooling uses) |
| **VeinMiner** | ships Paper + NeoForge | **FUTURE** | Softdepend gather booster, else a capped BFS flood-fill |
| **TAB** (`tab-was-taken`) | multi-platform, config-driven | **FUTURE polish** | RGB/animated tags layer over native teams |
| **PacketEvents** | multi-platform | **REJECTED as dep** | A raw-packet lib is exactly the surface Sexidium keeps behind its own seam → native `Clientbound*` packets instead |
| **WorldGuard** | Paper-focused | **REJECTED** | Native AABB + event cancellation covers the lobby/match protection actually needed |
| **FancyHolograms / DecentHolograms** | Paper only | **ADOPTED as softdepend** (FancyHolograms; NPC nameplates) | Bukkit-only; absent → no nameplates, never a crash. The generic hologram seam stays native `Display` entities |
| **FancyNpcs / Citizens** | Paper only | **ADOPTED as softdepend** (FancyNpcs) | Lobby NPCs behind the core `NpcAdapter` seam via `PaperNpcBackend`; absent → NPCs simply do not spawn |
| **MythicMobs** | Paper only | **REJECTED as dep** | Paper Mob Goal API covers what the modes need |
| **Origins / Apoli** | mod-side only | **REJECTED as dep** | Reimplement its small "power = modifiers + tick + cooldown" model in core |
| **Velocitab** | Velocity proxy only | **EXCLUDED** | Runs on neither backend |

### B.1 Rank name-tags — native scoreboard Teams `ADOPTED`

The headline mechanic, **zero-dependency**. Each rank class maps to one scoreboard
team whose color drives the above-head nametag *and* tablist color, and whose
name carries a priority digit so the best rank sorts to the top of the tab list.
Implemented in [`RankTagService`](../../packages/core/src/main/java/com/sexidium/core/data/RankTagService.java)
over the platform [`RankTagAdapter`](../../packages/core/src/main/java/com/sexidium/core/platform/RankTagAdapter.java)
seam: Paper uses Bukkit `Team`s (`PaperRankTagAdapter`).
The seven ranks (worst→best `OMEGA < EPSILON < DELTA < GAMMA < BETA < ALPHA < SIGMA`,
each with a hex color) live in
[`RankClass`](../../packages/core/src/main/java/com/sexidium/core/data/RankClass.java);
the team id is `"<priority>_<name>"` (`RankTagService.teamName`). The class is derived
from the player's **summed** score across all linked Minecraft names — see
[networking, bot & ranks](../operations/networking-bot-ranks.md). **TAB** is a possible
future RGB/animation polish layer, but it is config-driven and its API values are temporary,
so native teams remain the source of truth.

### B.2 Rich Java HUD — BetterHud driver `ON at the pinned 26.1.2, probed off elsewhere (softdepend)`

Shipped as an optional Paper softdepend (`compileOnly("io.github.toxicity188:BetterHud-bukkit-api:2.0.0")`)
behind the gate in
[`BetterHudLink`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/ui/betterhud/BetterHudLink.java).
It is a **driver**, not a feature: any challenge or game mode declares a `HudSurfaceSpec` and this
renders it in a screen corner — a surface vanilla Minecraft does not have — for the players it can
reach, while core's `SidebarHudDriver` renders the same declaration on the scoreboard for everyone
else. Absent, disabled or incapable → the sidebar carries all of it, which is not a degraded mode but
the ordinary one, since it is what every Bedrock player gets regardless.

Because BetterHud 2.0.0 exposes **no programmatic object creation** (`HudManager`/`PopupManager` are
read-only lookups; `TextManager` is empty), each spec is compiled to yml (`BetterHudAssetCompiler`) and
written into a `sexidium/` subtree of BetterHud's own folders that Sexidium **owns outright**
(`BetterHudAssetStore`): regenerated every boot, stale files deleted, a SHA-256 manifest so
`betterhud reload` fires only on a real change, and nothing outside that subtree touched. That
boundary is what retired the old never-overwrite rule — and with it the documented upgrade hole where
a server that had once run an older layout kept it forever.

Row text is resolved through a registered placeholder (`[string:sexidium <surface> <key>]`) that
returns the **whole line, already rendered in that viewer's language**, so the generated yml contains
no words and the corner readout is translatable — which it never was when its labels lived in an
operator-owned file. BetterHud wipes third-party placeholder registrations on every reload, so they are
re-registered from `addReloadEndTask`.

> **This is why the Paper pin is 26.1.2 and not 26.2 — see F62 in
> [known issues](known-issues.md).** BetterHud's pack replaces the client's core text shaders, and it
> picks its shader set from a hardcoded pack-format table claiming a wider range than the shaders it
> ships. **26.1.2 is format 84 and genuinely matches**; 26.2 (format 88) renamed those shaders and split
> them into `IS_GUI`/`IS_SEE_THROUGH` variants with no lightmap bound, so the same overlay corrupts
> every vanilla GUI screen.
>
> `BetterHudLink`'s **capability probe** (`PackFormats`) detects that and reports no capabilities, so
> declared surfaces render on the sidebar and stay legible instead of becoming white boxes. It stops
> *Sexidium* using BetterHud, not BetterHud sending its pack: on a version it does not cover,
> **removing the plugin** is the only complete fix. The shipped `hud.betterhud.enabled` default is
> **`true`** — the pin is a covered version and the corner readout is what the pin is for — and
> `scripts/init-paper.sh` turns it **off** when the pin is not covered (`betterhud_overlay_matches`).
> On paths the provisioner does not reach (network nodes, hand-installed jars) the capability probe above
> is the only thing standing between a moved pin and white boxes. Network nodes are separately seeded
> `false`, because BetterHud's self-hosted pack is unreachable there (F67).

The tab-list death column beside it is *not* BetterHud — it is a vanilla
`DisplaySlot.PLAYER_LIST` objective (`TabListHandle` → `PaperTabListCounter`), so it
works on Bedrock and on servers without the plugin.

Because BetterHud rides **boss bars** (invisible to Bedrock — it even auto-disables
for Floodgate players), it is **never** load-bearing: everything it shows is also
renderable on the sidebar, and whether a given player gets the sidebar instead is
decided **per player** (`HudSurfaceHandle#activeFor`), never once for a whole match.

Sexidium **owns the BetterHud surface** where the plugin is installed: alongside the
generated subtree it empties `default-hud: [test_hud]`, disables BetterHud's bundled demo
hud/compass/entity-health-popup by renaming them with the leading hyphen BetterHud itself
treats as "skip" (the compass carries `default: true` in its own yml and the entity popup is
trigger-driven, so neither can be stopped per player), and keeps every player wearing only
the surfaces a consumer asked for — so nothing shows in the lobby. Opt out with
`hud.betterhud.manage` / `.exclusive` / `.disable-demo-assets` / `.capability-probe`.

### B.3 Cross-loader wins held for later `FUTURE`

- **WorldEdit** — one `com.sk89q.worldedit` API. The intended path for schematic-paste
  arena lifecycles; **not wired at runtime** (it is build tooling only — see
  [work-on-worlds](../guides/work-on-worlds.md)). Native fallback: vanilla
  `StructureTemplate.placeInWorld`.
- **VeinMiner** — a softdepend gather booster, else a capped BFS flood-fill on the
  break event.

### B.4 Why the rest are native-first `REJECTED as dependency`

For every library below, the answer is the same: keep the *logic* in `core` behind a
thin interface so the primitive stays replaceable (Bukkit on Paper; a native fallback
where the library is absent).

> Several of these rejections were originally argued against "no NeoForge build"; that
> adapter is gone, but the rejections stand on their remaining grounds — the native path
> is simpler than the dependency for what these features actually do here.

- **PacketEvents** → raw `Clientbound*` packets (`connection.send`) where a packet-level
  effect is ever needed (fake entities, glow, per-viewer team packets).
- **WorldGuard** → native AABB regions + event cancellation covers the lobby/match
  protection actually needed.
- **HologramLib** (and other hologram engines) → the NPC nameplates ride FancyHolograms
  (above); anything beyond that is vanilla `text_display` entities behind a core seam.
- **Citizens** → FancyNpcs already covers the lobby-NPC need behind `NpcAdapter`; no
  second NPC engine.
- **MythicMobs** → Paper's Mob Goal API (`com.destroystokyo.paper.entity.ai`)
  + core `MobAbility` descriptors cover scripted mob behaviour.
- **Origins / Apoli** — mod-side only; the *model* (power = attribute modifiers + tick
  action + cooldown + condition) is small and would be reimplemented in core, mapping ~1:1
  to Sexidium kits and [composable experiences](../gameplay/experiences.md) over
  `AttributeInstance.addModifier`.
- **GUI libs (InventoryFramework / Triumph-GUI), NBT-API, particle libs** — Bukkit-only;
  Sexidium already renders menus from `MenuView` natively (Section A) and uses PDC /
  native packets, so none are shaded.

### B.5 Gameplay mechanics worth building (native, no dep)

Reference-only mods (low downloads or single-loader) that inform native
implementations, mapped to existing modes — see [minigames](../gameplay/minigames.md) and
[experiences](../gameplay/experiences.md): **grappling hook** (raycast → per-tick velocity pull,
no Paper lib exists), **dodge-roll / air-dash with i-frames**, **double-jump**
(the `setAllowFlight` trick), **custom on-hit enchant abilities** (PDC tags),
**shaped particle abilities** (geometry in core, emitted through the adapters).
Short-duration Discord-event mechanics (Blood Moon, Meteor Shower, rising-lava finale,
Boss Rush, cinematic sequencer) all reduce to a core timer/`Sequencer` firing adapter
primitives (`setSky`, `setLootMultiplier`, `spawnEntity`, title/particle/sound) — they
fit the existing `BaseTimedGame` and `releasePlayerUi`/`restorePlayerUi` lifecycle.

---

## Keeping this current

This doc is a derived decision-log; the **code is the source of truth**. Authoritative
files: menu rendering — [`MenuView`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java),
[`MenuForms`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuForms.java),
[`PaperFormRenderer`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperFormRenderer.java),
`PaperMenuAdapter`; ranks — `RankTagService`/`RankClass`/`PaperRankTagAdapter`;
HUD — `BetterHudDriver`; deps — `packages/*/build.gradle.kts`.
Update **this file in the same change** that touches them. Triggers: a menu surface
flips `[FUTURE]`→`[IMPLEMENTED]` (or a new renderer/adapter is added), a library is
adopted/rejected or its softdepend gate changes, a `compileOnly` dependency is
added/removed from a `build.gradle.kts`, or the rank set / form-routing condition
changes. Deeper architecture lives in the sibling docs cross-linked above, not here.
