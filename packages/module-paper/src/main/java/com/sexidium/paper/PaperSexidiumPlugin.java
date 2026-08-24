package com.sexidium.paper;

import com.sexidium.core.Branding;
import com.sexidium.core.SexidiumCore;
import com.sexidium.core.SexidiumCoreDependencies;
import com.sexidium.core.auth.AuthService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.lib.data.DatabaseConfig;
import com.sexidium.core.lib.data.DatabaseSettings;
import com.sexidium.core.lib.data.SqlDialect;
import com.sexidium.core.network.NetworkSettings;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.paper.adapter.config.PaperConfigurationAdapter;
import com.sexidium.paper.adapter.event.PaperEventBridge;
import com.sexidium.paper.adapter.game.PaperGameRegistryFactory;
import com.sexidium.paper.adapter.inventory.PaperKitAdapter;
import com.sexidium.paper.adapter.menu.PaperMenuAdapter;
import com.sexidium.paper.adapter.server.PaperServerAdapter;
import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.hud.surface.HudSurfaceCatalog;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import com.sexidium.paper.adapter.ui.betterhud.BetterHudDriver;
import com.sexidium.paper.adapter.ui.betterhud.BetterHudReconciler;
import com.sexidium.paper.adapter.ui.PaperMessageAdapter;
import com.sexidium.paper.adapter.ui.PaperUiAdapter;
import com.sexidium.paper.adapter.world.PaperLobbyBootstrap;
import com.sexidium.paper.adapter.world.PaperLobbyGuard;
import com.sexidium.core.command.CoreCommandService;
import com.sexidium.paper.command.PaperAliasCommand;
import com.sexidium.paper.command.PaperCommandBridge;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class PaperSexidiumPlugin extends JavaPlugin {
  private SexidiumCore core;
  private Database database;
  private AuthService authService;
  private PaperKitAdapter kitAdapter;
  private PaperConfigurationAdapter configurationAdapter;

  @Override
  public void onEnable() {
    saveDefaultConfig();
    configurationAdapter = new PaperConfigurationAdapter(this);
    String brandLabel = Branding.label(configurationAdapter);
    // The Discord bot talks to the database directly via TypeORM, which cannot safely share an embedded
    // SQLite file with the plugin. When the bot is enabled the plugin runs on MySQL/Postgres only.
    if (configurationAdapter.getBoolean("bot.enabled", false)) {
      DatabaseConfig probe = DatabaseSettings.resolve(configurationAdapter, getDataFolder());
      if (probe.dialect() == SqlDialect.SQLITE) {
        getLogger().severe("bot.enabled=true requires a networked database: set database.type to 'mysql' or "
            + "'postgres' (embedded SQLite is not supported while the Discord bot is enabled). Disabling "
            + brandLabel + ".");
        getServer().getPluginManager().disablePlugin(this);
        return;
      }
    }
    provisionWorlds();
    PaperMessageAdapter messageAdapter = new PaperMessageAdapter(this);
    PaperMenuAdapter menuAdapter = new PaperMenuAdapter(this);
    PaperServerAdapter serverAdapter = new PaperServerAdapter(this, configurationAdapter, messageAdapter, menuAdapter);
    kitAdapter = new PaperKitAdapter(this);
    setupDatabase(configurationAdapter);

    core = new SexidiumCore(new SexidiumCoreDependencies(
        serverAdapter,
        kitAdapter,
        PaperGameRegistryFactory.create(),
        database,
        authService,
        this::authEnabled
    ));
    messageAdapter.use(core.messages());
    // Published before start() so the first heartbeat already carries it. Best-effort, and there IS a
    // small race if the jar is swapped between JVM start and plugin enable -- which is why
    // plugin_version (from -Dsexidium.build.id) is the identity to gate a roll on, and this is only
    // the cross-check against the artifact the pipeline staged.
    core.network().setBuildSha(jarSha256());
    core.start();

    getLogger().info("Bedrock form UI backend: "
        + com.sexidium.paper.adapter.player.PaperGeyser.bedrockBackend()
        + " (Bedrock/Geyser players get native touch forms; chest GUI is the fallback).");

    PaperLobbyGuard lobbyGuard = new PaperLobbyGuard(this, core, configurationAdapter);
    getServer().getPluginManager().registerEvents(new PaperEventBridge(core), this);
    getServer().getPluginManager().registerEvents(lobbyGuard, this);
    // The in-world half of the login gate: freeze a player waiting on their Discord button and let
    // them go the moment it lands. Registered next to the lobby guard because it is the same shape --
    // a policy object plus native-event cancellations -- and inert while auth.hold.enabled is false.
    com.sexidium.paper.adapter.auth.PaperAuthHold authHold =
        new com.sexidium.paper.adapter.auth.PaperAuthHold(this, core, configurationAdapter);
    getServer().getPluginManager().registerEvents(authHold, this);
    authHold.start();
    // Poll-driven hotbar refresh so the lobby-management items swap in/out on lobby-state changes.
    lobbyGuard.start();
    getServer().getPluginManager().registerEvents(menuAdapter, this);
    // Per-experience Nether/End: redirect portal travel inside an experience to its own linked dimensions.
    if (serverAdapter.worlds() instanceof com.sexidium.paper.adapter.world.PaperWorldControl worldControl) {
      getServer().getPluginManager().registerEvents(
          new com.sexidium.paper.adapter.world.PaperPortalListener(worldControl, core), this);
    }

    // Auto-serve the menu art pack and gate glyph/item art on whether each player loaded it (Java only;
    // Bedrock gets the Cumulus Form). The pack was built + hosted by core.start(), so URL/SHA are ready.
    com.sexidium.paper.adapter.menu.PaperResourcePackService packService =
        new com.sexidium.paper.adapter.menu.PaperResourcePackService(this, core.resourcePack());
    menuAdapter.setPackGate(packService::loaded);
    // The lobby hotbar nav items also wear custom item-models for pack-loaded players; gate them the
    // same way and refresh them when the pack finishes loading (so the icons appear without a relog).
    lobbyGuard.setPackGate(packService::loaded);
    packService.onLoaded(lobbyGuard::refreshNavItems);
    getServer().getPluginManager().registerEvents(packService, this);

    // The HUD driver, if BetterHud is installed: generate the yml for every declared surface, take
    // ownership of what BetterHud shows so its demo hud stops riding along, and keep it reconciled.
    // Every part is opt-out; see the hud block in config.yml. A server without BetterHud, or one where
    // the driver is off, simply renders every declared surface on the scoreboard sidebar instead.
    installHudDriver(serverAdapter);

    // One shared command service backs the /sexidium root and every top-level shortcut, so they gate
    // permissions and dispatch identically. The shortcuts just prepend their subcommand token.
    CoreCommandService commandService = new CoreCommandService(core, this::reloadSexidium);
    bindCommand("sexidium", new PaperCommandBridge(commandService));
    bindCommand("exit", new PaperAliasCommand(commandService, "exit")); // /exit + /leave alias
    bindCommand("menu", new PaperAliasCommand(commandService, "menu"));
    bindCommand("lobby", new PaperAliasCommand(commandService, "lobby"));
    bindCommand("friend", new PaperAliasCommand(commandService, "friend"));
    getLogger().info(brandLabel + " Paper adapter enabled.");
    // THE line a rolling update waits on. `Done (` is not enough on its own: Paper prints it even
    // when a plugin threw during enable and was disabled, which has been seen live. This one is
    // printed at the end of a successful enable and nowhere else, and it carries the two values the
    // pipeline verifies -- which build came back, and with what content.
    getLogger().info("SX-READY node=" + core.network().identity().nodeId()
        + " protocol=" + core.network().protocolTag().version()
        + " build=" + core.network().buildIdentity().publishedVersion()
        + " content=" + core.network().contentManifest().digest()
        + " players=" + getServer().getOnlinePlayers().size());
  }

  /**
   * The sha256 of the jar this process actually loaded, so an orchestrator can join straight against
   * the artifact it staged. Null on any failure — a build stamp is a diagnostic, never a reason to
   * refuse to start.
   */
  private String jarSha256() {
    try {
      java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
      try (java.io.InputStream stream = new java.io.FileInputStream(getFile())) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = stream.read(buffer)) > 0) {
          digest.update(buffer, 0, read);
        }
      }
      StringBuilder hex = new StringBuilder(64);
      for (byte b : digest.digest()) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception | LinkageError unavailable) {
      getLogger().fine("Could not hash the plugin jar: " + unavailable);
      return null;
    }
  }

  /** Wires an executor/completer onto a plugin.yml command, logging if the command is missing. */
  private <T extends CommandExecutor & TabCompleter> void bindCommand(String name, T handler) {
    PluginCommand command = getCommand(name);
    if (command != null) {
      command.setExecutor(handler);
      command.setTabCompleter(handler);
    } else {
      getLogger().severe("Command '" + name + "' is missing from plugin.yml.");
    }
  }

  /**
   * Sets up the BetterHud HUD driver.
   *
   * <p>Two things happen here: every surface in {@link HudSurfaceCatalog} is declared so its yml can be
   * generated before anybody is online, and the generated tree is written — with BetterHud reloaded ONLY
   * if something actually changed, since a reload restarts another plugin's world.
   *
   * <p>All of it no-ops when BetterHud is absent, and the readouts land on the scoreboard sidebar
   * instead — which is not a degraded mode so much as the ordinary one, since it is what every Bedrock
   * player gets even on a server where BetterHud is working perfectly.</p>
   */
  private void installHudDriver(PaperServerAdapter serverAdapter) {
    if (!(serverAdapter.ui() instanceof PaperUiAdapter uiAdapter)) {
      return;
    }
    BetterHudDriver driver = uiAdapter.betterHud();
    try {
      for (HudSurfaceSpec spec : HudSurfaceCatalog.all()) {
        driver.declare(spec);
      }
      warnAboutLegacyHudConfig();
      boolean enabled = configurationAdapter.getBoolean("hud.betterhud.enabled", false);
      driver.enabled(enabled);
      driver.exclusive(configurationAdapter.getBoolean("hud.betterhud.exclusive", true));
      driver.capabilityProbe(configurationAdapter.getBoolean("hud.betterhud.capability-probe", true));

      if (!enabled) {
        installTeardownOnly(driver);
        return;
      }
      if (configurationAdapter.getBoolean("hud.betterhud.manage", true)
          && driver.syncAssets(
              getDataFolder().toPath().resolveSibling("BetterHud"),
              configurationAdapter.getBoolean("hud.betterhud.disable-demo-assets", true))) {
        // Only when something was actually written — a no-op boot must not restart another plugin's
        // world. Deferred a tick so BetterHud has finished enabling before it is asked to reload.
        //
        // A failed dispatch here is survivable and common: BetterHud softdepends the other way round in
        // practice (it finishes enabling AFTER us), so this command may not exist yet. Its own startup
        // load reads the generated tree off disk regardless, and the rows resolve from each player's
        // variable map at draw time rather than at parse time — so nothing depends on winning this race.
        getServer().getGlobalRegionScheduler().runDelayed(this, task ->
            getServer().dispatchCommand(getServer().getConsoleSender(), "betterhud reload"), 1L);
      }
      // Same cadence the readouts refresh at, so an object BetterHud re-added is gone again within one
      // update rather than lingering visibly.
      long period = HudCadence.ticks(configurationAdapter);
      // Rendering and publishing happen here, off the server thread — see BetterHudDriver#startPublishing
      // for why that is safe for this path and not for the sidebar.
      driver.startPublishing(this, period);
      BetterHudReconciler reconciler = driver.reconciler(this, period, false);
      getServer().getPluginManager().registerEvents(reconciler, this);
      reconciler.start();
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, "HUD driver setup failed; declared surfaces fall back to the "
          + "scoreboard sidebar and BetterHud's own objects are left as they are.", exception);
    }
  }

  /**
   * With the driver switched off, run the reconcile as a one-way teardown — and say why it is running
   * at all.
   *
   * <p>This is not tidiness. BetterHud persists worn objects per player and re-applies them on join,
   * so a surface claimed while the driver was enabled outlives the operator switching it off, and
   * keeps drawing. Taking ours back off has to work on the disabled path too.</p>
   *
   * <p>The warning is separate from that: an installed-but-unused BetterHud is still sending every
   * Java client its own resource pack, and on a Minecraft version it has no shader overlay for, that
   * pack is what corrupts vanilla GUI text. Nothing here can switch that off; only removing the plugin
   * can, and an operator chasing mis-coloured text deserves to be told so.</p>
   */
  /**
   * Says so out loud when the config on disk predates the HUD driver.
   *
   * <p>An upgrade keeps the operator's existing {@code config.yml} — Bukkit never overwrites one — so a
   * server that ran the previous release has a flat {@code betterhud:} block and no {@code hud:}
   * section. Every key this method's caller reads is then absent and silently takes its default, which
   * for {@code enabled} is false: BetterHud is left entirely unconfigured while appearing to be set up,
   * its own demo hud keeps drawing, and the operator's {@code betterhud.enabled: true} is read by
   * nobody. Defaults are the right behaviour for a missing key and the wrong behaviour for a MOVED one,
   * and only the plugin can tell the two apart.
   */
  private void warnAboutLegacyHudConfig() {
    if (configurationAdapter.contains("hud.betterhud") || !configurationAdapter.contains("betterhud")) {
      return;
    }
    getLogger().warning("config.yml still has the old top-level 'betterhud:' block and no 'hud:'"
        + " section. Those keys moved under 'hud.betterhud' in this release and the old ones are no"
        + " longer read, so the HUD driver is running on defaults (enabled: false) whatever your old"
        + " block says. Move config.yml aside and restart to regenerate it — re-applying your other"
        + " settings — or add:\n"
        + "  hud:\n"
        + "    betterhud:\n"
        + "      enabled: true\n"
        + "      manage: true\n"
        + "      exclusive: true\n"
        + "      disable-demo-assets: true\n"
        + "      capability-probe: true");
  }

  private void installTeardownOnly(BetterHudDriver driver) {
    if (!driver.installed()) {
      // No plugin to reconcile against: register nothing and schedule nothing, which is the state of
      // the overwhelming majority of servers.
      return;
    }
    BetterHudReconciler teardown = driver.reconciler(this, 20L, true);
    getServer().getPluginManager().registerEvents(teardown, this);
    teardown.start();
    getLogger().warning("BetterHud is installed but hud.betterhud.enabled is false, so Sexidium is not "
        + "using it and every declared surface renders on the scoreboard sidebar. NOTE: BetterHud still "
        + "sends its own resource pack to Java clients on its own. If you are seeing wrong text colours "
        + "or missing text/buttons in vanilla screens, that pack is why: BetterHud replaces the client's "
        + "core text shaders and ships no overlay matching every Minecraft version it claims to cover. "
        + "REMOVING THE PLUGIN is the only complete fix. `pack-type: none` in plugins/BetterHud/config.yml "
        + "stops the shader damage but leaves BetterHud drawing its own boss bar with a font the client no "
        + "longer has, which renders as a row of unknown-character boxes across the top of the screen.");
  }

  private void provisionWorlds() {
    try {
      PaperLobbyBootstrap bootstrap = new PaperLobbyBootstrap(this, configurationAdapter,
          PaperLobbyBootstrap.loggerFor(this));
      // Exactly one node hosts the lobby, and seeding it is the most destructive thing this plugin does
      // on disk: LobbyBundle DELETES the target folder before extracting. A worker doing that against a
      // world root it shares with the lobby node would erase a live world; a worker doing it against its
      // own disk merely builds a lobby nobody will ever be routed to. Neither is wanted, and the node
      // already declares whether it hosts one. Standalone holds every capability, so it still provisions.
      if (nodeIdentity().can(NodeCapability.LOBBY)) {
        bootstrap.provision();
      } else {
        getLogger().info("This node does not hold the LOBBY capability; skipping lobby world provisioning.");
      }
      // Map templates are cloned into match worlds, so EVERY node that can host a minigame needs them —
      // this stays ungated. MapBundle serialises the extraction across JVMs itself (see its javadoc).
      bootstrap.provisionBundledMaps();
    } catch (Exception exception) {
      getLogger().log(Level.WARNING, "World provisioning failed; falling back to existing world layout.", exception);
    }
  }

  /**
   * This node's identity, read straight from config because world provisioning happens before
   * {@code PaperServerAdapter} (which caches it) exists. Same source, same answer: standalone unless
   * {@code network.enabled} says otherwise.
   */
  private NodeIdentity nodeIdentity() {
    return NetworkSettings.resolve(configurationAdapter);
  }

  @Override
  public void onDisable() {
    if (core != null) {
      core.close();
    }
    if (database != null) {
      database.close();
    }
  }

  public SexidiumCore core() {
    return core;
  }

  public boolean authEnabled() {
    return getConfig().getBoolean("auth.enabled", true);
  }

  public void reloadSexidium() {
    reloadConfig();
    if (kitAdapter != null) {
      kitAdapter.reload();
    }
    if (core != null) {
      core.reload();
    }
  }

  private void setupDatabase(PaperConfigurationAdapter configurationAdapter) {
    try {
      DatabaseConfig databaseConfig = DatabaseSettings.resolve(configurationAdapter, getDataFolder());
      if (databaseConfig.sqliteFile() != null) {
        File parentDirectory = databaseConfig.sqliteFile().getParentFile();
        if (parentDirectory != null) {
          parentDirectory.mkdirs();
        }
      }
      database = new Database(databaseConfig, configurationAdapter.getLong(
          "network.db.validate-interval-millis", Database.DEFAULT_VALIDATE_INTERVAL_MILLIS));
      authService = new AuthService(database, this::authEnabled);
      getLogger().info(Branding.label(configurationAdapter) + " database ready: " + databaseConfig.describe() + ".");
    } catch (Exception exception) {
      getLogger().log(Level.SEVERE, "Could not open the " + Branding.label(configurationAdapter)
          + " database. Persistent features are disabled.", exception);
      database = null;
      authService = null;
    }
  }
}
