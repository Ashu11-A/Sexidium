# Menus

Sexidium's menu domain is a platform-agnostic chest-GUI framework in `com.sexidium.core.menu`, plus a
native "Nexo-style" custom-art layer and a per-platform render seam. The core builds every screen as one
abstract `MenuView`; adapters render that single model differently per target. There is no menu content in
`config.yml` — menus are code-driven in [`MenuService`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuService.java);
config only tunes resource-pack delivery. The custom art (glyph backgrounds + item-model icons + baked
per-screen scenes) is **purely additive**: every menu is fully usable without it, on every client. See
[graphical interfaces](../reference/tech-decisions.md) for the governing cross-play constraints and
[UI & localization](ui-and-localization.md) for HUD / boss bars / i18n.

Chest buttons and the persistent **lobby hotbar** items share one inheritance-based model built on the
`UiItem` render spec and a single Paper materializer — see
[UI interaction system](ui-interaction-system.md). `MenuButton.visual()` is the chest half of that seam;
`MenuView.plainRows(int)` lets a baked-six-row view render in a compact chest for no-pack viewers (the hub
uses `plainRows(3)`).

## Cross-play rule

A UI technique is acceptable only if it has a defined answer for all three render targets. One `MenuView`
renders per target:

| Viewer | Renderer | Custom art |
|---|---|---|
| Java + pack loaded | `PaperMenuAdapter` chest | Glyph background (or full baked scene) + `item_model` icons |
| Java + pack declined / no pack | `PaperMenuAdapter` chest | None — plain material icons, plain title |
| Bedrock via Geyser/Floodgate | `PaperFormRenderer` (Cumulus `SimpleForm`) | None — Java packs never reach Bedrock; native touch UI instead |

> **A modded-client hook, not a renderer.** Titles carry the invisible `MenuSentinel` marker so a
> hypothetical modded client could re-skin Sexidium's menus; no such client overlay exists in this repo,
> and every shipped viewer gets one of the three rows above.

All menu logic lives in platform-agnostic core; the only seams are `PlayerAdapter` (who/where) and the
`MenuAdapter` SPI (render this view).

## Declarative framework core types

`com.sexidium.core.menu` holds the whole model. None of it touches Bukkit/Minecraft.

| Type | Role |
|---|---|
| [`MenuService`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuService.java) | Builds + opens every screen as a `MenuView`; owns the hub `MenuCatalog`, per-player builder/confirm state |
| [`MenuView`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java) | A chest screen: MiniMessage title, 1–6 rows, sparse `slot → MenuButton` map, optional art |
| [`MenuButton`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuButton.java) | One slot: `icon, amount, name, lore, onClick, headOwner, model` |
| [`MenuContext`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuContext.java) | A click: `PlayerAdapter` + `ClickType` (LEFT/RIGHT/SHIFT_LEFT/SHIFT_RIGHT/MIDDLE/OTHER) |
| [`MenuCatalog`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuCatalog.java) | Ordered registry of hub tabs (`register` / `tabs` / `visibleFor` / `byId`) |
| [`MenuTab`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuTab.java) | One self-describing `/sx` interface |
| [`MenuForms`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuForms.java) | Flattens a sparse view into a Bedrock-form action list + body labels |
| [`MenuSentinel`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuSentinel.java) | Marks a menu title (invisible code point) so a modded client could recognise it |

### MenuView art opt-ins

A view requests art two ways, both ignored by no-pack / Bedrock viewers:

- `background(glyphId)` — paints a `MenuArt` background glyph behind the slots via the title trick.
- `screenArt(sceneId)` — renders the view as a fully baked scene; it also sets `backgroundArt =
  MenuArt.screenGlyphId(sceneId)`, so the title path picks up the screen glyph and every interactive button
  becomes an invisible hitbox (only the baked art shows, clicks intact).
  ([`MenuView#screenArt`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuView.java))

### MenuButton factories

