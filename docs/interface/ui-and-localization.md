# UI and Localization

How Sexidium renders server-side UI (boss bars, scoreboard panels, popups/titles,
action bars, the BetterHud corner overlay, in-world decor) and localizes all player-facing text
per client language. Chest GUIs are a separate domain — see
[menu system & art](menus.md). Game-loop integration of these helpers
is in [game framework](../architecture/game-framework.md).

Core (`com.sexidium.core`) decides **what** to show and say; the `module-paper` and
`module-neoforge` adapters decide **how** to render. A game never touches
Bukkit/Minecraft APIs — it calls `AbstractGame` helpers
(`announce` / `popup` / `popupAll` / `timerBar` / `track`) that route through
`ServerAdapter.ui()`, `.messages()`, and `.decor()` to the active adapter.

---

## 1. Architecture and SPI contracts

| Interface | File | Role |
|---|---|---|
| `UiAdapter` | `core/platform/UiAdapter.java` | Factory for boss bars + HUD panels; routes popups. `createPanel` default = `HudPanelHandle.NOOP` |
| `BossBarHandle` | `core/platform/BossBarHandle.java` | One boss bar, per-viewer `show/hide` + `title/progress/close` |
| `HudPanelHandle` | `core/platform/HudPanelHandle.java` | One scoreboard/HUD panel, `line/removeLine/refresh` + per-viewer `show/hide/close`; `NOOP` constant |
| `MessageAdapter` | `core/platform/MessageAdapter.java` | `send/raw/broadcast` of `LocalizedText` or raw MiniMessage |
| `DecorAdapter` | `core/platform/DecorAdapter.java` | Spawns in-world display-entity decor; `NOOP` default |
| `MessageService` | `core/i18n/MessageService.java` | Catalog loading, language resolution, template rendering |

Implementations:

- **Paper** — `PaperUiAdapter` (+ `PaperBossBarHandle`, `PaperScoreboardPanelHandle`,
  `BetterHudDriver`, `PaperMessageAdapter`, `PaperDecorAdapter`).
- **NeoForge** — `NeoForgeUiAdapter` (+ `NeoForgeBossBarHandle`,
  `NeoForgeScoreboardPanelHandle`, `NeoForgeMessageAdapter`). Decor inherits
  `DecorAdapter.NOOP` — **no decor on NeoForge**.

`UiAdapter`'s default `showPopup` (`UiAdapter.java:17-27`) is a fallback with no
`MessageService`: it renders the message **key path** as the text, routing
`WIN`/`ELIMINATION`/`OBJECTIVE` to `showTitle(TitleSpec(text,"",150,1200,350))` and
everything else to `sendActionBar`. Paper and NeoForge override `showPopup` to render
the localized string first.

---

## 2. The i18n system (`core/i18n`)

| Type | File | Purpose |
|---|---|---|
| `Language` | `Language.java` | Enum of locales: `EN("en")`, `PT("pt")` |
| `MessageKey` | `MessageKey.java` | Type-safe enum of every translation key (dotted path, e.g. `combat.win-title`) |
| `MessageArg` | `MessageArg.java` | A named placeholder: `text` (escaped), `mini` (raw MiniMessage), `localized` (nested `LocalizedText`) |
| `MessageArgumentType` | `MessageArgumentType.java` | `TEXT`, `MINI_MESSAGE`, `LOCALIZED` |
| `LocalizedText` | `LocalizedText.java` | A `MessageKey` + its `MessageArg` list — a render *recipe*, not a string |
| `MessageService` | `MessageService.java` | Loads catalogs, resolves the viewer's language, renders to a MiniMessage string |
| `CoreMessageAdapter` | `CoreMessageAdapter.java` | Pure-core default `MessageAdapter` over `MessageService` |

A `LocalizedText` is rendered only at the moment of display, against the
*recipient's* language — which is what lets one boss bar or scoreboard line localize
differently per viewer.

### 2.1 Catalogs and language resolution

Catalogs are UTF-8 `.properties` files on the classpath:

- `core/src/main/resources/lang/en.properties` — **298 keys**
- `core/src/main/resources/lang/pt.properties` — **298 keys**

Both are at parity with `MessageKey` (298 enum constants). `MessageService.reload()`
(`MessageService.java:31`) reads `messages.prefix`, `messages.default-language`, and
`messages.console-language` from `config.yml` (`config.yml:9-13`) and loads one
`Properties` per `Language` via `load()` (`MessageService.java:74-87`).

| Config key | Default | Used for |
|---|---|---|
| `messages.prefix` | `<gradient:#ff5f6d:#ffc371><bold>Sexidium</bold></gradient> <dark_gray>»</dark_gray> ` | Chat prefix prepended by `send()`/`broadcast()` |
| `messages.default-language` | `en` | Fallback when a client's locale is unsupported |
| `messages.console-language` | `en` | Language for all console / non-player output |

