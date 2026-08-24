package com.sexidium.core.platform;

import com.sexidium.core.network.NodeIdentity;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Everything a Sexidium process needs that does <em>not</em> require a Minecraft world.
 *
 * <p>{@link ServerAdapter} extends this and adds the world-bound half — {@code worlds()},
 * {@code ui()}, {@code menus()}, {@code events()}, {@code npcs()}, {@code decor()}. A Velocity proxy
 * implements only what is here, so it never has to return a lying {@code NoopWorldLeaseService} for a
 * {@code worlds()} call that {@code SexidiumCore.start()} would then invoke.</p>
 *
 * <p>Every member below already existed on {@code ServerAdapter}; this interface only draws a line
 * through the middle of it. That is why the change is behaviour-preserving: existing callers keep
 * compiling against {@code ServerAdapter}, and the handful of subsystems that need nothing more than
 * this — {@code BotManager}, {@code BridgeClient}, {@code RankService} — can be retyped one at a time.</p>
 */
public interface NodeRuntime {

  /**
   * Who this process is in the network. Defaulted to standalone so no existing adapter has to change,
   * and so a headless test adapter keeps working untouched.
   */
  default NodeIdentity identity() {
    return NodeIdentity.standalone();
  }

  Path dataDirectory();

  ConfigurationAdapter configuration();

  LoggerAdapter logger();

  ResourceAdapter resources();

  SchedulerAdapter scheduler();

  MessageAdapter messages();

  CommandDispatcherAdapter commands();

  CommandSource console();

  /**
   * Players connected to <em>this</em> node.
   *
   * <p>Declared over {@code ? extends NetworkPlayer} so {@code ServerAdapter} can narrow the return to
   * {@code Collection<PlayerAdapter>} — legal, because {@code Collection<PlayerAdapter>} is a subtype of
   * {@code Collection<? extends NetworkPlayer>}. Callers that only need identity and messaging can take
   * the wide type and work on a proxy too.</p>
   */
  Collection<? extends NetworkPlayer> onlinePlayers();

  Optional<? extends NetworkPlayer> player(UUID playerId);

  Optional<? extends NetworkPlayer> playerExact(String playerName);

  /** Live node info for the Discord bot's server card and the network status heartbeat. */
  default ServerInfoPort serverInfo() {
    return () ->
        new ServerInfoPort.ServerInfo(
            "", 0, onlinePlayers().size(), 0, identity().displayName(), "", -1.0);
  }

  /** Player-skin resolver (SkinsRestorer on Paper) for rendering rank-card avatars. */
  default SkinPort skins() {
    return SkinPort.NOOP;
  }

  /**
   * Tick health and capacity, published on every heartbeat so peers can avoid planning onto a node
   * that is already struggling. Defaults to "cannot answer", which is the truth on a proxy.
   */
  default NodeHealthPort health() {
    return NodeHealthPort.UNAVAILABLE;
  }

  /** Console line stream for the Discord live-console relay. */
  default ConsoleTap consoleTap() {
    return ConsoleTap.NOOP;
  }
}
