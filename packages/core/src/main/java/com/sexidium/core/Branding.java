package com.sexidium.core;

import com.sexidium.core.platform.ConfigurationAdapter;

/** Shared, operator-configurable name for player-facing and log labels. */
public final class Branding {
  public static final String LABEL_PATH = "branding.label";
  public static final String DEFAULT_LABEL = "Sexidium";

  private Branding() {
  }

  public static String label(ConfigurationAdapter configuration) {
    return label(configuration == null ? null : configuration.getString(LABEL_PATH, DEFAULT_LABEL));
  }

  public static String label(String configuredLabel) {
    return configuredLabel == null || configuredLabel.isBlank() ? DEFAULT_LABEL : configuredLabel.trim();
  }
}
