package com.sexidium.velocity.adapter;

import com.sexidium.core.network.NetworkSettings;
import com.sexidium.core.network.NodeCapability;
import com.sexidium.core.network.NodeIdentity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigurationAdapterTest {

  @TempDir
  Path tmp;

  private YamlConfigurationAdapter write(String yaml) throws IOException {
    Path file = tmp.resolve("config.yml");
    Files.writeString(file, yaml);
    return new YamlConfigurationAdapter(file);
  }

  @Test
  @DisplayName("nested keys flatten to the dotted paths ConfigurationAdapter addresses")
  void nestedKeys_flatten() throws IOException {
    YamlConfigurationAdapter config = write("""
        network:
          enabled: true
          node:
            id: 'proxy'
            role: proxy
        messages:
          console-language: en
        """);

    assertTrue(config.getBoolean("network.enabled", false));
    assertEquals("proxy", config.getString("network.node.id", ""));
    assertEquals("proxy", config.getString("network.node.role", ""));
    assertEquals("en", config.getString("messages.console-language", ""));
  }

  @Test
  @DisplayName("dedent closes nested sections so sibling keys do not inherit a stale prefix")
  void dedent_closesSections() throws IOException {
    YamlConfigurationAdapter config = write("""
        network:
          node:
            id: 'worker-1'
        database:
          type: postgres
        """);

    assertEquals("worker-1", config.getString("network.node.id", ""));
    assertEquals("postgres", config.getString("database.type", ""));
    // The bug this guards: `database.type` being filed under network.node.database.type.
    assertFalse(config.contains("network.node.database.type"));
  }

  @Test
  @DisplayName("comments are stripped, but a MiniMessage hex colour is not mistaken for one")
  void comments_doNotEatHexColours() throws IOException {
    YamlConfigurationAdapter config = write("""
        # leading comment
        messages:
          prefix: '<gradient:#ff5f6d:#ffc371><bold>Sexidium</bold></gradient>'  # trailing
          plain: value # note
        """);

    assertEquals("<gradient:#ff5f6d:#ffc371><bold>Sexidium</bold></gradient>",
        config.getString("messages.prefix", ""));
    assertEquals("value", config.getString("messages.plain", ""));
  }

  @Test
  @DisplayName("sequences load as string lists, in both inline-empty and block form")
  void sequences() throws IOException {
    YamlConfigurationAdapter config = write("""
        network:
          node:
            capabilities:
              - experiences
              - queue-authority
          other:
            capabilities: []
        """);

    assertEquals(java.util.List.of("experiences", "queue-authority"),
        config.getStringList("network.node.capabilities"));
    assertTrue(config.getStringList("network.other.capabilities").isEmpty());
  }

  @Test
  @DisplayName("quoted values are unquoted; numbers and booleans parse")
  void scalarTypes() throws IOException {
    YamlConfigurationAdapter config = write("""
        api:
          port: 8787
          token: "change-me"
          enabled: false
        ui:
          scale: 1.5
        """);

    assertEquals(8787, config.getInt("api.port", 0));
    assertEquals("change-me", config.getString("api.token", ""));
    assertFalse(config.getBoolean("api.enabled", true));
    assertEquals(1.5, config.getDouble("ui.scale", 0.0), 0.0001);
  }

  @Test
  @DisplayName("a missing file yields defaults rather than throwing")
  void missingFile_isEmpty() {
    YamlConfigurationAdapter config =
        new YamlConfigurationAdapter(tmp.resolve("does-not-exist.yml"));

    assertEquals("fallback", config.getString("anything", "fallback"));
    assertFalse(config.contains("anything"));
  }

  @Test
  @DisplayName("a non-numeric value falls back instead of throwing")
  void badNumber_fallsBack() throws IOException {
    YamlConfigurationAdapter config = write("api:\n  port: not-a-number\n");
    assertEquals(25565, config.getInt("api.port", 25565));
  }

  @Test
  @DisplayName("save() refuses rather than silently destroying config.yml's comments")
  void save_refuses() throws IOException {
    YamlConfigurationAdapter config = write("network:\n  enabled: true\n");
    assertThrows(UnsupportedOperationException.class, config::save);
  }

  @Test
  @DisplayName("end to end: the config the provisioner seeds resolves to a proxy identity")
  void seededProxyConfig_resolvesToProxyIdentity() throws IOException {
    // Byte-for-byte the block velocity::install_plugin writes.
    YamlConfigurationAdapter config = write("""
        # Sexidium proxy configuration. Seeded by docker/provision.sh.
        network:
          enabled: true
          node:
            id: 'proxy'
            display-name: 'Sexidium Network'
            role: proxy
            capabilities: []

        messages:
          default-language: en
          console-language: en

        auth:
          enabled: true
          require-for-login: true
          session:
            enabled: false
          approval:
            enabled: false
          premium:
            enabled: false
          hold:
            enabled: false
        """);

    NodeIdentity identity = NetworkSettings.resolve(config);

    assertFalse(identity.isStandalone());
    assertEquals("proxy", identity.nodeId());
    assertEquals("Sexidium Network", identity.displayName());
    assertTrue(identity.can(NodeCapability.ROUTER));
    // NOT BOT_HOST: the proxy runs module-velocity, which never builds SexidiumCore and so cannot
    // start a bot at all. The capability lives on the lobby, where the scripts always pointed it.
    assertFalse(identity.can(NodeCapability.BOT_HOST));
    // The gate is set EXPLICITLY here rather than left at `auto`, because `auto` resolves through
    // bot.enabled/bot.token -- keys that live on the bot's node, so on a proxy it means OFF.
    assertTrue(config.getBoolean("auth.enabled", false));
    assertEquals("true", config.getString("auth.require-for-login", "auto"));
    assertFalse(config.getBoolean("auth.session.enabled", true));
    assertFalse(config.getBoolean("auth.premium.enabled", true));
    assertFalse(config.getBoolean("auth.hold.enabled", true));
    // A proxy must never claim a world-bound capability it cannot honour.
    assertFalse(identity.can(NodeCapability.LOBBY));
    assertFalse(identity.can(NodeCapability.EXPERIENCES));
    assertFalse(identity.can(NodeCapability.MINIGAMES));
  }
}