Language resolution per `CommandSource` (`MessageService.language`, `:44-49`):
`playerSource() == true` → `Language.fromLocale(source.locale(), defaultLanguage)`;
console / non-player → `consoleLanguage`. `fromCode` (`Language.java:19-31`)
lowercases and maps `-`→`_`, then prefix-matches `pt`→`PT`, `en`→`EN`, else the
fallback — so `pt-BR`, `pt_PT`, `en-GB` all resolve.

### 2.2 Template rendering and argument substitution

`renderMini(language, localizedText)` (`MessageService.java:55-64`) looks up the
template, then literal-replaces each `<name>` token with `MessageArg.render`.
`template()` has a three-level fallback (`:89-98`): target language → English → the
literal `<red>Missing translation: <path></red>`. A key present in EN but absent in
`pt` silently shows English (keep catalogs at parity).

Argument rendering by type (`MessageArg.render`, `MessageArg.java:30-36`):

| Type | Rendering | Use when |
|---|---|---|
| `TEXT` | MiniMessage-**escaped** (`\`, `<`, `>` escaped via `escapeMiniMessage`, `:45-47`) | User/player names, numbers — untrusted/literal text |
| `MINI_MESSAGE` | Inserted **raw** | Pre-built markup such as a `<lang:...>` tag |
| `LOCALIZED` | Recursive `renderMini` of a nested `LocalizedText` | Composing a message out of other translated phrases |

Placeholder names are normalized lowercase + `_`→`-` (`normalizeName`, `:38-43`), so
`MessageArg.text("PLAYER_NAME", x)` fills `<player-name>`.

### 2.3 `<lang:>` translatable item names

How item names appear in each player's own Minecraft language without a per-language
catalog entry per item.

`ServerAdapter.itemTranslationKey(ItemKey)` (`ServerAdapter.java:50`, default `""`)
resolves an item's vanilla translation key. Paper
(`PaperServerAdapter.java:228-237`): `Material.matchMaterial(itemKey.qualifiedName())`
with a `.value()` fallback, then `material.translationKey()`; returns `""` if
unresolved. NeoForge: `NeoForgeServerAdapter.java:218`.

`RaceGame` (`RaceGame.java:583-585`) wraps a non-blank key as the MiniMessage tag
`<lang:` + key + `>` and passes it via `MessageArg.mini` so it is inserted **raw** and
survives into a real translatable `Component`; it falls back to `escapeMini(name)`
when the key is blank (the arg is `objective.itemKey()`). Using `MessageArg.mini`
(not `.text`) is required so the markup is not escaped.

Per-client localization only happens where the rendered string becomes a real
translatable `Component`: Paper chat (Adventure) and the sidebar line via `Team#prefix`
Component; NeoForge chat/sidebar via `NeoForgeMiniMessage` →
`Component.translatable` for `<lang:>`/`<tr:>`/`<translatable:>` tags
(`NeoForgeMiniMessage.java:16-17,140-141,235-238`).

---

## 3. Message delivery (`MessageAdapter`)

`MessageAdapter` has three shapes, each with a `LocalizedText` and a raw-MiniMessage
overload:

| Method | Behavior |
|---|---|
| `send(source, …)` | Prepends `messages.prefix`, renders in the source's language, sends to that source |
| `raw(source, …)` | No prefix; renders in the source's language |
| `broadcast(…)` | `send()` to every online player **and** the console |

`AbstractGame.announce(key, args…)` (`AbstractGame.java:157-163`) builds one
`LocalizedText` and `send()`s it to each `online()` participant plus the console —
each render uses that recipient's language.

Implementations:

- `CoreMessageAdapter` — pure-core default; calls
  `source.sendMiniMessage(renderPrefixedMini(...))`.
- `PaperMessageAdapter` (`paper/.../ui/PaperMessageAdapter.java`) — exposes
  `service()` so the Paper UI handles render per-viewer; `broadcast(String)`
  deserializes once via MiniMessage and uses Adventure `server.sendMessage`.
- `NeoForgeMessageAdapter` — parses MiniMessage to a vanilla `Component` via
  `NeoForgeMiniMessage` (named/hex colors, decorations, `<lang:>`/`<tr:>`);
  gradients/rainbow degrade to the first solid color (`NeoForgeMiniMessage.java:23`).

---

## 4. UI primitives

### 4.1 Boss bars and the `Countdown` timer

`UiAdapter.createBossBar(localizedText, progress, color, overlay)` → `BossBarHandle`:
a single bar with a per-viewer audience (`title`/`progress`/`show`/`hide`/`close`).
Progress is clamped 0..1. `BossBarColor` has 7 values (`PINK`, `BLUE`, `RED`, `GREEN`,
`YELLOW`, `PURPLE`, `WHITE`); `BossBarOverlay` has 5 (`PROGRESS` + `NOTCHED_6/10/12/20`).

Boss bars are driven by `Countdown` (`util/Countdown.java`), a 1-second-tick timer.
The title is a `LocalizedText` with a `<time>` arg formatted `m:ss` or `s`
(`Countdown.format`, `:132-136`; `title()`, `:126-130`). `addViewer`/`removeViewer`
(`:61-77`) map to `BossBarHandle.show`/`hide`; `pause`/`resume` (`:79-83`) gate
`tick()`; `stop()` (`:91`) cancels the task and `close()`s the bar.

`AbstractGame.timerBar(...)` (`AbstractGame.java:209-227`, two overloads — the second
adds an `IntConsumer onSecond`) builds the `Countdown`, `track()`s it, and `start()`s
it for current `online()` players.

### 4.2 Scoreboard panels (`HudPanelHandle`)

`UiAdapter.createPanel(LocalizedText title)` → `HudPanelHandle`:
`line(index, text)` (lower index = higher on screen), `removeLine`, `refresh()`
(repaint once after a batch — per-line repaint flickers), `show`/`hide` per-viewer,
`close`. Core default returns `HudPanelHandle.NOOP`.

**Paper** — `PaperScoreboardPanelHandle` is a **per-player sidebar backed by a single
persistent scoreboard**. The board, objective, and line teams are created **once** per
viewer (`ensureBoard`, `:126-145`) and updated **in place** on `refresh()`/`applyLines`
(`:153-195`) — **not** rebuilt each cycle. This is the anti-flicker design: a fresh
`getNewScoreboard()` + `setScoreboard()` every cycle blinks the sidebar and, on Bedrock,
briefly flashes the per-line score numbers.

Each line is an Adventure `Component` carried as a `Team#prefix` on a unique invisible
entry (`invisibleEntry` uses `§<hex>§r`), preserving per-viewer color **and** `<lang:>`
translatable item names. `MAX_LINES = 15`. `show()` snapshots the player's prior
scoreboard into `previousScoreboards`; `hide()`/`close()` restore it. Title and every
line render in the viewer's language (`render()` uses `service.language(viewer)`).

**`NumberFormat.blank()` trick** — `ensureBoard` sets
`objective.numberFormat(NumberFormat.blank())` to hide the red per-line score number
(scores only order the sidebar). `applyLines` **re-asserts** `NumberFormat.blank()`
only for Bedrock/Geyser viewers (`viewer.isBedrock()`), because those clients can drop
the format between updates and leak the line-count numbers
(`PaperScoreboardPanelHandle.java:141,162-164`).

**NeoForge** — `NeoForgeScoreboardPanelHandle` is a **server-global** sidebar objective
(vanilla has no per-player scoreboard server-side); lines render as styled vanilla
`Component`s in `Language.EN` (server default). `hide()` is best-effort per-client via
`ClientboundSetDisplayObjectivePacket(SIDEBAR, null)`, clearing the global display only
when no viewers remain. It uses `BlankFormat.INSTANCE` for the number column
(`:169-170`), with a 4-arg `addObjective` fallback for older signatures.

### 4.3 Popups, titles, and action bars

`UiAdapter.showPopup(player, PopupType, LocalizedText)` is the single transient-overlay
entry point. `PopupType` has 7 values: `INFO, SUCCESS, WARNING, OBJECTIVE, COUNTDOWN,
ELIMINATION, WIN`.

Routing (Paper `PaperUiAdapter.java:48-61`, NeoForge `NeoForgeUiAdapter.java:34-45`,
core default `UiAdapter.java:17-27`):

| PopupType | Rendered as |
|---|---|
| `WIN`, `ELIMINATION`, `OBJECTIVE` | A **title** via `showTitle(TitleSpec(text, "", 150, 1200, 350))` (ms fade/stay/fade) |
| `COUNTDOWN` | A **title** with **no fade either way**, via `TitleSpecs.countdown(text)` — `(0, 1500, 250)` |
| everything else | An **action bar** via `sendActionBar(text)` |

`COUNTDOWN` is the type for something re-sent once a second, and the missing fades are the
whole difference. With a fade-in, every tick of a countdown dims to nothing and swells back, so
five seconds read as five separate announcements instead of one number counting down; with a stay
shorter than the gap between sends, it disappears between them. The 1.5 s stay deliberately
overshoots the second it covers, which also means the last number expires on its own — nothing has
to send a title to clear it.

`AbstractGame.popup(...)` (`:198`) and `popupAll(...)` (`:202`) wrap this for single /
all-online players. `TitleSpec` (`model/TitleSpec.java`) carries title + subtitle
MiniMessage and fade/stay/fade millis; `showTitle`/`sendActionBar` are `PlayerAdapter`
methods (`PlayerAdapter.java:41,43`).

### 4.3b The tab-list column

`UiAdapter.createTabList(columnId)` → `TabListHandle` puts **one number beside every name in the
tab player list**. Death Resets uses it for each player's death count for the run
(§ `docs/experiences.md`, Death Resets); it is the only surface where a player reads the same
statistic for *everybody* rather than for themselves.

Vanilla scoreboards, not BetterHud, so it works on Bedrock and on servers with no plugins.
Paper implementation: `PaperTabListCounter`, an objective in `DisplaySlot.PLAYER_LIST`.

Three constraints the seam exists to encode:

- **Numbers only.** The player-list slot renders the score and nothing else — the objective's
  title is never drawn. Anything that needs a label belongs on the sidebar.
- **Viewers and subjects are different sets.** `show(player)` enrols someone who should *see*
  the column; `count(player, n)` sets someone's *number*. A spectator is a viewer who is not a
  subject; a player who logged out mid-run is a subject who is not a viewer.
- **It follows the viewer, not the server.** A player being shown a sidebar is on their own
  `getNewScoreboard()` (§ 4.2), so an objective installed on the main board is invisible to
  exactly them. `PaperTabListCounter` installs it on whichever board each viewer is currently
  looking at, deduplicated by identity, and re-resolves every refresh so a board swapped out
  from under it self-heals.

### 4.4 The HUD driver — declared surfaces, rendered wherever the player can see them

A challenge or game mode **declares** a `HudSurfaceSpec` — rows of text, bars, a toast, an
anchor — and a **driver** decides where it lands. There are two, and they are *stacked*, not
chosen between:

| Driver | Draws | Reaches |
| --- | --- | --- |
| `BetterHudDriver` (`module-paper/adapter/ui/betterhud/`) | a free-floating surface anywhere on screen — a corner, or the middle | Java players, when BetterHud is installed, opted in and capable |
| `SidebarHudDriver` (`core/game/hud/surface/`) | the same declaration as scoreboard lines, or as a title/action bar for a popup | everyone, always |

`HudDriverStack` asks the platform driver first and hands the sidebar renderer every player the
platform driver is **not** reaching, decided per player on every render pass. So one declaration
covers the Java player with the corner readout and the Bedrock player beside them, and neither
sees it twice.

> **Why this replaced a single hard-wired overlay.** The previous integration served exactly one
> consumer. It named one layout id, pushed three pre-formatted strings into BetterHud's shared
> `HudPlayer#getVariableMap()`, and required the consumer to hand-write a *second* rendering as a
> `HudContributor` so Bedrock players saw anything. Adding a second element meant authoring three
> yml files and duplicating the readout again — and because the labels lived in an
> operator-owned yml, the corner readout was the one surface in the codebase that
> `MessageService` could not translate.

#### Declaring one

```java
HudSurfaceSpec.persistent("deathresets")
    .anchor(HudAnchor.TOP_LEFT)
    .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
    .bar("pool", LocalizedText.of(MessageKey.EXPERIENCE_SHAREDLIFE_POOL))
    .build();
```

Then open it — a challenge through `ChallengeRegistry.hudSurface(spec)` (which unbinds it when
the challenge is unbound, for live edit / Chaos cycle reset), a game mode through
`AbstractGame.hudSurface(spec)`. Push values with the typed setters: `text`, `number`, `flag`,
`progress`. Add the spec to `HudSurfaceCatalog` so the Paper driver can generate its assets at
startup — a surface opened without being declared logs a line naming it and falls back.

**Elements carry no coordinates.** Position comes from declaration order; `Spacer` is how you ask
for a gap. Geometry is the driver's business, which is where the font scale, the row pitch and
the sign of the y axis now live — pinned by `BetterHudAssetCompilerTest` rather than described in
a comment nothing enforced.

**Templates are localized, values are not.** A row splits into a `LocalizedText` template (the
label, translated per viewer) and a runtime value pushed under its key. The generated yml carries
no words at all, which is what makes the corner readout translatable for the first time.

#### Anchors, and what `CENTER` means to each driver

`HudAnchor` has the four corners and `CENTER`. The difference reaches further than the geometry: a
corner is where a readout lives — something you glance at — and the middle is where the game
interrupts you. So the anchor is what the **fallback** reads too. A centred popup renders for a
player without BetterHud as a vanilla **title** (`PopupType.COUNTDOWN`, §4.3) rather than as an
action-bar line, because a player without the overlay plugin should still be interrupted.

On the BetterHud side, `CENTER` compiles to `gui: { x: 50, y: 50 }` with **no edge inset** — a
centred surface is not measured from an edge — lifted by half its own height so it straddles the
middle instead of hanging below it.

#### Animated rows (`PulseRow`)

`spec.pulse(key, template, restScale, peakScale)` declares a row that **pops to `peakScale` when
its value changes and eases back to `restScale`**. It is what makes the Death Resets countdown
(§ `docs/experiences.md`) legible without reading it: a number that merely replaces the last one at
the same size gives a glancing player nothing to tell "still counting" from "stuck".

Three things about it are worth knowing before declaring one:

* **The value fires it, not a call.** The phase is derived from how long ago the value under `key`
  last became *different*, which `HudValues.changedAt` tracks. There is deliberately no "play the
  animation now" method: every consumer in this codebase pushes its values unconditionally on every
  cadence (pushes are idempotent by contract, so a readout coming up mid-match has something to draw
  on its first frame), and a write-triggered animation would sit pinned at its peak for ever.
* **It needs only `HudCapability.TEXT`.** A driver that cannot animate draws it at its resting size
  and ignores both scales. Demanding a capability of its own would take a perfectly legible
  countdown away from every player without the overlay plugin.
* **BetterHud has no scale equation.** Its layout animations are `x-equation`, `y-equation` and
  `opacity-equation`; `scale` is fixed at parse time. So `BetterHudAssetCompiler` compiles one pulse
  into `PULSE_FRAMES` (8) *stacked* text rows, one per size, each reading its own
  `…_<key>_f<n>` variable, each lifted by half the height it gained so the number swells around a
  fixed centre. Exactly one of those variables holds the line at any instant and the rest hold the
  empty string — the animation is the publisher moving the line down the stack. The **sizes are
  evenly spaced and the clock is eased**, never the other way round: putting the ease-out in the
  size ladder bunches half of eight rungs within a percent of the resting size, giving an animation
  that jumps twice and appears to stop.

An animated surface is republished **every tick** while it is open, on a second publisher pass;
everything else stays on the shared `hud.refresh-ticks` cadence. `HudSurfaceSpec.animated()` is what
splits them, so one countdown does not make every static readout on the server twenty times more
expensive to draw.

#### A live popup is defended from the exclusive sweep

A popup is fired, not worn, so it is not a claim and is never re-asserted — but the exclusive strip
removes everything outside the retain set, and it runs on the shared HUD cadence. `BetterHudClaims`
therefore records each fired popup with a **deadline** and includes the unexpired ones in the retain
set only. Without that, a three-second announcement was taken down after one second, and a countdown
re-shown every second raced the sweep and blinked. The re-add loop still reads the *claims*, never
the wider retain set: re-firing a live popup on every sweep would mean a toast could never be waited
out.

#### A fired popup is invisible to BetterHud's own "what are you wearing" query

The deadline ledger above is not only about the sweep — it is the **only** way to know a popup is on
screen. `HudPlayer#getHuds/getPopups/getCompasses` are all derived from `getHudObjects()`, and the
only thing that ever writes to that map is `HudObject#add`. Showing a popup goes through
`Popup#show`, which records the result in the popup-iterator and popup-key maps and never touches
`getHudObjects()`. So `getPopups()` is empty for a popup that is being drawn right now, and no view
built on it can answer the question.

That matters because `BetterHudSurfaceHandle.activeFor` is what the core stack hands the fallback as
its suppression gate. Asked the wrong way it was **permanently false for every popup**, so the
fallback never stayed quiet: the Death Resets countdown drew a big number through BetterHud *and* a
vanilla title straight through the middle of it, once a second for five seconds. The handle
therefore branches on `spec.kind()` — `SurfaceClaims.showingPopup` (our ledger + "is BetterHud
drawing to this player at all") for a popup, `showing` for a worn surface. The same predicate is
what makes the anti-stacking guard in `show()` real rather than dead code. Driven for real in
`BetterHudPopupGateTest`, against a fake ledger that behaves the way the plugin does; a source-text
guard is what let it stay false in the first place.

Answering it from our own ledger has one hazard the worn question never had, and both halves of the
gate exist to close it. The ledger records what we **fired**, not what BetterHud **drew**, so a
suppressed fallback plus an undrawn popup is a countdown nobody sees — strictly worse than the
duplicate:

- **The object may not have loaded.** `activeFor` therefore starts with `active()`
  (`claims.exists(id)`), so a layout that failed to parse hands the player back to the title. A worn
  surface got this for free: you cannot be wearing an object that does not exist.
- **The popup may have been refused.** Generated popups carry `unique: true`, which files each live
  popup under `UpdateEvent#getKey()` in the player's `getPopupKeyMap()`; showing one whose key is
  already there updates *that* popup and returns null. `UpdateEvent.EMPTY`'s key is a single
  process-wide random UUID, so **every** Sexidium popup shared one slot per player — firing the reset
  countdown while a Random Events toast was up refreshed the toast and drew no countdown.
  `BetterHudPopupEvent` keys by surface id instead. It is a separate file on purpose: a nested class
  naming a BetterHud type puts an assignability check into `BetterHudApi`'s own bytecode, which the
  verifier resolves at link time and which would make that class unloadable without the plugin
  installed.

#### Row colour (`HudColor`)

A row's words come from a MiniMessage template, so the drivers that render through the component
pipeline (the vanilla title, the sidebar) obey the `<red>` in the lang file. BetterHud cannot:
`BetterHudRows` flattens the template to plain text — the markup is gone before the plugin sees the
line — and the colour comes from the generated layout's own `color` key instead. That key used to be
hardcoded `white`, so one declaration came out red on one surface and white on the other.

`HudColor` (Adventure's `NamedTextColor` names) is declared beside the row and read by the compiler,
defaulting to `WHITE`. It is said **twice on purpose**: the tag in the lang file for the component
pipeline, the enum on the element for the atlas renderer, and the two have to agree.
`ExperienceResetDesignTest` asserts both halves of the reset countdown together.

#### Sexidium owns its generated assets, and only those

BetterHud 2.0.0 has **no programmatic object creation** — `HudManager`/`PopupManager`/
`CompassManager` are read-only lookups and `TextManager` is an empty interface. Objects come from
yml on disk and nowhere else. So `BetterHudAssetCompiler` turns each spec into yml and
`BetterHudAssetStore` writes it under a `sexidium/` subdirectory of BetterHud's own `texts/`,
`layouts/`, `huds/` and `popups/` folders (its loader recurses into subdirectories, and skips any
name starting with `-`).

That subtree is **owned outright**: regenerated every boot, with files for surfaces that no
longer exist deleted. Nothing outside it is ever touched. Both rules can therefore hold at once —
operator files are never clobbered, and ours are never stale, which is what fixes the old
"delete the file and restart to be re-provisioned" upgrade hole. A SHA-256 manifest means
`betterhud reload` is dispatched only when something actually changed.

Config lives under `hud.betterhud` (`enabled`, `manage`, `exclusive`, `disable-demo-assets`,
`capability-probe`). `manage` also empties BetterHud's `default-hud:` list and renames its three
bundled demo objects out of the way with a leading hyphen — two of which cannot be removed per
player at all (`default_compass` carries `default: true` inside its own file; `entity-popup` is
trigger-driven).

#### Every hud is drawn inside a boss bar, and that is why one can move it

Worth knowing before touching any coordinate here. BetterHud has no way to draw at an arbitrary
screen position, so it encodes the whole hud into a **boss bar title** and ships a resource pack
whose `rendertype_text` shader decodes it back into screen space. The decode subtracts a constant
baked in at pack-build time — `DEFAULT_OFFSET = 10 + 17 × (bossbar-line − 1)` — which cancels out
where that bar sits. **The pack therefore assumes the payload is riding a bar on one particular
line.** Once it is, a hud at `gui: {x: 0, y: 0}` measures its `pixel` offsets from the top-left of
the window, which is why `EDGE_INSET_X/Y = 10` really means ten screen pixels in.

BetterHud reserves that line by sending `bossbar-line − 1` empty filler bars when the player
connects. But its `merge-boss-bar` default (**true**) throws that away: instead of using its own
bar it rewrites whichever boss-bar packet is passing to carry the payload. An Ender Dragon — or one
of our own `Countdown` bars — then becomes the carrier, on a different line and with a title that
is no longer empty, and every readout slides off its anchor both down and across. So `manage`
pins `merge-boss-bar: false`; `scripts/init-paper.sh` writes the same value so the first connect is
already right. The cost is one boss-bar slot: real bars render a line lower than vanilla.

`bossbar-line` itself is left to the operator — it is what the pack's constant is built from, and
changing it behind their back would desynchronise the two.

#### Installed is not the same as capable

`BetterHudLink` gates on three things in order: the operator switch, then `Class.forName` on
`kr.toxicity.hud.api.BetterHudAPI` (catching `LinkageError`), then a **capability probe**. The
probe exists because BetterHud's pack replaces the client's core text shaders and picks its
shader set from a hardcoded pack-format table that claims a wider range than the shaders it
ships. On a version inside the claim but outside the reality the plugin loads fine, accepts our
layouts fine, and the client draws unknown-character boxes (F62 in
[known issues](../reference/known-issues.md)). When the probe fails the driver reports **no capabilities**,
every surface renders on the sidebar, and the reason is logged once.

It cannot fix BetterHud itself: the plugin sends its own pack regardless. Removing it is still
the only complete fix.

#### Drawing is a per-player question

`HudSurfaceHandle.activeFor(PlayerAdapter)` — not `active()` — is what decides a screen, and on
`CompositeHudSurface` it deliberately answers for the **platform half only**: it is the question
"is this player's readout being drawn somewhere other than the sidebar", which is what decides
whether that player's sidebar should be suppressed. Counting the fallback would make it always
true, and the fallback would be suppressing the surface it renders on.

Consumers no longer wire this by hand. `Challenge.hudSurfaceActive(player)` answers from the
surfaces the challenge declared, `GameHud.suppressPanel(Predicate<PlayerAdapter>)` acts on it,
and `Game.drawsSidebar(player)` is how `LobbyHud` asks. `ownsHud()` stays an explicit opt-in —
it means "my readout is the whole interface", which is a claim only a mode can make.

#### Placeholders are wiped on reload

The row placeholder (`[string:sexidium <surface> <key>]`) resolves the *whole* rendered line for
one viewer, which is the hook that makes translation possible. BetterHud rebuilds its placeholder
containers from scratch on every reload and silently drops third-party registrations, so
`BetterHudDriver.installPlaceholders()` re-registers from `BetterHud#addReloadEndTask`. Without
that hook a single `/betterhud reload` blanks every row until the next server restart, with no
error anywhere to say why.
---

## 5. Decor: in-world display-entity system (Paper-only)

A separate UI-rendering subsystem that dresses the lobby with native display entities.

`DecorManager` (`core/decor/DecorManager.java`) decides **what** decor to place and
drives its lifecycle, mirroring `NpcManager`: a config-gated, deferred startup build
(so the world + NPCs exist first), a `stop()` that despawns everything, and per-NPC
respawn/remove hooks so a podium tracks its NPC. It hands the platform `DecorAdapter`
fully-resolved `DecorProp`s.

`DecorProp` (`core/decor/DecorProp.java`) is pure data (no Bukkit/JOML) describing one
display entity. Supporting types:

| Type | Values / role |
|---|---|
| `DecorKind` | `ITEM_DISPLAY` / `BLOCK_DISPLAY` / `TEXT_DISPLAY` / `INTERACTION` (the last reserved/unused in v1) |
| `DecorBillboard` | `FIXED` / `VERTICAL` / `HORIZONTAL` / `CENTER` (names match `org.bukkit` `Display.Billboard` for `valueOf` mapping) |
| `DecorAnimation` | `spin` / `spinAndBob` / `NONE` |
| `DecorPalette` | per-mode `baseItem` + ARGB glow |

Per minigame-bound NPC (`buildNpcProps`, `:118-145`): a spinning mode-icon
`ITEM_DISPLAY` above the head (when `ui.decor.podiums.enabled`) using
`DecorPalette.baseItem(mode)` + `MenuArt.modeModel(mode)` as the `item_model`, and a
pedestal `BLOCK_DISPLAY` at its feet (glowing in the mode color when
`ui.decor.portals.enabled`). Manual NPCs (no `minigameMode`) get no podium. An optional
`hub_center` centerpiece (`ui.decor.centerpiece.enabled`, default `false`) sits at a
configured anchor.

`DecorAdapter` SPI: `spawn`/`despawn`/`despawnAll` (+ optional `retarget`); `NOOP`
default. Paper implements it (`PaperDecorAdapter`, `paper/.../adapter/decor/`) rendering
native `ItemDisplay`/`BlockDisplay`/`TextDisplay`; NeoForge inherits `DecorAdapter.NOOP`.
By design decor is additive, Java-only flair — display entities are invisible on
Bedrock, so the layer must degrade gracefully.

Config lives under `ui.decor` (`config.yml:905-924`): `enabled` (master, default `true`;
sweeps existing decor when toggled off), `centerpiece.*`,
`podiums.enabled`/`item-height=2.4`/`item-scale=0.9`/`pedestal-block`,
`portals.enabled`, `animation.spin-degrees-per-second=45.0`. NPC podiums are also gated
by `lobby.npcs.enabled`.

Cross-link: the `item_model`/glyph art and `MenuArt` single-source live in
[menu system & art](menus.md); decor reuses `MenuArt.modeModel`/`model`
for the item-model overlay.

---

## 6. HUD lifecycle: per-player acquire / release

UI handles are **owned by the match** (the `Game`), not by the player. A single boss
bar / panel exists per match and players are added/removed as viewers — this is what
makes "hide my overlay when I leave, keep it for everyone still playing" work.

### 6.1 Tracking and teardown

`AbstractGame` keeps tracked lists of `ScheduledTask`, `Countdown`, `BossBarHandle`,
and `HudPanelHandle`. Every handle a game creates is wrapped in `track(...)`
(`track(Countdown)`, `:177`; `track(BossBarHandle)`, `:182`; `track(HudPanelHandle)`,
`:187`, which returns `NOOP` for `null`).

`AbstractGame.cleanup()` (`AbstractGame.java:487+`) is the single teardown path on
match end: cancels every task, `stop()`s every countdown, `close()`s every boss bar and
HUD panel.

### 6.2 Release on leave / quit / respawn

`AbstractGame.releasePlayerUi(player)` (`AbstractGame.java:389-402`) hides every tracked
boss bar/panel and `removeViewer()`s every countdown for **one** player. Invoked from:

- `GameManager.handleQuit` (`:356`) — server quit,
- `GameManager.handleRespawn` (`:432`) — death-respawn,
- `GameManager.handleChangedWorld` / `removePlayer` (`:411`) — leaving the match world,
- directly inside `AbstractGame.releaseAndReset` (`:296-312`) for mid-match
  eliminations (combat/gather/tntwar) that release a victim without going through
  `GameManager`.

This per-match `hide()` is the **cross-platform safety net**: on NeoForge
`PlayerAdapter.clearBossBars()` is a no-op (`PlayerAdapter.java:137-138`, it cannot
enumerate a player's bars), so per-match `BossBarHandle.hide(player)` is the only
reliable drop. On Paper, `resetStatuses()` also clears lingering bars via
`clearBossBars()` (`PaperPlayerAdapter.java:221-227` iterates `player.activeBossBars()`),
but `releasePlayerUi` runs first so behavior matches.

**Respawn-in-place opt-out:** `handleRespawn` (`GameManager.java:419-434`)
early-returns when `activeMatch.game().handlesOwnRespawn()` is `true` — open-ended
experiences keep the player in-world and run their own `PlayerRespawnGameEvent` handler
instead of being pulled to the lobby (`:429`).

### 6.3 Re-acquire for a player staying in the match

`AbstractGame.restorePlayerUi(player)` (`AbstractGame.java:318-331`) is the inverse:
re-`show()`s all boss bars/panels and re-`addViewer()`s all countdowns for a player
**staying** in the match. Invoked from `onParticipantRejoin` (`:471`, on
reconnect/restart restore) and intended for respawn-in-place modes (e.g. fugitive
hunter).

---

## 7. Authoring guide

1. **Add a key.** Add a `MessageKey` constant (dotted path) and the template to **both**
   `en.properties` and `pt.properties` (keep parity — both at 298). MiniMessage markup
   allowed.
2. **Chat:** `announce(MessageKey.X, MessageArg.text("name", value), …)`. Use
   `MessageArg.mini(...)` only for pre-built markup (e.g. a `<lang:...>` tag);
   `MessageArg.text(...)` for user-supplied text (it is escaped).
3. **Boss bar / timer:** `timerBar(MessageKey.X, seconds, BossBarColor.Y, onComplete,
   args…)`; include a `<time>` placeholder.
4. **Scoreboard panel:** `track(server.ui().createPanel(titleText))`, then
   `line(index, text(...))` per line and `refresh()` once.
5. **Popup:** `popup(player, PopupType.WIN, key, args…)` or `popupAll(...)` —
   `WIN`/`ELIMINATION`/`OBJECTIVE` become titles, others action bars. For a toast the driver
   can place in a corner instead, declare `HudSurfaceSpec.popup(...)` (§4.4).
5b. **Own on-screen surface:** declare a `HudSurfaceSpec`, add it to `HudSurfaceCatalog`, open it
   via `ChallengeRegistry.hudSurface(spec)` or `AbstractGame.hudSurface(spec)` and push typed
   values. Do **not** also hand-write a `HudContributor` copy — the driver renders the same
   declaration on the sidebar for every player it cannot reach (§4.4).
6. **Cleanup is automatic** if every handle is `track()`ed — `cleanup()` closes them on
   match end and `releasePlayerUi` handles per-player leaves.

---

## 8. Per-platform parity & validation notes

- **[medium] NeoForge boss-bar titles are not per-viewer localized.**
  `createBossBar` and `NeoForgeBossBarHandle.title` render via
  `renderMini((CommandSource) null, …)` = server default language, keeping no viewer
  set (`NeoForgeBossBarHandle.java:20-24`, `NeoForgeUiAdapter.java:26`). Paper
  re-renders per viewer on `show()`/`title()`.
- **[medium] NeoForge scoreboard panel is server-global and EN-only.**
  `render()` uses `Language.EN` (`NeoForgeScoreboardPanelHandle.java:198-208`), with
  best-effort per-client hide; non-participants may see the match HUD. Documented
  divergence from Paper's per-player, per-language panel.
- **[low] Paper boss-bar title is shared, last-viewer-wins.** `PaperBossBarHandle`
  wraps one Adventure `BossBar` shared by all viewers but sets `bossBar.name(...)` from
  each viewer's render (`PaperBossBarHandle.java:32-58`). With a mixed-language audience
  the title shows whichever viewer was rendered last. True per-language would need one
  `BossBar` per language.
- **[low] `resetStatuses()` does not reset experience points.**
  `PlayerAdapter.resetStatuses()` (`PlayerAdapter.java:166-177`) clears inventory,
  potion effects, health scale, **body scale (`resetScale`, `:170`)**, health, food,
  gamemode, boss bars, title, and compass — but never `setExperiencePoints(0)`. Modes
  that grant/scale XP (e.g. XpHealth) can leak altered XP to the lobby.
- **[info] Three-level translation fallback is silent except total misses.** A key in
  EN but missing in `pt` silently shows English. Keep catalogs at key-parity.
- **[info] Dead message keys.** `FRIEND_JOIN_NOT_FRIENDS`, `FRIEND_JOIN_NO_PARTY`,
  `FRIEND_JOIN_SUCCESS` (`MessageKey.java:116-118`) are defined and translated but
  referenced by no handler. Harmless.
- **[info] Console output is always one language.** Non-player `CommandSource`s resolve
  to `consoleLanguage` (default `en`) by design — only player output is per-client
  localized.

---

## Keeping this current

Source of truth (this doc is a derived view): core SPI under `core/platform/`
(`UiAdapter`, `BossBarHandle`, `HudPanelHandle`, `MessageAdapter`, `DecorAdapter`); the
i18n package `core/i18n/` (`MessageService`, `MessageKey`, `Language`, `LocalizedText`,
`MessageArg`) plus `lang/en.properties` + `lang/pt.properties`; `core/decor/`; the
lifecycle helpers in `AbstractGame` and `GameManager`; the Paper adapters
(`PaperUiAdapter`, `PaperScoreboardPanelHandle`, `PaperBossBarHandle`, `BetterHudDriver`,
`PaperDecorAdapter`) and the NeoForge UI adapters; and `config.yml` `messages.*` /
`ui.decor.*`. Update this doc in the **same change** that touches those files. Triggers:
a new UI/i18n/decor class or adapter; any signature/behavior change to a handle, the
popup/panel routing, the scoreboard rendering, or language resolution; a `MessageKey`
or `lang/*.properties` key added/removed (re-check the 298 parity count); or a
`messages.*` / `ui.decor.*` config key added or removed.
