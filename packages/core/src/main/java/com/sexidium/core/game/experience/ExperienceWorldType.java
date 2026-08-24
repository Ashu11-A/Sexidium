package com.sexidium.core.game.experience;

import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldDimension;
import com.sexidium.core.platform.model.WorldTerrain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The MAP TYPE of an experience: what kind of world is generated and which dimension the player starts
 * in. Exactly one is chosen per experience, which is what makes the map-generating twists
 * (Classic Skyblock, Random Skyblock, Random Layers) mutually exclusive — they used to be ordinary
 * challenges in the same multi-select grid, so two of them could be ticked at once and then fight over
 * the same void world.
 *
 * <p>Two kinds of entry live here:</p>
 * <ul>
 *   <li><b>Start dimensions</b> ({@link #NORMAL}, {@link #NETHER}, {@link #END}) — a normal terrain
 *       world; the type only decides which of the experience's linked dimensions the player is dropped
 *       into. Every experience already gets its own Nether and End siblings.</li>
 *   <li><b>Terrain presets</b> ({@link #SUPERFLAT}, {@link #LARGE_BIOMES}, {@link #AMPLIFIED}) — an
 *       ordinary Overworld start, but the terrain is generated with a vanilla world-generation preset
 *       ({@link WorldTerrain}). These are NOT challenges: nothing builds the map, the generator does, so
 *       they add no challenge and appear in no challenge grid.</li>
 *   <li><b>Generated maps</b> ({@link #CLASSIC_SKYBLOCK}, {@link #RANDOM_SKYBLOCK},
 *       {@link #RANDOM_LAYERS}) — a void world built by the challenge named in {@link #challengeId()},
 *       which is added to the experience automatically when the type is picked.</li>
 * </ul>
 *
 * <p>Anything that decides how the world is GENERATED — a preset or a generated map — is
 * {@link #fixedAtCreation()}: the terrain is baked in the moment the world exists, so it can never be
 * changed on an existing experience. Only the plain start dimension is re-pointable later.</p>
 *
 * <p>The chosen type is persisted on the experience row ({@code world_type}) and carried into a running
 * match as a {@code world:<id>} token in the mode args, so it survives a server restart with the rest
 * of the match state.</p>
 */
public enum ExperienceWorldType {
  NORMAL("normal", "Normal World", "grass_block", WorldDimension.OVERWORLD, null, WorldTerrain.NORMAL,
      "Standard terrain. You start on the surface."),
  NETHER("nether", "Nether", "netherrack", WorldDimension.NETHER, null, WorldTerrain.NORMAL,
      "Normal terrain, but you start in the Nether."),
  END("end", "The End", "end_stone", WorldDimension.END, null, WorldTerrain.NORMAL,
      "Normal terrain, but you start in The End."),
  SUPERFLAT("superflat", "Superflat", "smooth_stone_slab", WorldDimension.OVERWORLD, null,
      WorldTerrain.SUPERFLAT, "Flat layers to the horizon — no hills, no caves."),
  LARGE_BIOMES("largebiomes", "Large Biomes", "jungle_sapling", WorldDimension.OVERWORLD, null,
      WorldTerrain.LARGE_BIOMES, "Normal terrain, but every biome is enormous."),
  AMPLIFIED("amplified", "Amplified", "granite", WorldDimension.OVERWORLD, null,
      WorldTerrain.AMPLIFIED, "Extreme mountains and deep canyons."),
  CLASSIC_SKYBLOCK("classicskyblock", "Classic Skyblock", "grass_block", WorldDimension.OVERWORLD,
      "classicskyblock", WorldTerrain.NORMAL, "The classic island in the void, with a Nether mirror."),
  RANDOM_SKYBLOCK("randomskyblock", "Random Skyblock", "dropper", WorldDimension.OVERWORLD,
      "randomskyblock", WorldTerrain.NORMAL, "One block in the void that gives random items."),
  RANDOM_LAYERS("randomlayers", "Random Layers", "dirt", WorldDimension.OVERWORLD,
      "randomlayers", WorldTerrain.NORMAL, "A void island that grows random layers every day."),
  LAYERED_DIMENSIONS("layereddimensions", "Layered Dimensions", "deepslate", WorldDimension.OVERWORLD,
      "layereddimensions", WorldTerrain.NORMAL,
      "One chunk of stacked layers in every dimension. Dig down through all of them.");

  /** Prefix of the mode-arg token that carries the chosen type into a running match. */
  public static final String ARG_PREFIX = "world:";

  private final String id;
  private final String displayName;
  private final ItemKey icon;
  private final WorldDimension dimension;
  private final String challengeId;
  private final WorldTerrain terrain;
  private final String description;

  ExperienceWorldType(String id, String displayName, String icon, WorldDimension dimension,
      String challengeId, WorldTerrain terrain, String description) {
    this.id = id;
    this.displayName = displayName;
    this.icon = ItemKey.minecraft(icon);
    this.dimension = dimension;
    this.challengeId = challengeId;
    this.terrain = terrain == null ? WorldTerrain.NORMAL : terrain;
    this.description = description;
  }

  public String id() {
    return id;
  }

  public String displayName() {
    return displayName;
  }

  public ItemKey icon() {
    return icon;
  }

  /** Which of the experience's linked dimensions the player starts in. */
  public WorldDimension dimension() {
    return dimension;
  }

  /** The challenge that builds this map, or {@code null} for a plain terrain world. */
  public String challengeId() {
    return challengeId;
  }

  public String description() {
    return description;
  }

  /** Which vanilla generation preset this type's terrain uses. */
  public WorldTerrain terrain() {
    return terrain;
  }

  /** Whether this type generates its own map (and therefore owns the world's terrain). */
  public boolean generatesMap() {
    return challengeId != null;
  }

  /**
   * Whether picking this type decides how the world is GENERATED — a generated map, or a non-default
   * terrain preset. Terrain is baked in the moment a world is created, so such a type can neither be
   * chosen for, nor changed on, an experience that already exists.
   */
  public boolean fixedAtCreation() {
    return generatesMap() || terrain != WorldTerrain.NORMAL;
  }

  /** The plain-terrain types, which differ only in the dimension the player starts in. */
  public static List<ExperienceWorldType> startDimensions() {
    return List.of(NORMAL, NETHER, END);
  }

  /**
   * The vanilla generation presets: an ordinary Overworld start on differently-shaped terrain. They own
   * no challenge, so they never appear in the challenge grid.
   */
  public static List<ExperienceWorldType> terrainPresets() {
    return List.of(SUPERFLAT, LARGE_BIOMES, AMPLIFIED);
  }

  /** The types that generate their own map — one, and only one, may be active per experience. */
  public static List<ExperienceWorldType> generatedMaps() {
    return List.of(CLASSIC_SKYBLOCK, RANDOM_SKYBLOCK, RANDOM_LAYERS, LAYERED_DIMENSIONS);
  }

  /** Ids of every challenge that owns a world's terrain (so the challenge grid can leave them out). */
  public static Set<String> mapChallengeIds() {
    Set<String> ids = new LinkedHashSet<>();
    for (ExperienceWorldType type : generatedMaps()) {
      ids.add(type.challengeId());
    }
    return ids;
  }

  /** Resolves a stored/typed id; anything unknown or blank falls back to {@link #NORMAL}. */
  public static ExperienceWorldType of(String id) {
    if (id != null && !id.isBlank()) {
      String wanted = id.trim().toLowerCase(Locale.ROOT);
      for (ExperienceWorldType type : values()) {
        if (type.id.equals(wanted)) {
          return type;
        }
      }
    }
    return NORMAL;
  }

  /** The type owning {@code challengeId}, or {@code null} when that challenge does not generate a map. */
  public static ExperienceWorldType forChallenge(String challengeId) {
    if (challengeId == null) {
      return null;
    }
    String wanted = challengeId.trim().toLowerCase(Locale.ROOT);
    for (ExperienceWorldType type : generatedMaps()) {
      if (type.challengeId().equals(wanted)) {
        return type;
      }
    }
    return null;
  }

  /**
   * The effective type of an experience: whichever map-generating challenge it runs wins (that
   * challenge already owns the terrain), otherwise the stored start dimension. This keeps experiences
   * created before the world-type selector existed reading correctly from their challenge list alone.
   */
  public static ExperienceWorldType resolve(List<String> challengeIds, String storedTypeId) {
    if (challengeIds != null) {
      for (String id : challengeIds) {
        ExperienceWorldType type = forChallenge(id);
        if (type != null) {
          return type;
        }
      }
    }
    ExperienceWorldType stored = of(storedTypeId);
    // A stored generated-map type whose challenge is no longer in the list is stale — the map is gone.
    return stored.generatesMap() ? NORMAL : stored;
  }

  // ----- mode-arg transport ---------------------------------------------------------------------

  /** The mode-arg token for this type ({@code world:<id>}). */
  public String toArg() {
    return ARG_PREFIX + id;
  }

  /** Reads the {@code world:<id>} token out of a mode-arg list; {@link #NORMAL} when absent. */
  public static ExperienceWorldType fromArgs(List<String> modeArgs) {
    if (modeArgs != null) {
      for (String arg : modeArgs) {
        if (arg != null && arg.toLowerCase(Locale.ROOT).startsWith(ARG_PREFIX)) {
          return of(arg.substring(ARG_PREFIX.length()));
        }
      }
    }
    return NORMAL;
  }

  /** Whether {@code arg} is a world-type token rather than a challenge id. */
  public static boolean isArg(String arg) {
    return arg != null && arg.toLowerCase(Locale.ROOT).startsWith(ARG_PREFIX);
  }

  /** The mode args with every world-type token removed, leaving only challenge ids. */
  public static List<String> stripArgs(List<String> modeArgs) {
    List<String> result = new ArrayList<>();
    if (modeArgs != null) {
      for (String arg : modeArgs) {
        if (!isArg(arg)) {
          result.add(arg);
        }
      }
    }
    return result;
  }

  /**
   * The mode args for a match: the challenge ids plus this type's token, and — for a generated map —
   * its own challenge id when the caller did not already include it.
   */
  public List<String> toModeArgs(List<String> challengeIds) {
    List<String> args = new ArrayList<>(stripArgs(challengeIds));
    if (generatesMap() && !args.contains(challengeId)) {
      args.add(challengeId);
    }
    args.add(toArg());
    return args;
  }
}
