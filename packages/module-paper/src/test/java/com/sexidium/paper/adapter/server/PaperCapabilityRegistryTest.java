package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.capability.Capability;
import com.sexidium.core.platform.capability.CapabilityRegistry;
import com.sexidium.paper.adapter.npc.PaperNpcBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completeness guard for Paper's capability probe.
 *
 * <p>{@link CapabilityRegistry.Probe} already fails an unprobed capability CLOSED at runtime, which is
 * the safe behaviour — but "safe" there still means a capability silently reported unavailable on
 * every server because somebody added an enum constant and forgot the probe. This test turns that into
 * a failing build instead: the probe table must answer every constant, by name.</p>
 *
 * <p>It runs in a bare JVM with no Bukkit server and no soft-depend plugins, which is the harshest
 * case rather than an unrepresentative one — every probe has to answer there without throwing.</p>
 */
class PaperCapabilityRegistryTest {

  private static PaperNpcBackend backend() {
    return new PaperNpcBackend(null);
  }

  @Test
  @DisplayName("the probe table covers every Capability constant — no constant may be forgotten")
  void everyCapabilityHasAProbe() {
    Set<Capability> probed = PaperCapabilityRegistry.probes(() -> false, backend()).keySet();

    assertEquals(EnumSet.allOf(Capability.class), EnumSet.copyOf(probed),
        "a Capability constant with no entry in PaperCapabilityRegistry.probes() is reported"
            + " unavailable on every server, with UNPROBED_REASON, until somebody notices in a log."
            + " Add its probe here in the same commit as the constant.");
  }

  @Test
  @DisplayName("probing a bare JVM answers every capability without throwing, and never with UNPROBED")
  void probeAnswersEverythingHeadless() {
    CapabilityRegistry registry = PaperCapabilityRegistry.probe(() -> false, backend());

    for (Capability capability : Capability.values()) {
      Optional<String> reason = registry.reason(capability);
      assertNotEquals(Optional.of(CapabilityRegistry.UNPROBED_REASON), reason,
          capability + " was never probed");
      if (!registry.has(capability)) {
        assertTrue(reason.isPresent() && !reason.get().isBlank(),
            capability + " is unavailable but carries no reason for the operator");
      }
    }
  }

  @Test
  @DisplayName("with no server and no plugins, nothing is claimed as available")
  void headlessClaimsNothing() {
    CapabilityRegistry registry = PaperCapabilityRegistry.probe(() -> false, backend());

    // The fail-open bug this guards: HARDCORE_VIEW_PACKET used to be derived from the login-packet
    // SHAPE report alone, which is empty on a JVM with no net.minecraft classes — so the capability
    // read as supported on exactly the servers where it provably cannot work.
    assertFalse(registry.has(Capability.HARDCORE_VIEW_PACKET),
        "no ClientboundLoginPacket here, so hardcore hearts cannot be toggled");
    assertFalse(registry.has(Capability.BEDROCK_FORMS));
    assertFalse(registry.has(Capability.SKIN_LOOKUP_OFFLINE));
    assertFalse(registry.has(Capability.LOBBY_NPCS));
    assertFalse(registry.has(Capability.HUD_OVERLAY));
    assertFalse(registry.has(Capability.DIMENSION_STORAGE_KEYED),
        "no server means no readable version, and an unknown version is not 26.1+");
  }

  @Test
  @DisplayName("a probe that throws costs its own line, not the enable")
  void athrowingProbeIsContained() {
    // hudOverlay is supplied by the caller (the HUD stack); a LinkageError out of it must not escape
    // a registry that is built inside onEnable.
    CapabilityRegistry registry = PaperCapabilityRegistry.probe(
        () -> {
          throw new NoClassDefFoundError("kr/toxicity/hud/api/BetterHudAPI");
        },
        backend());

    assertFalse(registry.has(Capability.HUD_OVERLAY));
    assertTrue(registry.reason(Capability.HUD_OVERLAY).orElse("").contains("probing this capability failed"),
        "the failure should be reported as the capability's reason: "
            + registry.reason(Capability.HUD_OVERLAY));
  }
}
