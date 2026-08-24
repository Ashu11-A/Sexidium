package com.sexidium.velocity.adapter;

import com.sexidium.core.platform.LoggerAdapter;
import org.slf4j.Logger;

/** Bridges core's LoggerAdapter onto the slf4j Logger Velocity injects. */
public final class VelocityLoggerAdapter implements LoggerAdapter {

  private final Logger logger;

  public VelocityLoggerAdapter(Logger logger) {
    this.logger = logger;
  }

  @Override
  public void info(String message) {
    logger.info(message);
  }

  @Override
  public void warning(String message) {
    logger.warn(message);
  }

  @Override
  public void severe(String message) {
    logger.error(message);
  }

  @Override
  public void warning(String message, Throwable throwable) {
    logger.warn(message, throwable);
  }

  @Override
  public void severe(String message, Throwable throwable) {
    logger.error(message, throwable);
  }
}
