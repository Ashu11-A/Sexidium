package com.sexidium.core.game;

import com.sexidium.core.game.GameEvents.CancellableGameEvent;
import com.sexidium.core.game.GameEvents.GameEvent;
import com.sexidium.core.game.GameEvents.PlayerJumpGameEvent;
import com.sexidium.core.game.GameEvents.PlayerMoveGameEvent;
import com.sexidium.core.game.GameEvents.PlayerToggleSneakGameEvent;
import com.sexidium.core.game.experience.Challenge;
import com.sexidium.core.game.experience.compose.ChallengeRegistry;
import com.sexidium.core.platform.model.WorldPosition;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The jump event, which exists because a jump INPUT cannot be recovered from movement: knockback, an
 * explosion punt and a piston all produce the same upward delta a move sample would call a jump, and the
 * modes downstream of this (Jump Multiplies) are built on the promise that being launched is not a jump.
 */
class GameEventsPlayerJumpTest {

  private static final class RecordingChallenge extends Challenge {
    private final List<String> seen = new java.util.ArrayList<>();

    private RecordingChallenge() {
      super("recorder", "Recorder");
    }

    @Override
    public void register(ChallengeRegistry registry) {
    }

    @Override
    public void onPlayerJump(PlayerJumpGameEvent event) {
      seen.add("jump:" + (event.fromPosition() == null ? "?" : event.fromPosition().worldName()));
    }

    @Override
    public void onPlayerMove(PlayerMoveGameEvent event) {
      seen.add("move");
    }

    @Override
    public void onPlayerToggleSneak(PlayerToggleSneakGameEvent event) {
      seen.add("sneak");
    }
  }

  private static WorldPosition at(String worldName) {
    return new WorldPosition(worldName, 0.0, 64.0, 0.0, 0f, 0f);
  }

  @Test
  void challengeOnEvent_routesItToItsOwnTypedHook() {
    RecordingChallenge challenge = new RecordingChallenge();

    challenge.onEvent(new PlayerJumpGameEvent(null, at("world")));
    challenge.onEvent(new PlayerToggleSneakGameEvent(null, true));
    challenge.onEvent(new PlayerMoveGameEvent(null, at("world"), at("world")));

    assertEquals(List.of("jump:world", "sneak", "move"), challenge.seen);
  }

  /**
   * Never cancellable: Paper rubber-bands a refused jump back to where it started, so a mode that
   * "declined" one would teleport the player rather than enforce a rule.
   */
  @Test
  void isNotCancellable_becauseARefusedJumpWouldRubberBandTheClient() {
    GameEvent event = new PlayerJumpGameEvent(null, at("world"));

    assertFalse(event instanceof CancellableGameEvent);
  }

  @Test
  void carriesWhereTheJumpStarted_andToleratesAPlatformThatCannotSay() {
    assertEquals("nether", new PlayerJumpGameEvent(null, at("nether")).fromPosition().worldName());
    assertNull(new PlayerJumpGameEvent(null, null).fromPosition(),
        "a platform that cannot report the origin still gets to report the jump");
  }
}
