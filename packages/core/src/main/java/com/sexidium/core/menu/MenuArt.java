package com.sexidium.core.menu;

import java.util.List;
import java.util.Map;

/**
 * The single source of truth for Sexidium's custom menu art — the same data the
 * {@link MenuService} references when it <i>requests</i> a glyph background or a custom item-model
 * icon, and the resource-pack generator reads when it <i>produces</i> them. Keeping both sides on one
 * table is what makes the "Nexo-style" art layer maintainable: add one entry here and both the menu
 * and the pack pick it up.
 *
 * <p>This type is the stable public <b>facade</b>; the heavy data tables and build/load logic live in
 * package-private companions it delegates to: {@link MenuArtGlyphs} (background-glyph catalog),
 * {@link MenuArtIcons} (custom item-model tables + lookup), {@link MenuArtSpace} (the {@code space}
 * shifter font) and {@link MenuArtTiling} (the >256px tiling workaround + advance manifest). Callers
 * and the pack generator only ever reference {@code MenuArt}.
 *
 * <p><b>Icon source.</b> The button icons are the hand-authored {@code assets/icons/} sprite set,
 * imported verbatim by {@code scripts/art.py gen-menu-art} (into {@code assets/item/}) and organised by
 * <i>section</i> — the legacy sheet sections ({@code currency}, {@code cursors}, {@code elo_ranks},
 * {@code font}, {@code gui_buttons}, {@code system}) plus the minigame/experience/ui sets cut from
 * {@code UI/icons_minigames_experiences.png} ({@code minigames}, {@code experiences}, {@code ui}). An
 * icon id is therefore a {@code <section>/<name>} path
 * (e.g. {@code gui_buttons/button_back_green}); its {@code item_model} is {@code sexidium:<id>} and its
 * texture lives at {@code item/<id>.png}. Each semantic menu slot (hub tab / minigame / challenge /
 * action / nav) maps onto one of those icons via the tables below — the pack also <i>ships</i> every
 * source icon as a bare texture (no model) for future UIs, but only the icons referenced here get an
 * {@code item_model}.</p>
 *
 * <p><b>How the art renders (and why it degrades gracefully).</b> This is the negative-space font
 * technique popularised by Nexo/ItemsAdder, re-implemented natively (no paid dependency):
 * <ul>
 *   <li>A {@link Glyph} is one bitmap mapped to a Private-Use-Area code point in the
 *       {@code sexidium:menu} bitmap font. Writing that code point in a chest <b>title</b> paints the
 *       bitmap over the slot grid. {@link #shift(int)} emits characters of the {@code sexidium:space}
 *       font (a vanilla {@code space} font provider with per-code-point advances) to nudge the render
 *       cursor left/right by an exact pixel count, so the art lands where we want.</li>
 *   <li>An {@link IconModel} is a custom {@code item_model} a button can wear so its icon uses a
 *       bespoke texture instead of a vanilla material.</li>
 * </ul>
 *
 * <p><b>Cross-play reality (governing rule).</b> Java resource-pack fonts never reach Bedrock via
 * Geyser, so glyph art is a <b>Java-only enhancement</b>. Every renderer must fall back: Bedrock gets
 * the Cumulus Form, a Java client that declined the pack gets the plain chest. The platform adapter
 * only injects glyph/shift markup for players it knows loaded the pack; everyone else sees the
 * material-icon menu. See {@code docs/reference/tech-decisions.md} §A.3.</p>
 */
public final class MenuArt {
  /** Resource-pack namespace that owns every Sexidium font/texture/model. */
  public static final String NAMESPACE = "sexidium";
  /** Bitmap font that carries the GUI background glyphs. */
  public static final String GLYPH_FONT = NAMESPACE + ":menu";
  /** {@code space}-provider font that carries the negative/positive horizontal shifters. */
  public static final String SPACE_FONT = NAMESPACE + ":space";

