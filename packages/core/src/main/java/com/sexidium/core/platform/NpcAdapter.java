package com.sexidium.core.platform;

import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcInteraction;

import java.util.List;
import java.util.function.Consumer;

/**
 * Spawns and manages lobby NPCs with a floating nameplate (hologram). Paper uses the
 * FancyNpcs + FancyHolograms plugins (bound through PaperNpcBackend). The default
 * is inert so the core works without an NPC backend.
 */
public interface NpcAdapter {
  NpcAdapter NOOP = new NpcAdapter() {
    @Override
    public void spawn(NpcDefinition definition, Consumer<NpcInteraction> onClick) {
    }

    @Override
    public void despawn(String npcId) {
    }

    @Override
    public void despawnAll() {
    }

    @Override
    public void updateHologram(String npcId, List<String> renderedLines) {
    }
  };

  /** Spawns (or respawns) the NPC; {@code onClick} fires when a player right-clicks it. */
  void spawn(NpcDefinition definition, Consumer<NpcInteraction> onClick);

  void despawn(String npcId);

  void despawnAll();

  /** Replaces the NPC's floating nameplate lines (already placeholder-rendered, MiniMessage). */
  void updateHologram(String npcId, List<String> renderedLines);
}
