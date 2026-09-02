# Review — Stage 0 implementation vs `Plan.md`

> **Status: 27 of the 28 findings below are fixed; one (B22) was withdrawn as a mis-diagnosis.** See
> [§7 Fixes applied](#7-fixes-applied) for what changed and how each was verified. The findings are kept in
> their original wording so the record of what was wrong survives alongside the repair — including B22,
> which was wrong, was shipped, and was caught by the very boot log this work added.


**Scope:** every item in `Plan.md` §5 "Stage 0" (0a–0h) plus §6 blast radius and §7 testing.
**Method:** three independent review agents, read-only, splitting the plan 0a–0c / 0d–0f+§6 / 0g–0h+§7+docs.
Findings were verified by reading source, running `:packages:core:test` and the module test tasks, building
the Paper jar twice, and `javap`-ing `ClientboundLoginPacket` out of the pinned 26.1.2 and 26.2 server jars.

**State of the tree:** all of this work is **uncommitted**. `git log` ends at `675c0ef`; `Plan.md` itself is
untracked. Nothing below has been committed, so line numbers are working-tree line numbers.

---

## 1. Verdict at a glance

| Item | Verdict | One line |
|---|---|---|
| **0a** Folia scheduler | ✅ implemented as proposed | `runAtFixedRate` + `ScheduledTask`; sole legacy site confirmed gone. Region-thread correctness is still wrong (**B7**). |
| **0b** Backend/Capability/Version | ⚠️ **partial** | All types exist and match §4.1 shapes; `BackendStack` has **zero production callers**, ~1 of 7 fold-in sites folded, and the version chain is broken (**B3**, **B8**). |
| **0c** Pack-format consistency test | ✅ implemented as proposed | Genuinely reads all four sources incl. parsing `scripts/lib/paper.sh` from disk; runs live, fails on drift. |
| **0d** Golden API-surface test | ⚠️ implemented, **two holes** | Golden files are *exactly* accurate (re-derived independently; 88 outside `platform/`, matching the plan). But FQN usage bypasses it entirely (**B1**) and the regeneration command is a no-op (**B2**). |
| **0e** Login-packet shape check | ✅ implemented as proposed | Wired at enable, triple-guarded, **verified correct against both real server jars**. Tests added — the plan's "no test at all" gap is closed. |
| **0f** Bundle core via Configuration | ✅ implemented, 1 latent risk | Byte-for-byte identical jar (1469 entries), config cache stored **and reused**. Latent fat-jar regression (**B9**) and a comment that voids a §6 claim (**B10**). |
| **0g** NeoForge doc drift | ✅ implemented as proposed | 107 → 19 references, all now framed as history. Module tree + mermaid graph match `settings.gradle.kts`. |
| **0h** Three small fixes | ⚠️ 2.5 of 3 | `FoliaSupport` clean. Multiverse dedupe dropped a parameter (**B6**). `platformType()` ordering is wrong (**B13**). |
| **§7** `/sx admin capabilities` | ⚠️ partial | Exists and is permission-gated correctly, but omits `ServerBuildInfo` and is **undocumented** (**B12**). |
| **§7** `PackFormats` unit tests | ✅ implemented | Boundaries, RC suffixes, `null`/`""`/garbage all covered. |
| **§6** Blast radius | ✅ no regressions | Fork budget, toolchains, JaCoCo, `archiveBaseName`, `build/libs/{paper,velocity}/` all unchanged. Docs convention **not** honoured for the new build/test work. |

**Nothing in the plan was skipped outright.** Two items (0b, 0h) are meaningfully short of what §5 asked for;
everything else landed, and three items (0c, 0e, 0f) were verified empirically rather than by inspection.

---

## 2. What was implemented — detail

### 0a — Folia contract violation ✅

`PaperAuthHold.java:88-89` now calls `getGlobalRegionScheduler().runAtFixedRate(plugin, ignored -> tick(), TICK_PERIOD, TICK_PERIOD)`;
the field at `:71` is `io.papermc.paper.threadedregions.scheduler.ScheduledTask`. `TICK_PERIOD = 20L` satisfies
`runAtFixedRate`'s `initialDelay >= 1` requirement (unlike `runTaskTimer`, which accepts 0), so no
`IllegalArgumentException`. `stop()` (`:92-97`) null-checks and clears the field; `cancel()` is idempotent.

The plan's "sole such site" claim was re-verified: `git grep -n 'getScheduler()\.runTask\|BukkitTask\|BukkitRunnable'`
returns **nothing** in the current tree. Every other `getScheduler()` hit is `player.getScheduler()` /
`display.getScheduler()` (entity scheduler) or a `PaperSchedulerAdapter` wrapper.

### 0b — `Backend` / `CapabilityRegistry` / `ServerVersionPort` ⚠️

Everything §4.1 specified exists, with the right shapes:

| §4.1 proposal | Status |
|---|---|
| `core/platform/backend/{Backend,BackendStack}.java` | present |
| `ServerVersion` record `(major,minor,patch,raw)` + `atLeast()` + `UNKNOWN` | matches exactly |
| `ServerVersionPort` (`version()`, `packFormat()` defaulting `-1`) | matches; "no opinion" doctrine preserved |
| `Capability` enum | all 7 named constants + `LOBBY_NPCS` |
| `CapabilityRegistry` (`supported()`, `has()`, `reason()`) | matches exactly |
| `versions()` / `capabilities()` on `ServerAdapter` | `ServerAdapter.java:143`, `:151`, both inert by default |
| **Do not move HUD classes** | honoured — `HudDriverStack implements HudDriver, Backend<HudCapability>` |
| Migrate NPCs first | `PaperNpcBackend implements Backend<Capability>`, wired at `PaperServerAdapter.java:189-206` |
| Generalised `linkable(fqcn, cl)` probe | `util/PlatformProbes.java:18-25`, correctly catching `ClassNotFoundException \| LinkageError` |
| One probing `PaperCapabilityRegistry`, not a `v26_1`/`v26_2` pair | honoured |

Extras beyond the plan: a boot line `SX-VERSION` / `SX-CAPABILITY` (`PaperSexidiumPlugin.java:149-177`), the
`/sx admin capabilities` command, and tests `BackendStackTest`, `CapabilityRegistryTest`, `ServerVersionTest`,
`PaperNpcBackendTest`, `PaperServerVersionPortTest`.

**Where it falls short:**

1. **`BackendStack` has no production caller.** Grep outside tests returns only its own file and one javadoc
   mention. `Backend<T>` is implemented twice but never *consumed* polymorphically — `PaperServerAdapter` calls
   `npcBackend().adapter()` directly and `PaperCapabilityRegistry` calls `unavailableReason()`. Neither goes
   through `capabilities()` / `supports()` / `select()`. `PaperNpcBackend.capabilities()` is dead outside tests.
   The generalisation is currently an interface with two implementors and no client.
2. **§4.1's "sites to fold in" list is ~1/7 done.** Folded: `PaperUiItemFactory`/`PaperDecorAdapter` (now
   `PaperDecorAdapter.java:222` → `PaperUiItemFactory.applyItemModel`) and `PaperSkullSkins`/`PaperNpcSkinResolver`
   (→ `util/SkinsRestorerSupport.java`). **Not** folded: `BetterHudLink.linkable()` (own copy at `:96`),
   `PaperHardcoreView` (`:113`, `:251`), `PaperMenuAdapter:90` → `PaperGeyser`, `MultiverseBridge` (`:66`, `:259`),
   `FoliaSupport` (own copy, widened but still duplicated).
