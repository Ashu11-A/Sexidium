package com.sexidium.core.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The log lines a rolling update waits on, pinned so a refactor cannot break the pipeline silently.
 *
 * <p>An orchestrator tails the container log for these substrings to decide when a node is up, when a
 * drain is safe to restart through, and when to roll back. None of that is visible from inside the
 * JVM, so nothing else would ever fail if one of them were reworded — the roll would simply hang for
 * its whole timeout and then abort, on a node that was perfectly healthy.</p>
 *
 * <p>These are <b>substrings</b>, deliberately: {@code HTTP API listening on http://} stops before
 * the address because the bind is configurable now, and a node binding {@code 0.0.0.0} must still
 * satisfy the same gate.</p>
 *
 * <p>Rewording one of these is allowed — but it is a contract change, and it belongs in the same
 * commit as the pipeline change that follows it.</p>
 */
class LogLineContractTest {

  /** Where the source tree is, relative to the module the tests run in. */
  private static final Path CORE_SOURCES = Path.of("src/main/java");

  @Test
  @DisplayName("the four pre-existing lines the pipeline gates on are still emitted verbatim")
  void preExistingGateLinesAreStillEmitted() {
    // `Done (` is Paper's own and is not ours to pin -- and it is not sufficient on its own either:
    // Paper prints it even when a plugin threw during enable and was disabled. The other three are.
    assertEmitted("Network node '", "network/NetworkService.java");
    assertEmitted("' online with capabilities ", "network/NetworkService.java");
    assertEmitted("HTTP API listening on http://", "lib/net/ApiServer.java");
    assertEmitted("World placements are consistent with this node's disk",
        "network/PlacementReconciler.java");
  }

  @Test
  @DisplayName("the SX- lines an orchestrator waits on are all emitted")
  void sxLinesAreEmitted() {
    // SX-READY is emitted by the platform adapter, because only it knows the enable path finished.
    assertTrue(read(Path.of("../module-paper/src/main/java/com/sexidium/paper/PaperSexidiumPlugin.java"))
            .contains("SX-READY node="),
        "PaperSexidiumPlugin must emit SX-READY at the end of a successful enable; `Done (` alone is"
            + " not enough, because Paper prints that even when a plugin threw and was disabled.");
    assertEmitted("SX-PROTOCOL peer=", "network/NetworkService.java");
    assertEmitted("SX-CONTENT refused world=", "network/NetworkService.java");
    assertEmitted("SX-SELFTEST ok=true challenges=", "network/NodeSelfTest.java");
    assertEmitted("SX-SELFTEST ok=false detail=", "network/NodeSelfTest.java");
    // The two the rolling-update pipeline actually gates on: it waits for `phase=READY` before it
    // restarts a node and aborts the roll on `phase=STALLED`. A refactor that reworded either one
    // would leave the pipeline waiting out its whole timeout on a node that had finished.
    assertEmitted("SX-DRAIN phase=", "network/DrainCoordinator.java");
    assertEmitted("SX-RECLAIM node=", "network/DrainCoordinator.java");
  }

  @Test
  @DisplayName("the API log line reports the ACTUAL bind, not a hardcoded loopback")
  void apiLineReportsTheRealBind() {
    // It printed 127.0.0.1 unconditionally, which became a lie the moment the bind was configurable
    // -- and this is the line an orchestrator uses to learn the API is up.
    String source = read("lib/net/ApiServer.java");
    assertTrue(source.contains("\"HTTP API listening on http://\" + bind + \":\" + port"),
        "ApiServer must log the address it actually bound");
  }

  @Test
  @DisplayName("every SX- line stays on one line and starts at the beginning of the message")
  void sxLinesAreGreppable() {
    List<String> offenders = new ArrayList<>();
    for (Path file : sources()) {
      String source = read(file);
      int index = 0;
      while ((index = source.indexOf("\"SX-", index)) >= 0) {
        int end = source.indexOf('"', index + 1);
        String line = end < 0 ? source.substring(index) : source.substring(index + 1, end);
        if (line.contains("\\n") || line.contains("\\r")) {
          offenders.add(file + ": " + line);
        }
        index = end < 0 ? source.length() : end;
      }
    }
    if (!offenders.isEmpty()) {
      fail("SX- log lines must be a single line each; an orchestrator greps them:\n"
          + String.join("\n", offenders));
    }
  }

  private static void assertEmitted(String substring, String relativePath) {
    assertTrue(read(relativePath).contains(substring),
        relativePath + " no longer emits the pinned substring \"" + substring + "\"."
            + " An orchestrator waits on it; reword it only together with the pipeline.");
  }

  private static String read(String relativePath) {
    return read(CORE_SOURCES.resolve("com/sexidium/core").resolve(relativePath));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (Exception unreadable) {
      throw new AssertionError("Could not read " + path.toAbsolutePath(), unreadable);
    }
  }

  private static List<Path> sources() {
    try (Stream<Path> files = Files.walk(CORE_SOURCES)) {
      return files.filter(path -> path.toString().endsWith(".java")).toList();
    } catch (Exception unreadable) {
      throw new AssertionError("Could not walk " + CORE_SOURCES.toAbsolutePath(), unreadable);
    }
  }
}
