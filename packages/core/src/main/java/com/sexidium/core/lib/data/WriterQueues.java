package com.sexidium.core.lib.data;

import com.sexidium.core.platform.LoggerAdapter;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Waiting for the asynchronous database writers, in one place so the three of them cannot drift.
 *
 * <p>{@code RankService}, {@code FriendService} and {@code MatchRepository} each push their writes
 * onto a single-threaded executor. All three used to call {@code writer.shutdown()} with <b>no</b>
 * {@code awaitTermination}, and the platform then closed the {@code Database} immediately after
 * ({@code PaperSexidiumPlugin.onDisable}) — so on <em>every</em> shutdown, not just a drained one,
 * whatever was still queued executed against a closed {@link java.sql.Connection}. Rank points and
 * friend edits from the last moments before a stop were simply lost, silently.</p>
 *
 * <p>Two operations, because a drain and a shutdown want different things:</p>
 *
 * <ul>
 *   <li>{@link #flush} — <b>reversible</b>. Waits for the queue to empty and leaves the executor
 *       running. This is what a drain uses before reporting READY, because a drain can be cancelled
 *       and a node that came back with three dead writer threads would be a much worse outcome than
 *       one that never drained.</li>
 *   <li>{@link #shutdown} — final. Used on the way down, where the executor is not needed again.</li>
 * </ul>
 */
public final class WriterQueues {

  /**
   * How long to wait. Long enough for a queue of ordinary writes against a local database; short
   * enough that a stuck writer cannot hold a server-stop open indefinitely, which an operator would
   * read as a hang and kill with {@code -9} — losing far more than the writes.
   */
  public static final long DEFAULT_TIMEOUT_SECONDS = 5L;

  private WriterQueues() {
  }

  /**
   * Wait for everything already queued to be written, without stopping the executor.
   *
   * <p>Implemented as a barrier rather than a shutdown: a single-threaded executor runs its tasks in
   * submission order, so an empty task that has completed proves every task queued before it has too.
   * That is the whole trick, and it is what makes this safe to call on a drain that may be undrained
   * a second later.</p>
   *
   * @return whether the queue drained inside the timeout
   */
  public static boolean flush(ExecutorService writer, String name, LoggerAdapter logger) {
    if (writer == null || writer.isShutdown()) {
      return true;
    }
    try {
      writer.submit(() -> {
      }).get(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      return true;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    } catch (RuntimeException | java.util.concurrent.ExecutionException
        | java.util.concurrent.TimeoutException failed) {
      if (logger != null) {
        logger.warning("The " + name + " writer did not drain in " + DEFAULT_TIMEOUT_SECONDS
            + "s; writes queued before a restart may be lost.");
      }
      return false;
    }
  }

  /**
   * Stop accepting writes and wait for the queued ones to be written.
   *
   * <p>Says how many were dropped when the wait times out. "Dropped 3 writes" in the log is the
   * difference between a bug report and a mystery about why somebody's points went backwards.</p>
   */
  public static void shutdown(ExecutorService writer, String name, LoggerAdapter logger) {
    if (writer == null) {
      return;
    }
    writer.shutdown();
    try {
      if (writer.awaitTermination(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    List<Runnable> dropped = writer.shutdownNow();
    if (logger != null) {
      logger.warning("The " + name + " writer did not drain in " + DEFAULT_TIMEOUT_SECONDS + "s; "
          + dropped.size() + " write(s) dropped.");
    }
  }
}
