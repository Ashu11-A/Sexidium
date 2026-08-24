package com.sexidium.core.game.experience;

import com.sexidium.core.game.experience.challenges.BlockDeleterChallenge;
import com.sexidium.core.game.experience.challenges.BreakOneBreakAllChallenge;
import com.sexidium.core.game.experience.challenges.ChainedChallenge;
import com.sexidium.core.game.experience.challenges.ChunkBreakChallenge;
import com.sexidium.core.game.experience.challenges.ClassicSkyblockChallenge;
import com.sexidium.core.game.experience.challenges.CleaveChallenge;
import com.sexidium.core.game.experience.challenges.DoubleDropsChallenge;
import com.sexidium.core.game.experience.challenges.GrowingChallenge;
import com.sexidium.core.game.experience.challenges.JumpEnchantsChallenge;
import com.sexidium.core.game.experience.challenges.JumpMultipliesChallenge;
import com.sexidium.core.game.experience.challenges.DeathResetsChallenge;
import com.sexidium.core.game.experience.challenges.LayeredDimensionsChallenge;
import com.sexidium.core.game.experience.challenges.LayeredWorldChallenge;
import com.sexidium.core.game.experience.challenges.LookMultipliesChallenge;
import com.sexidium.core.game.experience.challenges.MobDuplicationChallenge;
import com.sexidium.core.game.experience.challenges.OmniChunkChallenge;
import com.sexidium.core.game.experience.challenges.RandomChunksChallenge;
import com.sexidium.core.game.experience.challenges.RandomDropsChallenge;
import com.sexidium.core.game.experience.challenges.RandomEventsChallenge;
import com.sexidium.core.game.experience.challenges.RandomMobChallenge;
import com.sexidium.core.game.experience.challenges.RandomSkyblockChallenge;
import com.sexidium.core.game.experience.challenges.RandomizerChallenge;
import com.sexidium.core.game.experience.challenges.SharedInventoryChallenge;
import com.sexidium.core.game.experience.challenges.SharedLifeChallenge;
import com.sexidium.core.game.experience.challenges.ShrinkingAchievementsChallenge;
import com.sexidium.core.game.experience.challenges.WalkingBlocksChallenge;
import com.sexidium.core.game.experience.challenges.XpHealthChallenge;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The catalog of selectable experience challenges a player can compose into an experience. It is
 * the single source of truth for which twists exist, their display name and GUI icon, and how to
 * instantiate one. {@link com.sexidium.core.game.GameRegistry} no longer registers these as standalone
 * games — they only ever run inside an {@link ExperienceGame}.
 */
public final class ChallengeCatalog {
  /**
   * One catalog row: stable id, human display name, chest-GUI icon, a one-line description of what the
   * twist does, and a fresh-instance factory. The {@code description} is what the lobby GUI shows under
   * each challenge so a player (especially on Bedrock, where the chest GUI is a flat tap-grid with no
   * hover tooltip) can tell what a twist does before adding it to a persistent world.
   */
  public record Entry(String id, String displayName, ItemKey icon, String description, Supplier<Challenge> factory) {
  }

  private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

