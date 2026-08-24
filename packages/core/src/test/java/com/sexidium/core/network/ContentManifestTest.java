package com.sexidium.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentManifestTest {

  @Test
  @DisplayName("the digest is order-independent and stable across builds")
  void contentDigestIsOrderIndependentAndStable() {
    ContentManifest one = new ContentManifest(List.of("c:doubledrops", "m:tntwar", "c:cleave"));
    ContentManifest other = new ContentManifest(List.of("m:tntwar", "c:cleave", "c:doubledrops"));
    assertEquals(one.digest(), other.digest());
    // 16 hex chars: short enough for a log line and a VARCHAR key, wide enough that two different
    // catalogs colliding is not something anybody will see.
    assertEquals(16, one.digest().length());
    assertTrue(one.digest().matches("[0-9a-f]{16}"));
    assertNotEquals(one.digest(), new ContentManifest(List.of("c:doubledrops")).digest());
  }

  @Test
  @DisplayName("encode and decode round-trip, and decode is tolerant of anything it does not like")
  void encodingRoundTrips() {
    ContentManifest manifest = new ContentManifest(List.of("m:tntwar", "c:cleave"));
    assertEquals("c:cleave,m:tntwar", manifest.encoded());
    assertEquals(Set.of("c:cleave", "m:tntwar"), ContentManifest.decode(manifest.encoded()));
    // Unknown/blank entries are dropped rather than failing the read: this is the same convention
    // `capabilities` already uses, and it is what lets an older node read a newer node's row.
    assertEquals(Set.of("c:cleave"), ContentManifest.decode(" c:cleave , ,"));
    assertEquals(Set.of(), ContentManifest.decode(null));
    assertEquals(Set.of(), ContentManifest.decode(""));
  }

  @Test
  @DisplayName("covers() names what is missing, and an empty requirement constrains nobody")
  void coversAndMissing() {
    ContentManifest manifest = new ContentManifest(List.of("c:cleave", "m:tntwar"));
    assertTrue(manifest.covers(List.of("c:cleave")));
    assertTrue(manifest.covers(List.of()));
    // A world with no recorded content constrains nobody; refusing it would strand worlds that have
    // nothing wrong with them.
    assertTrue(manifest.covers(null));
    assertFalse(manifest.covers(List.of("c:cleave", "c:lookmultiplies")));
    assertEquals(List.of("c:lookmultiplies"),
        manifest.missing(List.of("c:cleave", "c:lookmultiplies")));
  }

  @Test
  @DisplayName("a d:<digest> requirement is satisfied only by a node with exactly that content")
  void digestRequirementPinsIdenticalContent() {
    // Chaos worlds store no fixed challenge list -- they reshuffle random twists from the WHOLE
    // catalog at runtime -- so m:chaos alone under-constrains them. They demand an identical
    // content surface instead, which keeps them put during a skew window at no cost.
    ContentManifest manifest = new ContentManifest(List.of("c:cleave"));
    assertTrue(manifest.covers(List.of(ContentManifest.DIGEST_PREFIX + manifest.digest())));
    assertFalse(manifest.covers(List.of(ContentManifest.DIGEST_PREFIX + "0000000000000000")));
  }

  @Test
  @DisplayName("the local manifest carries every catalog challenge, prefixed and lowercase")
  void localManifestCoversTheCatalog() {
    ContentManifest manifest = ContentManifest.local(null);
    for (var entry : com.sexidium.core.game.experience.ChallengeCatalog.available()) {
      assertTrue(manifest.covers(List.of("c:" + entry.id())),
          "the local manifest must publish c:" + entry.id());
    }
    assertEquals(com.sexidium.core.game.experience.ChallengeCatalog.available().size(),
        manifest.codes().size());
  }

  @Test
  @DisplayName("a peer's raw column can be checked without building a manifest first")
  void peerCodesAreCheckedDirectly() {
    assertTrue(ContentManifest.covers("c:cleave,m:tntwar", List.of("c:cleave")));
    assertFalse(ContentManifest.covers("c:cleave", List.of("m:tntwar")));
    // A node that published nothing (an older build) covers nothing but an empty requirement.
    assertFalse(ContentManifest.covers(null, List.of("c:cleave")));
    assertTrue(ContentManifest.covers(null, List.of()));
  }
}
