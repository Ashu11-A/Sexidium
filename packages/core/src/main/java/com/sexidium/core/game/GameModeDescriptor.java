package com.sexidium.core.game;

import java.util.List;

/**
 * The single source of truth for a game mode's player-facing metadata: its display name, minimum
 * players, the item used as its menu icon, and the short description shown in every chest GUI. The
 * menu screens (mode grid, mode detail, lobby rows) all read these fields through shared helpers so
 * the same mode looks identical everywhere instead of each screen hardcoding its own icon and copy.
 */
public record GameModeDescriptor(
    String modeId,
    String category,
    String displayName,
    int minPlayers,
    List<String> aliases,
    List<String> hologramLines,
    String iconItem,
    List<String> description
) {
  public GameModeDescriptor {
    aliases = aliases == null ? List.of() : List.copyOf(aliases);
    // Kept nullable internally so the accessor can fall back to the standard template at call time.
    hologramLines = hologramLines == null ? null : List.copyOf(hologramLines);
    description = description == null ? List.of() : List.copyOf(description);
  }

  /** Full registration form: mode metadata plus its shared menu icon + description. */
  public GameModeDescriptor(String modeId, String category, String displayName, int minPlayers,
      List<String> aliases, String iconItem, List<String> description) {
    this(modeId, category, displayName, minPlayers, aliases, null, iconItem, description);
  }

  /** 6-arg form with a custom hologram but no icon/description (defaults applied). */
  public GameModeDescriptor(String modeId, String category, String displayName, int minPlayers,
      List<String> aliases, List<String> hologramLines) {
    this(modeId, category, displayName, minPlayers, aliases, hologramLines, null, List.of());
  }

  /** Backwards-compatible 5-arg form: the mode uses the standard hologram template and default icon. */
  public GameModeDescriptor(String modeId, String category, String displayName, int minPlayers, List<String> aliases) {
    this(modeId, category, displayName, minPlayers, aliases, null, null, List.of());
  }

  /** The vanilla item id (no namespace) used as this mode's menu icon; "paper" when unset. */
  @Override
  public String iconItem() {
    return (iconItem == null || iconItem.isEmpty()) ? "paper" : iconItem;
  }

  /**
   * The lines a bound lobby NPC's hologram should show for this mode. A mode that did not supply its own
   * lines falls back to {@link MinigameHologramSpec#standard(GameModeDescriptor)}, so every mode always
   * has a usable, standardized hologram with no per-mode configuration.
   */
  @Override
  public List<String> hologramLines() {
    List<String> custom = this.hologramLines;
    return (custom == null || custom.isEmpty()) ? MinigameHologramSpec.standard(this).lines() : custom;
  }
}
