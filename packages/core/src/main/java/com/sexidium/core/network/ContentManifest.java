package com.sexidium.core.network;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * What content a node can actually run: every challenge and every game mode it has compiled in.
 *
 * <p>The failure this exists to prevent is silent and save-destroying.
 * {@link com.sexidium.core.game.experience.ChallengeCatalog#create} drops ids it does not know, so a
 * node one build behind answers "no" to <em>does this experience need a void world?</em> and
 * generates normal terrain over what should be an empty SkyBlock. A capability code makes that node
 * refuse the world instead, and say which node can run it.</p>
 *
 * <h2>Why a sorted list plus a digest, and not a bitset</h2>
 * <p>A bitset needs a globally agreed bit index per code, so the assignment becomes a second protocol
 * that itself has to be versioned — and it fails <em>silently</em>, because a shifted bit reads as a
 * different challenge. An explicit list is self-describing, reuses the proven
 * {@code network_nodes.capabilities} convention (comma-joined lowercase, unknown entries ignored),
 * and the digest answers "same content?" in one comparison for the common case. 34 codes is ~700
 * bytes written every five seconds on a handful of nodes: noise.</p>
 *
 * <p>Deliberately NOT folded into {@code capabilities}: {@link NodeRegistry#with} splits and
 * {@code contains}-checks that string on every placement decision, and it is what
 * {@code /sx admin net nodes} prints.</p>
 */
public final class ContentManifest {

  /** Prefix for a challenge id, e.g. {@code c:doubledrops}. */
  public static final String CHALLENGE_PREFIX = "c:";
  /** Prefix for a game mode id, e.g. {@code m:tntwar}. */
  public static final String MODE_PREFIX = "m:";
  /**
   * Prefix for the synthetic "identical content" requirement.
   *
   * <p>A Chaos world stores no fixed challenge list — it reshuffles random twists from the whole
   * catalog at runtime — so {@code m:chaos} alone under-constrains it. Such a world additionally
   * requires the peer's digest to equal ours, expressed as a code only this node's own digest can
   * satisfy. Conservative on purpose: a chaos world simply stays put during a skew window, which
   * costs nothing (it is per-player) and cannot degrade.</p>
   */
  public static final String DIGEST_PREFIX = "d:";

  private static final int DIGEST_HEX_CHARS = 16;

  private final NavigableSet<String> codes;
  private final String digest;

  /** Test/decode constructor. Blank entries are dropped; order does not matter. */
  public ContentManifest(Collection<String> codes) {
    NavigableSet<String> sorted = new TreeSet<>();
    if (codes != null) {
      for (String code : codes) {
        String normalized = normalize(code);
        if (!normalized.isEmpty()) {
          sorted.add(normalized);
        }
      }
    }
    this.codes = sorted;
    this.digest = digestOf(sorted);
  }

  /** This node's own content: everything in the catalog plus every registered mode. */
  public static ContentManifest local(com.sexidium.core.game.GameRegistry registry) {
    List<String> codes = new ArrayList<>();
    for (com.sexidium.core.game.experience.ChallengeCatalog.Entry entry
        : com.sexidium.core.game.experience.ChallengeCatalog.available()) {
      codes.add(CHALLENGE_PREFIX + entry.id());
    }
    if (registry != null) {
      for (String modeId : registry.modeIds()) {
        codes.add(MODE_PREFIX + modeId);
      }
    }
    return new ContentManifest(codes);
  }

  /** Sorted, so two nodes with the same content produce byte-identical output. */
  public List<String> codes() {
    return List.copyOf(codes);
  }

  /** First 16 hex chars of SHA-256 over the newline-joined sorted codes. */
  public String digest() {
    return digest;
  }

  /** Comma-joined codes, for {@code network_nodes.content_codes}. */
  public String encoded() {
    return String.join(",", codes);
  }

  /** Tolerant by design: unknown and blank entries are dropped, exactly like {@code capabilities}. */
  public static Set<String> decode(String encoded) {
    Set<String> decoded = new LinkedHashSet<>();
    if (encoded == null || encoded.isBlank()) {
      return decoded;
    }
    for (String part : encoded.split(",")) {
      String normalized = normalize(part);
      if (!normalized.isEmpty()) {
        decoded.add(normalized);
      }
    }
    return decoded;
  }

  /**
   * Whether this node implements everything {@code required} names.
   *
   * <p>An empty requirement is satisfied by anything — a world with no recorded content constrains
   * nobody, and refusing it would strand worlds nothing is wrong with.</p>
   */
  public boolean covers(Collection<String> required) {
    return missing(required).isEmpty();
  }

  /** What is missing, for the refusal message and the {@code SX-CONTENT refused …} log line. */
  public List<String> missing(Collection<String> required) {
    List<String> absent = new ArrayList<>();
    if (required == null) {
      return absent;
    }
    for (String code : required) {
      String normalized = normalize(code);
      if (normalized.isEmpty()) {
        continue;
      }
      if (normalized.startsWith(DIGEST_PREFIX)) {
        // Only our own digest satisfies a digest requirement; see DIGEST_PREFIX.
        if (!normalized.equals(DIGEST_PREFIX + digest)) {
          absent.add(normalized);
        }
        continue;
      }
      if (!codes.contains(normalized)) {
        absent.add(normalized);
      }
    }
    return absent;
  }

  /** Whether a peer's published {@code content_codes} covers a requirement. */
  public static boolean covers(String encodedPeerCodes, Collection<String> required) {
    return new ContentManifest(decode(encodedPeerCodes)).covers(required);
  }

  private static String digestOf(Collection<String> sortedCodes) {
    String joined = String.join("\n", sortedCodes);
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(joined.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(DIGEST_HEX_CHARS);
      for (int index = 0; hex.length() < DIGEST_HEX_CHARS && index < hash.length; index++) {
        hex.append(Character.forDigit((hash[index] >> 4) & 0xF, 16));
        hex.append(Character.forDigit(hash[index] & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException impossible) {
      // SHA-256 is mandated by the platform spec; there is no runtime without it.
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String normalize(String code) {
    return code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
  }

  @Override
  public String toString() {
    return "ContentManifest[" + codes.size() + " codes, digest=" + digest + "]";
  }
}
