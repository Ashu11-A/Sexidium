package com.sexidium.core.game.experience;

import com.sexidium.core.network.ExperienceCommandStore;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Owner actions that have to happen where the world is, not where the owner is.
 *
 * <p>Six call sites each did the same wrong thing: {@code games.matchByWorldKey(...)} and then act
 * <em>only if the match is on this node</em>. The manage menu lives in the lobby, and the lobby never
 * holds an experience world — so from the one place an owner can actually reach these controls, every
 * one of them silently did nothing to the running world:</p>
 *
 * <ul>
 *   <li>{@code updateChallenges} persisted the new set and its "end the match so it restarts clean"
 *       was a no-op, so the world kept running the old challenges until something else ended it.</li>
 *   <li>{@code updateChallengesLive}, {@code setKeepInventory} and {@code setHardcore} wrote the row
 *       and left the live world untouched — the toggle appeared to work and changed nothing.</li>
 *   <li><b>{@code delete} was worse than a no-op.</b> Its only protection was a LOCAL loaded-world
 *       lookup, which on the lobby returns empty for a world worker-2 is running — so it fell through
 *       to deleting the shared folder out from under a live world. That is F-A2, and it is data loss,
 *       not a missed update.</li>
 * </ul>
 *
 * <p>One seam instead of six call-site patches: ask the locator where the world lives, run it here if
 * that is here, publish it to the holder if it is not.</p>
 *
 * <h2>DELETE is a request, not a broadcast</h2>
 *
 * <p>The first version of this routed on the LIVE lease and treated "sent" as "done", and both halves
 * were wrong for delete. A world nobody has open answers no holder, so a delete from the lobby fell
 * back to running <em>here</em> — where invariant I3 refuses it, because the lobby deliberately has no
 * {@code EXPERIENCES} capability. The refusal was honest all the way up and both callers threw it away,
 * so the owner was told "Experience deleted." about a world that is still on disk. And when a worker
 * <em>was</em> holding it, the answer was a fire-and-forget publish onto a bus that has no ack, no
 * retry and no redelivery across a restart.</p>
 *
 * <p>So the ops split in two. The live edits keep the old shape — they only make sense against a world
 * somebody has open, and a lost one costs nothing. {@link Op#DELETE} routes on the world's recorded
 * HOME ({@code world_placements.node_id}, a durable assignment that outlives the lease), is written to
 * {@link ExperienceCommandStore} before it is announced, and is answered on
 * {@link NetworkBus.Topics#EXPERIENCE_COMMAND_RESULT} — with the row, not the message, as the truth.
 * The bus is the doorbell; the table is the promise.</p>
 */
public final class ExperienceCommandRouter {

  /** How long a requester waits for an answer before telling the player it was queued. */
  private static final long ACK_TIMEOUT_MILLIS = 8_000L;

  /**
   * How long a request stays worth running.
   *
   * <p>Deliberately a day and not the ack timeout: the owner stops waiting after seconds, but a
   * worker that is down for a rolling update must still find the delete and run it when it comes
   * back. Expiring on the owner's patience would turn "your map will be deleted" into a lie.</p>
   */
  private static final long REQUEST_TTL_MILLIS = 24L * 60L * 60L * 1000L;

  /** How often a node re-reads the requests addressed to it. The bus is the fast path; this is the net. */
  private static final long DRAIN_INTERVAL_MILLIS = 5_000L;

  /** How often ANY node clears out requests whose deadline has passed. */
  private static final long EXPIRY_INTERVAL_MILLIS = 5L * 60L * 1000L;

  /**
   * How often a node re-stamps the rows it is still working on.
   *
   * <p>Ten times inside {@link ExperienceCommandStore#RECLAIM_AFTER_MILLIS}, so a row is reclaimable
   * only after several heartbeats in a row have failed to land — which is the shape of a node that is
   * gone, and not of one that missed a tick. It costs one UPDATE per DEFERRED request per half minute,
   * and a node with nothing deferred issues none at all.</p>
   */
  private static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

  /** What can be asked of the node holding a world. */
  public enum Op {
    /** End any live match, delete the world folder, and forget the registry row. */
    DELETE(true),
    /** End the live match so the next entry restarts it cleanly. */
    END_MATCH(false),
    /** Apply a new challenge set to the running world. */
    SET_CHALLENGES(false),
    /** Push the keep-inventory gamerule to every dimension of the running world. */
    SET_KEEP_INVENTORY(false),
    /** Apply hardcore to the running world (and to its clients). */
    SET_HARDCORE(false),
    /**
     * Copy the world folder — all three dimensions — plus every per-player save and challenge counter
     * into a brand-new experience of its own.
     *
     * <p>Routed like a delete and for the same reason: it READS the folder, and the owner clicks it
     * from the lobby, which never holds an experience world. Nothing about "the world nobody has open"
     * makes it copyable from here — the bytes are on the holder's disk.</p>
     */
    BACKUP(true),
    /**
     * Swap which folder a backup and its source name, making the copy the live world.
     *
     * <p>It moves no bytes at all, and it is STILL a folder op: it must run where the folders are, so
     * the executor can prove both are present and neither is open before it rewrites the rows.</p>
     */
    RESTORE(true),
    /** Take a backup again from its source: a full re-copy, then a re-point, then a delete. */
    REFRESH(true),
    /** Copy a world into a new playable experience that is nobody's backup. */
    DUPLICATE(true);

    private final boolean folder;

    Op(boolean folder) {
      this.folder = folder;
    }

    /**
     * Whether this op touches the world FOLDER rather than a running match.
     *
     * <p>Those two need different targets. A live edit is only meaningful where the world is open; a
     * delete — or a backup, which reads the same bytes — has to reach the node that physically holds
     * the folder, open or not.</p>
     *
     * <p>An explicit field rather than an identity check, because this gate guards destructive work:
     * a new op that forgets to be listed here would be routed at the LIVE lease and silently run on
     * whichever node happens to have the world open, or on none at all.</p>
     */
    boolean touchesTheFolder() {
      return folder;
    }

    static Optional<Op> parse(String value) {
      if (value == null) {
        return Optional.empty();
      }
      try {
        return Optional.of(valueOf(value.trim().toUpperCase(Locale.ROOT)));
      } catch (IllegalArgumentException unknown) {
        return Optional.empty();
      }
    }
  }

  /** What happened. {@code routed} means another node was asked and has not answered yet. */
  public record Result(boolean applied, boolean routed, boolean unrouted, String requestId,
      String detail) {

    public static Result here() {
      return new Result(true, false, false, null, "applied here");
    }

    public static Result routed(String nodeId) {
      return routed(nodeId, null);
    }

    public static Result routed(String nodeId, String requestId) {
      return new Result(false, true, false, requestId, "sent to '" + nodeId + "'");
    }

    public static Result refused(String why) {
      return new Result(false, false, false, null, why);
    }

    /**
     * No node could be asked at all: nothing has ever recorded where this world lives.
     *
     * <p>Distinct from {@link #refused} on purpose — a caller can finish a delete for a world that
     * has no home, because a world with no placement row has no folder on anybody's disk.</p>
     */
    public static Result unrouted(String why) {
      return new Result(false, false, true, null, why);
    }

    /** The holder answered, and the answer was no. */
    public static Result declined(String why) {
      return new Result(false, false, false, null, why);
    }

    /** Nobody answered in time. The request is still on the table and will still be run. */
    public static Result queued(String nodeId, String requestId) {
      return new Result(false, true, false, requestId,
          "'" + nodeId + "' has not answered yet; the request stands");
    }

    /** Whether the caller may treat this as a success from the player's point of view. */
    public boolean ok() {
      return applied || routed;
    }

    /**
     * The machine-readable code the node that ran this recorded for it, if it recorded one.
     *
     * <p>The known limitation of {@link LocalExecutor} is that it answers in a boolean, so a peer
     * cannot say WHY it refused — and "the disk is full" and "your friend is still standing in it"
     * are completely different instructions to the owner. This is the additive fix: a handler that
     * knows the reason writes it through {@link ReasonReader}, {@code detail} becomes
     * {@code "<CODE>: <sentence>"}, and the code survives the bus answer AND the
     * {@code experience_commands.detail} column a later read-back uses. Nothing existing changes
     * shape, and the column becomes readable to an operator as a bonus.</p>
     */
    public Optional<String> reason() {
      if (detail == null) {
        return Optional.empty();
      }
      int mark = detail.indexOf(": ");
      if (mark <= 0) {
        return Optional.empty();
      }
      String code = detail.substring(0, mark);
      for (int index = 0; index < code.length(); index++) {
        char current = code.charAt(index);
        if ((current < 'A' || current > 'Z') && current != '_') {
          return Optional.empty();
        }
      }
      return Optional.of(code);
    }
  }

  /**
   * How the local handler tells the router why it just answered the way it did.
   *
   * <p>Asked once, immediately after {@link LocalExecutor#apply}, so a SYNCHRONOUS refusal carries its
   * code and an asynchronous one does not — which is exactly right, because "still running" is
   * precisely the queued case and has no reason yet.</p>
   */
  @FunctionalInterface
  public interface ReasonReader {
    /** The code a local handler recorded for the op it just ran, or null. */
    String reasonFor(String experienceId, Op op, Map<String, String> args);
  }

  /** Runs an op against the world on THIS node. */
  @FunctionalInterface
  public interface LocalExecutor {
    boolean apply(String experienceId, Op op, Map<String, String> args);
  }

  /**
   * A local handler's promise to answer for a request whose work outlives {@link LocalExecutor#apply}.
   *
   * <p>{@code apply} returns a boolean the moment it has handed the work off, and for the copy verbs
   * that is seconds to minutes before the bytes land. The row used to be marked DONE right there —
   * terminal, because {@code pendingFor} only ever hands back PENDING rows and RUNNING ones a node
   * abandoned — so a copy that failed AFTER it started was recorded as a success and reported to
   * nobody: the late answer went into an {@code AtomicReference} the requester had already stopped
   * reading. A handler that takes one of these leaves the row RUNNING, which is what it actually is,
   * and completes it with the truth when the work ends.</p>
   */
  @FunctionalInterface
  public interface Completion {

    /**
     * The real answer. Called exactly once, from whatever thread the work finished on.
     *
     * @param applied whether the work succeeded
     * @param code the {@link ReasonReader} code to ride the detail column and the bus answer, or null
     */
    void complete(boolean applied, String code);
  }

  /** A request this node is waiting on, and who to tell when it is answered. */
  private record Waiting(String requestId, String targetNode, long deadline, Consumer<Result> onAck) {
  }

  /**
   * Where an op has to run — as three answers, not two.
   *
   * <p>A null node used to mean both "nothing records this world" and "I could not read the table",
   * and the delete path treats the first as permission to forget every row naming the world. Keeping
   * them apart is the whole reason this exists.</p>
   */
  private record Target(String nodeId, boolean known) {

    /** Read successfully, and no node holds it. Also the standalone answer: there is only here. */
    static Target none() {
      return new Target(null, true);
    }

    /** The placement table could not be read. */
    static Target unknown() {
      return new Target(null, false);
    }

    static Target node(String id) {
      return new Target(id, true);
    }

    /** Whether a node is positively recorded as holding the world. */
    boolean recorded() {
      return known && nodeId != null;
    }
  }

  /**
   * Who to ask when NOTHING records where a world lives.
   *
   * <p>Consulted in exactly one situation: an op that {@link Op#touchesTheFolder()}, on a node that
   * may not run one itself, for a world whose placement table was read cleanly and holds no row.
   * Those three conditions are what used to produce {@link Result#unrouted} — an answer that is
   * correct about the TABLE and wrong about the WORLD. A backup and a duplicate are born with
   * folders and no row (see {@code ExperienceBackupService.PlacementRecorder}), so from the lobby,
   * which is the one place an owner clicks Delete, their delete was refused for ever and the only
   * way out was to enter the world once — an instruction the owner had to read and act on to undo a
   * bookkeeping gap they never caused.</p>
   *
   * <p>Answers a node id, or null when there is nobody safe to ask: no capable node alive, or nodes
   * that do NOT share world storage — where a world's folder is on exactly one disk, and asking the
   * wrong node to delete it would have it delete nothing and report success, because a folder that
   * was never there deletes cleanly. Null keeps the old refusal, which stays recoverable.</p>
   */
  @FunctionalInterface
  public interface HomeFinder {

    /** A node that can do folder work for {@code key}, or null to leave the op unrouted. */
    String chooseHome(WorldKey key);
  }

  private final LoggerAdapter logger;
  private final LocalExecutor local;
  private volatile String nodeId;
  private volatile ExperienceLocator locator;
  /** The fallback host chooser. Null is the old behaviour: nothing recorded means nothing to ask. */
  private volatile HomeFinder homeFinder;
  private volatile NetworkBus bus;
  private volatile ExperienceCommandStore store;
  /**
   * Whether THIS node may open experience worlds at all (invariant I3's door, as a question).
   *
   * <p>Defaults to yes, which is standalone: one server holds every world it has. On a network the
   * lobby answers no, and that is what stops it running a delete it is then refused.</p>
   */
  private volatile BooleanSupplier canOpenExperiences = () -> true;
  /** Where a code comes from when the local handler has one. Null means "the boolean is all there is". */
  private volatile ReasonReader reasons;
  private final Map<String, Waiting> waiting = new ConcurrentHashMap<>();
  /**
   * The request being applied on THIS thread, so the handler inside {@code local.apply} can take it
   * over. A thread-local rather than a field because {@code run} is reachable from the drain tick and
   * from a bus delivery, and a handler must never be able to defer somebody else's request.
   */
  private final ThreadLocal<Deferral> applying = new ThreadLocal<>();
  /**
   * The requests this node has DEFERRED and is still working on, so {@link #tick} can keep their rows
   * from going stale underneath the work. Emptied by the {@link Completion}, whichever way it ends.
   */
  private final java.util.Set<String> deferred = ConcurrentHashMap.newKeySet();
  /** When the pending-row drain and the stale-row sweep last ran. Both are throttled, not per-tick. */
  private volatile long lastDrainAt;
  private volatile long lastExpiryAt;
  /** When the deferred rows were last re-stamped. */
  private volatile long lastHeartbeatAt;

  public ExperienceCommandRouter(LoggerAdapter logger, LocalExecutor local, String nodeId) {
    this.logger = logger;
    this.local = local;
    this.nodeId = nodeId == null ? "standalone" : nodeId;
  }

  /**
   * Wires the source of refusal codes. Additive: without it every answer is the boolean it always was.
   */
  public void attachReasons(ReasonReader reader) {
    this.reasons = reader;
  }

  /**
   * Wires the fallback host chooser. Additive: without it an unplaced world stays unroutable, which
   * is exactly the behaviour every caller had before.
   */
  public void attachHomeFinder(HomeFinder finder) {
    this.homeFinder = finder;
  }

  /** Wired by the core when networked. Without a locator everything runs locally — i.e. standalone. */
  public void attach(ExperienceLocator locator, NetworkBus bus, String nodeId) {
    attach(locator, bus, nodeId, null, null);
  }

  /**
   * The networked wiring, in full: where worlds live, how to reach the fleet, where requests are
   * recorded, and whether this node is allowed to run one itself.
   */
  public void attach(ExperienceLocator locator, NetworkBus bus, String nodeId,
      ExperienceCommandStore store, BooleanSupplier canOpenExperiences) {
    this.locator = locator;
    this.bus = bus;
    this.store = store;
    if (canOpenExperiences != null) {
      this.canOpenExperiences = canOpenExperiences;
    }
    if (nodeId != null && !nodeId.isBlank()) {
      this.nodeId = nodeId;
    }
  }

  /** Run an op where the world is, without waiting for an answer. */
  public Result execute(String experienceId, WorldKey key, Op op, Map<String, String> args) {
    return execute(experienceId, key, op, args, null);
  }

  /**
   * Run an op where the world is.
   *
   * <p>Runs locally when this node holds the world, when nothing holds it and this node is allowed to
   * open experience worlds, or when there is no network at all. Otherwise it is written down and sent,
   * and {@code onAck} is called exactly once with the terminal answer — applied, declined, or queued
   * because the node holding the world has not spoken within {@link #ACK_TIMEOUT_MILLIS}.</p>
   */
  public Result execute(String experienceId, WorldKey key, Op op, Map<String, String> args,
      Consumer<Result> onAck) {
    if (experienceId == null || op == null) {
      return answer(Result.refused("nothing to do"), onAck);
    }
    Map<String, String> arguments = args == null ? Map.of() : args;
    Target target = targetOf(op, key);
    if (op.touchesTheFolder() && !target.known()) {
      // "I could not read where this world lives" is not "this world lives nowhere", and the delete
      // path is the one place the difference is destructive: unrouted licenses the caller to forget
      // every row naming the world. A lock-wait timeout would cash that in over a world sitting
      // intact on a worker's disk.
      return answer(Result.refused("could not read where this world lives; nothing was changed"),
          onAck);
    }
    // Nothing records where this world lives AND this node may not do folder work itself. Before
    // refusing the owner for ever, ask who COULD -- see HomeFinder for why that is not the same
    // licence the `unrouted` answer used to hand out, and for the two cases where it answers null.
    String home = target.nodeId();
    if (home == null && op.touchesTheFolder() && !canOpenExperiences.getAsBoolean()) {
      home = chooseHome(key);
    }
    if (home == null || home.equals(nodeId)) {
      if (!op.touchesTheFolder() || canOpenExperiences.getAsBoolean()) {
        boolean applied = local.apply(experienceId, op, arguments);
        String code = reasonFor(experienceId, op, arguments);
        return answer(applied
            ? Result.here()
            : Result.declined(coded(code, "the local handler declined")), onAck);
      }
      // I3: this node may not open, and therefore may not delete, an experience world. Running it
      // here anyway is what produced "Experience deleted." over a world still on a worker's disk.
      //
      // Which of the two answers depends on WHY we got here, and conflating them was its own bug: a
      // worker that holds the world and has merely entered DRAINING is recorded as its home, and
      // telling the caller "nobody holds this" would have it forget the rows for a folder that very
      // much exists.
      return answer(target.recorded()
          ? Result.refused("'" + nodeId + "' holds this world but may not delete it right now")
          : Result.unrouted("no node is recorded as holding this world"), onAck);
    }
    NetworkBus channel = bus;
    if (channel == null) {
      return answer(Result.refused("no bus to reach '" + home + "'"), onAck);
    }
    String targetNode = home;
    String requestId = null;
    if (op.touchesTheFolder()) {
      requestId = record(experienceId, key, op, arguments, targetNode);
      if (requestId == null) {
        // Nothing was written down, so nothing may be promised.
        return answer(Result.refused("the request could not be recorded"), onAck);
      }
      if (!requestId.isBlank()) {
        Map<String, String> announced = new LinkedHashMap<>(arguments);
        announced.put("req", requestId);
        announced.put("from", nodeId);
        arguments = announced;
        if (onAck != null) {
          waiting.put(requestId, new Waiting(requestId, targetNode,
              System.currentTimeMillis() + ACK_TIMEOUT_MILLIS, onAck));
        }
      }
    }
    channel.publish(NetworkBus.Topics.EXPERIENCE_COMMAND, experienceId,
        encode(targetNode, op, arguments));
    logger.info("Sent " + op + " for experience " + experienceId + " to '" + targetNode
        + "', which is the node holding its world.");
    Result sent = Result.routed(targetNode, requestId);
    // Nothing was recorded (no table), so no answer is ever coming and the caller must be told now
    // rather than left waiting for one.
    return requestId == null || requestId.isBlank() ? answer(sent, onAck) : sent;
  }

  /**
   * A node that can do folder work for a world nothing records, or null to leave it unrouted.
   *
   * <p>Wrapped rather than called inline for the same reason every other unanswerable question on
   * this path is: a finder that threw has not named a node, and inventing one is how a delete reaches
   * a machine that cannot see the folder. A throw leaves the old refusal standing, which the owner
   * can retry and an operator can fix.</p>
   */
  private String chooseHome(WorldKey key) {
    HomeFinder finder = homeFinder;
    if (finder == null || key == null) {
      return null;
    }
    try {
      String chosen = finder.chooseHome(key);
      if (chosen == null || chosen.isBlank() || chosen.equals(nodeId)) {
        // Never ourselves. We are here precisely BECAUSE this node may not run the op, so taking the
        // work back would fail the door guard a second time and answer the owner with the same
        // refusal, having spent a fleet lookup on it.
        return null;
      }
      logger.info("Nothing records where '" + key.key() + "' lives, and '" + nodeId + "' may not do"
          + " folder work itself. Asking '" + chosen + "', which can host experience worlds and sees"
          + " the same storage, rather than refusing this for good.");
      return chosen;
    } catch (RuntimeException unavailable) {
      logger.warning("Could not choose a host for '" + key.key() + "': " + unavailable.getMessage()
          + ". Leaving the request unrouted, which is what it was before.");
      return null;
    }
  }

  /**
   * The node this op has to run on, or null.
   *
   * <p>Two different questions. A live edit needs the node with the world OPEN — a world nobody has
   * open has no in-memory state to change. A delete needs the node that HOLDS THE FOLDER, which is
   * {@code world_placements.node_id}: a durable assignment that stays true across a lapsed lease, and
   * the reason the lease filter must not be applied to it.</p>
   */
  private Target targetOf(Op op, WorldKey key) {
    ExperienceLocator directory = locator;
    if (directory == null || key == null) {
      // No locator is standalone: this node is the only node there is, so "nobody holds it" and
      // "we hold it" are the same sentence.
      return Target.none();
    }
    try {
      long now = System.currentTimeMillis();
      ExperienceLocator.Home home = directory.home(key);
      if (!home.known()) {
        return Target.unknown();
      }
      if (!home.recorded()) {
        return Target.none();
      }
      // A live edit is only meaningful where the world is OPEN, so a lapsed lease means there is
      // nothing running to edit. A delete is about the folder, which the recorded home owns whether
      // or not anybody has it open.
      return op.touchesTheFolder() || home.placement().leaseHeld(now)
          ? Target.node(home.nodeId())
          : Target.none();
    } catch (RuntimeException unavailable) {
      logger.warning("Could not locate the holder of " + key + ": " + unavailable.getMessage());
      return Target.unknown();
    }
  }

  /**
   * Whether some OTHER node has this world in its hands right now — the one question a node cannot
   * answer by looking at itself.
   *
   * <p>Every local answer to "is this world busy" is node-local and always was: {@code
   * experienceWorldLoaded} reads this JVM's loaded set and this node's {@code closing} set, {@code
   * matchRunning} reads this node's match map, and {@code experienceFolderPresent} reads a tree that on
   * the live deployment is a symlink into shared storage — the same device and inode from all three
   * Paper nodes, so it is true everywhere for everything and distinguishes nothing. The backup verbs
   * route on the SOURCE world's key, so the source is covered by the routing itself; the world on the
   * other end of a restore or a refresh is addressed by nobody, and this is the only thing that can
   * speak for it.</p>
   *
   * <p>Three answers folded into a boolean, and which way each folds is the whole safety argument:</p>
   *
   * <ul>
   *   <li><b>No locator is standalone.</b> This node is the only node there is, so nothing can be
   *       elsewhere and the local checks are the complete truth. False.</li>
   *   <li><b>The placement could not be read.</b> True — refuse. The caller's alternative is deleting
   *       or re-pointing a world it cannot locate; a refusal is a click the owner repeats, and there is
   *       no repeating a folder that has been removed under a player standing in it.</li>
   *   <li><b>Recorded on another node with a LAPSED lease.</b> False. The lease is renewed every few
   *       seconds for as long as the world is open <em>and</em> for the whole of an unload-with-save
   *       (that is what {@code closing} is for), so a lapsed one means the holder is gone — and a node
   *       that is gone is not writing to that folder. Folding this the other way would let one dead
   *       worker freeze a backup nobody else can ever touch again, forever, which is a data-loss shape
   *       of its own.</li>
   * </ul>
   */
  public boolean heldElsewhere(WorldKey key) {
    ExperienceLocator directory = locator;
    if (directory == null) {
      return false;
    }
    if (key == null) {
      return true; // cannot even name the world, so nothing may be said about who holds it
    }
    try {
      ExperienceLocator.Home home = directory.home(key);
      if (!home.known()) {
        logger.warning("Could not read where '" + key.key() + "' lives; treating it as held by"
            + " another node rather than acting on it from here.");
        return true;
      }
      if (!home.recorded()) {
        return false; // nothing has ever opened it, so nobody is holding it
      }
      // OUR OWN row is not "elsewhere": the local loaded/closing/match questions already answer for
      // this node, and they answer with more than the placement table knows.
      return !nodeId.equals(home.nodeId()) && home.placement().leaseHeld(System.currentTimeMillis());
    } catch (RuntimeException unavailable) {
      logger.warning("Could not read where '" + key.key() + "' lives: " + unavailable.getMessage()
          + ". Treating it as held by another node.");
      return true;
    }
  }

  /** The key a request carries with it, so a re-check does not depend on the registry row surviving. */
  private static WorldKey keyOf(Map<String, String> args) {
    String raw = args == null ? null : args.get("key");
    return raw == null || raw.isBlank() ? null : WorldKey.tryParse(raw).orElse(null);
  }

  /** Write the request down BEFORE announcing it. Returns its id, or null when it could not be. */
  private String record(String experienceId, WorldKey key, Op op, Map<String, String> args,
      String target) {
    ExperienceCommandStore table = store;
    if (table == null) {
      // No table (standalone, or a database that never came up). The bus is still better than
      // nothing, and the id being absent is what tells the caller not to promise an answer.
      return "";
    }
    long now = System.currentTimeMillis();
    String requestId = UUID.randomUUID().toString();
    boolean written = table.insert(new ExperienceCommandStore.Command(
        requestId, experienceId, key == null ? null : key.key(), op.name(), encodeArgs(args),
        nodeId, target, ExperienceCommandStore.State.PENDING, null,
        now + REQUEST_TTL_MILLIS, now, now));
    return written ? requestId : null;
  }

  /** Act on a command another node published, if it was addressed to us. */
  public void onMessage(String experienceId, String payload) {
    if (experienceId == null || payload == null) {
      return;
    }
    String[] parts = payload.split("\\|", 3);
    if (parts.length < 2 || !nodeId.equals(parts[0])) {
      return; // addressed elsewhere
    }
    Optional<Op> op = Op.parse(parts[1]);
    if (op.isEmpty()) {
      logger.warning("Ignoring an experience command with an unknown op: " + parts[1]);
      return;
    }
    Map<String, String> args = decode(parts.length > 2 ? parts[2] : "");
    run(experienceId, op.get(), args, args.get("req"), args.get("from"));
  }

  /**
   * Run one command here and answer for it.
   *
   * <p>The claim is what makes this idempotent: two deliveries of the same message (or a delivery
   * racing the pending-row sweep) both try to take the row PENDING → RUNNING and exactly one wins.
   * Without a request id there is nothing to claim and nothing to answer — that is the old
   * fire-and-forget shape, which the live edits still use.</p>
   */
  private void run(String experienceId, Op op, Map<String, String> args, String requestId,
      String requester) {
    ExperienceCommandStore table = store;
    if (op.touchesTheFolder() && !runnableHere(experienceId, op, args, requestId, table)) {
      return;
    }
    if (requestId != null && !requestId.isBlank() && table != null
        && table.claim(requestId, nodeId).isEmpty()) {
      return; // already claimed, already answered, or never addressed here
    }
    logger.info("Applying " + op + " for experience " + experienceId
        + ", requested by " + (requester == null ? "another node" : "'" + requester + "'") + ".");
    Deferral deferral = new Deferral(experienceId, op, requestId, requester);
    boolean applied;
    applying.set(deferral);
    try {
      applied = local.apply(experienceId, op, args);
    } finally {
      applying.remove();
    }
    // Read HERE, while the handler's answer is still the freshest thing that happened. A code recorded
    // now rides the detail column and the bus answer alike, which is the whole of the reason channel.
    // Read unconditionally, deferred or not, because reading is also what drains it.
    String code = reasonFor(experienceId, op, args);
    if (!applied && !deferral.deferred()) {
      logger.warning("The " + op + " for experience " + experienceId + " was declined locally"
          + (code == null ? "." : " (" + code + ")."));
    }
    if (requestId == null || requestId.isBlank()) {
      return;
    }
    if (deferral.deferred()) {
      // The handler took the request over: the work is still running, so the row stays RUNNING --
      // which is what it is -- and the answer is published when the work actually ends. Marking it
      // DONE here is what made a copy that failed after it started a success nobody could see.
      return;
    }
    answer(experienceId, op, requestId, requester, applied, code);
  }

  /**
   * Whether this node may still carry out a folder op it was addressed, asked at the moment it would
   * actually run rather than at the moment it was written down.
   *
   * <p>Those two moments can be a rolling update apart. The target is frozen into the row when the
   * owner clicks, and the row deliberately outlives the node being down — which is the whole point,
   * and also the danger: {@code world_placements.node_id} is not frozen. An idle world may be adopted
   * and opened by another worker while this one is restarting ({@code claim()} rewrites {@code
   * node_id}), so a delete that trusts its own address would remove the shared folder out from under a
   * live world. That is F-A2 again, relocated from the lobby to a stale worker.</p>
   *
   * <p>The three ways this answers no are all "not now", never "not ever": the row is left PENDING, so
   * the drain retries it, or is re-addressed to the node that has since taken the world. A folder op
   * that is <em>consumed</em> by a node that cannot run it is how "your map will be deleted when that
   * server is back" becomes a lie.</p>
   */
  private boolean runnableHere(String experienceId, Op op, Map<String, String> args,
      String requestId, ExperienceCommandStore table) {
    if (!canOpenExperiences.getAsBoolean()) {
      // Draining, or no EXPERIENCES capability. Claiming the row here would end the live match and
      // then be refused by invariant I3's door, leaving a terminal FAILED nothing retries.
      logger.info("Leaving " + op + " for experience " + experienceId + " on the table: '" + nodeId
          + "' may not open experience worlds right now. The request stands.");
      return false;
    }
    WorldKey key = keyOf(args);
    if (key == null) {
      // Nothing to re-check against: this is the old fire-and-forget shape, addressed to this node by
      // name and carrying no key. Every request written by this build carries one.
      return true;
    }
    Target current = targetOf(op, key);
    if (!current.known()) {
      logger.warning("Could not confirm that '" + nodeId + "' still holds the world of experience "
          + experienceId + "; leaving the " + op + " on the table rather than guessing.");
      return false;
    }
    if (current.recorded() && !nodeId.equals(current.nodeId())) {
      // The world changed hands while this request waited. Re-address it; the new home drains it.
      if (requestId != null && !requestId.isBlank() && table != null) {
        table.retarget(requestId, current.nodeId());
      }
      logger.warning("The world of experience " + experienceId + " is now held by '"
          + current.nodeId() + "', not '" + nodeId + "'. The " + op
          + " was re-addressed instead of being carried out here.");
      return false;
    }
    return true;
  }

  /** An answer another node published, if it was addressed to us. */
  public void onResult(String experienceId, String payload) {
    if (payload == null) {
      return;
    }
    String[] parts = payload.split("\\|", 5);
    if (parts.length < 4 || !nodeId.equals(parts[0])) {
      return; // addressed elsewhere
    }
    Waiting pending = waiting.remove(parts[1]);
    if (pending == null) {
      return; // nobody here is waiting: a duplicate, or the requester already gave up
    }
    boolean applied = "OK".equals(parts[3]);
    String detail = parts.length > 4 ? unescape(parts[4]) : parts[3];
    pending.onAck().accept(applied ? Result.here() : Result.declined(detail));
  }

  /**
   * The recovery tick: one second's worth of "did anything I was promised actually happen?".
   *
   * <p>Two jobs the bus cannot do. A requester whose answer never arrived reads the ROW — which is
   * the truth, and survives the message being dropped or the answering node restarting. And a node
   * that may open experience worlds drains the requests addressed to it, which is what makes a delete
   * published while it was down still happen when it comes back.</p>
   */
  public void tick() {
    tick(System.currentTimeMillis());
  }

  /** {@link #tick()} against a given clock, so "nobody answered in time" is a test and not a wait. */
  void tick(long now) {
    ExperienceCommandStore table = store;
    for (Waiting pending : new ArrayList<>(waiting.values())) {
      ExperienceCommandStore.Command row =
          table == null ? null : table.byId(pending.requestId()).orElse(null);
      if (row != null && row.terminal()) {
        if (waiting.remove(pending.requestId()) != null) {
          pending.onAck().accept(row.state() == ExperienceCommandStore.State.DONE
              ? Result.here()
              : Result.declined(row.detail() == null ? "it was not carried out" : row.detail()));
        }
        continue;
      }
      if (now >= pending.deadline() && waiting.remove(pending.requestId()) != null) {
        pending.onAck().accept(Result.queued(pending.targetNode(), pending.requestId()));
      }
    }
    // BEFORE the drain gate, and not subject to it. A node that has entered DRAINING may no longer
    // TAKE work, but it is still finishing the copy it took, and the row of that copy is exactly the
    // one that must not be handed to somebody else while the bytes are moving.
    if (table != null && !deferred.isEmpty() && now - lastHeartbeatAt >= HEARTBEAT_INTERVAL_MILLIS) {
      lastHeartbeatAt = now;
      for (String requestId : deferred) {
        // Deliberately not checked: a false here is a row we have already lost or already answered,
        // and the Completion is what decides what to do about that -- see `answer`.
        table.touch(requestId, nodeId, now);
      }
    }
    if (table == null || !canOpenExperiences.getAsBoolean()
        || now - lastDrainAt < DRAIN_INTERVAL_MILLIS) {
      return;
    }
    lastDrainAt = now;
    // Whoever ticks first clears out requests nobody will ever run -- the node they name is gone for
    // good. Rate-limited for the same reason the node reaper is: it is one UPDATE per node per window
    // against a table the whole fleet shares.
    if (now - lastExpiryAt >= EXPIRY_INTERVAL_MILLIS) {
      lastExpiryAt = now;
      table.expire(now);
    }
    for (ExperienceCommandStore.Command row : table.pendingFor(nodeId, now)) {
      Optional<Op> op = Op.parse(row.op());
      if (op.isEmpty()) {
        // Only answer for a row we actually took. `complete` matches on RUNNING alone, so answering
        // after a lost claim marks somebody else's in-flight request FAILED while they are running it
        // -- reachable during a rolling update, where the node being replaced and its replacement
        // share an SX_NODE id and both drain the same rows.
        if (table.claim(row.id(), nodeId).isPresent()) {
          table.complete(row.id(), false, "this build does not know the op '" + row.op() + "'");
        }
        continue;
      }
      Map<String, String> args = decode(row.args());
      // The row's own column is the key here. A drained request is being read back long after the
      // message that carried it was published — possibly never delivered at all — and without the key
      // there is nothing to re-check the world's home against before deleting its folder.
      if (row.worldKey() != null && !row.worldKey().isBlank()) {
        args.putIfAbsent("key", row.worldKey());
      }
      run(row.experienceId(), op.get(), args, row.id(), row.requestedBy());
    }
  }

  /** Every request this node is still waiting on. For tests and the admin surface. */
  public List<String> pendingRequestIds() {
    return List.copyOf(waiting.keySet());
  }

  /**
   * From inside {@link LocalExecutor#apply}: keep this request open until the work really finishes.
   *
   * <p>The row stays RUNNING and no answer is published until the returned {@link Completion} is
   * invoked. Taking one is the handler promising to invoke it — a promise it must keep on the failure
   * paths too, or the row sits RUNNING with nothing left to answer for it.</p>
   *
   * <p>For as long as it is open, {@link #tick} re-stamps the row (see
   * {@link ExperienceCommandStore#touch}). Without that, work that outlasts
   * {@code ExperienceCommandStore.RECLAIM_AFTER_MILLIS} — which a folder copy of a large world does —
   * is handed back to this node's OWN drain, re-run, refused by the claim the first attempt still
   * holds, and marked terminally FAILED while that first attempt goes on to succeed.</p>
   *
   * @return null when there is nothing to hold open: no request row was written (standalone, a live
   *     edit, the fire-and-forget shape), so the boolean this handler returns is the whole answer
   */
  public Completion deferCurrentRequest() {
    Deferral deferral = applying.get();
    return deferral == null ? null : deferral.take();
  }

  /** One request, and whether the handler running it has taken over answering for it. */
  private final class Deferral {
    private final String experienceId;
    private final Op op;
    private final String requestId;
    private final String requester;
    private final java.util.concurrent.atomic.AtomicBoolean taken =
        new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean answered =
        new java.util.concurrent.atomic.AtomicBoolean();

    private Deferral(String experienceId, Op op, String requestId, String requester) {
      this.experienceId = experienceId;
      this.op = op;
      this.requestId = requestId;
      this.requester = requester;
    }

    private boolean deferred() {
      return taken.get();
    }

    private Completion take() {
      if (requestId == null || requestId.isBlank()) {
        return null; // nothing was written down, so there is nothing to keep open
      }
      taken.set(true);
      // From here until the Completion runs, `tick` renews this row's updated_at. A handler that
      // breaks its promise and never completes therefore keeps renewing -- bounded, because `expire`
      // sweeps RUNNING rows past their deadline whatever their age, so the row still reaches a
      // terminal state; it is the RECLAIM window it stops feeding, which is the whole point.
      deferred.add(requestId);
      return (applied, code) -> {
        // Exactly once, whichever path the work ended on. A second call would race the row's next
        // life -- a reclaimed RUNNING row is a legitimate second attempt, and answering for that one
        // from here would mark somebody else's in-flight request.
        if (!answered.compareAndSet(false, true)) {
          return;
        }
        deferred.remove(requestId);
        answer(experienceId, op, requestId, requester, applied, code);
      };
    }
  }

  /** Record and publish the answer for one request. Shared by the synchronous and deferred paths. */
  private void answer(String experienceId, Op op, String requestId, String requester, boolean applied,
      String code) {
    String detail = coded(code,
        applied ? "applied on '" + nodeId + "'" : "'" + nodeId + "' declined it");
    ExperienceCommandStore table = store;
    if (table != null && !table.complete(requestId, applied, detail)) {
      // The row was not RUNNING any more: its claim went stale and another attempt took it. Ours is
      // the answer to a question somebody else is now answering, so it is logged and dropped -- and
      // "dropped" has to include the BUS, which is the half the owner actually reads.
      //
      // The publish used to sit outside this guard and fire anyway. `onResult` hands the first answer
      // that arrives to the waiting caller and forgets the request, so during a rolling update -- the
      // one situation that produces this at all, where the node being replaced and its replacement
      // share an SX_NODE id and drain the same rows -- a loser's stale "declined" could be the
      // sentence the owner sees for an operation the winner went on to finish. Losing the claim means
      // losing the right to answer, on both channels or on neither.
      logger.warning("The " + op + " for experience " + experienceId
          + " finished, but its request row was no longer ours to complete (" + detail + ").");
      return;
    }
    NetworkBus channel = bus;
    if (channel != null && requester != null && !requester.isBlank()) {
      channel.publish(NetworkBus.Topics.EXPERIENCE_COMMAND_RESULT, experienceId,
          encodeResult(requester, requestId, op, applied, detail));
    }
  }

  /** The code the local handler recorded for what it just did, or null. Never throws. */
  private String reasonFor(String experienceId, Op op, Map<String, String> args) {
    ReasonReader reader = reasons;
    if (reader == null) {
      return null;
    }
    try {
      String code = reader.reasonFor(experienceId, op, args);
      return code == null || code.isBlank() ? null : code.trim();
    } catch (RuntimeException unavailable) {
      // A reason is a nicety. Losing the answer itself over one would be the tail wagging the dog.
      return null;
    }
  }

  /** {@code "<CODE>: <sentence>"}, or the sentence alone when nothing was recorded. */
  private static String coded(String code, String detail) {
    return code == null ? detail : code + ": " + detail;
  }

  private static Result answer(Result result, Consumer<Result> onAck) {
    if (onAck != null) {
      onAck.accept(result);
    }
    return result;
  }

  // ----- wire format: "<targetNode>|<OP>|k=v;k=v" -----------------------------------------------
  // Flat and self-describing rather than JSON: the bus payload is a text column an operator reads
  // with a SELECT, and every other topic on it is already a short string.
  //
  // The answer rides the same grammar: "<requesterNode>|<requestId>|<OP>|<OK|FAILED>|detail".

  private static String encode(String targetNode, Op op, Map<String, String> args) {
    return new StringBuilder(targetNode).append('|').append(op.name()).append('|')
        .append(encodeArgs(args)).toString();
  }

  private static String encodeResult(String requesterNode, String requestId, Op op, boolean applied,
      String detail) {
    return requesterNode + '|' + requestId + '|' + op.name() + '|' + (applied ? "OK" : "FAILED")
        + '|' + escape(detail == null ? "" : detail);
  }

  private static String encodeArgs(Map<String, String> args) {
    StringBuilder encoded = new StringBuilder();
    boolean first = true;
    for (Map.Entry<String, String> entry : args.entrySet()) {
      if (entry.getKey() == null || entry.getValue() == null) {
        continue;
      }
      if (!first) {
        encoded.append(';');
      }
      first = false;
      encoded.append(escape(entry.getKey())).append('=').append(escape(entry.getValue()));
    }
    return encoded.toString();
  }

  private static Map<String, String> decode(String encoded) {
    Map<String, String> args = new LinkedHashMap<>();
    if (encoded == null || encoded.isBlank()) {
      return args;
    }
    for (String pair : encoded.split(";")) {
      int equals = pair.indexOf('=');
      if (equals > 0) {
        args.put(unescape(pair.substring(0, equals)), unescape(pair.substring(equals + 1)));
      }
    }
    return args;
  }

  /** A challenge list is comma-separated and a display name is arbitrary, so the separators escape. */
  private static String escape(String value) {
    return value.replace("%", "%25").replace(";", "%3B").replace("=", "%3D").replace("|", "%7C");
  }

  private static String unescape(String value) {
    return value.replace("%7C", "|").replace("%3D", "=").replace("%3B", ";").replace("%25", "%");
  }
}
