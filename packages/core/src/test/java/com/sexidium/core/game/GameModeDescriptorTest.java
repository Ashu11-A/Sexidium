package com.sexidium.core.game;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GameModeDescriptorTest {

  @Test
  void fields_areAccessible() {
    GameModeDescriptor desc = new GameModeDescriptor("combat", "pvp", "Combat", 2, List.of("fight", "pvp"));
    assertEquals("combat", desc.modeId());
    assertEquals("pvp", desc.category());
    assertEquals("Combat", desc.displayName());
    assertEquals(2, desc.minPlayers());
    assertEquals(List.of("fight", "pvp"), desc.aliases());
  }

  @Test
  void nullAliases_becomesEmptyList() {
    GameModeDescriptor desc = new GameModeDescriptor("m", "c", "n", 1, null);
    assertNotNull(desc.aliases());
    assertTrue(desc.aliases().isEmpty());
  }

  @Test
  void aliases_isImmutable() {
    GameModeDescriptor desc = new GameModeDescriptor("m", "c", "n", 1, List.of("a"));
    assertThrows(UnsupportedOperationException.class, () -> desc.aliases().add("x"));
  }

  @Test
  void aliases_mutatingOriginalList_doesNotAffectDescriptor() {
    List<String> aliases = new ArrayList<>();
    aliases.add("a");
    GameModeDescriptor desc = new GameModeDescriptor("m", "c", "n", 1, aliases);
    aliases.add("b");
    assertEquals(1, desc.aliases().size());
  }

  @Test
  void equality_byValue() {
    GameModeDescriptor a = new GameModeDescriptor("m", "c", "n", 2, List.of("x"));
    GameModeDescriptor b = new GameModeDescriptor("m", "c", "n", 2, List.of("x"));
    assertEquals(a, b);
  }
}
