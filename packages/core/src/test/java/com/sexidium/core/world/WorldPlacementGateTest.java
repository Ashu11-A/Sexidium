package com.sexidium.core.world;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldPlacementGateTest {

  @Test
  @DisplayName("the standalone gate allows everything and names no owner")
  void allowAll() {
    WorldPlacementGate.Decision decision =
        WorldPlacementGate.ALLOW_ALL.check("experiences:Ashu11a/Diamond_Hunt_ab12cd34");

    assertTrue(decision.allowed());
    assertNull(decision.ownerNodeId());
    assertFalse(decision.busy());
  }

  @Test
  @DisplayName("a world owned elsewhere is refused, naming the node to route to")
  void ownedElsewhere() {
    WorldPlacementGate.Decision decision = WorldPlacementGate.Decision.ownedBy("worker-2");

    assertFalse(decision.allowed());
    assertEquals("worker-2", decision.ownerNodeId());
    // Not busy: the owner is simply offline or has it unloaded. The player still cannot be
    // served here, because the folder is on that machine's disk.
    assertFalse(decision.busy());
  }

  @Test
  @DisplayName("a world currently open elsewhere is refused as busy")
  void busyElsewhere() {
    WorldPlacementGate.Decision decision = WorldPlacementGate.Decision.busyOn("worker-1");

    assertFalse(decision.allowed());
    assertEquals("worker-1", decision.ownerNodeId());
    assertTrue(decision.busy());
  }

  @Test
  @DisplayName("a refusing gate is what stops an empty world replacing a saved map")
  void refusalIsTheWholePoint() {
    // Models the acquisition path's decision: allowed -> create/load, refused -> route.
    WorldPlacementGate gate = worldKey -> WorldPlacementGate.Decision.ownedBy("worker-2");

    boolean wouldGenerate = gate.check("experiences:Ashu11a/Diamond_Hunt_ab12cd34").allowed();

    assertFalse(wouldGenerate,
        "generating here would overwrite the player's world, which lives on worker-2's disk");
  }
}
