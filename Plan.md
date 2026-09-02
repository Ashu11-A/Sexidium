# Plan — Multi-version structure: should Sexidium adopt the ModelEngine layout?

> **Short answer: no.** Adopt the *idiom*, not the *tree*. Three of ModelEngine's four package
> kinds solve problems this codebase provably does not have; the fourth (a runtime capability
> registry) already exists here in two places and just needs generalising.
>
> This document was produced by three parallel expert passes — JVM/class-file mechanics,
> Minecraft/NMS version churn, and repo architecture — cross-checked against the repo and the
> pinned jars. Claims are marked **[V]** verified this session, **[C]** confirmed externally,
> **[I]** inference.

---

## 1. What was actually asked

The reference layout, read off the ModelEngine jar:

```
com/ticxo/modelengine/
├── api/          1.3 MB   animation, entity, generator, model, nms, ...
├── core/         1.1 MB   the implementation
├── core21/       559 KB   Java21Helper.class + mythic/
├── core25/       1.1 KB   Java25Helper.class          ← ONE class
├── v1_19_R3/     428 KB   ┐
├── v1_20_R1..R4/ ~430 KB  │ 20 modules, each with NMSHandler_v*, NMSFields,
├── v1_21_R1..R7/ ~450 KB  │ NMSMethods + entity/network/parser
└── v26_1, v26_2/ ~444 KB  ┘  ≈ 8.8 MB of near-duplicate code
```

Two questions hide inside one tree, and **they are on completely orthogonal axes**. Conflating
them is the single most common mistake when copying this layout:

| Axis | ModelEngine package | Discriminator | Failure if you get it wrong |
|---|---|---|---|
| **JVM version** | `core21` / `core25` | `Runtime.version()` | `UnsupportedClassVersionError` at class-load, plugin never enables |
| **Minecraft version** | `v1_20_R4`, `v26_2`, … | server build / mappings | `NoSuchMethodError`/`NoSuchFieldError` at first use |

A jar can need one and not the other. **Sexidium needs neither today**, for different reasons on
each axis. Sections 2 and 3 establish why; section 4 is what to do instead.

---

## 2. Axis 1 — Java versions (`core21` / `core25`)

### 2.1 How Java actually behaves

`ClassFile.major_version = 44 + N`:

```
52=Java 8   55=11   61=17   65=21   66=22   67=23   68=24   69=25
```

Preview features additionally set `minor_version = 0xFFFF`, pinning the class to the exact JDK
build that emitted it — which is why no shipped plugin uses `--enable-preview`.

**The load-time rule.** JVMS §5.3.5 step 1: if the bytes carry an unsupported version, the loader
throws `UnsupportedClassVersionError` during *derivation* — the moment `defineClass` is reached.
**Load time, not call time.** You never reach the method body, and a `try/catch` around the call
site is useless because the failure happened earlier.

The exception hierarchy is what makes gating possible:

```
Throwable
├─ Exception → ReflectiveOperationException → ClassNotFoundException   (checked, NOT a LinkageError)
└─ Error → LinkageError
             ├─ ClassFormatError → UnsupportedClassVersionError
             ├─ NoClassDefFoundError
             ├─ IncompatibleClassChangeError (NoSuchMethodError, NoSuchFieldError, …)
             ├─ ExceptionInInitializerError
             └─ BootstrapMethodError            ← lambdas / method refs land here
```

A complete probe catches **both** `ClassNotFoundException` *and* `LinkageError`. Catching only
`ClassNotFoundException` — the usual mistake — lets `UnsupportedClassVersionError` escape and kill
plugin enable.

### 2.2 Why the gated code must be a separate, never-statically-referenced class

Loading is lazy; **verification is not lazy enough to rely on**. JVMS §5.4.3 permits both eager and
lazy constant-pool resolution, and the bytecode verifier loads types it needs for assignability
checks when a method is linked. Concretely:

| Reference to a missing/unloadable type `T` | Fails at | Throws |
|---|---|---|
| `class X extends T` / `implements T` | derivation of `X` — **unconditional** | `NoClassDefFoundError` |
| `static final T f = …` in `<clinit>` | class **initialisation** | `ExceptionInInitializerError`, then `NoClassDefFoundError` forever (class marked erroneous) |
| method body invoking on `T` | verification of that method, or first execution | `NoClassDefFoundError` |
| method that exists on `T` but not this version | first execution | `NoSuchMethodError` |
| `T::method` / lambda over `T` | first execution of the `invokedynamic` | `BootstrapMethodError` (real cause in `getCause()`) |
| **`"com.foo.T"` as a `String`** | **never** | — |

That last row is the entire trick. `Java25Helper` exists as a separate class precisely so the gate
— which lives in a class loaded on *every* JVM — mentions it only as a `CONSTANT_String`:

