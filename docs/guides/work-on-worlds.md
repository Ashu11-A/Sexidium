# Base prompt: working on worlds & world generation

You are working on Sexidium's **managed worlds**: the lobby, disposable minigame temp worlds, cloned
arena maps, and persistent experience worlds with linked Nether/End dimensions. Reference:
[lobby-worlds-and-social.md](../gameplay/lobby-worlds-and-social.md). All policy (naming, pooling, disposal) lives in
core; platform backends are thin executors.

## Key files

| Concern | File(s) |
|---|---|
| Core control layer (all policy) | `packages/core/src/main/java/com/sexidium/core/world/AbstractWorldControl.java`, `WorldNaming.java`, `WorldRequest.java`, `WorldSettings.java`, `WorldGeneration.java`, `WorldKind.java` |
| Public seam games use | `…/core/platform/WorldLeaseService.java` (`acquireOrCreate`, `acquireOrCreateClone`, `acquireOrCreatePersistent(name, viewers, WorldGeneration, …)`, `acquireReady`, `deletePersistent`) |
| Paper backend | `packages/module-paper/…/adapter/world/PaperWorldControl.java` (keyed `WorldCreator`s, linked `_nether`/`_end` siblings, `VoidChunkGenerator`), `PaperWorldAdapter.java`, `PaperPortalListener.java` |
| Safe player placement | `…/core/world/SafeSpawn.java` behind `WorldAdapter.safeSpawnPosition()` / `safePositionNear(pos)` |
| Player-following reach | `…/core/world/PlayerRadius.java` — render distance + overscan + the >50%-coverage chunk rule; **every** effect that follows a player resolves its area here |
| Structure/loot generation engine | `…/core/world/gen/StructureBuilder.java`, `LootTable.java`, `TreeSpec.java` |
| Generation request (void + preset) | `…/core/world/WorldGeneration.java`, `…/core/platform/model/WorldTerrain.java` |
| Experience map/dimension choice | `…/game/experience/ExperienceWorldType.java`, `ExperienceSetup.java` |
| Bundled worlds | `assets/worlds/**` → gradle `prepareMapBundle`/`prepareLobbyBundle` → `…/core/world/MapBundle.java`, `LobbyBundle.java` |
| Tests | `SafeSpawnTest`, `WorldGenerationTest`, `WorldNamingTest`, `AbstractWorldControlGcTest`, `WorldCloneTest`, `MapBundleTest`, `world/gen/StructureBuilderTest` |

## Rules

- **Never create a world outside the control layer.** Games declare what they need (a `worldTemplate()`
  for a clone, nothing for a pooled temp world, `acquireOrCreatePersistent` for an experience) and
  receive a `WorldLease`. Disk layout is `world/dimensions/<namespace>/<key>`; naming goes through
  `WorldNaming` only.
- **Persistent experience worlds are never auto-deleted** — only an explicit owner delete/ban via
  `deletePersistent`. Temp worlds are GC'd; do not rely on their contents after match end.
- **Every experience overworld has `_nether`/`_end` siblings** (created eagerly by
  `ensureExperienceSiblings`; portals redirected by `PaperPortalListener`). Reach them from core via
  `WorldAdapter.dimension(WorldDimension)`; never string-build sibling names outside
  `PaperWorldControl.siblingKeyPath`.
- **Prefer a warm world to a new one.** Creating a world is the most expensive thing the plugin does and
  it stalls the server thread for everyone. `WorldPool` keeps worlds ready per `WorldProfile` (dimension +
  void/natural + preset): 3 Overworlds, 3 Nethers, 3 Ends, plus the void shapes. Anything that needs a
  new world should reach the pool — `acquireReady()` for a disposable one, `acquireOrCreatePersistent`
  (which adopts automatically) for an experience, `takePooled(profile)` from a backend provisioning its
  own linked dimensions. Adoption **moves the folder and carries the seed**; never copy chunk data for
  this, and never adopt over a world that already exists on disk.
