package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.world.WorldNaming;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Everything Omni Chunk knows about ONE dimension: its history of changes, how far each of its chunks has
 * been brought up that history, and which of them are already queued for work.
 *
 * <h2>Why a dimension owns all of this</h2>
 * An experience is three worlds. A plank laid in the Overworld must repeat through the Overworld's chunks
 * and leave the Nether and the End exactly as they were — a shared history would mean mining out a Nether
 * corridor also carved the same holes through everything you had built at home, and a freshly entered
 * dimension would arrive pre-filled with edits that were never made in it.
 *
 * <p>So independence is <em>structural</em>: there is no cross-dimension state to forget to key by world,
 * because every piece of it hangs off this object. Nothing here is shared, and nothing here can reach
 * another dimension.</p>
 *
 * <p>Pure and host-free — no world, no scheduler — so the rules and the persisted form are unit-tested
 * ({@code DimensionLogTest}).</p>
 */
final class DimensionLog {
  /** How many chunk versions are remembered; an evicted chunk simply re-syncs from scratch next visit. */
  static final int MAX_TRACKED_CHUNKS = 8192;
  /** Field separator inside a persisted version entry. */
  static final char FIELD = '|';

  private final String worldName;
  private final ChunkLedger ledger;
  /**
   * Which change each chunk has been brought up to — the chunk's version. Comparing it to the ledger's
   * head is how a chunk synced earlier is detected as being BEHIND and fast-forwarded, rather than
   * skipped for ever because it was "already done" once.
   */
  private final Map<Long, Long> chunkVersion = new HashMap<>();
  /** Chunks with work already queued, so a sweep never queues the same chunk twice. */
  private final Set<Long> queued = new HashSet<>();

  DimensionLog(String worldName, int maxChanges) {
    this.worldName = worldName;
    this.ledger = new ChunkLedger(maxChanges);
  }

  String worldName() {
    return worldName;
  }

  ChunkLedger ledger() {
    return ledger;
  }

  /**
   * A world name reduced to one safe state-key segment. State keys are configuration paths, so a name's
   * slashes, colons and dots would otherwise nest it into a tree of sub-keys.
   */
  String slug() {
    return WorldNaming.sanitizeSegment(worldName, "world");
  }

  /** A chunk's coordinates packed into one long. Scoped to this dimension, so no world is needed. */
  static long chunkKey(int chunkX, int chunkZ) {
    return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
  }

  long versionOf(long chunkKey) {
    return chunkVersion.getOrDefault(chunkKey, 0L);
  }

  void setVersion(long chunkKey, long seq) {
    chunkVersion.put(chunkKey, seq);
    bound();
  }

  int trackedChunks() {
    return chunkVersion.size();
  }

  /** Marks a chunk as waiting for its turn; false when it was already waiting. */
  boolean enqueue(long chunkKey) {
    return queued.add(chunkKey);
  }

  void dequeue(long chunkKey) {
    queued.remove(chunkKey);
  }

  /** Keeps the remembered-version map bounded; an evicted chunk simply re-syncs on its next visit. */
  private void bound() {
    Iterator<Map.Entry<Long, Long>> iterator = chunkVersion.entrySet().iterator();
    while (iterator.hasNext() && chunkVersion.size() > MAX_TRACKED_CHUNKS) {
      iterator.next();
      iterator.remove();
    }
  }

  /** {@code chunkX|chunkZ|seq} per entry — which version each of this dimension's chunks is on. */
  String encodeVersions() {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<Long, Long> entry : chunkVersion.entrySet()) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      long key = entry.getKey();
      builder.append((int) (key >> 32)).append(FIELD).append((int) key)
          .append(FIELD).append(entry.getValue());
    }
    return builder.toString();
  }

  void decodeVersions(String encoded) {
    chunkVersion.clear();
    if (encoded == null || encoded.isBlank()) {
      return;
    }
    for (String part : encoded.split(",")) {
      String[] fields = part.split("\\" + FIELD);
      if (fields.length != 3) {
        continue; // a damaged entry just means that chunk re-syncs; never fatal
      }
      try {
        chunkVersion.put(chunkKey(Integer.parseInt(fields[0]), Integer.parseInt(fields[1])),
            Long.parseLong(fields[2]));
      } catch (NumberFormatException exception) {
        // same: skip it and let that chunk re-verify itself
      }
    }
    bound();
  }

  /**
   * Restores this dimension's chunk versions, but only when they were recorded against the history it now
   * holds. A mismatched hash means those chunks were synced against different changes — a restored or
   * hand-edited world — so every one of them must be re-verified rather than trusted.
   */
  void restoreVersions(String encodedVersions, String recordedHash) {
    if (ledger.head().hash().equals(recordedHash)) {
      decodeVersions(encodedVersions);
    } else {
      chunkVersion.clear();
    }
  }
}
