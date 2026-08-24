package com.sexidium.core.network;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rollback trigger: is this node fit to serve, answered before a player finds out the hard way.
 */
class NodeSelfTestTest {

  @TempDir
  Path tmp;

  private static GameRegistry registryWith(String... modeIds) {
    GameRegistry registry = new GameRegistry();
    for (String modeId : modeIds) {
      registry.register(
          new GameModeDescriptor(modeId, "minigames", modeId, 1, List.of()),
          (GameContext context, String id, List<String> args) -> null);
    }
    return registry;
  }

  @Test
  @DisplayName("a healthy node reports every challenge and every mode, on one line")
  void healthyNodeReportsOk() {
    NodeSelfTest.Result result =
        new NodeSelfTest(registryWith("tntwar", "gather"), () -> "overworld 3/3", null).run();
    assertTrue(result.ok(), "detail was: " + result.detail());
    assertEquals(com.sexidium.core.game.experience.ChallengeCatalog.available().size(),
        result.challengesTotal());
    assertEquals(result.challengesTotal(), result.challengesOk());
    assertEquals(2, result.modesOk());
    // One word in the line, the description in the JSON: an orchestrator parses key=value.
    assertEquals("ok", result.pool());
    assertEquals("overworld 3/3", result.poolDetail());
    assertEquals("SX-SELFTEST ok=true challenges=" + result.challengesTotal() + "/"
        + result.challengesTotal() + " modes=2/2 pool=ok", result.logLine());
    assertFalse(result.logLine().contains("\n"));
  }

  @Test
  @DisplayName("a probe that throws is a finding, not an exception — it never takes the caller down")
  void aThrowingProbeBecomesAFinding() {
    NodeSelfTest.Result result = new NodeSelfTest(registryWith("tntwar"), () -> {
      throw new IllegalStateException("world control is gone");
    }, null).run();
    assertFalse(result.ok());
    assertEquals("error", result.pool());
    assertTrue(result.detail().contains("world control is gone"));
    assertTrue(result.logLine().startsWith("SX-SELFTEST ok=false detail="));
  }

  @Test
  @DisplayName("the database is genuinely queried, not merely held")
  void theDatabaseIsProbed() throws Exception {
    try (Database database = new Database(new File(tmp.toFile(), "selftest.db"))) {
      NodeSelfTest.Result result = new NodeSelfTest(registryWith(), () -> "ok", database).run();
      assertTrue(result.databaseOk());
      assertTrue(result.ok(), "detail was: " + result.detail());
    }
  }

  @Test
  @DisplayName("a node with no modes and no database is still a valid answer")
  void anEmptyNodeIsStillOk() {
    NodeSelfTest.Result result = new NodeSelfTest(null, null, null).run();
    assertTrue(result.ok());
    assertEquals(0, result.modesTotal());
    assertEquals("unknown", result.pool());
  }

  @Test
  @DisplayName("the detail is the FIRST failure and stays on one greppable line")
  void detailIsBoundedAndSingleLine() {
    NodeSelfTest.Result result = new NodeSelfTest(registryWith(), () -> {
      throw new IllegalStateException("line one\nline two");
    }, null).run();
    assertFalse(result.detail().contains("\n"));
    assertTrue(result.detail().length() <= 220);
  }
}
