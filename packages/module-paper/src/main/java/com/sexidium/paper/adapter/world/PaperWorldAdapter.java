package com.sexidium.paper.adapter.world;

import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import com.sexidium.paper.adapter.util.PaperConverters;
import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.BrokenBlock;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.gen.PortalFrame;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Silverfish;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class PaperWorldAdapter implements WorldAdapter {
  private final World world;
  private final String canonicalName;

  public PaperWorldAdapter(World world) {
    this(world, null);
  }

  /**
   * Wraps {@code world} but reports {@code canonicalName} from {@link #name()} instead of the Bukkit
   * world name. Sexidium-managed worlds are keyed ({@code world/dimensions/<ns>/<key>}); after a restart
   * Paper may report a keyed world's name in a form the core's matching does not recognise, so a managed
   * handle pins the stable canonical runtime name (e.g. {@code experiences/<nick>/<map>_<id>}) here.
   */
  public PaperWorldAdapter(World world, String canonicalName) {
    this.world = world;
    this.canonicalName = canonicalName;
  }

  public World handle() {
    return world;
  }

  @Override
  public String name() {
    return canonicalName != null ? canonicalName : world.getName();
  }

  @Override
  public WorldPosition spawnPosition() {
    return PaperConverters.toCore(world.getSpawnLocation());
  }

  @Override
  public WorldAdapter inWorld(String worldName) {
    World resolved = worldFor(worldName);
    return resolved == world ? this : new PaperWorldAdapter(resolved);
  }

  /**
   * Warms the destination chunk before an entry, so the teleport that follows lands in the same tick it
   * is issued rather than waiting on generation. See the seam's javadoc for why that window is harmful.
   *
   * <p>Failure is not fatal and must not block the entry: if the chunk cannot be loaded, the caller
   * proceeds anyway and simply gets the old behaviour rather than no entry at all.</p>
   */
  @Override
  public void preloadChunk(com.sexidium.core.platform.model.WorldPosition position, Runnable onReady) {
    if (onReady == null) {
      return;
    }
    World target = position == null ? world : worldFor(position.worldName());
    if (target == null || position == null) {
      onReady.run();
      return;
    }
    int chunkX = (int) Math.floor(position.coordinateX()) >> 4;
    int chunkZ = (int) Math.floor(position.coordinateZ()) >> 4;
    try {
      target.getChunkAtAsync(chunkX, chunkZ, true).whenComplete((chunk, error) -> onReady.run());
    } catch (RuntimeException exception) {
      onReady.run();
    }
  }

  @Override
  public boolean isNether() {
    return world.getEnvironment() == org.bukkit.World.Environment.NETHER;
  }

  @Override
  public boolean isEnd() {
    return world.getEnvironment() == org.bukkit.World.Environment.THE_END;
  }

  @Override
  @SuppressWarnings("removal") // GameRule constants are deprecated-for-removal; no replacement API yet
  public void setKeepInventory(boolean keepInventory) {
    world.setGameRule(org.bukkit.GameRule.KEEP_INVENTORY, keepInventory);
  }

  @Override
  public void setHardcore(boolean hardcore) {
    world.setHardcore(hardcore);
    if (hardcore) {
      // Vanilla hardcore is HARD and cannot be lowered — half of what makes it hardcore is the difficulty,
      // not just the heart texture.
      world.setDifficulty(org.bukkit.Difficulty.HARD);
    }
  }

  /**
   * This world's linked sibling dimension — for an experience world, the {@code _nether}/{@code _end}
   * world provisioned next to it (see {@code PaperWorldControl.ensureExperienceSiblings}). The sibling
   * keeps the canonical-name convention of this handle so the core's experience-world matching (which
   * compares short names) still recognises it as part of the same experience.
   */
  @Override
  public WorldAdapter dimension(WorldDimension dimension) {
    if (dimension == null || dimension == currentDimension()) {
      return this;
    }
    World sibling = PaperWorldControl.siblingDimension(world, PaperWorldControl.environmentOf(dimension));
    if (sibling == null) {
      return null;
    }
    String siblingName = canonicalName == null
        ? null : PaperWorldControl.siblingKeyPath(canonicalName, PaperWorldControl.environmentOf(dimension));
    return new PaperWorldAdapter(sibling, siblingName);
  }

  @Override
  public List<PlayerAdapter> players() {
    return world.getPlayers().stream().map(PaperPlayerAdapter::new).map(PlayerAdapter.class::cast).toList();
  }

  @Override
  public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {
    dropItem(targetPosition, itemStackData, true);
  }

  @Override
  public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData, boolean scatter) {
    Location location = PaperConverters.toNative(targetPosition);
    Material material = PaperConverters.toNative(itemStackData.itemKey());
    if (location == null || location.getWorld() == null || material == Material.AIR) {
      return;
    }
    ItemStack stack = new ItemStack(material, itemStackData.amount());
    if (scatter) {
      location.getWorld().dropItemNaturally(location, stack); // random offset + velocity
    } else {
      // Exact spot, zero velocity: the item drops straight down and stays put (no flinging off the platform).
      location.getWorld().dropItem(location, stack).setVelocity(new Vector(0, 0, 0));
    }
  }

  @Override
  public com.sexidium.core.platform.ItemEntityHandle spawnItemEntity(WorldPosition position, ItemKey itemKey, int amount) {
    Location location = PaperConverters.toNative(position);
    Material material = PaperConverters.toNative(itemKey);
    if (location == null || location.getWorld() == null || material == Material.AIR || amount <= 0) {
      return null;
    }
    // dropItemNaturally scatters slightly so distinct-type stacks at the same point don't z-fight; then
    // set the count directly so it can exceed the vanilla 64-cap.
    org.bukkit.entity.Item entity = location.getWorld().dropItemNaturally(location, new ItemStack(material, 1));
    ItemStack stack = entity.getItemStack();
    stack.setAmount(amount);
    entity.setItemStack(stack);
    return new PaperItemEntityHandle(entity);
  }

  @Override
  public List<com.sexidium.core.platform.ItemEntityHandle> nearbyItems(WorldPosition position, double radius) {
    Location location = PaperConverters.toNative(position);
    if (location == null || location.getWorld() == null) {
      return List.of();
    }
    World target = location.getWorld();
    double safeRadius = Math.max(0.0, radius);
    double radiusSq = safeRadius * safeRadius;
    // Walk only ALREADY-LOADED chunks in range rather than getNearbyEntities, which can synchronously
    // load chunks (a server-wide lag spike that hits mobile clients hardest) or throw when the box
    // reaches an unloaded/foreign chunk. Skipping unloaded chunks keeps the scan cheap and safe at any
    // radius.
    int minChunkX = (int) Math.floor((location.getX() - safeRadius) / 16.0);
    int maxChunkX = (int) Math.floor((location.getX() + safeRadius) / 16.0);
    int minChunkZ = (int) Math.floor((location.getZ() - safeRadius) / 16.0);
    int maxChunkZ = (int) Math.floor((location.getZ() + safeRadius) / 16.0);
    List<com.sexidium.core.platform.ItemEntityHandle> handles = new ArrayList<>();
    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        if (!target.isChunkLoaded(chunkX, chunkZ)) {
          continue;
        }
        for (org.bukkit.entity.Entity entity : target.getChunkAt(chunkX, chunkZ).getEntities()) {
          if (entity instanceof org.bukkit.entity.Item item
              && entity.getLocation().distanceSquared(location) <= radiusSq) {
            handles.add(new PaperItemEntityHandle(item));
          }
        }
      }
    }
    return handles;
  }

  @Override
  public int countNearbyEntities(WorldPosition center, double radius,
      java.util.Set<com.sexidium.core.platform.model.DuplicableKind> kinds) {
    Location location = PaperConverters.toNative(center);
    if (location == null || location.getWorld() == null) {
      return 0;
    }
    // Same loaded-chunk walk and same sphere test the duplication sweep uses, so the population figure
    // it is compared against is measured over exactly the area the sweep would touch.
    int[] count = {0};
    com.sexidium.paper.adapter.util.PaperDuplicableKinds.forEachNearby(
        location, radius, kinds, entity -> count[0]++);
    return count[0];
  }

  @Override
  public void spawnTnt(WorldPosition targetPosition, int fuseTicks) {
    spawnTnt(targetPosition, fuseTicks, 0.0, 0.0, 0.0, 4.0f);
  }

  @Override
  public void spawnTnt(WorldPosition targetPosition, int fuseTicks, double velocityX, double velocityY, double velocityZ, float explosionYield) {
    Location location = PaperConverters.toNative(targetPosition);
    if (location == null || location.getWorld() == null) {
      return;
    }
    org.bukkit.entity.TNTPrimed tnt = location.getWorld().spawn(location, org.bukkit.entity.TNTPrimed.class);
    tnt.setFuseTicks(Math.max(1, fuseTicks));
    tnt.setVelocity(new Vector(velocityX, velocityY, velocityZ));
    tnt.setYield(Math.max(0.0f, explosionYield));
  }

  @Override
  public void setBlock(BlockPosition blockPosition, ItemKey itemKey) {
    if (blockPosition == null) {
      return;
    }
    Material material = PaperConverters.toNative(itemKey);
    worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ()).setType(material, false);
  }

  @Override
  public void setBlockNatural(BlockPosition blockPosition, ItemKey itemKey) {
    if (blockPosition == null) {
      return;
    }
    Material material = PaperConverters.toNative(itemKey);
    // applyPhysics = TRUE is the whole point: the server runs the block's own onPlace and the neighbour
    // updates around it, so vanilla decides what happens next. That is what makes a carved pumpkin
    // completing an iron/snow golem actually spawn one, water and lava start flowing, sand fall and
    // redstone fire — none of which happens for the physics-free bulk setBlock.
    worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ())
        .setType(material, true);
  }

  @Override
  public boolean useItemOn(BlockPosition blockPosition, ItemKey itemKey) {
    if (blockPosition == null || itemKey == null) {
      return false;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    return switch (itemKey.value()) {
      case "flint_and_steel", "fire_charge" -> ignite(block);
      case "bone_meal" -> block.applyBoneMeal(org.bukkit.block.BlockFace.UP);
      default -> false; // not simulated: the caller records it but nothing is replayed
    };
  }

  /**
   * Lighting a block: TNT becomes a real primed entity that really explodes; an obsidian frame becomes a
   * real portal; anything else catches fire.
   *
   * <p>The portal case is handled <b>directly</b> rather than by placing fire and hoping vanilla notices.
   * Fire is a live block with its own behaviour — it spreads, it burns out, and where a copied frame is
   * not exactly like the original it lands somewhere unintended — so copying a portal by copying the fire
   * left the copies broken instead of lit. Filling the cavity with portal blocks is deterministic and
   * idempotent, so lighting one portal really does light them all, and replaying the same ignition twice
   * does nothing the second time.</p>
   */
  private boolean ignite(org.bukkit.block.Block block) {
    if (block.getType() == Material.TNT) {
      block.setType(Material.AIR, false);
      org.bukkit.entity.TNTPrimed tnt = block.getWorld()
          .spawn(block.getLocation().add(0.5, 0.0, 0.5), org.bukkit.entity.TNTPrimed.class);
      tnt.setFuseTicks(80); // the vanilla fuse, so the copies blow at the same moment the original does
      return true;
    }
    org.bukkit.block.Block above = block.getRelative(org.bukkit.block.BlockFace.UP);
    // A player lights a portal by clicking the frame's floor, so the cavity starts one block up; the click
    // may also land on the cavity itself when the frame is lit from inside.
    if (lightPortal(block.getWorld(), above) || lightPortal(block.getWorld(), block)) {
      return true;
    }
    if (above.getType().isAir()) {
      above.setType(Material.FIRE, true);
      return true;
    }
    return false;
  }

  /**
   * Fills the obsidian frame containing {@code inside} with portal blocks. Returns true when this was a
   * portal frame at all — including one that was ALREADY lit, which is the no-op that makes a replayed
   * ignition safe. Returns false when there is no valid frame, and the caller falls back to fire.
   */
  private boolean lightPortal(World target, org.bukkit.block.Block inside) {
    PortalFrame.Frame frame = PortalFrame.find(
        (x, y, z) -> target.getBlockAt(x, y, z).getType().getKey().getKey(),
        inside.getX(), inside.getY(), inside.getZ());
    if (frame == null) {
      return false;
    }
    if (frame.lit()) {
      return true; // already burning — replaying the same ignition must not disturb it
    }
    org.bukkit.block.data.BlockData portal = Bukkit.createBlockData(Material.NETHER_PORTAL);
    if (portal instanceof org.bukkit.block.data.Orientable orientable) {
      orientable.setAxis(frame.alongX() ? org.bukkit.Axis.X : org.bukkit.Axis.Z);
      portal = orientable;
    }
    for (int[] at : frame.interior()) {
      // No physics: a portal block is placed as a set, and letting each one update mid-fill lets vanilla
      // tear down the half-built portal it sees.
      target.getBlockAt(at[0], at[1], at[2]).setBlockData(portal, false);
    }
    return true;
  }

  @Override
  public void placeSpawner(BlockPosition blockPosition, String entityType) {
    if (blockPosition == null) {
      return;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    block.setType(Material.SPAWNER, false);
    if (entityType == null || entityType.isBlank() || !(block.getState() instanceof org.bukkit.block.CreatureSpawner spawner)) {
      return;
    }
    try {
      spawner.setSpawnedType(org.bukkit.entity.EntityType.valueOf(entityType.trim().toUpperCase(java.util.Locale.ROOT)));
      spawner.update(true, false);
    } catch (IllegalArgumentException exception) {
      // An entity this server does not know: leave the spawner inert rather than failing the whole build.
    }
  }

  @Override
  public void placeChest(BlockPosition blockPosition, List<ItemStackData> contents, String facing) {
    if (blockPosition == null) {
      return;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    block.setType(Material.CHEST, false);
    // Orient the chest (the front faces `facing`). Done on the block data before we touch the inventory.
    org.bukkit.block.BlockFace face = toBlockFace(facing);
    if (face != null && block.getBlockData() instanceof org.bukkit.block.data.Directional directional) {
      directional.setFacing(face);
      block.setBlockData(directional, false);
    }
    if (contents == null || contents.isEmpty() || !(block.getState() instanceof org.bukkit.block.Chest chest)) {
      return;
    }
    // Fill the LIVE block inventory and do NOT call update(): for a placed block state, getBlockInventory()
    // is linked to the real tile entity, so setItem persists immediately — whereas update() would write the
    // (empty) captured snapshot back over it, which is exactly why the chest kept coming out empty.
    org.bukkit.inventory.Inventory inventory = chest.getBlockInventory();
    inventory.clear();
    int slot = 0;
    for (ItemStackData data : contents) {
      if (data == null || slot >= inventory.getSize()) {
        continue;
      }
      Material material = PaperConverters.toNative(data.itemKey());
      if (material != Material.AIR) {
        inventory.setItem(slot++, new ItemStack(material, Math.max(1, data.amount())));
      }
    }
  }

  private static org.bukkit.block.BlockFace toBlockFace(String facing) {
    if (facing == null) {
      return null;
    }
    return switch (facing.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "north" -> org.bukkit.block.BlockFace.NORTH;
      case "south" -> org.bukkit.block.BlockFace.SOUTH;
      case "east" -> org.bukkit.block.BlockFace.EAST;
      case "west" -> org.bukkit.block.BlockFace.WEST;
      default -> null;
    };
  }

  @Override
  public boolean chestEmpty(BlockPosition blockPosition) {
    if (blockPosition == null) {
      return true;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    if (!(block.getState() instanceof org.bukkit.block.Chest chest)) {
      return true; // no longer a chest (already swapped back)
    }
    for (ItemStack item : chest.getBlockInventory().getContents()) {
      if (item != null && !item.getType().isAir()) {
        return false;
      }
    }
    return true;
  }

  @Override
  public ItemKey blockTypeAt(BlockPosition blockPosition) {
    if (blockPosition == null) {
      return ItemKey.minecraft("air");
    }
    return PaperConverters.toCore(worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ()).getType());
  }

  @Override
  public ItemKey breakIfType(BlockPosition blockPosition, java.util.Set<String> typeValues) {
    if (blockPosition == null || typeValues == null || typeValues.isEmpty()) {
      return null;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    Material material = block.getType();
    if (material.isAir()) {
      return null;
    }
    ItemKey key = PaperConverters.toCore(material);
    if (!typeValues.contains(key.value())) {
      return null;
    }
    // Remove without a scattered drop; the caller re-drops the batch merged at one spot.
    block.setType(Material.AIR, false);
    return key;
  }

  // A best-in-class tool so getDrops() yields the proper break result for tool-gated blocks
  // (stone -> cobblestone, coal_ore -> coal, diamond_ore -> diamond) rather than nothing. Built lazily
  // so the Material registry is not forced at class-load (breaks server-less unit tests).
  private ItemStack bestTool;

  private ItemStack bestTool() {
    if (bestTool == null) {
      bestTool = new ItemStack(Material.NETHERITE_PICKAXE);
    }
    return bestTool;
  }

  @Override
  public boolean isChunkLoaded(int chunkX, int chunkZ) {
    return world.isChunkLoaded(chunkX, chunkZ);
  }

  @Override
  public boolean resolvesBlockLoot() {
    return true; // Bukkit's Block#getDrops is the real vanilla loot function
  }

  @Override
  public List<ItemStackData> naturalDrops(BlockPosition blockPosition) {
    if (blockPosition == null) {
      return List.of();
    }
    return dropsOf(worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ()));
  }

  @Override
  public List<ItemStackData> naturalDrops(BlockPosition blockPosition, PlayerAdapter breaker) {
    if (blockPosition == null) {
      return List.of();
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    if (block.getType().isAir() || !(breaker instanceof PaperPlayerAdapter paper)) {
      return dropsOf(block); // no player context — fall back to the best-tool result
    }
    // Compute the loot for the breaker's ACTUAL held tool: ore -> raw item with a pickaxe, the block with
    // Silk Touch, extra with Fortune, and nothing when the tool is the wrong tier (vanilla-correct).
    // Rolled fresh on every call, so a caller can sample it repeatedly to estimate a random loot table.
    org.bukkit.entity.Player player = paper.handle();
    return toCoreStacks(block.getDrops(player.getInventory().getItemInMainHand(), player));
  }

  @Override
  public BrokenBlock breakIfTypeNatural(BlockPosition blockPosition, java.util.Set<String> typeValues) {
    if (blockPosition == null || typeValues == null || typeValues.isEmpty()) {
      return null;
    }
    org.bukkit.block.Block block = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ());
    Material material = block.getType();
    if (material.isAir()) {
      return null;
    }
    ItemKey key = PaperConverters.toCore(material);
    if (!typeValues.contains(key.value())) {
      return null;
    }
    // Snapshot the natural loot before removing the block (setType erases it).
    List<ItemStackData> drops = dropsOf(block);
    block.setType(Material.AIR, false);
    return new BrokenBlock(key, drops);
  }

  /**
   * The block's vanilla loot as core stacks, rolled with a plain best-in-class tool (an unenchanted
   * netherite pickaxe). That tool is deliberate: it satisfies every tool-tier gate — stone yields
   * cobblestone, iron ore yields raw iron — while carrying <b>no Silk Touch and no Fortune</b>, so a
   * stochastic block rolls its REAL loot table (leaves give the occasional sapling/stick/apple, never the
   * leaf block; glass and ice give nothing at all, exactly as in vanilla).
   *
   * <p>There is deliberately <b>no "fall back to the block item" rule</b>: it used to make a leaf block
   * drop a leaf block whenever its loot roll came up empty, which is precisely the drop a Silk Touch tool
   * is supposed to be required for. An empty result here means "this break legitimately dropped
   * nothing".</p>
   */
  private List<ItemStackData> dropsOf(org.bukkit.block.Block block) {
    if (block.getType().isAir()) {
      return List.of();
    }
    return toCoreStacks(block.getDrops(bestTool()));
  }

  private static List<ItemStackData> toCoreStacks(Collection<ItemStack> drops) {
    List<ItemStackData> result = new ArrayList<>(drops.size());
    for (ItemStack stack : drops) {
      if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
        continue;
      }
      result.add(new ItemStackData(PaperConverters.toCore(stack.getType()), stack.getAmount(), Map.of()));
    }
    return result;
  }

  @Override
  public void fillDispenserWithTnt(BlockPosition blockPosition) {
    if (blockPosition == null) {
      return;
    }
    org.bukkit.block.BlockState state = worldFor(blockPosition.worldName())
        .getBlockAt(blockPosition.blockX(), blockPosition.blockY(), blockPosition.blockZ()).getState();
    if (!(state instanceof org.bukkit.block.Dispenser dispenser)) {
      return;
    }
    org.bukkit.inventory.Inventory inventory = dispenser.getInventory();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      inventory.setItem(slot, new ItemStack(Material.TNT, 64));
    }
    dispenser.update(true, false);
  }

  @Override
  public List<MobHandle> nearbyMobs(WorldPosition centerPosition, double radius, boolean includePassive) {
    Location center = PaperConverters.toNative(centerPosition);
    if (center == null || center.getWorld() == null) {
      return List.of();
    }
    double safeRadius = Math.max(0.0, radius);
    return center.getWorld().getNearbyEntities(center, safeRadius, safeRadius, safeRadius).stream()
        .filter(entity -> entity instanceof Mob)
        .filter(entity -> includePassive || entity instanceof Monster)
        .map(entity -> new PaperMobHandle((LivingEntity) entity))
        .map(MobHandle.class::cast)
        .toList();
  }

  @Override
  public void drawRope(WorldPosition from, WorldPosition to, double sag) {
    if (from == null || to == null) {
      return;
    }
    double dx = to.coordinateX() - from.coordinateX();
    double dy = to.coordinateY() - from.coordinateY();
    double dz = to.coordinateZ() - from.coordinateZ();
    double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
    if (dist < 1.0e-3) {
      return;
    }
    // One dust point every ~0.4 blocks (capped), bowed down by the caller-supplied sag.
    int points = Math.max(1, Math.min(64, (int) (dist / 0.4)));
    World w = worldFor(from.worldName());
    Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(120, 78, 46), 0.7f);
    for (int i = 0; i <= points; i++) {
      double t = (double) i / points;
      double px = from.coordinateX() + dx * t;
      double py = from.coordinateY() + dy * t - sag * 4.0 * t * (1.0 - t);
      double pz = from.coordinateZ() + dz * t;
      w.spawnParticle(Particle.DUST, new Location(w, px, py, pz), 1, 0.0, 0.0, 0.0, 0.0, dust);
    }
  }

  @Override
  public void spawnMob(WorldPosition position, String entityType, int count, double equipChance) {
    if (position == null || entityType == null || entityType.isBlank() || count <= 0) {
      return;
    }
    String normalized = entityType.contains(":")
        ? entityType.substring(entityType.indexOf(':') + 1) : entityType;
    org.bukkit.entity.EntityType type;
    try {
      type = org.bukkit.entity.EntityType.valueOf(normalized.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException unknown) {
      return;
    }
    if (!type.isSpawnable() || !type.isAlive()) {
      return;
    }
    World w = worldFor(position.worldName());
    java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
    for (int index = 0; index < Math.min(count, 128); index++) {
      // Jitter each spawn across the slab so they do not stack into one column.
      Location at = new Location(w,
          position.coordinateX() + random.nextDouble(-4.0, 4.0),
          position.coordinateY(),
          position.coordinateZ() + random.nextDouble(-4.0, 4.0));
      org.bukkit.entity.Entity spawned = w.spawnEntity(at, type);
      if (equipChance > 0.0 && spawned instanceof org.bukkit.entity.Monster monster
          && random.nextDouble() < equipChance) {
        equipRandomly(monster, random);
      }
    }
  }

  /** Gives a hostile mob a random weapon and a partial set of random-tier armour. */
  private static void equipRandomly(org.bukkit.entity.LivingEntity mob, java.util.Random random) {
    org.bukkit.inventory.EntityEquipment equipment = mob.getEquipment();
    if (equipment == null) {
      return;
    }
    Material[] weapons = {Material.WOODEN_SWORD, Material.STONE_SWORD, Material.IRON_SWORD,
        Material.GOLDEN_SWORD, Material.DIAMOND_SWORD, Material.STONE_AXE, Material.IRON_AXE,
        Material.TRIDENT, Material.BOW};
    equipment.setItemInMainHand(new ItemStack(weapons[random.nextInt(weapons.length)]));
    equipment.setItemInMainHandDropChance(0.0f);
    String[] tiers = {"LEATHER", "GOLDEN", "CHAINMAIL", "IRON", "DIAMOND"};
    String[] slots = {"HELMET", "CHESTPLATE", "LEGGINGS", "BOOTS"};
    org.bukkit.inventory.EquipmentSlot[] armorSlots = {org.bukkit.inventory.EquipmentSlot.HEAD,
        org.bukkit.inventory.EquipmentSlot.CHEST, org.bukkit.inventory.EquipmentSlot.LEGS,
        org.bukkit.inventory.EquipmentSlot.FEET};
    for (int index = 0; index < slots.length; index++) {
      if (random.nextDouble() < 0.6) { // each piece has a 60% chance, so armour is partial and varied
        Material piece = Material.matchMaterial(tiers[random.nextInt(tiers.length)] + "_" + slots[index]);
        if (piece != null) {
          equipment.setItem(armorSlots[index], new ItemStack(piece));
          equipment.setDropChance(armorSlots[index], 0.0f);
        }
      }
    }
  }

  @Override
  public void strikeLightning(WorldPosition position) {
    if (position == null) {
      return;
    }
    Location location = PaperConverters.toNative(position);
    if (location != null && location.getWorld() != null) {
      location.getWorld().strikeLightning(location);
    }
  }

  @Override
  public void spawnDust(WorldPosition at, int rgb, float size) {
    if (at == null) {
      return;
    }
    World w = worldFor(at.worldName());
    Particle.DustOptions dust = new Particle.DustOptions(
        Color.fromRGB((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF), Math.max(0.1f, size));
    w.spawnParticle(Particle.DUST,
        new Location(w, at.coordinateX(), at.coordinateY(), at.coordinateZ()), 1, 0.0, 0.0, 0.0, 0.0, dust);
  }

  @Override
  public boolean supportsLeashRope() {
    return true;
  }

  @Override
  public java.util.UUID spawnRopeMarker(WorldPosition at, java.util.UUID holderPlayerId) {
    if (at == null) {
      return null;
    }
    Location loc = new Location(world, at.coordinateX(), at.coordinateY(), at.coordinateZ());
    // Build the marker fully BEFORE it enters the world (the spawn consumer runs pre-add) so it never
    // ticks AI, never falls, never collides, and is non-persistent — the disk-leak that sank the earlier
    // leash build (setPersistent(true) stranded an invisible silverfish leashed at spawn) cannot recur.
    Silverfish marker = world.spawn(loc, Silverfish.class, sf -> {
      sf.setAI(false);
      sf.setSilent(true);
      sf.setInvisible(true);
      sf.setInvulnerable(true);
      sf.setGravity(false);
      sf.setPersistent(false);
      sf.setCollidable(false);
      sf.setRemoveWhenFarAway(false);
      sf.addScoreboardTag("sx_rope_marker");
    });
    if (holderPlayerId != null) {
      org.bukkit.entity.Player holder = Bukkit.getPlayer(holderPlayerId);
      if (holder != null) {
        try {
          marker.setLeashHolder(holder); // sends the leash packet → the native lead renders to the holder
        } catch (RuntimeException ignored) {
          // Leashing can fail in odd states; the marker is still removable by id below.
        }
      }
    }
    return marker.getUniqueId();
  }

  @Override
  public void moveRopeMarker(java.util.UUID markerId, WorldPosition to) {
    if (markerId == null || to == null) {
      return;
    }
    org.bukkit.entity.Entity entity = world.getEntity(markerId);
    if (entity != null) {
      // Async teleport: required on Folia (a cross-region synchronous teleport throws) and a no-op
      // difference on regular Paper.
      entity.teleportAsync(new Location(world, to.coordinateX(), to.coordinateY(), to.coordinateZ()));
    }
  }

  @Override
  public void driveRopeMarker(java.util.UUID markerId, java.util.UUID holderPlayerId, WorldPosition to) {
    if (markerId == null || to == null) {
      return;
    }
    org.bukkit.entity.Entity entity = world.getEntity(markerId);
    if (entity == null) {
      return;
    }
    // Re-leash if the lead broke (holder died/disconnected) or now points at the wrong player. Skip when
    // it is already held by the right player so a fresh leash packet is not sent every tick.
    if (entity instanceof LivingEntity living && holderPlayerId != null) {
      org.bukkit.entity.Player holder = Bukkit.getPlayer(holderPlayerId);
      if (holder != null && holder.isValid()) {
        org.bukkit.entity.Entity current = living.isLeashed() ? living.getLeashHolder() : null;
        if (current == null || !holder.getUniqueId().equals(current.getUniqueId())) {
          try {
            living.setLeashHolder(holder);
          } catch (RuntimeException ignored) {
            // Holder not yet in a leashable state (e.g. mid-respawn); retried next tick.
          }
        }
      }
    }
    entity.teleportAsync(new Location(world, to.coordinateX(), to.coordinateY(), to.coordinateZ()));
  }

  @Override
  public void removeRopeMarker(java.util.UUID markerId) {
    if (markerId == null) {
      return;
    }
    org.bukkit.entity.Entity entity = world.getEntity(markerId);
    if (entity == null) {
      return;
    }
    if (entity instanceof LivingEntity living) {
      try {
        living.setLeashHolder(null); // clear the lead so it never drops as a pickup-able item
      } catch (RuntimeException ignored) {
        // Best effort — still remove below.
      }
    }
    entity.remove();
  }

  @Override
  public int removeRopeMarkers() {
    // Force-load the spawn chunk so a marker stranded there is in the loaded-entity set even when no
    // player is nearby (getEntities only returns entities in loaded chunks). Only when already
    // generated: forcing generation blocks the server thread, and an ungenerated chunk holds no marker.
    Location spawn = world.getSpawnLocation();
    if (spawn != null && world.isChunkGenerated(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) {
      world.getChunkAt(spawn);
    }
    int removed = 0;
    for (org.bukkit.entity.Entity entity : world.getEntities()) {
      boolean tagged = entity.getScoreboardTags().contains("sx_rope_marker");
      // An AI-disabled Silverfish is unambiguously a leftover rope marker (no wild one has its AI off).
      boolean inertMarker = entity instanceof Silverfish silverfish && !silverfish.hasAI();
      if (!tagged && !inertMarker) {
        continue;
      }
      if (entity instanceof LivingEntity living) {
        try {
          living.setLeashHolder(null); // clear the lead so it never drops as a pickup-able item
        } catch (RuntimeException ignored) {
          // Best effort — still remove below.
        }
      }
      entity.remove();
      removed++;
    }
    return removed;
  }

  @Override
  public MobHandle mobById(java.util.UUID id) {
    if (id == null) {
      return null;
    }
    org.bukkit.entity.Entity entity = world.getEntity(id);
    if (entity instanceof Mob && entity instanceof LivingEntity living) {
      return new PaperMobHandle(living);
    }
    return null;
  }

  @Override
  public long fullTimeTicks() {
    return world.getFullTime();
  }

  /**
   * {@code World#setTime} is already forward-only — it adds the remainder needed to reach the requested
   * time of day, wrapping into tomorrow rather than rewinding — which is exactly the contract the seam
   * promises. The daylight-cycle gamerule is untouched, so the world keeps running from there.
   */
  @Override
  public void setTimeOfDay(long timeOfDayTicks) {
    world.setTime(timeOfDayTicks);
  }

  /**
   * Biomes a world may start a player in, best first. Deliberately dry, walkable, temperate land with
   * trees or open ground nearby — the same character as a vanilla spawn. Oceans, rivers, swamps and
   * anything lethal or resourceless (deep ocean, deserts with no water, ice spikes) are simply absent.
   */
  private static final List<String> SPAWN_BIOME_KEYS = List.of(
      "plains", "sunflower_plains", "forest", "birch_forest", "flower_forest", "meadow", "savanna",
      "taiga", "snowy_taiga", "dark_forest", "jungle", "sparse_jungle", "old_growth_birch_forest",
      "old_growth_pine_taiga");

  /**
   * Resolves the spawn biomes from the server registry. Kept as ids and looked up lazily rather than held
   * as static {@code Biome} constants: those are registry-backed, so touching them during class
   * initialisation fails outright anywhere the registry is not up (unit tests, early boot).
   */
  private static org.bukkit.block.Biome[] spawnBiomes() {
    List<org.bukkit.block.Biome> biomes = new ArrayList<>();
    for (String id : SPAWN_BIOME_KEYS) {
      try {
        org.bukkit.block.Biome biome = org.bukkit.Registry.BIOME.get(org.bukkit.NamespacedKey.minecraft(id));
        if (biome != null) {
          biomes.add(biome);
        }
      } catch (RuntimeException | NoClassDefFoundError ignored) {
        // A biome this server does not know: skip it rather than losing the whole search.
      }
    }
    return biomes.toArray(new org.bukkit.block.Biome[0]);
  }

  /**
   * The horizontal half of a land spawn: the block column of the nearest habitable biome.
   *
   * <p>Read from the world's BIOME SOURCE, not its terrain, so nothing is generated however far out it
   * looks — which is what makes a radius big enough to escape an ocean affordable at all. Returns
   * {@code {blockX, blockZ}}, or null when this is not an Overworld or nothing suitable is in range.</p>
   *
   * <p>Resolving that column's surface Y is the expensive half and is deliberately NOT done here; see
   * {@link #locateLandSpawn} for the blocking version and {@code PaperWorldControl.pinLandSpawn} for the
   * one that keeps it off the server thread.</p>
   */
  public int[] locateLandColumn(String worldName, int maxRadius) {
    World target = worldFor(worldName);
    if (target.getEnvironment() != World.Environment.NORMAL) {
      return null; // only the Overworld has an ocean to be stranded in
    }
    try {
      org.bukkit.block.Biome[] wanted = spawnBiomes();
      if (wanted.length == 0) {
        return null;
      }
      org.bukkit.util.BiomeSearchResult found = target.locateNearestBiome(
          new Location(target, 0, 64, 0), Math.max(64, maxRadius), wanted);
      if (found == null || found.getLocation() == null) {
        return null;
      }
      Location at = found.getLocation();
      return new int[] {at.getBlockX(), at.getBlockZ()};
    } catch (RuntimeException | NoSuchMethodError exception) {
      // An older/divergent platform: keep whatever spawn the world already has rather than failing.
      return null;
    }
  }

  /**
   * {@inheritDoc}
   *
   * <p><strong>Blocks.</strong> The biome search is free, but reading the surface height generates the
   * column's chunk on the CALLING thread if it does not exist yet — a full carvers→features→full pass,
   * which on a fresh world is seconds, not milliseconds. Calling this on the server thread is what once
   * tripped Paper's watchdog during world-pool warm-up and took the node down with it. Anything on the
   * server thread wants {@link #locateLandColumn} plus an async chunk load instead.</p>
   */
  @Override
  public WorldPosition locateLandSpawn(String worldName, int maxRadius) {
    int[] column = locateLandColumn(worldName, maxRadius);
    if (column == null) {
      return null;
    }
    World target = worldFor(worldName);
    try {
      int surface = target.getHighestBlockYAt(column[0], column[1]);
      return new WorldPosition(target.getName(), column[0] + 0.5, surface + 1.0,
          column[1] + 0.5, 0.0f, 0.0f);
    } catch (RuntimeException | NoSuchMethodError exception) {
      return null;
    }
  }

  @Override
  public int highestSolidBlockY(String worldName, int blockX, int blockZ) {
    // getHighestBlockYAt loads/generates the chunk and returns the topmost non-air block's Y.
    return worldFor(worldName).getHighestBlockYAt(blockX, blockZ);
  }

  @Override
  public int minBuildHeight() {
    return world.getMinHeight();
  }

  @Override
  public int maxBuildHeight() {
    return world.getMaxHeight();
  }

  @Override
  public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {
    Location location = PaperConverters.toNative(targetPosition);
    if (location != null && location.getWorld() != null) {
      location.getWorld().playSound(location, soundKey.value(), volume, pitch);
    }
  }

  @Override
  public void setBorder(WorldBorderSpec worldBorderSpec) {
    WorldBorder border = world.getWorldBorder();
    border.setCenter(worldBorderSpec.centerX(), worldBorderSpec.centerZ());
    border.setSize(worldBorderSpec.size());
    border.setWarningDistance(worldBorderSpec.warningDistance());
    border.setDamageAmount(worldBorderSpec.damagePerBlock());
  }

  @Override
  public void resetBorder() {
    WorldBorder border = world.getWorldBorder();
    Location spawn = world.getSpawnLocation();
    border.setCenter(spawn.getX(), spawn.getZ());
    border.setSize(59_999_968.0);
  }

  @Override
  public void loadChunk(int chunkX, int chunkZ, boolean generate) {
    world.getChunkAtAsync(chunkX, chunkZ, generate, false);
  }

  // Hard cap on blocks changed in one sweep, so a common block type does not queue millions of light/
  // client updates and freeze the server thread for tens of seconds.
  private static final int REMOVE_BUDGET = 40000;
  // Hard cap on the scanned half-extent, so an over-large configured radius cannot scan an absurd volume.
  private static final int REMOVE_MAX_REACH = 64;

  @Override
  public int removeBlocksOfType(WorldPosition center, double radius, ItemKey blockKey, boolean dropItems) {
    if (center == null || blockKey == null) {
      return 0;
    }
    Material target = PaperConverters.toNative(blockKey);
    if (target == Material.AIR) {
      return 0;
    }
    World w = worldFor(center.worldName());
    int reach = Math.min(REMOVE_MAX_REACH, (int) Math.max(0, Math.ceil(radius)));
    int centerX = (int) Math.floor(center.coordinateX());
    int centerY = (int) Math.floor(center.coordinateY());
    int centerZ = (int) Math.floor(center.coordinateZ());
    int minY = Math.max(w.getMinHeight(), centerY - reach);
    int maxY = Math.min(w.getMaxHeight() - 1, centerY + reach);
    int removed = 0;
    // Iterate chunk-by-chunk and SKIP any chunk that is not already loaded — never force a synchronous
    // chunk load/generation for a sweep (that alone can stall the thread for many seconds).
    int minChunkX = (centerX - reach) >> 4;
    int maxChunkX = (centerX + reach) >> 4;
    int minChunkZ = (centerZ - reach) >> 4;
    int maxChunkZ = (centerZ + reach) >> 4;
    for (int chunkX = minChunkX; chunkX <= maxChunkX && removed < REMOVE_BUDGET; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ && removed < REMOVE_BUDGET; chunkZ++) {
        if (!w.isChunkLoaded(chunkX, chunkZ)) {
          continue;
        }
        int x0 = Math.max(centerX - reach, chunkX << 4);
        int x1 = Math.min(centerX + reach, (chunkX << 4) + 15);
        int z0 = Math.max(centerZ - reach, chunkZ << 4);
        int z1 = Math.min(centerZ + reach, (chunkZ << 4) + 15);
        for (int x = x0; x <= x1 && removed < REMOVE_BUDGET; x++) {
          for (int z = z0; z <= z1 && removed < REMOVE_BUDGET; z++) {
            for (int y = minY; y <= maxY && removed < REMOVE_BUDGET; y++) {
              org.bukkit.block.Block block = w.getBlockAt(x, y, z);
              if (block.getType() != target) {
                continue;
              }
              if (dropItems) {
                block.breakNaturally();
              } else {
                block.setType(Material.AIR, false);
              }
              removed++;
            }
          }
        }
      }
    }
    return removed;
  }

  @Override
  public int convertChunk(int chunkX, int chunkZ, ItemKey blockKey, int minY, int maxY, java.util.Set<String> preservedBlockValues) {
    Material target = PaperConverters.toNative(blockKey);
    if (target == Material.AIR) {
      return 0;
    }
    java.util.Set<String> preserved = preservedBlockValues == null ? java.util.Set.of() : preservedBlockValues;
    int baseX = chunkX << 4;
    int baseZ = chunkZ << 4;
    int low = Math.max(world.getMinHeight(), Math.min(minY, maxY));
    int high = Math.min(world.getMaxHeight() - 1, Math.max(minY, maxY));
    int replaced = 0;
    for (int dx = 0; dx < 16; dx++) {
      for (int dz = 0; dz < 16; dz++) {
        for (int y = low; y <= high; y++) {
          org.bukkit.block.Block block = world.getBlockAt(baseX + dx, y, baseZ + dz);
          Material current = block.getType();
          if (current.isAir()) {
            continue; // never fill air — only existing blocks are replaced
          }
          if (preserved.contains(PaperConverters.toCore(current).value())) {
            continue; // blacklisted (e.g. leaves) — keep it
          }
          block.setType(target, false);
          replaced++;
        }
      }
    }
    return replaced;
  }

  /**
   * The Bukkit world a position/block operation should act on: the world named by the operation's
   * {@code worldName} when it resolves to a loaded world, otherwise this adapter's own world. An
   * experience runs on a single overworld {@link WorldAdapter}, but its linked Nether/End siblings are
   * separate worlds; routing every position through here lets that one adapter correctly service a drop,
   * sweep, mob scan or effect whose position is in a sibling dimension — acting THERE instead of back in
   * the overworld, which is why the experience modes appeared dead the moment a player left the overworld.
   * Names that are null/blank or do not resolve fall back to this world, preserving the old behaviour.
   */
  private World worldFor(String worldName) {
    if (worldName == null || worldName.isBlank()) {
      return world;
    }
    try {
      World resolved = PaperConverters.resolveWorld(worldName);
      if (resolved != null) {
        return resolved;
      }
    } catch (RuntimeException ignored) {
      // No running server (unit tests) or an unusable name — fall back to this adapter's own world.
    }
    return world;
  }
}
