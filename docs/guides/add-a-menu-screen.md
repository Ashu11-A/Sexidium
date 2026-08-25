# Base prompt: adding or modifying a chest-GUI screen

You are working on Sexidium's **menu system** — the declarative, cross-platform chest GUIs. Reference:
[menus.md](../interface/menus.md) and [ui-interaction-system.md](../interface/ui-interaction-system.md). The golden rule: one
abstract `MenuView` is built in core and rendered per platform (Paper chest, Bedrock Cumulus form,
NeoForge plain container) — **a technique is only acceptable if it has a defined answer on all three.**

## Key files

| Concern | File(s) |
|---|---|
| Public facade | `packages/core/src/main/java/com/sexidium/core/menu/MenuService.java` — every screen is reached through it, never directly |
| Screen framework | `…/menu/ChestLayout.java` (slot grid), `Screen.java`/`AbstractScreen.java`, `SidebarScreen.java`, `PaginatedScreen.java`, `PickerScreen.java`, `ConfirmableScreen.java`, `SidebarNav.java` |
| Screen classes (one per domain) | `…/menu/MinigameMenu.java`, `ExperienceMenu.java`, `LobbyMenu.java`, `SocialMenu.java`, `AdminMenu.java` — all `SidebarScreen` subclasses |
| Shared toolkit + per-player state | `…/menu/MenuSupport.java` (builder selections, tap-again confirm, back buttons, player picker, roster) |
| View model | `…/menu/MenuView.java`, `MenuButton.java`, `MenuContext.java` |
| Custom art (icons/backgrounds/scenes) | `…/menu/MenuArt.java`, `MenuArtIcons.java`, `…/menu/scene/`, pack in `…/menu/pack/` |
| Bedrock projection | `…/menu/MenuForms.java` |
| Paper renderer | `packages/module-paper/…/adapter/menu/PaperMenuAdapter.java`, `PaperFormRenderer.java` |
| Drift guards | `MenuArtCoverageTest`, `MenuArtAssetsTest`, `MenuArtChestTest`, `MenuFormsTest`, `ChestLayoutTest`, `ScreenFrameworkTest`, `PaginatedScreenTest`, `SidebarLayoutGridTest`, `MenuFormsSidebarProjectionTest` |

## How to add a screen

1. Put the builder method on the domain's screen class (or create one composing `MenuSupport` and wire it
   in `MenuService`'s constructor + a delegating public method — `MenuService` stays the only public
   entry point; commands and adapters call it, never the screen classes).
2. Extend `SidebarScreen` (or `PaginatedScreen`/`PickerScreen` for a list) and fill the hooks —
   `buildSidebar`, `buildContent`, `buildBottomNav`. For a one-off screen, the fluent form
   `SidebarScreen.of(title).contentIndex(i, button)…build()` returns the `MenuView` directly.
   **No raw slot math**: address the grid through `ChestLayout` (28 content slots by index, 6 sidebar
   slots, fixed nav slots) so every screen keeps the same shape.
3. Buttons come from the same factories as before: `MenuButton.of(icon, name, lore, onClick)`,
   `.label(...)` (decorative — a **null onClick is the signal** `MenuForms` uses to treat it as body text
   on Bedrock), `.head(uuid, …)` for player heads, `.withModel(MenuArt.model(...))` for pack icons.
4. The framework owns navigation: `SidebarNav.apply(view, player, menus, support, NavSection.X)` renders
   the rail with your section active, `back(...)` fills `ChestLayout.SLOT_BACK`, and `PaginatedScreen`
   wires prev/page/next itself. Backgrounds via `.background(MenuArt.BG_*)` — purely additive, ignored by
   no-pack/Bedrock viewers.
5. More than 28 entries? Use `PaginatedScreen` rather than a bigger grid, and expose a page-taking
   overload on `MenuService` so the page controls can re-enter the screen.

## Cross-play rules (non-negotiable)

- **Bedrock is a flat tap-grid**: no hover tooltips, no shift-click, no drag. Therefore:
  - State lives **in the item name** (`Keep Inventory: ON`), not only in lore.
  - Destructive actions use the **tap-again confirm** (`support.confirmButton(...)`, 5 s window) — never
    a shift-click confirm.
  - "Type a name" flows use the click-only **player picker** (`support.openPlayerPicker(...)`).
- **Mutually exclusive options get their own single-choice sub-screen** (the "Choose World" screen in
  `ExperienceMenu.openExperienceWorldType` is the model), never checkboxes in a multi-select grid.
- Every click is cancelled by the framework; only `onClick` runs — never rely on vanilla item movement.
- Per-player in-progress state (selections, pending confirms) lives in `MenuSupport` maps and must be
  cleared on every entry point (`resetBuilder` pattern) — Bedrock players close menus by tapping out and
  never hit your Back button.

## Custom art

Vanilla item icons always work; pack art is optional on top. To give a tile pack art: add an icon id
constant (`MenuArt.ICON_*`) or a mode/challenge mapping in `MenuArtIcons`, ship the PNG under the
menu-art assets, and call `.withModel(...)`. `MenuArtCoverageTest` (registry/catalog ↔ icon tables) and
`MenuArtAssetsTest` (icon ↔ committed PNG) fail the build on drift. Animated tiles: `nameFrames` +
`view.animated(true)` (Paper-only; the static name is the fallback everywhere else).

## Checklist

- [ ] Screen reachable only through a `MenuService` public method
- [ ] Slots addressed through `ChestLayout`, sidebar rendered by `SidebarNav`
- [ ] Any list longer than the content area paginated, not truncated
- [ ] Works with zero pack art (vanilla icons, plain title)
- [ ] Bedrock-safe: state in names, tap-again confirms, no shift/hover dependence
- [ ] Per-player state cleared at every entry point
- [ ] Icon tables + tests updated if art was added
- [ ] [menus.md](../interface/menus.md) updated in the same change

---
*Keeping this current: tracks `MenuService`/`MenuSupport`/`MenuView`/`MenuButton`, the screen framework
(`ChestLayout`, `SidebarScreen`, `PaginatedScreen`, `SidebarNav`), the screen classes, `MenuArt(Icons)`
and `MenuForms`. Update it in the same change that alters those workflows.*