3. **Four surviving probe sites catch neither `LinkageError` nor use `initialize=false`** — see **B4**, **B5**, **B14**.
4. **§4.3 rule 1 violated for the version port:** `PaperServerAdapter.versions()` (`:398-400`) constructs a fresh
   `PaperServerVersionPort.probe()` on *every* call. `capabilities()` does cache.

### 0c — Pack-format consistency test ✅

`packages/module-paper/src/test/java/com/sexidium/paper/adapter/ui/betterhud/PackFormatConsistencyTest.java`.
It reads all four sources for real — no number is hardcoded on both sides of any assertion:

- `SexidiumResourcePack.PACK_FORMAT` widened to `public` and read at `:43`, `:66`.
- `PackFormats` widened to `public final class` / `public of()`, read at `:41`, `:51`, `:53`, `:66`.
- `BetterHudLink.SUPPORTED_PACK_FORMAT_MIN/MAX` widened `private` → package-private, read at `:53`, `:55`.
- `scripts/lib/paper.sh` **parsed from disk** (`:73-82`), matching the real `PAPER_VERSION` line → `26.1.2`,
  fed through `PackFormats.of()` and compared against `SexidiumResourcePack.PACK_FORMAT`.

It genuinely runs: the result XML has all three `<testcase>` entries with **no `<skipped>`**, so the
`@EnabledIf("repoRootPresent")` gate found the repo root and the shell-script assertion is live. Drift in any
of the four sources fails at least one assertion.

Two soft spots (not bugs): tests 1 and 2 hardcode the *version strings* `"26.1"`/`"26.2"` rather than deriving
them from `PAPER_VERSION`, so a coordinated bump to 26.2 forces a test edit; and `plugin.yml`'s `api-version`
is named in a failure message but never asserted.

### 0d — Golden API-surface test ⚠️

Two copies (`module-paper` and `module-velocity`, ~100 lines each, no shared helper) with checked-in goldens at
`src/test/resources/golden/core-api-surface.txt` (141 and 34 lines). Both goldens were **independently
re-derived with grep and `diff` — IDENTICAL**, and all 154 union entries resolve to a real core source file or
a nested class of one. The union is 154 types, **88 outside `platform/`** — exactly the plan's figure (148 → 154
is the six new `platform/{backend,version,capability}` types from 0b). Both tests pass.

Handled correctly: nested classes, missing-golden (throws), missing-source-dir (throws), empty scan (fails with
141 REMOVED). Not handled: fully-qualified usage (**B1**), regeneration (**B2**), wildcard imports (**B15**).

### 0e — Login-packet shape check ✅

`PaperHardcoreView.loginPacketShapeReport()` at `:111-135`, backed by `KNOWN_LOGIN_COMPONENTS` at `:54-57`.
Wired at enable: `PaperSexidiumPlugin.java:130` → `logVersionAndCapabilities():162-178` →
`PaperServerAdapter.capabilities():403` → `PaperCapabilityRegistry.probeHardcoreViewPacket():72-77`, surfacing
as `SX-CAPABILITY no=HARDCORE_VIEW_PACKET (…)`.

**Cannot brick boot** (§4.3 rule 5): the probe catches `ClassNotFoundException | LinkageError`, the enable-time
call is additionally wrapped at `PaperSexidiumPlugin.java:175-177`, and the command path is guarded at
`AdminCommands.java:199-206`. No side effects on the existing `supported`/`giveUp` state.

**Verified against the real jars.** `ClientboundLoginPacket.class` extracted from `test/paper-26.1.2/…` and
`test/paper-26.2/…` and run through `javap -p`: 26.1.2 has 11 components (no `onlineMode`), 26.2 has 12 with
`onlineMode` inserted before `enforcesSecureChat`. `KNOWN_LOGIN_COMPONENTS` is exactly the 26.2 set, so the
report is green on both pinned servers — the correct answer.

Tests were added (`PaperHardcoreViewLoginShapeTest.java`, 2 passing), closing the plan's "no test at all" gap.

### 0f — Bundle core through a resolvable Configuration ✅

`packages/module-paper/build.gradle.kts:83-90` (`bundledCore`) and `:119-131` (rewritten `tasks.jar`).
Deviations from the plan snippet: named `bundledCore`, plus an explicit `dependsOn(bundledCore.buildDependencies)`.
`duplicatesStrategy = EXCLUDE` predates the change.

Verified by building, not reading:

- **Jar contents unchanged** — `unzip -l` entry lists of the new jar vs the pre-existing deployed jar:
  **1469 entries each, `diff` IDENTICAL**.
- All three `bundled/maps/tntwar/*.zip` present, plus `bundled/maps/manifest.txt` and `bundled/menupack-textures/`.
- **No fat-jar pull-in today**: `:packages:module-paper:dependencies --configuration bundledCore` → the graph is
  literally `\--- project :packages:core` and nothing more (core declares only `compileOnly` + `testImplementation`).
