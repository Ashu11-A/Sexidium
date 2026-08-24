package com.sexidium.core.command;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.SexidiumCoreDependencies;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.lib.data.Database;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared test harness for the {@code CoreCommandService} suite: wires up a
 * {@link SexidiumCore} with fake platform adapters and a {@link CoreCommandService}.
 * Extracted verbatim from the former {@code Fixture} nested class of
 * {@code CoreCommandServiceAdvancedTest}.
 */
final class CommandTestFixture implements AutoCloseable {
  final SexidiumCore core;
  CoreCommandService commands;
  final FakeServerAdapter server;
  final FakeKitAdapter kits;
  final CapturingMessages messages;
  final AtomicInteger reloadCount = new AtomicInteger();

  private CommandTestFixture(SexidiumCore core, CoreCommandService commands, FakeServerAdapter server,
                             FakeKitAdapter kits, CapturingMessages messages) {
    this.core = core;
    this.commands = commands;
    this.server = server;
    this.kits = kits;
    this.messages = messages;
  }

  static CommandTestFixture create(Path tempDir, Database database, boolean authEnabled) {
    return createInternal(tempDir, database, authEnabled, true);
  }

  static CommandTestFixture createEmpty(Path tempDir, boolean authEnabled) {
    return createInternal(tempDir, null, authEnabled, false);
  }

  private static CommandTestFixture createInternal(Path tempDir, Database database, boolean authEnabled, boolean withModes) {
    GameRegistry registry = new GameRegistry();
    if (withModes) {
      registry.register(new GameModeDescriptor("combat", "minigames", "Combat", 1, List.of()),
          (context, id, args) -> new StubGame("combat"));
      registry.register(new GameModeDescriptor("gravity", "experience", "Gravity", 1, List.of()),
          (context, id, args) -> new StubGame("gravity"));
    }
    FakeKitAdapter kits = new FakeKitAdapter(withModes ? Set.of("pvp", "builder") : Set.of());
    FakeServerAdapter server = new FakeServerAdapter(tempDir);
    CapturingMessages messages = new CapturingMessages();
    server.setMessages(messages);
    SexidiumCore core = new SexidiumCore(new SexidiumCoreDependencies(
        server, kits, registry, database, null, () -> authEnabled));
    CommandTestFixture fixture = new CommandTestFixture(core, null, server, kits, messages);
    CoreCommandService commands = new CoreCommandService(core, fixture.reloadCount::incrementAndGet);
    fixture.commands = commands;
    return fixture;
  }

  FakePlayer admin(String name) {
    FakePlayer player = new FakePlayer(name, Set.of(CoreCommandService.ADMIN_PERMISSION, CoreCommandService.PLAY_PERMISSION));
    server.players.put(name.toLowerCase(Locale.ROOT), player);
    return player;
  }

  FakePlayer player(String name, String... permissions) {
    Set<String> all = new LinkedHashSet<>();
    all.add(CoreCommandService.PLAY_PERMISSION);
    for (String permission : permissions) {
      all.add(permission);
    }
    FakePlayer player = new FakePlayer(name, all);
    server.players.put(name.toLowerCase(Locale.ROOT), player);
    return player;
  }

  @Override public void close() {
    try { core.close(); } catch (Exception ignored) {}
  }
}
