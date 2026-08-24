package com.sexidium.core.network;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One set of behaviours, asserted against BOTH bus implementations.
 *
 * <p>This is the dual-mode strategy in miniature: the standalone path is only trustworthy if it is
 * held to the same contract as the networked one. Anything asserted here must hold whether or not a
 * network exists, which is what stops "works standalone, breaks on a network" from being discovered
 * in production.</p>
 */
class NetworkBusContractTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** Collects deliveries so assertions read as "what did this node receive". */
  private static final class Inbox implements NetworkBus.BusListener {
    final List<String> received = new ArrayList<>();

    @Override
    public void onMessage(String topic, String key, String payload, String originNode) {
      received.add(topic + "|" + key + "|" + payload + "|" + originNode);
    }
  }

  // --- Local -----------------------------------------------------------------

  @Nested
  @DisplayName("LocalNetworkBus (standalone)")
  class Local {

    /** Deferred tasks are run by hand, so the test controls when delivery happens. */
    private final Deque<Runnable> pending = new ArrayDeque<>();

    private LocalNetworkBus bus() {
      LocalNetworkBus bus = new LocalNetworkBus("standalone", pending::add);
      bus.start();
      return bus;
    }

    private void drain() {
      while (!pending.isEmpty()) {
        pending.poll().run();
      }
    }

    @Test
    @DisplayName("a subscriber receives a published message")
    void delivers() {
      LocalNetworkBus bus = bus();
      Inbox inbox = new Inbox();
      bus.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      bus.publish(NetworkBus.Topics.RANK_CHANGED, "player-1", "{}");
      drain();

      assertEquals(1, inbox.received.size());
      assertTrue(inbox.received.get(0).startsWith("rank.changed|player-1|{}|"));
    }

    @Test
    @DisplayName("delivery is DEFERRED, so no caller can depend on it having already happened")
    void deliveryIsDeferred() {
      LocalNetworkBus bus = bus();
      Inbox inbox = new Inbox();
      bus.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      bus.publish(NetworkBus.Topics.RANK_CHANGED, "player-1", "{}");

      // Nothing yet: the networked bus is asynchronous, so the local one must be too, or code
      // written against standalone silently breaks when a second node appears.
      assertTrue(inbox.received.isEmpty());
      drain();
      assertEquals(1, inbox.received.size());
    }

    @Test
    @DisplayName("other topics are not delivered")
    void topicIsolation() {
      LocalNetworkBus bus = bus();
      Inbox inbox = new Inbox();
      bus.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      bus.publish(NetworkBus.Topics.FRIEND_CHANGED, "player-1", "{}");
      drain();

      assertTrue(inbox.received.isEmpty());
    }

    @Test
    @DisplayName("unsubscribing stops delivery")
    void unsubscribe() throws Exception {
      LocalNetworkBus bus = bus();
      Inbox inbox = new Inbox();
      AutoCloseable handle = bus.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      handle.close();
      bus.publish(NetworkBus.Topics.RANK_CHANGED, "player-1", "{}");
      drain();

      assertTrue(inbox.received.isEmpty());
    }

    @Test
    @DisplayName("publishing before start, or after close, is inert")
    void lifecycle() {
      LocalNetworkBus bus = new LocalNetworkBus("standalone", pending::add);
      Inbox inbox = new Inbox();
      bus.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      bus.publish(NetworkBus.Topics.RANK_CHANGED, "a", "{}"); // not started
      drain();
      assertTrue(inbox.received.isEmpty());

      bus.start();
      bus.close();
      bus.publish(NetworkBus.Topics.RANK_CHANGED, "b", "{}");
      drain();
      assertTrue(inbox.received.isEmpty());
    }
  }

  // --- Database ---------------------------------------------------------------

  @Nested
  @DisplayName("DbNetworkBus (networked)")
  class Db {

    @TempDir
    Path tmp;

    private Database database;

    private DbNetworkBus busFor(String nodeId) throws Exception {
      if (database == null) {
        database = new Database(new File(tmp.toFile(), "network.db"));
      }
      // Poll is driven by hand: schedule() records nothing and the test calls poll() directly.
      DbNetworkBus bus = new DbNetworkBus(database, SILENT, nodeId, poll -> () -> { }, 60_000L);
      bus.start();
      return bus;
    }

    @Test
    @DisplayName("a message published on one node reaches another")
    void crossNodeDelivery() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      DbNetworkBus nodeB = busFor("worker-2");
      Inbox inbox = new Inbox();
      nodeB.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      nodeA.publish(NetworkBus.Topics.RANK_CHANGED, "player-1", "{\"points\":5}");
      nodeB.poll();

      assertEquals(1, inbox.received.size());
      assertTrue(inbox.received.get(0).endsWith("|worker-1"), inbox.received.get(0));
    }

    @Test
    @DisplayName("a node never receives its OWN publication")
    void noEcho() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      Inbox inbox = new Inbox();
      nodeA.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);

      nodeA.publish(NetworkBus.Topics.RANK_CHANGED, "player-1", "{}");
      nodeA.poll();

      // The publisher already applied the change locally; echoing it back is how
      // cache-invalidation loops start.
      assertTrue(inbox.received.isEmpty());
    }

    @Test
    @DisplayName("the cursor advances, so a message is delivered exactly once")
    void deliveredOnce() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      DbNetworkBus nodeB = busFor("worker-2");
      Inbox inbox = new Inbox();
      nodeB.subscribe(NetworkBus.Topics.FRIEND_CHANGED, inbox);

      nodeA.publish(NetworkBus.Topics.FRIEND_CHANGED, "player-1", "{}");
      nodeB.poll();
      nodeB.poll();
      nodeB.poll();

      assertEquals(1, inbox.received.size());
    }

    @Test
    @DisplayName("messages arrive in publication order")
    void ordered() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      DbNetworkBus nodeB = busFor("worker-2");
      Inbox inbox = new Inbox();
      nodeB.subscribe(NetworkBus.Topics.QUEUE_CHANGED, inbox);

      nodeA.publish(NetworkBus.Topics.QUEUE_CHANGED, "1", "first");
      nodeA.publish(NetworkBus.Topics.QUEUE_CHANGED, "2", "second");
      nodeA.publish(NetworkBus.Topics.QUEUE_CHANGED, "3", "third");
      nodeB.poll();

      assertEquals(3, inbox.received.size());
      assertTrue(inbox.received.get(0).contains("first"));
      assertTrue(inbox.received.get(1).contains("second"));
      assertTrue(inbox.received.get(2).contains("third"));
    }

    @Test
    @DisplayName("a node joining later does not replay history as news")
    void joiningNodeStartsAtHead() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      nodeA.publish(NetworkBus.Topics.RANK_CHANGED, "old-1", "{}");
      nodeA.publish(NetworkBus.Topics.RANK_CHANGED, "old-2", "{}");

      // worker-3 boots into an established network.
      DbNetworkBus nodeC = busFor("worker-3");
      Inbox inbox = new Inbox();
      nodeC.subscribe(NetworkBus.Topics.RANK_CHANGED, inbox);
      nodeC.poll();

      // Replaying every historical message at boot would re-fire every cache
      // invalidation and every announcement.
      assertTrue(inbox.received.isEmpty());

      nodeA.publish(NetworkBus.Topics.RANK_CHANGED, "new-1", "{}");
      nodeC.poll();
      assertEquals(1, inbox.received.size());
      assertTrue(inbox.received.get(0).contains("new-1"));
    }

    @Test
    @DisplayName("a throwing listener does not stop the others")
    void listenerIsolation() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      DbNetworkBus nodeB = busFor("worker-2");
      Inbox healthy = new Inbox();
      nodeB.subscribe(NetworkBus.Topics.NODE_STATE, (t, k, p, o) -> {
        throw new IllegalStateException("listener blew up");
      });
      nodeB.subscribe(NetworkBus.Topics.NODE_STATE, healthy);

      nodeA.publish(NetworkBus.Topics.NODE_STATE, "worker-1", "DRAINING");
      nodeB.poll();

      assertEquals(1, healthy.received.size());
    }

    @Test
    @DisplayName("a null key round-trips")
    void nullKey() throws Exception {
      DbNetworkBus nodeA = busFor("worker-1");
      DbNetworkBus nodeB = busFor("worker-2");
      Inbox inbox = new Inbox();
      nodeB.subscribe(NetworkBus.Topics.NODE_STATE, inbox);

      nodeA.publish(NetworkBus.Topics.NODE_STATE, null, "payload");
      nodeB.poll();

      assertEquals(1, inbox.received.size());
      assertTrue(inbox.received.get(0).contains("null|payload"));
    }
  }
}
