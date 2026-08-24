package com.sexidium.core.game.experience;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two additions to the router that the copy verbs depend on: every folder op is routed as one, and a
 * refusal can say WHY.
 *
 * <p>{@code LocalExecutor} answers in a boolean, and over that channel "your friend is still standing
 * in it", "you are at your limit" and "the disk filled up" are one and the same word. That is
 * survivable for a backup — the owner shrugs and clicks again — and it is not survivable for a
 * restore, where those are completely different instructions. The fix is additive: the handler records
 * a code, {@code detail} carries it as {@code "<CODE>: <sentence>"}, and it survives both the bus
 * answer and the {@code experience_commands.detail} column a drained request is read back from.</p>
 */
class ExperienceCommandReasonTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  @Test
  @DisplayName("every op that reads or rewrites the FOLDER is routed as a folder op")
  void folderOpsAreListedExplicitly() {
    Set<ExperienceCommandRouter.Op> folder = EnumSet.noneOf(ExperienceCommandRouter.Op.class);
    for (ExperienceCommandRouter.Op op : ExperienceCommandRouter.Op.values()) {
      if (op.touchesTheFolder()) {
        folder.add(op);
      }
    }
    assertEquals(EnumSet.of(ExperienceCommandRouter.Op.DELETE, ExperienceCommandRouter.Op.BACKUP,
            ExperienceCommandRouter.Op.RESTORE, ExperienceCommandRouter.Op.REFRESH,
            ExperienceCommandRouter.Op.DUPLICATE), folder,
        "a folder op is routed at world_placements.node_id, which is durable; a live edit is routed at"
            + " the LEASE, which a world nobody has open does not have. An op missing from this set is"
            + " silently run on whichever node happens to hold the world open, or on none at all");
    // RESTORE moves no bytes and is still here on purpose: it has to run where the folders are, so the
    // executor can prove both are present and neither is open before it rewrites a single row.
    assertTrue(ExperienceCommandRouter.Op.RESTORE.touchesTheFolder());
  }

  @Test
  @DisplayName("a refusal carries its code back to the caller, through detail()")
  void aRefusalCarriesItsCode() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT,
        (experienceId, op, args) -> false, "worker-1");
    router.attachReasons((experienceId, op, args) -> "BUSY");

    ExperienceCommandRouter.Result result = router.execute("exp-1", WorldKey.parse("map_ab12cd34"),
        ExperienceCommandRouter.Op.RESTORE, Map.of());

    assertFalse(result.ok());
    assertEquals("BUSY", result.reason().orElse(null),
        "'someone is still inside' and 'something broke' send the owner to two different places");
    assertTrue(result.detail().contains("declined"),
        "the human sentence is still there; the code is a prefix, not a replacement");
  }

  @Test
  @DisplayName("no reader, no code — and a detail that merely contains a colon is not a code")
  void nothingIsInventedWhenNoCodeWasRecorded() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT,
        (experienceId, op, args) -> false, "worker-1");

    ExperienceCommandRouter.Result plain = router.execute("exp-1", WorldKey.parse("map_ab12cd34"),
        ExperienceCommandRouter.Op.BACKUP, Map.of());
    assertTrue(plain.reason().isEmpty(), "an unattached reader must change nothing at all");

    assertTrue(ExperienceCommandRouter.Result.refused("could not read where this world lives: sorry")
        .reason().isEmpty(),
        "only a leading run of A-Z and underscore is a code; ordinary prose that happens to contain"
            + " ': ' must not be read as one");
  }

  @Test
  @DisplayName("a reader that throws costs the answer nothing")
  void aBrokenReaderIsIgnored() {
    ExperienceCommandRouter router = new ExperienceCommandRouter(SILENT,
        (experienceId, op, args) -> true, "worker-1");
    router.attachReasons((experienceId, op, args) -> {
      throw new IllegalStateException("the reason map was cleared underneath us");
    });

    ExperienceCommandRouter.Result result = router.execute("exp-1", WorldKey.parse("map_ab12cd34"),
        ExperienceCommandRouter.Op.DUPLICATE, Map.of());
    assertTrue(result.applied(), "a reason is a nicety; losing the answer over one is the tail"
        + " wagging the dog");
  }

  @Test
  @DisplayName("an op an older build cannot parse is simply unknown, not a crash")
  void anUnknownOpIsUnknown() {
    // A node on the old build receiving RESTORE fails Op.parse, claims the row and completes it with a
    // readable sentence. Nothing here has to handle it; this pins that parse stays total.
    assertTrue(ExperienceCommandRouter.Op.valueOf("RESTORE").touchesTheFolder());
  }
}