  static {
    register("doubledrops", "Double Drops", "emerald", "Blocks and mobs drop double loot.", DoubleDropsChallenge::new);
    register("randomizer", "Randomizer", "crafting_table", "Every drop is shuffled into a random item.", RandomizerChallenge::new);
    register("sharedlife", "Shared Life", "red_dye", "Everyone shares a single health bar.", SharedLifeChallenge::new);
    register("sharedinventory", "Shared Inventory", "chest", "All players share one live inventory.", SharedInventoryChallenge::new);
    register("xphealth", "XP Health", "experience_bottle", "Your XP level IS your health.", XpHealthChallenge::new);
    register("shrinkingachievements", "Shrinking Achievements", "barrier", "The world border keeps shrinking.", ShrinkingAchievementsChallenge::new);
    register("breakonebreakall", "Break One Break All", "diamond_pickaxe", "Break one block, break all of its type.", BreakOneBreakAllChallenge::new);
    register("chunkbreak", "Chunk Break", "iron_pickaxe", "Break one block, break its type in that chunk.", ChunkBreakChallenge::new);
    register("blockdeleter", "Block Deleter", "coal_block", "Breaking a block deletes that type nearby.", BlockDeleterChallenge::new);
    register("randomchunks", "Random Chunks", "grass_block", "Chunks convert into random blocks.", RandomChunksChallenge::new);
    register("walkingblocks", "Walking Blocks", "magma_block", "A trail of blocks builds where you walk.", WalkingBlocksChallenge::new);
    register("chained", "Chained Together", "chain", "All players are leashed together.", ChainedChallenge::new);
    register("cleave", "Total Cleave", "netherite_sword", "Each hit cleaves every nearby mob.", CleaveChallenge::new);
    register("growing", "Endless Growth", "slime_block", "You grow larger the longer you live.", GrowingChallenge::new);
    register("jumpenchants", "Jump Enchants", "enchanted_book", "Jumping randomly enchants your gear.", JumpEnchantsChallenge::new);
    register("mobduplication", "Mob Duplication", "slime_ball", "Hitting a mob can duplicate it.", MobDuplicationChallenge::new);
    register("lookmultiplies", "Look Multiplies", "ender_eye", "Everything you look at multiplies.", LookMultipliesChallenge::new);
    register("jumpmultiplies", "Jump Multiplies", "rabbit_foot", "Every jump duplicates every entity around you.", JumpMultipliesChallenge::new);
    register("randomlayers", "Random Layers", "dirt", "New random layers are added every day.", LayeredWorldChallenge::new);
    register("randomskyblock", "Random Skyblock", "grass_block", "One void block gives random items as you break it.", RandomSkyblockChallenge::new);
    register("randommob", "Random Mob", "zombie_spawn_egg", "Random mobs spawn beside you on a timer.", RandomMobChallenge::new);
    register("randomdrops", "Randomized Drops", "dropper", "Blocks and mobs drop a random item each time.", RandomDropsChallenge::new);
    register("randomevents", "Random Events", "firework_rocket", "A chaotic random event fires every so often.", RandomEventsChallenge::new);
    register("classicskyblock", "Classic Skyblock", "grass_block", "The classic vanilla Skyblock island, with a Nether mirror.", ClassicSkyblockChallenge::new);
    register("omnichunk", "Omni Chunk", "chiseled_stone_bricks", "Every block you place or break copies into every chunk.", OmniChunkChallenge::new);
    register("layereddimensions", "Layered Dimensions", "deepslate", "One chunk of stacked layers in every dimension — dig down through all of them.", LayeredDimensionsChallenge::new);
    register("deathresets", "Death Resets", "totem_of_undying", "Hardcore with no goals — when anyone dies, everyone resets and the world is regenerated.", DeathResetsChallenge::new);
  }

  private ChallengeCatalog() {
  }

  private static void register(String id, String displayName, String icon, String description, Supplier<Challenge> factory) {
    ENTRIES.put(id, new Entry(id, displayName, ItemKey.minecraft(icon), description, factory));
  }

  /** All catalog rows in display order. */
  public static List<Entry> available() {
    return new ArrayList<>(ENTRIES.values());
  }

  /**
   * The rows a player picks freely as twists: everything except the map-generating challenges, which
   * own the world's terrain and are chosen — one at a time — through {@link ExperienceWorldType}
   * instead. They stay in the catalog (an experience still stores and runs them as challenges); they
   * just never appear in the multi-select grid, which is what makes two maps impossible to combine.
   */
  public static List<Entry> selectable() {
    Set<String> mapChallenges = ExperienceWorldType.mapChallengeIds();
    List<Entry> entries = new ArrayList<>();
    for (Entry entry : ENTRIES.values()) {
      if (!mapChallenges.contains(entry.id())) {
        entries.add(entry);
      }
    }
    return entries;
  }

