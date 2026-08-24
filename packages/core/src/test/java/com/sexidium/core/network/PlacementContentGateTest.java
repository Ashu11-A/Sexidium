package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader-side content gate, and the pin that must survive it.
 *
 * <p>The gap being closed: {@code adoptableHere} is handed a world key and a placement row and never
 * saw the challenge list, so a node one build behind cheerfully adopted a world whose challenges it
 * does not have — and then answered "no, this does not need a void world" to the four world-shaping
 * questions, generating <b>normal terrain over an empty SkyBlock</b>. Silently, permanently, and
 * reachable during any roll.</p>
 */
class PlacementContentGateTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  private static final long TIMEOUT = 30_000L;
  private static final String WORLD = "experiences/lookworld_ab12";

  @TempDir
  Path tmp;

  private Database database;

  @BeforeEach
  void setUp() throws Exception {
    database = new Database(new File(tmp.toFile(), "content.db"));
  }

  private NodeIdentity identity(String nodeId, String role) {
    return NodeIdentity.of(nodeId, nodeId, NodeIdentity.capabilitiesForRole(role));
  }

  private DbWorldLeaseAuthority placements(String nodeId, long epoch) {
    return new DbWorldLeaseAuthority(database, SILENT, identity(nodeId, "worker"), epoch, 15_000L);
  }

  /** An idle, materialised, persistent placement homed on somebody else — the adoptable shape. */
  private Optional<DbWorldLeaseAuthority.Placement> idleElsewhere() {
    return Optional.of(new DbWorldLeaseAuthority.Placement(
        WORLD, DbWorldLeaseAuthority.KIND_PERSISTENT, "worker-2", 4242L, null,
        DbWorldLeaseAuthority.STATE_IDLE, 0, 0L));
  }

  @Test
  @DisplayName("a node missing the world's challenges does NOT adopt it, even with the folder here")
  void doesNotAdoptWorldRequiringMissingContent() {
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    registry.heartbeat(0, 0);
    PlacementPlanner planner = new PlacementPlanner(
        placements("worker-1", registry.epoch()),
        new NodePlacementPlanner(registry, identity("worker-1", "worker")),
        identity("worker-1", "worker"),
        key -> true,                       // the folder IS here
        key -> false,                      // ...but this node cannot run what is in it
        key -> List.of("c:lookmultiplies"));

    assertEquals("worker-2", planner.plan(WORLD, idleElsewhere()),
        "opening it WRONG is far worse than leaving it shut: the world is never deleted and never"
            + " regenerated, it simply waits for a node that has the content");
  }

  @Test
  @DisplayName("a node that CAN run the world adopts it exactly as before")
  void adoptsWorldItCanRun() {
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    registry.heartbeat(0, 0);
    PlacementPlanner planner = new PlacementPlanner(
        placements("worker-1", registry.epoch()),
        new NodePlacementPlanner(registry, identity("worker-1", "worker")),
        identity("worker-1", "worker"),
        key -> true, key -> true, key -> List.of("c:lookmultiplies"));

    assertEquals("worker-1", planner.plan(WORLD, idleElsewhere()));
  }

  @Test
  @DisplayName("REGRESSION GUARD: a minigame's temp world is never adopted, however capable the node")
  void temporaryWorldsAreNeverAdopted() {
    // R8 asks that an incompatible worker never take over a minigame. Today that holds by
    // CONSTRUCTION rather than by a check: a minigame runs in a KIND_TEMP world, `adoptableHere`
    // requires KIND_PERSISTENT, and a drain ENDS matches (`handOverWorld` -> `endMatch`) instead of
    // moving them. So there is no takeover path to gate, and adding a `choose(MINIGAMES, codes)`
    // call today would be unreachable code — which is exactly how the content gate came to sit dead
    // for a whole release.
    //
    // This pins the invariant the guarantee actually rests on. If someone later teaches the drain to
    // MOVE a live match, this test fails and they have to decide the gating deliberately rather than
    // inherit a silent hole.
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    registry.heartbeat(0, 0);
    PlacementPlanner planner = new PlacementPlanner(
        placements("worker-1", registry.epoch()),
        new NodePlacementPlanner(registry, identity("worker-1", "worker")),
        identity("worker-1", "worker"),
        key -> true, key -> true, key -> List.of());

    // Lease VIVA: é isso que distingue "worker-2 está rodando esta partida" de "worker-2 morreu e
    // deixou lixo". O segundo caso é re-planejável de propósito -- um mundo temporário de um nó
    // morto é sobra a recolher, não partida a roubar --, e foi exatamente o que a primeira versão
    // deste teste afirmou errado.
    Optional<DbWorldLeaseAuthority.Placement> temp = Optional.of(
        new DbWorldLeaseAuthority.Placement(
            WORLD, DbWorldLeaseAuthority.KIND_TEMP, "worker-2", 4242L, null,
            DbWorldLeaseAuthority.STATE_LOADED, 1, System.currentTimeMillis() + 60_000L));

    assertEquals("worker-2", planner.plan(WORLD, temp),
        "a live minigame belongs to the node running it: no peer may take the world out from under it");
  }

  @Test
  @DisplayName("REGRESSION GUARD: adoptableHere still does not sort by load")
  void adoptableHereStillDoesNotSortByLoad() {
    // worker-1 is the BUSIEST node in the network by a wide margin. It is also the one being asked,
    // its disk has the folder, and it can run the content — so it serves the world. Introducing a
    // load comparison here re-plans the home on every entry over inputs every entry changes, which
    // was observed live as one world announced on worker-2 and, seconds later, on worker-1.
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    registry.heartbeat(90, 12);
    new NodeRegistry(database, SILENT, identity("worker-2", "worker"), TIMEOUT).heartbeat(0, 0);
    new NodeRegistry(database, SILENT, identity("worker-3", "worker"), TIMEOUT).heartbeat(0, 0);

    PlacementPlanner planner = new PlacementPlanner(
        placements("worker-1", registry.epoch()),
        new NodePlacementPlanner(registry, identity("worker-1", "worker")),
        identity("worker-1", "worker"),
        key -> true, key -> true, key -> List.of());

    assertEquals("worker-1", planner.plan(WORLD, idleElsewhere()),
        "whoever asks, serves. The player is already connected to this worker; it opens the world"
            + " and nobody is transferred.");
  }

  @Test
  @DisplayName("the lobby is still kept out, content gate or no content gate")
  void lobbyStillNeverAdopts() {
    NodeRegistry registry = new NodeRegistry(database, SILENT, identity("lobby", "lobby"), TIMEOUT);
    registry.heartbeat(0, 0);
    new NodeRegistry(database, SILENT, identity("worker-2", "worker"), TIMEOUT).heartbeat(0, 0);

    PlacementPlanner planner = new PlacementPlanner(
        placements("lobby", registry.epoch()),
        new NodePlacementPlanner(registry, identity("lobby", "lobby")),
        identity("lobby", "lobby"),
        key -> true, key -> true, key -> List.of());

    assertEquals("worker-2", planner.plan(WORLD, idleElsewhere()));
  }

  @Test
  @DisplayName("a NEW world is only planned onto a peer that publishes the content it needs")
  void plansNewWorldOnlyOntoACapablePeer() {
    NodeRegistry mine = new NodeRegistry(database, SILENT, identity("lobby", "lobby"), TIMEOUT);
    mine.heartbeat(0, 0);

    NodeRegistry poor = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    poor.publishTelemetry(new NodeRegistry.Telemetry(null, null, "aaaa", "c:doubledrops", 0, 0));
    poor.heartbeat(0, 0);
    NodeRegistry rich = new NodeRegistry(database, SILENT, identity("worker-2", "worker"), TIMEOUT);
    rich.publishTelemetry(new NodeRegistry.Telemetry(
        null, null, "bbbb", "c:doubledrops,c:lookmultiplies", 0, 0));
    rich.heartbeat(9, 9);   // and it is the BUSIER of the two, so load alone would not pick it

    PlacementPlanner planner = new PlacementPlanner(
        placements("lobby", mine.epoch()),
        new NodePlacementPlanner(mine, identity("lobby", "lobby")),
        identity("lobby", "lobby"),
        key -> false, key -> true, key -> List.of("c:lookmultiplies"));

    assertEquals("worker-2", planner.plan(WORLD, Optional.empty()),
        "planning a world onto a node that cannot run it is how a save gets generated over");
  }

  @Test
  @DisplayName("a peer that has published NO content set is unknown, not empty, and stays a candidate")
  void aPeerThatPublishedNothingIsStillACandidate() {
    NodeRegistry mine = new NodeRegistry(database, SILENT, identity("lobby", "lobby"), TIMEOUT);
    mine.heartbeat(0, 0);
    // Exactly what a node on the previous build writes: nothing at all.
    new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT).heartbeat(0, 0);

    NodePlacementPlanner planner = new NodePlacementPlanner(mine, identity("lobby", "lobby"));

    assertEquals(Optional.of("worker-1"),
        planner.choose(NodeCapability.EXPERIENCES, List.of("c:lookmultiplies")),
        "reading silence as 'has nothing' would empty the candidate list on the first roll of a"
            + " fleet that has never published a manifest — and a drain with no candidates stalls");
  }

  @Test
  @DisplayName("a peer below this build's MIN_PEER is not offered ownership")
  void skipsPeerBelowMinPeerProtocol() {
    NodeRegistry mine = new NodeRegistry(database, SILENT, identity("lobby", "lobby"), TIMEOUT,
        new ProtocolTag(2L, 2L));
    mine.heartbeat(0, 0);
    // Two builds, one JVM, one database — the whole point of injecting the tag.
    new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT,
        new ProtocolTag(1L, 1L)).heartbeat(0, 0);

    NodePlacementPlanner planner =
        new NodePlacementPlanner(mine, identity("lobby", "lobby"), new ProtocolTag(2L, 2L));
    assertTrue(planner.choose(NodeCapability.EXPERIENCES).isEmpty(),
        "worker-1 is at protocol 1 and this build's floor is 2: it stays routable and keeps its"
            + " players, it is only excluded as an ownership TARGET");

    new NodeRegistry(database, SILENT, identity("worker-2", "worker"), TIMEOUT,
        new ProtocolTag(3L, 2L)).heartbeat(0, 0);
    assertEquals(Optional.of("worker-2"), planner.choose(NodeCapability.EXPERIENCES),
        "a peer with a HIGHER tag is fully usable: it knows its own floor and refuses us if we are"
            + " too old");
  }

  @Test
  @DisplayName("a ticket for a world PLANNED onto a peer is addressed to that peer's live epoch")
  void routeUsesRegistryEpochWhenPlacementEpochIsZero() {
    NodeRegistry mine = new NodeRegistry(database, SILENT, identity("lobby", "lobby"), TIMEOUT);
    mine.heartbeat(0, 0);
    NodeRegistry owner = new NodeRegistry(database, SILENT, identity("worker-1", "worker"), TIMEOUT);
    owner.heartbeat(0, 0);

    NodePlacementPlanner planner = new NodePlacementPlanner(mine, identity("lobby", "lobby"));

    assertEquals(owner.epoch(), planner.ownerEpoch(0L, "worker-1"),
        "claimOn writes node_epoch = 0 for a REMOTE plan, and TransferTicket.addressedTo treats 0"
            + " as 'any node, any boot' — so the fence was inert for exactly the worlds a roll"
            + " moves, and a worker that restarted mid-flight consumed its predecessor's ticket");
    assertEquals(4242L, planner.ownerEpoch(4242L, "worker-1"),
        "a recorded epoch is authoritative and is never second-guessed");
    assertEquals(0L, planner.ownerEpoch(0L, "worker-9"),
        "an unregistered owner falls back to the old behaviour: an unfenced transfer still routes,"
            + " and that beats no transfer at all");
  }
}
