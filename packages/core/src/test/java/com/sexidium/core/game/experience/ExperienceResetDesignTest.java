package com.sexidium.core.game.experience;

import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.hud.HudColor;
import com.sexidium.core.platform.hud.HudElement;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural guards for the world-regeneration design — the invariants that are cheap to break by
 * accident and expensive to discover in play.
 *
 * <p>These read the source file rather than exercising a live {@link ExperienceGame} (which is
 * {@code final} and needs the full manager/context graph). The trade is deliberately one of honesty: a
 * live sequence harness would be brittle and disproportionate to the coverage it adds over the
 * world-layer tests in {@code WorldResetTest}, whereas these guards make a regression a build failure
 * for exactly the things that went wrong the first time.</p>
 */
class ExperienceResetDesignTest {

  private static final Path RESET_SOURCE = Path.of(
      "src/main/java/com/sexidium/core/game/experience/ExperienceWorldReset.java");

  private static String source() {
    return read("ExperienceWorldReset.java");
  }

  /** Reads a source file from the experience package, from either the repo root or the module root. */
  private static String read(String fileName) {
    Path path = RESET_SOURCE.resolveSibling(fileName);
    try {
      return Files.readString(path);
    } catch (IOException exception) {
      // Fall back to the canonical path when the test runs from the module root rather than the repo root.
      try {
        return Files.readString(Path.of("packages/core").resolve(path));
      } catch (IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /**
   * The first version of this feature sent every player to the lobby while it rebuilt the world in
   * place, and it was the whole reason it did not work: the teleport was asynchronous, the world could
   * not be unloaded while occupied, and deleting it anyway left a "ghost" the acquire path handed back.
   *
   * <p>"Never the lobby" is now a hard requirement of the design — players are moved directly from the
   * old world to the new one. A future change that reaches for the lobby again is reaching for the same
   * bug, so this fails the build.</p>
   */
  @Test
  void theResetNeverTouchesTheLobby() {
    String source = source();

    assertFalse(source.contains("lobbySpawn"),
        "the regeneration must not send anyone to the lobby — that is the bug it replaced");
    assertFalse(source.contains("leaveHardcoreWorld"),
        "hardcore hearts are re-sent by the genuine world-name change on arrival, not by a lobby detour");
  }

  /**
   * The old world is deleted ONLY after the swap, never before it. Building the replacement alongside
   * the world it replaces — rather than on top of it — is what makes the failure mode "nothing happened"
   * rather than "your run is gone".
   */
  @Test
  void theResetBuildsAlongsideRatherThanInPlace() {
    String source = source();

    assertTrue(source.contains("nextExperienceGeneration"),
        "a regeneration must produce a new name, so the two worlds can coexist");
    assertFalse(source.contains("resetPersistent("),
        "in-place regeneration (delete-then-rebuild-same-name) was the bug; it must not come back");
  }

  /**
   * The order inside the swap that makes persistence correct: the registry row is renamed BEFORE the
   * state is re-resolved, because loadState() looks the experience up by its world name.
   */
  @Test
  void theRegistryRowIsRenamedBeforeStateIsReResolved() {
    String source = source();
    // Search for the call sites (with a leading dot), not the mentions in comments above them.
    int updateWorldKey = source.indexOf(".updateWorldKey(");
    int loadState = source.indexOf(".loadState()");

    assertTrue(updateWorldKey >= 0 && loadState >= 0
        && updateWorldKey < loadState,
        "updateWorldKey must precede loadState, which resolves the experience by its world key");
  }

  /**
   * The reset count survives across the wipe because it is part of the carried allowlist, written to the
   * registry column before the swap and re-flushed into the new state.yml afterwards. Encoding through
   * {@link ExperienceState} is what makes it survive a mid-swap crash, so the round trip must hold.
   */
  @Test
  void carriedStateSurvivesAnEncodingRoundTrip() {
    java.util.Map<String, String> carried = new java.util.LinkedHashMap<>();
    carried.put("deathresets.resets", "7");
    carried.put("deathresets.daybaseline", "123456");

    ExperienceState encoded = ExperienceState.fromValues(carried);
    String blob = encoded.encode();
    ExperienceState decoded = ExperienceState.decode(blob);

    assertEquals("7", decoded.getString("deathresets.resets", "0"));
    assertEquals("123456", decoded.getString("deathresets.daybaseline", "0"));
    // A key that was not carried must not come back.
    assertEquals("0", decoded.getString("deathresets.somethingelse", "0"));
  }

  /**
   * The allowlist is the whole point: carrying wholesale would break composition. A map-building
   * sibling guards its first build with an "already built" flag, and carrying that into a fresh void
   * world leaves everyone standing in empty space. So the carried set is filtered, not the full map.
   */
  @Test
  void onlyAllowlistedKeysAreCarried_notTheWholeMap() {
    String source = source();

    assertTrue(source.contains("keepStateKeys"),
        "the caller names what survives; everything else describes a world that no longer exists");
  }

  /**
   * A world being replaced is inert — but its inertness must have NO STATE OF ITS OWN.
   *
   * <p>Suppression during the reset is required behaviour: a death ends the world, so the seconds after
   * it are not playable time. The danger is not suppressing, it is suppressing with a switch. The
   * original implementation held a gate that was turned on at the death and off at the swap, and every
   * bug in this feature's history was that gate failing to turn off, leaving a world nobody could ever
   * mine again.</p>
   *
   * <p>So the rule enforced here is: the reset owns exactly one piece of state that means "in progress"
   * ({@code running}), it is cleared in a {@code finally}, and suppression is derived from it by asking.
   * A second flag — anything that has to be independently switched off — can get stuck, and is banned.</p>
   */
  @Test
  void suppressionIsDerivedFromTheResetsLifetimeAndCannotGetStuck() {
    String source = source();

    assertFalse(source.contains("freezeGate"),
        "suppression must not be a separate gate that can fail to lift — derive it from running()");
    assertFalse(source.contains("FreezePolicy"),
        "no platform-level freeze: it outlives the reset that set it");
    assertTrue(source.contains("finally"),
        "the in-progress flag must be released in a finally, or a failed swap suppresses for ever");
  }

  /** The host asks the reset whether it is running; it is never told, and never caches the answer. */
  @Test
  void theHostDerivesSuppressionByAskingTheReset() {
    String host = read("ExperienceGame.java");

    assertTrue(host.contains("worldReset.running() && suppressedDuringReset("),
        "suppression must be gated on the live reset state, not on a flag the host keeps itself");
  }

  /**
   * An experience is three worlds, and a reset replaces all three — so the teardown's "has everybody
   * left?" question has to be asked about all three too.
   *
   * <p>The replacement side of this is automatic: the new generation is a new name, and the world layer
   * provisions a Nether and an End for every experience overworld it acquires, so {@code …_r2} is born
   * with {@code …_r2_nether} and {@code …_r2_end} beside it. The deletion side is where it can go wrong.
   * Asking {@code sameWorld} instead of {@code WorldNaming.belongsToExperience} would report a player
   * standing in the doomed Nether as "gone", and the world layer would then evacuate them — to the LOBBY,
   * which is the one outcome the whole alongside design exists to prevent.</p>
   */
  @Test
  void theTeardownCountsPlayersInEveryDimension_notJustTheOverworld() {
    String source = source();

    assertTrue(source.contains("WorldNaming.belongsToExperience(player.world().name(), worldName)"),
        "the straggler check must span the Overworld, Nether and End — an experience is all three");
    assertFalse(source.contains("WorldNaming.sameWorld(player.world().name()"),
        "a name equality would call somebody in the doomed Nether 'gone' and delete the world on them");
  }

  /**
   * A mode that generates its own map builds it AFTER its players are already standing in the world, so
   * the start has to check where that left them.
   *
   * <p>The two paths are opposites and that asymmetry is the whole bug: a world REGENERATION builds in
   * phase B and teleports in phase C, so terrain is always there first, while a fresh start teleports
   * everybody in and only then runs {@code Challenge.onStart}. Layered Dimensions fills its chunk from
   * y 312 down, straight through a player waiting at the pinned spawn of y 64. The settle pass is one
   * line and reads like tidying-up, which is exactly why it needs pinning.</p>
   */
  @Test
  void aFreshStartChecksWhereTheChallengesLeftItsPlayers() {
    String host = read("ExperienceGame.java");
    int initChallenges = host.indexOf("    initChallenges();\n");
    int settle = host.indexOf("    settleParticipants();");

    assertTrue(settle > 0, "a generated map is built around players who are already in the world");
    assertTrue(initChallenges > 0 && initChallenges < settle,
        "settling before the challenges have built would check terrain that does not exist yet");
    assertTrue(host.contains("SafeSpawn.buried("),
        "only a player encased in blocks may be moved — a returning player is standing where they chose");
  }

  /**
   * …and it must WAIT for the players to arrive before checking, because a teleport is asynchronous.
   *
   * <p>The first version of this check ran inline and was a complete no-op: at the end of {@code start()}
   * the players are still in the lobby, and {@code SafeSpawn.buried} takes the world from the position it
   * is handed — so it inspected the lobby floor, found air, and reported everybody fine. Nothing threw,
   * nothing logged, and the design test above passed the whole time. That is the failure mode this one
   * exists to make impossible: a settle that assumes arrival is worse than no settle, because it looks
   * like a fix.</p>
   */
  @Test
  void theSettlePassWaitsForArrivalRatherThanAssumingIt() {
    String host = read("ExperienceGame.java");

    assertTrue(host.contains("insideThisExperience("),
        "a player who is not in this world yet must be skipped, never judged against another world");
    assertTrue(host.contains("scheduleSettle("),
        "arrival has to be waited for; an inline check runs while the teleport is still in flight");
    assertTrue(host.contains("attemptsLeft"),
        "and bounded — a teleport that never lands must not leave a timer running for the whole match");
  }

  /**
   * The entry teleport is issued as ONE operation, not as prepare-then-teleport.
   *
   * <p>{@code EntryPolicy.arrive} warms the destination chunk and reports when the move settles. Split
   * into two statements it does neither: the platform teleport is still in flight when the next line
   * runs. That is what made the settle pass above inspect the lobby, and it is the same two-statement
   * pattern the join and world-reset paths were already moved off.</p>
   */
  @Test
  void theLauncherEntersThroughTheArrivalContract() {
    String launcher = read("../GameLauncher.java");

    assertTrue(launcher.contains("policy.arrive("),
        "the launcher must use the one-operation entry, so the chunk is warmed and arrival is observable");
    assertFalse(launcher.contains("policy.prepareArrival("),
        "prepare-then-teleport as two statements is the pattern arrive() exists to replace");
  }

  /**
   * A reset strips the player to nothing and drops them into a fresh HARD-difficulty hardcore world. With
   * no armour, food or tools, a mob or a fall can kill them before they can act — and that death triggers
   * another reset, looping the run and making the world feel permanently unplayable. So arrival grants a
   * brief, time-bounded invulnerability that is explicitly revoked afterwards.
   */
  @Test
  void arrivalGrantsABriefInvulnerabilityThatIsAlwaysRevoked() {
    String source = source();

    assertTrue(source.contains("setInvulnerable(true)"),
        "a freshly wiped player must not be killable the instant they land, or the reset loops");
    assertTrue(source.contains("setInvulnerable(false)"),
        "the grace must be revoked — permanent invulnerability is not the goal");
    assertTrue(source.contains("graceTicks()"),
        "the grace window must be configurable, not a magic number");

    // Revoking must be scheduled AFTER it is granted, or the player is never protected at all.
    assertTrue(source.indexOf("setInvulnerable(true)") < source.indexOf("setInvulnerable(false)"),
        "the grace is granted first and revoked later");
  }

  /**
   * The count is drawn ONCE, in the middle of the screen.
   *
   * <p>It used to run a red boss bar as well, on the reasoning that the bar reaches everybody — but the
   * centre already does, because the vanilla title covers exactly the players the overlay does not. The
   * bar was therefore the same five seconds counted twice, a line apart and a tick out of step, which
   * is what players reported as a duplicated countdown. Reaching for {@code timerBar} here again is
   * reaching for that report.</p>
   */
  @Test
  void theResetCountdownIsDrawnOnOneSurface() {
    String game = read("ExperienceGame.java");
    int start = game.indexOf("startResetCountdown(");
    assertTrue(start > 0, "the reset's countdown entry point must exist");
    String body = game.substring(start, game.indexOf('}', start));

    assertTrue(body.contains("timerHidden("),
        "the reset counts on a bar-less timer: the big centred number is the only surface");
    assertFalse(body.contains("timerBar("),
        "a boss bar counting the same seconds as the centred number is a second countdown");
  }

  /**
   * The countdown is RED, and says so in both of the places a renderer can read it from.
   *
   * <p>One declaration is drawn by two kinds of renderer. One deserializes the template as a chat
   * component and obeys the {@code <red>} in the lang file (the vanilla title, the scoreboard); the
   * other draws through a font atlas, flattens the template to plain words, and takes the colour from
   * the declaration. Disagreement between them is visible as the same number in two colours — which is
   * how the duplicated countdown was noticed at all — so both are asserted here, together.</p>
   */
  @Test
  void theCountdownNumberIsRedOnEverySurfaceThatDrawsIt() {
    HudElement seconds = ExperienceWorldReset.countdownSpec().element("seconds");

    assertInstanceOf(HudElement.PulseRow.class, seconds, "the countdown is the one animated row");
    assertEquals(HudColor.RED, seconds.color(),
        "white here is the overlay's copy of the number coming out a different colour from the title's");

    for (String language : new String[] {"en", "pt"}) {
      String template = langValue(language, MessageKey.EXPERIENCE_RESET_COUNTDOWN_NUMBER.path());
      assertTrue(template.contains("<red>"),
          language + " renders the countdown through the component pipeline, which reads the tag rather"
              + " than the declaration: " + template);
    }
  }

  /** One message template, straight out of the shipped lang bundle. */
  private static String langValue(String language, String key) {
    Properties properties = new Properties();
    try (InputStream stream = ExperienceResetDesignTest.class
        .getResourceAsStream("/lang/" + language + ".properties")) {
      assertNotNull(stream, "missing lang bundle for " + language);
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new AssertionError("Could not read the " + language + " lang bundle", exception);
    }
    String value = properties.getProperty(key);
    assertNotNull(value, key + " is missing from " + language);
    return value;
  }
}
