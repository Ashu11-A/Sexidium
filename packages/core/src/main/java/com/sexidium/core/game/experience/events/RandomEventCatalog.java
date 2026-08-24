package com.sexidium.core.game.experience.events;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The default set of {@link RandomEvent}s — 24 short, chaotic, "what just happened" moments (well over the
 * required 20). They are built from the existing platform seams (potion effects, launches, mob/TNT spawns,
 * lightning, teleports, item drops), so the same catalog runs on any backend. Each event is null-safe:
 * a missing world or an empty player list simply does nothing.
 */
public final class RandomEventCatalog {
  private RandomEventCatalog() {
  }

  /** A small pool of harmless "goodies" the item-rain event scatters. */
  private static final List<ItemKey> ITEM_RAIN = List.of(
      ItemKey.minecraft("apple"), ItemKey.minecraft("cooked_beef"), ItemKey.minecraft("iron_ingot"),
      ItemKey.minecraft("gold_ingot"), ItemKey.minecraft("diamond"), ItemKey.minecraft("emerald"),
      ItemKey.minecraft("cake"), ItemKey.minecraft("bread"), ItemKey.minecraft("cookie"),
      ItemKey.minecraft("ender_pearl"));

  /** All default events (the original set plus the extended set), in catalog order. */
  public static List<RandomEvent> defaults() {
    List<RandomEvent> all = new ArrayList<>();
    all.addAll(originalEvents());
    all.addAll(extendedEvents());
    return List.copyOf(all);
  }

  /** The original 24 events. */
  private static List<RandomEvent> originalEvents() {
    return List.of(
        effect("speed_demon", "Speed Demon", "speed", 2, 15, 12),
        effect("slug_mode", "Slug Mode", "slowness", 2, 12, 10),
        effect("moon_walk", "Moon Walk", "levitation", 0, 6, 8),
        effect("flea_legs", "Flea Legs", "jump_boost", 4, 15, 10),
        effect("hulk", "Hulk Smash", "strength", 2, 20, 10),
        effect("noodle_arms", "Noodle Arms", "weakness", 1, 15, 8),
        effect("toxic", "Toxic Cloud", "poison", 1, 8, 8),
        effect("lights_out", "Lights Out", "blindness", 0, 8, 8),
        effect("dizzy", "Dizzy Spell", "nausea", 0, 12, 10),
        effect("ghost_mode", "Ghost Mode", "invisibility", 0, 15, 8),
        effect("disco", "Disco Fever", "glowing", 0, 20, 10),
        effect("night_owl", "Night Owl", "night_vision", 0, 30, 8),
        effect("floaty", "Floaty Feet", "slow_falling", 0, 20, 8),
        new RandomEvent("yeet", "YEET!", 10, RandomEventCatalog::yeet),
        new RandomEvent("full_heal", "Second Wind", 10, RandomEventCatalog::fullHeal),
        new RandomEvent("starving", "Sudden Hunger", 8, RandomEventCatalog::starve),
        new RandomEvent("feast", "Free Feast", 8, RandomEventCatalog::feast),
        new RandomEvent("zombie_siege", "Zombie Siege", 9, mobs("zombie", 5)),
        new RandomEvent("creeper_party", "Creeper Party", 8, mobs("creeper", 3)),
        new RandomEvent("cow_stampede", "Cow Stampede", 8, mobs("cow", 6)),
        new RandomEvent("tnt_rain", "TNT Rain", 7, RandomEventCatalog::tntRain),
        new RandomEvent("lightning", "Thor's Wrath", 7, RandomEventCatalog::lightning),
        new RandomEvent("shuffle", "Position Shuffle", 8, RandomEventCatalog::shuffle),
        new RandomEvent("item_rain", "Item Rain", 10, RandomEventCatalog::itemRain));
  }

