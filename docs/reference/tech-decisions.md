# Reference: UI Techniques & Library Decisions

A condensed decision-log for two research efforts: which **GUI surfaces** can serve
Sexidium's three render targets, and which **third-party libraries / gameplay
mechanics** survive the project's hard rules. It records what shipped, what was
rejected and why, and what is deferred — so the same options aren't re-litigated.

**The two governing rules** every entry is judged against:

- **Two adapters, one core.** Sexidium ships a **Paper** plugin (MC 26.1.2) and a **NeoForge**
  mod over one platform-agnostic `core` — though only core + Paper are currently in the
  build; the NeoForge module is not in the tree. A technique must have a
  defined answer on both, or live in `core` as an abstract model rendered with each
  adapter's native primitives (the `CoreCommandService` / reflection-adapter pattern).
- **Cross-play via Geyser.** A large slice of players join from mobile/console on a
  **vanilla Bedrock client** through Geyser + Floodgate. Bedrock loads no mod and no
  Java resource pack, so any Java-pack-dependent visual is invisible to them.
- **No fat-jars.** A dependency is acceptable only as an optional **softdepend**
  (present → delegate, absent → native fallback) or a **true cross-loader library**
  (one API, both a Paper and a NeoForge build). Everything is `compileOnly`.

The single load-bearing finding: **no UI surface renders richly on all three targets.**
Forms are invisible to Java; native `Screen`s and font-pack GUIs are invisible to
Bedrock. So menu *logic* lives in `core` ([`MenuView`](../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java),
a sparse `slot → MenuButton` map) and each adapter renders it with whatever its
target supports. See [menu system](menus.md) for the shipped architecture and
[platform abstraction](platform-and-adapters.md) for the adapter seams.

---

## Section A — GUI techniques

What a **Bedrock-via-Geyser** player actually sees is the load-bearing column.
`[IMPLEMENTED]` = wired into the shipped menu system; `[FUTURE]` = evaluated, not built.

| Technique | Java (no mod) | NeoForge mod | Bedrock-via-Geyser | Status |
|---|---|---|---|---|
| **Chest / container GUI** | native | native | fake-chest, click-only | **[IMPLEMENTED]** the universal floor |
| **Bedrock Cumulus Forms** | invisible | (no NeoForge renderer) | native touch UI | **[IMPLEMENTED]** Paper adapter only |
| **Native font/glyph art** (custom item-model icons + bitmap titles) | rich (with pack) | n/a | blank/tofu | **[IMPLEMENTED]** Java-only, graceful fallback |
| **Map-item pixel canvas** | native | native | held-map renders | **[FUTURE]** |
| **`text_display` + interaction entity** | rich | native | text degraded, tap works | **[FUTURE]** |
| **Vanilla `/dialog`** (MC 26.x) | native | native | translation buggy | **[FUTURE]** |
| **Native NeoForge `Screen`** | n/a | free-form | invisible | **[FUTURE]** |
| **Negative-space font GUI** (ItemsAdder/Oraxen/Nexo) | rich (with pack) | no build | blank/tofu | **REJECTED** |
| **ModelEngine / BetterModel 3D props** | rich (with pack) | no build | invisible | **REJECTED** for UI |

### A.1 Chest / container GUI — the floor `[IMPLEMENTED]`

The baseline every renderer falls back to: a real container whose slots are
`ItemStack` buttons. It is the **only** GUI surface Geyser translates to Bedrock —
by spawning a temporary fake chest block and force-opening it. Consequences a
skeptic must accept: it **silently fails in void/lobby space** (no block to place),
Geyser **can't distinguish left vs right click**, and touch players **can't hover
items** (lore tooltips unreadable). So Sexidium menus are **single-tap, click-only**
with info in item *names* not lore — see [menu system](menus.md). NeoForge
renders the same `MenuView` as a vanilla chest in
[`NeoForgeMenuAdapter`](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/menu/NeoForgeMenuAdapter.java);
no GUI library is shaded on either side (`core` holds only the abstract model).

### A.2 Bedrock Cumulus Forms — the Bedrock answer `[IMPLEMENTED, Paper only]`

