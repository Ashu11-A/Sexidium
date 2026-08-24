package com.sexidium.paper.adapter.npc;

import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcInteraction;
import com.sexidium.core.platform.NpcAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import de.oliver.fancyholograms.api.FancyHologramsPlugin;
import de.oliver.fancyholograms.api.HologramManager;
import de.oliver.fancyholograms.api.data.TextHologramData;
import de.oliver.fancyholograms.api.hologram.Hologram;
import de.oliver.fancynpcs.api.FancyNpcsPlugin;
import de.oliver.fancynpcs.api.Npc;
import de.oliver.fancynpcs.api.NpcData;
import de.oliver.fancynpcs.api.NpcManager;
import de.oliver.fancynpcs.api.skins.SkinData;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Lobby NPC ({@link NpcAdapter}) backend for Paper, built on the <b>FancyNpcs</b> +
 * <b>FancyHolograms</b> plugins (declared {@code softdepend} in {@code plugin.yml} and {@code compileOnly}
 * in the build).
 *
 * <p>Each {@link NpcDefinition} maps to one FancyNpcs {@link Npc} (the clickable body) and one
 * FancyHolograms {@link Hologram} (the floating nameplate), both tracked by the definition id. Right-click
 * routing uses FancyNpcs' native {@link NpcData#setOnClick(Consumer)} hook — when a player interacts with
 * the NPC, FancyNpcs invokes our consumer, which we forward to the core as an {@link NpcInteraction} (the
 * core then runs the NPC's configured command as the clicking player). Skin copying uses FancyNpcs'
 * {@code setSkin(playerName)}, which fetches and applies that player's skin asynchronously.
 *
 * <p>When either plugin is absent at runtime the adapter degrades gracefully: every operation becomes a
 * no-op (and the first call logs a warning), so the core continues to work without an NPC backend.
 */
public final class PaperNpcAdapter implements NpcAdapter {
  private final JavaPlugin plugin;
  /** FancyNpcs Npc handles keyed by definition id. */
  private final Map<String, Npc> npcs = new ConcurrentHashMap<>();
  /** FancyHolograms nameplate handles keyed by definition id. */
  private final Map<String, Hologram> holograms = new ConcurrentHashMap<>();
  private boolean warnedMissingNpcs;
  private boolean warnedMissingHolograms;

  public PaperNpcAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  @Override
  public void spawn(NpcDefinition definition, Consumer<NpcInteraction> onClick) {
    if (definition == null) {
      return;
    }
    // Respawn semantics: drop any existing NPC/hologram for this id first.
    despawn(definition.id());

    if (!npcsAvailable()) {
      return;
    }

    World world = Bukkit.getWorld(definition.world());
    if (world == null) {
      plugin.getLogger().warning("Lobby NPC '" + definition.id() + "' references unknown world '"
          + definition.world() + "'; skipping spawn.");
      return;
    }
    Location location = new Location(world, definition.x(), definition.y(), definition.z(),
        definition.yaw(), definition.pitch());

    try {
      spawnNpc(definition, location, onClick);
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.WARNING, "Failed to spawn lobby NPC '" + definition.id() + "'.", exception);
      return;
    }

    if (!definition.hologram().isEmpty()) {
      try {
        spawnHologram(definition, location);
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.WARNING,
            "Failed to spawn nameplate for lobby NPC '" + definition.id() + "'.", exception);
      }
    }
  }

  private void spawnNpc(NpcDefinition definition, Location location, Consumer<NpcInteraction> onClick) {
    FancyNpcsPlugin fancyNpcs = FancyNpcsPlugin.get();
    NpcManager manager = fancyNpcs.getNpcManager();

    // A unique, namespaced FancyNpcs name keeps lobby NPCs from clashing with operator-created ones.
    String npcName = npcName(definition.id());
    NpcData data = new NpcData(npcName, internalCreator(), location);
    if (definition.name() != null && !definition.name().isBlank()) {
      data.setDisplayName(definition.name());
    } else {
      // No nameplate on the body itself — the hologram carries the visible text.
      data.setDisplayName("<empty>");
    }
    if (definition.skin() != null && !definition.skin().isBlank()) {
      // Prefer SkinsRestorer: an offline lookup resolves player names, custom skin ids, and already-cached
      // URL skins to a raw texture we apply directly (main-thread safe). On a miss we let FancyNpcs' own
      // name lookup cover plain names immediately, and asynchronously ask SkinsRestorer to generate the
      // skin (URLs go through MineSkin) and re-apply it to the live NPC once ready.
      var resolved = PaperNpcSkinResolver.resolve(definition.skin());
      if (resolved.isPresent()) {
        var texture = resolved.get();
        data.setSkinData(new SkinData(definition.skin(), SkinData.SkinVariant.AUTO, texture.value(), texture.signature()));
      } else {
        data.setSkin(definition.skin());
        applySkinAsync(definition.id(), definition.skin());
      }
    }
    data.setTurnToPlayer(definition.followPlayerHead());
    data.setOnClick(player -> handleClick(definition.id(), player, onClick));

    Npc npc = fancyNpcs.getNpcAdapter().apply(data);
    npc.create();
    manager.registerNpc(npc);
    npc.spawnForAll();
    npcs.put(definition.id(), npc);
  }

  /**
   * Generates the skin from SkinsRestorer off the main thread (URLs hit MineSkin), then re-applies the
   * resulting texture to the still-spawned NPC on the main thread. A no-op if SkinsRestorer cannot resolve
   * it (the FancyNpcs fallback skin set at spawn stays).
   */
  private void applySkinAsync(String npcId, String skin) {
    // Folia-safe: resolve off-thread via the AsyncScheduler, then re-apply on the global region (the
    // NPC update is packet-based — updateForAll sends to every viewer — so it is not region-bound).
    Bukkit.getAsyncScheduler().runNow(plugin, asyncTask -> {
      var created = PaperNpcSkinResolver.resolveOrCreate(skin);
      if (created.isEmpty()) {
        return;
      }
      var texture = created.get();
      Bukkit.getGlobalRegionScheduler().run(plugin, scheduledTask -> {
        Npc npc = npcs.get(npcId);
        if (npc == null) {
          return;
        }
        try {
          npc.getData().setSkinData(new SkinData(skin, SkinData.SkinVariant.AUTO, texture.value(), texture.signature()));
          npc.updateForAll();
        } catch (RuntimeException exception) {
          plugin.getLogger().log(Level.FINE, "Failed to apply resolved skin for lobby NPC '" + npcId + "'.", exception);
        }
      });
    });
  }

  private void spawnHologram(NpcDefinition definition, Location location) {
    if (!hologramsAvailable()) {
      return;
    }
    HologramManager manager = FancyHologramsPlugin.get().getHologramManager();

    // Float the nameplate a little above the NPC's head, leaving room for the multi-line card.
    Location holoLocation = location.clone().add(0.0D, 2.2D, 0.0D);
    TextHologramData data = new TextHologramData(hologramName(definition.id()), holoLocation);
    data.setText(List.copyOf(definition.hologram()));
    data.setBillboard(Display.Billboard.CENTER);
    data.setSeeThrough(false);
    data.setPersistent(false);
    // A modern floating-text look: centred, drop-shadowed, no dark background box, full-bright so the
    // gradients stay vivid at night, and scaled up slightly for presence.
    data.setTextAlignment(TextDisplay.TextAlignment.CENTER);
    data.setTextShadow(true);
    data.setBackground(Color.fromARGB(0, 0, 0, 0));
    data.setBrightness(new Display.Brightness(15, 15));
    // Keep scale at 1.0: enlarging the text pushes long titles past the text-display line-width, which
    // wraps them onto a second line and breaks the card layout.
    data.setScale(new Vector3f(1.0F, 1.0F, 1.0F));

    Hologram hologram = manager.create(data);
    hologram.createHologram();
    manager.addHologram(hologram);
    hologram.forceUpdate();
    holograms.put(definition.id(), hologram);
  }

  private void handleClick(String npcId, Player player, Consumer<NpcInteraction> onClick) {
    if (player == null || onClick == null) {
      return;
    }
    onClick.accept(new NpcInteraction(new PaperPlayerAdapter(player), npcId, player.isSneaking()));
  }

  @Override
  public void updateHologram(String npcId, List<String> renderedLines) {
    if (npcId == null) {
      return;
    }
    Hologram hologram = holograms.get(npcId);
    if (hologram == null) {
      return;
    }
    if (!(hologram.getData() instanceof TextHologramData textData)) {
      return;
    }
    // FancyHolograms text lines are MiniMessage strings (deserialized to Components internally); the core
    // hands us lines that are already placeholder-rendered.
    textData.setText(renderedLines == null ? List.of() : List.copyOf(renderedLines));
    try {
      hologram.queueUpdate();
    } catch (RuntimeException exception) {
      plugin.getLogger().log(Level.FINE, "Failed to refresh nameplate for lobby NPC '" + npcId + "'.", exception);
    }
  }

  @Override
  public void despawn(String npcId) {
    if (npcId == null) {
      return;
    }
    Npc npc = npcs.remove(npcId);
    if (npc != null && npcsAvailable()) {
      try {
        npc.removeForAll();
        FancyNpcsPlugin.get().getNpcManager().removeNpc(npc);
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.FINE, "Failed to despawn lobby NPC '" + npcId + "'.", exception);
      }
    }
    Hologram hologram = holograms.remove(npcId);
    if (hologram != null && hologramsAvailable()) {
      try {
        // Delete BEFORE unregistering: deleteHologram() sends the entity-destroy packets to current
        // viewers, while removeHologram() clears the manager's viewer tracking. Doing it the other way
        // round leaves a client-side ghost nameplate at the old position (e.g. after "Move NPC Here").
        hologram.deleteHologram();
        FancyHologramsPlugin.get().getHologramManager().removeHologram(hologram);
        // Belt-and-suspenders: kill the backing display entity if anything survived the above.
        Display display = hologram.getDisplayEntity();
        if (display != null && !display.isDead()) {
          display.remove();
        }
      } catch (RuntimeException exception) {
        plugin.getLogger().log(Level.FINE, "Failed to despawn nameplate for lobby NPC '" + npcId + "'.", exception);
      }
    }
  }

  @Override
  public void despawnAll() {
    for (String id : List.copyOf(npcs.keySet())) {
      despawn(id);
    }
    // Any holograms without a matching NPC (defensive) are cleared too.
    for (String id : List.copyOf(holograms.keySet())) {
      despawn(id);
    }
  }

  private boolean npcsAvailable() {
    if (Bukkit.getPluginManager().getPlugin("FancyNpcs") == null) {
      if (!warnedMissingNpcs) {
        plugin.getLogger().warning("FancyNpcs is not installed; lobby NPCs are disabled.");
        warnedMissingNpcs = true;
      }
      return false;
    }
    return true;
  }

  private boolean hologramsAvailable() {
    if (Bukkit.getPluginManager().getPlugin("FancyHolograms") == null) {
      if (!warnedMissingHolograms) {
        plugin.getLogger().warning("FancyHolograms is not installed; lobby NPC nameplates are disabled.");
        warnedMissingHolograms = true;
      }
      return false;
    }
    return true;
  }

  private UUID internalCreator() {
    // Stable, non-player creator id for plugin-managed NPCs.
    return new UUID(0L, 0L);
  }

  private static String npcName(String id) {
    return "sexidium_npc_" + id;
  }

  private static String hologramName(String id) {
    return "sexidium_npc_holo_" + id;
  }
}