  // ----- "wants a background" flags ------------------------------------------------------------
  // A screen passes one of these to MenuView.background(...) to request the slot-frame overlay. The
  // value is now just a non-null marker: the ACTUAL frame is chosen by the view's row count (see
  // chestGlyph(int)), so every chest of a given size shares one correctly-proportioned frame. Kept as
  // distinct constants so call sites stay self-documenting and unchanged.
  public static final String BG_MAIN = "main";
  public static final String BG_MINIGAMES = "minigames";
  public static final String BG_EXPERIENCES = "experiences";
  public static final String BG_BROWSE = "browse";
  public static final String BG_LOBBY = "lobby";
  public static final String BG_FRIENDS = "friends";
  public static final String BG_ADMIN = "admin";

  // ----- per-screen baked backgrounds (the scene-render system) -------------------------------
  // Unlike the size-keyed chest frames above, these are fully-composed per-screen backgrounds baked by
  // com.sexidium.core.menu.scene.bake.SceneBaker (frame + tile chrome + labels + dividers) and shipped as
  // glyphs. A view that names one of these (instead of a plain BG_* marker) gets the rich screen art as its
  // chest background. They are declared in the same yml registry (menu/backgrounds.yml, kind: screen) as
  // the chest frames; SCREEN_BG_IDS (below, derived from BackgroundCatalog) lists their scene ids.

  /** The background-glyph id for a baked per-screen scene, e.g. {@code screen/main-hub}. */
  public static String screenGlyphId(String sceneId) {
    return "screen/" + sceneId;
  }

  // ----- in-client calibration grid -----------------------------------------------------------
  // A debug full-window glyph that paints the EXACT vanilla slot grid (16x16 outlines at GUI (8+18c,18+18r))
  // plus a 1px ruler, so an operator can read off how far the live art is mis-placed against the real item
  // slots and dial in calibrateDx/Dy (below). It rides the same render path as a baked screen, so it moves
  // with the calibration exactly like real art does — when the boxes hug the slot items, calibration is right.
  /** Scene id of the procedural calibration overlay (kind {@code debug} in the registry). */
  public static final String CALIBRATION_SCENE_ID = "calibration";
  /** Glyph id of the calibration overlay ({@code screen/calibration}); a full-window {@code screen/} glyph. */
  public static final String CALIBRATION_GLYPH_ID = "screen/" + CALIBRATION_SCENE_ID;

  // ----- icon ids (each is a "<section>/<name>" path into the assets/icons/ source set) --------
  // Hub tabs.
  public static final String ICON_MINIGAMES = "ui/swords_duel";
  public static final String ICON_EXPERIENCE_CREATE = "gui_buttons/badge_emblem_gold";
  public static final String ICON_EXPERIENCE_MINE = "ui/book";
  public static final String ICON_BROWSE = "ui/portal";
  public static final String ICON_LOBBY = "system/home";
  public static final String ICON_FRIENDS = "ui/friends_add";
  public static final String ICON_ADMIN = "ui/gear";

  // Generic menu-action icons (the repeated buttons every screen shares).
  public static final String ICON_BACK = "gui_buttons/button_back_green";
  public static final String ICON_CREATE = "system/plus_gold";
  public static final String ICON_ENTER = "gui_buttons/button_arrow_blue";
  public static final String ICON_DELETE = "system/trash";
  public static final String ICON_RENAME = "system/pencil_edit";
  public static final String ICON_PUBLIC = "ui/spectate";
  public static final String ICON_PRIVATE = "ui/lock";
  public static final String ICON_EDIT_CHALLENGES = "system/wrench";
  public static final String ICON_QUICK_PLAY = "system/mouse_left_click";
  public static final String ICON_CREATE_LOBBY = "ui/banner_crest";
  public static final String ICON_JOIN = "system/arrow_right_gold";
  public static final String ICON_LOBBY_ROW = "ui/banner";
  public static final String ICON_INVITE = "ui/mail";
  public static final String ICON_INVITES = "ui/mail";
  public static final String ICON_ACCEPT = "system/check_green";
  public static final String ICON_DECLINE = "gui_buttons/button_close_x_red";
  public static final String ICON_ADD_FRIEND = "ui/friend_heart";
  public static final String ICON_PLAY_TOGETHER = "ui/friends_add";
  public static final String ICON_START = "gui_buttons/button_check_red";
  public static final String ICON_LEAVE = "system/key_esc";
  public static final String ICON_TEAMS = "ui/players";
  public static final String ICON_VISIBILITY = "ui/spectate";
  public static final String ICON_MIN_PLAYERS = "gui_buttons/badge_dots_green";
  public static final String ICON_EMPTY = "gui_buttons/button_blank_tan";
  public static final String ICON_NPC = "ui/players";
  public static final String ICON_NPC_CREATE = "system/plus_gold";
  public static final String ICON_RELOAD = "system/redo";

