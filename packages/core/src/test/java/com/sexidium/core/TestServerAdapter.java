package com.sexidium.core;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.*;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.noop.*;

import java.nio.file.Path;
import java.util.*;

/**
 * Minimal no-op server adapter for unit tests.
 * All methods return safe defaults; no Minecraft platform calls are made.
 *
 * <p>Not final: a test that needs one behaviour to be real -- resolving a player by id, say --
 * overrides that method alone instead of restating the whole interface.</p>
 */
public class TestServerAdapter implements ServerAdapter {
  private final ConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
  private static final MessageAdapter NOOP_MESSAGES = new MessageAdapter() {
    @Override public void send(CommandSource s, LocalizedText t) {}
    @Override public void send(CommandSource s, String m) {}
    @Override public void raw(CommandSource s, LocalizedText t) {}
    @Override public void raw(CommandSource s, String m) {}
    @Override public void broadcast(LocalizedText t) {}
    @Override public void broadcast(String m) {}
  };

  @Override public String serverName() { return "Test"; }
  @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
  @Override public Path dataDirectory() { return Path.of("."); }
  @Override public ConfigurationAdapter configuration() { return configuration; }
  @Override public LoggerAdapter logger() { return new StdoutLoggerAdapter("Test"); }
  @Override public ResourceAdapter resources() { return new ClassLoaderResourceAdapter(null); }
  @Override public SchedulerAdapter scheduler() { return new DirectSchedulerAdapter(); }
  @Override public UiAdapter ui() { return new NoopUiAdapter(); }
  @Override public MessageAdapter messages() { return NOOP_MESSAGES; }
  @Override public EventDispatcherAdapter events() { return new NoopEventDispatcherAdapter(); }
  @Override public CommandDispatcherAdapter commands() { return new NoopCommandDispatcherAdapter(); }
  @Override public WorldLeaseService worlds() { return new NoopWorldLeaseService(); }
  @Override public CommandSource console() { return null; }
  @Override public Collection<PlayerAdapter> onlinePlayers() { return List.of(); }
  @Override public Optional<PlayerAdapter> player(UUID id) { return Optional.empty(); }
  @Override public Optional<PlayerAdapter> playerExact(String name) { return Optional.empty(); }
}
