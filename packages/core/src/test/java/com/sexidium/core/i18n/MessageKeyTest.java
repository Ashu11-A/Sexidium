package com.sexidium.core.i18n;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageKeyTest {

  @Test
  void path_returnsConfigPath() {
    assertEquals("command.reload", MessageKey.COMMAND_RELOAD.path());
    assertEquals("auth.already-linked", MessageKey.AUTH_ALREADY_LINKED.path());
    assertEquals("command.player-only", MessageKey.COMMAND_PLAYER_ONLY.path());
  }

  @Test
  void allKeys_haveNonBlankPaths() {
    for (MessageKey key : MessageKey.values()) {
      assertFalse(key.path().isBlank(), "Blank path for key: " + key);
    }
  }

  @Test
  void allKeys_pathsAreUnique() {
    var paths = java.util.Arrays.stream(MessageKey.values())
        .map(MessageKey::path)
        .toList();
    assertEquals(paths.size(), new java.util.HashSet<>(paths).size(), "Duplicate paths found");
  }
}
