# Base prompt: adding a platform capability (SPI seam)

You are extending Sexidium's **platform SPI** — the seam that keeps all gameplay logic in
`packages/core` while the Paper adapter (and, for proxy-side concerns, Velocity) supplies thin
implementations. This is the single most repeated workflow in
the repo: almost every feature adds one or two seams. Reference:
[platform-and-adapters.md](../architecture/platform-and-adapters.md).

## Key files

| Concern | File(s) |
|---|---|
| SPI interfaces | `packages/core/src/main/java/com/sexidium/core/platform/` — `ServerAdapter`, `PlayerAdapter`, `WorldAdapter`, `SchedulerAdapter`, `UiAdapter`, `MenuAdapter`, `ConfigurationAdapter`, handle types (`ItemEntityHandle`, `MobHandle`, `BossBarHandle`, `HudPanelHandle`) … |
| Value model | `…/core/platform/model/` — records/enums only (`WorldPosition`, `BlockPosition`, `ItemKey`, `ItemStackData`, `WorldDimension`, `GameModeType`, …) |
| Runtime capability vocabulary | `…/core/platform/capability/` — `Capability` + `CapabilityRegistry` (what the running server can do RIGHT NOW, with a reason for every miss) and `…/core/platform/version/` (`ServerVersion`, `ServerVersionPort`) |
| Paper implementations | `packages/module-paper/src/main/java/com/sexidium/paper/adapter/**` (+ `…/adapter/util/PaperConverters.java` for core↔Bukkit conversion; `PaperCapabilityRegistry` probes each capability at boot) |
| Parity ledger | [known-issues.md](../reference/known-issues.md) + the SPI → adapter implementation map in [platform-and-adapters.md](../architecture/platform-and-adapters.md) (§1.8). The old Paper/NeoForge parity table is gone with the module it compared against — what a capability actually resolves to at runtime is now read from `/sx admin capabilities`, not from a table. |

## The pattern

1. **Declare the capability as a `default` method** on the narrowest fitting interface, with a safe
   degraded behaviour (no-op, empty list, `null`, `false`). Core code must behave sensibly when the
   platform can't do it. Write real Javadoc: what it does, who calls it, what the default means.
2. **Implement it on Paper** in the matching `Paper*Adapter`, converting through `PaperConverters`.
   Never leak a Bukkit type into a signature — extend the value model in `platform/model/` instead
   (records, no behaviour beyond validation/parsing).
3. **Probe, don't version-key.** If availability varies between servers, add a `Capability` constant,
   probe it honestly in `PaperCapabilityRegistry.probe(...)` (a missing API or an installed-but-
   incompatible plugin gets a human-readable REASON), and let callers degrade through their fallback.
   `/sx admin capabilities` prints the result — check it after adding one.
4. **Ambiguity guard**: if "the degraded result" is indistinguishable from a legitimate result (an empty
   loot list could mean *drops nothing* or *cannot tell*), add a **capability flag** the caller checks —
   `WorldAdapter.resolvesBlockLoot()` is the model. Same idea for handles:
   `ItemEntityHandle.setVelocity` returns `false` when unsupported so callers can fall back immediately.
5. **Test with POJO fakes**, never mocks of Bukkit: implement the interface directly in the test with
   just the members the logic reads. Models: `SafeSpawnTest.FakeWorld` (block map),
   `StackMergePacingTest.FakeWorld`/`FakeItem` (pushable items), `ExperienceRespawnTest.FakePlayer`.
   Adding an abstract method breaks every fake — prefer `default` methods; go abstract only when every
   platform genuinely must answer.

## Rules

- Core never imports `org.bukkit.*` / Minecraft classes — the build treats this as
  architecture, not style.
- One seam per concept; do not overload an existing method with a mode flag when a second well-named
  default reads better (`dropItem(pos, stack, scatter)` was the exception, justified in its Javadoc).
- Deterministic contracts: document units (ticks, blocks), coordinate conventions, and whether a method
  may load chunks or must not (chunk-loading on the caller's thread is a recurring lag source — see
  `PaperWorldAdapter.nearbyItems` for the loaded-chunks-only pattern).
- If the capability composes across dimensions or worlds, put the composition in the **default method**
  (e.g. `setKeepInventoryEverywhere` walks `dimension(...)`) so platforms only implement the primitive.

## Checklist

- [ ] Default method with degraded behaviour + honest Javadoc
- [ ] Paper override + `PaperConverters` additions if new model types
- [ ] Availability varies? → `Capability` constant + honest probe in `PaperCapabilityRegistry`
      (**or** the gap recorded)
- [ ] Capability flag if empty/`null` is ambiguous
- [ ] POJO-fake unit test exercising the core logic through the seam
- [ ] [platform-and-adapters.md](../architecture/platform-and-adapters.md) updated in the same change

---
*Keeping this current: tracks the `platform/` SPI package, `platform/model/`, `PaperConverters` and the
adapter modules. Update it in the same change that changes the seam-adding workflow.*
