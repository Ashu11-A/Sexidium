package com.sexidium.core.game.experience;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.game.hardcore.HardcoreDeathOutcome;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.world.WorldGeneration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * That the Death Resets challenge's two declarations actually reach the places that decide the world's
 * shape and what a death costs. Each of these is a link in the chain from "the owner ticked a challenge"
 * to "the world was generated hardcore", and a break anywhere in it is silent at runtime.
 */
class DeathResetsWiringTest {

  @Test
  void theChallengeDeclaresTheStakesAndTheOutcome() {
    Challenge challenge = ChallengeCatalog.get("deathresets").factory().get();

    assertTrue(challenge.requiresHardcore());
    assertEquals(HardcoreDeathOutcome.RESET_WORLD, challenge.hardcoreDeathOutcome());
    // It builds no terrain of its own, so it must not force a void world onto whatever map it composes with.
    assertFalse(challenge.requiresVoidWorld());
    assertFalse(challenge.requiresVoidNether());
  }

  /**
   * This mode renders its two counters in the top-left corner overlay and suppresses the scoreboard
   * sidebar entirely — the counters ARE the interface, and the sidebar's active-list/deaths/blocks header
   * beside them would be two surfaces saying different things.
   */
  @Test
  void deathResetsOwnsTheScreen() {
    Challenge challenge = ChallengeCatalog.get("deathresets").factory().get();

    assertTrue(challenge.ownsHud(),
        "the counters render in the corner overlay; the sidebar must be suppressed for this mode");
  }

  /**
   * The guard that keeps the claim from becoming a blank screen. A freshly built challenge has opened no
   * overlay, so it must report its surface INACTIVE — which is exactly the state of a server with no
   * BetterHud, and the state in which the sidebar has to be left alone. Losing this is invisible in every
   * unit test and shows up as an empty screen on the one server that matters.
   *
   * <p>Both forms have to say so. The per-player one is what actually decides a screen, and defaulting it
   * to anything but the coarse answer would let a challenge suppress a sidebar it never earned.</p>
   */
  @Test
  void owningTheScreenIsNotClaimedUntilTheSurfaceIsActuallyDrawing() {
    Challenge challenge = ChallengeCatalog.get("deathresets").factory().get();

    assertFalse(challenge.hudSurfaceActive(),
        "no overlay has been opened yet, so the sidebar must survive");
    assertFalse(challenge.hudSurfaceActive(null),
        "and nobody in particular is certainly not somebody the corner is drawing to");
  }

  /**
   * Owning the screen has to mean the WHOLE screen, including a board somebody else put up.
   *
   * <p>A player walks into the mode from the lobby still wearing the lobby's server/points/rank card.
   * The lobby hands that card over without clearing it, because every other mode draws its own sidebar
   * straight over the top. This one draws none — so unless suppression actively strips the inherited
   * board, the lobby card stays on screen for the entire match, beside a corner readout that is supposed
   * to be the only thing there.</p>
   *
   * <p>And the lobby has to ask about the PLAYER, not the match: the same match draws a sidebar for its
   * Bedrock players and none for its Java ones, so a single answer takes the card off the wrong person.</p>
   */
  @Test
  void aModeThatOwnsTheScreenReportsThatItDrawsNoSidebar() {
    String hud = readSource("game/hud/GameHud.java");
    assertTrue(hud.contains("clearInheritedSidebar"),
        "suppressing the panel must strip a board inherited from elsewhere, not just close our own");

    String lobbyHud = readSource("world/LobbyHud.java");
    assertTrue(lobbyHud.contains("drawsSidebar(player)"),
        "the lobby must ask whether the match will draw a board for THIS player before handing over blind");
  }

