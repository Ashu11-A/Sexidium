package com.sexidium.core.platform;

import java.util.function.BiConsumer;

/**
 * Streams server console lines to a subscriber so the bridge can relay them to Discord (live console
 * view). Platforms attach to their logging backend; the default {@link ServerAdapter#consoleTap()}
 * emits nothing.
 */
public interface ConsoleTap {
  /** Register a sink receiving {@code (level, message)} for each console line. */
  void subscribe(BiConsumer<String, String> lineSink);

  ConsoleTap NOOP = lineSink -> { };
}
