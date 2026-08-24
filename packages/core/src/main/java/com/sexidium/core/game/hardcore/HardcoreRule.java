package com.sexidium.core.game.hardcore;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.WorldAdapter;

/**
 * Everything "this mode is hardcore" means, as one object a mode owns rather than a handful of private
 * methods on one host.
 *
 * <h2>Why this exists</h2>
 * Hardcore is three separate things that have to agree: a WORLD flag (hard difficulty, a death that
 * counts), a CLIENT view (the hardened hearts, which can only be re-sent on a world change — see
 * {@link EntryPolicy#prepareArrival}), and an OUTCOME for the death itself. The first two were already
 * reusable seams. The third was written into {@code ExperienceGame} as "the world is lost forever",
 * which meant any other mode wanting hardcore had to either copy that block or accept its ending.
 *
 * <p>So the outcome is a field now. A mode constructs one of these, tells it what a death should do,
 * and asks it questions; the mode keeps whatever is specific to itself (what to announce, where to put
 * the players) and stops owning the bookkeeping that is the same everywhere.</p>
 *
 * <h2>What it deliberately does not know</h2>
 * Where "lost" is written down. That is a {@link Ledger}, so a mode with a database row supplies one and
 * a mode without one passes {@link Ledger#MEMORY} — and the rule stays testable without a database.
 */
public final class HardcoreRule {

  /**
   * Where the one-way "this world has been lost" fact is recorded and read back.
   *
   * <p>Reading matters as much as writing: on a shared database another server may have lost the world
   * first, and a restart has to be able to find out that it happened at all.</p>
   */
  public interface Ledger {
    /** A world with nowhere to record anything: never lost, and losing it records nothing. */
    Ledger MEMORY = new Ledger() {
      @Override
      public boolean lost() {
        return false;
      }

      @Override
      public void markLost() {
      }
    };

    boolean lost();

    void markLost();
  }

  private final Ledger ledger;
  private boolean enabled;
  private HardcoreDeathOutcome outcome = HardcoreDeathOutcome.LOSE_WORLD;
  // Cached because it is asked on every entry path and once a second by the watchdog, and a SQL round
  // trip per player per second is not a thing to put in a tick loop.
  private boolean lost;
  private int polls;

  public HardcoreRule(Ledger ledger) {
    this.ledger = ledger == null ? Ledger.MEMORY : ledger;
  }

  public boolean enabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public HardcoreDeathOutcome outcome() {
    return outcome;
  }

  /** Sets what a death costs. Null is ignored, so "no opinion" callers can pass their answer straight in. */
  public void outcome(HardcoreDeathOutcome outcome) {
    if (outcome != null) {
      this.outcome = outcome;
    }
  }

  /** Whether this world has already been lost. Answered from the cache; see {@link #refreshLost}. */
  public boolean lost() {
    return lost;
  }

  /**
   * Re-reads the lost flag from the ledger — the source of truth across restarts and, on a shared
   * database, across servers. NEVER clears the in-memory flag: losing a hardcore world is one way, and a
   * ledger that is unavailable or has forgotten the row must not be able to revive one.
   */
  public void refreshLost() {
    if (!lost && ledger.lost()) {
      lost = true;
    }
  }

  /**
   * The watchdog's rate-limited {@link #refreshLost}: only every {@code everyPasses} calls, only for a
   * world that is hardcore and not already lost. Everything else the watchdog checks is in memory, and
   * only another server could tell us something new here.
   *
   * @return true when this call actually re-read the ledger
   */
  public boolean pollLost(int everyPasses) {
    if (!enabled || lost || ++polls < Math.max(1, everyPasses)) {
      return false;
    }
    polls = 0;
    refreshLost();
    return true;
  }

  /**
   * Pushes the hardcore flag onto a world AND its linked dimensions. Hardcore is a WORLD property in
   * vanilla — it pins the difficulty to hard and makes a death final — so it has to be set on every
   * world the mode owns, or walking into the Nether would quietly drop the stakes. The client-side half
   * (the hardened hearts) is not this: that is {@link EntryPolicy#prepareArrival}.
   *
   * <p>Cheap and idempotent, so re-applying it on every entry is fine — and necessary, because the world
   * layer sets its own default whenever a world is (re)acquired.</p>
   */
  public void applyToWorld(WorldAdapter world) {
    if (world != null) {
      world.setHardcoreEverywhere(enabled);
    }
  }

  /**
   * Records a death against this rule and reports whether it is the one that ENDS the world.
   *
   * <p>Only the first death in a {@link HardcoreDeathOutcome#LOSE_WORLD} world returns true, so a second
   * death in the same moment cannot re-announce the ending. A {@link HardcoreDeathOutcome#RESET_WORLD}
   * world <b>never</b> returns true and never writes to the ledger — that is what keeps a mode that
   * intends to continue from marking its own world dead, which would otherwise lock its owner out of it
   * permanently.</p>
   *
   * <p>The in-memory flag is set before the ledger write and unconditionally: a mode with no row to
   * write has nothing to persist, and a ledger that refuses the write must not be able to hand a
   * hardcore run back to the player who just lost it.</p>
   */
  public boolean recordDeath() {
    if (!enabled || outcome != HardcoreDeathOutcome.LOSE_WORLD) {
      return false;
    }
    boolean first = !lost;
    lost = true;
    ledger.markLost();
    return first;
  }

  /**
   * How a player arrives in — and is kept in — a world with these hardcore facts.
   *
   * <p>Two rules, and they are the whole of what hardcore means to a player: a world that has been LOST
   * can only ever be spectated, and a LIVE hardcore world hands its players hardcore hearts and keeps
   * them playing.</p>
   *
   * <h2>Why a live hardcore world is enforced too</h2>
   * It reads backwards — surely the dead world is the one that needs holding down? — but it is the other
   * way round. Nothing outside this class ever tries to make a player survival in a lost world, so that
   * policy only has to be applied once. A live hardcore world has a competitor: the server itself puts a
   * player who respawns in a hardcore world into SPECTATOR, and it does so AFTER the respawn event this
   * code gets to react to, so it wins. A mode that respawns its players deliberately — Death Resets does,
   * to get them off a death screen that has no Respawn button — therefore has exactly one chance to undo
   * it, and if that chance is missed the player is a spectator for the rest of the session: no chunks
   * loading, nothing breakable, cured only by reconnecting.
   *
   * <p>Enforcing gives a standing correction instead of a single chance. {@link EntryPolicy#enforce}
   * writes only when the mode is actually wrong, so the cost is one comparison per player per tick of the
   * watchdog, and the failure mode becomes "wrong for up to a second" rather than "wrong for ever".</p>
   */
  public static EntryPolicy entryPolicyFor(boolean hardcore, boolean lost, LocalizedText spectatorNotice) {
    if (lost) {
      return EntryPolicy.spectator(spectatorNotice);
    }
    EntryPolicy alive = EntryPolicy.SURVIVAL.withHardcoreView(hardcore);
    return hardcore ? alive.alwaysEnforced() : alive;
  }
}
