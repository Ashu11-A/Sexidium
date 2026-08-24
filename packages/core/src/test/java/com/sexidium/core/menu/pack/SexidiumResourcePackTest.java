package com.sexidium.core.menu.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sexidium.core.menu.MenuArt;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

class SexidiumResourcePackTest {

  @Test
  void buildsAValidPackWithFontsModelsAndTextures() throws IOException {
    ResourcePackInfo info = SexidiumResourcePack.build();
    assertEquals(40, info.sha1Hex().length(), "SHA-1 hex is 40 chars");
    assertTrue(info.bytes().length > 0);

    Set<String> entries = new HashSet<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(info.bytes()))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.add(entry.getName());
      }
    }

    assertTrue(entries.contains("pack.mcmeta"), entries.toString());
    assertTrue(entries.contains("assets/sexidium/font/menu.json"));
    assertTrue(entries.contains("assets/sexidium/font/space.json"));
    // Every declared glyph + icon must ship its asset(s), so the menu never references missing art. A tiled
    // background ships one ≤256px row-strip texture per provider (not the whole 768px file); a non-tiled
    // glyph ships its single texture.
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      if (glyph.tiled()) {
        for (MenuArt.TileRow row : MenuArt.tileRows(glyph)) {
          assertTrue(entries.contains("assets/sexidium/textures/" + row.texturePath()),
              "missing strip texture " + row.texturePath() + " for glyph " + glyph.id());
        }
      } else {
        assertTrue(entries.contains("assets/sexidium/textures/" + glyph.texturePath()),
            "missing texture for glyph " + glyph.id());
      }
    }
    for (MenuArt.IconModel icon : MenuArt.icons()) {
      assertTrue(entries.contains("assets/sexidium/items/" + icon.id() + ".json"));
      assertTrue(entries.contains("assets/sexidium/models/item/" + icon.id() + ".json"));
      assertTrue(entries.contains("assets/sexidium/textures/" + icon.texturePath()));
    }
    // Every declared bitmap font (the medieval title/button caps) ships its font json + per-char textures.
    for (com.sexidium.core.menu.BackgroundCatalog.FontDef font
        : com.sexidium.core.menu.BackgroundCatalog.fontsAll()) {
      assertTrue(entries.contains("assets/sexidium/font/" + font.id() + ".json"),
          "missing font json for " + font.id());
      for (char c : font.chars().toCharArray()) {
        assertTrue(entries.contains("assets/sexidium/textures/" + font.texturePath(c)),
            "missing glyph texture " + font.texturePath(c));
      }
    }
  }

  @Test
  void shipsLangOverridesThatHideTheVanillaInventoryLabel() throws IOException {
    // The "Inventory" label is drawn after the chest title and cannot be covered by the title glyph;
    // the only fix is a per-locale resource-pack override blanking container.inventory. Must cover the
    // server's English + Brazilian-Portuguese audience at minimum, in the MINECRAFT namespace.
    ResourcePackInfo info = SexidiumResourcePack.build();
    Set<String> entries = entryNames(info.bytes());
    for (String code : new String[] {"en_us", "pt_br", "en_gb", "pt_pt"}) {
      assertTrue(entries.contains("assets/minecraft/lang/" + code + ".json"),
          "missing inventory-label override for locale " + code);
    }
    byte[] enUs = entryBytes(info.bytes(), "assets/minecraft/lang/en_us.json");
    assertTrue(new String(enUs, java.nio.charset.StandardCharsets.UTF_8).contains("\"container.inventory\""),
        "override must set the container.inventory key");
    assertTrue(new String(enUs, java.nio.charset.StandardCharsets.UTF_8).contains("\"\""),
        "override value must be the empty string (renders nothing)");
  }

  @Test
  void buildIsDeterministic() {
    // Same inputs → same bytes/hash, so the client cache key is stable across restarts.
    assertEquals(SexidiumResourcePack.build().sha1Hex(), SexidiumResourcePack.build().sha1Hex());
  }

  @Test
  void customLabelIsWrittenToPackMetadata() throws IOException {
    ResourcePackInfo info = SexidiumResourcePack.build("Ashu", null);

    String metadata = new String(entryBytes(info.bytes(), "pack.mcmeta"), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(metadata.contains("\"description\": \"Ashu menu art\""));
  }

  @Test
  void packDeclaresItsFormatTheWayModernMinecraftReadsIt() throws IOException {
    // The client decides whether our art is a pack it understands from these two keys alone. The pair
    // this used to emit (pack_format + supported_formats) is no longer the schema: supported_formats was
    // removed, and pack_format is only still read for packs declaring a format below 65. Emitting the old
    // shape does not fail loudly — the pack is applied anyway and the art quietly renders wrong — so the
    // shape is asserted here rather than left to be noticed in-game.
    String metadata = new String(
        entryBytes(SexidiumResourcePack.build().bytes(), "pack.mcmeta"), java.nio.charset.StandardCharsets.UTF_8);

    // 84 = pack_version.resource_major of the pinned Minecraft release (26.1.2). Asserted as a literal
    // rather than read back off the constant, so bumping the pin has to come here and be looked at.
    assertTrue(metadata.contains("\"min_format\": 84"), metadata);
    assertTrue(metadata.contains("\"max_format\": 84"), metadata);
    assertFalse(metadata.contains("supported_formats"), metadata);
    assertFalse(metadata.contains("pack_format"), metadata);
  }

  @Test
  void extraTexturePathsShipAsBareTexturesWithoutModelsAndNeverTwice() throws IOException {
    // A path advertised by texturePaths() that is NOT a referenced icon ships as a bare texture only
    // (no item def, no model). A path that overlaps a referenced icon is written once (by the icon loop),
    // not duplicated by the bare pass.
    String referenced = MenuArt.icons().get(0).texturePath();      // item/<section>/<name>.png
    String extra = "item/elo_ranks/rank_gem_gold_tier1.png";       // a bundled sprite with no model
    byte[] bytes = "EXTRA".getBytes();
    SexidiumResourcePack.TextureSource source = new SexidiumResourcePack.TextureSource() {
      @Override public byte[] texture(String path) { return bytes; }
      @Override public java.util.Collection<String> texturePaths() {
        return java.util.List.of(referenced, extra);
      }
    };
    ResourcePackInfo info = SexidiumResourcePack.build(source);
    Set<String> entries = entryNames(info.bytes());

    assertTrue(entries.contains("assets/sexidium/textures/" + extra), "extra ships as a bare texture");
    String extraId = extra.substring("item/".length(), extra.length() - ".png".length());
    assertFalse(entries.contains("assets/sexidium/items/" + extraId + ".json"),
        "a bare extra must NOT get an item definition");
    assertFalse(entries.contains("assets/sexidium/models/item/" + extraId + ".json"),
        "a bare extra must NOT get a model");
    assertEquals(1, countEntries(info.bytes(), "assets/sexidium/textures/" + referenced),
        "a texturePaths() entry overlapping a referenced icon is shipped once, not twice");
  }

  @Test
  void extraTexturesChangeTheHashSoClientsRedownload() throws IOException {
    SexidiumResourcePack.TextureSource withExtra = new SexidiumResourcePack.TextureSource() {
      @Override public byte[] texture(String path) { return "x".getBytes(); }
      @Override public java.util.Collection<String> texturePaths() {
        return java.util.List.of("item/elo_ranks/rank_gem_gold_tier1.png");
      }
    };
    assertNotEquals(SexidiumResourcePack.build().sha1Hex(), SexidiumResourcePack.build(withExtra).sha1Hex());
  }

  @Test
  void textureSourceReplacesPlaceholderAndChangesHash() throws IOException {
    // The first glyph is a tiled chest frame, so its shipped texture is the first row STRIP, not the whole
    // 768px file (which no provider references). A non-tiled glyph would ship its own path.
    MenuArt.Glyph first = MenuArt.glyphs().get(0);
    String target = first.tiled()
        ? MenuArt.tileRows(first).get(0).texturePath()
        : first.texturePath();
    byte[] real = "REAL-PNG-BYTES".getBytes();
    ResourcePackInfo info = SexidiumResourcePack.build(path -> path.equals(target) ? real : null);

    byte[] stored = entryBytes(info.bytes(), "assets/sexidium/textures/" + target);
    assertArrayEquals(real, stored, "the bundled real texture must be shipped verbatim");
    // A different texture set must change the pack hash so clients re-download it.
    assertNotEquals(SexidiumResourcePack.build().sha1Hex(), info.sha1Hex());
  }

  private static Set<String> entryNames(byte[] zipBytes) throws IOException {
    Set<String> names = new HashSet<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        names.add(entry.getName());
      }
    }
    return names;
  }

  private static long countEntries(byte[] zipBytes, String name) throws IOException {
    long count = 0;
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(name)) {
          count++;
        }
      }
    }
    return count;
  }

  private static byte[] entryBytes(byte[] zipBytes, String name) throws IOException {
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (entry.getName().equals(name)) {
          return zip.readAllBytes();
        }
      }
    }
    return new byte[0];
  }
}
