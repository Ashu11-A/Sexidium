package com.sexidium.paper.adapter.server;

import com.sexidium.core.platform.version.ServerVersionPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The version port must fail soft everywhere: no Bukkit server (every unit-test JVM), a fork without
 * {@code ServerBuildInfo}, or an unparseable string each degrade to {@code UNKNOWN}/-1 — never an
 * exception, never a guessed format.
 */
class PaperServerVersionPortTest {

  @Test
  @DisplayName("probing without a server yields an opinionless port instead of throwing")
  void probingWithoutAServerNeverThrows() {
    ServerVersionPort port = PaperServerVersionPort.probe();

    // On this classpath there is no server, so the chain has nothing to read. Whatever it found,
    // the contract is the same: packFormat() answers -1 ("no opinion") rather than guessing.
    org.junit.jupiter.api.Assertions.assertEquals(-1, port.packFormat());
  }
}
