# Sexidium — Known issues

This is the live, severity-tagged defect log for `com.sexidium.core` and its adapters. It supersedes the
old `docs/known-issues.md` (a snapshot from 12 reviewer passes): every finding below was re-verified
against the current source on 2026-06-13. The headline takeaway is that **all three old critical findings
and most of the old highs are fixed** — persistence, open-match authorization, the world-name lease bugs,
and the HTTP token bridge now hold. What remains is one high (an unauthenticated read endpoint), a small
cluster of medium state-leak gaps, and a few low/latent code smells. See [game framework](../architecture/game-framework.md),
[platform abstraction](../architecture/platform-and-adapters.md), and [networking & ranks](../operations/networking-bot-ranks.md) for the
subsystems referenced here.

> **Verification scope.** This pass re-read the core anchors only (`com.sexidium.core`). The Paper adapter
> migrated to `module-paper` (Multiverse-Core v5.7, `AbstractWorldControl`/`WorldNaming`) since the old
> report, and the NeoForge adapter the old report also covered has since been DROPPED from the build, so
> **every Paper/NeoForge finding in the old report cites pre-migration line numbers and was NOT re-verified
> here** — they are listed under "Re-verify outside core" rather than asserted as open. The bot (`bot/`) was
> likewise not re-read this pass.

---

## Still-open issues

### High

