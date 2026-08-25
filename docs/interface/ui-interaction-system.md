# UI Interaction System (chest menus + dynamic hotbar)

Sexidium has two families of clickable items — the **chest GUIs** (`MenuView` / `MenuButton`) and the
**lobby hotbar** (the persistent items a player holds while in the lobby). They used to be two unrelated
code paths: the chest system had a full declarative framework, while the hotbar was two items hard-coded
inline in `PaperLobbyGuard` with their own Paper materializer. This page describes the **single, unified,
inheritance-based model** they now share, so an item looks and behaves the same wherever it appears and
new items are one small class.

For the chest-GUI framework itself (render seams, cross-play rule, art layer, Bedrock forms) see
[Menus](menus.md). For lobby/world context (protection, HUD, world kinds) see
[Lobby, Worlds & Social](../gameplay/lobby-worlds-and-social.md).

## The shared render spec: `UiItem`

Every UI item — a chest `MenuButton` and a hotbar `HotbarItem` — renders through one platform-agnostic
visual value object:

[`UiItem`](../../packages/core/src/main/java/com/sexidium/core/menu/UiItem.java) — `icon` (`ItemKey`),
`amount` (also used as a badge count), `name` (MiniMessage), `lore` (MiniMessage lines), `headOwner`
(optional `player_head` skin), `model` (optional pack-gated `item_model`). It carries only what an item
*looks like*; behaviour lives on the specialization.

- `MenuButton` (chest) is the **click-carrying** specialization: it adds an `onClick(MenuContext)`
  consumer. `MenuButton.visual()` projects it down to a `UiItem` (dropping the click — chest clicks route
  by slot, not by a carried spec).
- `HotbarItem` (hotbar) is the **inheritance-based** specialization: it adds a slot, a stable routing id,
  a visibility predicate, and a click handler, and `build(...)`s a `UiItem`.

### One materializer

Both families materialize through a single Paper factory:
[`PaperUiItemFactory`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/menu/PaperUiItemFactory.java)
(`build` / `apply`). It does MiniMessage name+lore with the `PaperMenuArt` glyph translation, player-head
skinning, and the pack-gated custom `item_model` — in exactly one place. `PaperMenuAdapter.toItemStack`
calls `PaperUiItemFactory.build(button.visual(), packLoaded)`; the hotbar renderer calls
`PaperUiItemFactory.build(hotbarSlot.item(), packLoaded)` and then adds its PDC routing tag. Changing how
items render (a new component, a texture rule) is now a one-file change.

## The dynamic lobby hotbar

Package [`com.sexidium.core.world.hotbar`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/).
All platform-agnostic; the platform only renders resolved slots and forwards clicks.

| Type | Role |
|---|---|
| [`HotbarItem`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarItem.java) | Abstract base: `id()`, `slot()`, `visibleFor(ctx)`, `build(ctx) → UiItem`, `onClick(ctx)` |
| [`HotbarScope`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarScope.java) | `LOBBY` / `MINIGAME` / `EXPERIENCE` — selects a profile (named to avoid clashing with the world-lease `WorldKind`) |
| [`HotbarProfile`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarProfile.java) | The ordered `HotbarItem`s for one scope |
| [`HotbarController`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarController.java) | Single authority: owns the profiles; `resolve(player, scope) → List<HotbarSlot>` and `handleClick(player, scope, id)` |
| [`HotbarContext`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarContext.java) | Per-render/-click: the player + scope + accessors onto `HotbarServices` (server, games, lobbies, friends, menus) |
| [`HotbarSlot`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/HotbarSlot.java) | A resolved item ready to render: `(slot, id, UiItem)` — the platform never sees the `HotbarItem` |

### The lobby items — context-sensitive

All lobby items live in the single `LOBBY` profile; each decides its own visibility from
`HotbarContext`, so the same profile renders the **default** set for a solo player and swaps to the
**lobby-management** set once the player configures/joins a match lobby. The switch key is
`HotbarContext.managingLobby()` (in a lobby that is `CONFIGURED` or `QUEUED`); `leadsLobby()` gates
owner-only tools ([`items/`](../../packages/core/src/main/java/com/sexidium/core/world/hotbar/items/)).

Default set (shown while **not** managing a lobby):