- Exactly one 25-byte `META-INF/MANIFEST.MF`.
- **Configuration cache: entry stored, then reused** on the second run. The `val bundledCoreConfiguration = bundledCore`
  local-capture discipline is followed; no task action captures `project`, `file()` or a script-level val.
- Deploy layout intact: `archiveBaseName` still `Sexidium-Paper`, `collectJars` still syncs to
  `build/libs/{paper,velocity,internal}`. All ≥8 hardcoded consumers (`scripts/lib/paper.sh:74`, `store.sh:57-58`,
  `velocity.sh:406,414`, `net.sh:269`, `remote/checks.py:226`, `remote/pipeline.py:419,1049`, `docker/node-entry.sh:94`)
  are unaffected — `git diff -- '*.kts'` touches only that one build file.

### 0g — NeoForge doc drift ✅

`grep -ric neoforge docs/` now totals **19** across 9 files (was 107). All 19 read as explicit history or
withdrawal (`tech-decisions.md:14-17`, `:50-51`, `:247`; `known-issues.md:178-181`; `commands.md:403`;
`menus.md:120`; `README.md:61`; `lobby-worlds-and-social.md:14`; `ui-and-localization.md:31`), plus two genuine
non-drift hits (`platform-and-adapters.md:102` — `PlatformType` really does have a `NEOFORGE` member;
`work-on-worlds.md:105` — client mod loaders). The module tree (`overview.md:24-29`) and mermaid graph
(`overview.md:42-62`) now show `core` / `module-paper` / `module-velocity`, matching `settings.gradle.kts` and
`ls packages/` exactly. `add-a-platform-capability.md` was rewritten, replacing the NeoForge rows with
capability-probe rows.

### 0h — the three small fixes ⚠️

- **`FoliaSupport`** ✅ — `:25` now catches `ClassNotFoundException | LinkageError`, and `:21-23` switched to
  `Class.forName(name, false, FoliaSupport.class.getClassLoader())`. `initialize=false` is a strict improvement.
- **Multiverse dedupe** ⚠️ — `PaperLobbyBootstrap.MVWorldBridge` (206 lines) deleted; the call site now uses
  `MultiverseBridge.tryBind/world/importWorld`. Both v4 and v5 paths survive as `addWorldV4`/`importV5`. But it
  is **not** behaviour-preserving (**B6**) and loses diagnostics (**B18**). The dropped `createWorld`/`applyLobbyFlags`
  were dead code — fine.
- **`platformType()`** ⚠️ — `PaperServerAdapter.java:131-142` now uses `ServerBuildInfo.isBrandCompatible(BRAND_PAPER_ID)`,
  API usage verified against the actual `paper-api-26.1.2` jar. Fallback handling is correct: `catch (RuntimeException | LinkageError)`
  covers the `NoClassDefFoundError` a fork without `ServerBuildInfo` raises, resolution is lazy and inside the `try`,
  and the substring sniff is retained. **Ordering is wrong** (**B13**).

### §7 — Testing ⚠️/✅

- **`/sx admin capabilities`** exists: dispatch `AdminCommands.java:71`, body `:196-236`, tab-completion `:256`,
  usage string `:22`. Permission is correct — the whole `admin` bucket is behind `sexidium.admin`
  (`CoreCommandService.java:30`, `:138`). It does **not** dump `ServerBuildInfo` as §7 asked (no brand, build
  number, or git commit — only version + pack format), and it is undocumented (**B12**).
- **`PackFormatsTest.java`** (new, 50 lines) covers `26.1→84`, `26.2→88`, patch-invariance, `26.3`/`25.6`→`-1`,
  RC suffix, `null`, `""`, whitespace, `"garbage"`, `"26"`, `".1"`. The plan's "83/87" are pack-*format* numbers,
  not version inputs, so they cannot be `of()` arguments; they are enforced instead by `PackFormatConsistencyTest`
  (`MIN == of("26.1")`, `MAX < of("26.2")`, i.e. 84 and 87 < 88). Coverage is complete for the intent.

---

## 3. Bugs found

Severity is about blast radius on a live server, not effort to fix. **B3** and **B8** were independently
reported by two reviewers.

### High

**B1 · The golden API-surface test is bypassed by fully-qualified names — and 8 core types already bypass it.**
`GoldenApiSurfaceTest.importsOf()` (paper `:60-66`, velocity `:65-71`) only matches lines starting with
`import com.sexidium.core.`. A core type used by FQN needs no import and is invisible. Already in main code and
absent from both goldens:
`PaperServerAdapter.java:329-330` (`core.platform.NodeHealthPort`, return type *and* anonymous impl),
`PaperWorldControl.java:1175` + `PaperEventBridge.java:264` (`core.world.WorldKey`),
`PaperUiAdapter.java:66` (`core.platform.model.TitleSpecs`),
`SexidiumVelocityPlugin.java:59,142` (`core.network.transfer.DbTransferService`, a field type) and `:177`
(`core.network.BuildIdentity`), plus `core.auth.premium.HttpMojangApiClient`, `core.network.DbWorldLeaseAuthority`,
`core.game.EntryPolicy`.
*Failure scenario:* the boundary widens one type per commit with a green build — exactly the drift 0d exists to
stop. Worse, the newest code in this change set uses the pattern (`PaperSexidiumPlugin.java:168-169`,
`PaperCapabilityRegistry.java:73`), so the style is spreading, and anyone wanting to dodge a golden-file review
just writes the FQN. The plan's own "148 / 88 distinct types" measurement is an undercount by ~8.

**B2 · The documented golden-regeneration command is a no-op.**
`GoldenApiSurfaceTest.java:39` (velocity `:37`) reads `Boolean.getBoolean("sexidium.updateGolden")` — the *forked
test JVM's* properties. Neither `module-paper/build.gradle.kts:109-117` nor `module-velocity/build.gradle.kts:47-49`
forwards it; there is no `systemProperty(...)` anywhere in the build. `./gradlew -D…` sets it on the daemon, not
the worker. *Verified empirically:* running the documented command with `--rerun-tasks` left the golden file's
mtime and sha256 unchanged, so `writeGolden()` never executed.
*Failure scenario:* a developer legitimately adds a core import, the test fails and tells them (in both javadocs
and the assertion message) to run a command that fails identically. The only way out is hand-editing the golden —
the error-prone step the test was meant to remove.

