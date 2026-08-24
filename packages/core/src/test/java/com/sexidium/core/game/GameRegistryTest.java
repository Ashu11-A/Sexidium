package com.sexidium.core.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameRegistryTest {

  private GameModeDescriptor descriptor(String modeId, String... aliases) {
    return new GameModeDescriptor(modeId, "test", "Test Mode", 2, List.of(aliases));
  }

  @Test
  void register_nullDescriptor_doesNotCrash() {
    assertDoesNotThrow(() -> new GameRegistry().register(null, (ctx, id, args) -> null));
  }

  @Test
  void register_nullFactory_doesNotCrash() {
    assertDoesNotThrow(() -> new GameRegistry().register(descriptor("mode"), null));
  }

  @Test
  void contains_returnsFalse_whenEmpty() {
    assertFalse(new GameRegistry().contains("combat"));
  }

  @Test
  void contains_returnsTrueAfterRegister() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("combat"), (ctx, id, args) -> null);
    assertTrue(registry.contains("combat"));
  }

  @Test
  void contains_isCaseInsensitive() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("Combat"), (ctx, id, args) -> null);
    assertTrue(registry.contains("combat"));
    assertTrue(registry.contains("COMBAT"));
  }

  @Test
  void contains_ignoresDashesAndUnderscores() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("tnt-war"), (ctx, id, args) -> null);
    assertTrue(registry.contains("tntwar"));
    assertTrue(registry.contains("tnt_war"));
    assertTrue(registry.contains("TNT WAR"));
  }

  @Test
  void contains_withAlias() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("tntwar", "tnt", "war"), (ctx, id, args) -> null);
    assertTrue(registry.contains("tnt"));
    assertTrue(registry.contains("war"));
  }

  @Test
  void create_unknownMode_returnsEmpty() {
    assertTrue(new GameRegistry().create(null, "unknown", List.of()).isEmpty());
  }

  @Test
  void create_knownMode_returnsGame() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("combat"), (ctx, id, args) -> new StubGame());
    assertTrue(registry.create(null, "combat", List.of()).isPresent());
  }

  @Test
  void create_factoryReturningNull_returnsEmpty() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("mode"), (ctx, id, args) -> null);
    assertTrue(registry.create(null, "mode", List.of()).isEmpty());
  }

  @Test
  void modeIds_returnsRegisteredIds() {
    GameRegistry registry = new GameRegistry();
    registry.register(descriptor("alpha"), (ctx, id, args) -> null);
    registry.register(descriptor("beta"), (ctx, id, args) -> null);
    List<String> ids = registry.modeIds();
    assertTrue(ids.contains("alpha"));
    assertTrue(ids.contains("beta"));
  }

  @Test
  void descriptors_returnsRegisteredDescriptors() {
    GameRegistry registry = new GameRegistry();
    GameModeDescriptor d = descriptor("mymode");
    registry.register(d, (ctx, id, args) -> null);
    assertTrue(registry.descriptors().contains(d));
  }

  @Test
  void descriptors_isImmutable() {
    GameRegistry registry = new GameRegistry();
    assertThrows(UnsupportedOperationException.class,
        () -> registry.descriptors().add(descriptor("x")));
  }

  // Minimal stub game for testing
  private static class StubGame implements Game {
    @Override public String id() { return "stub"; }
    @Override public String displayName() { return "Stub"; }
    @Override public int minPlayers() { return 1; }
    @Override public void start(List<com.sexidium.core.platform.PlayerAdapter> players) {}
    @Override public void stop(com.sexidium.core.i18n.LocalizedText reason) {}
    @Override public GameState state() { return GameState.IDLE; }
  }
}
