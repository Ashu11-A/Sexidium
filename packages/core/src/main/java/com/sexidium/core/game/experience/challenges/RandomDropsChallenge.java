package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.game.GameEvents.EntityDeathGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.game.experience.compose.DropContext;
import com.sexidium.core.game.experience.compose.DropContributor;
import com.sexidium.core.game.experience.compose.DropPhase;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomized Block &amp; Mob Drops. Every block broken and every mob killed drops a <em>random</em> item —
 * not a fixed per-type mapping (that is {@link RandomizerChallenge}) but a fresh roll each time, so the same
 * block can drop diamonds once and dirt the next. Block drops run through the shared drop pipeline (so they
 * compose with the other drop twists); mob drops are added at the death location.
 */
public final class RandomDropsChallenge extends Challenge {
  private static final List<ItemKey> DEFAULT_POOL = List.of(
      ItemKey.minecraft("diamond"), ItemKey.minecraft("emerald"), ItemKey.minecraft("gold_ingot"),
      ItemKey.minecraft("iron_ingot"), ItemKey.minecraft("copper_ingot"), ItemKey.minecraft("coal"),
      ItemKey.minecraft("redstone"), ItemKey.minecraft("lapis_lazuli"), ItemKey.minecraft("quartz"),
      ItemKey.minecraft("amethyst_shard"), ItemKey.minecraft("ender_pearl"), ItemKey.minecraft("blaze_rod"),
      ItemKey.minecraft("slime_ball"), ItemKey.minecraft("gunpowder"), ItemKey.minecraft("bone"),
      ItemKey.minecraft("string"), ItemKey.minecraft("leather"), ItemKey.minecraft("feather"),
      ItemKey.minecraft("apple"), ItemKey.minecraft("bread"), ItemKey.minecraft("carrot"),
      ItemKey.minecraft("potato"), ItemKey.minecraft("wheat"), ItemKey.minecraft("sugar_cane"),
      ItemKey.minecraft("oak_log"), ItemKey.minecraft("cobblestone"), ItemKey.minecraft("obsidian"),
      ItemKey.minecraft("glowstone_dust"), ItemKey.minecraft("clay_ball"), ItemKey.minecraft("flint"),
      ItemKey.minecraft("egg"), ItemKey.minecraft("gold_nugget"), ItemKey.minecraft("iron_nugget"),
      ItemKey.minecraft("nether_wart"), ItemKey.minecraft("magma_cream"), ItemKey.minecraft("ghast_tear"),
      ItemKey.minecraft("prismarine_shard"), ItemKey.minecraft("honeycomb"), ItemKey.minecraft("glow_ink_sac"),
      ItemKey.minecraft("experience_bottle"));

  private List<ItemKey> pool;

  public RandomDropsChallenge() {
    super("randomdrops", "Randomized Drops");
  }

  @Override
  public void register(ChallengeRegistry registry) {
    registry.dropContributor(new RandomizeBlockDrop());
    registry.hud(this::describeHud);
  }

  @Override
  public void onStart(List<PlayerAdapter> participants) {
    pool = resolvePool();
  }

  /** Replaces a broken block's loot with a single random pool item (rolled fresh each break). */
  private final class RandomizeBlockDrop implements DropContributor {
    @Override
    public DropPhase phase() {
      return DropPhase.GENERATE;
    }

    @Override
    public void contribute(DropContext context) {
      if (context.sourceKey() == null) {
        return;
      }
      context.replaceAll(List.of(new ItemStackData(randomItem(), 1, Map.of())));
      stats().add("randomdrops.blocks", 1);
    }
  }

  @Override
  public void onEntityDeath(EntityDeathGameEvent event) {
    WorldAdapter world = world();
    WorldPosition at = event.deathPosition();
    if (world == null || at == null) {
      return;
    }
    world.dropItem(at, new ItemStackData(randomItem(), 1, Map.of()));
    stats().add("randomdrops.mobs", 1);
  }

  private ItemKey randomItem() {
    List<ItemKey> active = (pool == null || pool.isEmpty()) ? DEFAULT_POOL : pool;
    return active.get(ThreadLocalRandom.current().nextInt(active.size()));
  }

  private void describeHud(HudContext context) {
    context.line("<light_purple>Randomized Drops</light_purple> <gray>active</gray>");
    if (context.debug()) {
      context.debugHeader(displayName());
      context.debugStat("Pool", (pool == null ? DEFAULT_POOL : pool).size() + " items");
      context.debugStat("Block drops", stats().get("randomdrops.blocks"));
      context.debugStat("Mob drops", stats().get("randomdrops.mobs"));
    }
  }

  private List<ItemKey> resolvePool() {
    List<String> configured = cfg().getStringList(configPath("item-pool"));
    if (configured == null || configured.isEmpty()) {
      // Prefer the whole registry when the platform reports it, else the built-in pool.
      List<ItemKey> all = new ArrayList<>();
      for (ItemKey item : gameContext().server().allItems()) {
        if (item != null) {
          all.add(item);
        }
      }
      return all.isEmpty() ? DEFAULT_POOL : all;
    }
    List<ItemKey> parsed = new ArrayList<>();
    for (String entry : configured) {
      ItemKey key = ItemKey.parse(entry);
      if (key != null) {
        parsed.add(key);
      }
    }
    return parsed.isEmpty() ? DEFAULT_POOL : parsed;
  }
}
