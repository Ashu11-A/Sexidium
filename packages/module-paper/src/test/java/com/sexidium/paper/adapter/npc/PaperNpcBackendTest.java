package com.sexidium.paper.adapter.npc;

import com.sexidium.core.platform.NpcAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The migrated NPC seam (Plan.md Stage 0b). Without FancyNpcs/FancyHolograms — the situation of every
 * unit test JVM, and of plenty of real servers — the backend must report zero capabilities WITH a
 * reason, and hand back the inert core no-op rather than exploding on missing classes.
 */
class PaperNpcBackendTest {

  @Test
  @DisplayName("with neither plugin present it reports no capabilities, with a reason")
  void reportsWhyItCannotServe() {
    PaperNpcBackend backend = new PaperNpcBackend(null);

    Optional<String> reason = backend.unavailableReason();
    assertTrue(reason.isPresent(), "a bare JVM cannot have both plugins installed");
    assertEquals(0, backend.capabilities().size(), "installed-vs-capable: nothing is capable here");
  }

  @Test
  @DisplayName("the bound adapter degrades to the core no-op, never null")
  void bindsToTheNoopWhenUncapable() {
    PaperNpcBackend backend = new PaperNpcBackend(null);

    assertSame(NpcAdapter.NOOP, backend.adapter());
    // And the binding is remembered: asking twice must not re-probe into a different answer.
    assertSame(NpcAdapter.NOOP, backend.adapter());
    assertEquals(0, backend.capabilities().size());
  }
}
