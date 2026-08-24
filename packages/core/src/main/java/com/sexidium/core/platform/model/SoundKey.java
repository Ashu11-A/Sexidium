package com.sexidium.core.platform.model;

public record SoundKey(String value) {
  public SoundKey {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Sound key cannot be blank.");
    }
  }
}
