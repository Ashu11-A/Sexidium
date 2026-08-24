package com.sexidium.paper.adapter.decor;

import com.sexidium.core.decor.DecorTypes.DecorAnimation;
import com.sexidium.core.decor.DecorProp;
import com.sexidium.core.platform.DecorAdapter;
import com.sexidium.paper.adapter.player.PaperGeyser;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * In-world {@link DecorAdapter} backend for Paper, built on <b>native</b> display entities
 * ({@link ItemDisplay}/{@link BlockDisplay}/{@link TextDisplay}) — no plugin dependency. Each
 * {@link DecorProp} maps to one display entity tracked by prop id, mirroring {@code PaperNpcAdapter}'s
 * lifecycle discipline: respawn drops the old entity first, {@code despawn} removes the entity itself
 * (sending the destroy packet so no client-side ghost survives), entities are spawned
 * {@code setPersistent(false)} and scoreboard-tagged {@code sx_decor} so a startup sweep can clear any
 * leftover from a crash or an older build (mirrors {@code PaperWorldAdapter.removeRopeMarkers}).
 *
 * <p><b>Animation</b> runs as client-side interpolation: a slow ({@value #ANIM_CADENCE_TICKS}-tick)
 * task advances a coarse spin/bob target and the client tweens between frames, so an animated prop costs
 * no per-tick server work.</p>
 *
 * <p><b>Cross-play</b>: item/block displays are invisible to Bedrock (Geyser has no entity definition,
 * and they log-spam) — so every decor entity is hidden from Bedrock/Geyser players (on spawn and on
 * join) via {@link Player#hideEntity}. Java clients that declined the resource pack still see the
 * sensible vanilla base item; only pack-loaded Java clients see the bespoke {@code item_model}. This
 * keeps the layer additive Java flair, per {@code docs/graphical-interfaces-report.md} §3.4.</p>
 */
public final class PaperDecorAdapter implements DecorAdapter, Listener {
  private static final String DECOR_TAG = "sx_decor";
  private static final long ANIM_CADENCE_TICKS = 10L; // 2 Hz; the client interpolates between updates
  private static final String GLOW_TEAM_PREFIX = "sxd_";

  private final JavaPlugin plugin;
  private final NamespacedKey decorIdKey;
  private final Map<String, Display> displays = new ConcurrentHashMap<>();
  private final Map<String, DecorProp> props = new ConcurrentHashMap<>();
  private final Map<String, AnimState> animations = new ConcurrentHashMap<>();
  // Folia-safe global cadence; each per-display mutation is dispatched onto the display's own region.
  private final io.papermc.paper.threadedregions.scheduler.ScheduledTask animationTask;

  public PaperDecorAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
    this.decorIdKey = new NamespacedKey(plugin, "decor_id");
    Bukkit.getPluginManager().registerEvents(this, plugin);
    this.animationTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
        plugin, scheduledTask -> tickAnimations(), ANIM_CADENCE_TICKS, ANIM_CADENCE_TICKS);
  }

  @Override
  public void spawn(DecorProp prop) {
    if (prop == null) {
      return;
    }
    // Respawn semantics: drop any existing prop with this id first (no leaked / ghost entity).
    despawn(prop.id());

    World world = Bukkit.getWorld(prop.world());
    if (world == null) {
      plugin.getLogger().warning("Decor prop '" + prop.id() + "' references unknown world '"
          + prop.world() + "'; skipping spawn.");
      return;
    }
    Location location = new Location(world, prop.x(), prop.y(), prop.z(), prop.yaw(), prop.pitch());

    Display display;
    try {
      display = switch (prop.kind()) {
        case ITEM_DISPLAY -> world.spawn(location, ItemDisplay.class, entity -> configureItem(entity, prop));
        case BLOCK_DISPLAY -> world.spawn(location, BlockDisplay.class, entity -> configureBlock(entity, prop));
        case TEXT_DISPLAY -> world.spawn(location, TextDisplay.class, entity -> configureText(entity, prop));
        // v1 decor is non-interactive — INTERACTION is reserved for a future clickable portal.
        case INTERACTION -> null;
      };
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to spawn decor prop '" + prop.id() + "'.", exception);
      return;
    }
    if (display == null) {
      return;
    }

    displays.put(prop.id(), display);
    props.put(prop.id(), prop);
    applyGlow(display, prop);
    hideFromBedrock(display);
    if (prop.animation().animated()) {
      animations.put(prop.id(), new AnimState());
    }
  }

  @Override
  public void despawn(String propId) {
    if (propId == null) {
      return;
    }
    props.remove(propId);
    animations.remove(propId);
    Display display = displays.remove(propId);
    if (display != null) {
      String entry = display.getUniqueId().toString();
      try {
        if (!display.isDead()) {
          display.remove(); // sends the entity-destroy packet — kills any client-side ghost
        }
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.FINE, "Failed to despawn decor prop '" + propId + "'.", exception);
      }
      removeFromGlowTeams(entry);
    }
    // Belt-and-suspenders: also remove any *orphaned* decor entity carrying this prop id that is not in our
    // in-memory map (left by a previous plugin /reload or an older build). Without this, moving an NPC
    // (/sx admin npc here) drops the tracked entity but leaves an untracked twin behind at the old spot.
    removeOrphansByDecorId(propId);
  }

  /** Removes any loaded decor entity whose {@code decor_id} PDC matches {@code propId} (orphan cleanup). */
  private void removeOrphansByDecorId(String propId) {
    for (World world : Bukkit.getWorlds()) {
      try {
        for (Entity entity : world.getEntities()) {
          if (!(entity instanceof Display) || !entity.getScoreboardTags().contains(DECOR_TAG)) {
            continue;
          }
          String id = entity.getPersistentDataContainer().get(decorIdKey, PersistentDataType.STRING);
          if (!propId.equals(id)) {
            continue;
          }
          String entry = entity.getUniqueId().toString();
          try {
            entity.remove();
          } catch (RuntimeException ignored) {
            // Best effort.
          }
          removeFromGlowTeams(entry);
        }
      } catch (RuntimeException ignored) {
        // Best effort per world.
      }
    }
  }

  @Override
  public void despawnAll() {
    for (String id : List.copyOf(displays.keySet())) {
      despawn(id);
    }
    // Sweep any stray decor entities a previous run / older build left behind (mirrors removeRopeMarkers).
    sweepStray();
  }

  // ----- configuration --------------------------------------------------------------------------

  private void configureCommon(Display display, DecorProp prop) {
    display.setPersistent(false); // never written to chunk storage → an unclean shutdown leaves no ghost
    display.setBrightness(new Display.Brightness(15, 15)); // full-bright, vivid at any time of day
    display.setBillboard(Display.Billboard.valueOf(prop.billboard().name()));
    display.setViewRange(2.0F); // render from across the lobby (default ~1.0)
    display.addScoreboardTag(DECOR_TAG);
    display.getPersistentDataContainer().set(decorIdKey, PersistentDataType.STRING, prop.id());
    display.setTransformation(baseTransformation(prop.scale(), 0.0F, 0.0F));
  }

  private void configureItem(ItemDisplay display, DecorProp prop) {
    configureCommon(display, prop);
    display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
    display.setItemStack(itemStack(prop.baseItem(), prop.itemModelId()));
  }

  private void configureBlock(BlockDisplay display, DecorProp prop) {
    configureCommon(display, prop);
    display.setBlock(blockData(prop.blockData()));
  }

  private void configureText(TextDisplay display, DecorProp prop) {
    configureCommon(display, prop);
    display.text(MiniMessage.miniMessage().deserialize(prop.text() == null ? "" : prop.text()));
    display.setAlignment(TextDisplay.TextAlignment.CENTER);
    display.setShadowed(true);
    display.setSeeThrough(false);
    display.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
  }

  private ItemStack itemStack(String baseItem, String modelId) {
    Material material = resolveMaterial(baseItem);
    ItemStack stack = new ItemStack(material);
    if (modelId != null && !modelId.isBlank()) {
      ItemMeta meta = stack.getItemMeta();
      if (meta != null) {
        applyItemModel(meta, modelId);
        stack.setItemMeta(meta);
      }
    }
    return stack;
  }

  private Material resolveMaterial(String id) {
    if (id == null || id.isBlank()) {
      return Material.PAPER;
    }
    Material material = Material.matchMaterial(id);
    return material == null ? Material.PAPER : material;
  }

  private BlockData blockData(String id) {
    try {
      return Bukkit.createBlockData(id == null || id.isBlank() ? "minecraft:smooth_quartz" : id);
    } catch (IllegalArgumentException exception) {
      return Bukkit.createBlockData(Material.SMOOTH_QUARTZ);
    }
  }

  /** Applies a custom {@code item_model} key, degrading to the vanilla material on older servers. */
  private void applyItemModel(ItemMeta meta, String model) {
    try {
      NamespacedKey key = NamespacedKey.fromString(model);
      if (key != null) {
        meta.setItemModel(key);
      }
    } catch (Throwable ignored) {
      // Older server without the item-model component: keep the vanilla material icon.
    }
  }

  // ----- glow (native scoreboard-team color) ----------------------------------------------------

  private void applyGlow(Display display, DecorProp prop) {
    if (!prop.glowing()) {
      return;
    }
    display.setGlowing(true);
    Integer argb = prop.glowArgb();
    if (argb == null) {
      return;
    }
    NamedTextColor color = NamedTextColor.nearestTo(TextColor.color(argb & 0xFFFFFF));
    glowTeam(color).addEntry(display.getUniqueId().toString());
  }

  private Team glowTeam(NamedTextColor color) {
    Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
    String name = GLOW_TEAM_PREFIX + Integer.toHexString(color.value() & 0xFFFFFF);
    Team team = scoreboard.getTeam(name);
    if (team == null) {
      team = scoreboard.registerNewTeam(name);
      team.color(color);
    }
    return team;
  }

  private void removeFromGlowTeams(String entry) {
    try {
      Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
      for (Team team : scoreboard.getTeams()) {
        if (team.getName().startsWith(GLOW_TEAM_PREFIX) && team.hasEntry(entry)) {
          team.removeEntry(entry);
        }
      }
    } catch (RuntimeException ignored) {
      // Best effort — the entity is gone regardless.
    }
  }

  // ----- cross-play: hide from Bedrock ----------------------------------------------------------

  private void hideFromBedrock(Display display) {
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (PaperGeyser.isBedrock(player.getUniqueId())) {
        try {
          player.hideEntity(plugin, display);
        } catch (RuntimeException ignored) {
          // Best effort.
        }
      }
    }
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    Player player = event.getPlayer();
    if (!PaperGeyser.isBedrock(player.getUniqueId())) {
      return; // Java clients see decor (pack-loaded: bespoke art; declined: vanilla base item).
    }
    // Item/block displays are invisible to Bedrock and log-spam Geyser — hide every decor entity.
    // Runs on the player's own region (Folia-safe; on regular Paper this is the main thread).
    player.getScheduler().run(plugin, scheduledTask -> {
      for (Display display : displays.values()) {
        if (display != null && !display.isDead()) {
          try {
            player.hideEntity(plugin, display);
          } catch (RuntimeException ignored) {
            // Best effort.
          }
        }
      }
    }, null);
  }

  // ----- startup sweep --------------------------------------------------------------------------

  private void sweepStray() {
    for (World world : Bukkit.getWorlds()) {
      try {
        // Force-load the spawn chunk so a stranded marker is in the loaded-entity set (mirrors the
        // rope-marker sweep) even with no player nearby. Only when the chunk is already generated:
        // forcing generation here blocks the server thread (custom-gen experience worlds stall on
        // light init), and decor markers are non-persistent so an ungenerated chunk holds none.
        Location spawn = world.getSpawnLocation();
        if (spawn != null && world.isChunkGenerated(spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4)) {
          world.getChunkAt(spawn);
        }
        for (Entity entity : world.getEntities()) {
          if (!entity.getScoreboardTags().contains(DECOR_TAG)) {
            continue;
          }
          String entry = entity.getUniqueId().toString();
          try {
            entity.remove();
          } catch (RuntimeException ignored) {
            // Best effort.
          }
          removeFromGlowTeams(entry);
        }
      } catch (RuntimeException ignored) {
        // Best effort per world.
      }
    }
  }

  // ----- animation (client-side interpolation) --------------------------------------------------

  private void tickAnimations() {
    if (animations.isEmpty()) {
      return;
    }
    for (Map.Entry<String, AnimState> entry : animations.entrySet()) {
      Display display = displays.get(entry.getKey());
      DecorProp prop = props.get(entry.getKey());
      if (display == null || prop == null || display.isDead()) {
        continue;
      }
      // Mutate each display on the region that owns it (Folia-safe; main thread on regular Paper).
      String id = entry.getKey();
      AnimState state = entry.getValue();
      display.getScheduler().run(plugin, scheduledTask -> {
        try {
          advance(display, prop, state);
        } catch (RuntimeException exception) {
          plugin.getLogger().log(Level.FINE, "Decor animation tick failed for '" + id + "'.", exception);
        }
      }, null);
    }
  }

  private void advance(Display display, DecorProp prop, AnimState state) {
    DecorAnimation anim = prop.animation();
    double secondsPerCadence = ANIM_CADENCE_TICKS / 20.0;
    state.elapsedTicks += ANIM_CADENCE_TICKS;
    double seconds = state.elapsedTicks / 20.0;

    if (anim.spin()) {
      state.angleDeg = (state.angleDeg + anim.spinDegreesPerSecond() * secondsPerCadence) % 360.0;
    }
    float bobOffset = 0.0F;
    if (anim.bob() && anim.bobPeriodSeconds() > 0.0) {
      bobOffset = (float) (anim.bobAmplitude() * Math.sin(2.0 * Math.PI * seconds / anim.bobPeriodSeconds()));
    }
    if (display instanceof ItemDisplay item && !anim.cmdCycleModelIds().isEmpty() && anim.cmdCycleSeconds() > 0.0) {
      int index = (int) (seconds / anim.cmdCycleSeconds()) % anim.cmdCycleModelIds().size();
      if (index != state.cmdIndex) {
        state.cmdIndex = index;
        item.setItemStack(itemStack(prop.baseItem(), anim.cmdCycleModelIds().get(index)));
      }
    }

    display.setInterpolationDelay(0);
    display.setInterpolationDuration((int) ANIM_CADENCE_TICKS);
    display.setTransformation(baseTransformation(prop.scale(), bobOffset, (float) state.angleDeg));
  }

  private Transformation baseTransformation(float scale, float yTranslation, float spinDegrees) {
    return new Transformation(
        new Vector3f(0.0F, yTranslation, 0.0F),
        new Quaternionf().rotateY((float) Math.toRadians(spinDegrees)),
        new Vector3f(scale, scale, scale),
        new Quaternionf());
  }

  private static final class AnimState {
    private double angleDeg;
    private long elapsedTicks;
    private int cmdIndex = -1;
  }
}
