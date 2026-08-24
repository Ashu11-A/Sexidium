package com.sexidium.core.menu.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * The hub screen builders: turn the permission-filtered tab list into a baked {@link Scene} — a fully
 * TRANSPARENT overlay of medieval button cards (the button components from the UltimateGUI {@code 54.psd}
 * board, cut to {@code assets/ui/cards/card.png}). The in-game adapter paints the {@code chest_6} wood frame
 * behind it and draws these cards on top, each card sitting on the frame's painted slot cells.
 *
 * <p>The overlay shares {@code chest_6}'s cell and on-screen glyph geometry exactly (same {@code height} /
 * {@code ascent} / {@code left_x} / {@code render_width} in {@code menu/backgrounds.yml}, and the same 768px
 * source cell the baker emits), so a card drawn on
 * a {@code chest_6} cell renders pixel-for-pixel on top of it. Validate the fit with {@code scripts/art.py
 * overlay-hub}. The baked PNG itself carries NO frame and NO opaque fill — only the cards on a transparent
 * canvas. Templates are pure (data in → {@code Scene} out) so they render headlessly in tests and the baker.</p>
 */
public final class SceneTemplates {

  /** Overlay canvas in GUI px — identical to {@code chest_6}'s on-screen cell so the two glyphs align 1:1.
   *  The scene is authored in GUI px; {@code SceneBaker} emits the PNG at {@code SOURCE_SCALE×} (768). */
  private static final int HUB_SIZE = 256;

  // chest_6's painted slot-cell grid, MEASURED from the medieval frame (256-space): the slot cells are the
  // light tan (#a88a68) "core blocks", separated by the darker tan (#ab8c6b) separator lines. Cell-0 interior
  // top-left is (21,32), pitch 18. Cards span full 3×2 cell-pitch BLOCKS and tile FLUSH (no gap between cards
  // — each card edge meets the next). (Click hit-groups are slot-index based — see hubHitGroups — and
  // independent of these pixel metrics.)
  private static final int HUB_CELL_X0 = 21;
  private static final int HUB_CELL_Y0 = 32;
  private static final int HUB_CELL_PITCH = 18;

  // Optional uniform enlargement of the cards about the grid centre. 1.0 = fit EXACTLY within the slot blocks
  // (cards bounded by the #ab8c6b separators); raise slightly only if a touch of overflow is wanted. Re-bake
  // screens after changing (./gradlew :packages:core:bakeMenuScreens && python3 scripts/art.py tile-backgrounds).
  private static final double HUB_SCALE = 1.0;
  private static final double HUB_ANCHOR_X = 99;   // chest_6 slot-grid centre (x), 256-space
  private static final double HUB_ANCHOR_Y = 65;   // card-grid centre (y), 256-space

  private SceneTemplates() {
  }

  /** One hub card: its caption and the icon sprite drawn on it. */
  public record Tab(String label, String iconSpriteId) {}

  // ----- Main hub -----------------------------------------------------------------------------

  /** Chest slot columns / rows one card spans (a 3×2 block). */
  public static final int HUB_CARD_COLS = 3;
  public static final int HUB_CARD_ROWS = 2;

  /** Visible-tab counts that select a baked hub variant: 6 ⇒ normal, 7 ⇒ operator (adds Settings). */
  public static final int HUB_TABS_NORMAL = 6;
  public static final int HUB_TABS_OP = 7;

