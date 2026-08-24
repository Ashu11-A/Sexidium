package com.sexidium.core.world;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * The identity of one persistent experience world. One spelling, everywhere.
 *
 * <p>Before this type there were four. The launcher named a world by its owner
 * ({@code ashu11a/death_resets_ab12}), a reset handed back a namespaced form
 * ({@code experiences/death_resets_ab12_r1}), the folder on disk was the bare key, and Bukkit
 * reported a flattened label ({@code experiences_death_resets_ab12}). Each of the four was written
 * into {@code world_placements} verbatim by whichever caller happened to be holding it, so one world
 * could own several rows on several nodes while the boot-time folder scan matched none of them — and
 * the normaliser that was supposed to reconcile them, {@code WorldNaming.experienceKeyOf}, was not
 * idempotent: {@code experiences/ashu/x} became {@code ashu/x} and {@code ashu/x} became {@code x}.
 * Which answer you got depended on which spelling you happened to start with.</p>
 *
 * <p>So: a key is a base plus a generation, it is constructed once at creation time, and every other
 * form is DERIVED from it. Nothing parses a world name into an identity except {@link #parse} (strict,
 * for values that came from our own database) and {@link #fromRuntime} (tolerant, and only for labels
 * handed to us by the platform).</p>
 *
 * @param base       the generation-free identity, {@code <slug>_<8 hex id>} — never namespaced, never
 *                   owner-scoped, never containing a separator
 * @param generation 0 for the original world, {@code n} for the {@code _r<n>} regeneration
 */
public record WorldKey(String base, int generation) implements Comparable<WorldKey> {

  /** The marker a regeneration appends. Kept here so only one class knows the spelling. */
  private static final String GENERATION_MARKER = "_r";

  public WorldKey {
    if (base == null || base.isBlank()) {
      throw new IllegalArgumentException("A world key needs a base.");
    }
    // A base is ONE segment. Rejecting separators here is what makes every rendering below total:
    // a key can never smuggle a path, a namespace or a parent traversal into a folder resolution.
    if (base.indexOf('/') >= 0 || base.indexOf('\\') >= 0 || base.indexOf(':') >= 0) {
      throw new IllegalArgumentException(
          "A world key base is a single segment and may not contain '/', '\\' or ':': '" + base + "'");
    }
    if (base.contains("..")) {
      throw new IllegalArgumentException("A world key base may not contain '..': '" + base + "'");
    }
    if (generation < 0) {
      throw new IllegalArgumentException("A world generation is never negative: " + generation);
    }
    base = base.toLowerCase(Locale.ROOT);
  }

  /**
   * The key a brand-new experience is created under: its sanitised display name plus its id.
   *
   * <p><b>No owner segment.</b> The old {@code <nick>/<map>_<id>} shape was documented as producing an
   * {@code <nick>/} folder level that the world layer then silently dropped, which is where two of the
   * four spellings came from. The trailing 8-hex id is what makes the key unique; the nick never was.</p>
   */
  public static WorldKey of(String slug, String experienceId) {
    if (experienceId == null || experienceId.isBlank()) {
      throw new IllegalArgumentException("A world key needs an experience id.");
    }
    String cleanSlug = WorldNaming.sanitizeSegment(slug, "map");
    return new WorldKey(cleanSlug + "_" + WorldNaming.sanitizeSegment(experienceId, "world"), 0);
  }

  /**
   * Parses a value this system wrote: exactly {@code <base>} or {@code <base>_r<n>}.
   *
   * <p>Strict on purpose. Everything that reaches here came out of {@code experiences.world_key},
   * {@code world_placements.world_key} or {@code experience_players.world_key} — all three written by
   * {@link #key()} — so anything else is corruption and should say so rather than be quietly coerced
   * into a key that addresses a different world.</p>
   */
  public static WorldKey parse(String canonical) {
    if (canonical == null || canonical.isBlank()) {
      throw new IllegalArgumentException("A world key cannot be parsed from a blank string.");
    }
    String trimmed = canonical.trim();
    int marker = generationMarkerIndex(trimmed);
    if (marker < 0) {
      return new WorldKey(trimmed, 0);
    }
    return new WorldKey(
        trimmed.substring(0, marker),
        Integer.parseInt(trimmed.substring(marker + GENERATION_MARKER.length())));
  }

  /** {@link #parse} that answers empty instead of throwing, for values of uncertain provenance. */
  public static Optional<WorldKey> tryParse(String canonical) {
    try {
      return Optional.of(parse(canonical));
    } catch (IllegalArgumentException notAKey) {
      return Optional.empty();
    }
  }

