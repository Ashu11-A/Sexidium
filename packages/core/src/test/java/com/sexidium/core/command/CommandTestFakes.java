package com.sexidium.core.command;

import com.sexidium.core.game.Game;
import com.sexidium.core.game.GameState;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.CommandDispatcherAdapter;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.EventDispatcherAdapter;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.SchedulerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.UiAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.DirectSchedulerAdapter;
import com.sexidium.core.platform.noop.NoopCommandDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopEventDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopUiAdapter;
import com.sexidium.core.platform.noop.NoopWorldLeaseService;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fake platform adapters shared by the {@code CoreCommandService} test suite.
 * Extracted verbatim from the former nested classes of
 * {@code CoreCommandServiceAdvancedTest}.
 */
final class CommandTestFakes {
  private CommandTestFakes() {}
}

final class FakeServerAdapter implements ServerAdapter {
  final Map<String, FakePlayer> players = new LinkedHashMap<>();
  private MessageAdapter messages = new CapturingMessages();
  private final ConfigurationAdapter configuration = new PropertiesConfigurationAdapter();
  private final Path dataDir;

  FakeServerAdapter(Path dataDir) {
    this.dataDir = dataDir;
    this.configuration.set("api.port", 0);
  }

  void setMessages(MessageAdapter messages) { this.messages = messages; }

  @Override public String serverName() { return "Test"; }
  @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
  @Override public Path dataDirectory() { return dataDir; }
  @Override public ConfigurationAdapter configuration() { return configuration; }
  @Override public LoggerAdapter logger() { return new StdoutLoggerAdapter("Test"); }
  @Override public ResourceAdapter resources() { return new ClassLoaderResourceAdapter(null); }
  @Override public SchedulerAdapter scheduler() { return new DirectSchedulerAdapter(); }
  @Override public UiAdapter ui() { return new NoopUiAdapter(); }
  @Override public MessageAdapter messages() { return messages; }
  @Override public EventDispatcherAdapter events() { return new NoopEventDispatcherAdapter(); }
  @Override public CommandDispatcherAdapter commands() { return new NoopCommandDispatcherAdapter(); }
  @Override public WorldLeaseService worlds() { return new NoopWorldLeaseService(); }
  @Override public CommandSource console() { return new FakeSource("Console", Set.of(CoreCommandService.ADMIN_PERMISSION)); }
  @Override public Collection<PlayerAdapter> onlinePlayers() { return new ArrayList<>(players.values()); }
  @Override public Optional<PlayerAdapter> player(UUID playerId) {
    return players.values().stream().filter(player -> player.uniqueId().equals(playerId)).map(player -> (PlayerAdapter) player).findFirst();
  }
  @Override public Optional<PlayerAdapter> playerExact(String playerName) {
    return Optional.ofNullable(players.get((playerName == null ? "" : playerName).toLowerCase(Locale.ROOT)));
  }
}

final class FakeKitAdapter implements KitAdapter {
  private final Set<String> names;
  private final Map<UUID, String> given = new LinkedHashMap<>();
  FakeKitAdapter(Set<String> names) { this.names = new LinkedHashSet<>(names); }
  @Override public boolean apply(PlayerAdapter playerAdapter, String kitName) {
    if (!exists(kitName)) return false;
    given.put(playerAdapter.uniqueId(), kitName);
    return true;
  }
  @Override public boolean exists(String kitName) { return names.contains(kitName); }
  @Override public Set<String> names() { return names; }
  @Override public void reload() {}
}

final class CapturingMessages implements MessageAdapter {
  final List<String> sent = new ArrayList<>();
  @Override public void send(CommandSource source, LocalizedText text) { sent.add(text.messageKey().path()); }
  @Override public void send(CommandSource source, String miniMessage) { sent.add(miniMessage); }
  @Override public void raw(CommandSource source, LocalizedText text) { sent.add(text.messageKey().path()); }
  @Override public void raw(CommandSource source, String miniMessage) { sent.add(miniMessage); }
  @Override public void broadcast(LocalizedText text) { sent.add(text.messageKey().path()); }
  @Override public void broadcast(String miniMessage) { sent.add(miniMessage); }
}

final class FakeInventory implements InventoryAdapter {
  @Override public void clear() {}
  @Override public boolean contains(ItemKey itemKey) { return false; }
  @Override public void add(ItemStackData itemStackData) {}
  @Override public List<ItemStackData> storageContents() { return List.of(); }
  @Override public void setStorageContents(List<ItemStackData> itemStacks) {}
  @Override public Map<String, List<ItemStackData>> equipmentContents() { return Map.of(); }
  @Override public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) {}
}

final class StubGame implements Game {
  private final String id;
  StubGame(String id) { this.id = id; }
  @Override public String id() { return id; }
  @Override public String displayName() { return id; }
  @Override public int minPlayers() { return 1; }
  @Override public void start(List<PlayerAdapter> players) {}
  @Override public void stop(LocalizedText reason) {}
  @Override public GameState state() { return GameState.IDLE; }
}
