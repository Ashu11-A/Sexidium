package com.sexidium.core.decor;
import com.sexidium.core.decor.DecorTypes.*;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.world.npc.NpcDefinition;
import com.sexidium.core.world.npc.NpcManager;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides <b>what</b> in-world decor to place in the lobby and drives its lifecycle, mirroring
 * {@link NpcManager}: a config-gated, deferred startup build (so the world + NPCs exist first), a clean
 * {@link #stop()} that despawns everything, and per-NPC respawn/remove hooks so a podium tracks its NPC
 * (no leaked or ghost display entities). It hands the platform {@link com.sexidium.core.platform.DecorAdapter}
 * fully-resolved {@link DecorProp}s — resolving each minigame mode's {@code item_model} via {@link MenuArt}
 * and its base item / glow via {@link DecorPalette} here — so the adapter stays agnostic of minigame modes.
 *
 * <p>The set per minigame-bound NPC is opt-in and empty by default (the NPC shows only its FancyHolograms
 * text nameplate): a spinning mode-icon {@code ITEM_DISPLAY} above the NPC's head (when
 * {@code ui.decor.podiums.enabled}) and a glowing pedestal {@code BLOCK_DISPLAY} one block below its feet
 * (when {@code ui.decor.portals.enabled}). A manual NPC (no {@code minigameMode}) gets no decor. An
 * optional hub centerpiece sits at a configured anchor.</p>
 */
public final class DecorManager {
  private final ServerAdapter serverAdapter;
  private final NpcManager npcManager;
  private ScheduledTask startupTask;
  private boolean built;

  public DecorManager(ServerAdapter serverAdapter, NpcManager npcManager) {
    this.serverAdapter = serverAdapter;
    this.npcManager = npcManager;
  }

  public void start() {
    if (!enabled()) {
      // Still sweep once (deferred, after the world loads) so toggling decor off actually removes any
      // decor a previous run left behind — the adapter's despawnAll() runs the stray-entity sweep.
      startupTask = serverAdapter.scheduler().runLater(() -> {
        startupTask = null;
        built = true;
        serverAdapter.decor().despawnAll();
      }, 2L);
      return;
    }
    // When NPCs are enabled, NpcManager.reloadAndSpawn (its own +1-tick startup task) fires our rebuild
    // hook once its definitions are loaded — so we only schedule a fallback build for the rare case it
    // never runs (e.g. NPCs disabled but decor on, or no NPC backend). The `built` guard prevents a
    // double build at boot.
    long delay = npcsEnabled() ? 3L : 2L;
    startupTask = serverAdapter.scheduler().runLater(() -> {
      startupTask = null;
      if (!built) {
        rebuildAndSpawn();
      }
    }, delay);
  }

  public void stop() {
    if (startupTask != null) {
      startupTask.cancel();
      startupTask = null;
    }
    serverAdapter.decor().despawnAll();
  }

  /** Despawns all decor and rebuilds it from scratch (boot, and {@code /sx admin npc reload}). Idempotent. */
  public void rebuildAndSpawn() {
    built = true;
    serverAdapter.decor().despawnAll();
    if (!enabled()) {
      return;
    }
    for (DecorProp prop : buildProps()) {
      serverAdapter.decor().spawn(prop);
    }
  }

  /** Re-places one NPC's podium decor at its (possibly moved) location. Called from {@link NpcManager#save}. */
  public void respawnForNpc(NpcDefinition definition) {
    if (definition == null) {
      return;
    }
    removeForNpc(definition.id());
    if (!enabled()) {
      return;
    }
    for (DecorProp prop : buildNpcProps(definition)) {
      serverAdapter.decor().spawn(prop);
    }
  }

  /** Removes one NPC's podium decor. Called from {@link NpcManager#remove}. */
  public void removeForNpc(String npcId) {
    if (npcId == null) {
      return;
    }
    serverAdapter.decor().despawn(podiumId(npcId));
    serverAdapter.decor().despawn(pedestalId(npcId));
  }

  /** The full prop list for the current configuration + NPC set (pure — unit-tested). */
  List<DecorProp> buildProps() {
    List<DecorProp> props = new ArrayList<>();
    if (centerpieceEnabled()) {
      props.add(centerpiece());
    }
    if (podiumsEnabled() || portalsEnabled()) {
      for (NpcDefinition definition : npcManager.definitions()) {
        props.addAll(buildNpcProps(definition));
      }
    }
    return props;
  }