  /** Whether {@code id} is a map-generating challenge (owned by a {@link ExperienceWorldType}). */
  public static boolean isMapChallenge(String id) {
    return ExperienceWorldType.forChallenge(normalize(id)) != null;
  }

  /**
   * The map-generating challenge ids inside a requested set, deduplicated in request order. More than
   * one entry means the request tries to generate two different maps into the same world — the conflict
   * the world-type selector exists to prevent, and which the command path rejects.
   */
  public static List<String> mapChallenges(List<String> ids) {
    List<String> found = new ArrayList<>();
    if (ids != null) {
      for (String rawId : ids) {
        String id = normalize(rawId);
        if (isMapChallenge(id) && !found.contains(id)) {
          found.add(id);
        }
      }
    }
    return found;
  }

  public static boolean contains(String id) {
    return id != null && ENTRIES.containsKey(normalize(id));
  }

  /**
   * The requested ids this build does not have, in request order, deduplicated.
   *
   * <p>Blank entries are not "unknown" — they are nothing, and {@link #create} has always skipped
   * them. Only a non-blank id with no catalog row counts.</p>
   */
  public static List<String> unknown(List<String> ids) {
    List<String> missing = new ArrayList<>();
    if (ids == null) {
      return missing;
    }
    for (String rawId : ids) {
      String id = normalize(rawId);
      if (!id.isEmpty() && !ENTRIES.containsKey(id) && !RETIRED.contains(id) && !missing.contains(id)) {
        missing.add(id);
      }
    }
    return missing;
  }

  /**
   * Ids this project once shipped and then deliberately removed.
   *
   * <p>The strict queries exist for <b>version skew</b>: an id this build cannot read may be one
   * another node CAN, so refusing is right — guessing generates normal terrain over a void SkyBlock and
   * destroys the save. Retirement is the opposite situation. Nobody has these; no node ever will again.
   * Refusing them does not protect a save, it strands one: the world becomes unopenable everywhere,
   * <em>and</em> its Manage screen throws while rendering, so the owner cannot even remove the offending
   * twist to recover. There is no version of the network on which waiting helps.</p>
   *
   * <p>Answering "no demand" for these is not a guess. The two whose source survives in history
   * ({@code EvolvingMobsChallenge}, {@code TntMobsChallenge}) override <b>none</b> of
   * {@code requiresVoidWorld} / {@code requiresVoidNether} / {@code requiresHardcore} — verified against
   * the commit that deleted them — and the two older ones were the same cosmetic kind. They shaped no
   * world, so a world that named them was generated exactly as a world without them.</p>
   *
   * <p><b>Adding to this set is a deliberate act.</b> An id belongs here only once it is gone from the
   * catalog for good AND is known never to have shaped a world. Anything else is skew, and skew must
   * still be refused.</p>
   */
  private static final java.util.Set<String> RETIRED =
      java.util.Set.of("evolvingmobs", "tntmobs", "lavafloor", "midastouch");

  /** Whether {@code id} is a retired challenge: gone for good, and safe to ignore rather than refuse. */
  public static boolean retired(String id) {
    return id != null && RETIRED.contains(normalize(id));
  }

  /**
   * Refuse to answer a question whose answer depends on ids this build cannot read.
   *
   * <p>Applied to the four <b>world-shaping</b> queries below and nowhere else. Those four decide the
   * <em>generator</em> and the stakes, and a wrong answer there is unrecoverable — see
   * {@link UnknownChallengeException}. Composition-time {@link #create} stays tolerant on purpose:
   * silently dropping a cosmetic twist is survivable, and refusing to open an experience over one
   * would be a worse outcome than running it without that twist.</p>
   */
  public static void requireKnown(List<String> ids) {
    List<String> missing = unknown(ids);
    if (!missing.isEmpty()) {
      throw new UnknownChallengeException(missing);
    }
  }

  /**
   * Whether any of the requested challenges needs its experience generated as an empty VOID world (see
   * {@link Challenge#requiresVoidWorld()}). Resolved from fresh instances, so world creation can decide
   * the generator from just the stored challenge ids, before the experience is composed.
   *
   * @throws UnknownChallengeException if any requested id is not in this build's catalog
   */
  public static boolean anyRequiresVoidWorld(List<String> ids) {
    requireKnown(ids);
    return create(ids).stream().anyMatch(Challenge::requiresVoidWorld);
  }

