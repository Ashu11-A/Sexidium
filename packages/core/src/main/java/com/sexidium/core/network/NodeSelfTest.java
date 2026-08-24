package com.sexidium.core.network;

import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.ChallengeCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * "Is this node fit to serve?", answered before a player finds out the hard way.
 *
 * <p>This is the highest-value check on the whole rolling-update contract, and the reason is
 * specific: {@link ChallengeCatalog#create} drops ids it does not know, so a node with a stale or
 * half-broken catalog answers <em>false</em> to "does this experience need a void world?" and
 * generates <b>normal terrain into what should be an empty SkyBlock</b> — silently, permanently, and
 * reachable today with no version skew at all. A selftest turns that into a rollback trigger before
 * the node ever takes a player.</p>
 *
 * <p>Contract, deliberately narrow: runs on the caller's thread (the main thread, from the console or
 * the API handler), catches every {@link Throwable}, completes in well under ten seconds, <b>never
 * throws</b>, and emits exactly one line. A failure is a log line, not an exception — a node that
 * crashes while checking whether it is healthy has told the orchestrator nothing useful.</p>
 *
 * <p><b>What it does not do, and why.</b> The world pool is <em>reported</em>, not exercised:
 * acquiring a world generates terrain, which is minutes of work on a cold node and would blow the
 * time bound on exactly the node a roll is waiting for. And challenges are instantiated but not
 * {@code register}ed — registration needs a live {@code ChallengeContext} and a running match, so a
 * fake host would test the fake. Instantiation is what actually catches the failure this exists for:
 * a missing class, a broken static initialiser, an id that no longer resolves.</p>
 */
public final class NodeSelfTest {

  /**
   * The outcome, in the shape both the log line and the JSON endpoint render.
   *
   * @param detail the FIRST failure, or null when everything passed. First, not all: an operator
   *     reading a rollback trigger needs the cause, and the tail is usually the same cause again.
   */
  public record Result(
      boolean ok,
      int challengesOk,
      int challengesTotal,
      int modesOk,
      int modesTotal,
      String pool,
      String poolDetail,
      boolean databaseOk,
      String detail) {

    /** The single {@code SX-SELFTEST} line. Stable, English, never localized. */
    public String logLine() {
      if (ok) {
        return "SX-SELFTEST ok=true challenges=" + challengesOk + "/" + challengesTotal
            + " modes=" + modesOk + "/" + modesTotal + " pool=" + pool;
      }
      return "SX-SELFTEST ok=false detail=" + (detail == null ? "unknown" : detail);
    }
  }

  private final GameRegistry registry;
  private final java.util.function.Supplier<String> poolProbe;
  private final com.sexidium.core.lib.data.Database database;

  /**
   * @param registry  the mode registry, or null on a node that registered none
   * @param poolProbe a one-word summary of the warm pool ({@code ok}, {@code empty}, …), or null
   * @param database  the shared database, or null when this node has none
   */
  public NodeSelfTest(GameRegistry registry, java.util.function.Supplier<String> poolProbe,
      com.sexidium.core.lib.data.Database database) {
    this.registry = registry;
    this.poolProbe = poolProbe;
    this.database = database;
  }

  /** Run every check. Never throws; a failure is a {@link Result}, not an exception. */
  public Result run() {
    List<String> failures = new ArrayList<>();

    List<ChallengeCatalog.Entry> entries = ChallengeCatalog.available();
    int challengesOk = 0;
    for (ChallengeCatalog.Entry entry : entries) {
      try {
        Challenge challenge = entry.factory().get();
        if (challenge == null) {
          failures.add("challenge " + entry.id() + " produced no instance");
          continue;
        }
        // The id has to survive the round trip, because every capability code, every stored
        // experience row and every world-shaping decision is keyed on it.
        if (!entry.id().equals(challenge.id())) {
          failures.add("challenge " + entry.id() + " reports id '" + challenge.id() + "'");
          continue;
        }
        challengesOk++;
      } catch (Throwable failed) {
        failures.add("challenge " + entry.id() + ": " + describe(failed));
      }
    }

    int modesTotal = 0;
    int modesOk = 0;
    try {
      List<String> modeIds = registry == null ? List.of() : registry.modeIds();
      modesTotal = modeIds.size();
      for (String modeId : modeIds) {
        if (registry.contains(modeId)) {
          modesOk++;
        } else {
          failures.add("mode " + modeId + " is registered but does not resolve");
        }
      }
    } catch (Throwable failed) {
      failures.add("modes: " + describe(failed));
    }

    // One WORD in the log line, the free-form description in the JSON. The line is grepped by an
    // orchestrator, and "pool=overworld 2/3, nether 1/1" would break the key=value parse it does.
    String pool = "unknown";
    String poolDetail = null;
    try {
      if (poolProbe != null) {
        poolDetail = poolProbe.get();
        pool = poolDetail == null || poolDetail.isBlank() ? "unknown" : "ok";
      }
    } catch (Throwable failed) {
      pool = "error";
      failures.add("world pool: " + describe(failed));
    }

    boolean databaseOk = true;
    try {
      if (database != null) {
        synchronized (database.lock()) {
          try (java.sql.Statement statement = database.connection().createStatement();
               java.sql.ResultSet rs = statement.executeQuery("SELECT 1")) {
            databaseOk = rs.next();
          }
        }
        if (!databaseOk) {
          failures.add("database: SELECT 1 returned no row");
        }
      }
    } catch (Throwable failed) {
      databaseOk = false;
      failures.add("database: " + describe(failed));
    }

    boolean ok = failures.isEmpty() && challengesOk == entries.size() && modesOk == modesTotal;
    return new Result(ok, challengesOk, entries.size(), modesOk, modesTotal, pool, poolDetail,
        databaseOk, failures.isEmpty() ? null : failures.get(0));
  }

  private static String describe(Throwable failed) {
    String message = failed.getMessage();
    String described = failed.getClass().getSimpleName()
        + (message == null || message.isBlank() ? "" : ": " + message);
    // The line is grepped by an orchestrator, so it must stay one line and stay short.
    described = described.replace('\n', ' ').replace('\r', ' ');
    return described.length() <= 200 ? described : described.substring(0, 200);
  }
}
