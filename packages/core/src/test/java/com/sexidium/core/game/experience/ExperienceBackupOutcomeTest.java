package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.game.experience.ExperienceManager.Experience;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.ExperienceCommandStore;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the owner is TOLD about a backup, and what a backup is allowed to be mistaken for afterwards.
 *
 * <p>The routing was already right — a backup runs where the folder is. What was wrong was everything
 * the owner then reads. On a network the owner always clicks from the LOBBY, so every real backup is
 * answered by a peer, and a peer can only say "accepted" or "refused". "Accepted" was being read as
 * "created": the copy engine hands the folder to a staging executor and returns, so the worker answers
 * OK the instant the copy STARTS. The owner was told <em>"Backup created. It is a world of its own
 * now"</em> about bytes still being written, and if that copy then failed — a full disk, a failed
 * insert, a shutdown race, all of which delete the half-written folders — nobody ever told them.</p>
 *
 * <p>And once the copy does land it is a real row, created now, wearing the source's name. Two places
 * then hand it to somebody who never asked for a copy: the lobby End portal, which takes the newest
 * experience among the player AND their friends, and the world browser, which lists everything a
 * friend owns.</p>
 */
class ExperienceBackupOutcomeTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** A bus that keeps what it was handed; nothing here needs delivery. */
  private static final class Published implements NetworkBus {
    final List<String> messages = new ArrayList<>();

    @Override public void start() { }

    @Override public void publish(String topic, String key, String payload) {
      messages.add(topic + "|" + key + "|" + payload);
    }

    @Override public AutoCloseable subscribe(String topic, BusListener listener) { return () -> { }; }

    @Override public void close() { }
  }

  /** The smallest {@code experience_commands} table a folder op needs: insert and read back. */
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

    @Override public List<Command> pendingFor(String nodeId, long now) { return List.of(); }

    @Override public int expire(long now) { return 0; }
  }

  /** A locator that says {@code nodeId} is this world's recorded home, with a live lease. */
  private static ExperienceLocator holder(String nodeId) {
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) {
        return Optional.of(new Placement(key, "exp-1", nodeId, 7L, 42L, "LOADED", 1,
            System.currentTimeMillis() + 60_000L));
      }

      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }

      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  /** A locator that read the table fine and found nobody holding this world at all. */
  private static ExperienceLocator nobody() {
    return new ExperienceLocator() {
      @Override public Optional<Placement> locate(WorldKey key) { return Optional.empty(); }

      @Override public Optional<WorldKey> keyOf(String experienceId) { return Optional.empty(); }

      @Override public List<Placement> heldBy(String node) { return List.of(); }
    };
  }

  @TempDir
  Path tmp;

  private Database database;
  private ExperienceManager experiences;
  private Experience experience;
  private ExperienceService service;
  private TestServerAdapter server;
  private GameManager games;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "backups.db"));
    experiences = new ExperienceManager(SILENT, database);
    experience = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Random Drops",
        System.currentTimeMillis());
    assertNotNull(experience, "the fixture needs a stored experience to copy");

    server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
    };
    games = new GameManager(
        new GameContext(server, new NoopKitAdapter(), RankAwardPort.noop()), new GameRegistry(), null);
    service = new ExperienceService(server, games, experiences, null, null);
  }

  /**
   * The lobby, in production: it may not open experience worlds, and the world lives on worker-2. Every
   * backup an owner ever clicks goes through exactly this wiring.
   */
  private Table asLobbyRoutingTo(String node) {
    Table table = new Table();
    service.commands().attach(holder(node), new Published(), "lobby", table, () -> false);
    return table;
  }

  private static String onlyRequestIn(Table table) {
    assertEquals(1, table.rows.size(), "a folder op is written down before it is announced");
    return table.rows.keySet().iterator().next();
  }

  @Test
  @DisplayName("THE LIE: the owner is told 'Backup created.' about a copy that has not been made")
  void aPeersYesMeansStarted() {
    Table table = asLobbyRoutingTo("worker-2");
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();

    service.backup(owner, experience.id(), outcomes::add);
    // Routed and unanswered: the owner is told nothing yet, which is what the "Taking a copy…" line
    // on screen is for.
    assertTrue(outcomes.isEmpty(), "nothing may be promised before the holder has spoken");

    String requestId = onlyRequestIn(table);
    service.commands().onResult(experience.id(), "lobby|" + requestId + "|BACKUP|OK|applied on 'worker-2'");

    // worker-2's OK means it ACCEPTED and STARTED the copy: WorldLeaseService.copyExperienceWorld hands
    // the folder to a staging executor and returns, so the row is marked DONE while the bytes are still
    // moving. QUEUED is the only sentence that is true whether or not the copy has landed -- and it is
    // the sentence that keeps the promise honest if the copy then fails and deletes itself.
    assertEquals(List.of(ExperienceBackup.Outcome.QUEUED), outcomes,
        "CREATED here told the owner their world existed while it was still being written, and said"
            + " nothing at all if the copy then failed");
  }

  @Test
  @DisplayName("a holder that refuses the copy is reported as a failure, not as a copy in progress")
  void aPeersNoIsAFailure() {
    Table table = asLobbyRoutingTo("worker-2");
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();

    service.backup(owner, experience.id(), outcomes::add);
    String requestId = onlyRequestIn(table);
    service.commands().onResult(experience.id(),
        "lobby|" + requestId + "|BACKUP|FAILED|'worker-2' declined it");

    // A peer's boolean cannot say BUSY from LIMIT_REACHED from a disk that filled up, so the honest
    // degradation is FAILED: "it did not happen, your world was not touched, try again".
    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), outcomes);
  }

  @Test
  @DisplayName("a world no node holds cannot be copied, so the owner is not told a copy is on its way")
  void nothingToCopyFromIsNotAPromise() {
    Table table = new Table();
    // Read successfully, and nobody holds this world -- the router's `unrouted`. For a DELETE that is a
    // legitimate SUCCESS (a world with no placement row has no folder to remove); for a backup it is the
    // exact opposite, and the two must not be folded together again.
    service.commands().attach(nobody(), new Published(), "lobby", table, () -> false);
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();

    service.backup(owner, experience.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.FAILED), outcomes,
        "unrouted is answered BEFORE anything is written down, so there is no request row, no pending"
            + " answer and no folder anywhere -- 'it appears when it is finished' promises a world that"
            + " will never exist");
    assertTrue(table.rows.isEmpty(), "nothing was written down, so nothing may be promised");
  }

  @Test
  @DisplayName("THE HIJACK: after a backup, the lobby End portal must not drop the pool into the copy")
  void thePortalNeverPicksACopy() {
    // Exactly what the engine writes: the source's display name, verbatim, created NOW.
    Experience backup = experiences.createBackup(experience, experience.displayName(),
        System.currentTimeMillis() + 1_000L);
    assertNotNull(backup);
    assertTrue(backup.isBackup());
    assertEquals(experience.displayName(), backup.displayName(),
        "the copy is indistinguishable by name -- which is why nothing may pick it by accident");

    // The trap, spelled out: the raw newest-row query really does answer the backup. enterLatest used
    // to call precisely this, and its pool is the player PLUS every friend, so one player taking a
    // backup silently sent the whole group into a frozen copy under the live world's name.
    assertEquals(backup.id(), experiences.latestByOwners(List.of(owner)).id());

    assertEquals(experience.id(), service.newestNonBackup(List.of(owner)).id(),
        "nobody asks a portal for a copy: a backup is only ever entered by being picked out of the"
            + " owner's own list on purpose");

    // And an owner whose ONLY row is a copy has nowhere to be taken -- NOT_FOUND, never the copy.
    UUID hasOnlyBackups = UUID.randomUUID();
    assertNull(service.newestNonBackup(List.of(hasOnlyBackups)));
  }

  @Test
  @DisplayName("a friend's backups are not offered in Browse Worlds as if they were live worlds")
  void afriendsBackupIsNotBrowsable() throws Exception {
    UUID viewer = UUID.randomUUID();
    FriendService friends = new FriendService(SILENT, database);
    friends.requestAsync(viewer, "Viewer", owner);
    friends.flushWrites();
    assertTrue(friends.accept(owner, "Ashu11a", viewer, "Viewer"), "the fixture needs a friendship");

    ExperienceService browsing = new ExperienceService(server, games, experiences, null, friends);
    Experience backup = experiences.createBackup(experience, experience.displayName(),
        System.currentTimeMillis() + 1_000L);
    assertNotNull(backup);

    List<String> listed = new ArrayList<>();
    for (Experience visible : browsing.browsable(viewer)) {
      listed.add(visible.id());
    }

    // The friends half of the browser is "everything my friend owns", private rows included, because
    // friends may join each other's private worlds. That is what swept the backups in: same name, same
    // icon, same lore, and canEnterDirectly walks a friend straight into a frozen copy they believe is
    // the live world. The distinction exists only in the OWNER's list.
    assertTrue(listed.contains(experience.id()), "a friend's real world is still listed");
    assertFalse(listed.contains(backup.id()),
        "a copy of a world is not a second world to visit, and nothing in the browser says it is one");

    friends.shutdown();
  }
}
