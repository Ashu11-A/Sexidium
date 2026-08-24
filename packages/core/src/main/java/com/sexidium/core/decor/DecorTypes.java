package com.sexidium.core.decor;

import java.util.List;

/**
 * The small value types of the decor subsystem (kind, billboard mode, animation), grouped in one place
 * (formerly one file each). Each keeps its original simple name, so a call site only switches its import
 * to {@code com.sexidium.core.decor.DecorTypes.<Name>}.
 */
public final class DecorTypes {
  private DecorTypes() {
  }

  /**
   * The kind of in-world display entity a {@link DecorProp} renders as. These map 1:1 onto the native
   * Minecraft display/interaction entities (MC 1.19.4+): {@code ITEM_DISPLAY} carries an item (optionally
   * wearing a custom {@code item_model}), {@code BLOCK_DISPLAY} carries a block state, {@code TEXT_DISPLAY}
   * carries MiniMessage text, and {@code INTERACTION} is a non-visual clickable hitbox.
   */
  public enum DecorKind {
    ITEM_DISPLAY,
    BLOCK_DISPLAY,
    TEXT_DISPLAY,
    INTERACTION
  }

  /**
   * How a decor display entity orients itself toward the viewer. The constant names match
   * {@code org.bukkit.entity.Display.Billboard} exactly so the Paper adapter can map by {@code valueOf}
   * with no translation table.
   */
  public enum DecorBillboard {
    FIXED,
    VERTICAL,
    HORIZONTAL,
    CENTER
  }

  /**
   * The (optional) animation a {@link DecorProp} plays. All motion runs as <b>client-side
   * interpolation</b> — the Paper adapter advances a coarse target a couple of times a second and the
   * client tweens between frames — so an animated prop costs no per-tick server work. {@code spin}
   * rotates the prop around its vertical axis; {@code bob} floats it up and down on a sine; and
   * {@code cmdCycleModelIds} swaps the displayed item's {@code item_model} through a list on a slow
   * cadence — never per tick.
   */
  public record DecorAnimation(
      boolean spin,
      double spinDegreesPerSecond,
      boolean bob,
      double bobAmplitude,
      double bobPeriodSeconds,
      List<String> cmdCycleModelIds,
      double cmdCycleSeconds) {

    /** A static prop: no motion at all. */
    public static final DecorAnimation NONE =
        new DecorAnimation(false, 0.0D, false, 0.0D, 0.0D, List.of(), 0.0D);

    public DecorAnimation {
      cmdCycleModelIds = cmdCycleModelIds == null ? List.of() : List.copyOf(cmdCycleModelIds);
    }

    /** Whether this prop animates at all (any of spin / bob / model-cycle). */
    public boolean animated() {
      return spin || bob || !cmdCycleModelIds.isEmpty();
    }

    /** A prop that only spins around its vertical axis. */
    public static DecorAnimation spin(double degreesPerSecond) {
      return new DecorAnimation(true, degreesPerSecond, false, 0.0D, 0.0D, List.of(), 0.0D);
    }

    /** A prop that spins and gently bobs up and down. */
    public static DecorAnimation spinAndBob(double degreesPerSecond, double amplitude, double periodSeconds) {
      return new DecorAnimation(true, degreesPerSecond, true, amplitude, periodSeconds, List.of(), 0.0D);
    }
  }
}
