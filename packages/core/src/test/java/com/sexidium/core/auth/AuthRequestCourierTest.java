package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The courier, and the one property that makes it safe to run on a timer: a delivery that fails must
 * leave the row recoverable rather than swallow somebody's login.
 */
class AuthRequestCourierTest {

  /** Records what it was handed, and can be told to throw once or report a dead channel once. */
  private static final class RecordingNotifier implements AuthRequestCourier.Notifier {
    private final List<String> delivered = new ArrayList<>();
    private boolean throwNext;
    private boolean dropNext;

    @Override
    public boolean notify(RequestRow row) {
      if (throwNext) {
        throwNext = false;
        throw new IllegalStateException("Discord is down");
      }
      if (dropNext) {
        dropNext = false;
        return false; // the socket was dead: nothing reached the bot
      }
      delivered.add(row.requestId());
      return true;
    }
  }

  /** A clock the test moves by hand, so the lease lapsing needs no sleep. */
  private static final class MovableClock extends Clock {
    private long millis = 1_000_000L;

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return Instant.ofEpochMilli(millis);
    }

    @Override
    public long millis() {
      return millis;
    }

    void advance(long by) {
      millis += by;
    }
  }

  private Database db;
  private AuthRequestRepository requests;
  private RecordingNotifier notifier;
  private MovableClock clock;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-courier-test");
    db = new Database(dir.resolve("courier.db").toFile());
    requests = new AuthRequestRepository(db);
    notifier = new RecordingNotifier();
    clock = new MovableClock();
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  @Test
  @DisplayName("a pending request is claimed, delivered and marked notified")
  void deliversAndMarksNotified() throws Exception {
    requests.insert(row("req-1"));

    courier(5_000L).tick();

    assertEquals(List.of("req-1"), notifier.delivered);
    assertEquals(AuthRequestRepository.STATE_NOTIFIED, requests.byId("req-1").state());
  }

  @Test
  @DisplayName("a second tick does not DM the same player again")
  void deliveryIsNotRepeated() throws Exception {
    requests.insert(row("req-1"));
    AuthRequestCourier courier = courier(5_000L);

    courier.tick();
    courier.tick();

    assertEquals(1, notifier.delivered.size());
  }

  @Test
  @DisplayName("a throwing notifier does not lose the row: it lapses back via the lease")
  void aFailedDeliveryIsRetried() throws Exception {
    requests.insert(row("req-1"));
    notifier.throwNext = true;
    AuthRequestCourier courier = courier(5_000L);

    courier.tick();
    assertTrue(notifier.delivered.isEmpty());
    assertEquals(AuthRequestRepository.STATE_PENDING, requests.byId("req-1").state(),
        "an undelivered request must stay pending, or the player waits forever");

    // Inside the lease the row is still claimed, so a second courier does not double-DM.
    courier.tick();
    assertTrue(notifier.delivered.isEmpty());

    clock.advance(6_000L);
    courier.tick();
    assertEquals(List.of("req-1"), notifier.delivered);
    assertEquals(AuthRequestRepository.STATE_NOTIFIED, requests.byId("req-1").state());
  }

  @Test
  @DisplayName("nothing to carry is not an error")
  void anEmptyQueueIsQuiet() {
    courier(5_000L).tick();
    assertTrue(notifier.delivered.isEmpty());
  }

  @Test
  @DisplayName("a notifier that reports a dead channel is retried — the approval is not swallowed")
  void aDroppedDeliveryIsRetried() throws Exception {
    requests.insert(row("req-1"));
    notifier.dropNext = true;
    AuthRequestCourier courier = courier(5_000L);

    courier.tick();
    assertTrue(notifier.delivered.isEmpty());
    assertEquals(AuthRequestRepository.STATE_PENDING, requests.byId("req-1").state(),
        "a delivery that never reached the bot must stay pending, or the player waits forever");

    // Inside the lease the row is still claimed, so a second courier does not double-DM.
    courier.tick();
    assertTrue(notifier.delivered.isEmpty());

    clock.advance(6_000L);
    courier.tick();
    assertEquals(List.of("req-1"), notifier.delivered);
    assertEquals(AuthRequestRepository.STATE_NOTIFIED, requests.byId("req-1").state());
  }

  @Test
  @DisplayName("a request that expired before anyone read it is never delivered")
  void expiredRequestsAreSkipped() throws Exception {
    requests.insert(new RequestRow("req-old", "id-1", "steve", "Steve", "discord-1", "hash-a",
        "187.61.*.*", AuthRequestRepository.KIND_SESSION, AuthRequestRepository.STATE_PENDING,
        false, "node-1", null, 0L, null, null, null, 0, 0L, 1L, null));

    courier(5_000L).tick();

    assertTrue(notifier.delivered.isEmpty());
  }

  @Test
  @DisplayName("a database that will not answer costs a log line, never the tick")
  void aClosedDatabaseIsSurvivable() {
    AuthRequestCourier courier = courier(5_000L);
    db.close();
    assertDoesNotThrow(courier::tick);
  }

  private AuthRequestCourier courier(long leaseMillis) {
    return new AuthRequestCourier(requests, new StdoutLoggerAdapter("T"), "node-1", notifier,
        leaseMillis, clock);
  }

  private static RequestRow row(String requestId) {
    return new RequestRow(requestId, "id-1", "steve", "Steve", "discord-1", "hash-a", "187.61.*.*",
        AuthRequestRepository.KIND_SESSION, AuthRequestRepository.STATE_PENDING, false, "node-1",
        null, 0L, null, null, null, 0, 0L, 9_000_000L, null);
  }
}
