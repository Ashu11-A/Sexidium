package com.sexidium.core;

import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.network.WorldContentRequirements;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code SexidiumCore.start()} actually WIRES on a networked node.
 *
 * <p>Everything else about the content gate was already tested — and the gate still never fired,
 * because every one of those tests injected its own requirements lambda. The production seam had no
 * caller at all: {@code contentRequirements} stayed {@link WorldContentRequirements#NONE} for the life
 * of the process, so {@code missingContentFor} answered {@code []} for every world, all four
 * enforcement points passed everything, and a node one build behind would open a SkyBlock world and
 * generate normal terrain into it. A test that constructs the collaborators itself cannot see that;
 * only one that boots the real core can.</p>
 */
class NetworkedCoreWiringTest {

  /** A world whose experience row asks for one challenge this build has and one it does not. */
  private static final String KNOWN_CHALLENGE = "doubledrops";
  private static final String UNKNOWN_CHALLENGE = "onlyinanewerbuild";

  /** Minimal in-memory config: everything the core reads has a default, so only overrides live here. */
  private static final class FakeConfig implements ConfigurationAdapter {
    private final Map<String, Object> values = new HashMap<>();

    FakeConfig with(String path, Object value) {
      values.put(path, value);
      return this;
    }

    @Override public boolean getBoolean(String path, boolean defaultValue) {
      return values.containsKey(path) ? (Boolean) values.get(path) : defaultValue;
    }

    @Override public int getInt(String path, int defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).intValue() : defaultValue;
    }

    @Override public long getLong(String path, long defaultValue) {
      return values.containsKey(path) ? ((Number) values.get(path)).longValue() : defaultValue;
    }

    @Override public double getDouble(String path, double defaultValue) { return defaultValue; }

    @Override public String getString(String path, String defaultValue) {
      return values.containsKey(path) ? (String) values.get(path) : defaultValue;
    }

    @Override public List<String> getStringList(String path) { return List.of(); }
    @Override public List<Map<String, Object>> getMapList(String path) { return List.of(); }
    @Override public Set<String> keys(String path) { return Set.of(); }
    @Override public Object get(String path) { return values.get(path); }
    @Override public boolean contains(String path) { return values.containsKey(path); }
    @Override public void set(String path, Object value) { values.put(path, value); }
    @Override public void reload() { }
    @Override public void save() { }
  }

  /** {@link TestServerAdapter} that is a WORKER on a network rather than a standalone server. */
  private static final class WorkerServerAdapter extends TestServerAdapter {
    private final Path dataDirectory;
    private final ConfigurationAdapter configuration;

    WorkerServerAdapter(Path dataDirectory, ConfigurationAdapter configuration) {
      this.dataDirectory = dataDirectory;
      this.configuration = configuration;
    }

    @Override public Path dataDirectory() { return dataDirectory; }

    @Override public ConfigurationAdapter configuration() { return configuration; }

    @Override public NodeIdentity identity() {
      return NodeIdentity.of("worker-1", "worker-1", Set.of(
          NodeCapability.EXPERIENCES, NodeCapability.MINIGAMES));
    }
  }

  @TempDir
  Path tmp;

  private Database database;
  private SexidiumCore core;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "core.db"));
  }

  @AfterEach
  void tearDown() {
    if (core != null) {
      core.close();
    }
  }

  /** A started worker core over the shared database. The API is off: a test binds no ports. */
  private SexidiumCore startedWorker() {
    GameRegistry registry = new GameRegistry();
    registry.register(new GameModeDescriptor(
        com.sexidium.core.game.experience.ExperienceGame.MODE_ID, "experience", "Experience", 1,
        List.of()), (context, id, args) -> null);
    core = new SexidiumCore(new SexidiumCoreDependencies(
        new WorkerServerAdapter(tmp, new FakeConfig().with("api.enabled", false)),
        new NoopKitAdapter(), registry, database, null, () -> false));
    core.start();
    return core;
  }

  /** An experience row exactly as the builder writes one, and the key its world is placed under. */
  private String storeExperience(List<String> challenges) {
    ExperienceManager experiences = new ExperienceManager(
        new com.sexidium.core.platform.noop.StdoutLoggerAdapter("test"), database);
    ExperienceManager.Experience stored = experiences.create(
        UUID.randomUUID(), "Ashu11a", challenges, "Diamond Hunt", System.currentTimeMillis());
    return WorldKey.parse(stored.worldKey()).key();
  }

  @Test
  @DisplayName("a started node asks the experience registry what each world needs")
  void startInstallsTheContentRequirements() {
    SexidiumCore started = startedWorker();

    assertNotSame(WorldContentRequirements.NONE, started.network().contentRequirements(),
        "the requirements seam had no caller in production: every gate that reads it was inert, and"
            + " the tests passed because each of them installed its own lambda");
  }

  @Test
  @DisplayName("the codes come from the world's own experience row: challenges, mode, and nothing else")
  void requiredCodesAreReadFromTheExperienceRow() {
    SexidiumCore started = startedWorker();
    String worldKey = storeExperience(List.of(KNOWN_CHALLENGE, UNKNOWN_CHALLENGE));

    List<String> required = started.network().contentRequirements().requiredCodes(worldKey);

    assertTrue(required.contains("c:" + KNOWN_CHALLENGE), "every challenge the world runs is required");
    assertTrue(required.contains("c:" + UNKNOWN_CHALLENGE));
    assertTrue(required.contains("m:experience"), "so is the mode that opens it");
  }

  @Test
  @DisplayName("a build missing one of the world's challenges reports it, instead of opening the world")
  void aMissingChallengeIsReportedByTheLiveGate() {
    SexidiumCore started = startedWorker();
    String worldKey = storeExperience(List.of(KNOWN_CHALLENGE, UNKNOWN_CHALLENGE));

    // The end-to-end answer, through the one method all four enforcement points call. Before the
    // wiring existed this returned [] for every world on every node, which is precisely how a node
    // one build behind came to generate normal terrain over somebody's SkyBlock.
    assertEquals(List.of("c:" + UNKNOWN_CHALLENGE), started.network().missingContentFor(worldKey));
    assertFalse(started.network().canHostContent(worldKey));
  }

  @Test
  @DisplayName("a world with no experience row constrains nobody, and never refuses")
  void anUnknownWorldStaysUnconstrained() {
    SexidiumCore started = startedWorker();

    assertTrue(started.network().contentRequirements().requiredCodes("no_such_world_ab12").isEmpty());
    assertTrue(started.network().canHostContent("no_such_world_ab12"),
        "a refused world is a player who cannot get into their own save; an unknown world is not"
            + " evidence of skew and must not be treated as any");
  }
}
