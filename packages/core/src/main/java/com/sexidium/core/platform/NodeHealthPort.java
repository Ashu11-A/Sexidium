package com.sexidium.core.platform;

/**
 * How well this node is coping, and how much of it is left.
 *
 * <p>Published on every heartbeat so a peer can refuse to plan a new world onto a node that is
 * already struggling, and so a rolling update can tell "came back up" from "came back up and is
 * thrashing". Every value defaults to <b>unknown</b> rather than to a plausible number: a proxy has
 * no tick loop, and a node that reports a made-up 20 TPS is worse than one that admits it cannot
 * answer.</p>
 *
 * <p>Scaled integers, not doubles, because these land in shared columns that
 * {@code SqlDialect} has to straddle across sqlite/mysql/postgres, and float behaviour is the one
 * thing those three genuinely disagree about.</p>
 */
public interface NodeHealthPort {

  /** The value every getter here returns when the platform cannot answer. Never 0 — 0 is a reading. */
  int UNKNOWN = -1;

  /** Ticks per second × 100, or {@link #UNKNOWN}. */
  default int tpsTimes100() {
    return UNKNOWN;
  }

  /** Mean tick time in milliseconds × 100, or {@link #UNKNOWN}. */
  default int msptTimes100() {
    return UNKNOWN;
  }

  /** Player slots this node advertises, or 0 when it has no fixed limit / cannot answer. */
  default int maxPlayers() {
    return 0;
  }

  /** Heap in use, in MiB. Read from the JVM, so every platform can answer this one. */
  default int heapUsedMb() {
    Runtime runtime = Runtime.getRuntime();
    return (int) ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L));
  }

  /** Heap ceiling in MiB, or 0 when the JVM reports no bound. */
  default int heapMaxMb() {
    long max = Runtime.getRuntime().maxMemory();
    return max == Long.MAX_VALUE ? 0 : (int) (max / (1024L * 1024L));
  }

  /** A node that knows nothing but its own heap. The default for any platform that does not answer. */
  NodeHealthPort UNAVAILABLE = new NodeHealthPort() {
  };
}
