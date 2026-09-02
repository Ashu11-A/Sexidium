package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.platform.hud.HudAlign;
import com.sexidium.core.platform.hud.HudColor;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceKind;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.core.platform.model.HudAnchor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link HudSurfaceSpec} into the BetterHud yml that draws it.
 *
 * <h2>Why a compiler at all</h2>
 * BetterHud 2.0.0 has no programmatic way to create a hud, a popup, a layout or a text — its
 * {@code HudManager}, {@code PopupManager} and {@code CompassManager} are read-only lookups, and
 * {@code TextManager} is an empty interface. Objects come from yml on disk and nowhere else. So a
 * driver that lets callers declare surfaces in Java has exactly one way to honour them: write the yml
 * itself and ask BetterHud to reload. That is this class.
 *
 * <h2>Pure by design</h2>
 * No I/O, no Bukkit, no BetterHud types — a spec in, a map of {@code relative path -> file contents}
 * out. All the geometry knowledge that used to live as prose comments in a hand-authored layout file
 * lives here instead, where a test can hold it to account:
 *
 * <ul>
 *   <li><b>Scale.</b> BetterHud's own {@code font.yml} uses {@code scale: 16}, twice vanilla's 8px, so
 *       a layout asking for scale 1 renders at double chat size. Our scale 1.0 means "normal", and
 *       every emitted scale is halved to get there.</li>
 *   <li><b>Sign of the y axis.</b> Positive y is DOWN in a layout. A fourth row is {@code y: 30}, not
 *       {@code y: -10} — the mistake the old file warned about in a comment because nothing else could.</li>
 *   <li><b>Row pitch.</b> 10px per row at scale 1.0, which clears an 8px glyph with room to spare, and
 *       scales with the row.</li>
 * </ul>
 *
 * <h2>How a size animation is compiled, given BetterHud cannot animate a size</h2>
 * BetterHud's layout animations are {@code x-equation}, {@code y-equation} and {@code opacity-equation}
 * — position and transparency, evaluated per frame. There is no scale equation, and {@code scale} is a
 * fixed property of a text object read once at parse time. So a growing-and-settling number cannot be
 * expressed as one animated row.
 *
 * <p>A {@link HudElement.PulseRow} is therefore compiled into {@link #PULSE_FRAMES} <em>stacked</em>
 * text rows, one per size along the eased curve, each reading its own variable. Exactly one of those
 * variables holds the line at any moment and the rest hold the empty string, so exactly one size is
 * drawn — and the animation is nothing more than the publisher moving the line from one variable to
 * the next. That is a mechanism the driver already had; the alternative (BetterHud's {@code conditions}
 * blocks, one per frame) would have bound the whole thing to placeholder-resolution semantics that are
 * fixed at parse time, which is the same trap documented under "Why the variable map" below.</p>
 *
 * <p>Each frame is lifted by half the height it gained, so the number swells around a fixed centre
 * instead of growing downwards out of its own top edge.</p>
 *
 * <h2>Colour comes from the line, with the declaration as the floor</h2>
 * Each text object sets {@code use-legacy-format: true} and {@code legacy-serializer: ampersand}, and
 * {@link BetterHudRows} publishes ampersand-coded lines. {@code TextRenderer#parseToComponent} resolves
 * the placeholder and then runs the legacy serializer over the result, so the runs of colour a template
 * declared survive into the atlas. {@code color} is still written and still matters: it is what a span
 * carrying no code of its own is drawn in. Decorations are a different story — the renderer reads
 * {@code color()} and nothing else, so a strikethrough is parsed and thrown away.
 *
 * <h2>One variable per row</h2>
 * Every row's pattern is a single {@code [string:sexidium_<surface>_<key>]} lookup into the viewer's
 * own {@code HudPlayer#getVariableMap()}, which the driver fills with the WHOLE rendered line — label
 * and value together, already translated into that viewer's language. The yml carries no words at all,
 * which is what makes the readout translatable; a label baked into an operator-owned file is a string
 * {@code MessageService} can never reach.
 *
 * <h2>Why the variable map and not a registered placeholder</h2>
 * A custom placeholder would read better, and cannot work. BetterHud rebuilds its placeholder
 * containers at the START of every reload and parses layouts afterwards, binding each pattern to the
 * resolver it finds AT PARSE TIME — so a third-party registration is wiped before the layouts that
 * would use it are read, and re-registering afterwards binds nothing. Worse, BetterHud finishes
 * enabling AFTER a plugin that softdepends on it, so the first parse happens before we could register
 * at all. The variable map has no such window: it is read per player, per frame, long after parsing.
 */
public final class BetterHudAssetCompiler {
  /** The shared text (font) object every generated layout draws with. */
  public static final String FONT_ID = HudSurfaceSpec.NAMESPACE + "font";

  /** Subdirectory of each BetterHud object folder that this driver owns outright. */
  public static final String MANAGED_DIR = "sexidium";

  /**
   * How many discrete sizes a {@link HudElement.PulseRow} is compiled into.
   *
   * <p>Eight, against a settle of a few hundred milliseconds and a publisher running every tick, puts
   * a size change roughly every other frame — past the point where the eye reads steps rather than
   * motion, and still only eight text objects for BetterHud to parse and skip. Raising it buys nothing
   * a client repainting at 20 Hz can show.</p>
   */
  public static final int PULSE_FRAMES = 8;

  /** Vertical distance between consecutive rows at scale 1.0, in pixels. */
  private static final int ROW_PITCH = 10;
  /**
   * BetterHud's font is configured at twice vanilla's glyph height, so "normal size" is half scale.
   * See the class doc — this is the constant the old layout file could only describe in a comment.
   */
  private static final double FONT_CORRECTION = 0.5d;
  /**
   * Inset from the screen edge, in real screen pixels, so a corner readout does not touch the very edge
   * of the window.
   *
   * <p>These ARE screen pixels rather than an offset from something else, which is worth stating because
   * the machinery underneath makes it look otherwise. BetterHud draws every hud inside a boss bar, and
   * its shader recovers screen space by subtracting a constant that cancels out where that bar sits:
   * once it does, a hud anchored at {@code gui: {x: 0, y: 0}} measures its {@code pixel} offsets from the
   * top-left of the window. So 10 and 10 mean ten pixels down and ten across — provided the bar the
   * payload rides really is on the line the shader assumed, which is what the {@code merge-boss-bar}
   * pin in {@link BetterHudAssetStore} exists to guarantee.</p>
   */
  private static final int EDGE_INSET_X = 10;
  private static final int EDGE_INSET_Y = 10;

  private BetterHudAssetCompiler() {
  }

  /** The name of the layout object generated for a surface. */
  public static String layoutId(HudSurfaceSpec spec) {
    return spec.id() + "_layout";
  }

  /**
   * The per-player variable a row's rendered line is published under.
   *
   * <p>One flat token, because that is what BetterHud's string container falls back to: an unknown
   * placeholder name is looked up in {@code HudPlayer#getVariableMap()}, and a name it cannot find
   * there renders as {@code <none>}.</p>
   */
  public static String variableKey(String surfaceId, String key) {
    return surfaceId + "_" + key;
  }

  /**
   * The variable one FRAME of a pulse reads from. Every frame of a row draws the same line; which one
   * is non-empty at a given moment is what makes the size appear to move.
   */
  public static String frameVariableKey(String surfaceId, String key, int frame) {
    return variableKey(surfaceId, key) + "_f" + frame;
  }

  /** The layout pattern for one row — a bare variable lookup, resolved per player at draw time. */
  public static String placeholder(String surfaceId, String key) {
    return "[string:" + variableKey(surfaceId, key) + "]";
  }

  /**
   * Which frame of a pulse is the one holding the line, given how long ago its value changed.
   *
   * <p>The eased clock, not the plain one: the frames are evenly spaced in SIZE (see
   * {@link HudElement.PulseRow#scaleAt}), so all of the easing has to happen in which one is picked
   * when. Stepping through them linearly would give a number that shrank at a constant rate.</p>
   */
  public static int pulseFrame(HudElement.PulseRow row, long millisSinceChange) {
    return (int) Math.round(row.easedProgress(millisSinceChange) * (PULSE_FRAMES - 1));
  }

  /**
   * The shared text object. BetterHud has no implicit default text, so a layout naming one that is not
   * configured fails to load — and takes the hud naming that layout down with it.
   */
  public static String fontYml() {
    return header("The font every generated Sexidium layout draws with.")
        + FONT_ID + ":\n"
        + "  merge-default-bitmap: true\n"
        + "  use-unifont: true\n"
        + "  include: []\n";
  }

  /**
   * Compiles one surface.
   *
   * @return {@code relative path -> file contents}, where the path is relative to the BetterHud plugin
   *     folder. Empty when the spec has nothing this driver can draw.
   */
  public static Map<String, String> compile(HudSurfaceSpec spec) {
    Map<String, String> files = new LinkedHashMap<>();
    if (spec == null) {
      return files;
    }
    String bare = bareName(spec.id());
    String layout = layoutYml(spec);
    if (layout == null) {
      return files;
    }
    files.put("layouts/" + MANAGED_DIR + "/" + bare + ".yml", layout);
    files.put(objectFolder(spec) + "/" + MANAGED_DIR + "/" + bare + ".yml", objectYml(spec));
    return files;
  }

  private static String objectFolder(HudSurfaceSpec spec) {
    return spec.kind() == HudSurfaceKind.POPUP ? "popups" : "huds";
  }

  /** The rows, stacked downward from the anchor. Null when no element compiles to a drawable row. */
  private static String layoutYml(HudSurfaceSpec spec) {
    StringBuilder texts = new StringBuilder();
    int row = 0;
    int offsetY = 0;
    for (HudElement element : spec.elements()) {
      if (element instanceof HudElement.Spacer spacer) {
        offsetY += spacer.pixels();
        continue;
      }
      // Icons need image assets this driver does not generate, and it says so in its capabilities —
      // a spec containing one is never handed here. Skipped rather than trusted, because a null key
      // would compile into a placeholder that silently resolves to nothing.
      if (element.key() == null) {
        continue;
      }
      if (element instanceof HudElement.PulseRow pulse) {
        for (int frame = 0; frame < PULSE_FRAMES; frame++) {
          double frameScale = pulse.scaleAt((double) frame / (double) (PULSE_FRAMES - 1));
          row++;
          appendText(texts, row,
              "[string:" + frameVariableKey(spec.id(), pulse.key(), frame) + "]",
              alignOf(element),
              colorOf(element),
              frameScale,
              // Half the height it gained, taken back off the top, so the row swells around its own
              // centre rather than growing downwards. Larger than resting means a NEGATIVE offset.
              offsetY + (int) Math.round(ROW_PITCH * (pulse.scale() - frameScale) / 2.0d));
        }
        offsetY += Math.max(1, (int) Math.round(ROW_PITCH * pulse.scale()));
        continue;
      }
      double scale = scaleOf(element);
      row++;
      appendText(texts, row, placeholder(spec.id(), element.key()),
          alignOf(element), colorOf(element), scale, offsetY);
      offsetY += Math.max(1, (int) Math.round(ROW_PITCH * scale));
    }
    if (row == 0) {
      return null;
    }
    return header("Generated from a HudSurfaceSpec. Edits here are overwritten on the next boot;"
        + " change the declaration in Java instead.")
        + layoutId(spec) + ":\n"
        + "  texts:\n"
        + texts;
  }

  /** One text object of a layout. The only place a {@code scale} is written, so the correction is too. */
  private static void appendText(
      StringBuilder texts, int index, String pattern, String align, String color, double scale, int offsetY) {
    texts.append("    ").append(index).append(":\n")
        .append("      name: ").append(FONT_ID).append('\n')
        .append("      pattern: \"").append(pattern).append("\"\n")
        .append("      align: ").append(align).append('\n')
        .append("      scale: ").append(number(scale * FONT_CORRECTION)).append('\n')
        // The whole row's colour, because this renderer draws through a font atlas and the template's
        // MiniMessage was flattened away before BetterHud ever saw it (see BetterHudRows). The
        // declaration names it; a declaration that names none still says white, as every row used to.
        .append("      color: \"").append(color).append("\"\n")
        // Makes BetterHud parse the published line's ampersand codes instead of taking it as one flat
        // string, which is what lets ONE row carry more than one colour — a green tick beside a dimmed
        // label. Set per text object rather than left to BetterHud's global config, because that config
        // belongs to the operator and this is a promise the driver has to be able to keep on its own.
        // `color` above is not redundant: it is what an uncoloured span falls back to, which is every
        // span in every template that declares no colour of its own.
        .append("      use-legacy-format: true\n")
        .append("      legacy-serializer: ampersand\n")
        .append("      x: 0\n")
        // Positive y is DOWN. See the class doc.
        .append("      y: ").append(offsetY).append('\n');
  }

  /** The hud (or popup) object that places the layout on screen. */
  private static String objectYml(HudSurfaceSpec spec) {
    HudAnchor anchor = spec.anchor();
    boolean center = anchor == HudAnchor.CENTER;
    boolean bottom = anchor == HudAnchor.BOTTOM_LEFT || anchor == HudAnchor.BOTTOM_RIGHT;
    boolean right = anchor == HudAnchor.TOP_RIGHT || anchor == HudAnchor.BOTTOM_RIGHT;
    // `gui` is a percentage of the window, clamped by BetterHud to 0..100 — so the middle is 50/50 and
    // needs no inset at all: a centred surface is not measured from an edge.
    int guiX = center ? 50 : (right ? 100 : 0);
    int guiY = center ? 50 : (bottom ? 100 : 0);
    int pixelX = center ? 0 : (right ? -EDGE_INSET_X : EDGE_INSET_X);
    // Rows always stack downward, so a bottom-anchored block has to start its own height above the
    // edge or it would run off the screen — and a centred one has to start HALF its height above the
    // middle, or it would hang below the middle rather than straddle it.
    int pixelY = center
        ? -(height(spec) / 2)
        : (bottom ? -(EDGE_INSET_Y + height(spec)) : EDGE_INSET_Y);

    StringBuilder builder = new StringBuilder();
    builder.append(header("Generated from a HudSurfaceSpec. Deliberately NOT listed in BetterHud's"
            + " own default-hud: the driver decides per player who is wearing this."))
        .append(spec.id()).append(":\n");
    if (spec.kind() == HudSurfaceKind.POPUP) {
      builder.append("  duration: ").append(Math.max(1L, spec.popupDuration().toSeconds() * 20L)).append('\n')
          .append("  unique: true\n");
    }
    builder.append("  layouts:\n")
        .append("    1:\n")
        .append("      name: ").append(layoutId(spec)).append('\n')
        .append("      gui: { x: ").append(guiX).append(", y: ").append(guiY).append(" }\n")
        .append("      pixel: { x: ").append(pixelX).append(", y: ").append(pixelY).append(" }\n");
    return builder.toString();
  }

  /** Total drawn height of a surface, for anchoring a bottom-pinned block. */
  private static int height(HudSurfaceSpec spec) {
    int total = 0;
    for (HudElement element : spec.elements()) {
      if (element instanceof HudElement.Spacer spacer) {
        total += spacer.pixels();
        continue;
      }
      if (element.key() == null) {
        continue;
      }
      total += Math.max(1, (int) Math.round(ROW_PITCH * scaleOf(element)));
    }
    return total;
  }

  /** A pulse measures as its RESTING size: that is the space it occupies between animations. */
  private static double scaleOf(HudElement element) {
    return switch (element) {
      case HudElement.TextRow textRow -> textRow.scale();
      case HudElement.PulseRow pulseRow -> pulseRow.scale();
      default -> 1.0d;
    };
  }

  private static String alignOf(HudElement element) {
    HudAlign align = switch (element) {
      case HudElement.TextRow textRow -> textRow.align();
      case HudElement.PulseRow pulseRow -> pulseRow.align();
      default -> HudAlign.LEFT;
    };
    return align.name().toLowerCase(Locale.ROOT);
  }

  /**
   * The colour BetterHud draws a row in.
   *
   * <p>Read from the declaration rather than from the template, and that is the whole point of it
   * existing: {@link BetterHudRows} flattens the template to plain text, so every {@code <red>} the
   * lang file carries is gone by the time this renderer sees the line. A row whose declaration says
   * nothing is white, which is what every row got before.</p>
   */
  private static String colorOf(HudElement element) {
    HudColor color = element.color();
    return (color == null ? HudColor.DEFAULT : color).id();
  }

  /** The surface id without the shared namespace, so generated file names read as what they are. */
  private static String bareName(String id) {
    return id.startsWith(HudSurfaceSpec.NAMESPACE) ? id.substring(HudSurfaceSpec.NAMESPACE.length()) : id;
  }

  private static String header(String note) {
    return "# Generated by Sexidium — do not edit.\n# " + note + "\n\n";
  }

  /** Emits a scale without a trailing {@code .0}, which BetterHud reads fine but humans read badly. */
  private static String number(double value) {
    if (value == Math.rint(value)) {
      return String.valueOf((long) value);
    }
    return String.valueOf(Math.round(value * 1000.0d) / 1000.0d);
  }
}
