package com.sexidium.core.lib.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON reader — the inbound counterpart to {@link Json} (which only writes).
 * Parses the small envelopes/payloads the WebSocket bridge exchanges into Java values:
 * {@code Map<String,Object>}, {@code List<Object>}, {@code String}, {@code Double}/{@code Long},
 * {@code Boolean}, {@code null}. Not a general-purpose parser (no streaming, no big-number handling),
 * just enough for the bridge protocol; consistent with the hand-rolled writer's philosophy of avoiding
 * a JSON dependency for a few endpoints.
 */
public final class JsonReader {
  private final String source;
  private int index;

  private JsonReader(String source) {
    this.source = source;
  }

  /** Parse a JSON document. Returns the parsed value, or throws {@link IllegalArgumentException}. */
  public static Object parse(String json) {
    if (json == null) {
      throw new IllegalArgumentException("null json");
    }
    JsonReader reader = new JsonReader(json);
    reader.skipWhitespace();
    Object value = reader.readValue();
    reader.skipWhitespace();
    if (reader.index != json.length()) {
      throw new IllegalArgumentException("trailing content at " + reader.index);
    }
    return value;
  }

  /** Convenience: parse and cast to an object map (empty map when not an object). */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object value = parse(json);
    return value instanceof Map<?, ?> map ? (Map<String, Object>) map : new LinkedHashMap<>();
  }

  private Object readValue() {
    char c = peek();
    return switch (c) {
      case '{' -> readObject();
      case '[' -> readArray();
      case '"' -> readString();
      case 't', 'f' -> readBoolean();
      case 'n' -> readNull();
      default -> readNumber();
    };
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<>();
    expect('{');
    skipWhitespace();
    if (peek() == '}') {
      index++;
      return map;
    }
    while (true) {
      skipWhitespace();
      String key = readString();
      skipWhitespace();
      expect(':');
      skipWhitespace();
      map.put(key, readValue());
      skipWhitespace();
      char c = next();
      if (c == '}') {
        return map;
      }
      if (c != ',') {
        throw error("expected ',' or '}'");
      }
    }
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<>();
    expect('[');
    skipWhitespace();
    if (peek() == ']') {
      index++;
      return list;
    }
    while (true) {
      skipWhitespace();
      list.add(readValue());
      skipWhitespace();
      char c = next();
      if (c == ']') {
        return list;
      }
      if (c != ',') {
        throw error("expected ',' or ']'");
      }
    }
  }

  private String readString() {
    expect('"');
    StringBuilder builder = new StringBuilder();
    while (true) {
      char c = next();
      if (c == '"') {
        return builder.toString();
      }
      if (c == '\\') {
        char escaped = next();
        switch (escaped) {
          case '"' -> builder.append('"');
          case '\\' -> builder.append('\\');
          case '/' -> builder.append('/');
          case 'b' -> builder.append('\b');
          case 'f' -> builder.append('\f');
          case 'n' -> builder.append('\n');
          case 'r' -> builder.append('\r');
          case 't' -> builder.append('\t');
          case 'u' -> {
            String hex = source.substring(index, index + 4);
            index += 4;
            builder.append((char) Integer.parseInt(hex, 16));
          }
          default -> throw error("bad escape \\" + escaped);
        }
      } else {
        builder.append(c);
      }
    }
  }

  private Object readNumber() {
    int start = index;
    while (index < source.length()) {
      char c = source.charAt(index);
      if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
        index++;
      } else {
        break;
      }
    }
    String token = source.substring(start, index);
    if (token.isEmpty()) {
      throw error("expected value");
    }
    if (token.indexOf('.') >= 0 || token.indexOf('e') >= 0 || token.indexOf('E') >= 0) {
      return Double.parseDouble(token);
    }
    try {
      return Long.parseLong(token);
    } catch (NumberFormatException exception) {
      return Double.parseDouble(token);
    }
  }

  private Boolean readBoolean() {
    if (source.startsWith("true", index)) {
      index += 4;
      return Boolean.TRUE;
    }
    if (source.startsWith("false", index)) {
      index += 5;
      return Boolean.FALSE;
    }
    throw error("expected boolean");
  }

  private Object readNull() {
    if (source.startsWith("null", index)) {
      index += 4;
      return null;
    }
    throw error("expected null");
  }

  private void skipWhitespace() {
    while (index < source.length()) {
      char c = source.charAt(index);
      if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
        index++;
      } else {
        break;
      }
    }
  }

  private char peek() {
    if (index >= source.length()) {
      throw error("unexpected end of input");
    }
    return source.charAt(index);
  }

  private char next() {
    if (index >= source.length()) {
      throw error("unexpected end of input");
    }
    return source.charAt(index++);
  }

  private void expect(char expected) {
    char c = next();
    if (c != expected) {
      throw error("expected '" + expected + "' but found '" + c + "'");
    }
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException("JSON parse error at " + index + ": " + message);
  }
}