### Medium

**B3 · `HARDCORE_VIEW_PACKET` reports *supported* on every server where it definitively cannot work.**
`PaperHardcoreView.java:131-134` returns `List.of()` ("no problems") when `Class.forName(LOGIN_PACKET)` throws
`ClassNotFoundException | LinkageError`. `PaperCapabilityRegistry.probeHardcoreViewPacket():72-77` only records a
miss when the list is non-empty, and `CapabilityRegistry.of()` (`:61`) treats every capability absent from the map
as supported.
*Failure scenario:* on any fork that does not expose `ClientboundLoginPacket` under that name, the shape report is
empty → the registry says supported → `SX-CAPABILITY` prints nothing and `/sx admin capabilities` shows it green,
while `PaperHardcoreView.bind():264` has already given up and hardcore hearts never render. This is fail-open —
the opposite of what `Capability.java:20-24` and `CapabilityRegistry.java:20-23` both promise.

**B4 · `PaperGeyser` catches only `ClassNotFoundException`; the escaping `LinkageError` reaches the menu hot path.**
`PaperGeyser.java:229-233` uses 1-arg `Class.forName(CUMULUS_FORM)` (`initialize=true`) inside
`catch (ClassNotFoundException)`. Caller `resolveFloodgate():184-199` catches only `ReflectiveOperationException | RuntimeException`;
`resolveGeyser():207-223` has the identical gap.
*Failure scenario:* Cumulus/Floodgate installed but built against an incompatible Java or missing a transitive type →
`UnsupportedClassVersionError` / `NoClassDefFoundError` / `ExceptionInInitializerError` propagates out of
`bedrockUiAvailable()` → out of `PaperMenuAdapter.java:90` → the player's menu **open throws** instead of degrading
to the chest GUI (§4.3 rule 2). This is precisely what `PlatformProbes` was written for, and §4.1 listed this site
for folding. The new `probeBedrockForms():104` *does* wrap it — so the boot log survives, but the hot path does not.

**B5 · `MultiverseBridge.tryBind` can kill plugin enable.**
`MultiverseBridge.java:64-74` uses 1-arg `Class.forName(V5_API_CLASS)` inside `catch (ReflectiveOperationException)` —
no `LinkageError`, no `RuntimeException`. `tryBind` is called from `PaperWorldControl.java:73`, in a constructor
invoked from `PaperServerAdapter`'s constructor during `onEnable`.
*Failure scenario:* Multiverse-Core v5 present and enabled but compiled against a newer Java, or a class whose
`<clinit>` fails → `ExceptionInInitializerError` escapes `tryBind` → escapes the adapter constructor → **the whole
plugin fails to enable**, over an optional soft-depend. §4.3 rule 5: a version probe must never be able to brick boot.

**B6 · The Multiverse dedupe silently dropped the `type` parameter on the v4 path.**
`MultiverseBridge.java:272-279` — `addWorldV4(String, World.Environment, org.bukkit.WorldType type, String)`
accepts `type` and never uses it; the reflective `invoke` hardcodes `org.bukkit.WorldType.NORMAL`. The deleted
`PaperLobbyBootstrap.MVWorldBridge.addWorldV4` passed `type` through. Latent today (both live call sites pass
`NORMAL`: `MultiverseBridge.java:85`, `PaperLobbyBootstrap.java:265`), but it is a trap for the next caller that
passes `FLAT`/`AMPLIFIED`. Fix: pass `type`, with a `type == null ? NORMAL : type` guard.

**B7 · `PaperAuthHold.tick()` mutates region-owned player state from the global region thread.**
The comment at `:83-87` claims *"The tick body touches no world state (database rows + player action bars)"*.
It does: `tick()` → `:222 player.kick(...)`, and `tick()` → `:238 release(...)` → `:195 player.setGameMode(...)`,
`:198 player.setInvulnerable(false)`, `:202-203 showPlayer(...)`.
*Failure scenario:* on Folia, a held player standing in a normal region is approved; `tick()` on the *global*
region thread calls `setGameMode(SURVIVAL)` on an entity owned by another region → Folia's tick-thread check
throws, the release is half-applied (the row was already cleared by `holds.release` at `:178`), and the player
stays frozen and invulnerable with no hold row to time them out. The per-player part belongs on the entity
scheduler, as `PaperSchedulerAdapter.runForPlayer` already does. 0a fixed the *startup* symptom, not the
region-correctness the comment asserts. (Not executable against Folia here; the claim rests on Folia's documented
thread-ownership contract.)

**B8 · The three-step version chain is at most two steps, often one.** *(reported independently by two reviewers)*
`PaperServerVersionPort.java:38-42` — step 1 `return ServerVersion.parse(ServerBuildInfo.buildInfo().minecraftVersionId());`
returns **unconditionally**, including `UNKNOWN`, unlike step 2 (`:45`) which correctly checks `known()`.
`:48-51` — step 2's `catch (RuntimeException | LinkageError)` does `return ServerVersion.UNKNOWN;` instead of
falling through to the `getBukkitVersion()` last resort at `:52`.
*Failure scenario:* a fork whose `minecraftVersionId()` returns `""`/`null`/non-numeric → step 1 returns `UNKNOWN`
and steps 2–3 never run, though `Bukkit.getMinecraftVersion()` would have answered. Or a fork without
`getMinecraftVersion()` → `NoSuchMethodError` → `UNKNOWN`, never trying the fallback the class's own javadoc calls
*"coarsest, always there"*. Consequences: `packFormat()` → `-1`, and `PaperCapabilityRegistry.probeDimensionStorage():82`
reports `DIMENSION_STORAGE_KEYED` unsupported on a server that supports it. This defeats the point of
`/sx admin capabilities` on exactly the non-Paper servers it was added to diagnose. The `// unit tests: no server at all`
rationale does not hold — the step-3 catch at `:55-57` already covers that case.

