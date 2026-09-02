# Interface

Everything the player touches. Sexidium is **server-side only** — vanilla clients connect with no
companion mod — so every surface here is rendered by the server through the `UiAdapter`/`MenuAdapter`
seams described in [`../architecture/platform-and-adapters.md`](../architecture/platform-and-adapters.md).

## The four surfaces

| Surface | Document | What it is |
|---------|----------|------------|
| **Text — commands** | [commands.md](commands.md) | The `/sx` (`/sexidium`) tree and `/lobby`: every subcommand, its args, its permission bucket, tab completion, and the two adapter command bridges. |
| **Chest GUI — menus** | [menus.md](menus.md) | The declarative framework (`MenuCatalog`/`MenuTab`/`MenuView`/`MenuButton`), `MenuArt` as the single source of truth, the native glyph / `item_model` **scene art** pipeline, the auto-served resource pack, and the per-platform menu adapters. |
| **Hotbar & items** | [ui-interaction-system.md](ui-interaction-system.md) | The one item model shared by chest menus **and** the dynamic lobby hotbar: the `UiItem` render spec, `MenuButton`/`HotbarItem`, the single Paper materializer, `HotbarController`/`HotbarProfile`/`HotbarScope`, cross-world item hygiene. |
| **HUD & language** | [ui-and-localization.md](ui-and-localization.md) | Boss bars, native scoreboard panels, the declarative HUD-driver system (BetterHud + sidebar fallback), popups, per-player UI acquire/release, the decor (hologram) system, and `<lang:>` client-side localization + `MessageService`. |

`ui-interaction-system.md` and `menus.md` overlap on purpose: the first owns the **item**, the second
owns the **screen** it sits in. Add an icon in the first, add a screen in the second.

## The cross-play rule (non-negotiable)

> A UI technique is acceptable only if it has a defined answer for **all** render targets.

One `MenuView` must render for a Java client with the pack loaded (glyph background + `item_model`
icons), a Java client that declined the pack (plain materials, plain title), **and** a Bedrock client
arriving through Geyser/Floodgate — which never receives a Java resource pack and is instead rendered
as a native Cumulus form by `PaperFormRenderer`.

Practically: never let a screen's *meaning* live in the art. If the glyph, the model, or the pack is
missing, the plain material and the plain title must still say what the button does. Full matrix and
the adapter-by-adapter caveats are in [menus.md § Cross-play rule](menus.md#cross-play-rule).

## Why the UI looks the way it does

The techniques that were evaluated and **rejected** — negative-space font GUIs, ItemsAdder/Oraxen-class
dependencies, map-based rendering, `/dialog` — and the ones adopted, are logged with reasons in
[`../reference/tech-decisions.md`](../reference/tech-decisions.md). Read it before proposing a UI
approach that "everyone else uses"; it has probably already been ruled out against the dual-adapter /
no-fat-jar rule.

## Changing it

| I want to… | Guide |
|-----------|-------|
| Add or modify a chest-GUI screen | [`../guides/add-a-menu-screen.md`](../guides/add-a-menu-screen.md) |
| Add or modify a command | [`../guides/add-a-command.md`](../guides/add-a-command.md) |
