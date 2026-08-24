package com.sexidium.core.lib.data;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProfileTest {

  @Test
  void fields_areAccessibleAsComponents() {
    Profile p = new Profile("uuid-1", "Steve", "discord-99", 500, 3, 10, 20, 30);
    assertEquals("uuid-1", p.uuid());
    assertEquals("Steve", p.name());
    assertEquals("discord-99", p.discordUserId());
    assertEquals(500, p.points());
    assertEquals(3, p.level());
    assertEquals(10, p.wins());
    assertEquals(20, p.kills());
    assertEquals(30, p.games());
  }

  @Test
  void nullDiscordUserId_isAllowed() {
    Profile p = new Profile("uuid-2", "Alex", null, 0, 0, 0, 0, 0);
    assertNull(p.discordUserId());
  }

  @Test
  void equalityByValue() {
    Profile a = new Profile("u", "n", "d", 1, 2, 3, 4, 5);
    Profile b = new Profile("u", "n", "d", 1, 2, 3, 4, 5);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void inequality_whenFieldsDiffer() {
    Profile a = new Profile("u", "n", "d", 1, 2, 3, 4, 5);
    Profile b = new Profile("u", "n", "d", 1, 2, 3, 4, 6);
    assertNotEquals(a, b);
  }
}
