package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.BlockBreakGameEvent;
import com.sexidium.core.game.GameEvents.PlayerInteractGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Total Cleave.
 *
 * <p>Rule: every left-click swing strikes every mob within range at once. One randomly chosen mob
 * takes the full damage value while every other nearby mob takes a minimal splash hit, so a single
 * swing can carve through an entire horde. The catch is that tools wear out fast: each swing — and,
 * optionally, each block broken — drains a configurable chunk of durability from the held item.
 *
 * <p><strong>Real-time tracking optimization:</strong> a periodic background timer (every
 * {@code scan-period-ticks}) rebuilds an in-memory set of the <em>ids</em> of every living mob near a
 * participant. The tracker stores only the mob ids — never an entity reference, which goes stale
 * across ticks / chunk reloads and loses the mob's position. On a swing each tracked id is re-resolved
 * to a fresh live {@link MobHandle} via {@link WorldAdapter#mobById(UUID)}, so damage and the live
 * distance check always act on the real, current entity — no spatial query on the hot path.</p>
 */
public final class CleaveChallenge extends Challenge {
  private final Random random = new Random();
  // Ids of mobs seen near a participant by the latest scan. Re-resolved to live handles on each swing.
  private final Set<UUID> trackedIds = new LinkedHashSet<>();
  // Last cleave time per player, so the same swing reported by two platform events (e.g. Paper's
  // PlayerAnimationEvent + PlayerInteractEvent pair) only cleaves once.
  private final Map<UUID, Long> lastSwingMillis = new HashMap<>();

  private double radius;
  private double fullDamage;
  private double splashDamage;
  private int toolDurabilityDrain;
  private boolean drainOnBreak;
  private boolean playSound;
  private long scanPeriodTicks;
  private long swingCooldownMillis;

  public CleaveChallenge() {
    super("cleave", "Total Cleave");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<gray>Mobs cleaved:</gray> <white>" + stats().get("cleave.cleaved") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Tracked mobs", trackedIds.size());
      context.debugStat("Radius", (int) radius);
      context.debugStat("Full / splash dmg", String.format(java.util.Locale.ROOT, "%.1f / %.1f", fullDamage, splashDamage));
      context.debugStat("Durability drain", toolDurabilityDrain + (drainOnBreak ? " (+ on break)" : ""));
    }
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    radius = cfg().getDouble(configPath("radius"), 48.0);
    fullDamage = cfg().getDouble(configPath("full-damage"), 10.0);
    splashDamage = cfg().getDouble(configPath("splash-damage"), 1.0);
    toolDurabilityDrain = cfg().getInt(configPath("tool-durability-drain"), 64);
    drainOnBreak = cfg().getBoolean(configPath("drain-on-break"), true);
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    scanPeriodTicks = Math.max(1L, cfg().getLong(configPath("scan-period-ticks"), 10L));
    swingCooldownMillis = Math.max(0L, cfg().getLong(configPath("swing-cooldown-ms"), 25L));
    trackedIds.clear();
    lastSwingMillis.clear();
    runTimer(this::trackNearbyMobs, scanPeriodTicks, scanPeriodTicks);
  }

  @Override
  public void onPlayerInteract(PlayerInteractGameEvent event) {
    if (event.actionType() == PlayerInteractGameEvent.ActionType.LEFT_CLICK
        && isParticipant(event.playerAdapter())) {
      cleave(event.playerAdapter());
    }
  }

  @Override
  public void onBlockBreak(BlockBreakGameEvent event) {
    if (isParticipant(event.playerAdapter()) && drainOnBreak) {
      event.playerAdapter().damageHeldItem(toolDurabilityDrain);
    }
  }

  /**
   * Periodic scan: rebuilds the tracked-id set from a fresh spatial query of every living mob within
   * range of each online participant, so the tracker is a complete, current picture of nearby mobs and
   * the swing never has to scan. Storing ids (not handles) keeps it stale-free.
   */
  private void trackNearbyMobs() {
    trackedIds.clear();
    for (PlayerAdapter player : online()) {
      WorldPosition position = player.position();
      if (position == null || player.world() == null) {
        continue;
      }
      // Shared, per-tick scan cache: the mob twists reuse the same query instead of each
      // paying for its own nearbyMobs sweep.
      for (MobHandle mob : mobs().scan(position, radius, true)) {
        UUID id = mob.id();
        if (id != null) {
          trackedIds.add(id);
        }
      }
    }
  }

  /** Cleaves the tracked mobs — each id re-resolved to a live handle so its position/damage is correct. */
  private void cleave(PlayerAdapter player) {
    // Dedupe the same physical swing arriving via two platform events.
    long now = System.currentTimeMillis();
    Long previous = lastSwingMillis.get(player.uniqueId());
    if (previous != null && now - previous < swingCooldownMillis) {
      return;
    }
    lastSwingMillis.put(player.uniqueId(), now);
    WorldAdapter world = player.world();
    WorldPosition origin = player.position();
    if (world == null || origin == null || trackedIds.isEmpty()) {
      return;
    }
    double radiusSquared = radius * radius;
    List<MobHandle> targets = new ArrayList<>();
    for (UUID id : trackedIds) {
      MobHandle mob = world.mobById(id);
      if (mob != null && mob.valid() && distanceSquared(mob.position(), origin) <= radiusSquared) {
        targets.add(mob);
      }
    }
    if (targets.isEmpty()) {
      return;
    }
    int lucky = random.nextInt(targets.size());
    // Mark the cleave active so Mob Duplication ignores the MobDamage events these programmatic hits
    // raise — otherwise one swing would duplicate the whole horde (exponential).
    mobs().beginCleave();
    try {
      for (int i = 0; i < targets.size(); i++) {
        targets.get(i).damage(i == lucky ? fullDamage : splashDamage);
      }
    } finally {
      mobs().endCleave();
    }
    stats().add("cleave.cleaved", targets.size());
    player.damageHeldItem(toolDurabilityDrain);
    if (playSound) {
      player.playSound(new SoundKey("minecraft:entity.player.attack.sweep"), 0.8F, 1.0F);
    }
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      lastSwingMillis.remove(playerAdapter.uniqueId());
    }
  }

  private static double distanceSquared(WorldPosition first, WorldPosition second) {
    if (first == null || second == null
        || first.worldName() == null || !first.worldName().equals(second.worldName())) {
      return Double.MAX_VALUE;
    }
    double dx = first.coordinateX() - second.coordinateX();
    double dy = first.coordinateY() - second.coordinateY();
    double dz = first.coordinateZ() - second.coordinateZ();
    return dx * dx + dy * dy + dz * dz;
  }
}
