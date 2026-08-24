package com.sexidium.core.lib.data;

import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The asynchronous database writers, and the wait that was missing from all three of them.
 *
 * <p>{@code RankService}, {@code FriendService} and {@code MatchRepository} each called
 * {@code writer.shutdown()} with no {@code awaitTermination}, and {@code PaperSexidiumPlugin.onDisable}
 * closed the {@code Database} immediately afterwards — so on <b>every</b> shutdown, not just a drained
 * one, whatever was still queued ran against a closed connection. Points and friend edits from the
 * last moments before a stop were lost, silently and routinely.</p>
 */
class WriterQueuesTest {

  private static final class CapturingLogger implements LoggerAdapter {
    final List<String> warnings = new ArrayList<>();

    @Override public void info(String message) { }

    @Override public void warning(String message) {
      warnings.add(message);
    }

    @Override public void severe(String message) { }

    @Override public void warning(String message, Throwable throwable) {
      warnings.add(message);
    }

    @Override public void severe(String message, Throwable throwable) { }
  }

  private static ExecutorService writer() {
    return Executors.newSingleThreadExecutor(runnable -> {
      Thread thread = new Thread(runnable, "test-writer");
      thread.setDaemon(true);
      return thread;
    });
  }

  @Test
  @DisplayName("shutdown waits for a queued write instead of dropping it on a closed connection")
  void awaitsWriterTerminationBeforeClose() {
    ExecutorService writer = writer();
    AtomicInteger written = new AtomicInteger();
    CapturingLogger logger = new CapturingLogger();

    // A slow write, queued and not yet started, which is the state the shutdown path always found it in.
    writer.execute(() -> {
      sleep(200L);
      written.incrementAndGet();
    });
    writer.execute(written::incrementAndGet);

    WriterQueues.shutdown(writer, "rank", logger);

    assertEquals(2, written.get(),
        "both writes had to reach the database BEFORE the connection is closed");
    assertTrue(writer.isTerminated());
    assertTrue(logger.warnings.isEmpty());
  }

  @Test
  @DisplayName("flush drains the queue and leaves the writer usable, so a drain can be cancelled")
  void flushIsReversible() {
    ExecutorService writer = writer();
    AtomicInteger written = new AtomicInteger();

    writer.execute(() -> {
      sleep(100L);
      written.incrementAndGet();
    });

    assertTrue(WriterQueues.flush(writer, "rank", new CapturingLogger()));
    assertEquals(1, written.get());
    assertFalse(writer.isShutdown(),
        "a drain can be undrained a second later; coming back with three dead writer threads would"
            + " be a much worse outcome than a drain that never finished");

    writer.execute(written::incrementAndGet);
    assertTrue(WriterQueues.flush(writer, "rank", new CapturingLogger()));
    assertEquals(2, written.get());
    writer.shutdownNow();
  }

  @Test
  @DisplayName("a writer that will not drain says how many writes were dropped")
  void reportsDroppedWrites() {
    ExecutorService writer = writer();
    CapturingLogger logger = new CapturingLogger();

    writer.execute(() -> sleep(WriterQueues.DEFAULT_TIMEOUT_SECONDS * 1000L + 2_000L));
    writer.execute(() -> { });
    writer.execute(() -> { });

    WriterQueues.shutdown(writer, "friend", logger);

    assertEquals(1, logger.warnings.size());
    assertTrue(logger.warnings.get(0).contains("2 write(s) dropped"),
        "\"dropped 2 writes\" in the log is the difference between a bug report and a mystery about"
            + " why somebody's points went backwards");
  }

  @Test
  @DisplayName("flushing or shutting down an already-shut writer is a no-op, not a failure")
  void tolerantOfAnAlreadyShutWriter() {
    ExecutorService writer = writer();
    writer.shutdown();
    assertTrue(WriterQueues.flush(writer, "match", new CapturingLogger()));
    WriterQueues.shutdown(writer, "match", new CapturingLogger());
    WriterQueues.shutdown(null, "match", new CapturingLogger());
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
