package com.sexidium.core.world.npc;

import java.util.List;

/**
 * A configured lobby NPC (dummy), persisted as one {@code fakeplayers/<id>.yml} file.
 *
 * <p>Click behaviour follows a priority: when {@link #minigameMode} is set the click queues the player
 * for that minigame (quick-play), and the hologram is auto-generated from the mode's standard template;
 * otherwise the click runs {@link #clickCommand} as the clicking player. {@link #hologram} lines support
 * placeholders such as {@code %players_total%}, {@code %players_<modeId>%} and {@code %queue_<modeId>%},
 * and when non-empty they always override the mode's auto-generated lines.</p>
 */
public record NpcDefinition(
    String id,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    String skin,
    String name,
    String clickCommand,
    boolean followPlayerHead,
    List<String> hologram,
    String minigameMode
) {
  public NpcDefinition {
    hologram = hologram == null ? List.of() : List.copyOf(hologram);
    minigameMode = minigameMode == null ? "" : minigameMode;
  }
}
