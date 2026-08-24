package com.sexidium.core.game;

import java.util.List;

/**
 * The standardized hologram layout for a minigame mode. This is the single source of truth for what a
 * lobby NPC's floating nameplate shows when the NPC is bound to a minigame (via {@code /sx admin npc mode} or
 * the GUI) and has no manual hologram lines of its own: the mode display name, a live "playing now"
 * count, a live "queued" count, and a call to action.
 *
 * <p>The count tokens ({@code %players_<modeId>%}, {@code %queue_<modeId>%}) are rendered live by
 * {@link com.sexidium.core.world.npc.NpcManager}; this record only defines the template. A mode that wants a
 * bespoke layout supplies its own lines through {@link GameModeDescriptor}; everything else falls back to
 * {@link #standard(GameModeDescriptor)}.</p>
 */
public record MinigameHologramSpec(List<String> lines) {
  public MinigameHologramSpec {
    lines = lines == null ? List.of() : List.copyOf(lines);
  }

  // A clean strikethrough run renders as a solid divider in every font (no missing-glyph "tofu"), unlike
  // box-drawing characters which depend on the client font.
  private static final String DIVIDER = "<#3A3A40><strikethrough>                    </strikethrough>";

  /**
   * The default per-mode hologram: a modern, animated nameplate. The {@code %phase%} token is replaced
   * each refresh tick by {@link com.sexidium.core.world.npc.NpcManager} with a shifting gradient phase, so the
   * title and the call-to-action visibly shimmer; {@code %players_<id>%} / {@code %queue_<id>%} carry the
   * live counts. Only Latin-1 glyphs (« » ●) are used so it reads correctly with the vanilla font, while a
   * server-supplied resource-pack font (config {@code lobby.npcs.hologram-font}) is layered on top.
   */
  public static MinigameHologramSpec standard(GameModeDescriptor descriptor) {
    String modeId = descriptor.modeId();
    String icon = icon(modeId);
    return new MinigameHologramSpec(List.of(
        DIVIDER,
        // Single leading glyph only: a flanked, scaled, bold title (e.g. "Gather and Duel") overruns the
        // text-display line-width and wraps, breaking the card. One icon keeps it on a single line.
        "<gradient:#F857A6:#FF5858:%phase%><bold>" + icon + " " + descriptor.displayName() + "</bold></gradient>",
        " ",
        "<#55FF55>●</#55FF55> <gray>Playing now</gray>  <white><bold>%players_" + modeId + "%</bold></white>",
        "<#55FFFF>●</#55FFFF> <gray>In queue</gray>  <white><bold>%queue_" + modeId + "%</bold></white>",
        " ",
        "<gradient:#43E97B:#38F9D7:%phase%><bold>» CLICK TO PLAY «</bold></gradient>",
        DIVIDER
    ));
  }

  /** A per-mode Latin-1 accent glyph; falls back to a neutral marker for unknown modes. */
  private static String icon(String modeId) {
    return switch (modeId) {
      case "race" -> "»";
      case "gather" -> "¤";
      case "tntwar" -> "×";
      case "combat" -> "†";
      case "fugitive" -> "‹";
      default -> "•";
    };
  }
}
