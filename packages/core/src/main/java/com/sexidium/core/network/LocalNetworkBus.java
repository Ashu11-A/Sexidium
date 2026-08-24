package com.sexidium.core.network;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The standalone bus: in-process fan-out, no database, no socket.
 *
 * <p>Selected whenever {@code network.enabled} is false, which keeps <em>one</em> code path. A
 * subsystem always publishes; on a single server the publication simply has no other node to reach.
 * Without this, every call site would need an {@code if (networked)} and the standalone path would be
 * the one nobody tests.</p>
 *
 * <p><b>Delivery is deferred, not immediate.</b> A listener is invoked on the next scheduler tick
 * rather than inside {@code publish}. That matters: the networked bus is inherently asynchronous, so
 * delivering synchronously here would let code accidentally depend on the listener having already run
 * — behaviour that works standalone and breaks the moment a second node exists. Making the local
 * implementation the <em>looser</em> of the two is what keeps the dual-mode tests honest.</p>
 */
public final class LocalNetworkBus implements NetworkBus {

  /** Where deferred deliveries go. Standalone wires this to the scheduler; tests run it inline. */
  @FunctionalInterface
  public interface Deferrer {
    void defer(Runnable task);
  }

  private final Map<String, List<BusListener>> listeners = new ConcurrentHashMap<>();
  private final Deferrer deferrer;
  private final String nodeId;
  private volatile boolean running;

  public LocalNetworkBus(String nodeId, Deferrer deferrer) {
    this.nodeId = nodeId;
    this.deferrer = deferrer;
  }

  @Override
  public void start() {
    running = true;
  }

  @Override
  public void publish(String topic, String key, String payload) {
    if (!running) {
      return;
    }
    List<BusListener> topicListeners = listeners.get(topic);
    if (topicListeners == null || topicListeners.isEmpty()) {
      return;
    }
    // Snapshot before deferring: a listener may unsubscribe before the task runs.
    List<BusListener> snapshot = List.copyOf(topicListeners);
    deferrer.defer(() -> {
      for (BusListener listener : snapshot) {
        listener.onMessage(topic, key, payload, nodeId);
      }
    });
  }

  @Override
  public AutoCloseable subscribe(String topic, BusListener listener) {
    listeners.computeIfAbsent(topic, unused -> new CopyOnWriteArrayList<>()).add(listener);
    return () -> {
      List<BusListener> topicListeners = listeners.get(topic);
      if (topicListeners != null) {
        topicListeners.remove(listener);
      }
    };
  }

  @Override
  public void close() {
    running = false;
    listeners.clear();
  }
}