  /**
   * 40 additional events, balanced by design: 20 negative, 10 neutral, 10 positive (50% / 25% / 25%).
   * Several are deliberately destructive (TNT at your feet, a ring of fire, a cobweb prison, the floor
   * dropping out, tools shattering, inventory wiped).
   */
  private static List<RandomEvent> extendedEvents() {
    List<RandomEvent> events = new ArrayList<>();

    // ----- NEGATIVE (20) -----
    events.add(new RandomEvent("spring_cleaning", "Spring Cleaning", 5, RandomEventCatalog::clearInventory));
    events.add(effects("molasses", "Molasses", 8, fx("slowness", 3, 12), fx("mining_fatigue", 2, 12)));
    events.add(effect("wilt", "Wilting", "wither", 1, 6, 7));
    events.add(effect("rumbly_tummy", "Rumbly Tummy", "hunger", 1, 15, 8));
    events.add(new RandomEvent("earthquake", "Earthquake", 8, RandomEventCatalog::earthquake));
    events.add(new RandomEvent("spider_swarm", "Spider Swarm", 8, mobs("cave_spider", 6)));
    events.add(new RandomEvent("bone_zone", "Bone Zone", 8, mobs("skeleton", 5)));
    events.add(new RandomEvent("phantom_menace", "Phantom Menace", 7, mobs("phantom", 4)));
    events.add(new RandomEvent("endermania", "Endermania", 7, mobs("enderman", 3)));
    events.add(new RandomEvent("tnt_gift", "TNT Gift", 5, RandomEventCatalog::tntGift));
    events.add(cage("ring_of_fire", "Ring of Fire", 6, ItemKey.minecraft("fire")));
    events.add(cage("cobweb_prison", "Cobweb Prison", 7, ItemKey.minecraft("cobweb")));
    events.add(effects("dark_souls", "Into Darkness", 8, fx("blindness", 0, 8), fx("darkness", 0, 8)));
    events.add(effect("venom", "Venom", "poison", 2, 10, 8));
    events.add(new RandomEvent("xp_thief", "XP Thief", 6, RandomEventCatalog::xpThief));
    events.add(new RandomEvent("butterfingers", "Butterfingers", 6, RandomEventCatalog::toolWear));
    events.add(new RandomEvent("witch_hunt", "Witch Hunt", 7, mobs("witch", 3)));
    events.add(new RandomEvent("silverfish_swarm", "Silverfish Swarm", 7, mobs("silverfish", 8)));
    events.add(effects("jelly_legs", "Jelly Legs", 8, fx("weakness", 2, 15), fx("slowness", 2, 15)));
    events.add(new RandomEvent("sinkhole", "Sinkhole", 6, RandomEventCatalog::sinkhole));

    // ----- NEUTRAL (10) -----
    events.add(new RandomEvent("disco_lights", "Disco Lights", 8, RandomEventCatalog::discoLights));
    events.add(new RandomEvent("oink", "Oink Oink", 8, mobs("pig", 5)));
    events.add(scale("mini_me", "Mini Me", 7, 0.55));
    events.add(scale("embiggen", "Embiggen", 7, 1.6));
    events.add(new RandomEvent("blink", "Blink", 8, RandomEventCatalog::blink));
    events.add(new RandomEvent("boo", "BOO!", 8, RandomEventCatalog::boo));
    events.add(new RandomEvent("batty", "Batty", 7, mobs("bat", 6)));
    events.add(new RandomEvent("frosty", "Frosty Friends", 7, mobs("snow_golem", 3)));
    events.add(new RandomEvent("axolotl_party", "Axolotl Party", 8, mobs("axolotl", 4)));
    events.add(new RandomEvent("sparkles", "Sparkles", 8, RandomEventCatalog::sparkles));

    // ----- POSITIVE (10) -----
    events.add(effects("sugar_rush", "Sugar Rush", 8, fx("speed", 2, 20), fx("haste", 2, 20)));
    events.add(effect("wolverine", "Wolverine", "regeneration", 2, 10, 8));
    events.add(effect("tank", "Tank Mode", "resistance", 2, 12, 8));
    events.add(effects("hero_time", "Hero Time", 7, fx("strength", 1, 20), fx("speed", 0, 20), fx("resistance", 0, 20)));
    events.add(goodies("midas_touch", "Midas Touch", 7, 3,
        ItemKey.minecraft("gold_ingot"), ItemKey.minecraft("gold_nugget"), ItemKey.minecraft("gold_block")));
    events.add(goodies("care_package", "Care Package", 7, 2,
        ItemKey.minecraft("diamond"), ItemKey.minecraft("cooked_beef"), ItemKey.minecraft("iron_ingot"),
        ItemKey.minecraft("golden_apple"), ItemKey.minecraft("oak_log")));
    events.add(effect("asbestos", "Asbestos Suit", "fire_resistance", 0, 30, 8));
    events.add(effects("golden_heart", "Golden Heart", 8, fx("absorption", 2, 30), fx("regeneration", 1, 8)));
    events.add(new RandomEvent("xp_jackpot", "XP Jackpot", 7, RandomEventCatalog::xpJackpot));
    events.add(effects("pogo", "Pogo Stick", 8, fx("jump_boost", 5, 20), fx("slow_falling", 0, 20)));

    return events;
  }

  /** A ready-to-use engine over the default catalog. */
  public static RandomEventEngine engine() {
    return new RandomEventEngine(defaults());
  }

  // ----- event builders ------------------------------------------------------------------------