  // Lobby hotbar navigation icons (the persistent Menu / My Experiences items).
  public static final String ICON_NAV_MENU = "gui_buttons/button_menu_list_red";
  public static final String ICON_NAV_EXPERIENCES = "gui_buttons/badge_emblem_green";

  // ----- per-minigame icon ids -----------------------------------------------------------------
  // MODE_ICON_IDS mirrors the minigame registry (CoreGameRegistryInitializer); MenuArtCoverageTest
  // fails the build if a registered mode is missing from this list or MenuArtIcons' model table, so the
  // art table cannot silently fall behind. The modeId→source-icon mapping lives in MenuArtIcons.
  public static final String[] MODE_ICON_IDS = {"race", "gather", "tntwar", "combat", "fugitive"};

  // ----- per-challenge icon ids ----------------------------------------------------------------
  // CHALLENGE_ICON_IDS mirrors ChallengeCatalog; the same coverage test guards against drift here. The
  // challengeId→source-icon mapping (and its greyscale "disabled" twins) lives in MenuArtIcons.
  public static final String[] CHALLENGE_ICON_IDS = {
      "doubledrops", "randomizer", "sharedlife", "sharedinventory", "xphealth",
      "shrinkingachievements", "breakonebreakall", "chunkbreak", "blockdeleter",
      "randomchunks", "walkingblocks", "chained", "cleave", "growing", "jumpenchants", "mobduplication",
      "lookmultiplies", "jumpmultiplies", "randomlayers", "randomskyblock", "randommob", "randomdrops", "randomevents",
      "classicskyblock", "omnichunk", "layereddimensions", "deathresets"};

  // ----- chest-frame geometry (vanilla generic container, GUI pixels) -------------------------
  // The background is ONE full-window frame per chest size, imported by scripts/art.py bake-medieval from
  // the UltimateGUI "Medieval" pack (generic_<rows*9>.png), normalised into a 768px font cell (3x the 256
  // GUI cell, for sharpness) with the visible frame at source (0,0). The art is drawn at vanilla GUI
  // proportions (18 GUI px = 1 slot row), the 9-column grid lands on the vanilla slot at GUI (8,18), and the
  // player-inventory rows are drawn into the same opaque frame so a background hides them. The PER-GLYPH
  // render geometry (height, ascent, left_x, render_width) — all ON-SCREEN GUI px, unchanged by the source
  // resolution — lives in the yml registry (menu/backgrounds.yml -> BackgroundCatalog).
  /** Chest GUI window width in GUI pixels. The chest background font cell is 768px wide at source (rendered
   *  at the 256 GUI-px {@code height}, i.e. scale 1/3). */
  public static final int CHEST_WIDTH = 176;
  /** Full six-row generic-container window height in GUI pixels (114 + 6*18). */
  public static final int SCREEN_WINDOW_HEIGHT = 222;
  /** {@code titleLabelX}: the x the chest title (and so our glyph) starts at. */
  public static final int CHEST_TITLE_X = 8;
  private static final int CHEST_MIN_ROWS = 1;
  private static final int CHEST_MAX_ROWS = 6;

  // The background-glyph registry, indexed for the per-size / per-kind lookups below. Declared before the
  // derived constants so they read the loaded values (static fields initialise in textual order).
  private static final Map<Integer, BackgroundCatalog.BackgroundDef> CHEST_DEFS_BY_ROW =
      MenuArtGlyphs.chestDefsByRow(CHEST_MIN_ROWS, CHEST_MAX_ROWS);
  private static final List<BackgroundCatalog.BackgroundDef> SCREEN_DEFS = MenuArtGlyphs.screenDefs();

