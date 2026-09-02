package com.sexidium.core.menu.pack;

import com.sexidium.core.Branding;
import com.sexidium.core.menu.BackgroundCatalog;
import com.sexidium.core.menu.MenuArt;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

/**
 * Generates the Sexidium menu resource pack from the {@link MenuArt} table — the native
 * re-implementation of the Nexo/ItemsAdder "custom GUI" pipeline, owned in-repo with no paid plugin.
 *
 * <p>The pack contains exactly what the negative-space art layer needs:
 * <ul>
 *   <li>{@code assets/sexidium/font/menu.json} — one {@code bitmap} provider per {@link MenuArt.Glyph}
 *       (the GUI backgrounds painted into chest titles).</li>
 *   <li>{@code assets/sexidium/font/space.json} — one {@code space} provider whose advances are
 *       {@link MenuArt#spaceAdvances()} (the {@code <shift:n>} horizontal nudges).</li>
 *   <li>{@code assets/sexidium/items/<id>.json} + {@code models/item/<id>.json} — one custom
 *       {@code item_model} per {@link MenuArt.IconModel} (bespoke button icons; the id is a
 *       {@code <section>/<name>} path, e.g. {@code gui_buttons/button_back_green}).</li>
 *   <li>Placeholder PNG textures for every glyph and icon, generated on the fly so the pack is
 *       functional out of the box; a designer drops real art over the same paths later.</li>
 *   <li>Every <i>extra</i> texture the {@link TextureSource} advertises via
 *       {@link TextureSource#texturePaths()} (the full {@code ./icons/} set), shipped as a bare texture
 *       with no model — available for future UIs without bloating the referenced-icon tables.</li>
 * </ul>
 *
 * <p>Pure and JDK-only (ImageIO + zip), so it runs headless and inside every adapter
 * reuse it. {@link #build()} returns the zip bytes + their SHA-1.</p>
 */
public final class SexidiumResourcePack {
  // The pack format Sexidium's art is authored for, declared through min_format/max_format (see
  // packMeta). This tracks the target Minecraft release exactly: it is pack_version.resource_major from
  // that version's version.json (26.1.2 -> 84, 26.2 -> 88), so bump it in the same change that bumps
  // PAPER_VERSION in scripts/init-paper.sh. The older pack_format + supported_formats pair this used to
  // emit is gone from the schema — Minecraft dropped supported_formats, and pack_format survives only for
  // packs declaring a format below 65, which is the legacy branch the previous value (64) landed in.
  //
  // min_format/max_format is NOT a 26.2 invention: 26.1.2's own bundled datapacks already declare it, so
  // the schema is shared and only the NUMBER moves between the two.
  //
  // Public (read-only) because it is one of the four places the same number lives — see
  // PackFormatConsistencyTest, which fails the build when any of them drifts.
  public static final int PACK_FORMAT = 84;
  // Fixed DOS-safe timestamp (2020-01-01) so the zip is reproducible byte-for-byte.
  private static final long FIXED_ENTRY_TIME = 1577836800000L;

