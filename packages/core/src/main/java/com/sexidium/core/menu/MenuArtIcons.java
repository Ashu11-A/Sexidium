package com.sexidium.core.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.sexidium.core.menu.MenuArt.IconModel;

/**
 * The custom {@code item_model} side of {@link MenuArt}: the per-minigame / per-challenge icon tables,
 * the de-duplicated + sorted icon set the pack ships, and the id→model lookups. Package-private
 * implementation detail of {@link MenuArt}, which exposes the public {@code modeModel}/{@code
 * challengeModel}/{@code hasIcon}/{@code icons} facade and the {@code ICON_*} id constants.
 *
 * <p>The icon-id constants live on {@link MenuArt} (callers reference {@code MenuArt.ICON_*}); the
 * {@code HUB_ICON_IDS}/{@code ACTION_ICON_IDS}/{@code NAV_ICON_IDS} groupings below read those values so
 * the build set stays identical.</p>
 */
final class MenuArtIcons {
  // MODE_ICON_MODELS maps each modeId onto its source icon. MODE_ICON_IDS (on MenuArt) mirrors the
  // minigame registry; MenuArtCoverageTest fails the build if a registered mode is missing here.
  private static final Map<String, String> MODE_ICON_MODELS = mapOf(
      "race", "minigames/race",
      "gather", "minigames/gather",
      "tntwar", "minigames/tntwar",
      "combat", "minigames/combat",
      "fugitive", "minigames/fugitive");

  // CHALLENGE_ICON_MODELS maps each challengeId onto its source icon. CHALLENGE_ICON_IDS (on MenuArt)
  // mirrors ChallengeCatalog; the same coverage test guards against drift here.
  private static final Map<String, String> CHALLENGE_ICON_MODELS = mapOf(
      "doubledrops", "experiences/doubledrops",
      "randomizer", "experiences/randomizer",
      "sharedlife", "experiences/sharedlife",
      "sharedinventory", "experiences/sharedinventory",
      "xphealth", "experiences/xphealth",
      // No bespoke shrinking-achievements sprite in the cut sheet yet; keep the legacy generic icon.
      "shrinkingachievements", "gui_buttons/button_arrow_down_orange",
      "breakonebreakall", "experiences/breakonebreakall",
      "chunkbreak", "experiences/breakonebreakall",
      "blockdeleter", "experiences/blockdeleter",
      "randomchunks", "experiences/randomchunks",
      "walkingblocks", "experiences/walkingblocks",
      "chained", "experiences/chained",
      "cleave", "experiences/cleave",
      "growing", "experiences/growing",
      "jumpenchants", "experiences/jumpenchants",
      "mobduplication", "experiences/mobduplication",
      // Look Multiplies reuses the Mob Duplication sprite (both are "one becomes many"), the same way
      // chunkbreak reuses breakonebreakall — no bespoke texture is shipped for it.
      "lookmultiplies", "experiences/mobduplication",
      // Jump Multiplies is the same "one becomes many" family, so it reuses the same sprite rather than
      // shipping a bespoke texture.
      "jumpmultiplies", "experiences/mobduplication",
      // Random Layers reuses the Random Chunks sprite (both are "the world is random blocks") — no
      // bespoke texture shipped.
      "randomlayers", "experiences/randomchunks",
      // The Random-Item-Giver skyblock family reuses existing sprites (no bespoke textures shipped).
      "randomskyblock", "experiences/randomchunks",
      "randommob", "experiences/mobduplication",
      "randomdrops", "experiences/randomizer",
      "randomevents", "experiences/jumpenchants",
      "classicskyblock", "experiences/randomchunks",
      // Omni Chunk: the chunk sprite reads as "the same slot in every chunk" (no bespoke art shipped).
      "omnichunk", "experiences/randomchunks",
      // Layered Dimensions: a column of stacked strata, so the chunk-strata art is the closest fit.
      "layereddimensions", "experiences/randomchunks",
      // Death Resets: the world itself keeps being replaced, which is what the chunk-randomiser
      // sprite already reads as (no bespoke texture shipped).
      "deathresets", "experiences/randomchunks");

  // Greyscale "disabled" twin of each experience icon (shipped at experiences/<id>_disabled). The
  // experience builder shows it for an UN-selected twist (off), the colour icon for a selected one (on).
  // Derived 1:1 from CHALLENGE_ICON_MODELS so it can never drift: every experiences/<id> gains a
  // <id>_disabled, while shrinkingachievements (legacy gui_buttons icon, no bespoke sprite) is skipped.
  private static final Map<String, String> CHALLENGE_ICON_MODELS_DISABLED = challengeDisabledModels();

