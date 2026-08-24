package com.sexidium.core.platform.model;

/**
 * A single captured potion effect, mirroring the arguments of
 * {@link com.sexidium.core.platform.PlayerAdapter#addEffect(String, int, int)} so a captured effect
 * round-trips straight back onto the player. {@code effectKey} is a vanilla effect id such as
 * {@code speed} (the namespace, if any, is stripped on apply).
 */
public record EffectSnapshot(String effectKey, int amplifier, int durationTicks) {
}