`of` (label + click), `label` (decorative, `onClick == null`), `head` / `headLabel` (a `player_head`
textured with `headOwner`'s skin), and `withModel(modelId)` for a custom `item_model`. `MenuView.add`
fills the first free slot; `set(slot, button)` places exactly. A `null` `onClick` makes the button a
non-interactive label — this is the sole signal `MenuForms` uses to split actions from body text.

### Animated tiles

A `MenuButton` may carry `nameFrames` (a list of MiniMessage name strings) and its `MenuView` be marked
`animated(true)`; the Paper adapter then cycles each such tile's display name in place every few ticks
while the menu is open (`PaperMenuAdapter.animationTick`/`animatePlayerMenu`, cleaned up on close). The
Minigames grid uses this: each mode tile shows a **distinct per-mode colour gradient** that sweeps (the
`nameFrames` rotate the gradient colours) plus a **live "playing now" count** (`GameManager.playersInMode`
+ the quick-play queue) badged as the stack size. The static `name()` is always a valid fallback for
no-animation renderers (Bedrock forms, tests).

### The `/sx` hub is a registry

The main menu is not hand-laid-out. `MenuService.registerHubTabs()` registers each top-level interface as a
`MenuTab`; `openMain` filters by permission via `catalog.visibleFor(player)` and renders the result into a
centered grid via `layoutCentered`. Adding an interface to the hub is exactly one `MenuTab` registration —
no layout-code edits. ([`MenuService#registerHubTabs` / `#openMain` / `#layoutCentered`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuService.java))

`MenuTab` is a record (`id, icon, iconModel, title-fn, lore-fn, visible-predicate, open`) with factories
`of` (constant), `dynamic` (per-viewer lore), `visibleWhen` (gated), `visibleTo`, and `toButton`. Tabs, in
order:

| Tab | Vanilla icon | `item_model` | Visibility |
|---|---|---|---|
| `minigames` | `diamond_sword` | `ICON_MINIGAMES` | always |
| `experience-create` | `tnt` | `ICON_EXPERIENCE_CREATE` | always |
| `experience-mine` | `ender_chest` | `ICON_EXPERIENCE_MINE` | always |
| `browse` | `compass` | `ICON_BROWSE` | always |
| `lobby` | `cake` | `ICON_LOBBY` | `dynamic` (state-aware lore) |
| `friends` | `player_head` | `ICON_FRIENDS` | `dynamic` (pending-invite count) |
| `admin` | `command_block` | `ICON_ADMIN` | `visibleWhen hasPermission(ADMIN_PERMISSION)` |

`openMain` opts the hub into a baked **main-hub** scene for the known tab counts (6 normal / 7 op) when a
pack is loaded; otherwise it renders the plain centered grid. Because the baked art needs six rows but the
plain grid fits in one, the hub calls `plainRows(3)` so a no-pack / Bedrock viewer gets a compact 27-slot
chest while the baked path stays 54 (`PaperMenuAdapter.openChest` picks `size()` vs `plainSize()` per
viewer). See [UI interaction system](ui-interaction-system.md).

### MenuForms (Bedrock projection)

`MenuForms` derives a flat, click-only projection of a sparse view for the Cumulus form renderer:

- **actions** — buttons with a non-null `onClick`, in ascending slot order. The list index is the form
  button id, so `clickedButtonId() → onClick`.
- **labels** — named buttons with `onClick == null`, joined into the form body text.

It reads the same `MenuButton`s the chest renders, so navigation is reused unchanged.

### MenuSentinel (modded-client marker)

Prefixes a title with one invisible Private-Use code point `U+E000`. `encode` / `isSexidium` / `strip` are
pure and idempotent. A modded client could check the marker and re-skin the screen; a vanilla/Geyser client
sees the plain chest because the marker carries no glyph. The consuming overlay is not in this repo (the
NeoForge adapter that originally motivated it was dropped from the build; the marker stays because it is
invisible, harmless, and forward-compatible).

## MenuArt: the single source of truth

[`MenuArt`](../../packages/core/src/main/java/com/sexidium/core/menu/MenuArt.java) declares every icon-model
and shift once, and reads the background glyphs + bitmap fonts from the yml registry
([`menu/backgrounds.yml`](../../packages/core/src/main/resources/menu/backgrounds.yml) via
[`BackgroundCatalog`](../../packages/core/src/main/java/com/sexidium/core/menu/BackgroundCatalog.java)).
`MenuService` reads `MenuArt` to **request** art; `SexidiumResourcePack` reads the same data to **produce**
it — so adding art is one entry (a yml line for a background/font, a Java constant for an icon), not two.
Fonts: `sexidium:menu` (bitmap background glyphs), `sexidium:space` (shift advances), and the medieval
`sexidium:title` / `sexidium:button` caps.

**Icon ids** are `<section>/<name>` paths into the hand-authored `./icons/` sprite set, organised by
section: `currency`, `cursors`, `elo_ranks`, `font`, `gui_buttons`, `system`. An icon's model id is
`sexidium:<id>` and its texture is `item/<id>.png`. The referenced-icon tables:

| Table | Count | Notes |
|---|---|---|
| Hub icons (`ICON_MINIGAMES … ICON_ADMIN`) | 7 | one per hub tab |
| `MODE_ICON_MODELS` | 5 | `race`, `gather`, `tntwar`, `combat`, `fugitive` |
| `CHALLENGE_ICON_MODELS` | 17 | one per `ChallengeCatalog` challenge |
| Action icons (`ICON_BACK … ICON_RELOAD`) | 27 | repeated buttons every screen shares |
| Nav icons (`ICON_NAV_MENU`, `ICON_NAV_EXPERIENCES`) | 2 | lobby hotbar items |

`modeModel(id)` / `challengeModel(id)` return `null` for an unknown id, so `MenuButton.withModel` cleanly
falls back to the vanilla material instead of pointing at a missing model. `icons()` de-duplicates shared
sprites (e.g. `system/plus_gold` backs create / add-friend / npc-create) into a sorted `TreeSet`, and
`spaceAdvances()` is a `LinkedHashMap` — both deterministic so the pack zip's byte order and SHA-1 stay
stable across JVM restarts (clients cache instead of re-downloading each join).

### shift math

`shift(n)` decomposes a pixel offset into `sexidium:space` code points: base `U+E100`, magnitudes
`{1,2,4,8,…,1024}`, two code points per magnitude (even = positive advance, odd = negative). Glyph code
points start at `U+E200`. Pure and unit-tested.

### Chest-frame geometry (current)

The size-keyed background is **one full-window frame per chest size (1–6 rows)**, imported by `scripts/art.py
bake-medieval` from the UltimateGUI **Medieval** pack (`UI/UltimateGUI_medieval_pack/Medieval/generic_<rows*9>.png`) into
a **256×256 font cell**. The source's opaque frame is moved to `(0,0)` inside that cell so runtime placement
does not depend on transparent margins. The art is authored at vanilla GUI proportions — 18 source px = one
slot row (1:1 scale) — with the 9-column slot grid mapped so column 0 lands on the vanilla slot at GUI (8,18);
the player-inventory rows are drawn into the same opaque frame so a background hides them. The visible wooden posts still overhang
the 176px window by ~11px each side and the cresting overhangs ~10px on top. The per-glyph geometry lives in
the **yml registry** (`menu/backgrounds.yml` → `BackgroundCatalog`); `MenuArt` exposes the shared chest values
for call sites/tests (GUI pixels):

| Constant | Value | Source |
|---|---|---|
| `CHEST_WIDTH` | 176 | constant |
| `CHEST_TITLE_X` | 8 | constant |
| `CHEST_FRAME_LEFT_X` | -10 | registry (`left_x`, the visible frame left edge) |
| `CHEST_FRAME_RENDER_WIDTH` | 198 | registry (`render_width`, the visible glyph text advance) |
| `chestGlyphAscent(rows)` (1–6) | `{23, 23, 23, 23, 23, 23}` | registry (`ascent`) |
| `chestGlyphHeight(rows)` (1–6) | `{256, 256, 256, 256, 256, 256}` | registry (`height`) |

`frameShift(glyphId)` and `titleReturnShift(glyphId)` read each glyph's own `left_x`/`render_width` from the
registry. The PNG files are 256×256 font cells, but their visible content is normalised to start at `(0,0)`;
this avoids relying on transparent left/top margins that the Minecraft bitmap-font loader may ignore when it
computes glyph advance. `MenuArtChestTest` cross-checks the 256 canvas and verifies the visible slot grid maps
to vanilla `(8,18)`.

### The yml background registry

`packages/core/src/main/resources/menu/backgrounds.yml` is the config-driven source for every background
glyph and bitmap font. It reuses the ItemsAdder/Nexo `font_images` shape (`id → { path, y_position,
permission }`) and extends each entry with the render geometry the native title-trick needs (`kind`,
`rows`, `height`, `ascent`, `left_x`, `render_width`). `BackgroundCatalog` (a tiny dependency-free
YAML-subset parser — `core` keeps its zero-runtime-dep footprint) loads it; `MenuArt.glyphs()` assigns code
points in declaration order (load-bearing for the pack SHA-1) and `SexidiumResourcePack` emits one font
provider per entry. **Adding a chest size or a baked screen is a yml edit, not a code change.** A `fonts:`
section in the same file declares the medieval bitmap fonts (below).

### Baked per-screen backgrounds

A view can instead render a fully composed per-screen background. `SCREEN_BG_IDS = {main-hub,
match-lobbies, social-lobby}`; the glyph id is `screen/<id>` (`screenGlyphId`), the texture is
`ui/screens/<id>.png`, stored as a 256×256 font cell. The scene content is rendered at the six-row chest window
size (`176×222`) and pasted into source `(0,0)`; with `height 256`, `ascent 13`, and `left_x 0`, that source
window maps back to GUI `(0,0)` in-game. These are produced by the scene baker below.

## Scene art rendering layer

`com.sexidium.core.menu.scene` is a headless Java2D system that **composes the baked per-screen
backgrounds**. It is fully unit-tested and entirely undocumented in the older docs.

### Model

- [`Scene`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/Scene.java) — `id` + canvas
  `width`/`height` + an ordered `List<Element>` drawn back-to-front. Built via a fluent `Builder`.
- [`Element`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/Element.java) — sealed,
  permitting `Fill` (solid/rounded rect, packed ARGB), `Sprite` (atlas id + `Fit` of
  `STRETCH`/`CONTAIN`/`COVER`/`NONE`), and `Text` (`fontId`, ARGB, H/V align). Colours are packed `int`s so
  the model carries no AWT dependency.
- [`Box`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/Box.java) — the GUI-pixel rectangle
  primitive (`inset`, `translate`, `centeredChild`, `intersects`, …).

### Renderer and assets

- [`SceneRenderer`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/SceneRenderer.java) —
  rasterises a `Scene` to a `BufferedImage` deterministically: forces `java.awt.headless=true` on class
  load, text antialiasing off (pixel-art look), nearest-neighbour upscale + bicubic downscale.
- [`BitmapFont`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/BitmapFont.java) — one PNG per
  char. **Display** fonts (`tintable == false`) draw the hand-authored `item/font/char_*` caps verbatim;
  **body** fonts (`tintable == true`) are alpha masks drawn multiplied by the requested colour (and can be
  built from an AWT logical font). Unknown chars advance by the space width and draw nothing.
- [`ComponentAtlas`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/ComponentAtlas.java) — maps
  a sprite id to a PNG, blending in-memory `register` overrides with a lazy cached lookup under a base dir
  (`item/system/home → <base>/item/system/home.png`). A miss returns `null`; the renderer skips it, so a
  single absent asset never aborts a compose.
- [`SceneAssets`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/SceneAssets.java) — wires a
  renderer from `assets/menu-art` (icons) + the **medieval window frame** (`assets/ui/chest/chest_6.png`,
  registered as the `frame` sprite; falls back to the first PNG in `UI/frame`), with the display font from
  the medieval title caps (`item/font_title`) and a logical body font (it needs lowercase, which the
  caps-only medieval sheets lack). One place so baker, tests, and previews render identically. `Components`
  carries the **wood/parchment palette** the reskinned screens use.

### Medieval bitmap fonts (`fonts:` registry section)

The pack ships two uppercase-Latin bitmap fonts sliced from the medieval typography sheets by `scripts/art.py
slice-typography`: `typography_title` → `sexidium:title` (chunky plaque caps, `item/font_title/char_<A-Z>.png`)
and `typography_button` → `sexidium:button` (small button caps, `item/font_button/char_<A-Z>.png`). They are
declared in the `fonts:` block of `menu/backgrounds.yml` (`font` key, texture `dir`, `chars`, `height`,
`ascent`, `space`); `SexidiumResourcePack` emits each as `assets/sexidium/font/<id>.json` (a `space`
provider plus one `bitmap` provider per character). The sheets carry **no lowercase and no digits**, so the
fonts are for controlled uppercase labels (the scene baker's plaque/tile caps; a live `<font:sexidium:title>`
is only safe for A–Z + space text). The scene baker uses `item/font_title` as its display font, so the baked
screens and the shipped live font are the same typography.

### Components and templates

- [`Components`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/Components.java) — the reusable
  vocabulary built from primitives: `frame`, `plaque`, `gridTile`, `listRow`, `pill`, `count`,
  `presenceDot`, `signalBars`, `divider`, `headWell`, `backButton`, plus the palette. The frame draws an
  **opaque** base so a baked screen hides the vanilla chest grid entirely.
- [`SceneTemplates`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/SceneTemplates.java) —
  per-screen builders for the 3-screen slice (`mainHub`, `matchLobbies`, `socialLobby`). It owns
  `HUB_SLOTS = {10, 12, 14, 16, 29, 31, 33}`, `HUB_TILE_W`/`HUB_TILE_H`, and `hubHitGroups()` which maps
  each hub tile to its disjoint group of clickable slots (a big baked tile is hit by a click anywhere it
  covers, not just its centre slot).
- [`ChestGrid`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/ChestGrid.java) — maps a slot
  index to its pixel `Box`: `ORIGIN (8, 18)`, `SLOT 18`, `ICON 16`, and `slotsIntersecting(box)` for the
  hit groups. This is the bridge that makes a baked screen clickable: art at `iconBox(n)` and the hitbox in
  slot `n` coincide.

### Baker

[`SceneBaker`](../../packages/core/src/main/java/com/sexidium/core/menu/scene/bake/SceneBaker.java) bakes each
slice scene to `ui/screens/<id>.png`: it supersamples at `PACK_SCALE = 6` for clean AA, downscales to the
logical `176×222` six-row window size, and pastes that window into a 256×256 font cell at source `(0,0)`.
CLI: `pack <menuArtDir> <frameDir>` (commit the output) or `preview …`. The gradle task
`:packages:core:bakeMenuScreens` runs `SceneBaker pack assets/menu-art UI/frame`.

### Live wiring

`PaperMenuAdapter.openChest` treats a view as a baked screen when `packLoaded && view.screenArt() != null &&
the glyph exists`. It then forces a 6×9 inventory, routes hub clicks through `hubHitGroups` (today only
`main-hub`), and **suppresses every non-head button item** so only the baked glyph shows. Only `main-hub` is
opted in at runtime (in `openMain`, all-7-tabs case). ([`PaperMenuAdapter#openChest` / `#screenButtons`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperMenuAdapter.java))

## Resource pack: generation, hosting, delivery

### Generation

[`SexidiumResourcePack.build()`](../../packages/core/src/main/java/com/sexidium/core/menu/pack/SexidiumResourcePack.java)
emits a deterministic zip + its SHA-1 from `MenuArt`:

- `pack.mcmeta` — `min_format`/`max_format` `84` (MC 26.1.2's `pack_version.resource_major`). Modern
  Minecraft reads these two keys; `supported_formats` was removed from the schema and `pack_format` is
  only still read for packs declaring a format below 65. Bump `SexidiumResourcePack.PACK_FORMAT` in the
  same change that bumps `PAPER_VERSION` in `scripts/init-paper.sh`.
- `assets/sexidium/font/menu.json` — one `bitmap` provider per background glyph (from `BackgroundCatalog`).
- `assets/sexidium/font/space.json` — the `space` provider from `spaceAdvances()`.
- `assets/sexidium/font/{title,button}.json` — the medieval bitmap fonts (a `space` provider + one `bitmap`
  provider per character), with their per-char glyph textures (`item/font_{title,button}/char_<A-Z>.png`).
- Per referenced icon: `items/<id>.json` + `models/item/<id>.json` + `textures/item/<id>.png`.
- The glyph textures.
- Every `texturePaths()` entry (the full `./icons/` set) as a **bare texture, no model**, for future UIs.

Fixed entry timestamps (`2020-01-01`) keep the bytes — and therefore the SHA-1 — stable. Without a
`TextureSource` the build ships generated placeholder PNGs.

**Per-locale lang blanking (note the side effect).** The pack also writes
`assets/minecraft/lang/<code>.json` with `{"container.inventory": ""}` for ~130 locales
(`VANILLA_LANG_CODES`). This blanks the vanilla "Inventory" label the client paints under the menu (the
client only falls back to `en_us` for a *missing* key, never a present one, so it must ship per-locale).
**Side effect:** the inventory label is hidden on *all* containers — survival inventory, chests, etc. — for
any player who loaded the pack. Accepted because the pack is server-gated.

### Hosting

[`ResourcePackServer.start()`](../../packages/core/src/main/java/com/sexidium/core/lib/net/ResourcePackServer.java)
— **lives in `core.net`, not `core.menu.pack`** — builds the pack from `bundled/menupack-textures` (staged
art) + `manifest.txt`, then either:

- hosts the zip itself on `ui.resource-pack.bind:port` at `/sexidium.zip`, or
- when `ui.resource-pack.url` is set, advertises the external URL — preferring a configured `sha1` and
  warning loudly if it is absent (the hosted file must be byte-identical to the generated pack or every
  client rejects it).

Any failure (port in use, out-of-range port, bad bind) degrades to "no pack"; it never crashes plugin
enable. Java clients then keep the plain chest menus.

### Build staging and the test server

- **`prepareMenuPack`** (root `build.gradle.kts`) stages `-PmenuPackZip` / `-PmenuPackDir` into
  `build/generated/menu-pack/bundled/menupack-textures/<path>` and writes a `manifest.txt` of every staged
  path; core's resources pick it up, so it lands in the jar. No property → placeholder art.
- **`bakeMenuScreens`** (`packages/core/build.gradle.kts`) re-bakes `assets/ui/screens/*.png`.
- **`scripts/art.py`** is a single tool with subcommands: `gen-menu-art` (import `./icons/` →
  `assets/menu-art/item/<section>/*`), `bake-medieval` (the **live** chest-frame generator — copies the
  UltimateGUI medieval `generic_<slots>.png` 256×256 canvas → `assets/ui/chest/chest_<rows>.png`), `slice-typography`
  (slices the medieval font sheets → `assets/menu-art/item/font_{title,button}/char_<A-Z>.png`), and
  legacy/utility commands `bake-overhang`, `cut-chest`, `extend-chest`, `align-chest`, `extract`, `montage`,
  `debug`, `name-zip`.
- **`scripts/init-paper.sh`** `build_menu_pack_zip` zips `assets/menu-art` (icons **and** the sliced
  `font_title`/`font_button` glyphs) + `assets/ui/chest` + `assets/ui/screens` into
  `build/sexidium-menu-pack.zip`, passes `-PmenuPackZip`, and regenerates missing PNGs via `scripts/art.py`
  first (`bake-medieval` for frames, `slice-typography` for fonts, `gen-menu-art` for icons);
  `configure_sexidium_menu_pack_if_present` points `ui.resource-pack.host` at the LAN IP.

### Config

`config.yml` → `ui.resource-pack`. (This is the only menu-related config; menu *content* is code-driven.)

| Key | Default | Meaning |
|---|---|---|
| `enabled` | `true` | master switch for building + serving the pack |
| `url` | `""` | external host override; when set the built-in host stays off |
| `sha1` | `""` | SHA-1 of the file at `url`; required when hosting custom art externally |
| `host` | `""` | public host/IP players connect to; required for remote players |
| `bind` | `0.0.0.0` | local interface the built-in host binds |
| `port` | `8788` | built-in host port (must be client-reachable) |
| `required` | `false` | `true` kicks players who decline (not recommended for cross-play) |
| `prompt` | `"<gradient…>Sexidium</gradient> … accept for the full look."` | MiniMessage prompt on the accept screen |

> In-world **decor** (spinning NPC podiums, hub centrepiece) is a separate feature under `ui.decor`. It
> reuses `MenuArt` `item_model` ids but is otherwise out of scope here; see the in-world decor docs.

## Per-platform adapters

### Paper (Bukkit-native, no InvUI)

- [`PaperMenuAdapter`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperMenuAdapter.java)
  implements `MenuAdapter` and is itself a Bukkit `Listener`. A custom
  [`SexidiumMenuHolder`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/SexidiumMenuHolder.java)
  marks the inventory; clicks and drags are cancelled, and only the clicked button's `onClick` fires (on the
  main thread, where `InventoryClickEvent` already runs). `setPackGate` supplies the per-player pack-loaded
  predicate (default: nobody).
- [`PaperMenuArt`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperMenuArt.java)
  converts `<glyph:id>` / `<shift:n>` tags to font components via two MiniMessage instances: `ART` renders
  them, `PLAIN` strips them (so a no-pack viewer never sees a literal tag). For a pack-loaded viewer the
  title composes `<shift:frameShift><glyph:glyphId><shift:titleReturnShift>` + the title (the literal shift
  numbers come from `MenuArt` and differ per glyph). Glyphs are forced **WHITE** because vanilla multiplies
  the title colour into the bitmap (un-coloured glyphs render ~25% dark).
- `toItemStack` calls `meta.setItemModel(NamespacedKey)` for pack-loaded players only, guarded so an older
  server falls back to the vanilla material.
- [`PaperSkullSkins`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperSkullSkins.java)
  textures a `player_head` button: live profile if online → SkinsRestorer via reflection (soft-depend) →
  offline Mojang profile.
- [`PaperResourcePackService`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperResourcePackService.java)
  offers the pack on join under a **stable UUID derived from the SHA-1** (so another plugin's / a proxy's
  pack never wrongly flips the gate), tracks `SUCCESSFULLY_LOADED`, exposes `loaded(UUID)` + `onLoaded`
  (used to refresh the lobby hotbar item-models without a relog), and **skips Bedrock players** entirely.

### Bedrock (Cumulus forms)

[`PaperFormRenderer`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperFormRenderer.java)
builds a Cumulus `SimpleForm` from `MenuForms`: actions become form buttons, labels become body text. It
flattens MiniMessage to plain text (Bedrock shows `§x` hex as garbage), attaches `mc-heads.net` face URLs to
roster buttons, and re-schedules each tap onto the main thread as a plain `LEFT` click. Used only when the
plugin is set, the player `isBedrock()`, and `PaperGeyser.bedrockUiAvailable()`; a `LinkageError` disables
forms permanently (missing Cumulus), a `RuntimeException` falls back to the chest once. The Cumulus API is a
runtime softdepend, never shaded.

## Player-facing screens and mechanics

Every screen is a chest GUI of `rows × 9` slots built in `MenuService`. The full per-screen slot
walkthrough and the icon catalogue (what each sprite depicts, the vanilla fallback material, and the
per-screen layouts) are the legacy guide content — broadly accurate for content; verify slot numbers
against `MenuService` when in doubt. The reusable mechanics:

- **Tap-again confirm** (5 s window) for destructive actions — arms on the first tap, confirms on a second
  matching tap. There is no shortcut past it on either platform: a shift-click used to confirm outright,
  which in a chest is the reflex gesture for "move this item", and only LEFT/RIGHT count as the second
  beat — a number key, `Q`, `F` or a double-click re-arms instead (`MenuSupport.isTap`). Works on
  Bedrock, where Geyser maps every tap to `LEFT`. The token is `"<verb>:<entityId>"`; `MenuSupport.confirmButton`
  owns arming, the 5 s window and the re-render. Current users: **Delete** (`delete:<id>`) on the
  experience manage screen, **Take a backup now** (`backup:<id>`) on the Backups screen, and
  **Restore** / **Refresh** / **Duplicate** / **Delete** (`restore:` / `refresh:` / `duplicate:` /
  `deletebackup:`) on a copy's own screen. **Every one of those tokens must differ**: there is exactly
  one `pendingConfirm` slot per player, so a shared token would let arming a verb on one screen disarm
  another on the next. A tile whose cap makes it inert is not drawn at all: with
  `max-backups-per-experience: 0` there is no "Take a backup now" tile, rather than one that arms and
  then refuses.
- **A fixed tile budget belongs to the primary rows.** When a list has more rows than slots, the derived
  ones give way — never the rows that are the only route to a thing. "My Experiences" has 18 tiles
  (slots 9–26) for worlds *and* their backups, so `ExperienceMenu.tilesFor(owned, 18)` drops copies,
  never worlds; and anything a budget can drop needs a second screen that always lists it (every copy of
  a world is listed on that world's **Backups** screen, reached from slot 15 of its manage screen).
  Otherwise the row is unreachable — with no
  error, and no way for the player to tell it still exists. See
  [experiences § Backups](../gameplay/experiences.md#backups-a-backup-is-an-experience).
- **An answer that comes from another node is reported, not assumed — onto a screen that is already
  gone.** Anything routed through `ExperienceCommandRouter` (delete, backup, restore, refresh,
  duplicate) **closes the menu on the click** (`serverAdapter.menus().close(clicker)`), sends a
  localized *working* line, and then reports the real outcome from the callback — re-checking
  `online()` and hopping to the clicker's region with `scheduler().runForPlayer`, because the router's
  recovery tick runs on the **global** region. **Nothing is redrawn**: not one outcome callback reopens
  a screen. Two reasons, both learned the hard way. The screen had to go *on the click* because a
  routed verb stays in flight until its ACK times out, and every tile on a still-open screen is still
  clickable — two more taps queued a second copy on top of the one already being taken. And the
  callback must not reopen, because the ACK can be minutes behind the click: a chest (a Cumulus popup,
  on Bedrock) springing open over whatever the owner walked off to do is the one thing this must not
  do — the armed lore says in as many words that they can keep playing meanwhile. Localized chat,
  not an action bar: the answer routinely arrives seconds later, long after an action bar would have
  faded, and chat is the whole answer — the new row is on the Backups screen when they next open it.
  See [experiences § Backups](../gameplay/experiences.md#backups-a-backup-is-an-experience).
- **Click-only player pickers** (Add Friend, NPC skin, invite) — a reusable roster of online-player heads,
  replacing every "type a name" command.
- **Single-choice sub-screens** — when options are mutually exclusive they get their own screen instead of
  a tile in a multi-select grid. **Choose World** (`ExperienceMenu.openExperienceWorldType`) is the model:
  the experience builder carries one **World** tile showing the current map type; tapping it opens a
  screen where exactly one of Normal / Nether / The End / the generated SkyBlock maps can be active
  (✔-marked), with un-pickable options rendered as greyed-out labels that say why. This is what makes two
  world-generating twists impossible to select at once — see [experiences](../gameplay/experiences.md#world-type-map-selection).
- **Sticky toggles read their state in the label** — a setting tile shows its current value in the item name
  (`Keep Inventory: ON` / `OFF`, `World: Nether`) rather than relying on a hover tooltip, because Bedrock's
  flat tap-grid has none. The experience builder carries the **World** and **Keep Inventory** tiles; the
  manage screen carries the same Keep-Inventory tile, applied live.
- **Reuse a registered icon before adding one.** `MenuArt.model(id)` is plain string concatenation and
  validates nothing, and no test asserts that every `MenuArt.ICON_*` constant appears in a
  `MenuArtIcons.*_ICON_IDS` array — so a new constant that is never registered compiles, passes the whole
  build and renders as a broken model in game. So the whole backup feature added **no new icon**: the
  backup and **Refresh** tiles wear the already-registered `MenuArt.ICON_RELOAD` (`system/redo`) over a
  vanilla `bundle` fallback, **Duplicate** reuses `ICON_CREATE` over `lime_concrete`, **More settings**
  reuses `ICON_EDIT_CHALLENGES`, and **Restore** ships model-less over a vanilla `recovery_compass`
  (as the copy rows themselves already do over `bundle`). (`skeleton_skull` is reserved for hardcore
  and nothing else may use it; `chest` is taken by keep-inventory.)
- **A decorative tile is `MenuButton.label(...)` with a real name, never a no-op lambda.** A null
  `onClick` is the *only* signal `MenuForms` has for splitting a Bedrock form's buttons from its body
  text, so a "do-nothing" click handler turns an information panel into a button that lies. The Backups
  header (slot 4) and a copy's identity card (slot 4) are both labels for this reason — on Bedrock they
  read as the paragraph above the buttons, which is exactly right.
- **Two screens for backups, because a copy is name-identical to its world.**
  `MenuService.openBackups(player, sourceId)` is 3 rows: a header label at 4, the **seven most recent**
  copies at 10–16 (ordered oldest-first *among themselves*, so the run reads forwards in time),
  **Take a backup now** at 22 and Back at 18. Most-recent, not first-seven: with
  `max-backups-per-experience` raised above seven this screen used to draw the seven **oldest**, which
  left the copy the owner had just taken drawn nowhere at all — not here, and not in "My
  Experiences", which spends its own tile budget on worlds first and drops copies. When more copies
  exist than fit, the header names how many older ones are kept and points at
  `/sx admin backup list <id>`, because a row nothing draws is a row the owner cannot tell still
  exists.
  `MenuService.openBackup(player, backupId)` is the copy itself: identity card at 4, then Enter (10),
  Restore (11), Refresh (12), Duplicate (13), Rename (14), an empty 15 so Delete never sits against
  another verb, Delete (16), Back (18) and **More settings** (22) — which opens the copy's ordinary
  manage screen rather than re-implementing visibility, keep-inventory and hardcore. Restore and
  Refresh are hidden, not shown refusing, when the source world has been deleted.
- **All clicks and drags cancelled** — only the button's action runs; vanilla item movement is blocked.
- **Back button** at `size − 9` on most screens.
- **Two locked hotbar nav items** in the lobby world: a `compass` → menu/lobby and an `ender_chest` → My
  Experiences. Both are pack-gated and refreshed via `PaperResourcePackService.onLoaded` so the custom
  model appears without a relog.

## Tests

| Area | Tests |
|---|---|
| `core.menu` | `MenuArtTest`, `MenuArtCoverageTest`, `MenuArtChestTest`, `MenuArtAssetsTest`, `MenuArtCalibrationTest`, `MenuArtTilingTest`, `MenuButtonTest`, `MenuCatalogTest`, `MenuFormsTest`, `MenuSentinelTest`, `MenuConfirmGestureTest`, `MenuTeardownTest`, `OpenScreenRefreshTest`, `ExperienceBackupMenuDesignTest`, `ExperienceBackupManagerDesignTest`, `ExperienceMenuTilesTest` |
| `core.menu.pack` | `SexidiumResourcePackTest` |
| `core.menu.scene` | `SceneRendererTest`, `SceneTemplatesTest`, `ChestGridTest` |
| Adapters | `module-paper` `PaperFormRendererTest`, `PaperMenuClickTypeTest` |

`MenuArtCoverageTest` fails the build if the mode/challenge icon tables drift from the registry /
`ChallengeCatalog`; `MenuArtAssetsTest` fails if a referenced sprite has no committed PNG;
`MenuArtChestTest` guards the chest-frame heights and the ≤256 render-width invariant. (`DecorPaletteCoverageTest`
/ `DecorManagerTest` belong to the in-world decor feature, not this domain.)

`MenuConfirmGestureTest` covers the core half of the arm-then-confirm gesture — one `pendingConfirm`
slot per player, the window, the distinct tokens, and `MenuSupport.isTap` accepting **only** LEFT and
RIGHT. The Paper half is `PaperMenuClickTypeTest`, and it is deliberately **behavioural**: it fires a
real `InventoryClickEvent` through `PaperMenuAdapter.onClick` for every `org.bukkit.event.inventory.ClickType`
the server can deliver and asserts that exactly LEFT and RIGHT come out as something `isTap` accepts.
That exhaustive form is the point. `DOUBLE_CLICK` mapping to `LEFT` is the bug this pair exists to
catch — Bukkit sends a double-click as a LEFT event *followed by* a DOUBLE_CLICK one, so it handed the
gesture both halves from one flick of the mouse — and the earlier guard for it read `PaperMenuAdapter.java`
as **text**, asserting a source string was absent, which any re-spelling of the `switch` (another arm,
an `if`, a hop through `ClickType.isLeftClick()`, which answers true for DOUBLE_CLICK) would have
passed while the bug was back.

## Keeping this current

The code is the source of truth; this doc is a derived view. The authoritative sources are
`com.sexidium.core.menu` (especially `MenuArt`, `MenuService`, `MenuView`, and the yml registry
`menu/backgrounds.yml` + `BackgroundCatalog`), `com.sexidium.core.menu.scene` (+ `scene.bake.SceneBaker`),
`com.sexidium.core.menu.pack.SexidiumResourcePack`, `com.sexidium.core.net.ResourcePackServer`, the
Paper `adapter/menu` package, `scripts/art.py` + `scripts/init-paper.sh`, and the
`ui.resource-pack` block in `config.yml`. Update this doc in the same change that touches any of them.
Triggers: a new class/scene/template added to the domain; a background/font entry or geometry change in
`backgrounds.yml`; a change to a `MenuArt` icon table, glyph metric, or `shift`/scene geometry; a new
`MenuTab` or screen; a change to the pack format, `VANILLA_LANG_CODES`, or hosting logic; or an
added/removed `ui.resource-pack` config key.
</content>
</invoke>
