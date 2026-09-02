package com.sexidium.core.platform.capability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityRegistryTest {

  /** Answers every constant supported except the ones named, so a test can vary one thing at a time. */
  private static CapabilityRegistry.Probe allSupportedExcept(Capability... missing) {
    CapabilityRegistry.Probe probe = CapabilityRegistry.probing();
    for (Capability capability : Capability.values()) {
      probe.supports(capability);
    }
    for (Capability capability : missing) {
      probe.lacks(capability, "not here");
    }
    return probe;
  }

  @Test
  @DisplayName("a complete probe supports what it answered yes to and nothing else")
  void completeProbeSupportsWhatItAnswered() {
    CapabilityRegistry registry = allSupportedExcept(Capability.BEDROCK_FORMS, Capability.HUD_OVERLAY)
        .build();

    assertTrue(registry.has(Capability.ITEM_MODEL_COMPONENT));
    assertTrue(registry.has(Capability.LOBBY_NPCS));
    assertFalse(registry.has(Capability.BEDROCK_FORMS));
    assertEquals(Capability.values().length - 2, registry.supported().size());
  }

  @Test
  @DisplayName("an UNPROBED capability is unsupported, not supported — the whole point of the builder")
  void unprobedFailsClosed() {
    // Everything answered EXCEPT one, standing in for a Capability constant added to the enum whose
    // probe nobody wrote. The old map-of-misses shape derived support by subtraction and reported this
    // as available on every server, with no reason and no log line, sending callers down the capable
    // path into a NoSuchMethodError.
    CapabilityRegistry.Probe probe = CapabilityRegistry.probing();
    for (Capability capability : Capability.values()) {
      if (capability != Capability.HARDCORE_VIEW_PACKET) {
        probe.supports(capability);
      }
    }
    CapabilityRegistry registry = probe.build();

    assertFalse(registry.has(Capability.HARDCORE_VIEW_PACKET),
        "a capability nobody probed must answer NO");
    assertEquals(Optional.of(CapabilityRegistry.UNPROBED_REASON),
        registry.reason(Capability.HARDCORE_VIEW_PACKET),
        "and it must say so, loudly enough to read in a boot log");
    assertEquals(Capability.values().length - 1, registry.supported().size());
  }

  @Test
  @DisplayName("an empty probe supports nothing at all")
  void emptyProbeSupportsNothing() {
    CapabilityRegistry registry = CapabilityRegistry.probing().build();

    assertTrue(registry.supported().isEmpty());
    for (Capability capability : Capability.values()) {
      assertEquals(Optional.of(CapabilityRegistry.UNPROBED_REASON), registry.reason(capability));
    }
  }

  @Test
  @DisplayName("reason() explains misses and stays empty for hits")
  void reasonsCarryTheWhy() {
    CapabilityRegistry registry = allSupportedExcept()
        .lacks(Capability.SKIN_LOOKUP_OFFLINE, "SkinsRestorer absent")
        .build();

    assertEquals(Optional.of("SkinsRestorer absent"), registry.reason(Capability.SKIN_LOOKUP_OFFLINE));
    assertEquals(Optional.empty(), registry.reason(Capability.HUD_OVERLAY),
        "a supported capability has no reason");
    assertEquals(Optional.empty(), registry.reason(null));
  }

  @Test
  @DisplayName("answer() takes a probe's Optional straight through, either way")
  void answerAcceptsAProbeVerdict() {
    CapabilityRegistry registry = allSupportedExcept()
        .answer(Capability.HUD_OVERLAY, Optional.of("shaders mismatch"))
        .answer(Capability.BEDROCK_FORMS, Optional.empty())
        .answer(null, Optional.of("ignored"))
        .build();

    assertFalse(registry.has(Capability.HUD_OVERLAY));
    assertTrue(registry.has(Capability.BEDROCK_FORMS));
  }

  @Test
  @DisplayName("a blank reason is not a reason: it reads as supported rather than as a silent miss")
  void blankReasonMeansSupported() {
    CapabilityRegistry registry = allSupportedExcept()
        .answer(Capability.HUD_OVERLAY, Optional.of("   "))
        .build();

    assertTrue(registry.has(Capability.HUD_OVERLAY));
  }

  @Test
  @DisplayName("EMPTY supports nothing and null answers false without throwing")
  void emptyAndNullAreFailClosed() {
    assertFalse(CapabilityRegistry.EMPTY.has(Capability.HUD_OVERLAY));
    assertFalse(CapabilityRegistry.EMPTY.has(null));
    assertFalse(CapabilityRegistry.EMPTY.supported().contains(Capability.HUD_OVERLAY));
    assertEquals(Optional.empty(), CapabilityRegistry.EMPTY.reason(Capability.HUD_OVERLAY));
  }

  @Test
  @DisplayName("the built registry is immutable")
  void supportedSetIsImmutable() {
    CapabilityRegistry registry = allSupportedExcept().build();
    assertThrows(UnsupportedOperationException.class,
        () -> registry.supported().add(Capability.HUD_OVERLAY));
  }
}