  /** Chest-x of the visible frame's left edge (negative = it overhangs left of the window). */
  public static final int CHEST_FRAME_LEFT_X = CHEST_DEFS_BY_ROW.get(CHEST_MIN_ROWS).leftX();
  /** Horizontal text advance after the visible frame glyph. */
  public static final int CHEST_FRAME_RENDER_WIDTH = CHEST_DEFS_BY_ROW.get(CHEST_MIN_ROWS).renderWidth();
  // Bitmap-font ON-SCREEN render height of a baked per-screen background (still 256 GUI px). Screens are
  // stored as 768px source font cells (3x, for sharpness) with the visible content starting at source (0,0);
  // placement is controlled by left_x/ascent. Sourced from the registry. (The 768px source exceeds the 256px
  // per-glyph atlas ceiling, so it ships TILED — see the TILE_* constants below.)
  public static final int SCREEN_GLYPH_HEIGHT = SCREEN_DEFS.get(0).height();
  /** Baked screens map their visible source window at (0,0) to GUI (0,0). */
  public static final int SCREEN_GLYPH_ASCENT = SCREEN_DEFS.get(0).ascent();
  /** Scene ids of the baked per-screen backgrounds (the {@code screen/} glyphs, prefix stripped), in
   *  registry order. Derived from {@link BackgroundCatalog}; a view opts in via {@link #screenGlyphId}. */
  public static final String[] SCREEN_BG_IDS = MenuArtGlyphs.screenBgIds(SCREEN_DEFS);

  // ----- background tiling (the >256px font-atlas workaround) ---------------------------------
  // Minecraft stitches every bitmap-font glyph into a fixed 256x256 atlas at SOURCE resolution; a glyph
  // whose source cell exceeds 256px on either axis cannot be placed and renders BLANK (minecraft.wiki/w/Font:
  // "Glyphs themselves must not be larger than 256x256 pixels"). Our backgrounds bake at 768px (3x) for
  // sharpness, so each is split into a TILE_GRID×TILE_GRID grid of ≤256px glyph cells (scripts/art.py
  // tile-backgrounds), reassembled on screen by the title-trick (per-tile horizontal shift + per-row font
  // ascent — see PaperMenuArt). 4×4 keeps the on-screen 256px cell exact (256/4 = 64) and the 768/4 = 192px
  // source tile ≤256; 3×3 would need a 85.33px on-screen tile (256 not divisible by 3) → non-integer
  // ascent/height → seams.
  /** Tiles per axis for a 768px background. */
  public static final int TILE_GRID = 4;
  /** On-screen px per tile ({@code TILE_GRID × this == the 256px background cell}). */
  public static final int TILE_SCREEN = SCREEN_GLYPH_HEIGHT / TILE_GRID;
  /** Per-tile horizontal advance (GUI px) the cursor-neutral return shift must undo. Minecraft bakes a
   *  {@code +1} into every bitmap glyph's advance ({@code advance = displayedWidth + 1} — proven by the
   *  whole-frame glyph: a ~197px-wide source renders {@code render_width 198}). The advance-anchor pixel
   *  (art.py tile-backgrounds) forces every tile's displayed width to a full {@link #TILE_SCREEN}, so the
   *  advance is uniformly {@code TILE_SCREEN + 1}. Using the bare tile width (no +1) left each tile's return
   *  1px short → tiles drifted right and opened 1px seams; the {@code +1} closes both. */
  public static final int TILE_ADVANCE = TILE_SCREEN + 1;

  private static final List<Glyph> GLYPHS = MenuArtGlyphs.buildGlyphs();

