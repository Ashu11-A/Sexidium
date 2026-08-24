package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerJumpGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.DuplicableKind;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.world.PlayerRadius;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Jump Multiplies ("Minecraft but jumping multiplies everything").
 *
 * <p>Rule: every time a participant presses JUMP, every eligible entity within {@code radius} of them is
 * cloned once — mobs, dropped item entities, projectiles in flight, primed TNT, and (opt-in) bosses, all
 * in the same instant. It is untargeted on purpose, which is what separates it from
 * {@link LookMultipliesChallenge}: that one copies whatever is in the crosshair, this one copies
 * <em>everything</em>, so the count is exponential in jumps rather than linear.</p>
 *
 * <p><b>The trigger is the input, not upward motion.</b> The challenge listens to
 * {@link PlayerJumpGameEvent}, a dedicated platform seam, precisely because mob knockback, an explosion
 * punt and a piston all produce the same Y delta a movement sample would read as a jump — and "I was
 * launched, I did not jump" is the rule players are asked to play around. {@code jump-cooldown-ms} is
 * only a debounce on top of that, never the detector.</p>
 *
 * <p><b>The entity layer only.</b> Placed blocks are never touched, so a TNT <em>block</em> on the
 * ground is inert while the same TNT once lit duplicates like anything else. Players are never copied.
 * Item entities copy <b>per entity, not per item</b>: a dropped stack of 64 is one entity and yields one
 * more, which is what makes "throw the stack out as individual drops first, <em>then</em> jump" the
 * mode's signature exploit rather than a bug.</p>
 *
 * <p>Bounding follows the fun-first policy: the caps sit where a server actually breaks. Three of them
 * cascade — a per-jump clone budget, the shared per-tick spawn budget in
 * {@link com.sexidium.core.game.experience.compose.MobRegistry} (which Mob Duplication and Cleave also
 * drain), and a live-entity ceiling measured over the same area the sweep would touch. At the ceiling
 * the mode <b>refuses and says so on the HUD</b>, so a saturated area reads as the mode working rather
 * than as the mode being broken. All of that arithmetic lives in {@link JumpMultiplierRule}.</p>
 */
public final class JumpMultipliesChallenge extends Challenge {
  private static final String KEY_JUMPS = "jumps";
  private static final String STAT_CLONES = "jumpmultiplies.clones";

  /** What the last sweep for one player did, for the HUD to report. */
  private record Sweep(int live, int spawned, boolean saturated) {
  }

  private final JumpMultiplierRule rule = new JumpMultiplierRule();
  // Per-player, so the panel shows the jumper their own last sweep. ConcurrentHashMap because on Folia
  // each player's jump lands on that player's own region thread while the HUD renders elsewhere.
  private final Map<UUID, Sweep> lastSweep = new ConcurrentHashMap<>();

  private double radius;
  private int copiesPerEntity;
  private int maxClonesPerJump;
  private int maxPerTick;
  private int maxLiveEntities;
  private long jumpCooldownMillis;
  private boolean playSound;
  private Set<DuplicableKind> kinds = Set.of();

  public JumpMultipliesChallenge() {
    super("jumpmultiplies", "Jump Multiplies");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    radius = Math.max(1.0, cfg().getDouble(configPath("radius"), 12.0));
    // 1 = everything doubles, which is the video's rule. Higher is a straight power multiplier on an
    // already-exponential mode, so it is left to the operator rather than defaulted up.
    copiesPerEntity = Math.max(1, cfg().getInt(configPath("copies-per-entity"), 1));
    maxClonesPerJump = Math.max(1, cfg().getInt(configPath("max-clones-per-jump"), 1024));
    maxPerTick = Math.max(0, cfg().getInt(configPath("max-per-tick"), 256));
    maxLiveEntities = Math.max(0, cfg().getInt(configPath("max-live-entities"), 4000));
    jumpCooldownMillis = Math.max(0L, cfg().getLong(configPath("jump-cooldown-ms"), 250L));
    playSound = cfg().getBoolean(configPath("play-sound"), true);
    kinds = JumpMultiplierRule.eligibleKinds(
        cfg().getBoolean(configPath("multiply-mobs"), true),
        cfg().getBoolean(configPath("multiply-items"), true),
        cfg().getBoolean(configPath("multiply-projectiles"), true),
        cfg().getBoolean(configPath("multiply-tnt"), true),
        cfg().getBoolean(configPath("multiply-bosses"), false));

    // The per-tick spawn budget is SHARED with Mob Duplication and Cleave, so take the MINIMUM of what
    // is already in force and what this mode wants: clobbering it would quietly widen a cap a composed
    // sibling asked for. 0 means "uncapped" on both sides, so it never wins the minimum.
    int existing = mobs().spawnBudgetPerTick();
    int desired = maxPerTick;
    mobs().spawnBudgetPerTick(existing == 0 ? desired : (desired == 0 ? existing : Math.min(existing, desired)));

    if (!stateHas(KEY_JUMPS)) {
      setStateInt(KEY_JUMPS, 0);
    }
  }