  private static RandomEvent effect(String id, String name, String effect, int amplifier, int seconds, int weight) {
    return new RandomEvent(id, name, weight, context -> {
      for (PlayerAdapter player : context.players()) {
        player.addEffect(effect, amplifier, seconds * 20);
      }
    });
  }

  /** One potion-effect spec for the multi-effect {@link #effects} builder. */
  private record EffectSpec(String key, int amplifier, int seconds) {
  }

  private static EffectSpec fx(String key, int amplifier, int seconds) {
    return new EffectSpec(key, amplifier, seconds);
  }

  /** A per-player world action, given each player's OWN live world + current position. */
  private interface WorldAction {
    void run(WorldAdapter world, PlayerAdapter player, WorldPosition at);
  }

  /**
   * Runs {@code action} for every player that has a live world and a position. World events use each
   * player's OWN world ({@link PlayerAdapter#world()}) — always a full adapter — rather than a single
   * shared context world, which is what fixes drops/particles that were silently no-oping.
   */
  private static void forEachInWorld(RandomEventContext context, WorldAction action) {
    for (PlayerAdapter player : context.players()) {
      WorldAdapter world = player.world();
      WorldPosition at = player.position();
      if (world != null && at != null) {
        action.run(world, player, at);
      }
    }
  }

  /** Applies several potion effects to each player at once. */
  private static RandomEvent effects(String id, String name, int weight, EffectSpec... specs) {
    List<EffectSpec> list = List.of(specs);
    return new RandomEvent(id, name, weight, context -> {
      for (PlayerAdapter player : context.players()) {
        for (EffectSpec spec : list) {
          player.addEffect(spec.key(), spec.amplifier(), spec.seconds() * 20);
        }
      }
    });
  }

  /** Sets every player's body scale (funny giant/tiny). */
  private static RandomEvent scale(String id, String name, int weight, double scale) {
    return new RandomEvent(id, name, weight, context -> {
      for (PlayerAdapter player : context.players()) {
        player.setScale(scale);
      }
    });
  }

  /** Drops {@code perPlayer} random goodies from {@code pool} above each player. */
  private static RandomEvent goodies(String id, String name, int weight, int perPlayer, ItemKey... pool) {
    List<ItemKey> items = List.of(pool);
    return new RandomEvent(id, name, weight, context -> forEachInWorld(context, (world, player, at) -> {
      for (int index = 0; index < perPlayer; index++) {
        ItemKey item = items.get(context.random().nextInt(items.size()));
        WorldPosition above = new WorldPosition(at.worldName(),
            at.coordinateX(), at.coordinateY() + 2, at.coordinateZ(), 0f, 0f);
        world.dropItem(above, new ItemStackData(item, 1, Map.of()));
      }
    }));
  }