  /** The podium/pedestal props for one NPC (empty for a manual NPC or when both features are off). */
  List<DecorProp> buildNpcProps(NpcDefinition definition) {
    String mode = definition.minigameMode();
    if (mode == null || mode.isBlank()) {
      return List.of();
    }
    boolean podiums = podiumsEnabled();
    boolean portals = portalsEnabled();
    if (!podiums && !portals) {
      return List.of();
    }
    List<DecorProp> props = new ArrayList<>(2);
    if (podiums) {
      double height = serverAdapter.configuration().getDouble("ui.decor.podiums.item-height", 2.4D);
      float scale = (float) serverAdapter.configuration().getDouble("ui.decor.podiums.item-scale", 0.9D);
      props.add(DecorProp.item(podiumId(definition.id()), definition.world(),
          definition.x(), definition.y() + height, definition.z(),
          DecorPalette.baseItem(mode), MenuArt.modeModel(mode), scale,
          DecorBillboard.FIXED, false, null, DecorAnimation.spin(spinDegreesPerSecond())));
    }
    if (portals) {
      // Glowing pedestal, only when portals are enabled. Placed ONE BLOCK BELOW the NPC's feet so the body
      // stands ON it — a block at the feet y would render around the NPC's legs (it looks "buried in a
      // white block"). The horizontal -0.5 offset centres the 1x1 block under the NPC.
      props.add(DecorProp.block(pedestalId(definition.id()), definition.world(),
          definition.x() - 0.5D, definition.y() - 1.0D, definition.z() - 0.5D,
          serverAdapter.configuration().getString("ui.decor.podiums.pedestal-block", "minecraft:smooth_quartz"),
          1.0F, true, DecorPalette.glowArgb(mode)));
    }
    return props;
  }

  private DecorProp centerpiece() {
    String world = serverAdapter.configuration().getString("ui.decor.centerpiece.world", lobbyWorld());
    double x = serverAdapter.configuration().getDouble("ui.decor.centerpiece.x", 0.5D);
    double y = serverAdapter.configuration().getDouble("ui.decor.centerpiece.y", 70.0D);
    double z = serverAdapter.configuration().getDouble("ui.decor.centerpiece.z", 0.5D);
    float scale = (float) serverAdapter.configuration().getDouble("ui.decor.centerpiece.scale", 1.6D);
    String baseItem = serverAdapter.configuration().getString("ui.decor.centerpiece.item", "minecraft:nether_star");
    String model = serverAdapter.configuration().getString("ui.decor.centerpiece.model", MenuArt.model(MenuArt.ICON_MINIGAMES));
    return DecorProp.item("hub_center", world, x, y, z, baseItem, model, scale,
        DecorBillboard.FIXED, true, 0xFFFFAA00,
        DecorAnimation.spinAndBob(spinDegreesPerSecond(), 0.12D, 4.0D));
  }

  private double spinDegreesPerSecond() {
    return serverAdapter.configuration().getDouble("ui.decor.animation.spin-degrees-per-second", 45.0D);
  }

  private boolean enabled() {
    return serverAdapter.configuration().getBoolean("ui.decor.enabled", true);
  }

  private boolean npcsEnabled() {
    return serverAdapter.configuration().getBoolean("lobby.npcs.enabled", true);
  }

  private boolean centerpieceEnabled() {
    return serverAdapter.configuration().getBoolean("ui.decor.centerpiece.enabled", false);
  }

  private boolean podiumsEnabled() {
    // Off by default: a minigame-bound NPC shows only its floating text nameplate (FancyHolograms). Opt in
    // to the spinning mode-icon above the head by setting ui.decor.podiums.enabled = true.
    return serverAdapter.configuration().getBoolean("ui.decor.podiums.enabled", false);
  }

  private boolean portalsEnabled() {
    return serverAdapter.configuration().getBoolean("ui.decor.portals.enabled", false);
  }

  private String lobbyWorld() {
    return serverAdapter.configuration().getString("worlds.lobby.name", "lobby");
  }

  private static String podiumId(String npcId) {
    return "podium_" + npcId;
  }

  private static String pedestalId(String npcId) {
    return "pedestal_" + npcId;
  }
}
