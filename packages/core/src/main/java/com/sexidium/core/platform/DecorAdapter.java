package com.sexidium.core.platform;

import com.sexidium.core.decor.DecorProp;

/**
 * Spawns and manages in-world <b>decor</b> — native display entities ({@code ItemDisplay}/
 * {@code BlockDisplay}/{@code TextDisplay}/{@code Interaction}) that dress the lobby (an animated hub
 * centerpiece, NPC podiums, glowing pedestals). Mirrors {@link NpcAdapter}: the default is inert so the
 * core works without a decor backend, and NeoForge / headless tests inherit the no-op (decor is
 * additive Java flair, never a cross-play requirement — see {@code docs/reference/tech-decisions.md}
 * §3.4: item/block displays are invisible on Bedrock, so this layer must degrade gracefully).
 */
public interface DecorAdapter {
  DecorAdapter NOOP = new DecorAdapter() {
    @Override
    public void spawn(DecorProp prop) {
    }

    @Override
    public void despawn(String propId) {
    }

    @Override
    public void despawnAll() {
    }
  };

  /** Spawns (or respawns) the prop and starts its client-side interpolated animation. */
  void spawn(DecorProp prop);

  /** Removes the prop's backing display entity (ghost-safe), if present. */
  void despawn(String propId);

  /** Removes every tracked prop and sweeps any stray decor entities (disable / reload / world-unload). */
  void despawnAll();

  /**
   * Re-issues the next animation target for an already-spawned prop. Optional: the adapter MAY self-tween
   * on its own slow cadence and leave this unused. No-op by default / for an unknown prop id.
   */
  default void retarget(String propId) {
  }
}
