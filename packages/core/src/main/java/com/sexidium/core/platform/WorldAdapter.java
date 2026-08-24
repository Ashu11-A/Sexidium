package com.sexidium.core.platform;

import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BrokenBlock;
import com.sexidium.core.platform.model.DuplicableKind;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.SafeSpawn;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface WorldAdapter {
  String name();

  WorldPosition spawnPosition();

  /**
   * A spawn position guaranteed to sit ON TOP of a block — never buried in terrain, never submerged in
   * water/lava and never hanging in mid-air. A freshly generated world's raw {@link #spawnPosition()}
   * can be any of those, so the search (see {@link SafeSpawn}) walks the spawn column down to solid
   * ground with head room and, when that column is unusable, moves outward to the NEAREST column that
   * works. This is the safe target every "send the player into the world" teleport should use.
   */
  default WorldPosition safeSpawnPosition() {
    return SafeSpawn.resolve(this);
  }

  /**
   * As {@link #safeSpawnPosition()} but anchored at an arbitrary position instead of the world spawn —
   * for dropping a player into a chosen starting dimension at its spawn area.
   */
  default WorldPosition safePositionNear(WorldPosition around) {
    return SafeSpawn.near(this, around);
  }

  /** Whether this world is a NETHER-environment dimension. Lets a challenge detect a player entering the
   * (void) Nether so it can build/seat them there. Default false. */
  default boolean isNether() {
    return false;
  }

  /** Whether this world is an END-environment dimension. Default false. */
  default boolean isEnd() {
    return false;
  }

  /**
   * This world's linked sibling for {@code dimension} — for an experience world, its own Nether / End /
   * Overworld — or {@code null} when the platform has no such sibling (or this is not a managed
   * experience world). Asking for the dimension this world already IS returns this world. Lets the core
   * start a player in a chosen dimension without knowing how a backend names or keys its siblings.
   */
  default WorldAdapter dimension(WorldDimension dimension) {
    return dimension == null || dimension == currentDimension() ? this : null;
  }

  /**
   * The adapter for the world NAMED by {@code worldName}, or this one when the name is blank or does not
   * resolve.
   *
   * <p>Most of this interface takes a position, and a position carries its world name, so those calls are
   * already dimension-correct — an experience's single adapter services a drop or a block edit in its
   * Nether by routing on the name. The exceptions are the handful of operations that describe a WORLD
   * rather than a point: {@link #isChunkLoaded}, {@link #loadChunk}, {@link #convertChunk} and the build
   * height limits. Those silently answered for the Overworld wherever a mode was really working in a
   * sibling — a Nether chunk was tested for residency against the Overworld, and Nether work was measured
   * against the Overworld's -64…320 range instead of its own 0…128. This is how a mode gets the right
   * world to ask.</p>
   *
   * <p>Default returns this adapter, so a platform without the capability behaves exactly as before.</p>
   */
  default WorldAdapter inWorld(String worldName) {
    return this;
  }

  /**
   * Loads (generating if necessary) the chunk containing {@code position}, then runs {@code onReady}.
   *
   * <p>This exists so an entry can make its two halves ATOMIC. Telling a client it is entering a
   * different kind of world costs it its loaded world — it discards everything and waits to be sent a
   * new one — so that message is only safe when the move that follows lands immediately. A move into a
   * freshly generated world does not: it has to generate the destination chunk first, and for that whole
   * window the client is sitting in nothing, with frozen entities and no chunks, unable to interact with
   * anything until it reconnects. Warming the chunk BEFORE the message closes the window.</p>
   *
   * <p>The default runs {@code onReady} immediately, which is correct for a platform whose worlds are
   * always fully resident.</p>
   */
  default void preloadChunk(WorldPosition position, Runnable onReady) {
    if (onReady != null) {
      onReady.run();
    }
  }

  /**
   * Turns the "keep your items when you die" rule on or off for THIS world. Default no-op on platforms
   * without the rule. Callers usually want {@link #setKeepInventoryEverywhere} instead.
   */
  default void setKeepInventory(boolean keepInventory) {
  }

  /**
   * Applies the keep-inventory rule to this world AND every linked dimension of it (its Nether and its
   * End). A player who walks into their experience's Nether must not silently lose the rule they chose,
   * so the setting is a property of the whole experience, not of the world they happened to start in.
   * Dimensions the platform does not provide are skipped.
   */
  default void setKeepInventoryEverywhere(boolean keepInventory) {
    setKeepInventory(keepInventory);
    for (WorldDimension dimension : WorldDimension.values()) {
      WorldAdapter sibling = dimension(dimension);
      if (sibling != null && sibling != this) {
        sibling.setKeepInventory(keepInventory);
      }
    }
  }

  /**
   * Puts this world into (or out of) HARDCORE: the difficulty is pinned to hard, and a death here is
   * final in the vanilla sense. This is the SERVER's half of hardcore only — it changes nothing about
   * what any client is drawing, because a client is told what a world is when it is sent the world and
   * not again. The other half, the hardened heart texture, is
   * {@link PlayerAdapter#setHardcoreView(boolean)}. Default no-op on platforms without the concept.
   */
  default void setHardcore(boolean hardcore) {
  }

  /**
   * Applies hardcore to this world AND every linked dimension of it. A player who walks into their
   * experience's Nether must not quietly stop being in a hardcore world — the stakes are a property of
   * the whole experience, not of the world they happened to start in.
   */
  default void setHardcoreEverywhere(boolean hardcore) {
    setHardcore(hardcore);
    for (WorldDimension dimension : WorldDimension.values()) {
      WorldAdapter sibling = dimension(dimension);
      if (sibling != null && sibling != this) {
        sibling.setHardcore(hardcore);
      }
    }
  }

  /** Which dimension class this world is, derived from {@link #isNether()} / {@link #isEnd()}. */
  default WorldDimension currentDimension() {
    if (isNether()) {
      return WorldDimension.NETHER;
    }
    return isEnd() ? WorldDimension.END : WorldDimension.OVERWORLD;
  }

  List<PlayerAdapter> players();

  void dropItem(WorldPosition targetPosition, ItemStackData itemStackData);

  /**
   * Drops an item, optionally WITHOUT the usual random scatter velocity. Pass {@code scatter=false} to spawn
   * the item exactly at {@code targetPosition} with no velocity, so it drops straight down and stays put —
   * used by the Random-Skyblock one-block so the reward lands on top of the block instead of being flung off
   * the tiny platform into the void. Default ignores the flag and delegates to {@link #dropItem(WorldPosition, ItemStackData)}.
   */
  default void dropItem(WorldPosition targetPosition, ItemStackData itemStackData, boolean scatter) {
    dropItem(targetPosition, itemStackData);
  }

  /**
   * Spawns a primed TNT entity at the given position. Platforms that cannot
   * spawn entities may leave this as a no-op.
   */
  default void spawnTnt(WorldPosition targetPosition, int fuseTicks) {
  }

  default void spawnTnt(WorldPosition targetPosition, int fuseTicks, double velocityX, double velocityY, double velocityZ, float explosionYield) {
    spawnTnt(targetPosition, fuseTicks);
  }

  default void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
  }

  /**
   * Sets a block the way a PLAYER would: with block updates and physics, so the game itself reacts to it
   * — a carved pumpkin completing an iron/snow golem actually spawns one, water and lava start flowing,
   * sand falls, redstone fires, saplings check their space. {@link #setBlock} deliberately skips all of
   * that for bulk edits; this is its counterpart for edits that are supposed to be real.
   *
   * <p>Default delegates to {@link #setBlock}, so a platform that cannot request physics still places the
   * block — it just will not trigger the knock-on behaviour.</p>
   */
  default void setBlockNatural(BlockPosition blockPosition, ItemKey itemKey) {
    setBlock(blockPosition, itemKey);
  }

  /**
   * Re-runs "a player used {@code itemKey} on this block" — lighting TNT into a primed, genuinely
   * exploding entity, bone-mealing a sapling into a tree. Returns false when the platform does not
   * simulate that item, so a caller can tell "nothing happened" from "not supported". Default: unsupported.
   */
  default boolean useItemOn(BlockPosition blockPosition, ItemKey itemKey) {
    return false;
  }

  /**
   * Removes every block of {@code blockKey} within {@code radius} (a cube half-extent, in blocks) of
   * {@code center}, optionally dropping the normal items. This is a bounded approximation of a
   * "world-wide" sweep (the full world is impractical to scan per break) — it captures the bulk
   * vanishing of one block type around active players. Used by the Break-One-Break-All and
   * Block-Deleter challenges. Returns the number of blocks removed. Default no-op returns 0.
   */
  default int removeBlocksOfType(WorldPosition center, double radius, ItemKey blockKey, boolean dropItems) {
    return 0;
  }

  /**
   * Replaces every existing (non-air) block in the given chunk between {@code minY} and {@code maxY}
   * (inclusive, clamped to world height) with {@code blockKey}, turning a chunk into a single block
   * type. Only blocks that are already there are swapped — air is never filled in, so the chunk's
   * existing shape is preserved. Any block whose registry value (lowercased, no namespace, e.g.
   * {@code oak_leaves}) is in {@code preservedBlockValues} is left untouched, so a caller can keep
   * tree canopies and other blocks intact. Used by the Random-Chunks challenge. Returns the number of
   * blocks replaced. Default no-op returns 0.
   */
  default int convertChunk(int chunkX, int chunkZ, ItemKey blockKey, int minY, int maxY, Set<String> preservedBlockValues) {
    return 0;
  }

  default ItemKey blockTypeAt(BlockPosition blockPosition) {
    return ItemKey.minecraft("air");
  }

  /**
   * Places a chest at {@code blockPosition} and fills it with {@code contents} (spread across the chest's
   * slots). Used by the SkyBlock-style challenges to plant loot chests. Platforms that cannot reach a
   * chest's inventory leave this as a no-op. Delegates to {@link #placeChest(BlockPosition, List, String)}
   * with no explicit facing.
   */
  /**
   * Places a monster spawner already configured to spawn {@code entityType}. A spawner set by
   * {@link #setBlock} alone is inert (vanilla defaults it to nothing), so a generator that wants a layer
   * to actually be dangerous has to say what it spawns. Default: falls back to a plain spawner block, so
   * a platform without the capability still produces the right terrain.
   */
  default void placeSpawner(BlockPosition blockPosition, String entityType) {
    setBlock(blockPosition, ItemKey.minecraft("spawner"));
  }

  default void placeChest(BlockPosition blockPosition, List<ItemStackData> contents) {
    placeChest(blockPosition, contents, null);
  }

  /**
   * As {@link #placeChest(BlockPosition, List)} but orients the chest to {@code facing} — one of
   * {@code north}/{@code south}/{@code east}/{@code west} (the direction the chest front faces);
   * {@code null}/unknown keeps the platform default. Default no-op.
   */
  default void placeChest(BlockPosition blockPosition, List<ItemStackData> contents, String facing) {
  }

  /**
   * Whether the block at {@code blockPosition} is NOT a chest, or is a chest with no items left. Used by
   * the Random-Skyblock reward chest, which auto-removes once the player has taken everything. Default
   * returns true (treat as empty/absent).
   */
  default boolean chestEmpty(BlockPosition blockPosition) {
    return true;
  }

  /**
   * If the block at {@code blockPosition} has a type whose registry value (lowercased, no namespace)
   * is in {@code typeValues}, removes it <strong>without dropping anything</strong> and returns that
   * block's item key; otherwise leaves it untouched and returns null. Removing without a scattered
   * per-block drop lets the caller accumulate a large batch of removals and re-drop them <em>merged</em>
   * as a few max-size stacks at a single location — the Break-One-Break-All optimization that keeps the
   * ground-item entity count low. Default no-op returns null.
   */
  default ItemKey breakIfType(BlockPosition blockPosition, Set<String> typeValues) {
    return null;
  }

  /**
   * Whether this platform can actually compute a block's vanilla loot ({@link #naturalDrops}). When it
   * cannot, callers must keep their "one of the block itself" fallback rather than concluding that a
   * break legitimately dropped nothing — the two are indistinguishable from an empty result alone.
   * Default false; a backend that implements {@code naturalDrops} overrides it to true.
   */
  default boolean resolvesBlockLoot() {
    return false;
  }

  /**
   * The vanilla loot the block currently at {@code blockPosition} would yield when broken with an
   * appropriate tool — e.g. {@code stone} resolves to {@code cobblestone}, {@code coal_ore} to
   * {@code coal}. Empty when the block is air or the platform cannot compute loot. Lets a challenge
   * emit the natural break result rather than the block item itself. Default returns empty.
   */
  default List<ItemStackData> naturalDrops(BlockPosition blockPosition) {
    return List.of();
  }

  /**
   * The vanilla loot the block at {@code blockPosition} yields when broken with {@code breaker}'s CURRENT
   * held tool — so it respects the tool (ore → raw item with a pickaxe, the block itself with Silk Touch,
   * extra drops with Fortune, and nothing when the tool is the wrong tier). Unlike {@link #naturalDrops(BlockPosition)}
   * (which assumes a best-in-class tool), this reflects what the player actually used. Default falls back to
   * the tool-agnostic {@link #naturalDrops(BlockPosition)}.
   */
  default List<ItemStackData> naturalDrops(BlockPosition blockPosition, PlayerAdapter breaker) {
    return naturalDrops(blockPosition);
  }

  /**
   * Like {@link #breakIfType} but returns the removed block's type plus its natural loot (see
   * {@link #naturalDrops}) instead of the block item, computed BEFORE the block is removed. Returns
   * null when no block of a matching type was present (nothing removed); a {@link BrokenBlock} with an
   * empty drop list means a matching block was removed but it drops nothing. The default falls back to
   * {@link #breakIfType}, which yields the block item as both type and loot.
   */
  default BrokenBlock breakIfTypeNatural(BlockPosition blockPosition, Set<String> typeValues) {
    ItemKey key = breakIfType(blockPosition, typeValues);
    return key == null ? null : new BrokenBlock(key, List.of(new ItemStackData(key, 1, java.util.Map.of())));
  }

  /**
   * Spawns a single dropped item entity carrying {@code amount} of {@code itemKey} (which may exceed the
   * vanilla 64-cap) and returns a handle to it, so the core
   * {@link com.sexidium.core.game.experience.compose.StackMergeService} can track and later consolidate
   * it. Returns {@code null} on platforms that cannot hand back an item-entity handle (the caller then
   * falls back to {@link #dropItem}).
   */
  default ItemEntityHandle spawnItemEntity(WorldPosition position, ItemKey itemKey, int amount) {
    return null;
  }

  /** Handles to every dropped item entity within {@code radius} of {@code position}. Default empty. */
  default List<ItemEntityHandle> nearbyItems(WorldPosition position, double radius) {
    return List.of();
  }

  /**
   * Fills the dispenser at the given position with TNT (every slot), so it dispenses primed TNT
   * endlessly when fired by redstone without anyone having to stock it. Used by TNT War's
   * infinite-dispenser machines. Platforms that cannot reach a block entity's inventory leave this as a
   * no-op.
   */
  default void fillDispenserWithTnt(BlockPosition blockPosition) {
  }

  default List<MobHandle> nearbyMobs(WorldPosition centerPosition, double radius, boolean includePassive) {
    return List.of();
  }

  /**
   * How many entities of the given {@link DuplicableKind kinds} are alive within {@code radius} of
   * {@code center} — a COUNT, not handles, because the caller only needs the population figure and
   * building a handle per entity is exactly the cost this avoids at the scale it is asked about.
   *
   * <p>This is the live-entity ceiling behind {@link PlayerAdapter#duplicateNearbyEntities}: an
   * exponential mode has to be able to ask "is this area already saturated?" before it doubles it again.
   * Default 0 — a platform that cannot answer reports an empty area, so the ceiling never engages there
   * (and neither does the duplication it guards).</p>
   */
  default int countNearbyEntities(WorldPosition center, double radius, Set<DuplicableKind> kinds) {
    return 0;
  }

  /**
   * Spawns {@code count} entities of the vanilla type {@code entityType} (e.g. {@code zombie},
   * {@code skeleton}) at (or spread slightly around) {@code position}. Used by the Random-Layers
   * challenge to populate a mob-spawn layer. Platforms that cannot spawn arbitrary entities leave this as
   * a no-op. Default no-op.
   */
  default void spawnMob(WorldPosition position, String entityType, int count) {
    spawnMob(position, entityType, count, 0.0);
  }

  /**
   * As {@link #spawnMob(WorldPosition, String, int)} but each spawned mob has a {@code equipChance}
   * (0..1) probability of being given randomized armour and a weapon (sword/axe/trident/bow) — used by the
   * Random-Mob challenge to make hostiles a real threat. Passive mobs are left unarmed. Default no-op.
   */
  default void spawnMob(WorldPosition position, String entityType, int count, double equipChance) {
  }

  /**
   * Strikes lightning at {@code position} (real, damaging lightning). Used by the Random-Events engine's
   * "lightning" event. Platforms that cannot spawn lightning leave this as a no-op. Default no-op.
   */
  default void strikeLightning(WorldPosition position) {
  }

  /**
   * Draws a short-lived line of rope-coloured particles from {@code from} to {@code to}, bowed downward
   * by {@code sag} blocks at the midpoint (a catenary) so it reads as a hanging rope — the caller passes a
   * larger sag when the rope is slack and a near-zero sag when it is pulled taut, giving an elastic look.
   * Called every tick by the Chained-Together challenge; particles render on Java and Bedrock (Geyser)
   * alike, so the rope is always visible. Default no-op.
   */
  default void drawRope(WorldPosition from, WorldPosition to, double sag) {
  }

  /**
   * Spawns a single coloured dust particle at {@code at}. {@code rgb} is a packed {@code 0xRRGGBB}
   * colour and {@code size} the particle scale. Used by {@link com.sexidium.core.world.map.RegionRenderer}
   * to draw team-region wireframes for the map editor / debugger; particles render on Java and Bedrock
   * (Geyser) alike. Default no-op so an unported platform simply shows nothing.
   */
  default void spawnDust(WorldPosition at, int rgb, float size) {
  }

  /**
   * Whether this platform can render the Chained-Together rope as a real Minecraft lead (a leashed
   * invisible marker entity per link) rather than the particle line. The challenge prefers the native
   * lead when this is true and {@code rope-style: leash}, and falls back to {@link #drawRope} otherwise.
   * Default false so an unported platform keeps the particle rope.
   */
  default boolean supportsLeashRope() {
    return false;
  }

  /**
   * Spawns an invisible, AI-less, non-persistent marker entity at {@code at} and leashes it to the player
   * {@code holderPlayerId} (if online), so a native Minecraft lead renders from that player's hand to the
   * marker. The marker is tagged {@code sx_rope_marker} so {@link #removeRopeMarkers} can sweep a stray
   * one. Returns the marker's id (to drive it with {@link #moveRopeMarker} / {@link #removeRopeMarker}),
   * or null when unsupported. Default no-op returns null.
   */
  default UUID spawnRopeMarker(WorldPosition at, UUID holderPlayerId) {
    return null;
  }

  /** Teleports the rope marker {@code markerId} to {@code to} (it is driven onto the roped neighbour each
   * tick so the lead spans the two players). Default no-op. */
  default void moveRopeMarker(UUID markerId, WorldPosition to) {
  }

  /**
   * Drives the rope marker {@code markerId} onto {@code to} AND re-leashes it to {@code holderPlayerId}
   * when the lead has broken (a holder's death/disconnect unleashes it). Re-leashing only when actually
   * detached avoids resending the leash packet every tick. Lets the rope self-heal onto a respawned player
   * without relying on a respawn event. Default no-op.
   */
  default void driveRopeMarker(UUID markerId, UUID holderPlayerId, WorldPosition to) {
  }

  /** Unleashes and removes the rope marker {@code markerId} (no lead item drops). Default no-op. */
  default void removeRopeMarker(UUID markerId) {
  }

  /**
   * Sweeps leftover rope-marker entities an earlier (leash-based) build of the Chained-Together challenge
   * left in this world: any entity tagged {@code sx_rope_marker}, plus any AI-disabled Silverfish (the
   * marker's signature — no legitimate Silverfish in a player experience world has its AI off). They are
   * unleashed first so no lead item drops. Returns the count removed. Default no-op returns 0.
   */
  default int removeRopeMarkers() {
    return 0;
  }

  /**
   * Resolves a fresh, live {@link MobHandle} for the mob with the given id in this world, or null if it
   * no longer exists. Lets a caller persist only a mob's id and re-resolve the entity right before
   * acting on it (the Total-Cleave tracker), instead of holding an entity reference that goes stale
   * across ticks / chunk reloads and loses the mob's position. Default no-op returns null.
   */
  default MobHandle mobById(UUID id) {
    return null;
  }

  default long fullTimeTicks() {
    return 0L;
  }

  /**
   * Winds this world's clock forward to the next occurrence of the given time of day (0–24000), leaving
   * the day/night cycle running.
   *
   * <p>Distinct from {@link com.sexidium.core.world.WorldSettings#lockTime()}, which freezes the cycle
   * at a fixed hour. This is a one-off nudge: for a world a player is about to be dropped into, the
   * difference between arriving at dawn and arriving in the dark with the mobs already out is the
   * difference between a start and an ambush.</p>
   *
   * <p><b>Forward only</b>, matching the platform: winding a world's clock backwards would make elapsed
   * time non-monotonic, which anything counting days off it (Death Resets) reads as the world having
   * been replaced. Expect a jump of up to one full day.</p>
   *
   * <p>Default no-op, for a platform with no clock to set.</p>
   */
  default void setTimeOfDay(long timeOfDayTicks) {
  }

  /**
   * Highest non-air block Y at the given column, loading/generating the chunk if needed, or
   * {@link Integer#MIN_VALUE} when the platform cannot report it. Lets a caller drop a marker/structure
   * onto the actual terrain surface at a far location instead of a fixed spawn Y (the Race structure
   * objective). Default unsupported.
   */
  /**
   * A habitable LAND position to use as this world's spawn — what vanilla looks for when it decides where
   * a new world starts, instead of simply using (0, 0).
   *
   * <p>This exists because (0, 0) is an arbitrary point in a random seed and is very often open ocean, so
   * pinning the spawn there dropped players straight into the sea. The search is over the world's biome
   * source rather than its terrain, so it costs no chunk generation and can afford to look thousands of
   * blocks out — far enough to actually leave an ocean, which a terrain probe never could.</p>
   *
   * <p>Settling on a ground-level Y for that column is the part that DOES cost a chunk generation, and an
   * implementation is free to do it synchronously here. Callers on a server/region thread must therefore
   * treat this as blocking, and reach for the platform's async path instead where one exists.</p>
   *
   * <p>Returns null when the platform cannot look (the default), and the caller keeps whatever spawn the
   * world already had.</p>
   */
  default WorldPosition locateLandSpawn(String worldName, int maxRadius) {
    return null;
  }

  default int highestSolidBlockY(String worldName, int blockX, int blockZ) {
    return Integer.MIN_VALUE;
  }

  /** Lowest buildable Y in this world (inclusive). Defaults to the 1.18+ overworld floor. */
  default int minBuildHeight() {
    return -64;
  }

  /** One past the highest buildable Y in this world (exclusive top). Defaults to the 1.18+ overworld. */
  default int maxBuildHeight() {
    return 320;
  }

  void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch);

  void setBorder(WorldBorderSpec worldBorderSpec);

  void resetBorder();

  void loadChunk(int chunkX, int chunkZ, boolean generate);

  /**
   * Whether the chunk is ALREADY loaded. Lets a caller edit only chunks that are resident instead of
   * forcing hundreds of chunk generations on the server thread — the difference between a smooth bulk
   * edit and a multi-second freeze. Default {@code true} means "cannot tell, assume yes", which keeps a
   * platform without the capability behaving exactly as it did before.
   */
  default boolean isChunkLoaded(int chunkX, int chunkZ) {
    return true;
  }
}
