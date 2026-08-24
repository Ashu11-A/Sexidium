package com.sexidium.paper.adapter.logging;

import com.sexidium.core.platform.LoggerAdapter;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class PaperLoggerAdapter implements LoggerAdapter {
  private final Logger logger;

  public PaperLoggerAdapter(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void info(String message) {
    logger.info(message);
  }

  @Override
  public void warning(String message) {
    logger.warning(message);
  }

  @Override
  public void severe(String message) {
    logger.severe(message);
  }

  @Override
  public void warning(String message, Throwable throwable) {
    logger.log(Level.WARNING, message, throwable);
  }

  @Override
  public void severe(String message, Throwable throwable) {
    logger.log(Level.SEVERE, message, throwable);
  }
}