  /**
   * The run's statistics have to be named in what a regeneration carries, or they are destroyed by the
   * first death — which is the entire failure mode this mode is uniquely exposed to, and one that no
   * test short of playing a run to a death would otherwise catch.
   *
   * <p>Asserted on the prefix constant rather than a literal so that renaming the namespace moves both
   * halves together instead of silently unhooking one from the other.</p>
   */
  @Test
  void aRegenerationCarriesTheRunsOwnStatistics() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");
    assertTrue(source.contains("ExperienceStats.RUN_KEY_PREFIX + \"*\""),
        "the reset's allowlist must carry the run-lifetime counters, whose keys are per-player and so "
            + "cannot be named exactly");
    assertTrue(source.contains("stateKey(KEY_RESETS)") && source.contains("stateKey(KEY_BASELINE)"),
        "…without dropping this mode's own two counters");
    assertFalse(source.contains("stateKey(KEY_DAYS)"),
        "the day mirror is the age of THIS world, so a new world must start counting again from zero");
  }

  /**
   * The day count is a saved statistic, not only a rendering detail: it is written whether or not any
   * surface is drawing, so a run whose players are all on Bedrock still records how far it got.
   *
   * <p>This used to be an ordering assertion — the write had to sit ABOVE a {@code if (!drawing)}
   * guard that skipped the push when the corner overlay was dark. The guard is gone: the driver stack
   * renders the same declaration on the sidebar for anyone the corner does not reach, so there is no
   * longer a state in which pushing is pointless, and nothing to be above. Pinning its absence is the
   * stronger statement, because ordering can be got right by luck and a reintroduced guard cannot.</p>
   */
  @Test
  void theCountersArePushedWithNoDrawingGuardAtAll() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");
    assertTrue(source.contains("setStateLong(KEY_DAYS"), "the day mirror must be written on the timer");
    assertFalse(source.contains("if (!drawing)"),
        "a drawing guard would skip the saved day mirror on a run whose surface is dark");
    assertFalse(source.contains("hudSurfaceChanged()"),
        "the host re-reads the live surface every pass now, so an edge report is a second source of "
            + "truth that can disagree with it");
  }

  /**
   * Deaths are counted at the DEATH, not at the respawn. In this mode the respawn is deliberately
   * deferred a tick while the regeneration may already have snapshotted what it carries — so a death
   * counted on the respawn is the death the next world never hears about.
   */
  @Test
  void deathsAreRecordedAtTheDeathEventNotTheRespawn() {
    String source = readSource("game/experience/ExperienceGame.java");
    int atDeath = source.indexOf("experienceStats.recordRunDeath");
    int atRespawn = source.indexOf("reviveParticipant(respawn.playerAdapter())");
    assertTrue(atDeath > 0, "the run-scoped death counter must be recorded somewhere");
    assertTrue(atDeath < atRespawn,
        "the run-scoped death must be counted in the death branch, above the respawn branch");
  }

  /**
   * The tab-list column is a vanilla scoreboard surface reaching every player, and it must be pushed
   * unconditionally — the Bedrock players it exists to serve are exactly the ones a surface-conditional
   * push would drop.
   */
  @Test
  void theTabListDeathColumnIsPushedUnconditionally() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");
    int column = source.indexOf("deathColumn.refresh()");
    assertTrue(column > 0, "the death column must be refreshed on the mode's timer");
    int method = source.indexOf("private void pushReadout()");
    assertTrue(method > 0 && method < column,
        "the column must be refreshed from the mode's own timer, not from a HUD render pass that a "
            + "suppressed player never runs");
  }

  /**
   * A challenge that claims the whole screen must open its surfaces through the helper that RECORDS
   * them, not through the registry directly.
   *
   * <p>Both paths compile, both render, and both unbind correctly — the difference is invisible until
   * it matters. Only the recording helper feeds {@code Challenge#surfaces}, which is what
   * {@code hudSurfaceActive(player)} answers from. Take it away and this challenge reports its surface
   * inactive forever while happily drawing it, so every player gets the corner readout AND the sidebar
   * it was supposed to replace — the exact double-interface {@code ownsHud()} exists to prevent.</p>
   */
  @Test
  void aScreenOwningChallengeRecordsItsSurfacesSoSuppressionCanSeeThem() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");

    assertTrue(source.contains("hudSurface(registry, readoutSpec())"),
        "the readout must go through Challenge#hudSurface(registry, spec), which records the handle");
    assertFalse(source.contains("registry.hudSurface("),
        "opening straight off the registry skips the record, and the claim silently stops being honoured");
  }

  /**
   * The played counter has to read the LIVE figure, not the persisted one.
   *
   * <p>{@code stats().runSeconds()} only moves when the host commits its occupancy buffer to the
   * experience's state, and that cadence is deliberately slow because the state is a file. A counter
   * built on it advances in jumps the length of the commit window — five seconds at the shipped
   * default — however often the HUD repaints, which reads on screen as a broken timer.</p>
   */
  @Test
  void thePlayedCounterReadsTheLiveFigureNotThePersistedOne() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");

    assertTrue(source.contains("DurationText.compact(liveRunSeconds())"),
        "the readout must include the seconds not yet committed, or it ticks a commit-window at a time");
    assertFalse(source.contains("DurationText.compact(stats().runSeconds())"),
        "the persisted figure is the one that jumps; it must not be what the counter shows");
  }

  /**
   * The counters are declared once and rendered by the driver, never hand-duplicated onto the sidebar.
   *
   * <p>The duplicate was the whole cost of the old seam: the corner could not be translated and could
   * not reach Bedrock, so the same three numbers existed twice, in two renderings, kept in step by
   * hand. A reintroduced copy would drift.</p>
   */
  @Test
  void theCountersAreDeclaredOnceNotRenderedTwice() {
    String source = readSource("game/experience/challenges/DeathResetsChallenge.java");
    assertTrue(source.contains("hudSurface(registry, readoutSpec())"),
        "the readout must be a declared surface, so the driver can choose where it lands per player");
    assertFalse(source.contains("context.line(LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_"),
        "the sidebar copy of the counters must be gone — the driver renders the declaration there");
  }

  private static String readSource(String relative) {
    java.nio.file.Path path = java.nio.file.Path.of("src/main/java/com/sexidium/core").resolve(relative);
    try {
      return java.nio.file.Files.readString(path);
    } catch (java.io.IOException exception) {
      try {
        return java.nio.file.Files.readString(java.nio.file.Path.of("packages/core").resolve(path));
      } catch (java.io.IOException nested) {
        throw new AssertionError("Could not read " + path, nested);
      }
    }
  }

  /** Owning the whole screen is for a mode whose readout IS the interface — not for any HUD line. */
  @Test
  void noOtherChallengeClaimsTheWholeScreen() {
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      if (entry.id().equals("deathresets")) {
        continue;
      }
      Challenge challenge = entry.factory().get();
      assertFalse(challenge.ownsHud(),
          entry.id() + " must contribute to the shared HUD, not suppress it for everyone else");
      assertFalse(challenge.hudSurfaceActive(),
          entry.id() + " draws on the shared panel, so it owns no surface of its own");
    }
  }

  @Test
  void everyOtherChallengeStaysNeutralOnHardcore() {
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      if (entry.id().equals("deathresets")) {
        continue;
      }
      Challenge challenge = entry.factory().get();
      assertFalse(challenge.requiresHardcore(), entry.id() + " must leave the hardcore choice to the owner");
      assertNull(challenge.hardcoreDeathOutcome(), entry.id() + " must have no opinion on what a death costs");
    }
  }

  /**
   * The catalog resolves the whole hardcore question in one object, so the menu tile, the setup and the
   * death handler cannot drift apart — which is exactly how the manage screen came to offer
   * "Hardcore: OFF — click to turn it on" for an experience the service had already forced on.
   */
  @Test
  void catalog_resolvesTheWholeHardcoreDemandForASelection() {
    var demand = ChallengeCatalog.hardcoreDemand(List.of("doubledrops", "deathresets"));

    assertTrue(demand.required(), "Death Resets in the selection forces the stakes");
    assertFalse(demand.ownerMayChoose(), "so the tile is locked, not an off switch");
    assertEquals("Death Resets", demand.reason(), "and the player is told which twist did it");
    assertEquals(HardcoreDeathOutcome.RESET_WORLD, demand.outcome(),
        "a death here replaces the world rather than ending the run");
    assertTrue(demand.appliesTo(false), "an owner who never ticked hardcore still gets it");

    var ordinary = ChallengeCatalog.hardcoreDemand(List.of("doubledrops"));
    assertFalse(ordinary.required());
    assertTrue(ordinary.ownerMayChoose(), "an ordinary selection leaves the choice alone");
  }

  @Test
  void catalog_reportsWhenASelectionForcesHardcore() {
    assertTrue(ChallengeCatalog.anyRequiresHardcore(List.of("deathresets")));
    assertTrue(ChallengeCatalog.anyRequiresHardcore(List.of("doubledrops", "deathresets")));
    assertFalse(ChallengeCatalog.anyRequiresHardcore(List.of("doubledrops", "sharedlife")));
    assertFalse(ChallengeCatalog.anyRequiresHardcore(List.of()));
  }

  @Test
  void setup_forcesHardcoreOnEvenWhenTheOwnerNeverTickedIt() {
    ExperienceSetup soft = new ExperienceSetup(ExperienceWorldType.NORMAL, true, false);

    assertTrue(soft.forChallenges(List.of("deathresets")).hardcore());
    assertFalse(soft.forChallenges(List.of("doubledrops")).hardcore(),
        "an ordinary selection must not quietly harden somebody's world");
  }

  /** The launch path and the reset path both build the world from here, so they cannot disagree. */
  @Test
  void generationFor_carriesHardcoreOntoTheWorldItself() {
    ExperienceSetup setup =
        new ExperienceSetup(ExperienceWorldType.NORMAL, true, false).forChallenges(List.of("deathresets"));

    WorldGeneration generation = setup.generationFor(List.of("deathresets"));

    assertTrue(generation.hardcore(), "the client is told a world is hardcore when it is SENT the world");
    assertFalse(generation.voidWorld());
  }

  @Test
  void generationFor_stillHonoursAMapChallengesVoidRequirement() {
    ExperienceSetup setup = new ExperienceSetup(ExperienceWorldType.NORMAL, true, false)
        .forChallenges(List.of("deathresets", "classicskyblock"));

    WorldGeneration generation = setup.generationFor(List.of("deathresets", "classicskyblock"));

    assertTrue(generation.hardcore());
    assertTrue(generation.voidWorld(), "composing with a map challenge must keep that map's world shape");
    assertTrue(generation.voidNether());
  }

  @Test
  void entryPolicy_handsOutHardcoreHeartsForAForcedSelection() {
    ExperienceSetup setup =
        new ExperienceSetup(ExperienceWorldType.NORMAL, true, false).forChallenges(List.of("deathresets"));

    EntryPolicy alive = setup.entryPolicy(false);

    assertEquals(GameModeType.SURVIVAL, alive.gameMode());
    assertTrue(alive.hardcoreView());
  }
}
