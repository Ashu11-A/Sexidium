package com.sexidium.core.platform;

import java.util.UUID;

/**
 * Detects players connected from Bedrock Edition (mobile/console via Geyser + Floodgate).
 *
 * <p>Floodgate assigns every Bedrock player a synthetic UUID derived purely from their Xbox XUID, so
 * the most-significant 64 bits are always zero (the form {@code 00000000-0000-0000-xxxx-xxxxxxxxxxxx}).
 * Java players always have a random/online UUID with non-zero high bits. Checking the high bits is the
 * standard, dependency-free way to recognise a Bedrock client and needs no Floodgate API on the
 * classpath, so it works on every platform and in headless tests.</p>
 *
 * <p>This is a UX hint only (e.g. to route command suggestions to a GUI affordance for clients that
 * cannot easily type chat commands); it is never used for an authorization or security decision.</p>
 */
public final class BedrockPlayers {
  private BedrockPlayers() {
  }

  public static boolean isBedrockUuid(UUID uuid) {
    return uuid != null && uuid.getMostSignificantBits() == 0L;
  }
}