- **Deleting a world must unregister it everywhere.** An external manager (Multiverse) autoloads before
  this plugin enables, so a registration for a folder we deleted is a boot error for ever. Delete paths go
  through `forgetRegistration` for the world **and its siblings**, loaded or not; `backendCleanupStaleRegistrations`
  repairs historical entries at boot.
- **How a world generates is ONE value**, `WorldGeneration(voidWorld, voidNether, terrain, dimension)`, folded into
  `WorldSettings` by `AbstractWorldControl.persistentRequest` so the backend gets a fully-described
  request and owns no generation policy. Add a new generation knob **there**, not as another boolean
  parameter on `acquireOrCreatePersistent`.
- **Void generation** (`voidWorld`/`voidNether`) is resolved from the challenge catalog
  (`Challenge.requiresVoidWorld/-Nether`) *before* the world exists — it must be derivable from stored
  challenge ids alone.
- **Vanilla terrain presets** (Superflat / Large Biomes / Amplified) are a `WorldTerrain` on the same
  request, mapped to the native type by `PaperWorldControl.nativeWorldType` and applied **after**
  `creator.copy(template)` so it overrides the template's own type. Rules: a VOID world ignores the
  preset (nothing to shape); linked `_nether`/`_end` siblings stay on normal generation, as in vanilla;
  and because terrain is baked in at creation, **nothing may change it on an existing world** — that is
  what `ExperienceWorldType.fixedAtCreation()` enforces.
- **Player placement**: any "drop the player into a world" path uses `safeSpawnPosition()` /
  `safePositionNear` (on-top-of-a-block, never underwater/mid-air; Nether-roof aware). Per-world
  behaviour like keep-inventory is applied through `WorldAdapter.setKeepInventoryEverywhere`, which
  covers all linked dimensions.
- **Generation performance**: never read surface height or pre-generate spawn chunks during
  `createWorld` (it stalls the server; see the comments in `PaperWorldControl.backendAcquire`); load
  chunks explicitly before `setBlock` bursts.
- **Structures & chests**: build through `StructureBuilder`/`LootTable` (deterministic, platform-free,
  unit-testable) — not ad-hoc block loops. Keep geometry in pure classes tested with a recording
  `WorldAdapter` (`SkyblockIslands` + `SkyblockIslandsTest` are the model).
- **Bundled worlds** ship as zips under `assets/worlds/**` (gitignore already whitelists it), staged by
  gradle and extracted at runtime. Maps are **content-tracked**: `prepareMapBundle` writes
  `<world-path> <sha256-of-source-zip>` into `manifest.txt`, `MapBundle` stamps that digest into the
  extracted folder (`.sexidium-map-bundle`), and on the next boot an *unchanged* digest leaves the folder
  alone while a *changed* one replaces it (previous copy moved to `<name>.replaced-<timestamp>`, newest one
  kept). A folder with no stamp is **adopted**, never replaced — that is what keeps an upgrade from wiping
  maps seeded by an older jar. The **lobby** has no equivalent: it is a live loaded world by then, so
  `LobbyBundle` stays seed-if-missing.

## Editing a map (tooling)

Building the content of a world — the lobby, a TNT War arena, an imported `.schem` — is **not** a code
change and touches none of the seams above. It happens in the tools below, and the result is committed as
a zipped world folder under `assets/worlds/**`. All of it runs on Linux; nothing here needs Windows.

| Where | Tool | Installed by | What it is for |
|---|---|---|---|
| Test server | **FastAsyncWorldEdit** | `scripts/init-paper.sh` (`INSTALL_WORLDEDIT=0` to skip) | `//wand`, `//set`, `//copy`/`//paste`, `//stack`, brushes, `//undo`, and `//schem load|save`. Provides `WorldEdit`, so never install WorldEdit beside it. |
| Test server | **Axiom** (Paper plugin) | `scripts/init-paper.sh` (`INSTALL_AXIOM=0` to skip) | Server half of the visual editor. Inert for a vanilla client; `axiom.all` defaults to op. |
| Client | **Axiom**, **WorldEdit**, **WorldEdit CUI**, Fabric API | `scripts/install-world-tools.sh client` | Visual editing/sculpting/blueprints, single-player `//commands` with no server running, and the selection box CUI draws for both. |
| Desktop | **MCA Selector** | `scripts/install-world-tools.sh desktop` | World *folders*, no game running: render the chunk map, then delete/prune/relocate/export chunks. The tool for trimming a bundled map before it ships. |
| Desktop | **Amulet** (optional) | `scripts/install-world-tools.sh amulet` | Block-level offline editing. Upstream ships no Linux binary; it is a source build needing `sudo apt install python3-wxgtk4.0` first, and it is the one tool here that may simply not build. |