  /** Cages each player by filling the 3×3×2 shell around them (minus their own column) with {@code block}. */
  private static RandomEvent cage(String id, String name, int weight, ItemKey block) {
    return new RandomEvent(id, name, weight, context -> forEachInWorld(context, (world, player, at) -> {
      int bx = (int) Math.floor(at.coordinateX());
      int by = (int) Math.floor(at.coordinateY());
      int bz = (int) Math.floor(at.coordinateZ());
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          if (dx == 0 && dz == 0) {
            continue; // keep the player's own column clear
          }
          for (int dy = 0; dy <= 1; dy++) {
            world.setBlock(new BlockPosition(at.worldName(), bx + dx, by + dy, bz + dz), block);
          }
        }
      }
    }));
  }

  private static void clearInventory(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.clearInventory();
    }
  }

  private static void earthquake(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.launch((context.random().nextDouble() - 0.5) * 0.8, 0.9, (context.random().nextDouble() - 0.5) * 0.8);
      player.addEffect("nausea", 0, 120);
    }
  }

  private static void tntGift(RandomEventContext context) {
    forEachInWorld(context, (world, player, at) -> world.spawnTnt(at, 40));
  }

  private static void xpThief(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.setExperiencePoints(0);
    }
  }

  private static void xpJackpot(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.setExperiencePoints(player.experiencePoints() + 100);
    }
  }

  private static void toolWear(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.damageHeldItem(150);
    }
  }

  /** Removes the block under each player's feet — a genuine surprise in a void world. */
  private static void sinkhole(RandomEventContext context) {
    ItemKey air = ItemKey.minecraft("air");
    forEachInWorld(context, (world, player, at) -> world.setBlock(new BlockPosition(at.worldName(),
        (int) Math.floor(at.coordinateX()),
        (int) Math.floor(at.coordinateY()) - 1,
        (int) Math.floor(at.coordinateZ())), air));
  }

  private static void discoLights(RandomEventContext context) {
    forEachInWorld(context, (world, player, at) -> {
      for (int index = 0; index < 24; index++) {
        WorldPosition spark = new WorldPosition(at.worldName(),
            at.coordinateX() + (context.random().nextDouble() - 0.5) * 3,
            at.coordinateY() + 0.5 + context.random().nextDouble() * 2.5,
            at.coordinateZ() + (context.random().nextDouble() - 0.5) * 3, 0f, 0f);
        world.spawnDust(spark, context.random().nextInt(0xFFFFFF), 1.6f);
      }
    });
  }

  private static void sparkles(RandomEventContext context) {
    forEachInWorld(context, (world, player, at) -> {
      player.playSound(new SoundKey("minecraft:entity.firework_rocket.twinkle"), 1.0f, 1.0f);
      for (int index = 0; index < 20; index++) {
        world.spawnDust(new WorldPosition(at.worldName(),
            at.coordinateX() + (context.random().nextDouble() - 0.5) * 2.5,
            at.coordinateY() + 1 + context.random().nextDouble() * 2,
            at.coordinateZ() + (context.random().nextDouble() - 0.5) * 2.5, 0f, 0f),
            context.random().nextInt(0xFFFFFF), 1.6f);
      }
    });
  }

  private static void blink(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      WorldPosition at = player.position();
      if (at != null) {
        player.teleport(new WorldPosition(at.worldName(),
            at.coordinateX() + (context.random().nextDouble() - 0.5) * 10,
            at.coordinateY() + 2,
            at.coordinateZ() + (context.random().nextDouble() - 0.5) * 10, at.yaw(), at.pitch()));
      }
    }
  }

  private static void boo(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.sendMiniMessage("<dark_red><bold>BOO!</bold></dark_red>");
      player.playSound(new SoundKey("minecraft:entity.enderman.scream"), 1.0f, 1.0f);
    }
  }

  /** Spawns {@code count} of a mob type at each player's feet. */
  private static Consumer<RandomEventContext> mobs(String mob, int count) {
    return context -> forEachInWorld(context, (world, player, at) -> world.spawnMob(at, mob, count));
  }

  private static void yeet(RandomEventContext context) {
    Random random = context.random();
    for (PlayerAdapter player : context.players()) {
      player.launch((random.nextDouble() - 0.5) * 1.5, 1.2, (random.nextDouble() - 0.5) * 1.5);
      player.playSound(new SoundKey("minecraft:entity.firework_rocket.launch"), 1.0f, 1.0f);
    }
  }

  private static void fullHeal(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.setHealth(player.maxHealth());
      player.setFoodLevel(20);
      player.addEffect("regeneration", 1, 100);
    }
  }

  private static void starve(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.setFoodLevel(0);
    }
  }

  private static void feast(RandomEventContext context) {
    for (PlayerAdapter player : context.players()) {
      player.setFoodLevel(20);
      player.addEffect("saturation", 2, 40);
    }
  }

  private static void tntRain(RandomEventContext context) {
    forEachInWorld(context, (world, player, at) -> {
      for (int index = 0; index < 3; index++) {
        WorldPosition above = new WorldPosition(at.worldName(),
            at.coordinateX() + (context.random().nextDouble() - 0.5) * 4,
            at.coordinateY() + 8,
            at.coordinateZ() + (context.random().nextDouble() - 0.5) * 4, 0f, 0f);
        world.spawnTnt(above, 60);
      }
    });
  }

  private static void lightning(RandomEventContext context) {
    forEachInWorld(context, (world, player, at) -> world.strikeLightning(at));
  }

  /** Rotates every player onto the next player's position (a group teleport swap). */
  private static void shuffle(RandomEventContext context) {
    List<PlayerAdapter> players = context.players();
    if (players.size() < 2) {
      return;
    }
    WorldPosition first = players.get(0).position();
    for (int index = 0; index < players.size(); index++) {
      WorldPosition target = index + 1 < players.size() ? players.get(index + 1).position() : first;
      if (target != null) {
        players.get(index).teleport(target);
      }
    }
  }

  private static void itemRain(RandomEventContext context) {
    Random random = context.random();
    forEachInWorld(context, (world, player, at) -> {
      for (int index = 0; index < 3; index++) {
        ItemKey item = ITEM_RAIN.get(random.nextInt(ITEM_RAIN.size()));
        WorldPosition above = new WorldPosition(at.worldName(),
            at.coordinateX(), at.coordinateY() + 3, at.coordinateZ(), 0f, 0f);
        world.dropItem(above, new ItemStackData(item, 1, Map.of()));
      }
    });
  }
}