| Slot | Item | Icon | Shows | Click |
|---|---|---|---|---|
| 0 | `MenuNavItem` | `compass` | always | Opens the lobby chest GUI if in a group, else the hub |
| 1 | `MyExperiencesItem` | `ender_chest` | not managing | Opens "My Experiences" |
| 2 | `MinigamesItem` | `diamond_sword` | not managing | Opens the Minigames grid |
| 4 | `FriendsItem` | `player_head` (viewer's face) | not managing | Opens the Friends roster (below) |
| 8 | `FriendRequestsItem` | `writable_book` (badge) | not managing **and** requests pending | Opens the Invites inbox |

Lobby-management set (shown while managing; slot 0 compass stays and still opens the chest GUI):

| Slot | Item | Icon | Shows | Action |
|---|---|---|---|---|
| 1 | `LobbyTeamCountItem` | `comparator` | owner, configured | Cycle FFA → 2 → 3 → 4 teams |
| 2 | `LobbyInviteItem` | `name_tag` | owner | **Right/left-click a player** to invite (Paper entity handler); click air = invite roster |
| 3–6 | `LobbyTeamColorItem(0..3)` | team wool | configured, teams enabled, `index < teamCount` | Join/leave that team; badge = headcount, ✔ = your team |
| 7 | `LobbyStartItem` | `lime_concrete` | owner, configured | Start the match |
| 8 | `LobbyLeaveItem` | `barrier` | managing | Leave (or disband, if host) |

`FriendsItem` opens the Friends roster (`MenuService.openFriendsWarp` → `LobbyMenu.openFriendsWarp`): each
online friend is a head whose name is their username and whose lore shows where they are (which
experience / minigame / the lobby). Tapping a friend in an experience **joins that experience** via the
existing friend-join system (`ExperienceService.enter`); tapping a friend in a lobby teleports the viewer
to them cross-world (`PlayerAdapter.teleport`).

The lobby-management set re-renders when lobby state changes with no world change (create a lobby, join a
team, change the team count): `PaperLobbyGuard` polls every 10 ticks and re-renders only when the resolved
hotbar **signature** (slot+id+badge+name) changed — so it never churns the inventory when nothing changed,
and it catches menu-driven, command-driven and tick-driven state flips in one place. The invite-by-clicking
handlers (`onInviteInteractEntity` for right-click, the top of `onEntityDamage` for left-click) fire only
while the attacker holds the `lobby-invite` tool.

### Render + click routing (Paper)

[`PaperLobbyGuard`](../../packages/module-paper/src/main/java/com/sexidium/paper/adapter/world/PaperLobbyGuard.java)
is the Paper seam — an event forwarder, not an item definition:

- **Render** (`renderLobbyHotbar`): clears the inventory, then places each `HotbarSlot` from
  `core.hotbar().resolve(player, LOBBY)`, tagging each with its routing id in the item's PDC
  (`nav` + `nav_id`). Called on join, world-change into a lobby, respawn, and pack-load.
- **Click** (`onNavInteract`): reads the `nav_id` PDC tag and calls
  `core.hotbar().handleClick(player, LOBBY, id)`; the controller runs that item's `onClick`.
- **Locks**: the existing drop / swap / inventory-click / drag guards still cancel any interaction that
  touches a tagged item, so the persistent items can't be moved, dropped, or crafted with.

### Item hygiene — no cross-world leakage

The controller is the **single authority** and only the `LOBBY` profile has items; `MINIGAME` and
`EXPERIENCE` resolve to nothing (their games own the player's kit). Combined with the transition choke
points, this is what makes leakage impossible:

- **Into a match:** `PlayerSessionCoordinator.admit` calls `resetStatuses()` (which clears the inventory
  *and* the XP bar — see [known-issues F61](../reference/known-issues.md)) before the entry teleport. Lobby items are
  never produced for a non-lobby scope, so none can enter.
- **Back to the lobby:** `PaperLobbyGuard.onChangedWorld` strips tagged items when leaving a lobby world,
  and `renderLobbyHotbar` clears the inventory before re-placing — so a match item can't return and a
  now-hidden item (e.g. Friend Requests with zero pending) can't linger.

### Adding a hotbar item

1. Subclass `HotbarItem` in `world/hotbar/items/` — declare `id()`, `slot()`, optional `visibleFor`,
   `build(ctx)` (return a `UiItem`), and `onClick(ctx)`.
2. Add it to the `LOBBY` profile list in `HotbarController`'s constructor.

No adapter change is needed — Paper renders and routes it automatically. Give it a custom `item_model`
via `UiItem`'s `model` (add a `MenuArt.ICON_*` + sprite) to have it wear bespoke art for pack-loaded
players; it degrades to the vanilla icon otherwise.

## Plain-mode window sizing

A `MenuView` can render at a different size for no-pack / Bedrock viewers via `plainRows(int)` /
`plainSize()` (defaults to `rows`/`size`). The hub uses this: its baked art needs six rows, but a plain
viewer only sees the centered icon grid, which `layoutCentered` packs into one row — so the hub asks for
`plainRows(3)` and `PaperMenuAdapter.openChest` opens a compact 27-slot chest for plain viewers while the
baked path stays 54. See [Menus](menus.md).

## Tests

`core.menu` (`MenuButtonTest`, `MenuArt*`), plus the shared render path is exercised through the existing
Paper menu tests. Hotbar items are plain POJOs over the core service interfaces (the POJO-fake test style
used across the codebase).

## Keeping this current

The code is the source of truth; this doc is a derived view. Authoritative sources:
`com.sexidium.core.menu.UiItem` + `MenuButton.visual()`, the whole
`com.sexidium.core.world.hotbar` package (`HotbarItem`, `HotbarScope`, `HotbarProfile`,
`HotbarController`, `HotbarContext`, `HotbarServices`, `HotbarSlot`, `items/*`),
`SexidiumCore.hotbar()`, `MenuService.openPlayers` / `LobbyMenu.openPlayers`,
`PaperUiItemFactory`, and `PaperLobbyGuard` (render + click routing). **Update this doc in the same
change that touches them.** Triggers: a new `HotbarItem` or scope; a change to the shared `UiItem` shape
or the one materializer; a change to the lobby hotbar slots or click routing; or a change to the
cross-world hygiene choke points. Related: [Menus](menus.md), [Lobby, Worlds & Social](../gameplay/lobby-worlds-and-social.md).