**B9 · Latent fat-jar regression in `bundledCore`.**
`module-paper/build.gradle.kts:130` — `from(provider { bundledCoreConfiguration.map { zipTree(it) } })` maps over
the **whole resolved FileCollection**, not just core's own artifact. Harmless today (verified: one file).
*Failure scenario:* someone adds `implementation("com.google.gson:…")` to `:packages:core`; the Paper jar silently
grows by every transitive jar's *unpacked contents* — an unrelocated fat jar inside a Paper plugin, where the old
source-set-output approach would have added nothing. There is no `isTransitive = false` at `:85`, no dependency
filter, and no warning comment. Second-order: `bundledCore` declares no attributes, so variant selection falls back
to core's `default` configuration and yields a jar. If anyone adds `attributes { … LibraryElements.CLASSES }`,
`bundledCore` resolves to a *directory* and `zipTree(directory)` fails the build with `Cannot expand ZIP`.

**B10 · The 0f build comment claims a source-set fix that was not delivered.**
`module-paper/build.gradle.kts:80-81`: *"…and it silently skipped every source set not literally named `main`."*
It still does. `bundledCore` resolves core's `jar` artifact, and `packages/core/build.gradle.kts:41-43` packs only
`sourceSets.main.output`. A new source set in `:packages:core` still lands in neither jar. Plan §6's "0f fixes this
structurally" is **not true**, and the comment will mislead the next person into exactly the §6 failure it describes
(*"the fallback runs forever on the right JVM, silently"*).
The isolated-projects motivation at `:78-79` is also only partly delivered: root `build.gradle.kts:370-401` still does
`project(":packages:core").tasks.named("jar")` and `paperAdapterProject.layout.buildDirectory`, and
`packages/core/build.gradle.kts:35-39` / `module-paper:101` still do `rootProject.tasks.named(...)`. Isolated
projects would still reject this build.

**B11 · `CapabilityRegistry.of()` is fail-open for any capability nobody probed.**
`CapabilityRegistry.java:61-65` builds `EnumSet.allOf(Capability.class)` and *subtracts* the misses, while the
javadoc at `:20-23` calls it a "complete probe" that "answers false for anything unknown rather than guessing yes".
Nothing checks that `PaperCapabilityRegistry.probe` asks about every constant.
*Failure scenario:* someone adds `Capability.FOO` and forgets a probe → `has(FOO)` is `true` on every server,
`reason(FOO)` is empty, the boot log is silent, and callers take the capable path into a `NoSuchMethodError`.
No test guards this — `CapabilityRegistryTest` only asserts arithmetic on `Capability.values().length`. **B3** is the
already-realised instance.

**B12 · `/sx admin capabilities` is undocumented, against the repo's own convention.**
The command is referenced in **six** other docs (`README.md:132`, `add-a-platform-capability.md:30`,
`platform-and-adapters.md:35`, `known-issues.md:181`, `overview.md:87`/`:90`/`:286`, `game-framework.md:610`) but
appears **nowhere** in `docs/interface/commands.md`, whose own footer says *"Update this doc in the same change …
Triggers: a subcommand added/removed."* Three concrete misses: no row in the `/sx admin` table (`:77-86`); the
`ADMIN_SUBCOMMANDS` gloss (`:108`) omits `net`, `backup`, `selftest`, `broadcast`, `capabilities` versus the real
dispatch at `AdminCommands.java:58-72`; the tab-completion table (`:375`) omits `capabilities`, which
`AdminCommands.java:254-257` now suggests. (`net`/`selftest`/`broadcast` were already undocumented;
`capabilities` is drift *this* change introduced.)

Related: the new build/test work is entirely undocumented —
`grep -rn "GoldenApiSurface\|core-api-surface\|bundledCore\|loginPacketShapeReport\|SX-CAPABILITY\|SX-VERSION" docs/ README.md`
returns **zero hits**, despite §6's "treat the doc edit as part of the commit".

### Low–Medium

**B13 · `platformType()` brand check runs before the hybrid sniff.**
`PaperServerAdapter.java:131-147`. The comment asserts *"Mohist/Arclight/Magma hybrids are not [Paper-brand-compatible]"* —
an unverified claim about third-party servers. Modern Mohist/Arclight lines are Paper-derived; if
`isBrandCompatible(BRAND_PAPER_ID)` returns true for them, `platformType()` returns `BUKKIT` at `:138` and the
`name.contains("mohist")` branch at `:144` is dead code — a silent behaviour change from the previous name-only
logic. `PaperServerAdapterTest:71,77,83` still assert `HYBRID`, but they run without a server, so
`ServerBuildInfo.buildInfo()` throws and the sniff *is* reached — **the tests cannot catch this regression**.
Correct order: name sniff first (it identifies the one case the brand check provably cannot), brand check second.
Low today only because `HYBRID` has one consumer — the diagnostic header at `AdminCommands.java:207`.

