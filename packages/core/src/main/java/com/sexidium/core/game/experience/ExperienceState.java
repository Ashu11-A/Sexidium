package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.Props;

/**
 * The shared, persistent gameplay state of a single experience. Every challenge in the experience
 * reads and writes the same instance (so a stat like the Double Drops multiplier is shared across all
 * connected players), and the values survive a player disconnecting, the last player leaving (the live
 * match ending) and a full server restart — they are flushed to the {@code experiences.challenge_state}
 * column via {@link ExperienceManager}.
 *
 * <p>Keys are plain strings; challenges namespace their own keys by challenge id (see
 * {@link Challenge#stateKey(String)}) so two challenges never collide. Mutating any value marks the
 * state dirty and notifies the owning {@link ExperienceGame}, which debounces the actual DB write.</p>
 */
public final class ExperienceState {
  private final Props props;
  private Runnable onChange = () -> {};

  private ExperienceState(Props props) {
    this.props = props == null ? new Props() : props;
  }

  public static ExperienceState empty() {
    return new ExperienceState(new Props());
  }

  /** Rebuilds the state from a previously {@link #encode() encoded} blob (or empty when blank). */
  public static ExperienceState decode(String encoded) {
    return new ExperienceState(Props.decode(encoded));
  }

  /** Rebuilds the state from a flat key→value map (the file-persisted form). */
  public static ExperienceState fromValues(java.util.Map<String, String> values) {
    return new ExperienceState(Props.fromMap(values));
  }

  /** Every stored key→value pair, for writing to the experience's state file. */
  public java.util.Map<String, String> values() {
    return props.asMap();
  }

  public String encode() {
    return props.encode();
  }

  /** Registers the callback invoked whenever a value changes (used to schedule a persist). */
  void onChange(Runnable callback) {
    this.onChange = callback == null ? () -> {} : callback;
  }

  public boolean has(String key) {
    return props.has(key);
  }

  public int getInt(String key, int defaultValue) {
    return props.getInt(key, defaultValue);
  }

  public long getLong(String key, long defaultValue) {
    return props.getLong(key, defaultValue);
  }

  public boolean getBoolean(String key, boolean defaultValue) {
    return props.getBoolean(key, defaultValue);
  }

  public String getString(String key, String defaultValue) {
    return props.get(key, defaultValue);
  }

  public double getDouble(String key, double defaultValue) {
    String stored = props.get(key);
    if (stored == null) {
      return defaultValue;
    }
    try {
      return Double.parseDouble(stored);
    } catch (NumberFormatException exception) {
      return defaultValue;
    }
  }

  public void setDouble(String key, double value) {
    props.set(key, Double.toString(value));
    onChange.run();
  }

  public void setInt(String key, int value) {
    props.set(key, value);
    onChange.run();
  }

  public void setLong(String key, long value) {
    props.set(key, value);
    onChange.run();
  }

  public void setBoolean(String key, boolean value) {
    props.set(key, value);
    onChange.run();
  }

  public void setString(String key, String value) {
    props.set(key, value == null ? "" : value);
    onChange.run();
  }
}
