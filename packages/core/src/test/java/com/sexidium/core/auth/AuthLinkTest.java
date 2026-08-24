package com.sexidium.core.auth;
import com.sexidium.core.auth.AuthResults.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuthLinkTest {

  @Test
  void fields_areAccessible() {
    AuthLink link = new AuthLink("discord-1", "mc-uuid-1", "Steve");
    assertEquals("discord-1", link.discordUserId());
    assertEquals("mc-uuid-1", link.minecraftUuid());
    assertEquals("Steve", link.minecraftName());
  }

  @Test
  void equality_byValue() {
    AuthLink a = new AuthLink("d", "u", "n");
    AuthLink b = new AuthLink("d", "u", "n");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void inequality_whenFieldsDiffer() {
    AuthLink a = new AuthLink("d1", "u", "n");
    AuthLink b = new AuthLink("d2", "u", "n");
    assertNotEquals(a, b);
  }
}
