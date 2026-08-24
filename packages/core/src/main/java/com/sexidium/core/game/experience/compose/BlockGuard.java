package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.ItemKey;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The world-integrity guard: the single authority on whether a challenge may destroy or replace a block.
 *
 * <h2>Why this exists</h2>
 * Challenges are deliberately destructive — Break-One-Break-All erases a whole block type, Block Deleter
 * removes it for good, Random Chunks rewrites terrain wholesale, Omni Chunk copies every edit into every
 * chunk. Each of them used to carry its own idea of what was off-limits (or none at all), so a block that
 * one challenge protected another would happily delete. The visible symptom was an <b>End portal frame
 * being destroyed by world replication</b>, which can make a world unbeatable.
 *
 * <p>So the rule lives here instead, once, and every mode that touches blocks asks the same question
 * through {@link BlockBreakService}. A protected block is protected no matter which challenge is running,
 * how many are combined, or which code path reaches it.</p>
 *
 * <h2>What is protected by default</h2>
 * Blocks whose loss <em>breaks the world rather than changing it</em>: the floor and the barriers that
 * hold it up, the structures that let players finish the game (End portal and its frames, the End
 * gateway, the ancient-city portal frame), and the admin blocks a server relies on. Everything else stays
 * destructible — per the fun-first policy, burying or erasing something you needed is the mode working as
 * intended. Servers can extend or replace the list from config.
 */
public final class BlockGuard {
  /**
   * The blocks no challenge may ever break or replace. Vanilla already treats most of these as
   * indestructible in survival; the rest are the ones that make a world completable or administrable.
   */
  public static final List<String> DEFAULT_PROTECTED = List.of(
      // The world's floor and ceiling, and the invisible walls a map may rely on.
      "bedrock", "barrier", "light", "structure_void",
      // The way out — losing any of these can make a world literally unbeatable.
      "end_portal", "end_portal_frame", "end_gateway", "reinforced_deepslate",
      // Admin/structure blocks: never a challenge's business.
      "command_block", "chain_command_block", "repeating_command_block", "structure_block", "jigsaw",
      // Storage. A destroyed container does not just change the world — it takes the player's THINGS with
      // it, which no amount of "that is the mode working" excuses. A sweep that chews through terrain now
      // flows around your chests instead of emptying them into the void.
      "chest", "trapped_chest", "ender_chest", "barrel", "shulker_box");

  /** The path challenges read their shared protected list from. */
  public static final String CONFIG_PATH = "experiences.common.protected-blocks";

  private final Set<String> protectedValues;
  /** The same set with every family variant spelled out, for callers that can only match exact ids. */
  private final Set<String> preserved;

  public BlockGuard(Collection<String> values) {
    Set<String> resolved = new LinkedHashSet<>();
    for (String value : values == null || values.isEmpty() ? DEFAULT_PROTECTED : values) {
      if (value != null && !value.isBlank()) {
        resolved.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    this.protectedValues = Set.copyOf(resolved);
    this.preserved = expand(this.protectedValues);
  }

  /** The guard for a server's configuration; an unset or empty list keeps {@link #DEFAULT_PROTECTED}. */
  public static BlockGuard fromConfig(ConfigurationAdapter configuration) {
    return new BlockGuard(configuration == null ? null : configuration.getStringList(CONFIG_PATH));
  }

  /** The default guard, for hosts without configuration (tests, headless). */
  public static BlockGuard defaults() {
    return new BlockGuard(null);
  }

  /**
   * Block families whose every colour/wood variant is protected when the family is. Listing sixteen
   * shulker boxes (and every copper-chest variant a future version adds) by hand is exactly the kind of
   * list that silently goes stale, so the suffix is matched instead.
   */
  private static final List<String> PROTECTED_SUFFIXES = List.of("_shulker_box", "_chest");

  /** Whether this block may never be destroyed or replaced by a challenge. */
  public boolean isProtected(ItemKey block) {
    if (block == null) {
      return false;
    }
    String value = block.value().toLowerCase(Locale.ROOT);
    if (protectedValues.contains(value)) {
      return true;
    }
    // A variant counts only when its family is protected — so a server that removes "chest" from the list
    // also stops "trapped_chest" being protected, rather than the two disagreeing.
    for (String suffix : PROTECTED_SUFFIXES) {
      if (value.endsWith(suffix) && protectedValues.contains(suffix.substring(1))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether the block CURRENTLY at this position may be destroyed or replaced. This is the position-level
   * check for anything that edits blocks one at a time; it reads the world, so callers that already know
   * the type should use {@link #isProtected} instead.
   */
  public boolean mayModify(WorldAdapter world, BlockPosition at) {
    if (world == null || at == null) {
      return false;
    }
    return !isProtected(world.blockTypeAt(at));
  }

  /**
   * The subset of {@code typeValues} a bulk sweep is allowed to remove. Sweeps hand the platform a set of
   * block ids to erase in one call, so the guard has to be applied to the SET rather than to each
   * position — filtering here is what stops "break every block of this type" from including a protected
   * one. Returns the input untouched when nothing is filtered, so the common case allocates nothing.
   */
  public Set<String> breakableTypes(Set<String> typeValues) {
    if (typeValues == null || typeValues.isEmpty()) {
      return typeValues;
    }
    Set<String> allowed = null;
    for (String value : typeValues) {
      if (value != null && isProtected(ItemKey.minecraft(value))) {
        if (allowed == null) {
          allowed = new LinkedHashSet<>(typeValues);
        }
        allowed.remove(value);
      }
    }
    return allowed == null ? typeValues : allowed;
  }

  /**
   * The protected ids as a set a platform bulk operation can be told to preserve — e.g. the
   * {@code preservedBlockValues} of {@link WorldAdapter#convertChunk}, which rewrites a whole chunk and
   * therefore has to be told what to leave alone up front.
   */
  public Set<String> preservedValues() {
    return preserved;
  }

  /** The dye colours every shulker box comes in; a bulk rewrite needs each one named explicitly. */
  private static final List<String> DYE_COLOURS = List.of(
      "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
      "cyan", "purple", "blue", "brown", "green", "red", "black");

  /**
   * Expands the protected set for callers that can only match exact ids. A whole-chunk rewrite is handed a
   * set and compares block ids against it literally, so the suffix rule {@link #isProtected} applies has
   * to be spelled out here or a chunk rewrite would quietly convert every coloured shulker box.
   */
  private static Set<String> expand(Set<String> values) {
    Set<String> expanded = new LinkedHashSet<>(values);
    if (values.contains("shulker_box")) {
      for (String colour : DYE_COLOURS) {
        expanded.add(colour + "_shulker_box");
      }
    }
    return Set.copyOf(expanded);
  }

  /** Merges extra ids into this guard (a challenge with its own additions). */
  public BlockGuard with(Collection<String> extra) {
    if (extra == null || extra.isEmpty()) {
      return this;
    }
    Set<String> merged = new LinkedHashSet<>(protectedValues);
    for (String value : extra) {
      if (value != null && !value.isBlank()) {
        merged.add(value.trim().toLowerCase(Locale.ROOT));
      }
    }
    return new BlockGuard(merged);
  }
}