  // Blanks the vanilla "Inventory" label the client paints under our menu art. That label is the
  // player's inventory name, Component.translatable("container.inventory"), drawn by
  // AbstractContainerScreen.renderLabels AFTER the chest title — so nothing we paint into the title
  // glyph can ever cover it (verified against the 1.21.x client). A resource-pack lang override is the
  // only lever: an empty value makes the client's drawString render nothing, so only our art shows.
  private static final String HIDE_INVENTORY_LABEL_LANG = "{\"container.inventory\": \"\"}\n";
  // The override MUST be written per client locale: lang files merge per-key with vanilla, and the
  // client only falls back to en_us for a MISSING key — never for a key that is PRESENT. An en_us-only
  // override would still show "Inventário" to a pt_br player, so we ship the empty override for EVERY
  // official locale (each file is ~30 bytes). Sorted + fixed so the pack stays byte-deterministic.
  // Side effect (accepted; the pack is server-gated): the label is hidden on ALL containers — the
  // survival inventory, chests, etc. — for players who loaded the pack. Lives in the MINECRAFT
  // namespace (container.inventory is a vanilla key), not sexidium.
  private static final List<String> VANILLA_LANG_CODES = List.of(
      "af_za", "ar_sa", "ast_es", "az_az", "ba_ru", "bar", "be_by", "be_latn", "bg_bg", "br_fr",
      "brb", "bs_ba", "ca_es", "cs_cz", "cy_gb", "da_dk", "de_at", "de_ch", "de_de", "el_gr",
      "en_au", "en_ca", "en_gb", "en_nz", "en_pt", "en_ud", "en_us", "enp", "enws", "eo_uy",
      "es_ar", "es_cl", "es_ec", "es_es", "es_mx", "es_uy", "es_ve", "esan", "et_ee", "eu_es",
      "fa_ir", "fi_fi", "fil_ph", "fo_fo", "fr_ca", "fr_fr", "fra_de", "fur_it", "fy_nl", "ga_ie",
      "gd_gb", "gl_es", "haw_us", "he_il", "hi_in", "hn_no", "hr_hr", "hu_hu", "hy_am", "id_id",
      "ig_ng", "io_en", "is_is", "isv", "it_it", "ja_jp", "jbo_en", "ka_ge", "kk_kz", "kn_in",
      "ko_kr", "ksh", "kw_gb", "ky_kg", "la_la", "lb_lu", "li_li", "lmo", "lo_la", "lol_us",
      "lt_lt", "lv_lv", "lzh", "mk_mk", "mn_mn", "ms_my", "mt_mt", "nah", "nds_de", "nl_be",
      "nl_nl", "nn_no", "no_no", "oc_fr", "ovd", "pl_pl", "pt_br", "pt_pt", "qya_aa", "ro_ro",
      "rpr", "ru_ru", "ry_ua", "se_no", "sk_sk", "sl_si", "so_so", "sq_al", "sr_cs", "sr_sp",
      "sv_se", "sxu", "szl", "ta_in", "th_th", "tl_ph", "tlh_aa", "tok", "tr_tr", "tt_ru",
      "tzl_tzl", "uk_ua", "val_es", "vec_it", "vi_vn", "vp_vl", "yi_de", "yo_ng", "zh_cn",
      "zh_hk", "zh_tw", "zlm_arab");

  private SexidiumResourcePack() {
  }

  /**
   * Supplies real artwork for a pack texture path (e.g. {@code gui/menu/main.png}) when the build is
   * driven by bundled art (the {@code ./context} textures staged via {@code -PmenuPackZip}); returns
   * {@code null} for a path with no real art so the generator falls back to a placeholder. Lets the
   * <i>same</i> JSON-emitting generator produce either a placeholder pack or a real-art pack.
   */
  public interface TextureSource {
    byte[] texture(String packTexturePath);

    /**
     * Extra pack-relative texture paths to ship as bare textures (no {@code item_model}) — e.g. the
     * full {@code ./icons/} set, so any sprite is available to a future UI even though only the
     * menu-referenced icons get a model. Returns nothing by default (placeholder builds ship only the
     * referenced art).
     */
    default Collection<String> texturePaths() {
      return List.of();
    }
  }

  /** Builds the placeholder pack (no real art). */
  public static ResourcePackInfo build() {
    return build(Branding.DEFAULT_LABEL, null);
  }

  /**
   * Builds the pack zip + its SHA-1, using {@code textures} for any texture it supplies and a generated
   * placeholder otherwise. Throws {@link UncheckedIOException} only on a JVM I/O fault.
   */
  public static ResourcePackInfo build(TextureSource textures) {
    return build(Branding.DEFAULT_LABEL, textures);
  }

  /** Builds the pack using {@code label} as its in-client description. */
  public static ResourcePackInfo build(String label, TextureSource textures) {
    byte[] bytes = zip(Branding.label(label), textures);
    return new ResourcePackInfo(bytes, sha1Hex(bytes), "");
  }

