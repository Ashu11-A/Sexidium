package com.sexidium.core.lib.net;

import com.sexidium.core.lib.data.Profile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

  private Profile profile(String uuid, String name, String discord) {
    return new Profile(uuid, name, discord, 100, 2, 5, 10, 15);
  }

  @Test
  void of_singleProfile_includesAllFields() {
    String json = Json.of(profile("uuid-1", "Steve", "discord-123"));
    assertTrue(json.contains("\"uuid\":\"uuid-1\""));
    assertTrue(json.contains("\"name\":\"Steve\""));
    assertTrue(json.contains("\"discordUserId\":\"discord-123\""));
    assertTrue(json.contains("\"points\":100"));
    assertTrue(json.contains("\"level\":2"));
    assertTrue(json.contains("\"wins\":5"));
    assertTrue(json.contains("\"kills\":10"));
    assertTrue(json.contains("\"games\":15"));
  }

  @Test
  void of_nullDiscordUserId_rendersNull() {
    String json = Json.of(profile("u", "n", null));
    assertTrue(json.contains("\"discordUserId\":null"));
  }

  @Test
  void of_blankDiscordUserId_rendersNull() {
    String json = Json.of(profile("u", "n", "  "));
    assertTrue(json.contains("\"discordUserId\":null"));
  }

  @Test
  void of_profileList_wrapsInArray() {
    String json = Json.of(List.of(profile("u1", "A", null), profile("u2", "B", "d")));
    assertTrue(json.startsWith("["));
    assertTrue(json.endsWith("]"));
    assertTrue(json.contains("\"uuid\":\"u1\""));
    assertTrue(json.contains("\"uuid\":\"u2\""));
  }

  @Test
  void of_emptyList_returnsEmptyArray() {
    assertEquals("[]", Json.of(List.of()));
  }

  @Test
  void escape_quotes() {
    assertEquals("\\\"hello\\\"", Json.escape("\"hello\""));
  }

  @Test
  void escape_backslash() {
    assertEquals("\\\\", Json.escape("\\"));
  }

  @Test
  void escape_newline() {
    assertEquals("\\n", Json.escape("\n"));
  }

  @Test
  void escape_carriageReturn() {
    assertEquals("\\r", Json.escape("\r"));
  }

  @Test
  void escape_tab() {
    assertEquals("\\t", Json.escape("\t"));
  }

  @Test
  void escape_controlChar() {
    String result = Json.escape("");
    assertEquals("\\u0001", result);
  }

  @Test
  void escape_normalString_unchanged() {
    assertEquals("hello world", Json.escape("hello world"));
  }

  @Test
  void of_profileWrappedInBraces() {
    String json = Json.of(profile("u", "n", null));
    assertTrue(json.startsWith("{"));
    assertTrue(json.endsWith("}"));
  }
}
