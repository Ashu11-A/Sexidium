package com.sexidium.core.platform;

public interface LoggerAdapter {
  void info(String message);

  void warning(String message);

  void severe(String message);

  void warning(String message, Throwable throwable);

  void severe(String message, Throwable throwable);
}
