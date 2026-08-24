package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.game.hud.surface.HudValues;
import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Renders a surface's rows for one viewer, into the flat {@code variable -> text} map BetterHud reads
 * from that player's own {@code HudPlayer#getVariableMap()}.
 *
 * <h2>Rendered per viewer, which is the point</h2>
 * The values BetterHud draws are plain strings, so on the face of it they cannot be localized. They
 * can, because the map is per player: the same row is rendered separately for each viewer, in that
 * viewer's language, and written only into their map. The generated yml holds no words at all.
 *
 * <h2>Why plain text, not MiniMessage</h2>
 * Our templates are MiniMessage and BetterHud would draw the tags literally. Deserializing and then
 * flattening gives it the words without the markup; colour comes from the generated layout's own
 * {@code color} key. Some styling is genuinely lost, which is the honest trade for a surface that
 * draws through a font atlas rather than through the chat component pipeline.
 */
final class BetterHudRows {
  private final Supplier<MessageService> messageService;

  BetterHudRows(Supplier<MessageService> messageService) {
    this.messageService = messageService;
  }

  /**
   * Every drawable row of {@code spec}, rendered for {@code viewer}, keyed by its variable name.
   *
   * <p>A row that renders to nothing still gets an entry — an empty string rather than an absent key.
   * A key BetterHud cannot find in the map renders as the literal {@code <none>}, so "no value yet" has
   * to be published as a blank rather than left out.</p>
   *
   * <p>Takes a resolved {@link Language} rather than the viewer, because this runs on the publisher
   * thread and resolving a language means reading the player's locale through Bukkit.</p>
   *
   * @param nowMillis the wall clock this frame is being rendered at, which is what an animated row
   *                  measures its own age against. Passed in rather than read here so every viewer of
   *                  a surface animates off the same instant — a per-row {@code currentTimeMillis()}
   *                  would let two players' pulses drift apart by however long the pass took.
   */
  Map<String, String> render(HudSurfaceSpec spec, HudValues values, Language language, long nowMillis) {
    Map<String, String> rendered = new LinkedHashMap<>();
    for (HudElement element : spec.elements()) {
      String key = element.key();
      if (key == null) {
        continue;
      }
      String line = flatten(values.render(element), language);
      if (element instanceof HudElement.PulseRow pulse) {
        publishPulse(rendered, spec, pulse, line, nowMillis - values.changedAt(key));
        continue;
      }
      rendered.put(BetterHudAssetCompiler.variableKey(spec.id(), key), line);
    }
    return rendered;
  }

  /**
   * Puts the line in the one frame variable whose size is right for this instant, and blanks the rest.
   *
   * <p>Every frame is written on every pass, never only the two that changed. The layout draws all of
   * them unconditionally, so a frame left holding a stale line would draw a second copy of the number
   * at a second size — and BetterHud renders a variable it cannot find as the literal {@code <none>},
   * so "leave it out" is not the same as "blank it".</p>
   */
  private static void publishPulse(
      Map<String, String> rendered,
      HudSurfaceSpec spec,
      HudElement.PulseRow pulse,
      String line,
      long millisSinceChange
  ) {
    int active = BetterHudAssetCompiler.pulseFrame(pulse, millisSinceChange);
    for (int frame = 0; frame < BetterHudAssetCompiler.PULSE_FRAMES; frame++) {
      rendered.put(
          BetterHudAssetCompiler.frameVariableKey(spec.id(), pulse.key(), frame),
          frame == active ? line : "");
    }
  }

  /** The variable names this spec owns, for taking them back off a player who stopped viewing. */
  static java.util.List<String> keys(HudSurfaceSpec spec) {
    java.util.List<String> keys = new java.util.ArrayList<>();
    for (HudElement element : spec.elements()) {
      if (element.key() == null) {
        continue;
      }
      if (element instanceof HudElement.PulseRow pulse) {
        // One name per frame: a pulse owns all of them, so all of them have to be taken back — a
        // leftover frame variable is a number frozen on screen at whatever size it stopped at.
        for (int frame = 0; frame < BetterHudAssetCompiler.PULSE_FRAMES; frame++) {
          keys.add(BetterHudAssetCompiler.frameVariableKey(spec.id(), pulse.key(), frame));
        }
        continue;
      }
      keys.add(BetterHudAssetCompiler.variableKey(spec.id(), element.key()));
    }
    return java.util.List.copyOf(keys);
  }

  /**
   * The viewer's language, read on the SERVER thread by the caller and cached — {@code Player#locale()}
   * is a Bukkit read, and the publisher that consumes the result does not run there.
   */
  Language languageOf(PlayerAdapter viewer) {
    try {
      MessageService service = messageService.get();
      return service == null || viewer == null ? Language.EN : service.language(viewer);
    } catch (RuntimeException | LinkageError ignored) {
      return Language.EN;
    }
  }

  private String flatten(LocalizedText line, Language language) {
    if (line == null) {
      return "";
    }
    try {
      MessageService service = messageService.get();
      if (service == null) {
        return "";
      }
      String mini = service.renderMini(language == null ? Language.EN : language, line);
      return PlainTextComponentSerializer.plainText()
          .serialize(MiniMessage.miniMessage().deserialize(mini));
    } catch (RuntimeException | LinkageError ignored) {
      // A malformed template costs one row, not the surface: every other row still publishes.
      return "";
    }
  }
}