  // Global in-GUI-pixel nudge applied to EVERY background glyph, to absorb a client/version baseline
  // mismatch (the art renders consistently up/left or down/right of the slot grid). +dx moves the art
  // right, +dy moves it down. dx is a runtime title-shift (takes effect on the next menu open); dy is
  // baked into the pack font ascent (takes effect once the pack is rebuilt and the client re-downloads
  // it — its SHA changes). Set via {@link #setCalibration} on enable/reload.
  //
  // The DEFAULT_* values are the MEASURED baseline correction for the live client: the theoretical
  // geometry (registry left_x/ascent) places the art at the exact vanilla slot origin (8,18), but the
  // client's title-glyph baseline sits a hair off, so out of the box the art needs (-1,-2) GUI px. This
  // is baked here — NOT in the registry — so the geometry/tests stay on the pure theoretical model and
  // the art is still aligned with no config. The config knob ui.menu-art.calibrate-dx/-dy is ADDED on
  // top of this baseline (0 = use the baseline; non-zero = a further per-deployment tweak).
  public static final int DEFAULT_CALIBRATE_DX = -1;
  public static final int DEFAULT_CALIBRATE_DY = -2;
  private static final int CALIBRATION_LIMIT = 64;
  private static volatile int calibrateDx = DEFAULT_CALIBRATE_DX;
  private static volatile int calibrateDy = DEFAULT_CALIBRATE_DY;

  /** Classpath resource of per-tile advances generated by {@code scripts/art.py tile-backgrounds}. */
  public static final String TILE_ADVANCES_RESOURCE = MenuArtTiling.TILE_ADVANCES_RESOURCE;

  private MenuArt() {
  }

  /** Sets the global background-art nudge (GUI px, clamped to +/-{@value #CALIBRATION_LIMIT}). +dx = right, +dy = down. */
  public static void setCalibration(int dx, int dy) {
    calibrateDx = clampCalibration(dx);
    calibrateDy = clampCalibration(dy);
  }

  /** The current horizontal calibration nudge (GUI px; + = right). */
  public static int calibrateDx() {
    return calibrateDx;
  }

  /** The current vertical calibration nudge (GUI px; + = down). */
  public static int calibrateDy() {
    return calibrateDy;
  }

  private static int clampCalibration(int value) {
    return Math.max(-CALIBRATION_LIMIT, Math.min(CALIBRATION_LIMIT, value));
  }

  /** The pack-font {@code ascent} to emit for a glyph: its registry ascent shifted down by the vertical
   *  calibration ({@code +dy} lowers the ascent, moving the art down). Used only by the pack generator. */
  public static int renderAscent(Glyph glyph) {
    return renderAscent(glyph.ascent());
  }

  /** The pack-font {@code ascent} for a raw registry/row ascent (e.g. a per-row tile ascent), shifted down
   *  by the vertical calibration. */
  public static int renderAscent(int rawAscent) {
    return rawAscent - calibrateDy;
  }

  /**
   * One GUI background bitmap. {@code texturePath} is the pack-relative source path (e.g.
   * {@code ui/chest/chest_6.png}); {@code height}/{@code ascent}/{@code leftX}/{@code renderWidth} are the
   * ON-SCREEN render geometry (GUI px) from {@link BackgroundCatalog}. {@code codepoint} is the code point
   * in {@link #GLYPH_FONT}; when {@code tiled}, it is the BASE of a {@code TILE_GRID²} block (one code
   * point per tile — see {@link #tiles}/{@link #tileRows}), because the 768px source exceeds Minecraft's
   * 256px per-glyph atlas ceiling and is split into ≤256px tiles. A non-tiled glyph (the ≤256px
   * calibration overlay) is one code point. {@code renderWidth} is the on-screen visible glyph width.
   */
  public record Glyph(String id, char codepoint, int height, int ascent, int leftX, int renderWidth,
      String texturePath, boolean tiled) {
  }

  /**
   * One tile of a {@code tiled} background, for the title-trick: its glyph {@code codepoint}, on-screen
   * {@code leftX} (GUI px) and {@code renderWidth} (advance). Vertical placement is the row provider's
   * font {@code ascent} (see {@link #tileRows}), not carried here.
   */
  public record Tile(char codepoint, int leftX, int renderWidth) {
  }

  /**
   * One tile-ROW provider of a tiled background, for the pack emitter: the strip {@code texturePath}
   * ({@code <stem>.row<r>.png}, a {@code TILE_GRID}-wide 1×N bitmap grid), its row {@code codepoints}
   * (left→right) and the row's raw {@code ascent} (apply {@link #renderAscent(int)} when emitting).
   */
  public record TileRow(String texturePath, char[] codepoints, int ascent) {
  }

