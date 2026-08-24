package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;

import java.util.Locale;

/**
 * The single source of truth for how Sexidium names, classifies and addresses its managed worlds.
 *
 * <p>Historically this logic was copy-pasted (with subtle divergences) across {@code
 * PaperWorldLeaseService}, {@code NeoForgeWorldLeaseService}, both lobby bootstraps, {@code
 * LobbyGuardPolicy}, {@code ExperienceStateStore} and {@code ExperienceGame}. Centralising it here lets
 * every layer build and parse the same names, so the lobby / experience / temp worlds are addressed
 * identically on every platform.</p>
 *
 * <h2>Identity model</h2>
 * Sexidium addresses a managed world by a <em>namespace</em> and a <em>key path</em>, mirroring a
 * Bukkit {@link org.bukkit.NamespacedKey NamespacedKey} (and a NeoForge {@code ResourceLocation}). On
 * Minecraft 26.1+ a keyed world lives on disk at {@code world/dimensions/<namespace>/<key-path>/}, so
 * the target layout falls out of the key directly:
 * <ul>
 *   <li>lobby      &rarr; {@code minecraft:lobby}      &rarr; {@code world/dimensions/minecraft/lobby}</li>
 *   <li>experience &rarr; {@code experiences:<nick>/<map>_<id>} &rarr; {@code world/dimensions/experiences/<nick>/<map>_<id>}</li>
 *   <li>temp       &rarr; {@code sexidium_temp:<short>} &rarr; {@code world/dimensions/sexidium_temp/<short>}</li>
 * </ul>
 *
 * <p>The <em>canonical runtime name</em> ({@code <namespace>/<key-path>}, with the {@code minecraft}
 * namespace elided to just the lobby name) is the stable string the rest of the core matches on — it is
 * what a managed {@link com.sexidium.core.platform.WorldAdapter#name() WorldAdapter.name()} reports,
 * regardless of how the platform internally labels the world.</p>
 */
public final class WorldNaming {
  public static final String DEFAULT_WORLDS_ROOT = "worlds";
  public static final String DEFAULT_LOBBY_NAME = "lobby";
  public static final String DEFAULT_LOBBY_NAMESPACE = "minecraft";
  public static final String DEFAULT_EXPERIENCES_NAMESPACE = "experiences";
  public static final String DEFAULT_TEMP_NAMESPACE = "sexidium_temp";
  public static final String DEFAULT_TEMP_PREFIX = "sexidium_temp_";

  private final ConfigurationAdapter configuration;

  public WorldNaming(ConfigurationAdapter configuration) {
    this.configuration = configuration;
  }

  // ----- configured identifiers ----------------------------------------------------------------

  /** Logical lobby world name (the {@code minecraft:<name>} key path), e.g. {@code "lobby"}. */
  public String lobbyName() {
    return sanitizeSegment(configuration.getString("worlds.lobby.name", DEFAULT_LOBBY_NAME), DEFAULT_LOBBY_NAME);
  }

  /** Namespace the lobby world lives under (default {@code minecraft}, so it sits beside the overworld). */
  public String lobbyNamespace() {
    return sanitizeSegment(configuration.getString("worlds.lobby.namespace", DEFAULT_LOBBY_NAMESPACE), DEFAULT_LOBBY_NAMESPACE);
  }

  /** Namespace persistent, player-owned experiences live under (default {@code experiences}). */
  public String experiencesNamespace() {
    // Accept the legacy worlds.experiences.subdir as a source for backwards compatibility, but default
    // to the target "experiences" namespace so the on-disk layout is world/dimensions/experiences/...
    String configured = configuration.getString("worlds.experiences.namespace", null);
    if (configured == null || configured.isBlank()) {
      configured = configuration.getString("worlds.experiences.subdir", DEFAULT_EXPERIENCES_NAMESPACE);
    }
    return sanitizeSegment(configured, DEFAULT_EXPERIENCES_NAMESPACE);
  }

  /** Namespace disposable game (minigame/quick-play) worlds live under. */
  public String tempNamespace() {
    return sanitizeSegment(configuration.getString("worlds.temp.namespace", DEFAULT_TEMP_NAMESPACE), DEFAULT_TEMP_NAMESPACE);
  }

