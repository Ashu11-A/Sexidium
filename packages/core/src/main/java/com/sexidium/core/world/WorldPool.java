package com.sexidium.core.world;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The warm world pool: worlds that already exist, fully generated, waiting for whoever asks next.
 *
 * <h2>Why worlds are kept warm at all</h2>
 * Creating a Minecraft world is the single most expensive thing this plugin does — terrain generation, a
 * safe-spawn search and the spawn-area prep loop all run on the server thread, so a world created while
 * players are online is felt by <em>everyone</em> as a freeze. Doing it three times (an Overworld and its
 * linked Nether and End) the moment somebody presses "play" is three freezes back to back.
 *
 * <p>So the cost is paid at boot instead, into this pool, and a start becomes a hand-over: the world is
 * already there, so the player joins immediately.</p>
 *
 * <h2>One pool, keyed by shape</h2>
 * The pool is shared by <b>every</b> consumer — minigames take a disposable Overworld, experiences take an
 * Overworld plus a Nether and an End for their linked dimensions. What decides whether a warm world can
 * serve a request is its {@link WorldProfile}: its dimension, and whether its terrain is void or natural.
 * Nothing else about a warm world is fixed, so the same Nether serves any experience that needs one.
 *
 * <p>Pure bookkeeping — it creates nothing and unloads nothing. {@link AbstractWorldControl} owns the
 * refilling and the disposal; this class only answers "what is ready, and how many more of each do we
 * want?", which is what makes it unit-testable without a server.</p>
 */
public final class WorldPool {
  /** The dimensions kept warm no matter what, so an experience can be handed all three at once. */
  public static final List<WorldProfile> DEFAULT_WARM =
      List.of(WorldProfile.OVERWORLD, WorldProfile.NETHER, WorldProfile.END);

  /** How many of each default profile are kept ready. */
  public static final int DEFAULT_WARM_SIZE = 3;

  /**
   * How many Overworlds are kept ready — deeper than the others because the Overworld is what everything
   * consumes: every minigame takes one, every experience takes one, and a Death Resets regeneration takes
   * another one on every death. This is effectively "how many world hand-overs are free before somebody
   * has to wait for terrain generation".
   */
  public static final int DEFAULT_OVERWORLD_WARM_SIZE = 5;

  // Concurrent, because the two ends of this pool run on different threads: warm worlds are PRODUCED on
  // the world/global-region thread (that is the only place a world may be created), while they are
  // CONSUMED synchronously on whichever thread asked for one — a command handler, a match start, a reset.
  // On stock Paper those happen to funnel through the main thread; on a region-threaded server they do
  // not, and a plain HashMap/ArrayDeque here is a lost update or a ConcurrentModificationException in the
  // middle of handing somebody a world.
  private final Map<String, Deque<WorldHandle>> ready = new ConcurrentHashMap<>();
  private final Map<String, WorldProfile> profiles = new ConcurrentHashMap<>();
  private final Map<String, Integer> targets = new ConcurrentHashMap<>();
  private final Map<String, Integer> pending = new ConcurrentHashMap<>();
  /**
   * Configuration order, kept explicitly because the maps above no longer preserve it and the order is
   * load-bearing: warming walks this list and builds the FIRST profile that is short, so whatever is
   * configured first is what a server has ready soonest. The Overworld is configured first for that
   * reason — it is the shape every consumer needs.
   */
  private final List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();

  /** Sets how many warm worlds of {@code profile} to keep. A target of 0 disables that profile. */
  public void target(WorldProfile profile, int size) {
    if (profile == null) {
      return;
    }
    profiles.put(profile.id(), profile);
    targets.put(profile.id(), Math.max(0, size));
    if (!order.contains(profile.id())) {
      order.add(profile.id());
    }
  }

  /** How many warm worlds of {@code profile} are wanted (0 when the profile is not pooled). */
  public int target(WorldProfile profile) {
    return profile == null ? 0 : targets.getOrDefault(profile.id(), 0);
  }