  /**
   * Recovers a key from ANY historical spelling. For platform labels ONLY.
   *
   * <p>{@code WorldAdapter.name()} reports whatever the server calls a world — on Paper that is the
   * flattened {@code experiences_death_resets_ab12}, never the canonical name — so a key has to be
   * recoverable from it. That is the whole legitimate use. Anything that has a key already must pass
   * the key, not a rendering of it: this method is tolerant, and tolerance is what let four spellings
   * co-exist in the first place.</p>
   *
   * <p>Returns empty rather than guessing when the name is not an experience world at all.</p>
   */
  public static Optional<WorldKey> fromRuntime(String anySpelling) {
    return fromRuntime(anySpelling, WorldNaming.DEFAULT_EXPERIENCES_NAMESPACE);
  }

  /** {@link #fromRuntime(String)} against a configured namespace. */
  public static Optional<WorldKey> fromRuntime(String anySpelling, String namespace) {
    if (anySpelling == null || anySpelling.isBlank()) {
      return Optional.empty();
    }
    String candidate = WorldNaming.experienceKeyOf(anySpelling, namespace);
    if (candidate == null || candidate.isBlank()) {
      return Optional.empty();
    }
    // The flattened label ("experiences_death_resets_ab12") carries no separator at all, so the
    // namespace has to come off by prefix. Done here rather than in experienceKeyOf because it is
    // exactly the ambiguity that must not leak into the strict parser.
    String flatPrefix = namespace + "_";
    if (candidate.startsWith(flatPrefix)) {
      candidate = candidate.substring(flatPrefix.length());
    }
    // A linked dimension is not a world of its own: it is opened by, and travels with, its overworld.
    for (String suffix : new String[] {WorldNaming.NETHER_SUFFIX, WorldNaming.END_SUFFIX}) {
      if (candidate.endsWith(suffix)) {
        candidate = candidate.substring(0, candidate.length() - suffix.length());
      }
    }
    return tryParse(candidate);
  }

  /** The canonical key: the placement key, the database value, the folder name. */
  public String key() {
    return generation <= 0 ? base : base + GENERATION_MARKER + generation;
  }

  /** The canonical runtime name a {@code WorldHandle} reports, under the default namespace. */
  public String runtimeName() {
    return runtimeName(WorldNaming.DEFAULT_EXPERIENCES_NAMESPACE);
  }

  public String runtimeName(String namespace) {
    return namespace + "/" + key();
  }

  /** The dimension id form, {@code experiences:<key>}. */
  public String dimensionKey() {
    return dimensionKey(WorldNaming.DEFAULT_EXPERIENCES_NAMESPACE);
  }

  public String dimensionKey(String namespace) {
    return namespace + ":" + key();
  }

  /** The flattened label Bukkit reports for this world, {@code experiences_<key>}. */
  public String flatLabel() {
    return flatLabel(WorldNaming.DEFAULT_EXPERIENCES_NAMESPACE);
  }

  public String flatLabel(String namespace) {
    return namespace + "_" + key();
  }

  /** This world's folder under an experiences root. */
  public Path folderIn(Path experiencesRoot) {
    return experiencesRoot.resolve(key());
  }

  /** The key the next regeneration of this run takes. */
  public WorldKey nextGeneration() {
    return new WorldKey(base, generation + 1);
  }

  /** The generation-free identity, i.e. generation 0 of this run. */
  public WorldKey origin() {
    return generation == 0 ? this : new WorldKey(base, 0);
  }

  /** Whether two keys are generations of the SAME experience — the identity a reset must preserve. */
  public boolean sameRun(WorldKey other) {
    return other != null && base.equals(other.base);
  }

  @Override
  public int compareTo(WorldKey other) {
    int byBase = base.compareTo(other.base);
    return byBase != 0 ? byBase : Integer.compare(generation, other.generation);
  }

  @Override
  public String toString() {
    return key();
  }

  /**
   * Index of a trailing {@code _r<digits>}, or -1.
   *
   * <p>Unambiguous because a key always ends in an 8-character hexadecimal id, and hexadecimal
   * contains no {@code r} — so a trailing {@code _r<digits>} can only ever be a marker we wrote. A map
   * literally named {@code my_map_r4} keeps working because the id is appended after it.</p>
   */
  private static int generationMarkerIndex(String key) {
    int marker = key.lastIndexOf(GENERATION_MARKER);
    if (marker < 0 || marker + GENERATION_MARKER.length() >= key.length()) {
      return -1;
    }
    for (int index = marker + GENERATION_MARKER.length(); index < key.length(); index++) {
      if (!Character.isDigit(key.charAt(index))) {
        return -1;
      }
    }
    return marker;
  }
}
