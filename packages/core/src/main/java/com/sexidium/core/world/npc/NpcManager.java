package com.sexidium.core.world.npc;

import com.sexidium.core.command.CoreCommandService;
import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.ServerAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Loads, spawns and maintains the lobby NPCs. Definitions live as one YAML file each
 * under {@code <dataFolder>/fakeplayers/}; this manager spawns them through the platform
 * {@link com.sexidium.core.platform.NpcAdapter}, refreshes their holograms' live placeholders on a
 * timer, and routes a click either to quick-play queue (when the NPC is bound to a minigame mode) or to
 * run the NPC's configured command as the clicking player.
 *
 * <p>Holograms follow a priority: a manual {@link NpcDefinition#hologram()} wins; otherwise a
 * minigame-bound NPC gets the mode's standardized {@link GameModeDescriptor#hologramLines()} with live
 * {@code %players_<modeId>%} / {@code %queue_<modeId>%} counts; otherwise no hologram. This is computed
 * by {@link #effectiveHologram(NpcDefinition)} so the platform adapter stays agnostic of minigame modes.</p>
 */
public final class NpcManager {
  private final ServerAdapter serverAdapter;
  private final GameManager gameManager;
  private final LobbyManager lobbyManager;
  private final Map<String, NpcDefinition> definitions = new LinkedHashMap<>();
  private final Map<UUID, Long> lastClickMillis = new HashMap<>();
  private BiConsumer<PlayerAdapter, String> adminEditorOpener = (player, npcId) -> { };
  // In-world decor sync hooks: kept as injected callbacks so NpcManager does not depend on DecorManager
  // (keeps this class + its tests decoupled). Default no-ops until SexidiumCore wires the decor manager.
  private Runnable decorRebuild = () -> { };
  private Consumer<NpcDefinition> decorRespawn = definition -> { };
  private Consumer<String> decorRemove = id -> { };
  private static final long CLICK_COOLDOWN_MILLIS = 500L;
  // Advances once per refresh tick to drive the holograms' shifting gradient (the %phase% token).
  private int animationFrame;
  // Lazily-read MiniMessage font key for holograms (resource-pack font); "default" = vanilla, no wrap.
  private String hologramFont;
  private ScheduledTask startupReloadTask;
  private ScheduledTask refreshTask;
  // True once NPCs have been (re)spawned with the server fully up. The boot-time spawn in start() can lose
  // the race against the FancyNpcs plugin finishing its own registry load, leaving NPCs invisible until a
  // manual /sx admin npc reload. The first player join is a reliable "everything is ready" signal, so we re-spawn
  // once then. See onPlayerJoin().
  private boolean liveSpawnDone;

  public NpcManager(ServerAdapter serverAdapter, GameManager gameManager, LobbyManager lobbyManager) {
    this.serverAdapter = serverAdapter;
    this.gameManager = gameManager;
    this.lobbyManager = lobbyManager;
  }

  public Path folder() {
    return serverAdapter.dataDirectory().resolve("fakeplayers");
  }

  public void start() {
    if (!serverAdapter.configuration().getBoolean("lobby.npcs.enabled", true)) {
      return;
    }
    startupReloadTask = serverAdapter.scheduler().runLater(() -> {
      startupReloadTask = null;
      reloadAndSpawn();
    }, 1L);
    long period = Math.max(20L, serverAdapter.configuration().getLong("lobby.npcs.hologram-refresh-ticks", 40L));
    refreshTask = serverAdapter.scheduler().runTimer(this::refreshHolograms, period, period);
  }

  public void stop() {
    if (startupReloadTask != null) {
      startupReloadTask.cancel();
      startupReloadTask = null;
    }
    if (refreshTask != null) {
      refreshTask.cancel();
      refreshTask = null;
    }
    liveSpawnDone = false;
    serverAdapter.npcs().despawnAll();
  }

  /**
   * Re-spawns the NPCs once, the first time a player joins after start. The boot-time spawn scheduled by
   * {@link #start()} can fire before the platform NPC backend (e.g. FancyNpcs) has finished loading its own
   * registry, which silently drops our NPCs — they then only reappear after a manual {@code /sx admin npc reload}.
   * A player join guarantees the backend is fully up and there is a viewer present, so spawning here is
   * reliable. Idempotent: only the first join triggers it.
   */
  public void onPlayerJoin() {
    if (liveSpawnDone) {
      return;
    }
    if (!serverAdapter.configuration().getBoolean("lobby.npcs.enabled", true)) {
      return;
    }
    liveSpawnDone = true;
    reloadAndSpawn();
  }

  public void reloadAndSpawn() {
    serverAdapter.npcs().despawnAll();
    definitions.clear();
    for (NpcDefinition definition : NpcDefinitionStore.loadAll(folder())) {
      definitions.put(definition.id(), definition);
      serverAdapter.npcs().spawn(spawnView(definition), this::onClick);
    }
    refreshHolograms();
    decorRebuild.run();
  }

  public Collection<NpcDefinition> definitions() {
    return new ArrayList<>(definitions.values());
  }

  public void setAdminEditorOpener(BiConsumer<PlayerAdapter, String> adminEditorOpener) {
    this.adminEditorOpener = adminEditorOpener == null ? (player, npcId) -> { } : adminEditorOpener;
  }

  /**
   * Wires the in-world decor manager so podium decor tracks NPC changes: {@code rebuild} re-places all
   * decor (full reload), {@code respawn} re-places one NPC's podium (add/move), {@code remove} drops it.
   * Injected as callbacks so this manager stays decoupled from {@code DecorManager}.
   */
  public void setDecorSync(Runnable rebuild, Consumer<NpcDefinition> respawn, Consumer<String> remove) {
    this.decorRebuild = rebuild == null ? () -> { } : rebuild;
    this.decorRespawn = respawn == null ? definition -> { } : respawn;
    this.decorRemove = remove == null ? id -> { } : remove;
  }

  public NpcDefinition get(String id) {
    return id == null ? null : definitions.get(NpcDefinitionStore.sanitize(id));
  }

  /** Persists the definition, then respawns it so the change is visible immediately. */
  public void save(NpcDefinition definition) throws IOException {
    NpcDefinitionStore.save(folder(), definition);
    definitions.put(definition.id(), definition);
    serverAdapter.npcs().despawn(definition.id());
    serverAdapter.npcs().spawn(spawnView(definition), this::onClick);
    refreshOne(definition);
    decorRespawn.accept(definition);
  }

  public boolean remove(String id) {
    String sanitized = NpcDefinitionStore.sanitize(id);
    serverAdapter.npcs().despawn(sanitized);
    definitions.remove(sanitized);
    decorRemove.accept(sanitized);
    return NpcDefinitionStore.delete(folder(), sanitized);
  }

  private void onClick(NpcInteraction interaction) {
    NpcDefinition definition = definitions.get(interaction.npcId());
    if (definition == null) {
      return;
    }
    if (interaction.sneaking() && interaction.player().hasPermission(CoreCommandService.ADMIN_PERMISSION)) {
      adminEditorOpener.accept(interaction.player(), definition.id());
      return;
    }
    // Debounce rapid clicks so a player spam-clicking an NPC does not queue / fire repeatedly.
    UUID playerId = interaction.player().uniqueId();
    long now = System.currentTimeMillis();
    Long last = lastClickMillis.get(playerId);
    if (last != null && now - last < CLICK_COOLDOWN_MILLIS) {
      return;
    }
    lastClickMillis.put(playerId, now);

    // A minigame-bound NPC queues the player for quick-play; this takes priority over any click command.
    String mode = definition.minigameMode();
    if (mode != null && !mode.isBlank()) {
      handleQueueClick(interaction.player(), mode);
      return;
    }
    String command = definition.clickCommand();
    if (command == null || command.isBlank()) {
      return;
    }
    interaction.player().performCommand(command);
  }

  /** Queues the clicking player for a minigame, mirroring {@code /lobby queue <mode>}'s result messages. */
  private void handleQueueClick(PlayerAdapter player, String modeId) {
    if (lobbyManager == null) {
      player.sendActionBar("<red>Quick-play is unavailable.</red>");
      return;
    }
    player.sendActionBar(switch (lobbyManager.queue(player, modeId)) {
      case QUEUED, ALREADY_QUEUED -> "<green>You're in the quick-play queue for <white>" + escape(modeId)
          + "</white>. We'll start when enough players are ready.</green>";
      case ALREADY_IN_MATCH -> "<red>Leave your current match first (/leave).</red>";
      case NOT_MINIGAME -> "<red>'<white>" + escape(modeId) + "</white>' is not a minigame.</red>";
      case NOT_LEADER -> "<red>Only your group leader can queue. Use the menu to leave your group first.</red>";
      default -> "<red>Could not join the queue.</red>";
    });
  }

  private void refreshHolograms() {
    animationFrame++;
    for (NpcDefinition definition : definitions.values()) {
      refreshOne(definition);
    }
  }

  private void refreshOne(NpcDefinition definition) {
    List<String> source = effectiveHologram(definition);
    if (source.isEmpty()) {
      return;
    }
    List<String> rendered = new ArrayList<>(source.size());
    for (String line : source) {
      rendered.add(render(line));
    }
    serverAdapter.npcs().updateHologram(definition.id(), rendered);
  }

  /**
   * The hologram lines this NPC should display: a manual hologram wins; else a minigame-bound NPC uses
   * the mode's standardized template; else none.
   */
  private List<String> effectiveHologram(NpcDefinition definition) {
    if (!definition.hologram().isEmpty()) {
      return definition.hologram();
    }
    String mode = definition.minigameMode();
    if (mode != null && !mode.isBlank()) {
      GameModeDescriptor descriptor = descriptorOf(mode);
      if (descriptor != null) {
        return descriptor.hologramLines();
      }
    }
    return List.of();
  }

  /**
   * A copy of the definition whose hologram is the {@link #effectiveHologram} — handed to the platform
   * adapter so a mode-bound NPC still gets a hologram entity created (the live counts are rendered in by
   * the immediately-following {@link #refreshOne}). The {@link #definitions} map keeps the original.
   */
  private NpcDefinition spawnView(NpcDefinition definition) {
    List<String> effective = effectiveHologram(definition);
    if (effective.equals(definition.hologram())) {
      return definition;
    }
    return new NpcDefinition(definition.id(), definition.world(), definition.x(), definition.y(),
        definition.z(), definition.yaw(), definition.pitch(), definition.skin(), definition.name(),
        definition.clickCommand(), definition.followPlayerHead(), effective, definition.minigameMode());
  }

  private GameModeDescriptor descriptorOf(String modeId) {
    String wanted = modeId.toLowerCase(Locale.ROOT).trim();
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      if (descriptor.modeId().equalsIgnoreCase(wanted) || descriptor.aliases().contains(wanted)) {
        return descriptor;
      }
    }
    return null;
  }

  private String render(String line) {
    String result = line.replace("%phase%", currentPhase());
    result = result.replace("%players_total%", Integer.toString(serverAdapter.onlinePlayers().size()));
    result = scanPlaceholder(result, "%players_", this::playersInMode);
    result = scanPlaceholder(result, "%queue_", this::queuedInMode);
    return applyFont(result);
  }

  /** The current gradient phase in [-1.0, 0.9], stepped each refresh tick so the gradient "flows". */
  private String currentPhase() {
    double phase = ((animationFrame % 20) / 10.0) - 1.0;
    return String.format(Locale.ROOT, "%.1f", phase);
  }

  /** Wraps a rendered line in the configured resource-pack font, unless it is the vanilla default. */
  private String applyFont(String line) {
    String font = hologramFont();
    if (font.isEmpty() || font.equalsIgnoreCase("default") || font.equalsIgnoreCase("minecraft:default")) {
      return line;
    }
    return "<font:" + font + ">" + line + "</font>";
  }

  private String hologramFont() {
    if (hologramFont == null) {
      String configured = serverAdapter.configuration().getString("lobby.npcs.hologram-font", "default");
      hologramFont = configured == null ? "default" : configured;
    }
    return hologramFont;
  }

  /** Replaces every {@code %prefix<token>%} occurrence with the resolver's value for {@code <token>}. */
  private String scanPlaceholder(String input, String prefix, Function<String, Integer> resolver) {
    String result = input;
    int start;
    while ((start = result.indexOf(prefix)) >= 0) {
      int end = result.indexOf('%', start + 1);
      if (end < 0) {
        break;
      }
      String token = result.substring(start + prefix.length(), end);
      result = result.substring(0, start) + resolver.apply(token) + result.substring(end + 1);
    }
    return result;
  }

  private int playersInMode(String modeId) {
    int count = 0;
    for (ActiveMatch match : gameManager.matches()) {
      if (modeId.equalsIgnoreCase(match.modeId())) {
        count += match.game().onlineCount();
      }
    }
    return count;
  }

  private int queuedInMode(String modeId) {
    return lobbyManager == null ? 0 : lobbyManager.queueSize(modeId);
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
  }
}