```java
// Compiled at the BASE release. Names no Java-25 type anywhere.
public final class Java25Support {
  private static final Object HELPER = probe();

  private static Object probe() {
    try {
      Class<?> c = Class.forName("com.sexidium.core.jvm25.Java25Helper", false,
                                 Java25Support.class.getClassLoader());
      return c.getDeclaredConstructor().newInstance();
    } catch (ClassNotFoundException | LinkageError | ReflectiveOperationException unavailable) {
      return null;   // Java 21 path: the class shipped, the JVM refused it. By design.
    }
  }
}
```

To *call* the helper without naming its type, declare an interface **at the base release** that the
helper implements; base code then holds only the base-release type.

**This repo already writes that idiom correctly, three times over** [V]: `FoliaSupport`,
`BetterHudLink` (`Class.forName(fqcn, /*initialize*/ false, cl)` catching `LinkageError`), and
`MultiverseBridge`. `BetterHudLink`'s javadoc states the constraint explicitly — it "names no
BetterHud type anywhere, deliberately… a field of a BetterHud type would resolve at class load and
defeat that before the first check ran." That javadoc is *more conservative than HotSpot strictly
requires*, and that conservatism is correct: the spec licenses eager resolution, so code that
survives by luck of implementation today may not on another JVM or a future release.

### 2.3 What Sexidium's Java floor actually is — measured, not assumed

Class-file majors read directly out of the pinned jars (bytes 6–7 of the first `.class`) [V]:

| Artifact | major | implies |
|---|---|---|
| `io.papermc.paper:paper-api:26.1.2.build.74-stable` | **69** | **Java 25** |
| `io.github.toxicity188:BetterHud-*-api:2.0.0` | **69** | Java 25 |
| **`com.velocitypowered:velocity-api:3.5.1`** | **65** | **Java 21** |
| `build/libs/paper/Sexidium-Paper-1.0.0.jar` | 69 | Java 25 |

And the Minecraft floors [C]: 1.17 → Java 16 · 1.18–1.20.4 → 17 · 1.20.5+ → 21 ·
**Paper 26.1.2 → 25** (not from docs — read out of the jar, which is stronger evidence, and
matches `scripts/lib/paper.sh:89` `REQUIRED_JAVA=25`).

> **The rule:** your Java floor is `max(oldest supported server's floor, oldest supported proxy's
> floor)`. Nothing below it buys anything, because an operator *cannot* run Paper 26.1 on Java 21
> at all.

**Consequences:**

1. **A `core21` for the Paper side would be dead code on day one.** The oldest supported server is
   itself compiled for Java 25.
2. **A `core25` has no payload either.** Grep for `Thread.ofVirtual`, `ScopedValue`,
   `StructuredTaskScope`, `java.lang.classfile`, `java.lang.foreign`, `StableValue`,
   `--enable-preview` across `packages/` and `buildSrc/`: **zero hits** [V]. There is nothing to
   gate.
3. **The floor is being pushed *up*, not down.** BetterHud 2.0.0's major-69 jars make Java 25 a
   hard compile requirement (documented in `packages/module-paper/build.gradle.kts`).
4. **One real, latent exposure exists — and it is on Velocity.** `velocity-api:3.5.1` is major 65,
   so a third-party operator can legitimately run Velocity 3.5.1 on Java 21. `Sexidium-Velocity` is
   major 69, so on such a proxy it dies with `UnsupportedClassVersionError` before a line of our
   code runs. **Our own fleet is unaffected** — `docker/` pins `eclipse-temurin:25-jdk` on every
   node including the proxy.

### 2.4 Mechanism choice, if the Velocity case is ever judged in scope

| Mechanism | Verdict here |
|---|---|
| **Multi-Release JAR** (`META-INF/versions/N/`) | Works on Paper — verified: `FileProviderSource.java:64` opens plugin jars with `JarFile.runtimeVersion()` and threads that same `JarFile` into the legacy `PluginClassLoader` [C]. **But** Shadow's `relocate()` on `module-velocity` mangles versioned entries inconsistently, `mergeServiceFiles()` never visits `META-INF/versions/N/META-INF/services/**`, and `DuplicatesStrategy.EXCLUDE` on `module-paper`'s jar makes manifest merging load-bearing. **Failures are silent.** ❌ |
| **Reflective helper package** (the ModelEngine approach) | Invisible to Shadow, to `duplicatesStrategy`, to jar signing. Failure is *observable and loggable*. Same idiom as the three probes already in the repo. ✅ if ever needed |
| **`options.release.set(21)`** on `:core` + `:module-velocity` | One line per module. Turns "accidentally used a JDK 25 API" into a *compile error* rather than a field `NoSuchMethodError`. ✅ **the cheap answer** |

`--release` vs the alternatives is worth stating precisely, because the repo currently sets
**none** of them [V]:

