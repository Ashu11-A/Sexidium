package com.sexidium.core.game.experience.challenges;

import com.sexidium.core.i18n.MessageKey;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The bosses <b>Death Resets</b> asks a world to get through, in the order it asks for them.
 *
 * <p>Declaration order IS the route — easiest first, and the order the checklist is drawn in. It is a
 * suggested route rather than a gate: a kill counts the moment the boss dies, whichever order the
 * table gets to it in. Refusing to tick a boss somebody actually beat would be punishing them for
 * succeeding, and the ladder is here to tell a run how far it has got, not to referee it.</p>
 *
 * <h2>Matching a death to a rung</h2>
 * The only signal there is, is {@link com.sexidium.core.game.GameEvents.EntityDeathGameEvent}, whose
 * {@code entityType} is whatever the platform calls the entity — {@code ELDER_GUARDIAN} from Paper's
 * {@code EntityType#name}, possibly {@code minecraft:elder_guardian} from somewhere else. So the match
 * is against a normalized form, and it is EXACT: {@code WITHER_SKELETON} and {@code WITHER_SKULL} are
 * not the Wither, and {@code ENDERMAN} is not the Ender Dragon.
 *
 * <h2>Two ids, deliberately spelled differently</h2>
 * {@link #stateKey()} names a column in the experience's saved state and uses this codebase's dotted
 * namespacing. {@link #rowKey()} names a row on a HUD surface, and a driver embeds that name in a
 * placeholder — {@code [string:sexidium_deathresets_boss_warden]} — where a dot is not a character to
 * gamble another plugin's parser on. Same rung, two names, neither derived from the other by accident.
 */
public enum BossLadder {
  ELDER_GUARDIAN("elder_guardian", MessageKey.EXPERIENCE_DEATHRESETS_BOSS_ELDER_GUARDIAN),
  WARDEN("warden", MessageKey.EXPERIENCE_DEATHRESETS_BOSS_WARDEN),
  WITHER("wither", MessageKey.EXPERIENCE_DEATHRESETS_BOSS_WITHER),
  ENDER_DRAGON("ender_dragon", MessageKey.EXPERIENCE_DEATHRESETS_BOSS_ENDER_DRAGON);

  /** The ladder, in the order a run is asked to climb it. */
  public static final List<BossLadder> ORDER = List.of(values());

  private final String id;
  private final MessageKey displayName;

  BossLadder(String id, MessageKey displayName) {
    this.id = id;
    this.displayName = displayName;
  }

  /**
   * The rung a dead entity is, if it is one of ours.
   *
   * <p>Called for EVERY entity death on the server, so it is a lower-case string compare and nothing
   * more — no parsing, no allocation on the overwhelmingly common "that was a zombie" answer.</p>
   */
  public static Optional<BossLadder> match(String entityType) {
    String normalized = normalize(entityType);
    if (normalized == null) {
      return Optional.empty();
    }
    for (BossLadder boss : ORDER) {
      if (boss.id.equals(normalized)) {
        return Optional.of(boss);
      }
    }
    return Optional.empty();
  }

  /** How many rungs there are — what the checklist counts up to. */
  public static int total() {
    return ORDER.size();
  }

  /** The key this rung's kill is recorded under in the experience's shared state. */
  public String stateKey() {
    return "boss." + id;
  }

  /**
   * Where the wall-clock instant of the kill is recorded — epoch millis.
   *
   * <p>A separate key rather than a richer value under {@link #stateKey()}, because the flag is what
   * every read on the hot path wants and the state is a flat {@code String -> String} map: encoding a
   * record into one value would mean parsing it once per row per second to answer "is this ticked".</p>
   */
  public String stateKeyAt() {
    return stateKey() + ".at";
  }

  /**
   * Where the run's played time AT the kill is recorded, in seconds.
   *
   * <p>Not derivable from {@link #stateKeyAt()}. Played time only accrues while somebody is inside the
   * experience, so the wall clock between two kills and the play time between them are different
   * numbers whenever the world sat empty — which, for a world that lives on disk between visits, is
   * most of the time.</p>
   */
  public String stateKeyPlayed() {
    return stateKey() + ".played";
  }

  /** Looks a rung up by its id, for a command naming one. */
  public static Optional<BossLadder> byId(String value) {
    String normalized = normalize(value);
    if (normalized == null) {
      return Optional.empty();
    }
    for (BossLadder boss : ORDER) {
      if (boss.id.equals(normalized) || boss.id.replace("_", "").equals(normalized.replace("_", ""))
          || boss.id.replace("_", "-").equals(normalized)) {
        return Optional.of(boss);
      }
    }
    return Optional.empty();
  }

  /** The id a command spells this rung as. */
  public String id() {
    return id;
  }

  /** The key of this rung's row on the readout surface. See the class doc for why it is not the above. */
  public String rowKey() {
    return "boss_" + id;
  }

  /** This boss's name, in the reader's own language. */
  public MessageKey displayName() {
    return displayName;
  }

  /**
   * Strips a namespace and lower-cases, so {@code minecraft:ender_dragon}, {@code ENDER_DRAGON} and
   * {@code ender_dragon} are all the same entity. Returns null for anything that could not name one.
   */
  private static String normalize(String entityType) {
    if (entityType == null || entityType.isBlank()) {
      return null;
    }
    String trimmed = entityType.trim();
    int namespaceEnd = trimmed.lastIndexOf(':');
    if (namespaceEnd >= 0) {
      trimmed = trimmed.substring(namespaceEnd + 1);
    }
    return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
  }
}