  // Card grid cells (cardColumn, cardRow) in tab order: two rows of three for the normal tabs; the operator
  // variant appends the Settings card centred a row below. The three card rows map onto the six slot rows of
  // the chest_6 tan panel.
  private static final int[][] HUB_CARDS_NORMAL = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}};
  private static final int[][] HUB_CARDS_OP = {{0, 0}, {1, 0}, {2, 0}, {0, 1}, {1, 1}, {2, 1}, {1, 2}};

  /** The medieval button-card sprite drawn as each option's background (assets/ui/cards/card.png). */
  private static final String HUB_CARD_SPRITE = "ui/cards/card";
  /** Caption ink: dark brown that reads on the tan card face. */
  private static final int HUB_LABEL_INK = 0xFF3E2A12;

  /**
   * The {@code chest_6} {@link Box} a card occupies: snug around its {@link #HUB_CARD_COLS}×{@link
   * #HUB_CARD_ROWS} cell block, 1px outset to frame the cells. Coordinates are in {@code chest_6}'s 256
   * GUI-px space (the baker scales the render to the 768 source), so the baked overlay lands on the cells.
   */
  private static Box hubCardBox(int cardCol, int cardRow) {
    // The card spans a full HUB_CARD_COLS×HUB_CARD_ROWS block of cell-pitches, so adjacent cards are FLUSH
    // (each card's right/bottom edge == the next card's left/top edge — no gap between cards).
    int x = HUB_CELL_X0 + cardCol * HUB_CARD_COLS * HUB_CELL_PITCH;
    int y = HUB_CELL_Y0 + cardRow * HUB_CARD_ROWS * HUB_CELL_PITCH;
    int w = HUB_CARD_COLS * HUB_CELL_PITCH;
    int h = HUB_CARD_ROWS * HUB_CELL_PITCH;
    // Optional enlargement by HUB_SCALE about the grid anchor (1.0 = exact fit, identity).
    double cx = HUB_ANCHOR_X + (x + w / 2.0 - HUB_ANCHOR_X) * HUB_SCALE;
    double cy = HUB_ANCHOR_Y + (y + h / 2.0 - HUB_ANCHOR_Y) * HUB_SCALE;
    int nw = (int) Math.round(w * HUB_SCALE);
    int nh = (int) Math.round(h * HUB_SCALE);
    return new Box((int) Math.round(cx - nw / 2.0), (int) Math.round(cy - nh / 2.0), nw, nh);
  }

  /** Every (vanilla) chest slot a card covers, ascending — the card's clickable footprint. */
  private static int[] hubCardSlots(int cardCol, int cardRow) {
    int[] slots = new int[HUB_CARD_COLS * HUB_CARD_ROWS];
    int n = 0;
    for (int r = 0; r < HUB_CARD_ROWS; r++) {
      for (int c = 0; c < HUB_CARD_COLS; c++) {
        slots[n++] = (cardRow * HUB_CARD_ROWS + r) * ChestGrid.COLUMNS + cardCol * HUB_CARD_COLS + c;
      }
    }
    java.util.Arrays.sort(slots);
    return slots;
  }

  /**
   * The clickable chest-slot group for each hub card, in tab order: card {@code i} is hit by a click on ANY
   * slot it covers (a {@link #HUB_CARD_COLS}×{@link #HUB_CARD_ROWS} block), so the big cards feel like
   * buttons. The blocks are disjoint by construction. {@code op} selects the operator layout (the extra
   * Settings card).
   */
  public static int[][] hubHitGroups(boolean op) {
    int[][] cards = op ? HUB_CARDS_OP : HUB_CARDS_NORMAL;
    int[][] groups = new int[cards.length][];
    for (int i = 0; i < cards.length; i++) {
      groups[i] = hubCardSlots(cards[i][0], cards[i][1]);
    }
    return groups;
  }

  /** The normal hub (scene id {@code main-hub}): six medieval button cards, transparent background. */
  public static Scene mainHub(List<Tab> tabs) {
    return hub("main-hub", tabs, HUB_CARDS_NORMAL);
  }

  /** The operator hub (scene id {@code main-hub-op}): the six cards plus the admin Settings card below. */
  public static Scene mainHubOp(List<Tab> tabs) {
    return hub("main-hub-op", tabs, HUB_CARDS_OP);
  }

  /**
   * Builds a hub overlay: one medieval button card per tab on a fully TRANSPARENT canvas in GUI px (no frame,
   * no opaque fill — the {@code chest_6} frame is painted behind it in-game; the baker emits at 768). Each
   * card carries a centred icon
   * in its upper face and a caption along its lower face.
   */
  private static Scene hub(String id, List<Tab> tabs, int[][] cards) {
    Scene.Builder b = Scene.builder(id, HUB_SIZE, HUB_SIZE);
    // Title centred on the frame's banner plaque. The plaque centre measures to source x=101 (the medieval
    // frame's banner is not centred in the 256 cell), so the text box is centred there (x = 101 - 84/2 = 59),
    // not at the old ~x99. The display font draws its own medieval caps colour, so it reads on the tan plaque.
    b.add(new Element.Text("SEXIDIUM", new Box(59, 4, 84, 10), Components.FONT_DISPLAY,
        HUB_LABEL_INK, Element.HAlign.CENTER, Element.VAlign.MIDDLE));
    int count = Math.min(tabs.size(), cards.length);
    for (int i = 0; i < count; i++) {
      Tab tab = tabs.get(i);
      Box card = hubCardBox(cards[i][0], cards[i][1]);
      b.add(new Element.Sprite(HUB_CARD_SPRITE, card, Element.Fit.STRETCH));
      // Icon centred in the card's upper (lighter) half.
      b.add(new Element.Sprite(tab.iconSpriteId(),
          new Box(card.centerX() - 9, card.y() + 3, 18, 18), Element.Fit.CONTAIN));
      // Caption along the lower face — dark ink on the tan card, full card width so it never squishes.
      b.add(new Element.Text(tab.label(), new Box(card.x() + 2, card.bottom() - 12, card.width() - 4, 9),
          Components.FONT_BODY, HUB_LABEL_INK, Element.HAlign.CENTER, Element.VAlign.MIDDLE));
    }
    return b.build();
  }

  // ----- sample data (bake + tests) -----------------------------------------------------------

  /**
   * The six normal hub cards, in {@code HubMenu.registerHubTabs} order so the baked label/icon at card
   * {@code i} matches the action the in-game adapter routes card {@code i}'s clicks to. Icons mirror the
   * runtime {@code MenuArt.ICON_*} identities (swords, emblem, book, portal, home, friends).
   */
  public static List<Tab> sampleHubTabsNormal() {
    return List.of(
        new Tab("Minigames", "icons/ui/swords_duel"),
        new Tab("Create", "item/gui_buttons/badge_emblem_gold"),
        new Tab("My Worlds", "icons/ui/book"),
        new Tab("Browse", "icons/ui/portal"),
        new Tab("Lobby", "item/system/home"),
        new Tab("Friends", "icons/ui/friends_add"));
  }

  /** The operator hub: the six normal cards plus the admin <b>Settings</b> card (the gear). */
  public static List<Tab> sampleHubTabsOp() {
    List<Tab> tabs = new ArrayList<>(sampleHubTabsNormal());
    tabs.add(new Tab("Settings", "icons/ui/gear"));
    return tabs;
  }
}