  private static final String[] HUB_ICON_IDS = {
      MenuArt.ICON_MINIGAMES, MenuArt.ICON_EXPERIENCE_CREATE, MenuArt.ICON_EXPERIENCE_MINE,
      MenuArt.ICON_BROWSE, MenuArt.ICON_LOBBY, MenuArt.ICON_FRIENDS, MenuArt.ICON_ADMIN};
  private static final String[] ACTION_ICON_IDS = {
      MenuArt.ICON_BACK, MenuArt.ICON_CREATE, MenuArt.ICON_ENTER, MenuArt.ICON_DELETE,
      MenuArt.ICON_RENAME, MenuArt.ICON_PUBLIC, MenuArt.ICON_PRIVATE, MenuArt.ICON_EDIT_CHALLENGES,
      MenuArt.ICON_QUICK_PLAY, MenuArt.ICON_CREATE_LOBBY, MenuArt.ICON_JOIN, MenuArt.ICON_LOBBY_ROW,
      MenuArt.ICON_INVITE, MenuArt.ICON_INVITES, MenuArt.ICON_ACCEPT, MenuArt.ICON_DECLINE,
      MenuArt.ICON_ADD_FRIEND, MenuArt.ICON_PLAY_TOGETHER, MenuArt.ICON_START, MenuArt.ICON_LEAVE,
      MenuArt.ICON_TEAMS, MenuArt.ICON_VISIBILITY, MenuArt.ICON_MIN_PLAYERS, MenuArt.ICON_EMPTY,
      MenuArt.ICON_NPC, MenuArt.ICON_NPC_CREATE, MenuArt.ICON_RELOAD};
  private static final String[] NAV_ICON_IDS = {MenuArt.ICON_NAV_MENU, MenuArt.ICON_NAV_EXPERIENCES};

  private static final List<IconModel> ICONS = buildIcons();
  private static final Set<String> ICON_IDS = buildIconIdSet();

  private MenuArtIcons() {
  }

  /** All custom icon models actually referenced by the menu, in pack-emit order. */
  static List<IconModel> icons() {
    return ICONS;
  }

  /** The custom {@code item_model} id for a minigame tile, or {@code null} when no bespoke icon ships. */
  static String modeModel(String modeId) {
    return iconModelOrNull(MODE_ICON_MODELS.get(normalizeArtId(modeId)));
  }

  /** The custom {@code item_model} id for an experience challenge tile, or {@code null} when none ships. */
  static String challengeModel(String challengeId) {
    return iconModelOrNull(CHALLENGE_ICON_MODELS.get(normalizeArtId(challengeId)));
  }

  /** The greyscale "disabled" {@code item_model} id for an experience challenge tile, or {@code null}. */
  static String challengeModelDisabled(String challengeId) {
    return iconModelOrNull(CHALLENGE_ICON_MODELS_DISABLED.get(normalizeArtId(challengeId)));
  }

  /** Whether a custom icon with this exact id ships an {@code item_model} in the pack. */
  static boolean hasIcon(String iconId) {
    return iconId != null && ICON_IDS.contains(iconId);
  }

  private static String iconModelOrNull(String iconId) {
    return iconId != null && ICON_IDS.contains(iconId) ? MenuArt.model(iconId) : null;
  }

  private static String normalizeArtId(String id) {
    return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
  }

  private static Map<String, String> challengeDisabledModels() {
    Map<String, String> map = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : CHALLENGE_ICON_MODELS.entrySet()) {
      if (entry.getValue().startsWith("experiences/")) {
        map.put(entry.getKey(), entry.getValue() + "_disabled");
      }
    }
    return Collections.unmodifiableMap(map);
  }

  // Every menu-referenced icon, de-duplicated (several semantic slots may share one source icon, e.g.
  // create / add_friend / npc_create all use system/plus_gold) AND sorted. The sort is load-bearing:
  // this order is the order SexidiumResourcePack writes the per-icon zip entries, and the pack SHA-1 must
  // be byte-stable across JVM restarts so clients cache it instead of re-downloading every join. A plain
  // insertion-ordered set fed from MODE/CHALLENGE_ICON_MODELS.values() would NOT be stable — those maps
  // could iterate in a per-JVM-salted order — so we emit a deterministic sorted-by-id sequence.
  private static List<IconModel> buildIcons() {
    Set<String> ids = new TreeSet<>();
    addAll(ids, HUB_ICON_IDS);
    ids.addAll(MODE_ICON_MODELS.values());
    ids.addAll(CHALLENGE_ICON_MODELS.values());
    ids.addAll(CHALLENGE_ICON_MODELS_DISABLED.values());
    addAll(ids, ACTION_ICON_IDS);
    addAll(ids, NAV_ICON_IDS);
    List<IconModel> icons = new ArrayList<>(ids.size());
    for (String id : ids) {
      icons.add(new IconModel(id));
    }
    return List.copyOf(icons);
  }

  private static Set<String> buildIconIdSet() {
    Set<String> ids = new TreeSet<>();
    for (IconModel icon : ICONS) {
      ids.add(icon.id());
    }
    return Set.copyOf(ids);
  }

  private static void addAll(Set<String> target, String[] values) {
    for (String value : values) {
      target.add(value);
    }
  }

  // Small ordered-map literal helper (keeps the mode/challenge tables readable above). Order-preserving
  // (not Map.copyOf) so any future .values()/entrySet() iteration stays deterministic across JVM runs.
  private static Map<String, String> mapOf(String... pairs) {
    Map<String, String> map = new LinkedHashMap<>();
    for (int index = 0; index < pairs.length; index += 2) {
      map.put(pairs[index], pairs[index + 1]);
    }
    return Collections.unmodifiableMap(map);
  }
}