The correct touch UI: a native Bedrock form (tappable buttons with images, dropdowns,
toggles, sliders), command-free and **position-independent** (works in lobby/void
where chest GUIs fail). Shipped on the **Paper adapter only** via
[`PaperFormRenderer`](../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperFormRenderer.java),
which projects the same `MenuView` through
[`MenuForms`](../packages/core/src/main/java/com/sexidium/core/menu/MenuForms.java)
(`MenuForms.java:30` — clickable buttons → form buttons in slot order; `null`-handler
buttons → body text). Selection is gated inline, **not** by a generic router:
`PaperMenuAdapter#open` routes to the form only when
`player.isBedrock() && PaperGeyser.bedrockUiAvailable()`
([`PaperMenuAdapter.java:86`](../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperMenuAdapter.java)),
else the chest renders.

> **Correction vs old GUI report.** Forms are **Paper-only**, not the cross-loader
> win the report's "byte-for-byte identical on both adapters via Floodgate-Modded"
> claim implied. NeoForge has **no** form renderer; it serves Bedrock players the
> vanilla chest. There is also **no `MenuRouter`/`MenuModel` SPI** — routing is the
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
[`core/menu/scene`](../packages/core/src/main/java/com/sexidium/core/menu/scene) and
served by an auto-generated pack ([`MenuArt`](../packages/core/src/main/java/com/sexidium/core/menu/MenuArt.java),
[`SexidiumResourcePack`](../packages/core/src/main/java/com/sexidium/core/menu/pack/SexidiumResourcePack.java)).
This is **Java-only by design** with a graceful fallback: Geyser doesn't convert Java
packs and Bedrock has no per-codepoint custom fonts, so a Bedrock player would see
tofu — but Bedrock players get a Form instead (A.2), and a Java player who declines
the pack gets plain vanilla items. See [menu art reference](menus.md).

### A.4 Negative-space font GUIs (ItemsAdder / Oraxen / Nexo / CraftEngine) — `REJECTED`

The flashy "fake a bespoke screen with resource-pack fonts" approach. **Rejected** on
both rules at once: **Paper-only** (no NeoForge build → breaks adapter parity) *and*
**Bedrock-blank** (Geyser delivers no Java font → tofu title, the background art
never appears). Most are also proprietary/paid. Sexidium's own glyph pipeline (A.3)
gives the Java payoff without the hard third-party pack dependency.

### A.5 Evaluated but not built `[FUTURE]`

- **Map-item pixel canvas.** The myth that "Bedrock can't see custom maps" is wrong —
  Geyser has translated map packets since 2020; **held-in-hand** maps render reliably
  on mobile (item-frame murals stay flaky). The rare cross-play *pixel* surface
  (leaderboards, lobby art, minimaps). Paper softdepend MapEngine (AGPL → external
  only) over a native `MapCanvas` fallback; NeoForge writes `MapItemSavedData`.
- **`text_display` + interaction entity.** The one cross-play-safe *worldspace* menu
  primitive: text shows on Bedrock (degraded/flat), and a **tap reaches the server**.
  `item_display`/`block_display` are **invisible** on Bedrock (no Geyser definition) —
  never use them as Bedrock-facing UI.
- **Vanilla `/dialog`.** Conceptually the best cross-loader primitive (packless,
  native to MC 26.x, form-like widgets on Paper *and* NeoForge), but Geyser's dialog
  translation is incomplete (button clicks can error). Adopt for the Java side
  opportunistically; keep Forms as the Bedrock path until Geyser stabilizes.
- **Native NeoForge `Screen`.** The most powerful UI (free-form rendering, `EditBox`,
  drag, scroll) but only for players on the Sexidium NeoForge client; invisible to
  Bedrock *and* vanilla Java. A Tier-3 luxury — never gate core flows behind it.
- **3D models (ModelEngine / BetterModel).** No NeoForge build and invisible on
  Bedrock without a brittle third-party bridge. At most an optional Paper cosmetic
  prop with a vanilla fallback — **never** route a menu through clicking a model.

---

## Section B — Modrinth libraries & mechanics

Only **two** libraries surveyed natively cover *both* Paper and NeoForge with one
API: **WorldEdit** and **VeinMiner**. Everything else is single-side, so the default
answer is **native scoreboard teams / vanilla packets / vanilla entities** in `core`,
with an optional softdepend when the richer library happens to be installed.

