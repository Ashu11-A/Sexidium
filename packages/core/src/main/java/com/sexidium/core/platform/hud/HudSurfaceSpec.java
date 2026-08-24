package com.sexidium.core.platform.hud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.model.HudAnchor;
import com.sexidium.core.platform.model.ItemKey;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A declaration of one on-screen surface: its id, where it is pinned, and what is drawn on it. Handed
 * to a {@link com.sexidium.core.platform.HudDriver}, which decides how — and whether — to render it.
 *
 * <h2>Why this exists</h2>
 * The seam it replaces let a caller name a layout id and push opaque strings into it. Everything that
 * made the readout a readout — the rows, their labels, their font scale, their order — lived in yml
 * files hand-installed beside the plugin. So a second consumer meant authoring three more yml files,
 * and no consumer's text could ever be translated. A spec moves all of that into Java, where the
 * driver can compile it to whatever the platform actually wants and the sidebar can render the very
 * same declaration when the platform wants nothing.
 *
 * <h2>Namespacing</h2>
 * Callers pass a bare id ({@code "deathresets"}); the spec stores {@code sexidium_deathresets}. That
 * prefix is not decoration — it is the bound on every destructive sweep a driver makes over the
 * platform's objects, and the reason a reconcile pass can never take down an operator's own HUD.
 *
 * <h2>Geometry is the driver's business</h2>
 * A spec says what is drawn and in what order, never where in pixels. See {@link HudElement}.
 */