**B14 · `BetterHudLink.serverPackFormat()` catches only `RuntimeException` and bypasses the new port.**
`BetterHudLink.java:136-141` still calls `Bukkit.getMinecraftVersion()` directly rather than going through
`ServerVersionPort` — which §4.2 named as the point of the exercise — and its catch omits `LinkageError`.
*Failure scenario:* a fork without `getMinecraftVersion()` → `NoSuchMethodError` escapes `capable()` → escapes
`BetterHudDriver.capabilities()` → escapes `HudDriverStack.capabilities()` → the HUD open throws instead of falling
back to the sidebar. Separately, the javadoc at `:104-105` ("Warns once, then answers false for the rest of the
session") is wrong — only the *warning* is once; `capable()` re-evaluates every call.

**B15 · Wildcard imports are an unguarded escape hatch in the golden test.**
`paper:62-63`, `velocity:67-68`. One `import com.sexidium.core.game.*;` becomes a single golden entry and thereafter
permits unbounded use of that package with no further diff. Static imports are excluded outright, and the
`!line.startsWith("import static ")` clause is dead code (`"import static com…"` already fails the preceding
`startsWith("import com.sexidium.core.")`). Zero of either exists today.

**B16 · Doc footers name packages that do not exist.**
There is no `com.sexidium.core.event` and no `com.sexidium.core.net` package (real layout:
`auth bot command data decor game i18n lib menu network platform world`). Yet:
`overview.md:294` (footer **rewritten by this change**) names `event/GameEventRouter.java` and `event/GameEvent.java`
(real: `game/GameEventRouter.java`; `GameEvent.java` does not exist — the sealed model is `game/GameEvents.java`);
`known-issues.md:194-196` (footer **edited by this change**) names `net/ApiServer.java` and `event/GameEventRouter.java`
(real: `lib/net/ApiServer.java`, `game/GameEventRouter.java`); `docs/README.md:55` lists core subpackages `event/`,
`net/`, `rank/` — none exist. A footer whose named files cannot be found is the one part of the convention that has
to be right, since it tells the next contributor which doc to update.

**B17 · `overview.md:82` now contradicts both the code and its sibling page.**
The line `default MenuAdapter menus(); // NOOP (Paper: the InvUI chest renderer)` was **edited by this change**
(from `(Paper: InvUI; NeoForge: vanilla container)`) and carried the InvUI claim forward — while the code it
documents was corrected in the same diff (`ServerAdapter.java:100` now says *"Paper: the chest menu adapter"*),
`docs/interface/menus.md:364` says in a heading *"Paper (Bukkit-native, **no InvUI**)"*, and
`module-paper/build.gradle.kts` declares no InvUI dependency.

### Low

**B18 · Multiverse dedupe degraded diagnostics.** `MultiverseBridge.java:110-113` dropped log severity from
`warning` to `info` on every v4/v5 import failure, so a Multiverse API break is now below the level operators scan
for; and the dedicated `catch (NoSuchMethodException) → "MVWorldManager.addWorld signature not found on this
Multiverse build"` message is gone, replaced by a generic `"skipped: " + getMessage()` (a raw signature string).
Carried-forward risk: `addWorldV4` reflects `boolean.class` for MV4's `Boolean generateStructures`; if the real
signature is boxed, `getMethod` throws and the v4 path never works — untested either way.

**B19 · `SkinsRestorerSupport` negative-caches a plugin probe forever.**
`util/SkinsRestorerSupport.java:26-46` sets `probed = true` unconditionally. §4.3 rule 1 carves out an explicit
exception: *"probes for plugins re-evaluate per call, because plugins enable late."*
*Failure scenario:* something asks for a skull avatar during another plugin's `onEnable` before SkinsRestorer is
enabled → `SkinsRestorerProvider.get()` throws → `api` pinned to `null` → skins are permanently off for the session,
and `probeSkinsRestorer():116` then reports the misleading *"installed but its storage API does not link"*.
(The double-checked locking itself is correct — `volatile probed` is written last.)

**B20 · `/sx admin capabilities` javadoc overstates its guard and its freshness.**
`AdminCommands.java:197` — `ctx.server.versions()` sits *above* the `try` at `:199`, so the `:194` "Never throws"
claim is one line wider than the code (and `versions()` probes unguarded — see the 0b caching gap). The javadoc also
says *"probed rather than assumed"*, but `PaperServerAdapter.capabilities():399-416` caches for the process lifetime
and `logVersionAndCapabilities` pins that cache at enable — so the command always replays the **boot-time snapshot**.
Deliberate, but misstated to an operator who reads it after installing a plugin at runtime. Also: the boot log emits
`SX-VERSION`/`SX-CAPABILITY` while the command emits `SX-CAPABILITIES`; a grep written against one will not match the other.

**B21 · `ServerVersion.parse` can throw `NumberFormatException`.** `ServerVersion.java:36-38` — the regex group is
unbounded `\d+`, so ≥10 digits throws, escaping a method documented at `:25` as "Anything else is `UNKNOWN`".
Currently masked (`PaperServerVersionPort:55` catches `RuntimeException`), but any future caller inherits an
undocumented throw.

**B22 · The shape check detects added components but never removed ones.** `PaperHardcoreView.java:118-125` iterates
the *actual* components and flags unknown names; a name left in `KNOWN_LOGIN_COMPONENTS` after Minecraft removes it
is never reported. Harmless (the packet fill is also driven by actual components) but the set silently rots — as it
already does on 26.1.2, where `onlineMode` is in the set and not in the packet. A `golden - actual` diff at `fine`
would keep it honest.

**B23 · Golden drift can go undetected: `src/main/java` is not a declared task input.** The `Test` task's only
relevant input is the compiled classpath, so adding an import used **only** in javadoc (`{@link com.sexidium.core.…}`)
changes no bytecode, leaves `:test` UP-TO-DATE, and surfaces later attached to an unrelated commit.
`inputs.dir("src/main/java")` closes it.

**B24 · Three new tests depend on `Test.workingDir` being the project dir.** `GoldenApiSurfaceTest` (paper `:31`,
velocity `:29`) and `PaperHardcoreViewLoginShapeTest:27-28` use relative `Path.of("src","main","java",…)`. They pass
on Gradle's default, but the root build already sets `workingDir = rootDir` for other task types
(`build.gradle.kts:292`, and core's `bakeMenuScreens`). One `tasks.test { workingDir = rootDir }` turns all three into
`NoSuchFileException`, reading as a missing file rather than a config change.

**B25 · `PaperHardcoreViewLoginShapeTest.casesOf()` scans the whole file.** `:53-54` matches `case "<name>" ->`
across all of `PaperHardcoreView.java`, not within `buildLoginPacket`. Correct today (only string switch in the
file); the day a second one is added, the test fails with a message about login-packet components unrelated to the change.

**B26 · Stale `file:line` citations across the touched docs.** The rewrite changed prose around citations without
re-verifying numbers. Eight markdown links point past EOF or at a moved file — `commands.md:123,125,126,137,147,188,191`
cite `CoreCommandService.java#L655…#L1634` when the file is **155 lines** (it was split into focused handlers), and
`commands.md:214` cites `core/lobby/LobbyResult.java` which is now `core/world/lobby/LobbyResult.java`. Sixteen inline
citations are past EOF or nonexistent (`GameManager.java:487…751` — the file is 360 lines; `ChainedChallenge.java:610`;
`TeamDisplay.java`, `AuthLinkResult.java` — no such files). And a dozen in-range-but-wrong targets sit on lines this
change edited, e.g. `overview.md:171,194,199` cites `SexidiumCore.java:94/:109/:211` for `start()`/`reload()`/
`restorePersistedMatches()` (real: `:236`, `:692`, `:892`); `overview.md:248` cites `:71`/`:65`/`:147` (real `:75`/`:55`/`:138`);
`platform-and-adapters.md:122` cites `AbstractWorldControl.java:36` and hooks at `:59-105` (real: class `:43`, hooks
`:118-172`; `:59-105` is the private-field block); `ui-and-localization.md:34,203` cites `UiAdapter.java:17-27` for the
default `showPopup`, a range that cuts off the WIN/ELIMINATION branch and the action-bar fallback.

**B27 · `add-a-platform-capability.md:17` has a dangling reference.** The row *"Parity ledger | known-issues.md + the
parity table in platform-and-adapters.md"* survived 0g deleting that parity table; `grep -rn "parity table" docs/` now
returns only this line. The nearest surviving section is §1.8 "SPI → adapter implementation map"
(`platform-and-adapters.md:139-159`), which is an implementation map, not a parity ledger.

**B28 · Cosmetics.** `PaperCapabilityRegistry.java:63-65` — `Class#getMethod` never returns `null`, so the
`if (setItemModel == null) throw new NoSuchMethodException(...)` is dead code. `BackendStack.close():76-80` aborts
remaining layers if one throws. `BackendStack.capabilities():48-53` returns a fresh mutable `LinkedHashSet` per call
and is re-walked by every `supports()`, making nested stacks O(n²) — no impact at zero callers.
`docs/gameplay/chaos.md` was modified but has **no "Keeping this current" footer** (the only non-index content page
in the diff without one).

---

## 4. Cross-cutting observations

1. **The new safety nets do not run on the deploy path.** There is no `.github/` directory, and
   `scripts/lib/sexidium.sh:518-521` runs `build -x test` unless `SX_RUN_TESTS=1`, which is not the default. 0c, 0d
   and 0e only fire when someone runs `./gradlew build` by hand. The plan's promise that widening becomes "a reviewed
   one-line diff" rests entirely on that discipline. Not introduced by this change, but it caps the value of all three.
2. **The new tests are invisible to the coverage number.** JaCoCo is applied only to `:packages:core`; 0c, 0d and 0e
   all live in `module-paper`/`module-velocity`. Not a regression — but the safety net added by this change contributes
   zero to the metric §6 worries about.
3. **Fail-open is the recurring shape.** **B3**, **B11**, and the version chain in **B8** all default to "capable" or
   "unknown means fine" on the servers least likely to be capable. `Capability`/`CapabilityRegistry` javadoc promises
   the opposite in both files. This is one design decision (subtract-misses from `allOf`) showing up three times.
4. **`LinkageError` coverage is inconsistent.** 0h widened `FoliaSupport` and 0b introduced `PlatformProbes` — but
   `PaperGeyser` (×2), `MultiverseBridge`, `BetterHudLink` and `SkinsRestorerSupport` still use 1-arg `Class.forName`
   with narrow catches. §4.1 listed every one of them as a fold-in site. Finishing the fold would close **B4**, **B5**
   and **B14** as a side effect.
5. **Comments and javadoc drifted ahead of the code in five places** — `PaperAuthHold:83-87` (**B7**),
   `build.gradle.kts:80-81` (**B10**), `CapabilityRegistry:20-23` (**B11**), `BetterHudLink:104-105` (**B14**),
   `AdminCommands:194` (**B20**) — plus the doc-level cases (**B16**, **B17**, **B26**). Several were *written by this
   change*, which makes them harder to catch later than the code they describe.

---

## 5. Suggested order of work

| Priority | Fix | Cost |
|---|---|---|
| 1 | **B5** `MultiverseBridge.tryBind` — widen to `catch (ReflectiveOperationException \| RuntimeException \| LinkageError)`. Only bug here that can prevent enable. | 1 line |
| 2 | **B3** + **B11** — make `probeHardcoreViewPacket` record a miss when the class does not link, and add a test asserting `PaperCapabilityRegistry` probes every `Capability` constant. | ~15 lines |
| 3 | **B8** version chain — `if (known()) return` on step 1; fall through instead of returning in step 2's catch. | 3 lines |
| 4 | **B4** + **B14** — route `PaperGeyser` and `BetterHudLink` through `PlatformProbes.linkable`. Closes two escapes into hot paths and advances the §4.1 fold-in list. | ~10 lines |
| 5 | **B2** golden regeneration — `systemProperty("sexidium.updateGolden", …)` in both test tasks. Without it the escape hatch documented in three places does not exist. | 2 lines |
| 6 | **B6** Multiverse `type` parameter; **B7** move `PaperAuthHold`'s per-player mutations to the entity scheduler (or correct the comment). | small |
| 7 | **B1** FQN bypass — either scan for `com.sexidium.core.` anywhere in source (not just imports) or add a checkstyle-style ban on FQN core usage, then re-derive the golden. | design call |
| 8 | **B9** `isTransitive = false`; **B10** correct or delete the source-set claim in the build comment. | 2 lines |
| 9 | Docs: **B12** (`commands.md` rows + the undocumented build/test work), **B16** footers, **B17** InvUI, **B27** parity ledger, **B26** citations. | mechanical |

**B13** needs a decision, not a patch: confirm what `isBrandCompatible` returns on a real Mohist jar before trusting
the comment. Until then, moving the name sniff above the brand check preserves the previous behaviour at zero risk.

---

## 6. Also done in this session

`.gitignore` — the existing `/test/paper/` + `/test/neoforge/` rules did not cover the per-version directories the
§7 cross-version matrix creates (`test/paper-26.1.2`, `test/paper-26.2`, both present and untracked). Replaced with
`/test/*`. Nothing under `test/` was tracked, so no `git rm --cached` was needed; `git check-ignore -v` confirms both
directories now match.

---

## 7. Fixes applied

All 2,492 tests pass (`core` 2,088 · `module-paper` 369 · `module-velocity` 35, zero skipped), the
configuration cache stores **and reuses**, and `build/libs/{paper,velocity,internal}/` holds exactly the
same three jars as before.

### Correctness

| Finding | Fix |
|---|---|
| **B5** enable-blocking Multiverse probe | `MultiverseBridge.tryBind` catches `ReflectiveOperationException \| RuntimeException \| LinkageError` on both the v4 and v5 paths, and resolves the v5 API through `PlatformProbes.linkableClass`. |
| **B3 / B11** fail-open capabilities | `CapabilityRegistry.of(Map)` replaced by `CapabilityRegistry.probing()` — a `Probe` builder that requires an answer per constant and fails an unanswered one **closed**, with `UNPROBED_REASON`. `PaperCapabilityRegistry` now drives an `EnumMap<Capability, Probe>` walked over `Capability.values()`, so a missing probe is structurally visible; `PaperHardcoreView.unavailableReason()` separates "no shape disagreement" from "no packet at all". |
| **B8** version chain short-circuits | Step 1 returns only a `known()` version; step 2's `catch` falls through to step 3 instead of returning `UNKNOWN`. |
| **B4 / B14** `LinkageError` escaping into hot paths | `PaperGeyser` (both resolvers + Cumulus), `BetterHudLink` (now via `PaperServerVersionPort`, which cannot throw), `PaperHardcoreView.resolve()`, `MultiverseBridge`, `FoliaSupport` and `SkinsRestorerSupport` all go through `PlatformProbes` or a widened catch. No probe in `module-paper` can now escape as a `LinkageError`. |
| **B7** Folia region ownership | `PaperAuthHold` mutates players only through `onPlayerThread(...)` (the entity scheduler). Bookkeeping and the `consumeRequest` database write stay on the calling thread, with the original approved-and-online semantics preserved. |
| **B6** dropped `WorldType` | `addWorldV4` passes `type` through and locates `addWorld` by name+arity, tolerating the boxed/unboxed and `String`/`Long` seed variants that a hard-coded `getMethod` could not. |
| **B13** hybrid detection unreachable | The name sniff runs first (guarded), the brand check second. |
| **B19** plugin probes negative-cached | `SkinsRestorerSupport`, `PaperSkullSkins` and `PaperNpcSkinResolver` cache only success — plugins enable late. |
| **B21** `ServerVersion.parse` could throw | An over-long component now yields `UNKNOWN`, as the contract always claimed. |
| **B20** guard narrower than its javadoc | `versions()`, `capabilities()` and `platformType()` are all inside the `try` in `/sx admin capabilities`; the javadoc now states the snapshot semantics and the log-prefix relationship. |
| **B18** lost Multiverse diagnostics | Import failures log at `warning` again, with a dedicated message for `NoSuchMethodException`. |
| **B22** ~~one-sided shape check~~ **— WITHDRAWN, the finding was wrong** | The asymmetry is correct and deliberate. `KNOWN_LOGIN_COMPONENTS` is a **union across supported versions**: 26.1.2's login packet has 11 components, 26.2's has 12 (`onlineMode`), and one jar serves both. A known-but-absent name is therefore the normal state on the older version, not rot. The "fix" was deployed, and the boot log on the pinned 26.1.2 immediately reported `HARDCORE_VIEW_PACKET` unavailable for a feature that works — `buildLoginPacket` fills by iterating the *actual* components, so the unreached case costs nothing. Reverted to the added-direction-only check, with the reason recorded at the set and a regression test (`aMissingKnownComponentIsNotDrift`) so it is not "fixed" again. |
| **B28** cosmetics | Dead null check removed; `BackendStack.close()` closes every layer and rethrows with suppressed siblings; `supports()` short-circuits via `select()`; `capabilities()` returns an immutable set. |

### The golden surface, rebuilt on inheritance

**B1** and **B2** are the two that made 0d ineffective, so the check was rebuilt rather than patched.
The duplicated ~100-line test in each module is now one `AbstractCoreApiSurfaceTest` in a new
`testFixtures` source set on `:packages:core`, consumed as `testImplementation(testFixtures(project(":packages:core")))`
— a proper dependency, not the cross-project source-set read that 0f exists to avoid. Each module
contributes three overrides: where its sources are, where its golden file is, what to call itself.

- **B1** — the scan matches fully-qualified core **names**, not `import` lines. The eight types that were
  already through the hole are now recorded (paper 141 → 146, velocity 34 → 45).
- **B2** — `configureSourceScanningTests` (new, in `buildSrc/`) forwards `sexidium.updateGolden` into the
  test JVM. The regeneration command documented in three places now actually regenerates; both goldens
  above were produced by running it.
- **B15** — a wildcard import of a core package fails the test with its own message.
- **B23** — `src/main/java` is a declared task input, so a javadoc-only reference cannot hide behind an
  `UP-TO-DATE` test task.
- **B24** — paths resolve from `sexidium.moduleDir`, not from the working directory.

Both holes were verified closed by injecting the drift and watching the build fail: an FQN-only
reference to `com.sexidium.core.game.GameState`, and an `import com.sexidium.core.game.*;`.

### Build

- **B9** — `bundledCore` is `isTransitive = false`, so core gaining a runtime dependency can never
  silently unpack a fat jar into the plugin.
- **B10** — the comment claiming 0f fixed the source-set trap is replaced by one saying it does not, and
  what to do instead.
- **Regression caught and fixed during the work:** the new `testFixtures` source set put a second jar in
  `build/packages-core/libs`, which `collectJars` was copying wholesale — Gradle failed the build on the
  undeclared dependency, and the test-only jar would otherwise have shipped into `internal/`.
  `collectJars` now consumes the core **jar task**, so it copies exactly one artifact and carries its own
  dependency.

### Docs

- **B12** — `commands.md` gains a **Capability readout** section for `/sx admin capabilities` (including
  that it is the boot-time snapshot) plus the five subcommands missing from the admin table, the
  `ADMIN_SUBCOMMANDS` gloss and the tab-completion row. The golden surface is documented as
  `platform-and-adapters.md` §1.9, with its footer updated.
- **B16 / B26** — a citation checker was written and run over every page: 30 broken or out-of-range
  citations fixed (moved packages, the `CoreCommandService` and `GameManager` splits, renamed types),
  plus 16 in-range-but-wrong targets in `overview.md`. It now reports **0 problems** and can be re-run.
- **B17** — the InvUI line, which contradicted both the code and `menus.md`.
- **B27** — the dangling parity-ledger reference.
- **B28** — `chaos.md` gained the "Keeping this current" footer every other content page carries.