  /**
   * One custom {@code item_model} a button can wear via {@link MenuButton#model()}. The {@code id} is a
   * {@code <section>/<name>} path from the source icon set.
   */
  public record IconModel(String id) {
    /** The fully-qualified model id used in the {@code item_model} component, e.g. {@code sexidium:gui_buttons/button_back_green}. */
    public String modelId() {
      return NAMESPACE + ":" + id;
    }

    /** The pack-relative texture path this icon model uses, e.g. {@code item/gui_buttons/button_back_green.png}. */
    public String texturePath() {
      return "item/" + id + ".png";
    }
  }

  /** All background glyphs, in declaration order. The pack generator writes one font entry per glyph. */
  public static List<Glyph> glyphs() {
    return GLYPHS;
  }

  /** All custom icon models actually referenced by the menu. The pack generator writes one model + texture per entry. */
  public static List<IconModel> icons() {
    return MenuArtIcons.icons();
  }

  /** The fully-qualified {@code item_model} id for an icon id, e.g. {@code model(ICON_LOBBY)} → {@code sexidium:system/home}. */
  public static String model(String iconId) {
    return NAMESPACE + ":" + iconId;
  }

  /**
   * The custom {@code item_model} id for a minigame tile (e.g. {@code modeModel("race")} →
   * {@code sexidium:gui_buttons/button_clock_red}), or {@code null} when no bespoke icon ships for that
   * mode — so a caller's {@link MenuButton#withModel(String)} cleanly falls back to the vanilla
   * material rather than pointing at a missing model.
   */
  public static String modeModel(String modeId) {
    return MenuArtIcons.modeModel(modeId);
  }

  /**
   * The custom {@code item_model} id for an experience challenge tile (e.g.
   * {@code challengeModel("chained")} → {@code sexidium:gui_buttons/badge_crossed_blue}), or
   * {@code null} when no bespoke icon ships for that challenge.
   */
  public static String challengeModel(String challengeId) {
    return MenuArtIcons.challengeModel(challengeId);
  }

  /**
   * The greyscale "disabled" {@code item_model} id for an experience challenge tile (e.g.
   * {@code challengeModelDisabled("chained")} → {@code sexidium:experiences/chained_disabled}), or
   * {@code null} when no greyscale twin ships (e.g. {@code shrinkingachievements}). The experience
   * builder uses it for an un-selected twist so the icon reads as "off".
   */
  public static String challengeModelDisabled(String challengeId) {
    return MenuArtIcons.challengeModelDisabled(challengeId);
  }

  /** Whether a custom icon with this exact id ships an {@code item_model} in the pack. */
  public static boolean hasIcon(String iconId) {
    return MenuArtIcons.hasIcon(iconId);
  }

  /** The glyph for a background id, or {@code null} if unknown. */
  public static Glyph glyph(String backgroundId) {
    for (Glyph glyph : GLYPHS) {
      if (glyph.id().equals(backgroundId)) {
        return glyph;
      }
    }
    return null;
  }

  /** The background-glyph id for a chest of {@code rows} rows (clamped 1..6), e.g. {@code chest_6}. */
  public static String chestGlyphId(int rows) {
    return "chest_" + clampRows(rows);
  }

  /** The GUI-pixel render height of the chest frame for {@code rows} rows (preserves the frame aspect). */
  public static int chestGlyphHeight(int rows) {
    return CHEST_DEFS_BY_ROW.get(clampRows(rows)).height();
  }

  /** The bitmap-font ascent of the chest frame for {@code rows} rows. */
  public static int chestGlyphAscent(int rows) {
    return CHEST_DEFS_BY_ROW.get(clampRows(rows)).ascent();
  }

  /** The background glyph for a chest of {@code rows} rows, or {@code null} if none (never, 1..6 covered). */
  public static Glyph chestGlyph(int rows) {
    return glyph(chestGlyphId(rows));
  }