| | bytecode | language | **platform API linkage** |
|---|---|---|---|
| `-source 21 -target 21` | 65 | 21 | **current JDK's API** — no restriction, compiles JDK-25-only calls happily |
| `--release 21` | 65 | 21 | **JDK 21's API** via `ct.sym` — rejects them at compile time |
| `toolchain = 21` | 65 | 21 | real JDK 21 `java.base` — strongest, needs the JDK present |

`options.release` is also the **only** knob Gradle honours when a toolchain is set;
`sourceCompatibility`/`targetCompatibility` are ignored in that combination.

**Recommendation for axis 1:** keep Java 25 for `:core` and `:module-paper` — the Paper API forces
it and a lower baseline in `core` has no beneficiary, since `core` ships *inside* the Paper jar.
Make one deliberate decision about Velocity (§5, item V1). Do **not** build a `coreNN` split; there
is no call site to put in it.

---

## 3. Axis 2 — Minecraft versions (`v1_20_R4`, `v26_2`, …)

### 3.1 Why ModelEngine genuinely needs 20 modules

It renders rigged 3D models on entities. Nothing in the Bukkit API expresses that, so it works at
the packet layer [I — its core is closed-source; the folder list is your own observation of the
jar]: invisible carrier entities per bone, raw clientbound packets, and — the killer —
`SynchedEntityData` entries written **by numeric index**. Those indices are assigned by declaration
order up the entity class hierarchy, so adding one field to `Entity` shifts every subclass index
below it. No API, no stability guarantee, breaks every release. Confirmed publicly [C]: ModelEngine
v4 renders bones with item/block display entities, moved there explicitly "to cut down packet
usage".

Note also that `R1`/`R2`/`R3` are **CraftBukkit/Spigot revisions, not Minecraft versions** [C] —
assigned by the Spigot team via BuildTools, bumped when internal mappings change *within* one
Minecraft version (1.16.5 shipped R1, R2 and R3). That is why the 1.21 line alone needs 7 modules.

### 3.2 The mapping history — and why it removed the *packaging* reason, not the *semantic* one

- Pre-1.17: CraftBukkit relocated into `org.bukkit.craftbukkit.v1_16_R3`, NMS into
  `net.minecraft.server.v1_16_R3` [K].
- **1.17 flattened NMS** [C] (June 2021): Mojang's official deobfuscation mappings adopted, so
  `net.minecraft.server.vX_Y_RZ.*` became real unversioned names.
