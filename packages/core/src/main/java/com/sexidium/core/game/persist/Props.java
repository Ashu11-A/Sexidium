package com.sexidium.core.game.persist;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Props {
  private final Map<String, String> values = new LinkedHashMap<>();

  public Props set(String key, String value) {
    if (key != null && value != null) {
      values.put(key, value);
    }
    return this;
  }

  public Props set(String key, int value) {
    return set(key, Integer.toString(value));
  }

  public Props set(String key, long value) {
    return set(key, Long.toString(value));
  }

  public Props set(String key, boolean value) {
    return set(key, Boolean.toString(value));
  }

  public String get(String key) {
    return values.get(key);
  }

  public String get(String key, String defaultValue) {
    String storedValue = values.get(key);
    return storedValue == null ? defaultValue : storedValue;
  }

  public int getInt(String key, int defaultValue) {
    try {
      String storedValue = values.get(key);
      return storedValue == null ? defaultValue : Integer.parseInt(storedValue);
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  public long getLong(String key, long defaultValue) {
    try {
      String storedValue = values.get(key);
      return storedValue == null ? defaultValue : Long.parseLong(storedValue);
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    String storedValue = values.get(key);
    return storedValue == null ? defaultValue : Boolean.parseBoolean(storedValue);
  }

  public boolean has(String key) {
    return values.containsKey(key);
  }

  public void putAll(Props otherProps) {
    if (otherProps != null) {
      values.putAll(otherProps.values);
    }
  }

  /** A copy of every key→value pair (insertion order), for file persistence. */
  public Map<String, String> asMap() {
    return new LinkedHashMap<>(values);
  }

  /** Builds a Props from a flat key→value map (the inverse of {@link #asMap()}). */
  public static Props fromMap(Map<String, String> source) {
    Props props = new Props();
    if (source != null) {
      source.forEach(props::set);
    }
    return props;
  }

  public String encode() {
    StringBuilder builder = new StringBuilder();
    boolean firstEntry = true;
    for (Map.Entry<String, String> entry : values.entrySet()) {
      if (!firstEntry) {
        builder.append('\n');
      }
      firstEntry = false;
      builder.append(escape(entry.getKey())).append('=').append(escape(entry.getValue()));
    }
    return builder.toString();
  }

  public static Props decode(String encodedValues) {
    Props props = new Props();
    if (encodedValues == null || encodedValues.isBlank()) {
      return props;
    }
    for (String encodedLine : encodedValues.split("\n", -1)) {
      if (encodedLine.isEmpty()) {
        continue;
      }
      int separatorIndex = indexOfUnescapedSeparator(encodedLine);
      if (separatorIndex < 0) {
        continue;
      }
      String decodedKey = unescape(encodedLine.substring(0, separatorIndex));
      String decodedValue = unescape(encodedLine.substring(separatorIndex + 1));
      props.values.put(decodedKey, decodedValue);
    }
    return props;
  }

  private static int indexOfUnescapedSeparator(String encodedLine) {
    boolean escaped = false;
    for (int characterIndex = 0; characterIndex < encodedLine.length(); characterIndex++) {
      char currentCharacter = encodedLine.charAt(characterIndex);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (currentCharacter == '\\') {
        escaped = true;
      } else if (currentCharacter == '=') {
        return characterIndex;
      }
    }
    return -1;
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\=");
  }

  private static String unescape(String value) {
    StringBuilder builder = new StringBuilder(value.length());
    boolean escaped = false;
    for (int characterIndex = 0; characterIndex < value.length(); characterIndex++) {
      char currentCharacter = value.charAt(characterIndex);
      if (escaped) {
        builder.append(currentCharacter == 'n' ? '\n' : currentCharacter);
        escaped = false;
      } else if (currentCharacter == '\\') {
        escaped = true;
      } else {
        builder.append(currentCharacter);
      }
    }
    return builder.toString();
  }
}
