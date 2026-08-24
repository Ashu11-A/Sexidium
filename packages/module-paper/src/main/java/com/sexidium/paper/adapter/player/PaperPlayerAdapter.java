package com.sexidium.paper.adapter.player;

import com.sexidium.paper.adapter.command.PaperCommandSource;
import com.sexidium.paper.adapter.inventory.PaperInventoryAdapter;
import com.sexidium.paper.adapter.util.PaperConverters;
import com.sexidium.paper.adapter.world.PaperWorldAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.EffectSnapshot;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PaperPlayerAdapter extends PaperCommandSource implements PlayerAdapter {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private final Player player;

  public PaperPlayerAdapter(Player player) {
    super(player);
    this.player = player;
  }

  public Player handle() {
    return player;
  }

  @Override
  public int ping() {
    return player.getPing();
  }

  @Override
  public long idleMillis() {
    // Paper's idle duration is vanilla's last-action time -- the same clock that drives
    // player-idle-timeout. Standing-still movement packets do NOT reset it, which is precisely why it
    // answers "is this player driving their character" rather than "is the socket open".
    //
    // Wrapped like the health probes in PaperServerAdapter: a reading that throws must cost the
    // reading, not the caller. -1 is "cannot say", and every caller treats that as "assume responsive".
    try {
      return player.getIdleDuration().toMillis();
    } catch (Throwable unavailable) {
      return -1L;
    }
  }

  @Override
  public void performCommand(String command) {
    if (command == null || command.isBlank()) {
      return;
    }
    player.performCommand(command.startsWith("/") ? command.substring(1) : command);
  }

  @Override
  public UUID uniqueId() {
    return player.getUniqueId();
  }

  /**
   * Whether this is a Geyser (Bedrock) player — authoritative via the Floodgate API when installed,
   * else the Floodgate-UUID heuristic. Bedrock players get special treatment (tap-only input,
   * Bedrock-safe HUD rendering). See {@link PaperGeyser}.
   */
  @Override
  public boolean isBedrock() {
    return PaperGeyser.isBedrock(player.getUniqueId());
  }

  @Override
  public Locale locale() {
    return player.locale();
  }

  @Override
  public boolean online() {
    return player.isOnline();
  }

  @Override
  public boolean dead() {
    return player.isDead();
  }

  @Override
  public WorldAdapter world() {
    return new PaperWorldAdapter(player.getWorld());
  }

  @Override
  public WorldPosition position() {
    return PaperConverters.toCore(player.getLocation());
  }

  @Override
  public void teleport(WorldPosition targetPosition) {
    Location location = PaperConverters.toNative(targetPosition);
    if (location != null) {
      player.teleportAsync(location);
    }
  }

  /**
   * Paper's teleport is a future, so this chains the callback onto it rather than running it inline.
   *
   * <p>The distinction matters most for a move into a freshly generated world: {@code teleportAsync}
   * returns before the destination chunk exists, so a caller that treats the next statement as
   * "the player is now there" is wrong for as long as generation takes. The callback fires only on a
   * teleport that reported success, and only for a player still online.</p>
   */
  @Override
  public void teleport(WorldPosition targetPosition, java.util.function.Consumer<Boolean> onSettled) {
    if (onSettled == null) {
      teleport(targetPosition);
      return;
    }
    Location location = PaperConverters.toNative(targetPosition);
    if (location == null) {
      // An unresolvable destination is a FAILED teleport, not a skipped one. Returning silently here is
      // what stranded players: the caller's pending-move bookkeeping never drained, so the retry meant
      // for exactly this case was skipped for the rest of the reset.
      onSettled.accept(Boolean.FALSE);
      return;
    }
    // whenComplete, not thenAccept: a future that completes exceptionally (a chunk load that throws)
    // must still settle the caller, or it pins the player in "still travelling" for ever.
    player.teleportAsync(location).whenComplete((moved, error) ->
        onSettled.accept(error == null && Boolean.TRUE.equals(moved)));
  }

  @Override
  public void setItemCooldown(ItemKey itemKey, int ticks) {
    if (itemKey == null || ticks <= 0) {
      return;
    }
    Material material = Material.matchMaterial(itemKey.qualifiedName());
    if (material == null) {
      material = Material.matchMaterial(itemKey.value());
    }
    if (material != null) {
      player.setCooldown(material, ticks);
    }
  }

  @Override
  public GameModeType gameMode() {
    return PaperConverters.toCore(player.getGameMode());
  }

  @Override
  public void setGameMode(GameModeType gameModeType) {
    player.setGameMode(PaperConverters.toNative(gameModeType));
  }

  @Override
  public double health() {
    return player.getHealth();
  }

  @Override
  public double maxHealth() {
    if (player.getAttribute(Attribute.MAX_HEALTH) == null) {
      return 20.0;
    }
    return player.getAttribute(Attribute.MAX_HEALTH).getValue();
  }

  @Override
  public void setHealth(double health) {
    player.setHealth(Math.max(0.0, Math.min(maxHealth(), health)));
  }

  @Override
  public int foodLevel() {
    return player.getFoodLevel();
  }

  @Override
  public void setFoodLevel(int foodLevel) {
    player.setFoodLevel(Math.max(0, Math.min(20, foodLevel)));
  }

  @Override
  public InventoryAdapter inventory() {
    return new PaperInventoryAdapter(player.getInventory());
  }

  @Override
  public com.sexidium.core.platform.model.ItemKey heldItem() {
    org.bukkit.inventory.ItemStack inHand = player.getInventory().getItemInMainHand();
    return inHand == null || inHand.getType().isAir() ? null : PaperConverters.toCore(inHand.getType());
  }

  @Override
  public void playSound(SoundKey soundKey, float volume, float pitch) {
    player.playSound(player.getLocation(), soundKey.value(), volume, pitch);
  }

  @Override
  public void showTitle(TitleSpec titleSpec) {
    String title = titleSpec.titleMiniMessage() == null ? "" : titleSpec.titleMiniMessage();
    String subtitle = titleSpec.subtitleMiniMessage() == null ? "" : titleSpec.subtitleMiniMessage();
    player.showTitle(Title.title(
        MINI_MESSAGE.deserialize(title),
        MINI_MESSAGE.deserialize(subtitle),
        Title.Times.times(
            Duration.ofMillis(titleSpec.fadeInMillis()),
            Duration.ofMillis(titleSpec.stayMillis()),
            Duration.ofMillis(titleSpec.fadeOutMillis())
        )
    ));
  }

  @Override
  public void sendActionBar(String miniMessage) {
    String rendered = miniMessage == null ? "" : miniMessage;
    player.sendActionBar(MINI_MESSAGE.deserialize(rendered));
  }

  @Override
  public void setCompassTarget(WorldPosition targetPosition) {
    Location location = PaperConverters.toNative(targetPosition);
    if (location != null) {
      player.setCompassTarget(location);
    }
  }

  @Override
  public int experiencePoints() {
    return player.getTotalExperience();
  }

  @Override
  public void setExperiencePoints(int points) {
    player.setExp(0.0f);
    player.setLevel(0);
    player.setTotalExperience(0);
    player.giveExp(Math.max(0, points));
  }

  @Override
  public void setExperienceBar(int level, float progress) {
    // Set the bar's level number and fill directly, without giveExp's point-total coupling.
    player.setLevel(Math.max(0, level));
    player.setExp(Math.max(0.0f, Math.min(1.0f, progress)));
  }

  @Override
  public void setHealthScale(double scale) {
    player.setHealthScale(Math.max(1.0, scale));
    player.setHealthScaled(true);
  }

  @Override
  public void setBelowName(String label, int value) {
    org.bukkit.scoreboard.Scoreboard board = player.getServer().getScoreboardManager().getMainScoreboard();
    org.bukkit.scoreboard.Objective objective = board.getObjective("sx_belowname");
    net.kyori.adventure.text.Component title =
        net.kyori.adventure.text.Component.text(label == null ? "" : label);
    if (objective == null) {
      objective = board.registerNewObjective("sx_belowname", org.bukkit.scoreboard.Criteria.DUMMY, title);
      objective.setDisplaySlot(org.bukkit.scoreboard.DisplaySlot.BELOW_NAME);
    } else {
      objective.displayName(title);
    }
    objective.getScore(player.getName()).setScore(value);
  }

  @Override
  public void clearBelowName() {
    org.bukkit.scoreboard.Scoreboard board = player.getServer().getScoreboardManager().getMainScoreboard();
    board.resetScores(player.getName());
  }

  @Override
  public void resetHealthScale() {
    player.setHealthScaled(false);
  }

  @Override
  public void setVelocity(double velocityX, double velocityY, double velocityZ) {
    player.setVelocity(player.getVelocity().add(new org.bukkit.util.Vector(velocityX, velocityY, velocityZ)));
  }

  @Override
  public void launch(double velocityX, double velocityY, double velocityZ) {
    // Absolute set (not additive): a dash must override any existing fall velocity so it works mid-air.
    player.setVelocity(new org.bukkit.util.Vector(velocityX, velocityY, velocityZ));
  }

  @Override
  public void setFlySpeed(float speed) {
    // Bukkit throws outside -1..1; clamp defensively.
    player.setFlySpeed(Math.max(-1.0f, Math.min(1.0f, speed)));
  }

  @Override
  public void clearInventory() {
    player.getInventory().clear();
    player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[0]);
    player.getInventory().setExtraContents(new org.bukkit.inventory.ItemStack[0]);
  }

  @Override
  public void clearPotionEffects() {
    player.clearActivePotionEffects();
  }

  /**
   * Puts the player on a fresh, empty scoreboard, which is the only way to guarantee no sidebar: the
   * board on screen may belong to another system entirely, so there is nothing of ours to hide.
   */
  @Override
  public void clearSidebar() {
    org.bukkit.scoreboard.ScoreboardManager manager = org.bukkit.Bukkit.getScoreboardManager();
    if (manager != null) {
      player.setScoreboard(manager.getNewScoreboard());
    }
  }

  @Override
  public void clearDamageImmunity() {
    // A player who just died and was force-respawned, or who is being moved between worlds mid-match,
    // can carry the no-damage-ticks from the hit that killed them, the fall distance they had built up,
    // and the fire they were standing in. None of those is a potion effect, so clearPotionEffects leaves
    // them — and the player reads as invulnerable in the world they land in. Resetting all three puts
    // them back to "a fresh survival player who takes damage normally".
    player.setNoDamageTicks(0);
    player.setFallDistance(0F);
    player.setFireTicks(0);
    // The blanket invulnerability flag too — the ONE form of damage immunity this method used to miss,
    // and the one that does not expire. It is granted for short grace windows (a freshly-wiped player
    // arriving in a hard world), and every one of those windows is revoked by a SCHEDULED task, so any
    // path that skips the schedule leaves it set for ever: a failed teleport, a disconnect mid-move, or a
    // match ending inside the window and cancelling its own tracked tasks. It is persistent NBT, so it
    // then survives relog, restart and world change, and follows the player into the lobby and into every
    // later match. Clearing it wherever a player is being reset to "fresh survival" is what makes the
    // grace fail safe instead of fail open.
    player.setInvulnerable(false);
  }

  @Override
  public void setInvulnerable(boolean invulnerable) {
    player.setInvulnerable(invulnerable);
  }

  @Override
  public void clearBossBars() {
    // Copy first: hideBossBar mutates the player's active boss-bar set.
    List<BossBar> activeBars = new ArrayList<>();
    player.activeBossBars().forEach(activeBars::add);
    for (BossBar bossBar : activeBars) {
      player.hideBossBar(bossBar);
    }
  }

  @Override
  public void clearTitle() {
    player.clearTitle();
    player.sendActionBar(Component.empty());
  }

  @Override
  public void resetCompass() {
    player.setCompassTarget(player.getWorld().getSpawnLocation());
  }

  @Override
  public void setHardcoreView(boolean hardcore) {
    PaperHardcoreView.apply(player, hardcore);
  }

  @Override
  public void forceRespawn() {
    if (!player.isDead()) {
      return;
    }
    // The only way off a hardcore death screen: it has no Respawn button for the player to press, so
    // nothing but this will ever move them. Guarded because respawn() throws if the player is alive and
    // is the kind of API a server fork can drop.
    try {
      player.spigot().respawn();
    } catch (RuntimeException | NoSuchMethodError exception) {
      // A player stuck on a death screen is bad, but not worth taking the reset down with it.
    }
  }

  @Override
  public void setScale(double scale) {
    var attribute = player.getAttribute(Attribute.SCALE);
    if (attribute != null) {
      attribute.setBaseValue(Math.max(0.0625, scale));
    }
  }

  @Override
  public void resetScale() {
    var attribute = player.getAttribute(Attribute.SCALE);
    if (attribute != null) {
      attribute.setBaseValue(attribute.getDefaultValue());
    }
  }

  @Override
  public int viewDistanceChunks() {
    // The CLIENT's actual render distance (their video setting), not the server-side send distance
    // (getViewDistance), which is what we really want for "the user's rendering". Falls back to the
    // server view distance, then a sane default, if the client hasn't reported it yet.
    int client = player.getClientViewDistance();
    if (client > 0) {
      return client;
    }
    int server = player.getViewDistance();
    return server > 0 ? server : 10;
  }

  @Override
  public void addEffect(String effectKey, int amplifier, int durationTicks) {
    org.bukkit.potion.PotionEffectType type = effectType(effectKey);
    if (type != null) {
      player.addPotionEffect(new org.bukkit.potion.PotionEffect(type, Math.max(1, durationTicks), Math.max(0, amplifier)));
    }
  }

  @Override
  public List<EffectSnapshot> activeEffects() {
    List<EffectSnapshot> effects = new ArrayList<>();
    for (org.bukkit.potion.PotionEffect effect : player.getActivePotionEffects()) {
      // Store the namespaced-stripped key so it round-trips through effectType(...) on apply.
      effects.add(new EffectSnapshot(effect.getType().getKey().getKey(), effect.getAmplifier(), effect.getDuration()));
    }
    return effects;
  }

  @Override
  public void damageHeldItem(int amount) {
    org.bukkit.inventory.ItemStack item = player.getInventory().getItemInMainHand();
    if (item == null || item.getType().isAir() || amount <= 0) {
      return;
    }
    item.damage(amount, player);
  }

  @Override
  public boolean enchantRandomItem(String enchantKey, int level) {
    org.bukkit.enchantments.Enchantment enchantment = resolveEnchantment(enchantKey);
    if (enchantment == null) {
      return false;
    }
    org.bukkit.inventory.PlayerInventory inventory = player.getInventory();
    java.util.List<Integer> slots = new java.util.ArrayList<>();
    for (int slot = 0; slot < inventory.getSize(); slot++) {
      org.bukkit.inventory.ItemStack candidate = inventory.getItem(slot);
      if (candidate != null && !candidate.getType().isAir()) {
        slots.add(slot);
      }
    }
    if (slots.isEmpty()) {
      return false;
    }
    int chosen = slots.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(slots.size()));
    org.bukkit.inventory.ItemStack item = inventory.getItem(chosen);
    if (item == null) {
      return false;
    }
    item.addUnsafeEnchantment(enchantment, Math.max(1, level));
    return true;
  }

  @Override
  public int duplicateLookedAtEntity(double maxDistance, int copies,
      boolean includeItems, boolean includeMobs, boolean includeProjectiles) {
    int want = Math.min(Math.max(0, copies), 128); // hard safety cap regardless of the caller's multiplier
    if (want <= 0 || (!includeItems && !includeMobs && !includeProjectiles)) {
      return 0;
    }
    Location eye = player.getEyeLocation();
    org.bukkit.World world = player.getWorld();
    java.util.function.Predicate<org.bukkit.entity.Entity> filter = entity -> {
      if (entity == player || entity instanceof Player || !entity.isValid()) {
        return false;
      }
      if (entity instanceof org.bukkit.entity.Item) {
        return includeItems;
      }
      if (entity instanceof org.bukkit.entity.AbstractArrow) {
        return includeProjectiles;
      }
      return includeMobs && entity instanceof org.bukkit.entity.LivingEntity;
    };
    // raySize widens the crosshair a touch so "looking at" is forgiving on both Java and Bedrock.
    org.bukkit.util.RayTraceResult result =
        world.rayTraceEntities(eye, eye.getDirection(), maxDistance, 0.35, filter);
    org.bukkit.entity.Entity hit = result == null ? null : result.getHitEntity();
    if (hit == null) {
      return 0;
    }
    Location at = hit.getLocation();
    if (hit instanceof org.bukkit.entity.Item item) {
      org.bukkit.inventory.ItemStack stack = item.getItemStack();
      for (int index = 0; index < want; index++) {
        world.dropItem(at, stack.clone());
      }
      return want;
    }
    if (hit instanceof org.bukkit.entity.AbstractArrow arrow) {
      org.bukkit.util.Vector base = arrow.getVelocity();
      double speed = base.length();
      java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
      for (int index = 0; index < want; index++) {
        org.bukkit.entity.Entity spawned = world.spawnEntity(at, arrow.getType());
        if (spawned instanceof org.bukkit.entity.AbstractArrow copy) {
          org.bukkit.util.Vector velocity = base.clone().add(new org.bukkit.util.Vector(
              random.nextDouble(-0.15, 0.15), random.nextDouble(-0.15, 0.15), random.nextDouble(-0.15, 0.15)));
          if (speed > 0.0) {
            velocity.normalize().multiply(speed);
          }
          copy.setVelocity(velocity);
          copy.setShooter(player);
          copy.setCritical(arrow.isCritical());
        }
      }
      return want;
    }
    if (hit instanceof org.bukkit.entity.LivingEntity living) {
      double health = living.getHealth();
      for (int index = 0; index < want; index++) {
        org.bukkit.entity.Entity spawned = world.spawnEntity(at, living.getType());
        if (spawned instanceof org.bukkit.entity.LivingEntity clone) {
          clone.setHealth(Math.max(1.0, Math.min(clone.getMaxHealth(), health)));
        }
      }
      return want;
    }
    return 0;
  }

  /** Hard ceiling on one sweep, whatever the caller's budget says — see {@link #duplicateNearbyEntities}. */
  private static final int MAX_SPAWNS_PER_SWEEP = 512;

  @Override
  public boolean supportsEntityDuplication() {
    return true;
  }

  @Override
  public int duplicateNearbyEntities(double radius, int copiesPerEntity, int maxSpawns,
      java.util.Set<com.sexidium.core.platform.model.DuplicableKind> kinds) {
    int perEntity = Math.max(0, copiesPerEntity);
    int budget = Math.min(Math.max(0, maxSpawns), MAX_SPAWNS_PER_SWEEP);
    if (perEntity == 0 || budget == 0 || kinds == null || kinds.isEmpty()) {
      return 0;
    }
    // SNAPSHOT first, clone second. The sweep drops its copies into the same chunks it is reading, so a
    // streaming walk would re-visit its own output and multiply that too — unbounded, in the one mode
    // built on exponential growth.
    org.bukkit.entity.Entity[] sources =
        com.sexidium.paper.adapter.util.PaperDuplicableKinds.snapshotNearby(player.getLocation(), radius, kinds);
    int spawned = 0;
    for (org.bukkit.entity.Entity source : sources) {
      if (spawned >= budget) {
        break;
      }
      if (!source.isValid()) {
        continue; // died or was picked up between the snapshot and now
      }
      for (int index = 0; index < perEntity && spawned < budget; index++) {
        // Entity#copy is a full NBT-level clone with a fresh UUID, spawned in one call. That is what
        // delivers aggro, taming and its owner, baby state, equipment, custom names, item-stack NBT, a
        // projectile's shooter and a TNT's fuse — for every entity kind at once. Reconstructing from a
        // type string (as duplicateLookedAtEntity does) loses all of it, and the mode's whole promise is
        // that a copy is the same creature, not a same-species stranger.
        org.bukkit.entity.Entity copy = source.copy(source.getLocation());
        if (copy == null) {
          continue;
        }
        // copy() is not documented to carry the current attack target, so re-assert it: a duplicated
        // hostile that forgets it was chasing you reads as a different mob, not a copy.
        if (source instanceof org.bukkit.entity.Mob sourceMob && copy instanceof org.bukkit.entity.Mob copyMob) {
          copyMob.setTarget(sourceMob.getTarget());
        }
        // N primed TNT at one point with one fuse is a single N-fold blast. Staggering the copies by a
        // tick each keeps it a chain of explosions, which is survivable and reads better.
        if (source instanceof org.bukkit.entity.TNTPrimed sourceTnt
            && copy instanceof org.bukkit.entity.TNTPrimed copyTnt) {
          copyTnt.setFuseTicks(Math.max(1, sourceTnt.getFuseTicks() + index + 1));
        }
        spawned++;
      }
    }
    return spawned;
  }

  private org.bukkit.potion.PotionEffectType effectType(String effectKey) {
    if (effectKey == null || effectKey.isBlank()) {
      return null;
    }
    String normalized = effectKey.contains(":") ? effectKey.substring(effectKey.indexOf(':') + 1) : effectKey;
    normalized = normalized.trim().toLowerCase(Locale.ROOT);
    // Resolve by the modern registry key first (so haste/slowness/jump_boost/darkness/… all work), then
    // fall back to the legacy getByName. getByName alone misses effects whose legacy constant name differs.
    org.bukkit.potion.PotionEffectType byKey =
        org.bukkit.Registry.EFFECT.get(org.bukkit.NamespacedKey.minecraft(normalized));
    return byKey != null ? byKey : org.bukkit.potion.PotionEffectType.getByName(normalized.toUpperCase(Locale.ROOT));
  }

  private org.bukkit.enchantments.Enchantment resolveEnchantment(String enchantKey) {
    if (enchantKey == null || enchantKey.isBlank()) {
      return null;
    }
    String normalized = enchantKey.contains(":") ? enchantKey.substring(enchantKey.indexOf(':') + 1) : enchantKey;
    return org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft(normalized.toLowerCase(Locale.ROOT)));
  }
}
