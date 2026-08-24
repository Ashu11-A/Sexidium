package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.LoggerAdapter;

public final class StdoutLoggerAdapter implements LoggerAdapter {
  private final String prefix;

  public StdoutLoggerAdapter(String prefix) {
    this.prefix = prefix == null || prefix.isBlank() ? "Sexidium" : prefix;
  }

  @Override
  public void info(String message) {
    System.out.println("[" + prefix + "] " + message);
  }

  @Override
  public void warning(String message) {
    System.out.println("[" + prefix + "] WARN " + message);
  }

  @Override
  public void severe(String message) {
    System.err.println("[" + prefix + "] ERROR " + message);
  }

  @Override
  public void warning(String message, Throwable throwable) {
    warning(message);
    if (throwable != null) {
      throwable.printStackTrace(System.out);
    }
  }

  @Override
  public void severe(String message, Throwable throwable) {
    severe(message);
    if (throwable != null) {
      throwable.printStackTrace(System.err);
    }
  }
}
