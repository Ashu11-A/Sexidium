package com.sexidium.core.platform.noop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

class StdoutLoggerAdapterTest {
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private PrintStream origOut, origErr;

  @BeforeEach
  void captureStreams() {
    origOut = System.out;
    origErr = System.err;
    System.setOut(new PrintStream(out));
    System.setErr(new PrintStream(err));
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(origOut);
    System.setErr(origErr);
  }

  @Test
  void info_printsToStdout() {
    new StdoutLoggerAdapter("Test").info("hello");
    assertTrue(out.toString().contains("hello"));
    assertTrue(out.toString().contains("Test"));
  }

  @Test
  void warning_printsToStdout() {
    new StdoutLoggerAdapter("Test").warning("warn-msg");
    assertTrue(out.toString().contains("warn-msg"));
    assertTrue(out.toString().contains("WARN"));
  }

  @Test
  void severe_printsToStderr() {
    new StdoutLoggerAdapter("Test").severe("error-msg");
    assertTrue(err.toString().contains("error-msg"));
    assertTrue(err.toString().contains("ERROR"));
  }

  @Test
  void warning_withThrowable_printsStackTrace() {
    new StdoutLoggerAdapter("Test").warning("boom", new RuntimeException("cause"));
    assertTrue(out.toString().contains("boom"));
    assertTrue(out.toString().contains("RuntimeException"));
  }

  @Test
  void severe_withThrowable_printsStackTrace() {
    new StdoutLoggerAdapter("Test").severe("severe-boom", new IllegalStateException("ise"));
    assertTrue(err.toString().contains("severe-boom"));
    assertTrue(err.toString().contains("IllegalStateException"));
  }

  @Test
  void nullPrefix_defaultsToSexidium() {
    new StdoutLoggerAdapter(null).info("msg");
    assertTrue(out.toString().contains("Sexidium"));
  }

  @Test
  void blankPrefix_defaultsToSexidium() {
    new StdoutLoggerAdapter("  ").info("msg");
    assertTrue(out.toString().contains("Sexidium"));
  }

  @Test
  void warning_withNullThrowable_doesNotCrash() {
    assertDoesNotThrow(() -> new StdoutLoggerAdapter("T").warning("w", null));
  }

  @Test
  void severe_withNullThrowable_doesNotCrash() {
    assertDoesNotThrow(() -> new StdoutLoggerAdapter("T").severe("s", null));
  }
}