  /** Whether a background-glyph id is a baked per-screen scene (vs a size-keyed chest frame). */
  public static boolean isScreenGlyph(String glyphId) {
    return glyphId != null && glyphId.startsWith("screen/");
  }

  /** Pixel shift that pulls the cursor from the title origin (x=8) to a background glyph's left edge.
   *  Per-glyph {@code left_x} comes from the registry: chest frames overhang (negative left edge), screens
   *  align to the window left edge (0). Falls back to the shared chest value for an unknown id. */
  public static int frameShift(String glyphId) {
    return glyphLeftX(glyphId) - CHEST_TITLE_X + calibrateDx;
  }

  /** Pixel shift after the glyph (which advances by its render width) that returns the cursor to the title
   *  origin so the title text still renders. Render width is the glyph's own ({@code render_width}). */
  public static int titleReturnShift(String glyphId) {
    Glyph glyph = glyph(glyphId);
    int leftX = glyph != null ? glyph.leftX() : CHEST_FRAME_LEFT_X;
    int renderWidth = glyph != null ? glyph.renderWidth() : CHEST_FRAME_RENDER_WIDTH;
    // Subtract calibrateDx: frameShift pushed the pen an extra +dx right, so the title text that follows
    // the glyph must come back the same amount to stay at the vanilla title origin.
    return CHEST_TITLE_X - (leftX + renderWidth) - calibrateDx;
  }

  private static int glyphLeftX(String glyphId) {
    Glyph glyph = glyph(glyphId);
    return glyph != null ? glyph.leftX() : CHEST_FRAME_LEFT_X;
  }

  /**
   * The per-row strip providers of a tiled background (top→bottom), for the pack emitter: each is the
   * {@code <stem>.row<r>.png} strip texture, its {@code TILE_GRID} row code points (left→right), and the
   * row's raw font {@code ascent} (= the glyph ascent shifted up by one tile-height per row; pass through
   * {@link #renderAscent(int)} when emitting). Empty for a non-tiled glyph.
   */
  public static List<TileRow> tileRows(Glyph glyph) {
    return MenuArtTiling.tileRows(glyph);
  }

  /**
   * The tiles of a tiled background in reading order (row-major), for the title-trick: each carries its
   * code point, on-screen left edge ({@code leftX + col*tile}) and advance ({@link #TILE_ADVANCE}-equiv).
   * Vertical placement comes from the row provider {@link TileRow#ascent()}. Empty for a non-tiled glyph.
   */
  public static List<Tile> tiles(Glyph glyph) {
    return MenuArtTiling.tiles(glyph);
  }

  /** {@link #frameShift} for one tile: pulls the cursor from the title origin to the tile's left edge. */
  public static int tileFrameShift(int tileLeftX) {
    return tileLeftX - CHEST_TITLE_X + calibrateDx;
  }

  /** {@link #titleReturnShift} for one tile: returns the cursor to the title origin after the tile's
   *  advance, so each tile triple is cursor-neutral and the tiles (and any stacked overlay) share an anchor. */
  public static int tileReturnShift(int tileLeftX, int renderWidth) {
    return CHEST_TITLE_X - (tileLeftX + renderWidth) - calibrateDx;
  }

  private static int clampRows(int rows) {
    return Math.max(CHEST_MIN_ROWS, Math.min(CHEST_MAX_ROWS, rows));
  }

  /**
   * The advance table for the {@code space} font provider: code point → horizontal pixel advance. The
   * pack generator serialises this verbatim into {@code assets/sexidium/font/space.json}.
   */
  public static Map<Character, Integer> spaceAdvances() {
    return MenuArtSpace.spaceAdvances();
  }

  /**
   * The raw character sequence (in {@link #SPACE_FONT}) that advances the render cursor by exactly
   * {@code pixels} (negative pulls left). Decomposes the magnitude into the available powers of two so
   * any reachable offset is exact. Wrap the result in {@code <font:sexidium:space>…</font>} when
   * rendering. Pure, so it is unit-testable headless.
   */
  public static String shift(int pixels) {
    return MenuArtSpace.shift(pixels);
  }
}