| ID | Subsystem | Issue | Anchor |
|----|-----------|-------|--------|
| F62 | UI / client | **BetterHud's resource pack corrupts vanilla GUI rendering on Minecraft 26.2.** Not a Sexidium defect — upstream, and unfixable by updating. **Avoided by pinning Paper to 26.1.2**, the version BetterHud's newest overlay actually matches. Sexidium now *detects* the mismatch (`BetterHudLink`'s capability probe) and renders every declared surface on the scoreboard sidebar instead, so the readout stays legible; it cannot stop BetterHud sending its own pack. See below. | `BetterHudLink`, `PackFormats`, `hud.betterhud.enabled`, `PAPER_VERSION` |
| F40 | Networking | `/rank` and `/player` are unauthenticated and leak linked Discord IDs. `handleRank`/`handlePlayer` do no token check (unlike `/command` and `/auth/link`, which are token-gated), and the JSON they emit includes `discordUserId`. Any process that can reach loopback can enumerate the Minecraft↔Discord link table. | `ApiServer#handleRank`/`handlePlayer`, `Json.java:42` |

The server binds to `127.0.0.1` only, so the blast radius is local processes — but on shared/multi-tenant
hosts that is still a privacy leak. Fix direction: token-gate the read endpoints too, or strip
`discordUserId` from their payload (`Json#of(LeaderboardEntry)`), keeping it only on the already-gated
`/discord` path.

**F62 in full.** BetterHud does not draw its HUD with text and textures alone: the resource pack it builds
and sends **replaces the client's vanilla core shaders**,
`assets/minecraft/shaders/core/rendertype_text.vsh/.fsh`. That is how it moves glyphs to arbitrary screen
positions. Which shader set a client receives is picked from a **hardcoded pack-format table** inside the
plugin — its `PackOverlay` enum — whose entries in the newest published build (`2.1.0-SNAPSHOT-447`) are:

| overlay directory | pack formats |
|---|---|
| `betterhud_1_21_2` | 9–45 |
| `betterhud_1_21_4` | 46–55 |
| `betterhud_1_21_6` | 56–83 |
| `betterhud_26_1` | **84–99** |

Minecraft **26.2 is pack format 88**, so a 26.2 client is served the `betterhud_26_1` set. A Modrinth build
*listing* 26.2 means the plugin loads, not that its shaders match; 447 is the newest build, so **there is
no version to upgrade to**.

**What that overlay actually contains**, diffed against both clients' own `assets/minecraft/shaders/core`
(the vanilla 26.x jars are unobfuscated, so this is a straight file comparison):

| | MC 26.1.2 (format 84) | MC 26.2 (format 88) | BetterHud `betterhud_26_1` |
|---|---|---|---|
| core text shader | `rendertype_text.vsh/.fsh` | **renamed** to `text.vsh/.fsh` | ships **both** |
| GUI variant split | none | `#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)` | **no guards at all** |
| lightmap | always declared | GUI variant binds none | always `vertexColor *= sample_lightmap(...)` |
| `#moj_import` | `<minecraft:fog.glsl>` | `<minecraft:fog.glsl>` | `<fog.glsl>` (pre-26 form) |

So **26.1.2 is the version this overlay was written for** and it matches line for line. On 26.2 the live
shader is `text.*`, and BetterHud's copy of it samples a lightmap that the `IS_GUI` variant never binds —
which is exactly the reported symptom, wrong text colours and missing text/buttons in vanilla screens.

*Resolution, in place:* **`PAPER_VERSION` is pinned to 26.1.2** (`scripts/init-paper.sh`), where BetterHud
works and the Death Resets corner readout renders as designed. The plugin builds against
`paper-api:26.1.2.build.74-stable` with `api-version: '26.1'`, which is the **oldest** server it targets —
Paper refuses a plugin whose `api-version` exceeds the server, but accepts an older one with legacy shims,
so one jar covers 26.1.2 and 26.2 alike. `SexidiumResourcePack.PACK_FORMAT` tracks the pin (84).

*Still in place for anyone on 26.2, but the default flipped.* The shipped `hud.betterhud.enabled` is now
**true** — the pin is a covered version, and the corner readout is what the pin is for. Two mechanisms
protect a server that moves off the pin, and neither depends on that default:

1. **`betterhud_overlay_matches`** in `scripts/init-paper.sh` decides per pin. On a covered version it
   installs BetterHud and leaves `hud.betterhud.enabled: true`; otherwise it installs nothing (opt in with
   `INSTALL_BETTERHUD=1`), forces `hud.betterhud.enabled: false` and sets `pack-type: none` on an existing
   install.
2. **`BetterHudLink`'s capability probe** covers everything the provisioner does not reach — network nodes
   (no warm-up boot, so the config patch above finds no file to patch), hand-installed jars, a pin bumped
   after provisioning. On a pack format outside the range the shaders genuinely cover it reports no
   capabilities, logs the reason once, and the sidebar takes over. This is the protection that remains
   with the switch on, so `hud.betterhud.capability-probe: false` is not a knob to turn on a version you
   have not diffed.

Network nodes are additionally seeded with `hud.betterhud.enabled: false` by
`sexidium::seed_node_identity`, for a reason unrelated to shaders — see the BetterHud entry below.

*Downgrading is not just a version string.* Worlds upgrade in place and never downgrade — a world last
opened by 26.2 carries DataVersion 4903 and 26.1.2 (4790) refuses it. `refresh_jars_on_version_change`
therefore calls `quarantine_worlds_on_downgrade`, which **moves** `world*/` and `worlds/` aside as
`<name>.mc<previous>.<timestamp>` rather than handing them to a server that cannot read them. The lobby and
bundled maps re-extract from the jar on the next boot.

*The gate is not sufficient on its own.* Switching Sexidium off stops **Sexidium** using BetterHud; it does
not stop **BetterHud** sending its own pack. An installed, loaded BetterHud keeps corrupting clients
regardless. **Removing the plugin is the only complete fix** — the plugin logs a warning saying exactly
this when it finds BetterHud installed while the gate is off.

*`pack-type: none` is a half-fix, and the other half is visible.* It does stop the shader damage, and it is
what `scripts/init-paper.sh` applies. But it only stops BetterHud **shipping** the pack; BetterHud keeps
**drawing**, in its own boss bar, with a font the client now does not have. Every glyph then renders as the
unknown-character box, and the boss bar itself — normally invisible because BetterHud's pack replaces the
bar texture — shows up as a bare bar (its colour is `shader.yml` → `bar-color`, yellow by default). The
symptom is a row of ~30 white boxes across the top of the screen.

*Why that outlived the gate at all (fixed).* BetterHud persists each player's worn objects to
`plugins/BetterHud/.users/<uuid>.yml` and re-applies them on join. A `sexidium_*` surface claimed while
the driver was **enabled** therefore survived the switch to false, and the disabled integration had
nothing left that could take it back off. `BetterHudClaims#purgeOwned` now runs on the disabled path,
driven by `BetterHudReconciler` in teardown-only mode: a one-way sweep that removes `sexidium_*` objects
only and can add nothing. An existing stale claim clears itself on the next join or sweep; deleting
`plugins/BetterHud/.users/` with the server **stopped** does the same by hand (BetterHud rewrites those
files on shutdown, so deleting them while it is running achieves nothing).

*What changed with the HUD driver.* The failure is no longer silent or all-or-nothing.
`BetterHudLink`'s capability probe compares the running server's pack format (`PackFormats`) against the
range BetterHud's shaders genuinely cover; on a mismatch the driver reports **no capabilities**, logs the
reason once, and core's `SidebarHudDriver` renders every declared surface on the scoreboard instead. An
operator on 26.2 gets a legible readout rather than white boxes — but still gets BetterHud's own corrupted
GUI text, because the pack is sent regardless of what Sexidium does with the plugin. Removing it remains
the only complete fix. `hud.betterhud.capability-probe: false` overrides the check.

*Close this when* upstream ships a `betterhud_26_2` overlay (or caps `betterhud_26_1` at format 87); then
widen `SUPPORTED_PACK_FORMAT_MAX` in `BetterHudLink`, add the version to `betterhud_overlay_matches`, and
move the `PAPER_VERSION` pin. `hud.betterhud.enabled` needs no change — it already ships `true`.

### Medium

| ID | Subsystem | Issue | Anchor |
|----|-----------|-------|--------|
| F18 | Event routing | Every non-lifecycle event is fanned to **every** active match's `game().handle(...)`. There is no per-match event registration; isolation depends entirely on each mode self-filtering by `isParticipant`/world. A mode that forgets a participant/world guard sees other matches' `EntityDeath`/`BlockBreak`/`Advancement`. | `GameEventRouter.java:71-75` |
| ~~F61~~ | Platform / Experiences | **FIXED.** `resetStatuses()` now calls `setExperienceBar(0, 0f)`, so the XP bar is cleared on every match entry/leave. XP earned in an `XpHealth`/`SharedLife` experience can no longer leak back to the lobby, and the new lobby points-bar (`LobbyHud` → `setExperienceBar`) can no longer leak into a minigame that uses the XP bar as a health meter. | `PlayerAdapter#resetStatuses` (`setExperienceBar` call) |
| F66 | Networking / UI | **The Sexidium menu art pack is served by nobody on a network deployment.** `sexidium::seed_node_identity` sets `ui.resource-pack.enabled: false` on every node — correct as far as it goes, since four backends each hosting the pack would hand clients four different SHA-1s and re-prompt on every server switch — but nothing then hosts it, on the proxy or anywhere else. Java clients fall back to placeholder art for the whole menu. Standalone is unaffected. Fix direction: one host (the proxy, or a single designated node with its port published) and every other node pointed at that URL. | `sexidium::seed_node_identity`, `ui.resource-pack.*` |
| F67 | Networking / UI | **BetterHud's self-hosted pack advertises a container-internal address on a network deployment.** BetterHud builds the URL it sends clients from the address it discovers for itself; inside the compose network that is a `172.x` container address no player can reach, so the download fails silently and its custom font never arrives — a boss bar of unknown-character boxes, the exact F62 symptom by a different route. Moot on the network as deployed: nodes are seeded `hud.betterhud.enabled: false` and BetterHud is no longer installed on the network **at all** — the shared install sets `SX_SKIP_BETTERHUD=1` and prunes the jar, since a shared `plugins/` cannot express "lobby only". Still open for any deployment that re-enables it. Fix direction: publish BetterHud's port on the node that serves it and advertise a public host. | `sexidium::seed_node_identity`, `configure_betterhud_if_present`, `self-host-ip` |
| F21 | Minigames / Platform | No pre-match snapshot of the player's original inventory/XP/location. `resetStatuses()` wipes to a fixed lobby baseline on join, so starting a match for a player standing in a survival world destroys their inventory. (Distinct from reconnect persistence, which **is** now implemented — see Recently fixed.) | `PlayerAdapter#resetStatuses` |

### Low

| ID | Subsystem | Issue | Anchor |
|----|-----------|-------|--------|
| F43 | Minigames | FugitiveGame dash works for hunters too. `handle()` routes `PlayerToggleSneakGameEvent` to `dash()` for any participant; `dash()` has **no `isFugitive` guard**, so hunters get the same escape boost the ability is meant to give only the fugitive. | `FugitiveGame.java:106-107` → `:362` |
| F44 | Minigames | `RaceGame.markItem` (block-break path) compares `blockKey` (e.g. `diamond_ore`) against item targets (`diamond`) via exact `equals`, so it effectively never fires. **Benign** — the `scanInventory` path already credits the item objective on the next inventory-change event, so the dead branch is misleading code, not a functional gap. | `RaceGame#markItem` (vs `#scanInventory`) |
| ~~F59~~ | Networking | **FIXED.** `RankService#winPoints` now resolves the `default`/`participate` branch with the participate default (10), not the 100-point win default, so an unmatched (future) modeId earns the participate value rather than a full win. | `RankService#winPoints` |
| F65 | Networking | `command_queue` table is still created but unused — the Discord→server path runs over HTTP `/command`, not a DB queue. Dead schema. **Not re-read end to end.** | `SchemaMigrator.java` (`command_queue` DDL) |
| F68 | Deployment | **Third-party plugin versions are resolved once and never pinned.** `ensure_modrinth_plugin` returns early when the destination file already exists, so whatever Modrinth called "latest" the day the tree was first built is frozen there — and `SX_REFRESH_PLUGINS=1` re-resolves to *today's* latest with no record of what that is. The shared install removed the dangerous half of this (there is one destination, so nodes cannot differ from each other), and Multiverse-Core is pinned to the `release` channel after a resolve picked up `5.8.0-pre` while production ran 5.7.3. The remaining gap is reproducibility: no manifest records which version is installed. Fix direction: `SX_PIN_<PLUGIN>` carrying the exact URL + expected hash, failing the provision on a mismatch. | `ensure_modrinth_plugin` (`scripts/lib/modrinth.sh`), `paper::prune_unwanted_shared_jars` |
| F69 | Interface / Economy | **`/bal` and `/money` collide with other economy plugins.** Both are declared as aliases of Sexidium's `balance` command. Bukkit resolves a contested name to whichever plugin registered it first and namespaces the loser — so on a server that also runs, say, EssentialsX, one of the two ends up reachable only as `/sexidium:balance`, with no error anywhere and players typing a command that answers for the wrong economy. `/pay` and `/baltop` carry the same risk under their own names. Note this cannot be fixed by removing the aliases: the collision is between the plugins, not inside ours. Mitigation: run exactly one economy plugin (which is the point of Sexidium being the Vault PROVIDER — see `docs/architecture/platform-and-adapters.md`), or drop the contested entries from the other plugin's `commands:` block. | `packages/module-paper/src/main/resources/plugin.yml` (`balance.aliases`), `PaperAliasCommand` |
| F39 | Networking | Schema is hand-synced across three places (Java DDL superset, `001_auth.sql` subset, TypeORM entities); no automated drift guard. The `SchemaMigrator` header explicitly warns to keep them in sync. **Not re-read.** | `SchemaMigrator.java:12` |

---

## Recently fixed (dropped from the open log)

Verified fixed in the current core; kept here as a short audit trail so a maintainer does not re-open them.

| Old IDs | What was broken | What it is now |
|---------|-----------------|----------------|
| C2 / H5 / H6 | No mode overrode `writeSnapshot`/`restore`; snapshots saved zero player rows; reconnect restored nothing. | `AbstractGame#writeSnapshot`/`restore`/`onParticipantRejoin` capture every participant (inventory via `InventorySerializer`, health, position, role) and re-apply on a restart-rebuilt match (`AbstractGame.java:412-472`). |
| H4 / H16 / F17 / F38 | Any player could `/sx join` a stranger's match; `/sx start` with no names conscripted every online player; `PartyManager.joinFriendParty` was dead unauthenticated code. | `GameManager#joinInProgress` now takes `relatedPlayerIds` and returns `NOT_RELATED` unless the target match contains a related player (`GameManager.java:310` → `PlayerSessionCoordinator.java:370-405`). `/sx start <category> <mode>` for arbitrary players is admin-gated (`GameCommands.java:44`, re-checked against `sexidium.admin`). `PartyManager` was removed entirely, merged into `LobbyManager` (which gates joins via `Lobby#canJoin(viewer, friends, invited)`). |
| H7 | `SharedInventoryGame` missed pickup/mine/craft mutations and the sync timer wiped fresh items. | Reconcile-based sync (no longer a stale overwrite). |
| H13 / H14 | `/command` bridge ran arbitrary console commands gated only by a token whose default shipped as `change-me-please`, compared with `String.equals`. | `ApiServer` refuses `/command` (and `/auth/link`) while the token is unset/default, uses `MessageDigest.isEqual` (constant-time), and supports an optional `api.command-allowlist` (`ApiServer.java:52-206`). |
| C1 / C4 / H8 / H10 / H11 / F30 / F31 | Paper/NeoForge world-name handling: full path vs bare prefix, lobby unresolvable, double-namespaced dimension keys, wrong folder deletes. | Core now owns world identity via `WorldNaming#sameWorld`, which flattens `/ \ :` separators and falls back to a trailing-segment match before comparing (`WorldNaming.java:247-261`), so canonical (`<ns>/<key>`) and flattened (`<ns>_<key>`) names compare equal. |
| — (2026-08-22 incident) | A thirty-second network outage destroyed a fifty-five-in-game-day Death Resets world. A frozen player was killed 27s into the freeze and 3s *before* her disconnect, the swap then completed with **nobody online**, and `carryPlayerSnapshots` brought every offline player's inventory across intact because the wipe loop only reached `game.online()` — so everyone came back to the fresh world with all their gear. | Four guards. `PlayerAdapter#idleMillis()` (vanilla last-action time; `ping()` freezes rather than climbing when a client goes silent, so it cannot detect this) drives a `PlayerControlWatch`: a downed player's damage events are cancelled outright and hostiles drop them. A death while downed returns before `recordRunDeath`/`handleHardcoreDeath`/challenge dispatch, so no counter moves and no reset is asked for. `ExperienceWorldReset` abandons a swap with nobody online, checked at the request **and** at the swap. `carryPlayerSnapshots` takes a `Carry` mode and strips contents for a `RESET_WORLD` outcome — including on the unreadable-snapshot path, which was the same hole by a quieter route. |
| — (found alongside it) | `AbstractGame#markDisconnected` was dead on the live path: only the restart rehydration in `AbstractGame.restore` ever called it, so `isDisconnected(...)` answered `false` for every player who dropped mid-session, while `online()` dropped them the same tick. | Called from `PlayerSessionCoordinator#handleQuit`, before the `isReconnectable()` branch so a non-reconnectable mode is covered too. |
| F24 / F61 | Mid-match quit left XpHealth's 2x health scale and altered XP unrestored. | Health scale is reset on quit; the XP bar is now also reset — `resetStatuses()` calls `setExperienceBar(0, 0f)` on entry and leave, closing the XP residue formerly tracked as F61. |

---

## Re-verify outside core (not checked this pass)

These were open in the old report but live in code this pass did not re-read. Treat them as **unknown**, not
fixed or open, until re-verified against the current modules.

- **All Paper findings** (old C1/F30–F35/F50–F52): the adapter is now `packages/module-paper` on
  Multiverse-Core v5.7 with `AbstractWorldControl`; old line numbers refer to the removed
  `PaperWorldLeaseService`. Re-verify the temp-world dispose/lease lifecycle and the inventory-serializer
  swallow-on-corruption (old F50). See [adapters: Paper](../architecture/platform-and-adapters.md).
- **The NeoForge adapter was dropped from the build** — `packages/` holds `core`, `module-paper` and
  `module-velocity` (the proxy). All NeoForge findings (old C3/C4/H8–H12/F25–F29/F45–F49/F53/F60) are
  therefore MOOT, not deferred: there is no module to re-verify against. The seams those findings pointed
  at remain core + Paper; the capability-probe layer (`CapabilityRegistry`, `/sx admin capabilities`)
  now makes any future "installed but not served" gap visible instead of silent.
- **Bot race H15** (`sqljs` full-file rewrite vs Java JDBC) and **F58** (unscoped/unthrottled auth-code
  consumption): both in `bot/`, not re-read. The Java side moved account linking to the token-gated
  `/auth/link` endpoint (`ApiServer#handleAuthLink`), which removes the bot's need to write the DB directly
  — confirm the bot actually routes through it now rather than still opening `sexidium.db`. See
  [networking & ranks](../operations/networking-bot-ranks.md).
- **Old C1 dispose leak** specifically: re-test a full match-world create→dispose→reacquire round-trip on
  the current Paper adapter, since no test covered it at the time.

---

## Keeping this current

The code is the source of truth; this doc is a derived view. The authoritative files are the core anchors
cited above — chiefly `lib/net/ApiServer.java`, `lib/net/Json.java`, `game/GameEventRouter.java`,
`platform/PlayerAdapter.java`, `game/AbstractGame.java`, `game/GameManager.java`,
`command/CoreCommandService.java`, `world/WorldNaming.java`, and `data/RankService.java` — plus the Paper
(`packages/module-paper`) and bot (`bot/`) code for the deferred
section. Update this file in the **same change** that touches those. Triggers: closing or opening any listed
finding; adding/removing an HTTP endpoint or changing its auth; changing `resetStatuses`/snapshot capture or
event-routing fan-out; adding a game mode (re-check `RankService#winPoints` coverage); or completing the
Paper/bot re-verification (move those items from "Re-verify" into the open log or "Recently fixed").
