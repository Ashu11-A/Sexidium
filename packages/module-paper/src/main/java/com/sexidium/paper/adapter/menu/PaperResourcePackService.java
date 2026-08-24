package com.sexidium.paper.adapter.menu;

import com.sexidium.core.Branding;
import com.sexidium.core.menu.pack.ResourcePackInfo;
import com.sexidium.paper.adapter.player.PaperGeyser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The per-player resource-pack capability gate — the hard Bedrock/no-pack guard the graphical
 * interfaces report mandates for the negative-space art layer. On join it offers the hosted Sexidium
 * pack to <b>Java</b> players (Bedrock/Geyser players are skipped — Geyser cannot load Java packs),
 * tracks who actually loaded it via {@link PlayerResourcePackStatusEvent}, and exposes
 * {@link #loaded(UUID)} so {@link PaperMenuAdapter} only injects glyph/shift art and custom item
 * models for players who will actually see them. Everyone else gets the plain, fully-functional chest
 * (and Bedrock gets the Cumulus Form).
 */
public final class PaperResourcePackService implements Listener {
  private final JavaPlugin plugin;
  private final ResourcePackInfo pack;
  // A stable id derived from the pack hash so we offer the pack under a UUID WE know, and can match the
  // status callback to OUR pack. Without this, any other pack the client loads (a proxy network pack,
  // another plugin's pack) would fire SUCCESSFULLY_LOADED and wrongly mark the player as having our
  // fonts/models — they would then get glyph tofu + broken item icons.
  private final UUID packUuid;
  private final boolean required;
  private final Component prompt;
  private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
  private final Set<UUID> offered = ConcurrentHashMap.newKeySet();
  // Notified (on the main thread) when a player's pack finishes loading, so persistent surfaces that
  // can't re-render on demand — the lobby hotbar nav items — can refresh their custom item-models.
  private final List<Consumer<UUID>> loadListeners = new CopyOnWriteArrayList<>();

  public PaperResourcePackService(JavaPlugin plugin, ResourcePackInfo pack) {
    this.plugin = plugin;
    this.pack = pack;
    this.packUuid = pack == null
        ? null
        : UUID.nameUUIDFromBytes(("sexidium-menu:" + pack.sha1Hex()).getBytes(StandardCharsets.UTF_8));
    this.required = plugin.getConfig().getBoolean("ui.resource-pack.required", false);
    String brandLabel = Branding.label(plugin.getConfig().getString(Branding.LABEL_PATH, Branding.DEFAULT_LABEL));
    String promptText = plugin.getConfig().getString("ui.resource-pack.prompt",
        "<gradient:#ff5f6d:#ffc371><brand></gradient> <gray>menu art pack — accept for the full look.</gray>");
    if (promptText != null) {
      promptText = promptText.contains("<brand>") ? promptText.replace("<brand>", brandLabel)
          : promptText.replace(Branding.DEFAULT_LABEL, brandLabel);
    }
    this.prompt = promptText == null || promptText.isBlank()
        ? null : MiniMessage.miniMessage().deserialize(promptText);
    if (pack == null || pack.url() == null || pack.url().isBlank()) {
      plugin.getLogger().warning("Menu resource pack delivery is unavailable; Java players will keep plain menus.");
    } else {
      plugin.getLogger().info("Menu resource pack gate ready: url=" + pack.url()
          + ", sha1=" + pack.sha1Hex() + ", id=" + packUuid + ", required=" + required + ".");
    }
  }

  /** Whether {@code playerId} has the Sexidium pack applied (so glyph/item art is worth sending). */
  public boolean loaded(UUID playerId) {
    return playerId != null && loaded.contains(playerId);
  }

  /** Registers a callback fired (on the main thread) when a player's pack finishes loading. */
  public void onLoaded(Consumer<UUID> listener) {
    if (listener != null) {
      loadListeners.add(listener);
    }
  }

  @EventHandler
  public void onJoin(PlayerJoinEvent event) {
    if (pack == null || pack.url() == null || pack.url().isBlank()) {
      plugin.getLogger().fine("Menu resource pack not offered to " + event.getPlayer().getName()
          + ": no hosted pack URL.");
      return;
    }
    Player player = event.getPlayer();
    // Java packs never reach a Geyser/Bedrock client — don't prompt them; they get the Form UI.
    if (PaperGeyser.isBedrock(player.getUniqueId())) {
      plugin.getLogger().info("Skipping menu resource pack for Bedrock/Geyser player " + player.getName() + ".");
      return;
    }
    try {
      // Offer under OUR known UUID so the status callback can be matched to this pack specifically.
      offered.add(player.getUniqueId());
      plugin.getLogger().info("Offering menu resource pack to " + player.getName()
          + ": " + pack.url() + " (sha1 " + pack.sha1Hex() + ", id " + packUuid + ").");
      // ADDITIVE — replace(false) — and never the deprecated Player#setResourcePack. The Bukkit API
      // distinguishes the two by verb: set is "download and SWITCH resource packs", add is "download and
      // INCLUDE ANOTHER". Since 1.20.3 a client can hold several server packs at once and other plugins
      // rely on it: a BetterHud install self-hosts its own, and that pack carries the font its readout is
      // drawn in. Switching would pop it on every join and turn its HUD into unknown-character boxes.
      //
      // Adventure's request rather than Player#addResourcePack because only this one takes a Component
      // prompt — the String overload would silently drop our configured, MiniMessage-rendered message.
      player.sendResourcePacks(net.kyori.adventure.resource.ResourcePackRequest.resourcePackRequest()
          .packs(net.kyori.adventure.resource.ResourcePackInfo.resourcePackInfo(
              packUuid, java.net.URI.create(pack.url()), pack.sha1Hex()))
          .prompt(prompt)
          .required(required)
          .replace(false)
          .build());
    } catch (Throwable error) {
      // Any odd server/API mismatch must not block login — just skip the art for this player.
      offered.remove(player.getUniqueId());
      plugin.getLogger().warning("Could not offer the menu resource pack to " + player.getName() + ": " + error);
    }
  }

  @EventHandler
  public void onStatus(PlayerResourcePackStatusEvent event) {
    // Only our pack's status may flip the gate — ignore any other pack the client loads.
    UUID id = event.getPlayer().getUniqueId();
    UUID eventPackId = event.getID();
    if (packUuid == null || !packUuid.equals(eventPackId)) {
      if (offered.contains(id)) {
        plugin.getLogger().warning("Ignoring menu resource-pack status for " + event.getPlayer().getName()
            + ": expected id " + packUuid + ", got " + eventPackId + " (status " + event.getStatus() + ").");
      }
      return;
    }
    switch (event.getStatus()) {
      case SUCCESSFULLY_LOADED -> {
        loaded.add(id);
        offered.remove(id);
        plugin.getLogger().info("Menu resource pack loaded by " + event.getPlayer().getName()
            + "; enabling glyph backgrounds and custom item models.");
        for (Consumer<UUID> listener : loadListeners) {
          listener.accept(id);
        }
      }
      case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, DISCARDED, INVALID_URL -> {
        loaded.remove(id);
        offered.remove(id);
        plugin.getLogger().warning("Menu resource pack " + event.getStatus() + " for "
            + event.getPlayer().getName() + "; keeping plain menu rendering.");
      }
      default -> {
        // ACCEPTED / DOWNLOADED are in-flight; wait for SUCCESSFULLY_LOADED before enabling art.
        plugin.getLogger().info("Menu resource pack status for " + event.getPlayer().getName()
            + ": " + event.getStatus() + " (waiting for SUCCESSFULLY_LOADED).");
      }
    }
  }

  @EventHandler
  public void onQuit(PlayerQuitEvent event) {
    loaded.remove(event.getPlayer().getUniqueId());
    offered.remove(event.getPlayer().getUniqueId());
  }

  /**
   * Retracts our pack from a player, by id, leaving every other server pack they hold alone. Exposed for
   * a reload/shutdown path that wants the client back on plain vanilla rendering without disturbing
   * whatever else put a pack on them.
   */
  public void retract(Player player) {
    if (player == null || packUuid == null) {
      return;
    }
    try {
      player.removeResourcePack(packUuid);
    } catch (Throwable ignored) {
      // A client that already dropped it, or an API that will not take the id back: nothing to do.
    }
    loaded.remove(player.getUniqueId());
    offered.remove(player.getUniqueId());
  }
}
