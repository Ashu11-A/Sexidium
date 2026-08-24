package com.sexidium.core.game.experience.challenges;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ChainSolverTest {

  // (segmentLength, pullStrength, damping, dragStrength, maxPull, moveEpsilon, hardSnapMultiplier)
  private static ChainSolver.Config config() {
    return new ChainSolver.Config(5.0, 0.18, 0.5, 0.06, 0.6, 0.02, 1.7);
  }

  private static double[] zeros(int n) {
    return new double[n];
  }

  @Test
  void fewerThanTwoPlayers_noForces() {
    ChainSolver.Step step = ChainSolver.solve(new double[] {0}, new double[] {0},
        new double[] {0}, new double[] {0}, config());
    assertEquals(0.0, step.shoveX[0]);
    assertEquals(-1, step.snapTo[0]);
  }

  @Test
  void slackLink_exertsNoForceAndNoDrag_playersFree() {
    // All links within rest length (slack): even with the whole team walking, nobody is pulled or dragged.
    double[] x = {0.0, 2.0, 4.0};
    double[] z = {0.0, 0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, new double[] {0.3, 0.3, 0.0}, zeros(3), config());
    for (int i = 0; i < 3; i++) {
      assertEquals(0.0, step.shoveX[i], 1.0e-9);
      assertEquals(0.0, step.dragX[i], 1.0e-9);
    }
  }

  @Test
  void stretchedStandingLink_reelsBothEndsInward_softSaturated() {
    double[] x = {0.0, 8.0}; // dist 8 < hardSnap 8.5 → spring, not snap; overshoot 3 → base 0.54
    double[] z = {0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, zeros(2), zeros(2), config());
    double expected = 0.6 * Math.tanh(0.54 / 0.6); // softSaturate(0.54, maxPull 0.6)
    assertEquals(expected, step.shoveX[0], 1.0e-9, "trailing end pulled toward neighbour (+x), eased");
    assertEquals(-expected, step.shoveX[1], 1.0e-9, "leading end pulled back (-x)");
    assertTrue(expected < 0.54, "tanh saturation keeps the force below the raw linear spring");
  }

  @Test
  void springForce_easesIntoMaxPull_neverExceeds() {
    ChainSolver.Config tight = new ChainSolver.Config(5.0, 0.18, 0.5, 0.06, 0.1, 0.02, 5.0);
    ChainSolver.Step step = ChainSolver.solve(new double[] {0.0, 8.0}, new double[] {0.0, 0.0},
        zeros(2), zeros(2), tight);
    assertTrue(step.shoveX[0] < 0.1, "never exceeds the ceiling");
    assertTrue(step.shoveX[0] > 0.099, "but eases right up to it for a large stretch");
  }

  @Test
  void fightingTheRope_getsStrongerSpringBackThanStandingStill() {
    ChainSolver.Config c = new ChainSolver.Config(5.0, 0.1, 1.0, 0.0, 10.0, 0.02, 5.0);
    double[] x = {0.0, 7.0}; // a=0, b=7, rope +x; overshoot 2 → base 0.2
    double[] z = {0.0, 0.0};
    ChainSolver.Step standing = ChainSolver.solve(x, z, zeros(2), zeros(2), c);
    ChainSolver.Step fleeing = ChainSolver.solve(x, z, new double[] {-0.5, 0.0}, new double[] {0.0, 0.0}, c);
    assertEquals(10.0 * Math.tanh(0.02), standing.shoveX[0], 1.0e-9, "passive: only the gentle base reel");
    assertTrue(fleeing.shoveX[0] > standing.shoveX[0] + 1.0e-3, "fighting the rope adds spring-back");
    assertEquals(10.0 * Math.tanh(0.07), fleeing.shoveX[0], 1.0e-9); // base 0.2 + damping 1.0 * 0.5
  }

  @Test
  void dampingFadesInNearBoundary_noStepAtTautOnset() {
    ChainSolver.Config c = new ChainSolver.Config(5.0, 0.1, 1.0, 0.0, 10.0, 0.02, 5.0);
    double[] x = {0.0, 5.25}; // overshoot 0.25 < blend width 0.5 → damping at half strength
    double[] z = {0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, new double[] {-0.5, 0.0}, new double[] {0.0, 0.0}, c);
    // base 0.1*0.25 + damping 1.0 * outward 0.5 * blend (0.25/0.5 = 0.5) = 0.025 + 0.25 = 0.275
    assertEquals(10.0 * Math.tanh(0.275 / 10.0), step.shoveX[0], 1.0e-9);
    assertTrue(step.shoveX[0] < 10.0 * Math.tanh(0.525 / 10.0),
        "near the boundary the damping is faded, not applied at full strength");
  }

  @Test
  void returningTowardNeighbour_getsNoPush_noBounce() {
    ChainSolver.Config c = new ChainSolver.Config(5.0, 0.1, 1.0, 0.0, 10.0, 0.02, 5.0);
    ChainSolver.Step step = ChainSolver.solve(new double[] {0.0, 7.0}, new double[] {0.0, 0.0},
        new double[] {0.5, 0.0}, new double[] {0.0, 0.0}, c);
    assertEquals(0.0, step.shoveX[0], 1.0e-9, "no inward shove while already returning");
  }

  @Test
  void overStretchedLink_snapsLaggingNeighbour() {
    double[] x = {0.0, 10.0}; // dist 10 > 5 * 1.7 = 8.5 → snap
    double[] z = {0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, zeros(2), zeros(2), config());
    assertEquals(1, step.snapTo[0]);
    assertEquals(-1, step.snapTo[1]);
  }

  @Test
  void movingMajority_dragsTautStragglerOnly() {
    // 0,1 walk +x; 2 stands. Link 1-2 is taut (dist 6), link 0-1 is slack (dist 2).
    double[] x = {0.0, 2.0, 8.0};
    double[] z = {0.0, 0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, new double[] {0.3, 0.3, 0.0}, zeros(3), config());
    assertTrue(step.dragX[2] > 0.0, "taut straggler dragged along the heading");
    assertEquals(0.0, step.dragX[0], 1.0e-9, "the slack leader's link is loose → it is not dragged");
  }

  @Test
  void agreementAddsStrength_moreMoversDragHarder() {
    double[] x = {0.0, 2.0, 8.0}; // straggler (index 2) taut via link 1-2
    double[] z = {0.0, 0.0, 0.0};
    ChainSolver.Step a = ChainSolver.solve(x, z, new double[] {0.3, 0.0, 0.0}, zeros(3), config());
    ChainSolver.Step b = ChainSolver.solve(x, z, new double[] {0.3, 0.3, 0.0}, zeros(3), config());
    assertTrue(b.dragX[2] > a.dragX[2] + 1.0e-9,
        "more players agreeing on a heading drags the straggler harder (strength adds)");
  }

  @Test
  void consensusStrengthIsExposed() {
    double[] x = {0.0, 2.0, 8.0};
    double[] z = {0.0, 0.0, 0.0};
    ChainSolver.Step step = ChainSolver.solve(x, z, new double[] {0.3, 0.3, 0.0}, zeros(3), config());
    assertTrue(step.consensusStrength > 1.0, "two aligned movers → consensus strength ~2");
  }
}