- **Paper changed it again at MC 1.20.5** [C] — announced 2024-03-22 ("Future removal of CB package
  relocation"), reconfirmed in the 1.20.6 post. Paper ships a **Mojang-mapped runtime** and
  **stopped relocating CraftBukkit** (`org.bukkit.craftbukkit.CraftPlayer`, unversioned). **Spigot
  still relocates.** You can no longer parse the server version out of a package name.
- **paperweight-userdev is Mojang-only for this pin** [C]: `REOBF_PRODUCTION` worked
  1.20.5 – ~1.21.11, but Paper dropped remapping plugins to Spigot runtime mappings entirely as of
  MC 26.1. Sexidium is pinned at 26.1.2, so **even the dual-mapping argument for a module tree is
  gone.**

Per-version modules are still needed for *semantic* drift — packet record shapes, data-accessor
indices, registry internals. ModelEngine's split survives the mapping change unharmed. Sexidium
does not touch any of that.

### 3.3 What `api-version` really does

`plugin.yml:4-14` documents the team's model verbatim: a **floor**, not a requirement — declaring
an older version silently opts into legacy behaviour for every API break since, while declaring a
**newer** one than the running server is fatal.

Publicly documented behaviour is exactly two binary facts [C]: omitting it loads the plugin as
*legacy* (Legacy Material Support + console warning), and a server older than the declared version
refuses to load the plugin. Minor-version granularity exists from 1.20.5; the valid range is
currently `1.13` – `26.2`.

> **Correction worth carrying into the docs:** the `ApiVersion.ABSTRACT_CUBE_MOB` shim that
> `plugin.yml` cites is **not in `paper-api-26.1.2`** — the jar carries only `apiVersioning.json`
> and `org.bukkit.MinecraftExperimental` (a *datapack feature-flag* annotation, unrelated) [V]. The
> class is a **paper-server internal**, and the comment is credible team observation of
> implementation behaviour, not documented API. Cite it as such.

Either way, the load-bearing fact stands: **`api-version` is Paper doing, for free, the "one jar
across N Minecraft versions" job that ModelEngine's per-version modules do by hand — for the API
surface.** It does nothing for internals. Sexidium lives entirely on the API surface.

### 3.4 What breaks across MC updates, ranked — and Sexidium's exposure

| # | Surface | Breaks | Exposure here |
|---|---|---|---|
| 1 | Raw NMS / CraftBukkit internals | every release | **Once.** `PaperHardcoreView`, ~250 lines, cosmetic |
| 2 | Packet classes & `SynchedEntityData` indices | every protocol bump | **None** [V] |
| 3 | **`pack_format` / font / atlas / shader schema** | every release | **Heavy — the dominant risk** |
| 4 | Registries data-driven; enum→interface migrations | 1.19.3, 1.20.5, 1.21.3 | Partial, already modernised |
| 5 | Adventure / item data components | occasional | Minimal, guarded |
| 6 | Inventory/menu API, `ItemMeta` | occasional | Low |
| 7 | Deprecate-and-remove cadence | continuous | Managed via `api-version` |

### 3.5 Repo audit — the numbers that decide it [V]

- 849 Java files. `packages/core/src/main`: **83,504 LOC, zero `import org.bukkit.*`**. The
  boundary is real, and it is enforced *for free* — core's `build.gradle.kts` declares no platform
  dependency, so a stray Bukkit import simply does not compile.
- `packages/module-paper/src/main`: **64 files, 13,656 LOC** — the entire Minecraft-coupled
  surface, 14% of the codebase.
- Files naming any `net.minecraft.*` type: **1** — `PaperHardcoreView.java:43`, and it is a
  `String` constant, not an import.
- Files naming any `org.bukkit.craftbukkit.*` type: **0**.
- Reflective call sites: ~45, of which **~7 are Minecraft-internal**. The rest exist because
  Sexidium declines to hard-link soft-depend plugins (Multiverse, Floodgate, SkinsRestorer,
  BetterHud).
- `paper-api-26.1.2` itself: 2286 classes, **no `net.minecraft.*`, no `org.bukkit.craftbukkit.*`**
  — there is no compile-time NMS surface to be tempted by.

**The one NMS site already beat the module approach.** `PaperHardcoreView` re-sends
`ClientboundLoginPacket` for hardcore hearts. It fills the packet **by record-component *name*, not
position** (`:132-159`), and an unrecognised name **aborts the send** rather than guessing
(`:154-157`). Its javadoc records the real event: *"MC 26.2 added `onlineMode` between
`commonPlayerSpawnInfo` and `enforcesSecureChat`, which stopped every hardcore toggle until the
case below was added."* The fix was **one `case` line**. A per-version module would have had to be
written *before* 26.2 shipped; the name-keyed fill degraded safely on an unknown version and needed
three lines. `giveUp()` (`:247-254`) then logs once, sets `supported = FALSE` permanently, and
states the blast radius: the heart texture.

**The version axis that actually bites is data, not code.** `PackFormats.java` is the proof:

```java
return switch (majorMinor(version)) {
  case "26.1" -> 84;
  case "26.2" -> 88;
  default -> -1;      // "no opinion" — never "refuse"
};
```

A table, not a module. And **F62** (`docs/reference/known-issues.md:26,34-46`) is the case study: on
MC 26.2, BetterHud's `PackOverlay` declares `betterhud_26_1 → formats 84..99`, but 26.2 renamed the
core text shaders `rendertype_text.*` → `text.*` and split them into `IS_GUI`/`IS_SEE_THROUGH`
variants — so serving the 26.1 overlay to a 26.2 client corrupts *vanilla* GUI rendering.
`BetterHudLink` caps at **87** while `PackFormats` knows 26.2 is **88**, deliberately narrower than
BetterHud's own declared range, *because the declared range lies*. **No `v26_2/NMSHandler` would
have helped.** The fix was a capability probe plus a version allowlist.

**Recommendation for axis 2:** never build `v*` modules. Zero NMS call sites justify them, Paper's
mappings removed the packaging reason, `api-version` covers the API surface, and the repo's real
risk is a pack-format table that per-version *code* cannot address.

---

## 4. The pattern that IS warranted — and already half-exists

The fourth ModelEngine idea — **runtime-selected implementations with graceful degradation** — is
the one worth having, and this repo already ships a better version of it than most multi-module
plugins:

- **`HudDriverStack`** (`core/game/hud/surface/`) — `platform.supports(spec) ? platform.open(spec)
  : NOOP`, composed with a sidebar fallback. `HudDriver.capabilities()` carries the doctrine in its
  javadoc: *"must describe what this driver can draw right now — not what the underlying plugin
  advertises."*
- **`BetterHudLink`** — a three-gate probe (operator switch → `Class.forName(…, false, cl)`
  catching `LinkageError` → pack-format window), re-evaluated per call because a plugin can enable
  late. It reports **zero capabilities** when BetterHud is installed-but-mismatched, so the sidebar
  takes over instead of players getting white boxes. This distinction — *installed* vs *capable* —
  is the failure mode that actually bites, and it is already handled.
- **`ServerAdapter`** — 74 SPI files, optional capabilities defaulting to `NOOP`
  (`menus()/npcs()/decor()/rankTags()/inventorySerializer()`), values crossing as immutable records.
- **`NodeCapability`** — capability-not-role for deployment topology.

**The gap:** only HUD has been generalised into a *stack*. NPCs (FancyNpcs), holograms
(FancyHolograms), Bedrock forms (Floodgate/Cumulus), Multiverse and SkinsRestorer are each
hand-gated at their own call site, with `MultiverseBridge` and `PaperLobbyBootstrap:365-550` being
two independent reflective MV v4/v5 bridges with near-identical logic.

> **This is the whole recommendation: generalise the registry, do not add modules.** A registry
> needs an interface, a stack, and honest `capabilities()` — not a Gradle module. `HudDriverStack`
> proves it in this codebase already.

### 4.1 Proposed shape

```
core/platform/version/ServerVersion.java         // record(major, minor, patch, raw) + atLeast(), UNKNOWN
core/platform/version/ServerVersionPort.java     // ServerVersion version(); int packFormat();
core/platform/capability/Capability.java         // ITEM_MODEL_COMPONENT, HARDCORE_VIEW_PACKET,
                                                 // DIMENSION_STORAGE_KEYED, FOLIA_REGION_SCHEDULER,
                                                 // BEDROCK_FORMS, SKIN_LOOKUP_OFFLINE, HUD_OVERLAY, …
core/platform/capability/CapabilityRegistry.java // Set<Capability> supported(); boolean has(c);
                                                 // Optional<String> reason(c)   ← for the boot log
```

Hang `versions()` / `capabilities()` off `ServerAdapter` beside the existing `health()` /
`serverInfo()`. Implement **one** `PaperCapabilityRegistry` that **probes** — not a `v26_1`/`v26_2`
class pair — because probing survived 26.2 and version-keying is what broke. Version number is the
*tiebreaker* for what cannot be probed (pack formats), never the primary key.

Generalise the probe helper (in `module-paper`; `core` has no Bukkit):

```java
static boolean linkable(String fqcn, ClassLoader cl) {
  try { Class.forName(fqcn, /*initialize*/ false, cl); return true; }
  catch (ClassNotFoundException | LinkageError ignored) { return false; }
}
```

`initialize=false` is load-bearing — it links without running static initialisers. Catching
`LinkageError` *as well as* `ClassNotFoundException` catches "present but incompatible", which
`ClassNotFoundException` alone misses.

**Sites to fold in:** `PaperUiItemFactory:99-107` + `PaperDecorAdapter:245-253` (same guard,
duplicated) · `PaperHardcoreView.supported` · `BetterHudLink.capable()` · `PaperMenuAdapter:95-108`
· `PaperSkullSkins:83-108` + `PaperNpcSkinResolver:86-101` (one probe, two consumers) ·
`MultiverseBridge.tryBind` + `PaperLobbyBootstrap:365-400` (one probe, dedupe) · `FoliaSupport`.

### 4.2 Detecting the running server version — verified against the jar [V]

`paper-api-26.1.2` contains `io.papermc.paper.ServerBuildInfo` with `minecraftVersionId()`,
`brandId()`, `isBrandCompatible(Key)`, `buildNumber()`, `gitCommit()`; and `Bukkit.getMinecraftVersion()`
/ `getBukkitVersion()`. Recommended chain, all behind one try-fallback returning
`ServerVersion.UNKNOWN`:

1. `ServerBuildInfo.buildInfo().minecraftVersionId()` — cleanest; `isBrandCompatible(BRAND_PAPER_ID)`
   also distinguishes Paper from forks far better than `PaperServerAdapter.platformType()`'s current
   string sniffing (`:126-130`).
2. `Bukkit.getMinecraftVersion()` — the current call site (`BetterHudLink:136`); keep as fallback,
   exists on forks where `ServerBuildInfo` may not.
3. `Bukkit.getBukkitVersion().split("-")[0]` — last resort, already used at `:358-359`.

**Preserve the `PackFormats` doctrine** (`:11-14`): an unrecognised version means *"no opinion"*,
not *"refuse"*. Breaking a working server over a parsing detail is a self-inflicted outage.

### 4.3 Degradation rules to codify (all already practised somewhere)

1. **Probe once, remember, log once, state the blast radius.** (`PaperHardcoreView.giveUp`.)
   Exception: probes for *plugins* re-evaluate per call, because plugins enable late.
   (`BetterHudLink.available()`.)
2. **Degrade to a working path, never to an exception.** BetterHud → sidebar; Bedrock form → chest
   GUI; item model → vanilla material. All three already implemented.
3. **If a degraded result is indistinguishable from a legitimate one, expose a capability flag.**
   (`docs/guides/add-a-platform-capability.md:28-31`.)
4. **Never guess a value into a protocol structure.** (`PaperHardcoreView:117-125`.)
5. **Never let a version probe be able to brick boot.** (`PaperWorldControl:645-656` refuses to ship
   a boot-time worldgen datapack, because a format mismatch hard-fails the server *before* plugins
   enable and the plugin cannot self-heal it.)

---

## 5. Staged plan, with triggers

### Stage 0 — do now. No modules added. **This is the actual recommendation.**

| # | Item | Where |
|---|---|---|
| **0a** | 🐛 **Folia contract violation.** `plugin.yml:17-19` declares *"never the legacy BukkitScheduler"*; `PaperAuthHold.java:83` uses `getServer().getScheduler().runTaskTimer(...)`. It is the **sole** such site in module-paper [V]; every other site uses region/entity/async schedulers. On Folia this throws `UnsupportedOperationException` and the auth-hold countdown dies at startup. Fix: `getGlobalRegionScheduler().runAtFixedRate` + change the stored `BukkitTask` to `ScheduledTask`. | `PaperAuthHold.java:83` |
| **0b** | **Generalise `HudDriverStack` into `core/platform/backend/`** (`Backend<T>` / `BackendStack<T>`) and add `ServerVersionPort` + `CapabilityRegistry` (§4.1). **Do not move the HUD classes** — add the general form beside them and have `HudDriverStack` implement it. Then migrate one backend (NPCs is the best candidate) and ship, before doing the rest one commit each. **Highest-value work in this document.** | new `core/platform/{backend,version,capability}` |
| **0c** | **Cross-artifact pack-format consistency test.** Four places carry the same number with four comments telling you to keep them in step and nothing enforcing it: `SexidiumResourcePack.PACK_FORMAT` (84), `PackFormats.of()` (26.1→84, 26.2→88), `BetterHudLink.SUPPORTED_PACK_FORMAT_MIN/MAX` (84/87), `scripts/lib/paper.sh:87` `PAPER_VERSION`. This is exactly the drift `PackFormats.java:6-10` was created to end — and it is only half-solved. | new test in `:packages:core` / `:module-paper` |
| **0d** | **Golden API-surface test.** Adapters import **148 distinct core types, 88 of them outside `platform/`** [V], and nothing stops that growing. A test that scans `import com.sexidium.core.*` and diffs against a checked-in list makes every widening a reviewed one-line diff. ~60 lines. **This is 90% of what a `core-api` module would buy, at 1% of the cost.** | `module-paper` + `module-velocity` tests |
| **0e** | **Login-packet shape check at enable.** Reflect `ClientboundLoginPacket.getRecordComponents()` and diff the names against the `switch` cases in `buildLoginPacket`; log the delta. Converts the next silent 26.x regression into a startup line. There is currently **no test at all** for `PaperHardcoreView` [V]. | `PaperHardcoreView:180-219` |
| **0f** | **Bundle core into the Paper jar through a resolvable configuration**, not `project(":packages:core").extensions.getByType<SourceSetContainer>()["main"].output`. Works today, but it is precisely the cross-project model access Gradle's *isolated projects* forbids — and this build has already lost the configuration-cache fight twice (see `buildSrc/`, which exists solely because of it). Also makes every later stage a one-line `dependencies` edit instead of a jar-task rewrite. | `module-paper/build.gradle.kts` |
| **0g** | **Fix NeoForge doc drift.** `settings.gradle.kts` has 3 projects; the docs carry **107 NeoForge references** across `platform-and-adapters.md` (52), `tech-decisions.md` (37) and `overview.md` (18), including the module tree and the mermaid graph, presenting `packages/module-neoforge` as present. Only `docs/README.md` flags it. **This is evidence, not just a chore:** the repo already ran the "add a speculative module" experiment, wrote 100+ doc references against it, and dropped it from the build. That is the measured cost of module proliferation here. | `docs/architecture/*`, `docs/guides/add-a-platform-capability.md`, `docs/reference/tech-decisions.md` |
| **0h** | Smaller: dedupe the two Multiverse reflective bridges; widen `FoliaSupport:21` to catch `LinkageError`; `platformType()` via `ServerBuildInfo.isBrandCompatible` instead of name substrings. | as listed |

> **Correction to an earlier draft finding:** `PaperDecorAdapter.java:249` `setItemModel` was
> reported as unguarded. It is **not** — it carries the identical `try/catch (Throwable)` as
> `PaperUiItemFactory:99-107` [V]. The real issue there is duplication, folded into 0b.

### The concrete first commit

**`♻️ Bundle core into the Paper jar through a configuration, not the source set`** (0f)

```kotlin
val bundled: Configuration by configurations.creating {
  isCanBeConsumed = false; isCanBeResolved = true
}
dependencies { bundled(project(":packages:core")) }

tasks.jar {
  // provider {}, NOT bundled.files — eager resolution at configuration time is exactly
  // what broke generateBuildInfo/prepareLobbyBundle before buildSrc existed.
  from(provider { bundled.map { zipTree(it) } })
  duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

Verify: `./gradlew clean build` twice (the second must report *Reusing configuration cache*), then
`unzip -l build/libs/paper/Sexidium-Paper-1.0.0.jar | wc -l` unchanged, with the three
`bundled/maps/tntwar/*.zip` present. Blast radius: zero outside that file. Revertible in one line.

### Stages A–D — conditional, none fired today

| Stage | What | Trigger | Status |
|---|---|---|---|
| **A** | Extract `packages/core-api` | A **second server adapter** is in `settings.gradle.kts` **and has survived one release**; or someone outside this repo compiles against `com.sexidium.core`. | ❌ not fired |
| **C** | `packages/compat-java<N>` | A supported runtime is on a **lower** JVM major than the toolchain **and** a real call site diverges. | ❌ not fired (and BetterHud pushes the floor *up*) |
| **D** | `packages/compat-mc<version>` | The **first** call site `paper-api` cannot express that **differs between two supported MC versions**. | ❌ not fired (0 NMS call sites) |

**Stage A carries a prerequisite that is the real work**, and it is why the split is ceremony today:
`SexidiumCore` exposes ~40 accessors returning **concrete impl types**, and `AbstractWorldControl`
(**2,559 LOC**) is extended by `PaperWorldControl` (1,426 LOC) — template-method inheritance
*across the seam*. Splitting today would either drag ~90 impl classes into "api" (making the split
meaningless) or require inverting all of that first. **The module is the trivial part.**

**Stage D's rule, if it ever fires:** the module count equals the number of versions where an
internals call site *actually diverges* — never the number of versions supported.

---

## 6. Blast radius — what any module addition perturbs

| Surface | Risk | Mitigation |
|---|---|---|
| **Test fork memory** ⚠️ | Root `build.gradle.kts` sizes `maxParallelForks.coerceIn(1,4)` × `maxHeapSize=1g` + 2g daemon = **exactly** the `init` container's 6g `mem_limit`. **Each new module gets its own fork pool, and `org.gradle.parallel=true` runs them concurrently.** 2–3 modules can push past 6g → OOM-kill → SIGKILL → **a deploy that fails with no test report at all**. The single most under-appreciated cost here. | Re-derive the fork budget *before* adding any module; lower the ceiling or raise `mem_limit`. |
| **Configuration cache** | `buildSrc/` exists *solely* because this build already failed with `cannot serialize Gradle script object references`. Every new task action must not capture `project`, `file()`, `copy{}`, or a script-level `val`. | Run `./gradlew build` twice per commit; require *Reusing configuration cache*. |
| **Jar assembly** | `module-paper`'s `tasks.jar` hardcodes `["main"]`. Add a source set and its classes land in `Sexidium-Core.jar` but **not** `Sexidium-Paper.jar` → `Class.forName` fails → the gate catches it → **the fallback runs forever on the right JVM, silently**. | 0f fixes this structurally; otherwise add every source set explicitly. |
| **Deploy pipeline** | The jar name/path is hardcoded in **≥8 places** (`scripts/lib/paper.sh:74`, `store.sh:57-58`, `velocity.sh:406,414`, `net.sh:269`, `remote/checks.py:226`) plus two **golden traces**. The build store keys builds by **sha256 of the jar**. | **Never change `archiveBaseName` or the `build/libs/{paper,velocity}/` layout.** Run `scripts/test` on every build-affecting commit. |
| **JaCoCo** | Applied only to `:packages:core`. Splitting core yields N unrelated reports and **no aggregate** — coverage regresses as a *number*, not a failure. | Any core split must land with a root `JacocoReport` aggregation task in the same commit. |
| **Toolchain availability** | `settings.gradle.kts` registers **no toolchain resolver**, and `eclipse-temurin:25-jdk` ships only JDK 25. A `javaLauncher { 21 }` test task fails the build in-container with *"No matching toolchain … auto-provisioning is not enabled"*. | Guard with `onlyIf`, or add the foojay resolver (which adds a network round-trip the root build deliberately prunes elsewhere). |
| **Docs** | Every page carries a *"Keeping this current"* footer naming its tracked files; the convention requires updating **in the same change**. | Treat the doc edit as part of the commit. The NeoForge drift is what happens otherwise. |

---

## 7. Testing across versions

`PAPER_VERSION` is already env-overridable (`scripts/lib/paper.sh:87`), a change deletes and
re-downloads every jar (`papermc.sh:16`), and a pinned RC (`26.3-rc-1`) resolves. So a matrix is one
variable — **with one trap**: `mcserver.sh:54-65` quarantines worlds on downgrade (worlds upgrade in
place and never downgrade), so a naive loop over one `SERVER_DIR` is destructive. **Use a distinct
`SERVER_DIR` per version:**

```bash
for v in 26.1.2 26.2; do
  SERVER_DIR=test/paper-$v PAPER_VERSION=$v INSTALL_WORLDEDIT=0 INSTALL_AXIOM=0 scripts/init-paper.sh
done
```

Assert: `Done (`, Sexidium enabled, no `giveUp`/`warnOnce` warnings. Add **`/sx admin capabilities`**
dumping `CapabilityRegistry` + `ServerVersion` + `ServerBuildInfo` — that turns "does it work on
26.3" from an eyeball into a one-line grep. Unit-test `PackFormats` boundaries (83/84/87/88/`null`/
garbage); it is pure and needs no server.

---

## 8. The case for doing nothing (it is strong)

1. **Three of ModelEngine's four package kinds would be empty scaffolding here** — zero NMS call
   sites, zero Java-25-only API usage, no external API consumers.
2. **The seam that matters exists and is compile-enforced for free.** Core declares no platform
   dependency; violations do not compile. A module boundary adds ceremony, not enforcement.
3. **The registry exists and is more sophisticated than most** — it distinguishes *installed* from
   *capable*, which is the failure mode that actually bites.
4. **The repo already ran this experiment** (`module-neoforge`) and the residue is 107 misleading
   doc references.
5. **The build is at a delicate equilibrium**: configuration cache + `buildSrc` + fork pools sized
   exactly to a 6g container + a sha256-keyed build store + golden deploy traces. Every module
   perturbs all five, and the comments in `build.gradle.kts` read like a field report from someone
   who already paid for it.
6. **Splitting would not make the hard things smaller.** `AbstractWorldControl` (2,559 LOC) and
   `SexidiumCore` (~40 accessors) are what make this codebase hard to move — not the module count.
7. **A module built before its second implementation exists gets shaped by the one it has**, and
   then does not fit the second when it arrives.

**If the goal is only that the jar *look* like ModelEngine's, it already does** — modulo naming:

```
com/ticxo/modelengine/api      →  com/sexidium/core/platform{,/model,/hud,/noop}   (74 files)
com/ticxo/modelengine/core     →  com/sexidium/core/{game,world,menu,lobby,…}      (396 files)
com/ticxo/modelengine/v1_21_R5 →  com/sexidium/paper                               (one, not twenty)
com/ticxo/modelengine/core25   →  does not exist, and should not
```

---

## 9. The five questions to ask before copying any plugin's layout

1. **Do you compile against unstable internals?** (NMS, obfuscated names, `getHandle()`.)
   *No → no `v*` modules, ever.*
2. **Does anyone outside your repo compile against your types?** *No → no separate `api` artifact.*
   An internal boundary is a package plus a test, not a Maven coordinate.
3. **Must one artifact link on ≥2 JVM majors with divergent APIs you actually call?**
   *No → no `coreNN`.*
4. **Is the implementation swappable at runtime for a reason a user can observe?**
   *Yes → a registry with honest capability reporting.*
5. **Would the duplicated modules have tests?** If not, the layout converts a compile error into a
   runtime error at the worst possible moment — the day everyone upgrades.

**Sexidium answers no to 1, 2 and 3, and already yes to 4.**

---

## 10. Open uncertainties

- **ModelEngine's module list and packet mechanism** have no citable public source (closed core).
  The folder list is your own observation of the jar; the `SynchedEntityData`-index rationale is
  inference, though it is the standard reason for this pattern.
- **The `1.20 → R1..R4` revision mapping** is approximate from memory. The *mechanism* (Spigot
  revisions, bumped within one MC version) is confirmed; the specific table is not.
- **The `ApiVersion` per-feature-shim model** rests solely on this repo's own `plugin.yml` comment;
  the class is absent from `paper-api` and the behaviour is not publicly documented (§3.3).
- **`core21`/`core25` being a JVM split rather than something else** is inference from the size
  asymmetry (559 KB with a `mythic/` subpackage vs a single 1.1 KB class) plus the naming.

---

## 11. One-paragraph summary

The `core21`/`core25` idiom is legitimate and well-understood: `UnsupportedClassVersionError` fires
at class-*derivation* time, so JVM-gated bytecode must live in a class older JVMs never attempt to
load, reached only through `Class.forName` + `catch (ClassNotFoundException | LinkageError)`.
Sexidium already writes that idiom correctly three times over — but has nothing to gate:
`paper-api:26.1.2` is itself class-file major 69, so the oldest supported server already mandates
Java 25, and no code here uses a Java-25-only API. The `v1_20_R4`-style tree is even less
applicable: this plugin has **one** NMS call site (250 lines, cosmetic, behind a self-disabling
name-keyed probe that already survived the 26.2 packet change with a three-line fix), Paper's move
to unversioned CraftBukkit and a Mojang-mapped runtime at 1.20.5 removed the packaging reason, and
`api-version` gives the one-jar-across-versions property for free. The repo's real version risk is
resource-pack formats — a **data table**, which no amount of per-version *code* modules would fix.
So: adopt the registry, not the tree. Generalise `HudDriverStack` + `BetterHudLink` into a
`CapabilityRegistry` and `ServerVersionPort` (a few hundred lines in `core`), fix the one Folia
scheduler violation, enforce the four pack-format constants with a test, and leave the module count
at three until a trigger in §5 actually fires.
