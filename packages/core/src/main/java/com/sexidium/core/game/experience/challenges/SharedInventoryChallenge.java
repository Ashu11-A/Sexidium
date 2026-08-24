package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.InventoryChangeGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemStackData;

import java.util.ArrayDeque;
import java.util.BitSet;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every participant shares one inventory. A single authoritative SLOT array is the source of truth; each
 * tick a <em>sliding window</em> of player actions (dropping, picking up, dragging, crafting — every one
 * surfaces as a per-slot change) is folded into it and the changed slots pushed back to everyone.
 *
 * <h2>Why a window (the duplication fix)</h2>
 * The previous model folded each dirty player's slots into the shared array while <em>mutating that same
 * array mid-pass</em>. So when player A dropped a shared item, player B's not-yet-updated (stale) copy of
 * that slot — read later in the same pass, or on the periodic reconcile that re-reads everyone — no longer
 * matched the (already cleared) shared value and was mistaken for a fresh change, RE-ADDING the item: the
 * dropper got it back while the dropped entity still existed on the ground. Classic shared-inventory dupe.
 *
 * <p>This rewrite diffs every player against a {@code before} SNAPSHOT taken at the start of the pass, not
 * the array as it is being mutated. Because every online player's storage equals the shared array right
 * after each push, a slot that differs from the snapshot was genuinely changed by <em>that</em> player;
 * a stale copy still equals the snapshot and therefore never counts as a change. Removals win over adds
 * within a pass, so a drop can never be resurrected by a concurrent add. Detected changes are appended to
 * a rolling {@code window-ticks} (default 200 = 10 s at 20 tps) history for conflict arbitration + debug.</p>
 *
 * <p>Diffing is exact per slot (full {@link ItemStackData} equality, including serialized item bytes), so
 * enchantments/durability/components survive. Open-ended: never ends; a joiner is pulled into the pool.</p>
 */
public final class SharedInventoryChallenge extends Challenge {
  // Authoritative slot-indexed model (null = empty slot), sized to the storage capacity.
  private ItemStackData[] shared = new ItemStackData[0];
  // Players whose inventory changed since the last apply (re-read next tick). No writes on the event thread.
  private final Set<UUID> dirty = new HashSet<>();
  // Rolling history of applied per-slot actions (drop/pickup/drag/craft), pruned to windowTicks each pass.
  private final Deque<SlotAction> window = new ArrayDeque<>();
  private int applyPeriod;
  private int reconcilePeriod;
  private int windowTicks;
  private long sinceReconcile;
  // Monotonic age counter in SERVER ticks (advances by applyPeriod each pass) used to age the window.
  private long ticks;

  /** One applied inventory action: {@code from -> to} in {@code slot}, by {@code player}, at {@code tick}. */
  private record SlotAction(long tick, UUID player, int slot, ItemStackData from, ItemStackData to) {
  }

