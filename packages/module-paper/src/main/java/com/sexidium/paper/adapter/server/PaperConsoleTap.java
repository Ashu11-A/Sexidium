package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.ConsoleTap;

import java.util.function.BiConsumer;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * {@link ConsoleTap} that mirrors console output by attaching a {@link Handler} to the root JUL logger.
 * This captures plugin/Bukkit log lines (which the Discord live-console relay cares about); pure
 * vanilla log4j lines are not seen — a documented limitation. Best-effort and never throws into the
 * logging pipeline.
 */
public final class PaperConsoleTap implements ConsoleTap {
  // Formatter.formatMessage (public) resolves {0}-style params; Handler itself has no such method.
  private final Formatter messageFormatter = new SimpleFormatter();
  private volatile BiConsumer<String, String> sink;
  private Handler handler;

  @Override
  public synchronized void subscribe(BiConsumer<String, String> lineSink) {
    this.sink = lineSink;
    if (handler != null) {
      return; // already attached; just swapped the sink
    }
    handler = new Handler() {
      @Override
      public void publish(LogRecord record) {
        BiConsumer<String, String> current = sink;
        if (current == null || record == null || record.getMessage() == null) {
          return;
        }
        try {
          current.accept(record.getLevel().getName(), messageFormatter.formatMessage(record));
        } catch (RuntimeException ignored) {
          // never let the relay break logging
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }
    };
    handler.setLevel(Level.ALL);
    Logger.getLogger("").addHandler(handler);
  }
}
