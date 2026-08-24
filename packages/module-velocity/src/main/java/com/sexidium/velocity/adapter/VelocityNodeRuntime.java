package com.sexidium.velocity.adapter;

import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.network.NetworkSettings;
import com.sexidium.core.network.NodeIdentity;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.NetworkPlayer;
import com.sexidium.core.platform.NodeRuntime;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerInfoPort;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The proxy's implementation of {@link NodeRuntime}.
 *
 * <p>This is the whole reason the SPI was split. Implementing {@code ServerAdapter} here would mean
 * providing {@code worlds()}, {@code ui()}, {@code events()}, {@code menus()}, {@code npcs()} and
 * {@code decor()} — six subsystems a proxy has no way to honour — and {@code SexidiumCore.start()}
 * would then call {@code worlds().start()} on the no-op it was handed.</p>
 */
public final class VelocityNodeRuntime implements NodeRuntime {

  private final ProxyServer proxy;
  private final Path dataDirectory;
  private final ConfigurationAdapter configuration;
  private final LoggerAdapter logger;
  private final ResourceAdapter resources;
  private final SchedulerAdapter scheduler;
  private final VelocityMessageAdapter messages;
  private final CommandSource console;
  private final NodeIdentity identity;

  public VelocityNodeRuntime(ProxyServer proxy, Logger slf4j, Path dataDirectory, Object plugin) {
    this.proxy = proxy;
    this.dataDirectory = dataDirectory;
    this.logger = new VelocityLoggerAdapter(slf4j);
    this.resources = new ClassLoaderResourceAdapter(VelocityNodeRuntime.class.getClassLoader());
    this.configuration = new YamlConfigurationAdapter(dataDirectory.resolve("config.yml"));
    this.scheduler = new VelocitySchedulerAdapter(proxy, plugin);
    this.identity = NetworkSettings.resolve(configuration);

    MessageService messageService = new MessageService(resources, configuration, logger);
    this.messages = new VelocityMessageAdapter(proxy, messageService);
    this.console = new VelocityConsoleSource(
        proxy.getConsoleCommandSource(),
        Locale.forLanguageTag(configuration.getString("messages.console-language", "en")));
  }

  @Override
  public NodeIdentity identity() {
    return identity;
  }

  @Override
  public Path dataDirectory() {
    return dataDirectory;
  }

  @Override
  public ConfigurationAdapter configuration() {
    return configuration;
  }

  @Override
  public LoggerAdapter logger() {
    return logger;
  }

  @Override
  public ResourceAdapter resources() {
    return resources;
  }

  @Override
  public SchedulerAdapter scheduler() {
    return scheduler;
  }

  @Override
  public MessageAdapter messages() {
    return messages;
  }

  /**
   * Console command dispatch on the proxy runs PROXY commands, not backend ones.
   *
   * <p>A backend-bound command has to name the node it is for, which needs the message bus rather
   * than this seam. Until that exists, dispatching here silently running a proxy command that
   * happened to share a name would be worse than refusing.</p>
   */
  @Override
  public CommandDispatcherAdapter commands() {
    return commandLine -> proxy.getCommandManager()
        .executeAsync(proxy.getConsoleCommandSource(), commandLine);
  }

  @Override
  public CommandSource console() {
    return console;
  }

  @Override
  public Collection<? extends NetworkPlayer> onlinePlayers() {
    return proxy.getAllPlayers().stream()
        .map(player -> new VelocityPlayer(player, identity.nodeId()))
        .map(NetworkPlayer.class::cast)
        .toList();
  }

  @Override
  public Optional<? extends NetworkPlayer> player(UUID playerId) {
    return proxy.getPlayer(playerId).map(player -> new VelocityPlayer(player, identity.nodeId()));
  }

  @Override
  public Optional<? extends NetworkPlayer> playerExact(String playerName) {
    return proxy.getPlayer(playerName).map(player -> new VelocityPlayer(player, identity.nodeId()));
  }

  /**
   * Network-wide counts, not this process's.
   *
   * <p>The proxy is the only component that sees every player, so its {@code onlinePlayers()} is
   * already the network total — which is what the Discord server card should show.</p>
   */
  @Override
  public ServerInfoPort serverInfo() {
    return () -> {
      List<String> backends = proxy.getAllServers().stream()
          .map(server -> server.getServerInfo().getName())
          .toList();
      return new ServerInfoPort.ServerInfo(
          proxy.getBoundAddress().getHostString(),
          proxy.getBoundAddress().getPort(),
          proxy.getPlayerCount(),
          proxy.getConfiguration().getShowMaxPlayers(),
          identity.displayName() + " (" + backends.size() + " backends)",
          proxy.getVersion().getVersion(),
          -1.0);
    };
  }
}