| Library / mechanic | Cross-loader? | Decision | Reason |
|---|---|---|---|
| **Native scoreboard Teams** (rank tags) | 1:1 native both sides | **ADOPTED** | Zero deps; one mechanism drives nametag color + tablist sort |
| **BetterHud** (rich Java HUD) | Paper only | **ADOPTED (softdepend, Sexidium-managed)** | The only top-left text surface; present → corner readout, absent → native scoreboard |
| **Floodgate + Cumulus** (Bedrock forms) | Paper softdepend | **ADOPTED (Paper)** | Native Bedrock touch UI; see A.2 |
| **WorldEdit** | one API, both loaders | **FUTURE** | True cross-loader; for schematic-paste arenas — not yet wired |
| **VeinMiner** | ships Paper + NeoForge | **FUTURE** | Cross-loader softdepend for a gather booster |
| **TAB** (`tab-was-taken`) | both, but config-driven | **FUTURE polish** | RGB/animated tags layer over native teams |
| **PacketEvents** | **no NeoForge build** | **REJECTED as dep** | Fabric-only on loader side → native `Clientbound*` packets instead |
| **WorldGuard** | **no NeoForge port** | **REJECTED** | EngineHub deems modded infeasible → native AABB + event cancel |
| **FancyHolograms / DecentHolograms** | Paper only | **REJECTED as dep** | Bukkit-only → vanilla `Display` entities behind a core `Hologram` seam |
| **FancyNpcs / Citizens** | Paper only | **REJECTED as dep** | No NeoForge → native fake `ServerPlayer` + player-info packets |
| **MythicMobs** | Paper only | **REJECTED as dep** | No NeoForge engine → Paper Mob Goal API + native `Goal` classes |
| **Origins / Apoli** | mod-side only | **REJECTED as dep** | Reimplement its small "power = modifiers + tick + cooldown" model in core |
| **Velocitab** | Velocity proxy only | **EXCLUDED** | Runs on neither backend |

### B.1 Rank name-tags — native scoreboard Teams `ADOPTED`

The headline mechanic, **zero-dependency**. Each rank class maps to one scoreboard
team whose color drives the above-head nametag *and* tablist color, and whose
name carries a priority digit so the best rank sorts to the top of the tab list.
Implemented in [`RankTagService`](../packages/core/src/main/java/com/sexidium/core/rank/RankTagService.java)
over the platform [`RankTagAdapter`](../packages/core/src/main/java/com/sexidium/core/platform/RankTagAdapter.java)
seam: Paper uses Bukkit `Team`, NeoForge drives the vanilla `Scoreboard`/`PlayerTeam`
reflectively in [`NeoForgeRankTagAdapter`](../packages/module-neoforge/src/main/java/com/sexidium/neoforge/adapter/ui/NeoForgeRankTagAdapter.java).
The seven ranks (worst→best `OMEGA < EPSILON < DELTA < GAMMA < BETA < ALPHA < SIGMA`,
each with a hex color) live in
[`RankClass`](../packages/core/src/main/java/com/sexidium/core/rank/RankClass.java);
the team id is `"<priority>_<name>"` (`RankTagService.teamName`). The class is derived
from the player's **summed** score across all linked Minecraft names — see
[networking, bot & ranks](networking-bot-ranks.md). **TAB** is the only nametag
library on both loaders and is a possible future RGB/animation polish layer, but it
is config-driven on NeoForge and its API values are temporary, so native teams remain
the source of truth.

### B.2 Rich Java HUD — BetterHud driver `ON at the pinned 26.1.2, probed off elsewhere (softdepend)`

Shipped as an optional Paper softdepend (`compileOnly("io.github.toxicity188:BetterHud-bukkit-api:2.0.0")`)
behind the gate in
[`BetterHudLink`](../packages/module-paper/src/main/java/com/sexidium/paper/adapter/ui/betterhud/BetterHudLink.java).
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
NeoForge would use a native `GuiLayer` (not yet built).

### B.3 Cross-loader wins held for later `FUTURE`

- **WorldEdit** — same `com.sk89q.worldedit` API on Paper *and* NeoForge. The intended
  path for schematic-paste arena lifecycles; **not integrated yet** (no `worldedit`
  reference exists in the build or sources). Native fallback: vanilla
  `StructureTemplate.placeInWorld`.
