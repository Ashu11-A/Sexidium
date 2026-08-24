package com.sexidium.core.network.transfer;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;

import java.io.File;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** Shared fixture: one real SQLite database in a @TempDir, and a logger that records SEVEREs. */
final class TransferTestSupport {

  private TransferTestSupport() {
  }

  /** Records severe lines so a breaker trip can be asserted on rather than merely inferred. */
  static final class RecordingLogger implements LoggerAdapter {
    final List<String> severe = new ArrayList<>();
    final List<String> info = new ArrayList<>();

    @Override public void info(String message) { info.add(message); }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { severe.add(message); }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { severe.add(message); }
  }

  static Database database(Path tmp, String name) throws SQLException {
    return new Database(new File(tmp.toFile(), name + ".db"));
  }
}
