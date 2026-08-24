package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Jump Enchants challenge.
 *
 * <p>Rule: every time a participant jumps, a random item in their inventory is enchanted with a
 * randomly chosen enchantment at a random level. Jumps are detected purely from movement: a
 * vertical delta (toY - fromY) above the configured threshold counts as a jump, throttled by a
 * per-player cooldown so a single hop only fires once.
 *
 * <p>Mechanic: handled entirely in {@link #onEvent(GameEvent)} via {@link PlayerMoveGameEvent} with
 * no scheduled timer. The chosen enchantment is drawn from the {@code enchants} config list (or the
 * built-in defaults when empty), and applied through the core SPI
 * {@link PlayerAdapter#enchantRandomItem(String, int)}.
 */
public final class JumpEnchantsChallenge extends Challenge {
  // Empty config -> EVERY enchantment in the game is in the pool. Repeated jumps stack any of these
  // to absurd levels on whatever junk gets picked, which is the whole point of the challenge.
  private static final List<String> DEFAULT_ENCHANTS =
      List.of(
          "protection", "fire_protection", "feather_falling", "blast_protection", "projectile_protection",
          "respiration", "aqua_affinity", "thorns", "depth_strider", "frost_walker", "binding_curse",
          "soul_speed", "swift_sneak", "sharpness", "smite", "bane_of_arthropods", "knockback",
          "fire_aspect", "looting", "sweeping_edge", "efficiency", "silk_touch", "unbreaking", "fortune",
          "power", "punch", "flame", "infinity", "luck_of_the_sea", "lure", "loyalty", "impaling",
          "riptide", "channeling", "multishot", "quick_charge", "piercing", "density", "breach",
          "wind_burst", "mending", "vanishing_curse");

  private final Map<UUID, Long> lastJumpMillis = new HashMap<>();
  private final Random random = new Random();

  public JumpEnchantsChallenge() {
    super("jumpenchants", "Jump Enchants");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.hud(this::describeHud);
  }

  private void describeHud(HudContext context) {
    context.line("<aqua>Enchants applied:</aqua> <white>" + stats().get("jumpenchants.count") + "</white>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Pool", enchantPool().size() + " enchants");
      context.debugStat("Max level", cfg().getInt(configPath("max-level"), 5));
      context.debugStat("Jump cooldown", cfg().getLong(configPath("jump-cooldown-ms"), 400L) + "ms");
      context.debugStat("Tracked jumpers", lastJumpMillis.size());
    }
  }

  @Override
  public void onPlayerMove(PlayerMoveGameEvent event) {
    if (isParticipant(event.playerAdapter())) {
      WorldPosition from = event.fromPosition();
      WorldPosition to = event.toPosition();
      if (from == null || to == null) {
        return;
      }
      double dy = to.coordinateY() - from.coordinateY();
      if (dy < cfg().getDouble(configPath("jump-detection-y-delta"), 0.4)) {
        return;
      }
      UUID id = event.playerAdapter().uniqueId();
      long now = System.currentTimeMillis();
      long cooldown = cfg().getLong(configPath("jump-cooldown-ms"), 400L);
      if (now - lastJumpMillis.getOrDefault(id, 0L) < cooldown) {
        return;
      }
      lastJumpMillis.put(id, now);

      List<String> enchants = enchantPool();
      String enchant = enchants.get(random.nextInt(enchants.size()));
      int maxLevel = Math.max(1, cfg().getInt(configPath("max-level"), 5));
      int level = 1 + random.nextInt(maxLevel);
      boolean applied = event.playerAdapter().enchantRandomItem(enchant, level);
      if (applied) {
        stats().add("jumpenchants.count", 1);
      }
      if (applied && cfg().getBoolean(configPath("play-sound"), true)) {
        event
            .playerAdapter()
            .playSound(new SoundKey("minecraft:entity.experience_orb.pickup"), 0.7f, 1.4f);
        event.playerAdapter().sendActionBar("<aqua>Enchanted: <white>" + enchant + " " + level);
      }
    }
  }

  @Override
  public void onPlayerLeave(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      lastJumpMillis.remove(playerAdapter.uniqueId());
    }
  }

  private List<String> enchantPool() {
    List<String> configured = cfg().getStringList(configPath("enchants"));
    if (configured == null || configured.isEmpty()) {
      return DEFAULT_ENCHANTS;
    }
    return configured;
  }
}