- **VeinMiner** — ships both builds; a softdepend gather booster, else a capped BFS
  flood-fill on the break event (platform-agnostic core, only the break/drop call
  differs).

### B.4 Why the rest are native-first `REJECTED as dependency`

For every Paper-only library below, the cross-platform answer is the same: keep the
*logic* in `core` behind a thin interface and implement the primitive twice (Bukkit
on Paper, vanilla packets/entities on NeoForge via `NeoForgeReflector`).

- **PacketEvents** — no NeoForge build → core `PacketBridge` over raw `Clientbound*`
  packets (`connection.send`) for fake entities, glow, per-viewer team packets.
- **WorldGuard** — no NeoForge port → native AABB regions + event cancellation
  (`BlockEvent.BreakEvent`, `EntityPlaceEvent`, interact, mob-damage).
- **Holograms (FancyHolograms / DecentHolograms / HologramLib)** — Bukkit-only →
  vanilla `text_display` entities behind a core `Hologram` seam (per-player variants
  via packets where needed).
- **NPCs (FancyNpcs / Citizens)** — no NeoForge → fake `ServerPlayer` +
  `ClientboundPlayerInfoUpdatePacket(ADD_PLAYER)` + add-entity/metadata, skin via
  `GameProfile` textures; core `Npc` interface.
- **MythicMobs** — no NeoForge engine → Paper Mob Goal API
  (`com.destroystokyo.paper.entity.ai`) + native `Goal`/`TargetGoal` on NeoForge;
  core `MobAbility` descriptors.
- **Origins / Apoli** — mod-side only; the *model* (power = attribute modifiers + tick
  action + cooldown + condition) is small and reimplemented in core, mapping ~1:1 to
  Sexidium kits and [composable experiences](experiences.md). Adapters own
  `AttributeInstance.addModifier` (Paper) / `AttributeSupplier` + `PlayerTickEvent`
  (NeoForge).
- **GUI libs (InventoryFramework / Triumph-GUI), NBT-API, particle libs** — Bukkit-only;
  Sexidium already renders menus from `MenuView` natively (Section A) and uses PDC /
  native packets, so none are shaded.

### B.5 Gameplay mechanics worth building (native, no dep)

Reference-only mods (low downloads or single-loader) that inform native
implementations, mapped to existing modes — see [minigames](minigames.md) and
[experiences](experiences.md): **grappling hook** (raycast → per-tick velocity pull,
no Paper lib exists), **dodge-roll / air-dash with i-frames**, **double-jump**
(`setAllowFlight` trick on Paper / `jumpsUsed` counter on NeoForge), **custom on-hit
enchant abilities** (PDC tags on Paper / real `EnchantmentEffectComponents` on
NeoForge), **shaped particle abilities** (geometry in core, emit via each adapter).
Short-duration Discord-event mechanics (Blood Moon, Meteor Shower, rising-lava finale,
Boss Rush, cinematic sequencer) all reduce to a core timer/`Sequencer` firing adapter
primitives (`setSky`, `setLootMultiplier`, `spawnEntity`, title/particle/sound) — they
fit the existing `BaseTimedGame` and `releasePlayerUi`/`restorePlayerUi` lifecycle.

---

## Keeping this current

This doc is a derived decision-log; the **code is the source of truth**. Authoritative
files: menu rendering — [`MenuView`](../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java),
[`MenuForms`](../packages/core/src/main/java/com/sexidium/core/menu/MenuForms.java),
[`PaperFormRenderer`](../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperFormRenderer.java),
`PaperMenuAdapter`, `NeoForgeMenuAdapter`; ranks — `RankTagService`/`RankClass`/
`NeoForgeRankTagAdapter`; HUD — `BetterHudDriver`; deps — `packages/*/build.gradle.kts`.
Update **this file in the same change** that touches them. Triggers: a menu surface
flips `[FUTURE]`→`[IMPLEMENTED]` (or a new renderer/adapter is added), a library is
adopted/rejected or its softdepend gate changes, a `compileOnly` dependency is
added/removed from a `build.gradle.kts`, or the rank set / form-routing condition
changes. Deeper architecture lives in the sibling docs cross-linked above, not here.
