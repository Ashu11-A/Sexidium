package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.platform.noop.NoopWorldLeaseService;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A SHARED map has to show the owner's latest state, and the row is what "latest" means.
 *
 * <p>The registry never cached anything — every read here is a query — so the browse list was
 * already right the moment it was reopened. What was wrong was everything DOWNSTREAM of the row, and
 * neither half could even find out that a change had happened: the world that is RUNNING composed
 * its challenges once at start, on whichever worker holds it, and the menus sit in the LOBBY, which
 * is where the owner is editing from. Nothing published {@code experience.updated} and nothing
 * subscribed to it.</p>
 *
 * <p>So what these pin is the notification, not the query: every owner action announces the row it
 * changed, the announcement is acted on locally as well as sent (the bus never echoes to the
 * publisher, and the node the owner edits from is the node the other viewers are on), and a peer's
 * announcement is acted on exactly once.</p>
 */
class SharedExperienceFreshnessTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** One published message, so a test can assert the topic AND what it was keyed by. */
  private record Published(String topic, String key, String payload) {
  }

  /** A bus that keeps what it was handed. Subscription is not exercised: the core wires that. */
  private static final class RecordingBus implements NetworkBus {
    private final List<Published> published = new ArrayList<>();

    @Override public void publish(String topic, String key, String payload) {
      published.add(new Published(topic, key, payload));
    }

    @Override public AutoCloseable subscribe(String topic, BusListener listener) {
      return () -> { };
    }

    @Override public void start() { }

    @Override public void close() { }

    List<Published> updatesFor(String experienceId) {
      List<Published> matching = new ArrayList<>();
      for (Published message : published) {
        if (NetworkBus.Topics.EXPERIENCE_UPDATED.equals(message.topic())
            && experienceId.equals(message.key())) {
          matching.add(message);
        }
      }
      return matching;
    }
  }

  /**
   * A world layer that only answers the one question the delete path asks it. {@code false} is the
   * production meaning of "the folder is still there", and the service must not forget the row then.
   */
  private final class AnsweringWorlds implements WorldLeaseService {
    @Override public boolean deletePersistent(WorldKey key) { return worldDeletes; }

    @Override public boolean enabled() { return false; }

    @Override public void start() { }

    @Override public void preserve(java.util.Collection<String> worldNames) { }

    @Override public java.util.Optional<com.sexidium.core.platform.WorldLease> reacquireByName(
        String worldName) {
      return java.util.Optional.empty();
    }

    @Override public void discardByName(String worldName) { }

    @Override public String lobbyName() { return "lobby"; }

    @Override public Path worldRoot() { return tmp; }

    @Override public Path tempSubdir() { return tmp.resolve("temp"); }

    @Override public java.util.Optional<com.sexidium.core.platform.model.WorldPosition> lobbySpawn() {
      return java.util.Optional.empty();
    }

    @Override public java.util.Optional<com.sexidium.core.platform.WorldLease> acquireReady(
        com.sexidium.core.world.WorldProfile profile) {
      return java.util.Optional.empty();
    }

    @Override public void acquireOrCreate(
        java.util.Collection<? extends com.sexidium.core.platform.PlayerAdapter> viewers,
        java.util.function.Consumer<com.sexidium.core.platform.WorldLease> onReady,
        Runnable onFailure) {
      if (onFailure != null) {
        onFailure.run();
      }
    }

    @Override public void shutdown() { }
  }

  @TempDir
  Path tmp;

  private Database database;
  private ExperienceManager experiences;
  private ExperienceManager.Experience experience;
  private ExperienceService service;
  private RecordingBus bus;
  private final List<String> watched = new ArrayList<>();
  private final UUID owner = UUID.randomUUID();
  /** Whether the (fake) world layer agrees to delete a world folder. */
  private boolean worldDeletes = true;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "shared.db"));
    experiences = new ExperienceManager(SILENT, database);
    experience = experiences.create(owner, "Ashu11a", List.of("randomdrops"), "Random Drops",
        System.currentTimeMillis());
    assertNotNull(experience, "the fixture needs a stored experience");

    WorldLeaseService worlds = new AnsweringWorlds();
    TestServerAdapter server = new TestServerAdapter() {
      @Override public WorldLeaseService worlds() { return worlds; }
      @Override public LoggerAdapter logger() { return SILENT; }
    };
    GameManager games = new GameManager(
        new GameContext(server, new NoopKitAdapter(), RankAwardPort.noop()), new GameRegistry(), null);
    service = new ExperienceService(server, games, experiences, null, null);
    bus = new RecordingBus();
    service.setUpdateChannel(bus, "lobby-1", watched::add);
  }

  @Test
  @DisplayName("every owner action announces the row it changed, on the bus and here")
  void everyOwnerActionAnnouncesTheRow() {
    assertTrue(service.setVisibility(owner, experience.id(), true));
    assertTrue(service.rename(owner, experience.id(), "Shared Chaos"));
    assertTrue(service.updateChallengesLive(owner, experience.id(), List.of("randommob")));
    assertTrue(service.setKeepInventory(owner, experience.id(), false));
    assertTrue(service.setHardcore(owner, experience.id(), true));
    assertTrue(service.updateWorldType(owner, experience.id(), ExperienceWorldType.NETHER));

    assertEquals(6, bus.updatesFor(experience.id()).size(),
        "a change nobody is told about is exactly the bug: the row moved and the world and the "
            + "menus showing it did not");
    assertEquals(6, watched.size(),
        "the local half matters most — the owner edits from the LOBBY, which is where the other "
            + "players' menus are, and the bus never delivers a node its own publications");
    assertTrue(bus.updatesFor(experience.id()).get(0).payload().contains("by=lobby-1"),
        "the announcement names its author so the author does not act on it twice");
  }

  @Test
  @DisplayName("an action that is refused announces nothing")
  void arefusedActionAnnouncesNothing() {
    UUID stranger = UUID.randomUUID();
    assertFalse(service.setVisibility(stranger, experience.id(), true));
    assertFalse(service.rename(stranger, experience.id(), "Not Yours"));
    assertFalse(service.setHardcore(stranger, experience.id(), true));

    assertTrue(bus.updatesFor(experience.id()).isEmpty(),
        "announcing a change that never happened would make every node re-read for nothing");
    assertTrue(watched.isEmpty());
  }

  @Test
  @DisplayName("a completed delete announces itself, so a browser stops offering a dead world")
  void aDeleteAnnouncesItself() {
    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, experience.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.DELETED), outcomes);
    assertNull(experiences.get(experience.id()));
    assertEquals(1, bus.updatesFor(experience.id()).size(),
        "clicking a map whose world is gone is a guaranteed NOT_FOUND; the list has to lose it");
    assertTrue(bus.updatesFor(experience.id()).get(0).payload().contains("field=deleted"));
  }

  @Test
  @DisplayName("a delete the world layer refuses keeps the row and says nothing")
  void arefusedDeleteAnnouncesNothing() {
    worldDeletes = false;
    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, experience.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.REFUSED), outcomes);
    assertNotNull(experiences.get(experience.id()),
        "the row is what keeps the world reachable for a second attempt");
    assertTrue(bus.updatesFor(experience.id()).isEmpty());
  }

  @Test
  @DisplayName("a node acts on a peer's announcement, and never on its own echo")
  void ownEchoesAreIgnored() {
    // The standalone bus is in-process and DOES deliver a publication back to its publisher.
    service.onExperienceUpdated(experience.id(), "field=name;by=lobby-1");
    assertTrue(watched.isEmpty(), "applying it twice is how a redraw loop starts");

    service.onExperienceUpdated(experience.id(), "field=name;by=worker-2");
    assertEquals(List.of(experience.id()), watched,
        "this is the whole point: the owner edited on another node and this one has to re-read");
  }

  /**
   * The claim the whole design rests on: the registry is the source of truth and holds no cache, so a
   * second reader — another node, in production — sees an owner's change with no invalidation at all.
   * The announcement exists for the two consumers that read the row ONCE (a running world, an open
   * menu), not for the queries.
   */
  @Test
  @DisplayName("a second reader sees the owner's latest name and visibility with no invalidation")
  void theRegistryIsTheSourceOfTruth() {
    ExperienceManager viewerSide = new ExperienceManager(SILENT, database);
    UUID viewer = UUID.randomUUID();

    assertTrue(viewerSide.publicExperiencesExcluding(viewer).isEmpty(),
        "a private world is nobody else's business");

    service.setVisibility(owner, experience.id(), true);
    service.rename(owner, experience.id(), "Shared Chaos");

    List<ExperienceManager.Experience> visible = viewerSide.publicExperiencesExcluding(viewer);
    assertEquals(1, visible.size());
    assertEquals("Shared Chaos", visible.get(0).displayName());

    service.setVisibility(owner, experience.id(), false);
    assertTrue(viewerSide.publicExperiencesExcluding(viewer).isEmpty(),
        "un-sharing has to take effect too, or private means nothing");
  }

  @Test
  @DisplayName("updated_at moves on every mutation, because it is the version a live world compares")
  void theRowCarriesAVersion() {
    long created = experiences.updatedAt(experience.id());
    assertTrue(created > 0);

    experiences.rename(experience.id(), "Later", created + 1_000L);
    assertEquals(created + 1_000L, experiences.updatedAt(experience.id()));

    assertEquals(0L, experiences.updatedAt("no-such-experience"),
        "a missing row must read as 'no version', never as somebody else's");
  }

  /**
   * The reconcile sits on the ENTRY path, so its cheap answers have to be the common ones: nothing
   * running here (every node but one), a Chaos world (no stored set to apply — applying the empty
   * list would strip its twists) and a lost hardcore world (frozen; its settings are the record of a
   * run that is over).
   */
  @Test
  @DisplayName("reconciling a world nobody is running here changes nothing")
  void reconcilingWithoutALiveWorldIsANoOp() {
    assertFalse(service.reconcileLive(experience.id()));
    assertFalse(service.reconcileLive("no-such-experience"));
    assertFalse(service.reconcileLive(null));

    ExperienceManager.Experience chaos = experiences.create(owner, "Ashu11a", List.of(),
        "Chaos", ExperienceManager.Experience.MODE_CHAOS, System.currentTimeMillis());
    assertNotNull(chaos);
    assertFalse(service.reconcileLive(chaos.id()));

    experiences.markDead(experience.id(), System.currentTimeMillis());
    assertFalse(service.reconcileLive(experience.id()));
  }
}