  @Override
  public void onPlayerJump(PlayerJumpGameEvent event) {
    PlayerAdapter player = event.playerAdapter();
    if (player == null || !isParticipant(player) || kinds.isEmpty()) {
      return;
    }
    if (!rule.acceptJump(player.uniqueId(), jumpCooldownMillis)) {
      return;
    }
    setStateInt(KEY_JUMPS, stateInt(KEY_JUMPS, 0) + 1);
    sweep(player);
  }

  /**
   * The duplication sweep, run INLINE in the jump's own call stack — deliberately not deferred onto
   * {@link #runLater}/{@link #runTimer}.
   *
   * <p>Those map to Folia's <em>global</em> region scheduler, whereas the jump listener already runs on
   * the player's own region thread: handing the work to a timer would move it off the region that owns
   * the entities being cloned, which is the one thing that must not happen here. It also breaks the
   * mode's feel — the jump and the doubling are supposed to be the same instant. Please do not "fix"
   * this onto a timer.</p>
   */
  private void sweep(PlayerAdapter player) {
    // Never reach past what the player can actually see (invisible work still costs the server), but the
    // configured radius is what normally wins — a client's reported view distance is ~176 blocks, which
    // is nothing like the tight ring around the jumper this mode is.
    double reach = JumpMultiplierRule.reach(
        radius, PlayerRadius.blockRadius(player, 1, Integer.MAX_VALUE));
    // The player's OWN world, not world(): an experience's Nether and End are separate worlds, and a jump
    // taken in one of them must be measured there.
    WorldAdapter world = player.world();
    int live = world == null ? 0 : world.countNearbyEntities(player.position(), reach, kinds);
    int wanted = JumpMultiplierRule.clonesFor(live, copiesPerEntity, maxClonesPerJump, live, maxLiveEntities);
    if (wanted <= 0) {
      lastSweep.put(player.uniqueId(),
          new Sweep(live, 0, JumpMultiplierRule.saturated(live, maxLiveEntities)));
      return;
    }
    // Drain the shared per-tick budget one clone at a time, so a jump beside a mob farm spreads across
    // ticks instead of spending the whole server's spawn allowance in one.
    int granted = 0;
    while (granted < wanted && mobs().tryRegisterSpawn()) {
      granted++;
    }
    int spawned = granted <= 0 ? 0 : player.duplicateNearbyEntities(reach, copiesPerEntity, granted, kinds);
    lastSweep.put(player.uniqueId(), new Sweep(live, spawned, false));
    if (spawned > 0) {
      // long: an exponential mode blows past int in a single long session.
      stats().add(STAT_CLONES, spawned);
      if (playSound) {
        player.playSound(new SoundKey("minecraft:entity.slime.jump"), 0.6f, 1.4f);
      }
    }
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      rule.forget(playerAdapter.uniqueId());
      lastSweep.remove(playerAdapter.uniqueId());
    }
  }

  private void describeHud(HudContext context) {
    context.line(text(MessageKey.EXPERIENCE_JUMPMULTIPLIES_CLONES,
        MessageArg.text("count", stats().get(STAT_CLONES)),
        MessageArg.text("jumps", stateInt(KEY_JUMPS, 0))));
    PlayerAdapter viewer = context.player();
    if (viewer != null && !viewer.supportsEntityDuplication()) {
      // "Nothing was nearby" and "this platform cannot clone at all" both spawn 0; say which.
      context.line(text(MessageKey.EXPERIENCE_JUMPMULTIPLIES_UNSUPPORTED));
    } else {
      Sweep last = viewer == null ? null : lastSweep.get(viewer.uniqueId());
      if (last != null) {
        context.line(text(MessageKey.EXPERIENCE_JUMPMULTIPLIES_NEARBY,
            MessageArg.text("live", last.live()),
            MessageArg.text("ceiling", maxLiveEntities == 0 ? "∞" : maxLiveEntities)));
        if (last.saturated()) {
          context.line(text(MessageKey.EXPERIENCE_JUMPMULTIPLIES_SATURATED));
        }
      }
    }
    if (context.debug()) {
      Sweep last = viewer == null ? null : lastSweep.get(viewer.uniqueId());
      context.debugHeader(displayName());
      context.debugStat("Radius", String.format(Locale.ROOT, "%.1f", radius));
      context.debugStat("Copies / entity", copiesPerEntity);
      context.debugStat("Clones / jump", maxClonesPerJump);
      context.debugStat("Spawn budget/tick", maxPerTick == 0 ? "∞" : maxPerTick);
      context.debugStat("Live ceiling", maxLiveEntities == 0 ? "∞" : maxLiveEntities);
      context.debugStat("Jump cooldown", jumpCooldownMillis + "ms");
      context.debugStat("Kinds", kinds.toString());
      context.debugStat("Tracked jumpers", rule.tracked());
      context.debugStat("Last jump clones", last == null ? 0 : last.spawned());
    }
  }
}
