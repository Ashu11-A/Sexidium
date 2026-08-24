package com.sexidium.paper.adapter.logging;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PaperLoggerAdapterTest {

  @Test
  void info_logsAtInfoLevel() {
    Logger logger = mock(Logger.class);
    PaperLoggerAdapter adapter = new PaperLoggerAdapter(logger);
    adapter.info("hello");
    verify(logger).info("hello");
  }

  @Test
  void warning_logsAtWarningLevel() {
    Logger logger = mock(Logger.class);
    PaperLoggerAdapter adapter = new PaperLoggerAdapter(logger);
    adapter.warning("careful");
    verify(logger).warning("careful");
  }

  @Test
  void severe_logsAtSevereLevel() {
    Logger logger = mock(Logger.class);
    PaperLoggerAdapter adapter = new PaperLoggerAdapter(logger);
    adapter.severe("boom");
    verify(logger).severe("boom");
  }

  @Test
  void warningWithThrowable_logsAtWarningWithThrowable() {
    Logger logger = mock(Logger.class);
    PaperLoggerAdapter adapter = new PaperLoggerAdapter(logger);
    Throwable throwable = new RuntimeException("oops");
    adapter.warning("careful", throwable);
    verify(logger).log(Level.WARNING, "careful", throwable);
  }

  @Test
  void severeWithThrowable_logsAtSevereWithThrowable() {
    Logger logger = mock(Logger.class);
    PaperLoggerAdapter adapter = new PaperLoggerAdapter(logger);
    Throwable throwable = new RuntimeException("explode");
    adapter.severe("boom", throwable);
    verify(logger).log(Level.SEVERE, "boom", throwable);
  }
}
