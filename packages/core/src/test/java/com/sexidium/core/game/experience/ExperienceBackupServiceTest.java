package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the owner is TOLD about a backup, and what is refused before anything is copied.
 *
 * <p>{@code ExperienceService} copies nothing: it owns who may ask, where the request has to run, and
 * how the answer comes back. Three of those are worth pinning because getting them wrong is silent.
 * A stranger's request must not reach the engine at all — an engine that re-checks ownership is a
 * second opinion, not a gate. A refusal the owner can act on (leave the world; delete an old copy)
 * must survive the trip back as itself rather than collapsing into "failed". And a copy that has
 * FINISHED has to announce itself, because it adds a row to a list the owner is looking at from
 * another node entirely.</p>
 */
class ExperienceBackupServiceTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** A copy engine that answers whatever the test wants, or deliberately does not answer at all. */
  private static final class FakeEngine implements ExperienceBackup {
    final List<String> asked = new ArrayList<>();
    final List<UUID> requesters = new ArrayList<>();
    /** Null = the copy is under way and will answer later, which is the honest common case. */
    Outcome answer = Outcome.CREATED;
    Consumer<Outcome> deferred;

    /** Which verb was asked for, in order, so a routed op cannot quietly become a different one. */
    final List<String> verbs = new ArrayList<>();

    @Override public void backup(UUID requester, String experienceId, Consumer<Outcome> onOutcome) {
      run("backup", requester, experienceId, onOutcome);
    }

    @Override public void restore(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
      run("restore", requester, backupId, onOutcome);
    }

    @Override public void refresh(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
      run("refresh", requester, backupId, onOutcome);
    }

    @Override public void duplicate(UUID requester, String backupId, Consumer<Outcome> onOutcome) {
      run("duplicate", requester, backupId, onOutcome);
    }

    private void run(String verb, UUID requester, String experienceId, Consumer<Outcome> onOutcome) {
      verbs.add(verb);
      asked.add(experienceId);
      requesters.add(requester);
      if (answer == null) {
        deferred = onOutcome;
        return;
      }
      onOutcome.accept(answer);
    }
  }

  /** A bus that keeps what it was handed. */
  private static final class RecordingBus implements NetworkBus {
    final List<String> published = new ArrayList<>();

    @Override public void publish(String topic, String key, String payload) {
      published.add(topic + "|" + key + "|" + payload);
    }

    @Override public AutoCloseable subscribe(String topic, BusListener listener) { return () -> { }; }

    @Override public void start() { }

    @Override public void close() { }

    List<String> updatesFor(String experienceId) {
      List<String> matching = new ArrayList<>();
      for (String message : published) {
        if (message.startsWith(NetworkBus.Topics.EXPERIENCE_UPDATED + "|" + experienceId + "|")) {
          matching.add(message);
        }
      }
      return matching;
    }
  }

  @TempDir
  Path tmp;

  private ExperienceManager experiences;
  private ExperienceManager.Experience experience;
  private ExperienceService service;
  private FakeEngine engine;
  private RecordingBus bus;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "backups.db"));
    experiences = new ExperienceManager(SILENT, database);
    experience = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Random Drops",
        System.currentTimeMillis());
    assertNotNull(experience, "the fixture needs a stored experience");

    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
    };
    GameManager games = new GameManager(
        new GameContext(server, new NoopKitAdapter(), RankAwardPort.noop()), new GameRegistry(), null);
    service = new ExperienceService(server, games, experiences, null, null);
    engine = new FakeEngine();
    service.attachBackup(engine);
    bus = new RecordingBus();
    service.setUpdateChannel(bus, "lobby-1", id -> { });
  }

  private ExperienceBackup.Outcome backup(UUID requester) {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    service.backup(requester, experience.id(), outcomes::add);
    assertEquals(1, outcomes.size(), "the owner is answered exactly once, always");
    return outcomes.get(0);
  }

  @Test
  @DisplayName("a finished copy is CREATED, and it ANNOUNCES itself so open lists gain the row")
  void aFinishedCopyIsCreatedAndAnnounced() {
    assertEquals(ExperienceBackup.Outcome.CREATED, backup(owner));
    assertEquals(List.of(experience.id()), engine.asked);
    assertEquals(List.of(owner), engine.requesters,
        "the engine is told WHO asked, so its own ownership check has something to check");
    assertEquals(1, bus.updatesFor(experience.id()).size(),
        "a backup adds a row to My Experiences, and the owner is looking at that list from the lobby"
            + " while the copy was taken on a worker");
    assertTrue(bus.updatesFor(experience.id()).get(0).contains("field=backup"));
  }

  @Test
  @DisplayName("a stranger is refused HERE, and the engine is never asked")
  void aStrangerNeverReachesTheEngine() {
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, backup(UUID.randomUUID()));
    assertEquals(ExperienceBackup.Outcome.NOT_OWNER, backup(null));
    assertTrue(engine.asked.isEmpty(),
        "routing a stranger's request would write a row and wake a worker for a copy that is then"
            + " refused there -- the authorisation belongs where the click is");
    assertTrue(bus.updatesFor(experience.id()).isEmpty());
  }

  @Test
  @DisplayName("an experience that does not exist is NOT_OWNER, not a crash")
  void anUnknownExperienceIsNotOwner() {
    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    service.backup(owner, "no-such-experience", outcomes::add);
    assertEquals(List.of(ExperienceBackup.Outcome.NOT_OWNER), outcomes);
    assertTrue(engine.asked.isEmpty());
  }

  @Test
  @DisplayName("a refusal the owner can act on survives as itself, not as 'failed'")
  void anActionableRefusalKeepsItsMeaning() {
    engine.answer = ExperienceBackup.Outcome.BUSY;
    assertEquals(ExperienceBackup.Outcome.BUSY, backup(owner),
        "BUSY means 'leave the world and ask again'; FAILED means 'something broke'. Telling the"
            + " owner the second about the first sends them looking for a fault that is not there");
    assertTrue(bus.updatesFor(experience.id()).isEmpty(), "nothing was copied, so nothing changed");
  }

  @Test
  @DisplayName("a copy still running is QUEUED — never CREATED over a world that does not exist yet")
  void aCopyStillRunningIsQueued() {
    engine.answer = null; // the engine took the job and will answer when the folder is written
    assertEquals(ExperienceBackup.Outcome.QUEUED, backup(owner));
    assertTrue(bus.updatesFor(experience.id()).isEmpty(),
        "there is no new row to tell anybody about yet");

    // ...and when it lands, the announcement is what puts it in every open list.
    engine.deferred.accept(ExperienceBackup.Outcome.CREATED);
    assertEquals(1, bus.updatesFor(experience.id()).size());
  }

  @Test
  @DisplayName("with no engine attached the owner is told it failed, not that it worked")
  void aNodeWithNoEngineFails() {
    service.attachBackup(null);
    assertEquals(ExperienceBackup.Outcome.FAILED, backup(owner));
    assertTrue(bus.updatesFor(experience.id()).isEmpty());
  }

  @Test
  @DisplayName("the per-experience cap is enforced before anything is copied, and deletes nothing")
  void theCapIsEnforcedWithoutTouchingTheEngine() {
    int cap = service.maxBackupsPerExperience();
    assertEquals(ExperienceService.DEFAULT_MAX_BACKUPS_PER_EXPERIENCE, cap);
    for (int index = 0; index < cap; index++) {
      assertNotNull(experiences.createBackup(experience, "Random Drops (backup " + index + ")",
          System.currentTimeMillis()), "the fixture needs " + cap + " stored backups");
    }
    assertEquals(cap, experiences.countBackupsOf(experience.id()));

    assertEquals(ExperienceBackup.Outcome.LIMIT_REACHED, backup(owner));
    assertTrue(engine.asked.isEmpty());
    assertEquals(cap, experiences.countBackupsOf(experience.id()),
        "hitting the cap refuses the NEW copy; it never makes room by deleting an old one");
  }

  @Test
  @DisplayName("a backup is never copied itself: a copy of a copy is refused before the engine")
  void aBackupIsNotBackedUp() {
    ExperienceManager.Experience copy =
        experiences.createBackup(experience, "Random Drops (backup)", System.currentTimeMillis());
    assertNotNull(copy);
    assertTrue(copy.isBackup());

    List<ExperienceBackup.Outcome> outcomes = new ArrayList<>();
    service.backup(owner, copy.id(), outcomes::add);

    assertEquals(List.of(ExperienceBackup.Outcome.LIMIT_REACHED), outcomes);
    assertTrue(engine.asked.isEmpty(),
        "a copy of a copy is indistinguishable in the list, and the source it names is a world its"
            + " owner may delete tomorrow");
  }

  @Test
  @DisplayName("backups do not spend the per-player world allowance")
  void backupsDoNotCountAgainstThePerPlayerCap() {
    int before = experiences.countByOwner(owner);
    assertNotNull(experiences.createBackup(experience, "Random Drops (backup)",
        System.currentTimeMillis()));
    assertEquals(before, experiences.countByOwner(owner),
        "a backup is not a world the player chose to make; charging them for it turns 'keep a safety"
            + " copy' into 'give up a world slot'");
  }
}
