package com.sexidium.core.platform;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BedrockPlayersTest {
  @Test
  void floodgateStyleUuid_withZeroHighBits_isBedrock() {
    // Floodgate assigns Bedrock players a UUID of the form 00000000-0000-0000-xxxx-xxxxxxxxxxxx.
    assertTrue(BedrockPlayers.isBedrockUuid(new UUID(0L, 1234567890L)));
  }

  @Test
  void javaStyleUuid_isNotBedrock() {
    assertFalse(BedrockPlayers.isBedrockUuid(UUID.fromString("11111111-2222-3333-4444-555555555555")));
    assertFalse(BedrockPlayers.isBedrockUuid(UUID.randomUUID()));
  }

  @Test
  void nullUuid_isNotBedrock() {
    assertFalse(BedrockPlayers.isBedrockUuid(null));
  }
}
