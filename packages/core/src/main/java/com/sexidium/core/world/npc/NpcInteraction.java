package com.sexidium.core.world.npc;

import com.sexidium.core.platform.PlayerAdapter;

/** A player clicking a lobby NPC. */
public record NpcInteraction(PlayerAdapter player, String npcId, boolean sneaking) {
}