  public SharedInventoryChallenge() {
    super("sharedinventory", "Shared Inventory");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Shared slots:</gray> <white>" + filledSlots() + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Apply period", applyPeriod + "t");
      context.debugStat("Reconcile period", reconcilePeriod + "t");
      context.debugStat("Window", windowTicks + "t");
      context.debugStat("Window actions", window.size());
      context.debugStat("Dirty readers", dirty.size());
      context.debugStat("Sharing players", online().size());
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    // Seed the shared pool from the FIRST participant's (host-restored) storage. Seeding from one is
    // correct: on a restart everyone returns with the identical saved shared inventory, so merging would
    // duplicate, and the host already cleared lobby items on entry.
    List<PlayerAdapter> online = online();
    int capacity = online.isEmpty() ? 36 : online.get(0).inventory().storageCapacity();
    shared = new ItemStackData[capacity];
    if (!online.isEmpty()) {
      List<ItemStackData> seed = online.get(0).inventory().storageSlots();
      for (int index = 0; index < capacity && index < seed.size(); index++) {
        shared[index] = seed.get(index);
      }
    }
    for (PlayerAdapter player : online) {
      writeAllSlots(player);
    }
    window.clear();
    ticks = 0;
    sinceReconcile = 0;
    applyPeriod = Math.max(1, cfg().getInt(configPath("apply-period-ticks"), 1));
    reconcilePeriod = Math.max(applyPeriod, cfg().getInt(configPath("reconcile-period-ticks"), 20));
    // 10 s of history at 20 tps. The window is measured in SERVER ticks, so it stays "10 s" regardless of
    // the apply period.
    windowTicks = Math.max(applyPeriod, cfg().getInt(configPath("window-ticks"), 200));
    runTimer(this::apply, applyPeriod, applyPeriod);
  }

  @Override
  public void onInventoryChange(InventoryChangeGameEvent event) {
    if (isParticipant(event.playerAdapter())) {
      dirty.add(event.playerAdapter().uniqueId());
    }
  }

  @Override
  public void onPlayerJoin(PlayerAdapter playerAdapter) {
    if (playerAdapter == null) {
      return;
    }
    // Pull the joiner into the shared inventory; do not let their prior inventory feed the pool.
    writeAllSlots(playerAdapter);
    dirty.remove(playerAdapter.uniqueId());
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      dirty.remove(playerAdapter.uniqueId());
    }
  }

  /**
   * One window pass (runs {@code applyPeriod} ticks, i.e. 20x/s by default): snapshot the shared slots,
   * detect each acting player's genuine per-slot actions against that snapshot, fold them in (removals
   * beat adds so a drop is never resurrected), record them in the rolling window and push the changed
   * slots to everyone.
   */
  private void apply() {
    if (shared.length == 0) {
      return;
    }
    ticks += applyPeriod;
    Set<UUID> toPull = new HashSet<>(dirty);
    dirty.clear();
    sinceReconcile += applyPeriod;
    boolean reconcileNow = sinceReconcile >= reconcilePeriod;
    if (reconcileNow) {
      sinceReconcile = 0;
      for (PlayerAdapter player : online()) {
        toPull.add(player.uniqueId());
      }
    }
    if (toPull.isEmpty()) {
      pruneWindow();
      return;
    }
    // Snapshot BEFORE any fold: every online player's storage equals this right after the last push, so a
    // slot that now differs was changed by THAT player — a stale copy still equals the snapshot and is
    // never mistaken for a change. This is the single line that kills the drop-duplication.
    ItemStackData[] before = shared.clone();
    // Resolved new value per genuinely-changed slot. A removal (-> null) claims the slot and blocks a
    // same-pass add from resurrecting the item, regardless of player iteration order.
    Map<Integer, ItemStackData> resolved = new LinkedHashMap<>();
    Set<Integer> removedThisPass = new HashSet<>();
    for (PlayerAdapter player : online()) {
      if (!toPull.contains(player.uniqueId())) {
        continue;
      }
      List<ItemStackData> slots = player.inventory().storageSlots();
      for (int index = 0; index < shared.length; index++) {
        ItemStackData current = index < slots.size() ? slots.get(index) : null;
        if (equalStack(current, before[index])) {
          continue; // unchanged for this player (or a stale, not-yet-synced copy) — not their action
        }
        window.addLast(new SlotAction(ticks, player.uniqueId(), index, before[index], current));
        if (current == null) {
          resolved.put(index, null);
          removedThisPass.add(index);
        } else if (!removedThisPass.contains(index)) {
          resolved.put(index, current);
        }
      }
    }
    pruneWindow();
    BitSet changed = new BitSet(shared.length);
    for (Map.Entry<Integer, ItemStackData> entry : resolved.entrySet()) {
      int index = entry.getKey();
      if (!equalStack(entry.getValue(), shared[index])) {
        shared[index] = entry.getValue();
        changed.set(index);
      }
    }
    if (changed.isEmpty()) {
      return;
    }
    // Push only the changed slots to everyone; afterwards every online player's storage == shared again,
    // which is the invariant the next pass's snapshot diff relies on.
    for (PlayerAdapter player : online()) {
      InventoryAdapter inventory = player.inventory();
      for (int index = changed.nextSetBit(0); index >= 0; index = changed.nextSetBit(index + 1)) {
        inventory.setSlot(index, shared[index]);
      }
    }
  }

  /** Drops window entries older than the rolling {@code windowTicks} horizon. */
  private void pruneWindow() {
    long horizon = ticks - windowTicks;
    while (!window.isEmpty() && window.peekFirst().tick() <= horizon) {
      window.removeFirst();
    }
  }

  private void writeAllSlots(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    InventoryAdapter inventory = player.inventory();
    for (int index = 0; index < shared.length; index++) {
      inventory.setSlot(index, shared[index]);
    }
  }

  private int filledSlots() {
    int count = 0;
    for (ItemStackData stack : shared) {
      if (stack != null) {
        count++;
      }
    }
    return count;
  }

  private static boolean equalStack(ItemStackData a, ItemStackData b) {
    return a == null ? b == null : a.equals(b);
  }
}