  /** Prefix every disposable temp world's key carries, so a stale-world sweep can recognise them. */
  public String tempPrefix() {
    String raw = configuration.getString("worlds.temp.name-prefix", DEFAULT_TEMP_PREFIX);
    if (raw == null || raw.isBlank()) {
      raw = configuration.getString("temporary-worlds.name-prefix", DEFAULT_TEMP_PREFIX);
    }
    return raw == null || raw.isBlank() ? DEFAULT_TEMP_PREFIX : raw.trim().replace('/', '_').replace('\\', '_');
  }

  /**
   * Legacy top-level worlds root folder name (default {@code worlds}). Retained for the NeoForge
   * folder-based backend and tooling; the Paper dimension layout no longer uses it.
   */
  public String worldsRoot() {
    String raw = configuration.getString("worlds.root", DEFAULT_WORLDS_ROOT);
    if (raw == null || raw.isBlank()) {
      return DEFAULT_WORLDS_ROOT;
    }
    String[] parts = raw.trim().split("[\\\\/]+");
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (part.isBlank()) {
        continue;
      }
      if (builder.length() > 0) {
        builder.append('/');
      }
      builder.append(part);
    }
    return builder.length() == 0 ? DEFAULT_WORLDS_ROOT : builder.toString();
  }

  // ----- canonical runtime names ---------------------------------------------------------------

  /** Canonical runtime name of the lobby world (its bare name; the {@code minecraft} namespace is implicit). */
  public String lobbyRuntimeName() {
    return lobbyName();
  }

  /** Canonical runtime name of an experience world from its stable key ({@code <nick>/<map>_<id>}). */
  public String experienceRuntimeName(String key) {
    return experiencesNamespace() + "/" + normalizeKeyPath(key);
  }

  /** Canonical runtime name of a disposable temp world from its short key. */
  public String tempRuntimeName(String shortKey) {
    return tempNamespace() + "/" + shortKey;
  }

  /** Generates a fresh, unique short key for a disposable temp world. */
  public String nextTempShortKey(long timestampMillis, int sequence) {
    return tempPrefix() + Long.toUnsignedString(timestampMillis, 36) + "_" + sequence;
  }

  // ----- parsing / classification --------------------------------------------------------------

  /**
   * Recovers the stable experience key from any runtime form of the name: the canonical
   * {@code experiences/<key>}, a slash path {@code worlds/experience/<key>}, or a namespaced dimension
   * id {@code experiences:<key>} / {@code sexidium:experiences/<key>}.
   *
   * <p>Package-private, and it is meant to stay that way: {@link WorldKey#fromRuntime} is the only
   * caller, and going through it is what makes the result idempotent. This function on its own is not
   * — feed it its own output twice and the answer can change — which is precisely how one experience
   * ended up with several placement rows under several spellings.</p>
   */
  String experienceKeyOf(String runtimeName) {
    return experienceKeyOf(runtimeName, experiencesNamespace());
  }

  /** As {@link #experienceKeyOf(String)} but against a caller-supplied namespace/subdir marker. */
  static String experienceKeyOf(String runtimeName, String namespace) {
    if (runtimeName == null) {
      return null;
    }
    String normalized = runtimeName.replace('\\', '/');
    // Strip a leading "<prefix>:" dimension qualifier (a namespace never contains a slash). When the
    // prefix IS the experiences namespace ("experiences:<key>") the remainder is exactly the key.
    int colon = normalized.indexOf(':');
    if (colon >= 0 && normalized.lastIndexOf('/', colon) < 0) {
      String prefix = normalized.substring(0, colon);
      String rest = normalized.substring(colon + 1);
      if (namespace != null && prefix.equals(namespace)) {
        return rest;
      }
      normalized = rest;
    }
    if (namespace != null && !namespace.isBlank()) {
      String marker = "/" + namespace + "/";
      int index = normalized.indexOf(marker);
      if (index >= 0) {
        return normalized.substring(index + marker.length());
      }
      if (normalized.startsWith(namespace + "/")) {
        return normalized.substring(namespace.length() + 1);
      }
    }
    // The owner-segment fallback that used to live here (`return lastSegment(normalized)`) is DELETED.
    // For "ashu/death_resets_ab12" it silently dropped the owner and answered "death_resets_ab12",
    // which is a DIFFERENT key from the one the same function returns for "experiences/ashu/..." —
    // the non-idempotence that let one world hold several placement rows. A name that carries a path
    // but no namespace marker is not an experience runtime name, and saying so is the whole point.
    return normalized.indexOf('/') >= 0 ? null : normalized;
  }

  /** True when {@code runtimeName} (in any form) refers to the configured lobby world. */
  public boolean isLobby(String runtimeName) {
    if (runtimeName == null || runtimeName.isBlank()) {
      return false;
    }
    String stripped = stripNamespace(runtimeName);
    String lobby = lobbyName();
    return stripped.equalsIgnoreCase(lobby)
        || lastSegment(stripped).equalsIgnoreCase(lobby)
        || stripped.equalsIgnoreCase(lobbyNamespace() + "/" + lobby);
  }

  /** True when {@code runtimeName} (in any form) refers to a persistent experience world. */
  public boolean isExperience(String runtimeName) {
    if (runtimeName == null || runtimeName.isBlank()) {
      return false;
    }
    String namespace = experiencesNamespace();
    // Dimension id form "experiences:<key>".
    if (runtimeName.replace('\\', '/').startsWith(namespace + ":")) {
      return true;
    }
    String normalized = stripNamespace(runtimeName);
    return normalized.startsWith(namespace + "/")
        || normalized.contains("/" + namespace + "/")
        // Bukkit's FLATTENED label, "experiences_<key>". It is the only form a live world ever
        // reports, and leaving it out here meant every caller that asks "is this an experience?"
        // about a running world answered no -- including the guard that stops reacquireByName
        // CREATING one under a name a player's save answers to.
        || normalized.startsWith(namespace + "_")
        // Legacy slash layout: worlds/experience/<key>.
        || normalized.contains("/experience/");
  }

  /** True when {@code runtimeName} (in any form) refers to a disposable temp world. */
  public boolean isTemp(String runtimeName) {
    if (runtimeName == null || runtimeName.isBlank()) {
      return false;
    }
    String namespace = tempNamespace();
    if (runtimeName.replace('\\', '/').startsWith(namespace + ":")) {
      return true;
    }
    String normalized = stripNamespace(runtimeName);
    return normalized.startsWith(namespace + "/")
        || normalized.contains("/" + namespace + "/")
        || lastSegment(normalized).startsWith(tempPrefix());
  }

  // ----- static helpers ------------------------------------------------------------------------

  /** The trailing path segment of a (possibly namespaced, possibly slash-pathed) name. */
  public static String lastSegment(String name) {
    if (name == null) {
      return null;
    }
    String stripped = stripNamespace(name);
    int separator = Math.max(stripped.lastIndexOf('/'), stripped.lastIndexOf('\\'));
    return separator >= 0 ? stripped.substring(separator + 1) : stripped;
  }

  /** Removes a leading {@code namespace:} qualifier (when the colon precedes the first slash). */
  public static String stripNamespace(String name) {
    if (name == null) {
      return "";
    }
    String normalized = name.replace('\\', '/');
    int colon = normalized.indexOf(':');
    if (colon >= 0 && normalized.lastIndexOf('/', colon) < 0) {
      return normalized.substring(colon + 1);
    }
    return normalized;
  }

  /**
   * True when two runtime names refer to the same world. A managed world's <em>canonical</em> name is a
   * slash path ({@code <namespace>/<key>}) while the platform's live label flattens it to
   * {@code <namespace>_<key>} (e.g. a keyed Paper world named from {@code sexidium_temp:<short>} reports
   * {@code sexidium_temp_<short>}). Comparing those with {@code equals} fails and ejects a player from
   * their own match world the instant they are teleported into it — so we normalise the separators
   * ({@code / \ :} &rarr; {@code _}) before comparing, then fall back to a trailing-segment match.
   */
  public static boolean sameWorld(String first, String second) {
    if (first == null || second == null) {
      return false;
    }
    if (flattenSeparators(first).equalsIgnoreCase(flattenSeparators(second))) {
      return true;
    }
    String firstSegment = lastSegment(first);
    return firstSegment != null && firstSegment.equalsIgnoreCase(lastSegment(second));
  }

  /** Collapses every path/namespace separator to {@code _} so canonical and flattened names compare equal. */
  private static String flattenSeparators(String name) {
    return name.replace('\\', '/').replace('/', '_').replace(':', '_');
  }

  /**
   * Whether {@code liveWorldName} is any dimension of the experience whose Overworld is
   * {@code overworldName} — the Overworld itself, or its {@code _nether} / {@code _end} sibling.
   *
   * <p>An experience is three worlds, so "is this player still inside it" is never a name equality.
   * Lives here rather than in any one caller because three of them need the same answer: the persistence
   * layer deciding whether a saved position belongs to this experience, and the reset deciding whether
   * everybody has actually left the world it is about to delete.</p>
   */
  public static boolean belongsToExperience(String liveWorldName, String overworldName) {
    if (liveWorldName == null || liveWorldName.isBlank()
        || overworldName == null || overworldName.isBlank()) {
      return false;
    }
    if (sameWorld(overworldName, liveWorldName)) {
      return true;
    }
    String flatBase = flattenSeparators(overworldName);
    String flatLive = flattenSeparators(liveWorldName);
    return flatLive.equalsIgnoreCase(flatBase + NETHER_SUFFIX)
        || flatLive.equalsIgnoreCase(flatBase + END_SUFFIX);
  }

  /** The dimension suffixes an experience's Overworld key grows for its linked worlds. */
  public static final String NETHER_SUFFIX = "_nether";
  public static final String END_SUFFIX = "_end";

  // ----- experience world generations -----------------------------------------------------------
  // A regenerated experience world cannot reuse its own name: the old world is still loaded, and still
  // has players standing in it, while the new one is being prepared. So each regeneration appends a
  // generation marker. This is also what makes the hardcore hearts work — EntryPolicy#prepareArrival
  // only re-sends the login packet when the world NAME changes.

  private static final String GENERATION_MARKER = "_r";

  /**
   * The experience key with any regeneration suffix removed:
   * {@code ashu/diamond_hunt_ab12cd34_r3} &rarr; {@code ashu/diamond_hunt_ab12cd34}.
   *
   * <p>Unambiguous because an experience key always ends in the experience's 8-character hexadecimal id,
   * and hexadecimal contains no {@code r} — so a trailing {@code _r<digits>} can only ever be a marker
   * this class wrote.</p>
   */
  public static String baseExperienceKey(String key) {
    int marker = generationMarkerIndex(key);
    return marker < 0 ? key : key.substring(0, marker);
  }

  /** The generation encoded in a key, or {@code 0} for a world that has never been regenerated. */
  public static int generationOf(String key) {
    int marker = generationMarkerIndex(key);
    if (marker < 0) {
      return 0;
    }
    try {
      return Integer.parseInt(key.substring(marker + GENERATION_MARKER.length()));
    } catch (NumberFormatException exception) {
      return 0;
    }
  }

  /** {@code key}'s base plus a generation suffix; a generation of 0 or less yields the bare base. */
  public static String experienceKeyForGeneration(String key, int generation) {
    String base = baseExperienceKey(key);
    return generation <= 0 ? base : base + GENERATION_MARKER + generation;
  }

  /** Index of a trailing {@code _r<digits>}, or -1 when the key carries none. */
  private static int generationMarkerIndex(String key) {
    if (key == null) {
      return -1;
    }
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

  /** Normalises a key path: trims, swaps backslashes, drops a leading slash. */
  private static String normalizeKeyPath(String key) {
    if (key == null) {
      return "";
    }
    String normalized = key.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    return normalized;
  }

  /**
   * Reduces a raw value to one safe path/key segment: lowercases, keeps {@code [a-z0-9._-]}, collapses
   * other runs to a single {@code _}. Valid as a Bukkit {@code NamespacedKey} segment and a NeoForge
   * dimension id alike. Falls back to {@code fallback} when nothing usable remains.
   */
  public static String sanitizeSegment(String raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    StringBuilder builder = new StringBuilder(raw.length());
    boolean lastUnderscore = false;
    for (int i = 0; i < raw.length(); i++) {
      char current = Character.toLowerCase(raw.charAt(i));
      boolean safe = (current >= 'a' && current <= 'z')
          || (current >= '0' && current <= '9') || current == '-' || current == '_' || current == '.';
      if (safe) {
        builder.append(current);
        lastUnderscore = false;
      } else if (!lastUnderscore) {
        builder.append('_');
        lastUnderscore = true;
      }
    }
    int start = 0;
    int end = builder.length();
    while (start < end && builder.charAt(start) == '_') {
      start++;
    }
    while (end > start && builder.charAt(end - 1) == '_') {
      end--;
    }
    String cleaned = builder.substring(start, end).toLowerCase(Locale.ROOT);
    return cleaned.isEmpty() ? fallback : cleaned;
  }
}