public record HudSurfaceSpec(
    String id,
    HudAnchor anchor,
    HudSurfaceKind kind,
    List<HudElement> elements,
    Duration popupDuration
) {
  /** Prefixed onto every surface id, and the bound on every sweep a driver makes. */
  public static final String NAMESPACE = "sexidium_";

  private static final Duration DEFAULT_POPUP_DURATION = Duration.ofSeconds(3);

  public HudSurfaceSpec {
    id = qualify(id);
    anchor = anchor == null ? HudAnchor.TOP_LEFT : anchor;
    kind = kind == null ? HudSurfaceKind.PERSISTENT : kind;
    elements = elements == null ? List.of() : List.copyOf(elements);
    popupDuration = popupDuration == null ? DEFAULT_POPUP_DURATION : popupDuration;
    requireUniqueKeys(id, elements);
  }

  /** A surface that stays on screen until it is hidden. */
  public static Builder persistent(String id) {
    return new Builder(id, HudSurfaceKind.PERSISTENT);
  }

  /** A surface that shows itself once and expires. */
  public static Builder popup(String id) {
    return new Builder(id, HudSurfaceKind.POPUP);
  }

  /** The element a runtime value under {@code key} belongs to, or null if the spec declares no such key. */
  public HudElement element(String key) {
    if (key == null) {
      return null;
    }
    String normalized = key.trim().toLowerCase(Locale.ROOT);
    for (HudElement element : elements) {
      if (normalized.equals(element.key())) {
        return element;
      }
    }
    return null;
  }

  /**
   * Whether anything here animates between pushes.
   *
   * <p>Asked by a driver to decide how often it has to repaint. Every other surface can be published
   * on the shared one-second cadence, because nothing about it moves until a value changes; an
   * animated one has to be republished every tick for as long as it is open, and a driver that
   * repainted everything at that rate would be paying an animation's cost for every static readout on
   * the server.</p>
   */
  public boolean animated() {
    return elements.stream().anyMatch(element -> element instanceof HudElement.PulseRow);
  }

  /** Whether every element this spec declares is drawable by a driver with these capabilities. */
  public boolean drawableBy(Set<HudCapability> capabilities) {
    if (capabilities == null) {
      return false;
    }
    if (kind == HudSurfaceKind.POPUP && !capabilities.contains(HudCapability.POPUP)) {
      return false;
    }
    for (HudElement element : elements) {
      HudCapability required = switch (element) {
        case HudElement.TextRow ignored -> HudCapability.TEXT;
        // TEXT, not a capability of its own. A driver that cannot animate still draws the row at its
        // resting size, which is a readout with less flourish rather than a missing readout — so
        // demanding an ANIMATION capability here would take the whole surface away from every driver
        // that can say what it needs to say perfectly well.
        case HudElement.PulseRow ignored -> HudCapability.TEXT;
        case HudElement.Bar ignored -> HudCapability.BAR;
        case HudElement.Icon ignored -> HudCapability.ICON;
        case HudElement.Spacer ignored -> null;
      };
      if (required != null && !capabilities.contains(required)) {
        return false;
      }
    }
    return true;
  }

  private static String qualify(String id) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("A HUD surface needs an id.");
    }
    String normalized = id.trim().toLowerCase(Locale.ROOT);
    if (normalized.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("HUD surface id '" + id + "' cannot contain whitespace.");
    }
    return normalized.startsWith(NAMESPACE) ? normalized : NAMESPACE + normalized;
  }

  private static void requireUniqueKeys(String id, List<HudElement> elements) {
    Set<String> seen = new HashSet<>();
    for (HudElement element : elements) {
      String key = element.key();
      if (key != null && !seen.add(key)) {
        throw new IllegalArgumentException(
            "Surface '" + id + "' declares the key '" + key + "' twice; a pushed value would be"
                + " ambiguous between the two elements.");
      }
    }
  }

  /** Assembles a spec. Elements are drawn in the order they are added. */
  public static final class Builder {
    private final String id;
    private final HudSurfaceKind kind;
    private final List<HudElement> elements = new ArrayList<>();
    private HudAnchor anchor = HudAnchor.TOP_LEFT;
    private Duration popupDuration = DEFAULT_POPUP_DURATION;

    private Builder(String id, HudSurfaceKind kind) {
      this.id = id;
      this.kind = kind;
    }

    public Builder anchor(HudAnchor value) {
      this.anchor = value;
      return this;
    }

    /** How long a {@link HudSurfaceKind#POPUP} stays up. Ignored by a persistent surface. */
    public Builder duration(Duration value) {
      this.popupDuration = value;
      return this;
    }

    public Builder text(String key, LocalizedText template) {
      return element(new HudElement.TextRow(key, template));
    }

    public Builder text(String key, LocalizedText template, HudAlign align, double scale) {
      return element(new HudElement.TextRow(key, template, align, scale));
    }

    /** As above, in an explicit colour for the drivers that cannot read the template's own tags. */
    public Builder text(String key, LocalizedText template, HudAlign align, double scale, HudColor color) {
      return element(new HudElement.TextRow(key, template, align, scale, color));
    }

    /**
     * A row that pops to {@code peakScale} whenever its value changes and eases back to {@code scale}.
     * See {@link HudElement.PulseRow} — the animation is triggered by the value, never by a call.
     */
    public Builder pulse(String key, LocalizedText template, double scale, double peakScale) {
      return element(new HudElement.PulseRow(key, template, scale, peakScale));
    }

    public Builder pulse(
        String key, LocalizedText template, HudAlign align, double scale, double peakScale, Duration settle) {
      return element(new HudElement.PulseRow(key, template, align, scale, peakScale, settle));
    }

    /** As above, in an explicit colour for the drivers that cannot read the template's own tags. */
    public Builder pulse(
        String key,
        LocalizedText template,
        HudAlign align,
        double scale,
        double peakScale,
        Duration settle,
        HudColor color
    ) {
      return element(new HudElement.PulseRow(key, template, align, scale, peakScale, settle, color));
    }

    public Builder bar(String key, LocalizedText label) {
      return element(new HudElement.Bar(key, label));
    }

    public Builder bar(String key, LocalizedText label, int widthPixels, BarStyle style) {
      return element(new HudElement.Bar(key, label, widthPixels, style));
    }

    public Builder icon(ItemKey icon) {
      return element(new HudElement.Icon(icon));
    }

    public Builder icon(String key, ItemKey icon, double scale) {
      return element(new HudElement.Icon(key, icon, scale));
    }

    public Builder spacer(int pixels) {
      return element(new HudElement.Spacer(pixels));
    }

    public Builder element(HudElement element) {
      if (element != null) {
        elements.add(element);
      }
      return this;
    }

    public HudSurfaceSpec build() {
      return new HudSurfaceSpec(id, anchor, kind, elements, popupDuration);
    }
  }
}