Facts worth keeping:

- **Op yourself first.** `test/paper` boots with an empty `ops.json`; both FAWE and Axiom are
  permission-gated, so `op <name>` in the console comes before `//wand`.
- **A re-exported map reaches the server by itself.** Zip the edited world back into
  `assets/worlds/tntwars/<id>.zip` and restart — the digest changed, so `MapBundle` replaces the extracted
  copy (old one kept as `<id>.replaced-<timestamp>`). No `rm -rf` of the world folder, and no risk of
  testing the stale map by mistake.
- **Schematics are tracked in `assets/schematics/`**, and provisioning copies them into FAWE's folder
  (newer-source-only). `//schem save` writes into gitignored `test/paper/` — copy anything worth keeping
  back by hand. See [`assets/schematics/README.md`](../../assets/schematics/README.md).
- **Client mods install into their own game directory** (`~/.minecraft/sexidium-world-editing`), not the
  shared `~/.minecraft/mods`: one mods folder is read by every profile, so Fabric jars sitting beside
  NeoForge ones break the other loader's launch. (The client-side modding here is tooling only —
  Sexidium itself stays server-side.)
- **Client mod versions are hard-pinned** to the server's Minecraft version (read from
  `test/paper/.mc-version`). A mod for another version does not degrade — it aborts the launch — so the
  installer skips a mod with no matching build rather than installing a mismatched one.
- **MCA Selector needs JavaFX**, which no modern JDK bundles. `install-world-tools.sh` fetches the
  modules into `tools/javafx` and the generated `tools/mcaselector/mcaselector` wrapper puts them on the
  module path. Run the bare jar and it exits 0 with no window and no error.
- **`launcher_profiles.json` belongs to whichever launcher is open.** Launchers rewrite it wholesale from
  memory, discarding entries they do not know about, so the installer refuses to write a profile while one
  is running and prints the version + game directory to enter by hand instead.

`scripts/install-world-tools.sh status` prints what is installed on all three sides.

## Checklist

- [ ] New world need expressed through `WorldLeaseService`, not a backend
- [ ] Naming through `WorldNaming`; no hand-built paths or keys
- [ ] Teleports use safe-spawn resolution
- [ ] Anything that reaches "around a player" resolves its area from `PlayerRadius`, not its own formula
- [ ] Geometry in a pure class + unit test against a fake `WorldAdapter`
- [ ] Settings drift (PvP/difficulty/gamerules) expressed in `WorldSettings`, not per-backend
- [ ] Generation knobs added to `WorldGeneration`/`WorldSettings`, not as new positional parameters
- [ ] A new world need is served from `WorldPool` before it generates one
- [ ] A delete path unregisters the world **and its siblings** from Multiverse, loaded or not
- [ ] World-scoped adapter calls (`isChunkLoaded`/`loadChunk`/`convertChunk`/build heights) go through
      `WorldAdapter.inWorld(name)` — they answer for the adapter you hold, not the position you pass
- [ ] Anything that changes terrain is unreachable for an already-created world
- [ ] [lobby-worlds-and-social.md](../gameplay/lobby-worlds-and-social.md) updated in the same change

---
*Keeping this current: tracks `AbstractWorldControl`/`WorldLeaseService`/`WorldNaming`,
`PaperWorldControl`, `SafeSpawn`, `world/gen/`, the bundle pipeline, and the map-editing toolchain in
`scripts/install-world-tools.sh` + `ensure_world_editors` in `scripts/init-paper.sh`. Update it in the
same change that alters those workflows.*