  /**
   * Whether any requested challenge needs its linked Nether generated void too (see
   * {@link Challenge#requiresVoidNether()}).
   *
   * @throws UnknownChallengeException if any requested id is not in this build's catalog
   */
  public static boolean anyRequiresVoidNether(List<String> ids) {
    requireKnown(ids);
    return create(ids).stream().anyMatch(Challenge::requiresVoidNether);
  }

  /**
   * Whether any of the requested challenges forces its experience to be HARDCORE regardless of the
   * owner's toggle (see {@link Challenge#requiresHardcore()}). Resolved from fresh instances, like the
   * void flags, because the answer is needed before the experience is composed — and again afterwards,
   * to decide whether the owner is allowed to turn hardcore back off.
   *
   * @throws UnknownChallengeException if any requested id is not in this build's catalog
   */
  public static boolean anyRequiresHardcore(List<String> ids) {
    return hardcoreDemand(ids).required();
  }

  /**
   * Everything the hardcore system needs to know about a selection, resolved in one pass: whether the
   * stakes are forced, what a death costs, and which challenge is responsible.
   *
   * <p>Prefer this over asking the three questions separately. They used to be resolved independently by
   * each caller, which is how the manage screen ended up offering "Hardcore: OFF — click to turn it on"
   * for an experience the service had already forced on and would refuse to turn off.</p>
   *
   * @throws UnknownChallengeException if any requested id is not in this build's catalog — a
   *     challenge this build cannot read may be the very one that forces hardcore, and answering
   *     "not hardcore" for it turns a one-death-ends-it world into an ordinary one
   */
  public static com.sexidium.core.game.hardcore.HardcoreDemand hardcoreDemand(List<String> ids) {
    requireKnown(ids);
    return com.sexidium.core.game.hardcore.HardcoreDemand.of(
        create(ids).stream()
            .map(challenge -> (com.sexidium.core.game.hardcore.HardcoreDemand.Source)
                new ChallengeHardcoreSource(challenge))
            .toList());
  }

  /** Adapts a challenge to what the hardcore system asks of it, so that package imports nothing here. */
  private record ChallengeHardcoreSource(Challenge challenge)
      implements com.sexidium.core.game.hardcore.HardcoreDemand.Source {
    @Override
    public boolean requiresHardcore() {
      return challenge.requiresHardcore();
    }

    @Override
    public com.sexidium.core.game.hardcore.HardcoreDeathOutcome deathOutcome() {
      return challenge.hardcoreDeathOutcome();
    }

    @Override
    public String displayName() {
      return challenge.displayName();
    }
  }

  public static Entry get(String id) {
    return id == null ? null : ENTRIES.get(normalize(id));
  }

  public static ItemKey iconFor(String id) {
    Entry entry = get(id);
    return entry == null ? ItemKey.minecraft("paper") : entry.icon();
  }

  public static String displayNameFor(String id) {
    Entry entry = get(id);
    return entry == null ? id : entry.displayName();
  }

  public static String descriptionFor(String id) {
    Entry entry = get(id);
    return entry == null ? "" : entry.description();
  }

  /**
   * Resolves a list of requested challenge ids into fresh {@link Challenge} instances, in request
   * order, deduplicated, skipping any unknown id. An empty/blank request yields an empty list (the
   * experience would have no twist — the caller should reject that).
   */
  public static List<Challenge> create(List<String> ids) {
    List<Challenge> challenges = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    if (ids != null) {
      for (String rawId : ids) {
        String id = normalize(rawId);
        if (id.isEmpty() || seen.contains(id)) {
          continue;
        }
        Entry entry = ENTRIES.get(id);
        if (entry != null) {
          seen.add(id);
          challenges.add(entry.factory().get());
        }
      }
    }
    return challenges;
  }

  private static String normalize(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }
}