  /** Every profile with a non-zero target, in the order they were configured. */
  public List<WorldProfile> pooledProfiles() {
    List<WorldProfile> result = new ArrayList<>();
    for (String id : order) {
      if (targets.getOrDefault(id, 0) > 0) {
        WorldProfile profile = profiles.get(id);
        if (profile != null) {
          result.add(profile);
        }
      }
    }
    return result;
  }

  /**
   * The next warm world of {@code profile}, or null when none is ready. The caller becomes its owner —
   * the pool forgets it entirely, so nothing else can hand out the same world.
   */
  public WorldHandle poll(WorldProfile profile) {
    Deque<WorldHandle> queue = ready.get(key(profile));
    return queue == null ? null : queue.pollFirst();
  }

  /** Adds a freshly created world to the pool. */
  public void offer(WorldProfile profile, WorldHandle handle) {
    if (handle == null || profile == null) {
      return;
    }
    profiles.putIfAbsent(profile.id(), profile);
    ready.computeIfAbsent(profile.id(), ignored -> new ConcurrentLinkedDeque<>()).addLast(handle);
  }

  /** How many warm worlds of {@code profile} are ready right now. */
  public int size(WorldProfile profile) {
    Deque<WorldHandle> queue = ready.get(key(profile));
    return queue == null ? 0 : queue.size();
  }

  /**
   * How many more worlds of {@code profile} should be created: the target, less what is ready and less
   * what is already being built.
   *
   * <p>Worlds already handed out deliberately do NOT count against the target. The old pool subtracted
   * them, which meant three simultaneous matches emptied the pool and stopped it refilling — so the
   * fourth player to press play paid the full generation cost, and the pool looked to an operator like it
   * was being ignored. The target is how many are kept <em>ready</em>, not how many exist.</p>
   */
  public int shortfall(WorldProfile profile) {
    return target(profile) - size(profile) - inFlight(profile);
  }

  /** Marks that one world of {@code profile} is being created (so a refill is not requested twice). */
  public void beginCreate(WorldProfile profile) {
    pending.merge(key(profile), 1, Integer::sum);
  }

  /** Marks a create finished, whether it succeeded or not. */
  public void endCreate(WorldProfile profile) {
    pending.computeIfPresent(key(profile), (ignored, count) -> count <= 1 ? null : count - 1);
  }

  /** How many worlds of {@code profile} are mid-creation. */
  public int inFlight(WorldProfile profile) {
    return pending.getOrDefault(key(profile), 0);
  }

  /** Every warm world across every profile — what the GC must never reclaim. */
  public List<WorldHandle> all() {
    List<WorldHandle> handles = new ArrayList<>();
    for (Deque<WorldHandle> queue : ready.values()) {
      handles.addAll(queue);
    }
    return handles;
  }

  /** Whether {@code runtimeName} is a warm world (matched by the shared naming rules). */
  public boolean contains(String runtimeName) {
    for (WorldHandle handle : all()) {
      if (handle != null && WorldNaming.sameWorld(handle.runtimeName(), runtimeName)) {
        return true;
      }
    }
    return false;
  }

  /** Total warm worlds ready across every profile. */
  public int totalReady() {
    int total = 0;
    for (Deque<WorldHandle> queue : ready.values()) {
      total += queue.size();
    }
    return total;
  }

  /** Empties the pool and returns what was in it, so the caller can dispose of each world. */
  public List<WorldHandle> drain() {
    List<WorldHandle> handles = all();
    ready.clear();
    pending.clear();
    return handles;
  }

  /** A one-line read-out of what is warm, for the boot log and {@code /sx admin} diagnostics. */
  public String describe() {
    StringBuilder builder = new StringBuilder();
    for (WorldProfile profile : pooledProfiles()) {
      if (builder.length() > 0) {
        builder.append(", ");
      }
      builder.append(profile.id()).append(' ').append(size(profile)).append('/').append(target(profile));
    }
    return builder.length() == 0 ? "disabled" : builder.toString();
  }

  private static String key(WorldProfile profile) {
    return profile == null ? WorldProfile.OVERWORLD.id() : profile.id();
  }
}
