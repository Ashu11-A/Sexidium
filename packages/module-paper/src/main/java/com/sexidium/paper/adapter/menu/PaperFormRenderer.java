package com.sexidium.paper.adapter.menu;

import com.sexidium.core.Branding;
import com.sexidium.core.menu.MenuButton;
import com.sexidium.core.menu.MenuContext;
import com.sexidium.core.menu.MenuForms;
import com.sexidium.core.menu.MenuView;
import com.sexidium.paper.adapter.player.PaperGeyser;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import org.bukkit.plugin.java.JavaPlugin;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Renders a {@link MenuView} as a <b>native Bedrock Cumulus form</b> for a Geyser/Floodgate player —
 * the touch-friendly UI for mobile/console clients, who cannot hover items, distinguish click types,
 * or open chests in void/lobby space (see {@code docs/graphical-interfaces-report.md}).
 *
 * <p>This class statically references the Cumulus form API, which is an optional runtime softdepend
 * (never shaded; provided at runtime by Floodgate or Geyser). {@link PaperMenuAdapter} therefore only
 * ever loads/uses it behind a {@link com.sexidium.paper.adapter.player.PaperGeyser#bedrockUiAvailable()}
 * guard and a {@code try/catch}, so a server without a Bedrock backend simply never reaches this code
 * and the chest renderer is used instead. The built form is dispatched through
 * {@link com.sexidium.paper.adapter.player.PaperGeyser#sendForm(java.util.UUID, Object)}, which uses
 * Floodgate when present and the Geyser API otherwise.</p>
 *
 * <h2>Mapping</h2>
 * <p>A chest {@code MenuView} is a sparse slot grid; a form is a vertical list. {@link MenuForms}
 * flattens it: clickable buttons become form buttons (top-to-bottom in slot order), decorative
 * labels become the form's body text. Each form button's index is its position in that list, so the
 * tap routes straight back to the {@link MenuButton#onClick()} the chest would have fired — re-using
 * all of {@code MenuService}'s navigation unchanged (a handler re-opens the next menu, which
 * re-renders as a new form). {@code MenuService} is already designed for single-tap interaction (no
 * shift/right-click, dual-tap confirms), so the projection is lossless.</p>
 *
 * <h2>Mobile polish</h2>
 * <ul>
 *   <li><b>Plain text, not legacy colour codes.</b> The Bedrock client renders only a handful of
 *       legacy {@code §} codes and shows {@code §x} hex sequences as garbage — exactly what Sexidium's
 *       MiniMessage gradients/hex titles would produce. So all form text is flattened to clean plain
 *       text; readable beats broken on a phone.</li>
 *   <li><b>Face icons on roster buttons.</b> Friend/party/picker entries carry a head owner; we attach
 *       that player's face as a button image by URL, so mobile rosters show who's who. URL images need
 *       no Bedrock resource pack; if the client blocks them the button still shows its text.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * <p>Cumulus invokes the response handler off the main server thread. Every {@code onClick} is
 * therefore re-scheduled onto the main thread before it touches game state.</p>
 */
final class PaperFormRenderer {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

  private final JavaPlugin plugin;

  PaperFormRenderer(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /**
   * Builds and sends the form. Returns {@code false} when the view has no clickable buttons (nothing a
   * form could meaningfully show) so the caller can fall back to the chest; otherwise returns whatever
   * Floodgate reports for the send.
   */
  boolean open(PaperPlayerAdapter player, MenuView view) {
    List<Map.Entry<Integer, MenuButton>> actions = MenuForms.actions(view);
    if (actions.isEmpty()) {
      return false;
    }

    SimpleForm.Builder builder = SimpleForm.builder().title(plain(view.title()));
    String body = body(view);
    if (!body.isEmpty()) {
      builder.content(body);
    }
    for (Map.Entry<Integer, MenuButton> entry : actions) {
      MenuButton button = entry.getValue();
      String text = buttonText(button);
      // A head-owner button (friends/party/picker roster) shows that player's face on mobile.
      if (button.headOwner() != null) {
        builder.button(text, FormImage.Type.URL, avatarUrl(button.headOwner()));
      } else {
        builder.button(text);
      }
    }
    // The clicked button id is the index into the ordered action list (MenuForms keeps slot order).
    builder.validResultHandler(response -> {
      int id = response.clickedButtonId();
      if (id >= 0 && id < actions.size()) {
        dispatch(player, actions.get(id).getValue().onClick());
      }
    });
    builder.closedOrInvalidResultHandler(() -> { /* player dismissed the form — nothing to do */ });

    // Sent via Floodgate when present, else the Geyser API directly (standalone Geyser, no Floodgate).
    return PaperGeyser.sendForm(player.uniqueId(), builder.build());
  }

  /** Re-schedules a button handler onto the player's region; every tap is a plain LEFT click on Bedrock. */
  private void dispatch(PaperPlayerAdapter player, Consumer<MenuContext> handler) {
    if (handler == null) {
      return;
    }
    // The handler runs player-facing menu actions, so it must execute on the region owning the player
    // (Folia-safe; the main thread on regular Paper).
    player.handle().getScheduler().run(plugin, scheduledTask -> {
      try {
        handler.accept(new MenuContext(player, MenuContext.ClickType.LEFT));
      } catch (RuntimeException exception) {
        plugin.getLogger().warning(Branding.label(plugin.getConfig().getString(Branding.LABEL_PATH,
            Branding.DEFAULT_LABEL)) + " form click handler failed: " + exception.getMessage());
      }
    }, null);
  }

  private String buttonText(MenuButton button) {
    String text = plain(button.name());
    List<String> lore = button.lore();
    if (lore != null && !lore.isEmpty()) {
      String first = plain(lore.get(0));
      if (!first.isBlank()) {
        text = text + "\n" + first;
      }
    }
    return text;
  }

  /** Joins the view's decorative labels (names + lore) into the form's body text. */
  private String body(MenuView view) {
    StringBuilder builder = new StringBuilder();
    for (MenuButton label : MenuForms.labels(view)) {
      appendLine(builder, plain(label.name()));
      for (String loreLine : label.lore()) {
        appendLine(builder, plain(loreLine));
      }
    }
    return builder.toString();
  }

  /** mc-heads renders a player's face from their UUID; Cumulus fetches it by URL, no Bedrock pack needed. */
  private static String avatarUrl(UUID owner) {
    return "https://mc-heads.net/avatar/" + owner + "/64";
  }

  private static void appendLine(StringBuilder builder, String line) {
    if (line == null || line.isBlank()) {
      return;
    }
    if (builder.length() > 0) {
      builder.append('\n');
    }
    builder.append(line);
  }

  /**
   * Flattens a MiniMessage string to plain text for the Bedrock client. Stripping colour/format avoids
   * the {@code §x} hex garbage Bedrock would otherwise show for Sexidium's gradient titles.
   */
  static String plain(String miniMessage) {
    if (miniMessage == null || miniMessage.isBlank()) {
      return "";
    }
    return PLAIN.serialize(MINI_MESSAGE.deserialize(miniMessage)).strip();
  }
}
