package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The commit log behind Omni Chunk: a <b>chronological, self-pruning history of everything the players
 * did inside a chunk</b>, expressed in chunk-local coordinates so it can be replayed into any chunk in
 * the world.
 *
 * <h2>Why a log and not a snapshot</h2>
 * A map of "slot → final block" is enough to make a chunk <em>look</em> right and is wrong for everything
 * that makes Minecraft interesting. An iron golem exists because four iron blocks were already there
 * <em>when the carved pumpkin landed</em>; TNT explodes because it was lit <em>after</em> it was placed;
 * water flows because it was poured into a shape that already existed. Order is the mechanic, so the
 * engine keeps the order and replays it — each commit applied for real, as a player would have done it.
 *
 * <h2>The Git analogy, precisely</h2>
 * <ul>
 *   <li>Every place / break / use is a <b>commit</b> appended in order. The log is the branch history.</li>
 *   <li>A commit is stored against a <b>slot</b> — the chunk-local {@code (x, y, z)} — so one log describes
 *       every chunk at once.</li>
 *   <li>Re-placing or breaking a slot <b>drops that slot's earlier commits</b> (a squash): the history that
 *       led to a block nobody can see any more is dead weight, and replaying it would be wrong as well as
 *       slow. What survives is the shortest history that still reproduces the world.</li>
 *   <li>A {@link Kind#USE} commit is an <em>event</em>, not a state, so it layers on top of the slot's
 *       block instead of replacing it — lighting TNT does not erase the fact that TNT was placed there.</li>
 *   <li>{@link #commits()} is the replay script: walk it in order and the chunk unfolds exactly as it
 *       originally happened.</li>
 * </ul>
 *
 * <p>Pure and host-free — no world, no scheduler — so the whole rule set is unit-tested
 * ({@code ChunkLedgerTest}). Persistence is a compact string ({@link #encode()}) written into the
 * experience's own world folder, so the history travels with the world and needs no database.</p>
 */
final class ChunkLedger {
  /** What a commit did. The letter is the persisted form and must stay stable. */
  enum Kind {
    /** A block was placed. Replay sets that block, with physics, so vanilla reacts to it. */
    PLACE('P'),
    /** A block was removed. Replay clears the slot. */
    BREAK('B'),
    /** An item was used on the block (lighting TNT, bone-mealing a sapling). Replay re-runs the use. */
    USE('U');

    private final char code;

    Kind(char code) {
      this.code = code;
    }

    char code() {
      return code;
    }

    static Kind of(char code) {
      for (Kind kind : values()) {
        if (kind.code == code) {
          return kind;
        }
      }
      return null;
    }
  }

  /**
   * One entry in the history, in CHUNK-LOCAL coordinates so it applies to every chunk.
   *
   * @param payload the block for {@link Kind#PLACE}, the item for {@link Kind#USE}, null for a break
   */
  record Commit(long seq, int localX, int blockY, int localZ, Kind kind, ItemKey payload) {
    /** The world position this commit targets inside the given chunk. */
    BlockPosition at(String worldName, int chunkX, int chunkZ) {
      return new BlockPosition(worldName, (chunkX << 4) + localX, blockY, (chunkZ << 4) + localZ);
    }

    /** The slot this commit belongs to — its identity for pruning. */
    long slot() {
      return ((long) localX << 40) ^ ((long) localZ << 32) ^ (blockY & 0xFFFFFFFFL);
    }

    /** Compact persisted form: {@code seq;localX;y;localZ;kind;payload}. */
    String encode() {
      return seq + ";" + localX + ";" + blockY + ";" + localZ + ";" + kind.code()
          + ";" + (payload == null ? "" : payload.qualifiedName());
    }

    static Commit decode(String encoded) {
      if (encoded == null || encoded.isBlank()) {
        return null;
      }
      String[] parts = encoded.split(";", 6);
      if (parts.length < 6) {
        return null;
      }
      Kind kind = parts[4].isEmpty() ? null : Kind.of(parts[4].charAt(0));
      if (kind == null) {
        return null;
      }
      try {
        return new Commit(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
            Integer.parseInt(parts[3]), kind, parts[5].isBlank() ? null : ItemKey.parse(parts[5]));
      } catch (NumberFormatException exception) {
        return null; // one damaged entry must never stop the rest of the history from loading
      }
    }
  }

  /**
   * Where the history currently stands: the newest commit's sequence id, and a hash over the whole
   * surviving log. A chunk records the {@code seq} it was brought up to; comparing that to {@link #seq()}
   * is the cheap "is this chunk on the latest version?" test, and the {@code hash} identifies the log
   * itself, so a chunk synced against a DIFFERENT history (a restored or edited world) is not mistaken
   * for an up-to-date one.
   */
  record Head(long seq, String hash) {
  }

  private final int maxCommits;
  private final List<Commit> log = new ArrayList<>();
  /**
   * The last computed {@link #hash()}, or null when the log has moved since. Not a micro-optimisation —
   * see {@link #head()} for the loop nest that made recomputing it per chunk freeze the server.
   */
  private String cachedHash;
  // Sequence ids are never reused, so they stay meaningful across squashes: a slot's replacement always
  // has a higher id than the commit it replaced, which is exactly what makes a delta correct.
  private long nextSeq;

  ChunkLedger(int maxCommits) {
    this.maxCommits = Math.max(1, maxCommits);
  }

  /**
   * The current head — what an up-to-date chunk should be at.
   *
   * <h2>Why the hash is cached</h2>
   * This is the hottest call in the mode and it used to recompute {@link #hash()} every time, which
   * walks the WHOLE log and builds a String per commit. That is fine once; the trouble is where it is
   * called from. {@code OmniChunkChallenge.syncChunk} asks for the head once PER CHUNK, {@code syncArea}
   * runs it over every chunk in the player's radius, and {@code commit} runs THAT per edited block — so
   * a single TNT explosion is {@code blocks × chunks × commits × characters}, with a String allocation
   * inside the innermost loop. On the live network that froze the server thread past the 10-second
   * watchdog, and a frozen node cannot renew its world lease: the placement row was taken by another
   * node and players were evicted from a world mid-session.
   *
   * <p>The log is append-and-trim only, so the cache is exact rather than approximate: every mutation
   * clears it ({@link #append}, {@link #decode}) and the next reader recomputes once.</p>
   */
  Head head() {
    return new Head(log.isEmpty() ? 0L : log.get(log.size() - 1).seq(), hash());
  }

  /** The oldest sequence id still in the log; anything below it has been trimmed away for good. */
  long baseSeq() {
    return log.isEmpty() ? 0L : log.get(0).seq();
  }

  /**
   * The commits a chunk at {@code seq} has not seen yet — its fast-forward. Because a slot's replacement
   * always carries a higher sequence id than what it replaced, applying just this delta lands the chunk in
   * the same state as replaying everything.
   */
  List<Commit> since(long seq) {
    List<Commit> pending = new ArrayList<>();
    for (Commit commit : log) {
      if (commit.seq() > seq) {
        pending.add(commit);
      }
    }
    return pending;
  }

  /**
   * Whether a chunk at {@code seq} is too far behind to fast-forward: the commits it is missing have
   * already been trimmed off the start of the log, so its only correct option is to replay everything
   * that survives (the equivalent of re-cloning rather than pulling).
   */
  boolean needsFullReplay(long seq) {
    return !log.isEmpty() && seq < baseSeq() - 1;
  }

  /**
   * A stable 64-bit hash of the whole ordered log, rendered as hex. Two ledgers with the same hash hold
   * the same history in the same order, so it identifies a version of the world's edits.
   */
  String hash() {
    String cached = cachedHash;
    if (cached != null) {
      return cached;
    }
    long value = 0xcbf29ce484222325L; // FNV-1a
    for (Commit commit : log) {
      // charAt over the encoded form rather than toCharArray(): identical result, one String per
      // commit instead of a String AND a char[] — and this runs once per commit per recompute.
      String encoded = commit.encode();
      for (int index = 0; index < encoded.length(); index++) {
        value ^= encoded.charAt(index);
        value *= 0x100000001b3L;
      }
      value ^= '\n';
      value *= 0x100000001b3L;
    }
    cached = Long.toHexString(value);
    cachedHash = cached;
    return cached;
  }

  /**
   * Appends a commit and squashes the slot's dead history.
   *
   * <p>A {@link Kind#PLACE} or {@link Kind#BREAK} makes every earlier commit for that same slot
   * irrelevant — whatever was there is gone, and so is any use that was applied to it — so they are
   * dropped. A {@link Kind#USE} keeps them, because it acts <em>on</em> the block that is already
   * there.</p>
   *
   * @return the appended commit, so the caller can replicate it immediately
   */
  Commit append(int blockX, int blockY, int blockZ, Kind kind, ItemKey payload) {
    Commit commit = new Commit(++nextSeq, ChunkStamp.localOf(blockX), blockY, ChunkStamp.localOf(blockZ),
        kind, payload);
    if (kind != Kind.USE) {
      long slot = commit.slot();
      log.removeIf(existing -> existing.slot() == slot);
    }
    log.add(commit);
    while (log.size() > maxCommits) {
      log.remove(0); // oldest first, exactly like trimming the far end of a branch
    }
    cachedHash = null; // the log moved; see head()
    return commit;
  }

  /** The replay script: every surviving commit, oldest first. */
  List<Commit> commits() {
    return List.copyOf(log);
  }

  int size() {
    return log.size();
  }

  /** Serialises the whole history for the experience's world-folder state. */
  String encode() {
    StringBuilder builder = new StringBuilder();
    for (Commit commit : log) {
      if (builder.length() > 0) {
        builder.append(',');
      }
      builder.append(commit.encode());
    }
    return builder.toString();
  }

  /** Restores a history written by {@link #encode()}; unreadable entries are skipped, never fatal. */
  void decode(String encoded) {
    log.clear();
    cachedHash = null; // the log moved; see head()
    if (encoded == null || encoded.isBlank()) {
      return;
    }
    for (String part : encoded.split(",")) {
      Commit commit = Commit.decode(part);
      if (commit != null) {
        log.add(commit);
        nextSeq = Math.max(nextSeq, commit.seq());
      }
    }
    while (log.size() > maxCommits) {
      log.remove(0);
    }
  }

  /** How many distinct slots the surviving history touches (for the HUD/debug read-out). */
  int slotCount() {
    Map<Long, Boolean> slots = new LinkedHashMap<>();
    for (Commit commit : log) {
      slots.put(commit.slot(), Boolean.TRUE);
    }
    return slots.size();
  }

  /** Whether {@code item} is one whose use on a block is worth recording and replaying. */
  static boolean recordableUse(ItemKey item, java.util.Set<String> recordedItems) {
    return item != null && recordedItems.contains(item.value().toLowerCase(Locale.ROOT));
  }
}
