package com.sexidium.core.game.experience;

import com.sexidium.core.network.ExperienceCommandStore;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backup reads the world FOLDER, so it has to run where the folder is — never where the owner is.
 *
 * <p>This is the same lesson {@link ExperienceCommandRouter.Op#DELETE} paid for: the manage menu lives
 * in the lobby, the lobby has no experience world on its disk, and an op that resolves its target from
 * the LIVE lease answers "nobody has it open, do it here" about a world sitting on a worker. For a
 * delete that was data loss; for a backup it would be a copy of an empty directory, handed to the owner
 * as an exact replica of their world.</p>
 */
class ExperienceBackupRoutingTest {

  private static final WorldKey KEY = WorldKey.parse("diamond_hunt_ab12cd34");

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** Records what was applied locally. */
  private static final class Applied {
    final List<String> ops = new ArrayList<>();

    boolean record(String experienceId, ExperienceCommandRouter.Op op, Map<String, String> args) {
      ops.add(experienceId + ":" + op);
      return true;
    }
  }

  /** Records what was published. */
  private static final class Published implements NetworkBus {
    final List<String> messages = new ArrayList<>();

    @Override public void start() { }

    @Override public void publish(String topic, String key, String payload) {
      messages.add(topic + "|" + key + "|" + payload);
    }

    @Override public AutoCloseable subscribe(String topic, BusListener listener) { return () -> { }; }

    @Override public void close() { }
  }

  /** The smallest {@code experience_commands} table the folder ops need: insert and read back. */
  private static final class Table implements ExperienceCommandStore {
    final Map<String, Command> rows = new LinkedHashMap<>();

    @Override public boolean insert(Command command) {
      return rows.putIfAbsent(command.id(), command) == null;
    }

    @Override public Optional<Command> byId(String id) { return Optional.ofNullable(rows.get(id)); }

    @Override public Optional<Command> claim(String id, String nodeId) {
      Command row = rows.get(id);
      if (row == null || row.state() != State.PENDING || !nodeId.equals(row.targetNode())) {
        return Optional.empty();
      }
      Command running = new Command(row.id(), row.experienceId(), row.worldKey(), row.op(), row.args(),
          row.requestedBy(), row.targetNode(), State.RUNNING, row.detail(), row.deadline(),
          row.createdAt(), System.currentTimeMillis());
      rows.put(id, running);
      return Optional.of(running);
    }

    @Override public boolean retarget(String id, String nodeId) { return false; }

    @Override public boolean complete(String id, boolean ok, String detail) {
      Command row = rows.get(id);
      if (row == null || row.state() != State.RUNNING) {
        return false;
      }
      rows.put(id, new Command(row.id(), row.experienceId(), row.worldKey(), row.op(), row.args(),
          row.requestedBy(), row.targetNode(), ok ? State.DONE : State.FAILED, detail, row.deadline(),
          row.createdAt(), System.currentTimeMillis()));
      return true;
    }

    @Override public List<Command> pendingFor(String nodeId, long now) {
      List<Command> pending = new ArrayList<>();
      for (Command row : rows.values()) {
        if (nodeId.equals(row.targetNode()) && row.state() == State.PENDING) {
          pending.add(row);
        }
      }
      return pending;
    }

    @Override public int expire(long now) { return 0; }
  }

  /** A locator that says one node holds the world, with a live or a lapsed lease. */
  private static ExperienceLocator locator(String nodeId, boolean leaseHeld) {
    long expires = leaseHeld ? System.currentTimeMillis() + 60_000L : 1L;
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) {
        return Optional.of(new Placement(key, "exp-1", nodeId, 7L, 42L, "LOADED", 1, expires));
      }

      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }

      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  @Test
  @DisplayName("BACKUP is a folder op, exactly like DELETE")
  void backupTouchesTheFolder() {
    assertTrue(ExperienceCommandRouter.Op.BACKUP.touchesTheFolder(),
        "resolving a backup off the LIVE lease means an idle world is 'held by nobody', and the lobby"
            + " would then copy a folder it does not have");
    assertTrue(ExperienceCommandRouter.Op.DELETE.touchesTheFolder());
    assertFalse(ExperienceCommandRouter.Op.SET_HARDCORE.touchesTheFolder());
    assertFalse(ExperienceCommandRouter.Op.END_MATCH.touchesTheFolder());
    assertFalse(ExperienceCommandRouter.Op.SET_CHALLENGES.touchesTheFolder());
    assertFalse(ExperienceCommandRouter.Op.SET_KEEP_INVENTORY.touchesTheFolder());
  }

  @Test
  @DisplayName("THE BUG: a backup asked for in the lobby is SENT to the node holding the folder")
  void aBackupFromTheLobbyIsRouted() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    router.attach(locator("worker-2", true), bus, "lobby", new Table(), () -> false);

    ExperienceCommandRouter.Result result =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.BACKUP, Map.of());

    assertTrue(result.routed(), "the lobby has no copy of these bytes to make");
    assertTrue(applied.ops.isEmpty());
    assertEquals(1, bus.messages.size());
    assertTrue(bus.messages.get(0)
            .startsWith(NetworkBus.Topics.EXPERIENCE_COMMAND + "|exp-1|worker-2|BACKUP"),
        bus.messages.get(0));
  }

  @Test
  @DisplayName("a world nobody has OPEN is still backed up where its folder lives, not where it is idle")
  void anIdleWorldIsBackedUpWhereItLives() {
    Applied applied = new Applied();
    Published bus = new Published();
    Table table = new Table();
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, applied::record, "lobby");
    // A lapsed lease: worker-2 is still the recorded home, but nobody has the world open. This is the
    // COMMON case for a backup -- the engine refuses a loaded world outright -- and it is exactly the
    // case a live-edit target resolution reads as "nothing to disturb, do it here".
    router.attach(locator("worker-2", false), bus, "lobby", table, () -> false);

    ExperienceCommandRouter.Result backup =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.BACKUP, Map.of());
    // ...and the contrast that makes the point: the same locator sends a live edit nowhere at all.
    ExperienceCommandRouter.Result liveEdit =
        router.execute("exp-1", KEY, ExperienceCommandRouter.Op.SET_HARDCORE, Map.of("value", "true"));

    assertTrue(backup.routed(), "world_placements.node_id is durable; the lease is not");
    assertTrue(liveEdit.applied(), "a live edit has no in-memory state to chase on another node");
    assertEquals(List.of("exp-1:SET_HARDCORE"), applied.ops,
        "the lobby must not be the one reading the folder");
    assertEquals(1, table.rows.size(), "a folder op is written down BEFORE it is announced");
    assertEquals("BACKUP", table.rows.values().iterator().next().op());
  }

  @Test
  @DisplayName("the node holding the folder does the copy itself, with no hop")
  void theHolderCopiesLocally() {
    Applied applied = new Applied();
    Published bus = new Published();
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    router.attach(locator("worker-2", false), bus, "worker-2", new Table(), () -> true);

    assertTrue(router.execute("exp-1", KEY, ExperienceCommandRouter.Op.BACKUP, Map.of()).applied());
    assertEquals(List.of("exp-1:BACKUP"), applied.ops);
    assertTrue(bus.messages.isEmpty());
  }

  @Test
  @DisplayName("a backup published while the holder was down is found in its own table at boot")
  void aLostBackupRequestIsRecovered() {
    Applied applied = new Applied();
    Table table = new Table();
    long now = System.currentTimeMillis();
    table.rows.put("req-1", new ExperienceCommandStore.Command("req-1", "exp-1", KEY.key(), "BACKUP",
        "", "lobby", "worker-2", ExperienceCommandStore.State.PENDING, null, now + 60_000L, now, now));
    ExperienceCommandRouter worker = new ExperienceCommandRouter(SILENT, applied::record, "worker-2");
    worker.attach(locator("worker-2", true), new Published(), "worker-2", table, () -> true);

    worker.tick();

    assertEquals(List.of("exp-1:BACKUP"), applied.ops,
        "the bus seeds its cursor to MAX(id) at start, so a request published while this node was"
            + " restarting is never delivered to it -- the ROW is what survives");
    assertEquals(ExperienceCommandStore.State.DONE, table.rows.get("req-1").state());
  }

  @Test
  @DisplayName("a backup addressed to another node is ignored")
  void aBackupForAnotherNodeIsIgnored() {
    Applied applied = new Applied();
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT, applied::record, "worker-3");

    router.onMessage("exp-1", "worker-2|BACKUP|");

    assertTrue(applied.ops.isEmpty(), "every node sees every bus message");
  }
}
