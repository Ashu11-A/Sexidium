package com.sexidium.core.command;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.SexidiumCoreDependencies;
import com.sexidium.core.game.Game;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
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
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.PlatformType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.DirectSchedulerAdapter;
import com.sexidium.core.platform.noop.NoopCommandDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopEventDispatcherAdapter;
import com.sexidium.core.platform.noop.NoopUiAdapter;
import com.sexidium.core.platform.noop.NoopWorldLeaseService;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreCommandServiceTest {
  @Test
  void suggestRoot_respectsPermissions() {
    Fixture fixture = fixture();
    FakePlayer admin = fixture.player("Admin", CoreCommandService.ADMIN_PERMISSION);
    FakePlayer player = fixture.player("Player", CoreCommandService.PLAY_PERMISSION, CoreCommandService.AUTH_PERMISSION);

    assertTrue(fixture.commands.suggest(admin, new String[]{""}).contains("start"));
    assertTrue(fixture.commands.suggest(admin, new String[]{""}).contains("admin"));
    assertTrue(fixture.commands.suggest(player, new String[]{""}).contains("lobby"));
    assertTrue(fixture.commands.suggest(player, new String[]{""}).contains("auth"));
    // Players may create their own experiences with /sx start experience, so "start" is offered...
    assertTrue(fixture.commands.suggest(player, new String[]{""}).contains("start"));
    // ...but the admin-only namespace (which now holds bot/npc/map/kit/reload/stop) still is not.
    assertFalse(fixture.commands.suggest(player, new String[]{""}).contains("admin"));
  }

  @Test
  void suggestStart_offersCategoriesThenModes() {
    Fixture fixture = fixture();
    FakePlayer admin = fixture.player("Admin", CoreCommandService.ADMIN_PERMISSION);

    List<String> categories = fixture.commands.suggest(admin, new String[]{"start", ""});
    assertTrue(categories.contains("minigames"));
    assertTrue(categories.contains("experience"));
    assertFalse(categories.contains("combat"), "bare mode ids should not be offered before a category");
    assertEquals(List.of("combat"), fixture.commands.suggest(admin, new String[]{"start", "minigames", "co"}));
    // After "experience", the selectable challenge ids are offered (multiple may be chosen).
    assertTrue(fixture.commands.suggest(admin, new String[]{"start", "experience", "xp"}).contains("xphealth"));
  }

  @Test
  void suggestModes_groupsByDescriptorCategory() {
    Fixture fixture = fixture();
    assertEquals(List.of("combat"), fixture.core.games().descriptors().stream()
        .filter(descriptor -> descriptor.category().equals("minigames"))
        .map(GameModeDescriptor::modeId)
        .toList());
  }

  @Test
  void kitGive_acceptsCurrentKitThenPlayerOrder() {
    Fixture fixture = fixture();
    FakePlayer admin = fixture.player("Admin", CoreCommandService.ADMIN_PERMISSION);
    FakePlayer target = fixture.player("Target", CoreCommandService.PLAY_PERMISSION);

    fixture.commands.execute(admin, new String[]{"admin", "kit", "give", "pvp", "Target"});

    assertEquals("pvp", fixture.kits.given.get(target.uniqueId()));
  }

  @Test
  void kitGive_acceptsLegacyPlayerThenKitOrder() {
    Fixture fixture = fixture();
    FakePlayer admin = fixture.player("Admin", CoreCommandService.ADMIN_PERMISSION);
    FakePlayer target = fixture.player("Target", CoreCommandService.PLAY_PERMISSION);

    fixture.commands.execute(admin, new String[]{"admin", "kit", "give", "Target", "builder"});

    assertEquals("builder", fixture.kits.given.get(target.uniqueId()));
  }

  private Fixture fixture() {
    GameRegistry registry = new GameRegistry();
    registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()), (context, id, args) -> new StubGame("combat"));
    registry.register(new GameModeDescriptor("gravity", "experience", "Gravity", 1, List.of()), (context, id, args) -> new StubGame("gravity"));
    FakeKitAdapter kits = new FakeKitAdapter(Set.of("pvp", "builder"));
    FakeServerAdapter server = new FakeServerAdapter();
    SexidiumCore core = new SexidiumCore(new SexidiumCoreDependencies(server, kits, registry, null, null, () -> true));
    return new Fixture(core, new CoreCommandService(core, core::reload), server, kits);
  }

  private record Fixture(SexidiumCore core, CoreCommandService commands, FakeServerAdapter server, FakeKitAdapter kits) {
    FakePlayer player(String name, String... permissions) {
      FakePlayer player = new FakePlayer(name, Set.of(permissions));
      server.players.put(name.toLowerCase(Locale.ROOT), player);
      return player;
    }
  }

  private static final class FakeServerAdapter implements ServerAdapter {
    private final Map<String, FakePlayer> players = new LinkedHashMap<>();
    private final MessageAdapter messages = new CapturingMessages();
    private final ConfigurationAdapter configuration = new PropertiesConfigurationAdapter();

    @Override public String serverName() { return "Test"; }
    @Override public PlatformType platformType() { return PlatformType.UNKNOWN; }
    @Override public Path dataDirectory() { return Path.of("."); }
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
    @Override public Collection<PlayerAdapter> onlinePlayers() { return List.copyOf(players.values()); }
    @Override public Optional<PlayerAdapter> player(UUID playerId) {
      return players.values().stream().filter(player -> player.uniqueId().equals(playerId)).map(player -> (PlayerAdapter) player).findFirst();
    }
    @Override public Optional<PlayerAdapter> playerExact(String playerName) {
      return Optional.ofNullable(players.get((playerName == null ? "" : playerName).toLowerCase(Locale.ROOT)));
    }
  }

  private static class FakeSource implements CommandSource {
    private final String name;
    private final Set<String> permissions;

    FakeSource(String name, Set<String> permissions) {
      this.name = name;
      this.permissions = permissions;
    }

    @Override public String name() { return name; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean hasPermission(String permission) { return permissions.contains(permission); }
    @Override public void sendMiniMessage(String miniMessage) {}
    @Override public void sendPlainMessage(String message) {}
  }

  private static final class FakePlayer extends FakeSource implements PlayerAdapter {
    private final UUID id = UUID.randomUUID();
    private final InventoryAdapter inventory = new FakeInventory();

    FakePlayer(String name, Set<String> permissions) {
      super(name, permissions);
    }

    @Override public UUID uniqueId() { return id; }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) {}
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) {}
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double health) {}
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) {}
    @Override public InventoryAdapter inventory() { return inventory; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) {}
    @Override public void showTitle(TitleSpec titleSpec) {}
    @Override public void sendActionBar(String miniMessage) {}
    @Override public void setCompassTarget(WorldPosition targetPosition) {}
    @Override public void clearInventory() { inventory.clear(); }
    @Override public void clearPotionEffects() {}
  }

  private static final class FakeKitAdapter implements KitAdapter {
    private final Set<String> names;
    private final Map<UUID, String> given = new LinkedHashMap<>();

    FakeKitAdapter(Set<String> names) {
      this.names = new LinkedHashSet<>(names);
    }

    @Override public boolean apply(PlayerAdapter playerAdapter, String kitName) {
      if (!exists(kitName)) {
        return false;
      }
      given.put(playerAdapter.uniqueId(), kitName);
      return true;
    }

    @Override public boolean exists(String kitName) { return names.contains(kitName); }
    @Override public Set<String> names() { return names; }
    @Override public void reload() {}
  }

  private static final class CapturingMessages implements MessageAdapter {
    private final List<String> sent = new ArrayList<>();

    @Override public void send(CommandSource source, LocalizedText text) { sent.add(text.messageKey().path()); }
    @Override public void send(CommandSource source, String miniMessage) { sent.add(miniMessage); }
    @Override public void raw(CommandSource source, LocalizedText text) { sent.add(text.messageKey().path()); }
    @Override public void raw(CommandSource source, String miniMessage) { sent.add(miniMessage); }
    @Override public void broadcast(LocalizedText text) { sent.add(text.messageKey().path()); }
    @Override public void broadcast(String miniMessage) { sent.add(miniMessage); }
  }

  private static final class FakeInventory implements InventoryAdapter {
    @Override public void clear() {}
    @Override public boolean contains(ItemKey itemKey) { return false; }
    @Override public void add(ItemStackData itemStackData) {}
    @Override public List<ItemStackData> storageContents() { return List.of(); }
    @Override public void setStorageContents(List<ItemStackData> itemStacks) {}
    @Override public Map<String, List<ItemStackData>> equipmentContents() { return Map.of(); }
    @Override public void setEquipmentContents(Map<String, List<ItemStackData>> equipmentContents) {}
  }

  private static final class StubGame implements Game {
    private final String id;

    StubGame(String id) {
      this.id = id;
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return id; }
    @Override public int minPlayers() { return 1; }
    @Override public void start(List<PlayerAdapter> players) {}
    @Override public void stop(LocalizedText reason) {}
    @Override public GameState state() { return GameState.IDLE; }
  }
}