  private static byte[] zip(String label, TextureSource textures) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    // Texture paths already shipped (glyphs + referenced icons), so the bare-texture pass below never
    // writes a duplicate zip entry for a path that also backs a referenced icon.
    Set<String> writtenTextures = new LinkedHashSet<>();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      put(zip, "pack.mcmeta", packMeta(label));
      // Vanilla-namespace lang overrides that blank the "Inventory" label over our menus (per locale).
      for (String code : VANILLA_LANG_CODES) {
        put(zip, "assets/minecraft/lang/" + code + ".json", HIDE_INVENTORY_LABEL_LANG);
      }
      put(zip, "assets/" + MenuArt.NAMESPACE + "/font/menu.json", menuFont());
      put(zip, "assets/" + MenuArt.NAMESPACE + "/font/space.json", spaceFont());
      // Medieval typography fonts (uppercase-Latin bitmap caps): one font provider + its per-char glyph
      // textures each. Declared in the same yml registry as the backgrounds (BackgroundCatalog.fontsAll()).
      for (BackgroundCatalog.FontDef font : BackgroundCatalog.fontsAll()) {
        put(zip, "assets/" + MenuArt.NAMESPACE + "/font/" + font.id() + ".json", typographyFont(font));
        for (char character : font.chars().toCharArray()) {
          String path = font.texturePath(character);
          putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + path,
              textureOr(textures, path, () -> iconTexture(font.id() + "/" + character)));
          writtenTextures.add(path);
        }
      }
      for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
        if (MenuArt.CALIBRATION_GLYPH_ID.equals(glyph.id())) {
          // The calibration overlay is ALWAYS the procedural slot-grid ruler — never a bundled/placeholder
          // texture — so it is a faithful ground-truth reference regardless of what art the pack ships.
          putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + glyph.texturePath(),
              calibrationTexture());
          writtenTextures.add(glyph.texturePath());
        } else if (glyph.tiled()) {
          // Tiled background: ship one ≤256px row-strip texture per provider (real art from the bundle, or a
          // placeholder strip). The whole-file 768px source is NOT shipped — no glyph references it.
          for (MenuArt.TileRow row : MenuArt.tileRows(glyph)) {
            byte[] texture = textureOr(textures, row.texturePath(),
                () -> backgroundStrip(glyph.id(), glyph.height()));
            putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + row.texturePath(), texture);
            writtenTextures.add(row.texturePath());
          }
        } else {
          byte[] texture = textureOr(textures, glyph.texturePath(), () -> backgroundTexture(glyph.id()));
          putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + glyph.texturePath(), texture);
          writtenTextures.add(glyph.texturePath());
        }
      }
      for (MenuArt.IconModel icon : MenuArt.icons()) {
        put(zip, "assets/" + MenuArt.NAMESPACE + "/items/" + icon.id() + ".json", itemDefinition(icon));
        put(zip, "assets/" + MenuArt.NAMESPACE + "/models/item/" + icon.id() + ".json", itemModel(icon));
        putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + icon.texturePath(),
            textureOr(textures, icon.texturePath(), () -> iconTexture(icon.id())));
        writtenTextures.add(icon.texturePath());
      }
      // Bare textures (no model): the rest of the bundled ./icons/ set, shipped verbatim. Skipped
      // entirely in placeholder builds (texturePaths() is empty). Sorted so the zip stays deterministic.
      for (String path : new java.util.TreeSet<>(textures == null ? List.of() : textures.texturePaths())) {
        if (path == null || path.isBlank() || writtenTextures.contains(path)) {
          continue;
        }
        byte[] bytes = textures.texture(path);
        if (bytes != null && bytes.length > 0) {
          putBytes(zip, "assets/" + MenuArt.NAMESPACE + "/textures/" + path, bytes);
          writtenTextures.add(path);
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to build " + label + " resource pack", exception);
    }
    return out.toByteArray();
  }

  /** Real bundled art for {@code packTexturePath} if the source has it, else the generated placeholder. */
  private static byte[] textureOr(TextureSource textures, String packTexturePath,
      java.util.function.Supplier<byte[]> placeholder) {
    if (textures != null) {
      byte[] real = textures.texture(packTexturePath);
      if (real != null && real.length > 0) {
        return real;
      }
    }
    return placeholder.get();
  }

  // ----- JSON assets --------------------------------------------------------------------------

  private static String packMeta(String label) {
    return "{\n  \"pack\": {\n    \"min_format\": " + PACK_FORMAT + ",\n"
        + "    \"max_format\": " + PACK_FORMAT + ",\n"
        + "    \"description\": \"" + jsonEscape(label) + " menu art\"\n  }\n}\n";
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String menuFont() {
    StringBuilder json = new StringBuilder("{\n  \"providers\": [\n");
    boolean first = true;
    for (MenuArt.Glyph glyph : MenuArt.glyphs()) {
      if (glyph.tiled()) {
        // A 768px background exceeds Minecraft's 256px per-glyph atlas ceiling, so it ships as TILE_GRID
        // row-STRIP providers (each a 768×(768/GRID) file, a 1×GRID char grid → GRID cells of ≤256px). The
        // strip's single `ascent` is that tile-row's vertical placement; horizontal is the title-trick shift.
        for (MenuArt.TileRow row : MenuArt.tileRows(glyph)) {
          if (!first) {
            json.append(",\n");
          }
          first = false;
          json.append("    {\"type\": \"bitmap\", \"file\": \"")
              .append(MenuArt.NAMESPACE).append(":").append(row.texturePath())
              .append("\", \"height\": ").append(glyph.height() / MenuArt.TILE_GRID)
              .append(", \"ascent\": ").append(MenuArt.renderAscent(row.ascent()))
              .append(", \"chars\": [\"").append(escape(new String(row.codepoints()))).append("\"]}");
        }
      } else {
        if (!first) {
          json.append(",\n");
        }
        first = false;
        json.append("    {\"type\": \"bitmap\", \"file\": \"")
            .append(MenuArt.NAMESPACE).append(":").append(glyph.texturePath())
            .append("\", \"height\": ").append(glyph.height())
            // renderAscent applies the vertical calibration nudge (config ui.menu-art.calibrate-dy) so a
            // client/version baseline mismatch can be corrected without re-baking textures.
            .append(", \"ascent\": ").append(MenuArt.renderAscent(glyph))
            .append(", \"chars\": [\"").append(escape(String.valueOf(glyph.codepoint()))).append("\"]}");
      }
    }
    json.append("\n  ]\n}\n");
    return json.toString();
  }

  /** A medieval typography font: a {@code space} provider for {@code ' '} plus one {@code bitmap} provider
   *  per uppercase character (each glyph is its own {@code char_<X>.png}). */
  private static String typographyFont(BackgroundCatalog.FontDef font) {
    StringBuilder json = new StringBuilder("{\n  \"providers\": [\n");
    json.append("    {\"type\": \"space\", \"advances\": {\" \": ").append(font.spaceAdvance()).append("}}");
    for (char character : font.chars().toCharArray()) {
      json.append(",\n    {\"type\": \"bitmap\", \"file\": \"")
          .append(MenuArt.NAMESPACE).append(":").append(font.texturePath(character))
          .append("\", \"height\": ").append(font.height())
          .append(", \"ascent\": ").append(font.ascent())
          .append(", \"chars\": [\"").append(escape(String.valueOf(character))).append("\"]}");
    }
    json.append("\n  ]\n}\n");
    return json.toString();
  }

  private static String spaceFont() {
    StringBuilder json = new StringBuilder("{\n  \"providers\": [\n    {\"type\": \"space\", \"advances\": {");
    boolean first = true;
    for (Map.Entry<Character, Integer> entry : MenuArt.spaceAdvances().entrySet()) {
      if (!first) {
        json.append(",");
      }
      first = false;
      json.append("\"").append(escape(String.valueOf(entry.getKey()))).append("\": ").append(entry.getValue());
    }
    json.append("}}\n  ]\n}\n");
    return json.toString();
  }

  private static String itemDefinition(MenuArt.IconModel icon) {
    return "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n"
        + "    \"model\": \"" + MenuArt.NAMESPACE + ":item/" + icon.id() + "\"\n  }\n}\n";
  }

  private static String itemModel(MenuArt.IconModel icon) {
    return "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n"
        + "    \"layer0\": \"" + MenuArt.NAMESPACE + ":item/" + icon.id() + "\"\n  }\n}\n";
  }

  // ----- placeholder textures (designer drops real art over these paths) ----------------------

  /** A 256×256 translucent panel with the Sexidium accent bar — a stand-in GUI background. */
  private static byte[] backgroundTexture(String id) {
    BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    // Mostly transparent so it overlays a chest without hiding the slots; a soft dark panel up top.
    graphics.setColor(new Color(20, 20, 28, 140));
    graphics.fillRoundRect(8, 4, 240, 70, 16, 16);
    graphics.setColor(accent(id));
    graphics.fillRoundRect(8, 4, 240, 8, 8, 8);
    graphics.dispose();
    return png(image);
  }

  /**
   * Placeholder for ONE row-strip of a tiled background ({@code cell}px-tall background → a
   * {@code cell}×{@code cell/TILE_GRID} strip). Only used when no real art is bundled; a faint translucent
   * wash so the menu is still usable, plus an alpha=1 advance-anchor at each cell's rightmost-bottom corner
   * (mirrors {@code art.py tile-backgrounds}) so the per-tile advance stays uniform.
   */
  private static byte[] backgroundStrip(String id, int cell) {
    int grid = MenuArt.TILE_GRID;
    int tile = cell / grid;
    BufferedImage image = new BufferedImage(cell, tile, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    graphics.setColor(new Color(20, 20, 28, 90));
    graphics.fillRect(0, 0, cell, tile);
    graphics.setColor(accent(id));
    graphics.fillRect(0, 0, cell, Math.max(1, tile / 24));
    graphics.dispose();
    for (int c = 0; c < grid; c++) {
      int ax = c * tile + tile - 1;
      int ay = tile - 1;
      if ((image.getRGB(ax, ay) >>> 24) == 0) {
        image.setRGB(ax, ay, 0x01000000); // alpha=1 advance anchor (imperceptible)
      }
    }
    return png(image);
  }

  /**
   * The calibration overlay: a 256×256 font cell whose visible 176×222 window paints the EXACT vanilla
   * slot grid plus a 1-pixel ruler, so an operator opening it (Admin ▸ Menu Art Calibration) can read how
   * far the live art sits from the real item slots and set {@code ui.menu-art.calibrate-dx/-dy}.
   *
   * <p>Drawn at source (0,0) (so it rides the {@code screen/} placement: source(0,0) → GUI(0,0)). Each
   * vanilla slot interior is a 16×16 box at GUI {@code (8+18c, 18+18r)}; a correctly-calibrated client shows
   * each cyan box framing the item in that slot. The top/left rulers tick every pixel (brighter at 5/10) so
   * an offset is countable. Because the overlay is itself art, it moves with the calibration exactly like the
   * real backgrounds — zero offset between boxes and items == calibrated.</p>
   */
  private static byte[] calibrationTexture() {
    int window = MenuArt.CHEST_WIDTH;            // 176
    int height = MenuArt.SCREEN_WINDOW_HEIGHT;   // 222
    int slot0X = MenuArt.CHEST_TITLE_X;          // 8  (vanilla first-slot interior x)
    int slot0Y = 18;                             // vanilla first-slot interior y
    int pitch = 18;
    int inner = 16;
    int dim = new Color(10, 8, 20, 170).getRGB();
    int box = new Color(0, 230, 255, 255).getRGB();
    int dot = new Color(255, 80, 230, 255).getRGB();
    int tick1 = new Color(120, 120, 130, 200).getRGB();
    int tick5 = new Color(0, 230, 255, 230).getRGB();
    int tick10 = new Color(255, 220, 70, 255).getRGB();
    BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
    // Semi-opaque wash over the window so the covered slots read as one panel but item icons still show.
    for (int y = 0; y < height; y++) {
      for (int x = 0; x < window; x++) {
        image.setRGB(x, y, dim);
      }
    }
    // Rulers along the top and left edges: a tick every px, taller/brighter every 5th and 10th.
    for (int x = 0; x < window; x++) {
      int len = x % 10 == 0 ? 6 : (x % 5 == 0 ? 4 : 2);
      int colour = x % 10 == 0 ? tick10 : (x % 5 == 0 ? tick5 : tick1);
      for (int y = 0; y < len; y++) {
        image.setRGB(x, y, colour);
      }
    }
    for (int y = 0; y < height; y++) {
      int len = y % 10 == 0 ? 6 : (y % 5 == 0 ? 4 : 2);
      int colour = y % 10 == 0 ? tick10 : (y % 5 == 0 ? tick5 : tick1);
      for (int x = 0; x < len; x++) {
        image.setRGB(x, y, colour);
      }
    }
    // One 16×16 outline per slot of the 9×6 grid, plus a centre dot.
    for (int row = 0; row < 6; row++) {
      for (int col = 0; col < 9; col++) {
        int left = slot0X + col * pitch;
        int top = slot0Y + row * pitch;
        for (int x = left; x < left + inner; x++) {
          plot(image, x, top, box);
          plot(image, x, top + inner - 1, box);
        }
        for (int y = top; y < top + inner; y++) {
          plot(image, left, y, box);
          plot(image, left + inner - 1, y, box);
        }
        plot(image, left + inner / 2, top + inner / 2, dot);
      }
    }
    return png(image);
  }

  private static void plot(BufferedImage image, int x, int y, int rgb) {
    if (x >= 0 && y >= 0 && x < image.getWidth() && y < image.getHeight()) {
      image.setRGB(x, y, rgb);
    }
  }

  /** A 32×32 rounded tile in the icon's accent hue — a stand-in custom item icon. */
  private static byte[] iconTexture(String id) {
    BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    Graphics2D graphics = image.createGraphics();
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    Color base = accent(id);
    graphics.setColor(base.darker());
    graphics.fillRoundRect(2, 2, 28, 28, 10, 10);
    graphics.setColor(base);
    graphics.fillRoundRect(5, 5, 22, 22, 8, 8);
    graphics.dispose();
    return png(image);
  }

  /** A stable, pleasant hue per art id so placeholders are distinguishable at a glance. */
  private static Color accent(String id) {
    float hue = (Math.abs(id.hashCode()) % 360) / 360.0f;
    return Color.getHSBColor(hue, 0.55f, 0.95f);
  }

  private static byte[] png(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException exception) {
      throw new UncheckedIOException("Failed to encode placeholder texture", exception);
    }
    return out.toByteArray();
  }

  // ----- zip + hashing helpers ----------------------------------------------------------------

  private static void put(ZipOutputStream zip, String path, String content) throws IOException {
    putBytes(zip, path, content.getBytes(StandardCharsets.UTF_8));
  }

  private static void putBytes(ZipOutputStream zip, String path, byte[] content) throws IOException {
    ZipEntry entry = new ZipEntry(path);
    // Pin a fixed timestamp so identical art produces byte-identical bytes (stable SHA-1 → the client
    // caches the pack across restarts instead of re-downloading it every join).
    entry.setTime(FIXED_ENTRY_TIME);
    zip.putNextEntry(entry);
    zip.write(content);
    zip.closeEntry();
  }

  private static String sha1Hex(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
      StringBuilder hex = new StringBuilder(digest.length * 2);
      for (byte value : digest) {
        hex.append(Character.forDigit((value >> 4) & 0xF, 16));
        hex.append(Character.forDigit(value & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-1 unavailable", exception);
    }
  }

  /** JSON string-escaping that emits {@code \\uXXXX} for the Private-Use code points the fonts use. */
  private static String escape(String value) {
    StringBuilder out = new StringBuilder();
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        default -> {
          if (character < 0x20 || character > 0x7E) {
            out.append(String.format("\\u%04X", (int) character));
          } else {
            out.append(character);
          }
        }
      }
    }
    return out.toString();
  }
}
