package com.sexidium.core.game.experience;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.game.experience.ExperienceManager.Experience;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.world.WorldGeneration;
import com.sexidium.core.world.WorldSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the rules that make hardcore mean anything: a world is only LOST when it was hardcore AND
 * something died in it; a lost world can only ever be spectated, and is kept that way rather than merely
 * checked at the door; and a hardcore world carries its flag from the moment it is created — a client is
 * told what a world is when the world is sent to it, so setting it afterwards is too late for the
 * players already being teleported in.
 */
class HardcoreLostWorldTest {
  private static final UUID OWNER = UUID.randomUUID();

  @Test
  void aWorldIsOnlyLostWhenItWasHardcoreAndDied() {
    assertFalse(experience(false, false).isLost(), "an ordinary living world");
    assertFalse(experience(true, false).isLost(), "hardcore but nobody has died yet");
    assertTrue(experience(true, true).isLost(), "hardcore and died — this is the one that locks");
    // A "dead" flag on a non-hardcore world is meaningless and must never lock it: only hardcore ends.
    assertFalse(experience(false, true).isLost());
  }

  @Test
  void aLostWorldCanOnlyEverBeSpectated() {
    // The reported bug, in one assertion: entering a world you have already died in put you back in
    // SURVIVAL, so a hardcore run could be carried on after it had ended.
    EntryPolicy lost = experience(true, true).setup().entryPolicy(true);

    assertEquals(GameModeType.SPECTATOR, lost.gameMode());
    assertFalse(lost.playable());
    assertTrue(lost.enforced(), "checked at the door is not enough — something always writes a mode later");
    assertNotNull(lost.notice(), "a player yanked into spectator is owed the reason");
    assertFalse(lost.heal(), "nothing about a world that is over should look playable");
  }

  @Test
  void aLiveHardcoreWorldIsPlayedInSurvivalWithHardcoreHearts() {
    EntryPolicy hardcore = experience(true, false).setup().entryPolicy(false);
    assertEquals(GameModeType.SURVIVAL, hardcore.gameMode());
    // The client half of hardcore. Setting it on the WORLD (difficulty, death handling) tells no client
    // anything, which is why a hardcore experience entered from the lobby drew ordinary hearts.
    assertTrue(hardcore.hardcoreView());
    // Enforced, and this is not the same thing as fighting an operator over a /gamemode. The competitor
    // is the SERVER: a respawn in a hardcore world is put into spectator by vanilla, after the respawn
    // event this code can react to. A mode that respawns its players on purpose (Death Resets, to clear
    // a death screen with no Respawn button) otherwise gets exactly one chance to undo that — and a
    // player who slips through is a spectator for the session: no chunks, nothing breakable, only a
    // relog fixes it. Enforcing turns "wrong for ever" into "wrong for up to a second".
    assertTrue(hardcore.enforced(),
        "a live hardcore world must keep re-asserting survival — vanilla respawns into spectator");

    EntryPolicy ordinary = experience(false, false).setup().entryPolicy(false);
    assertEquals(GameModeType.SURVIVAL, ordinary.gameMode());
    assertFalse(ordinary.hardcoreView(), "…and an ordinary world must never hand out hardcore hearts");
    assertFalse(ordinary.enforced(),
        "a NON-hardcore world has no such competitor, so it must not fight an operator's /gamemode");
  }

  @Test
  void lostOutranksEveryOtherOption() {
    // Whatever the world was set up as, once it is lost there is exactly one way to be in it. Guards the
    // ordering inside the rule: a hardcore world's own "survival + hearts" branch must not win here.
    for (boolean keepInventory : new boolean[] {true, false}) {
      for (ExperienceWorldType type : ExperienceWorldType.values()) {
        EntryPolicy policy = new ExperienceSetup(type, keepInventory, true).entryPolicy(true);
        assertEquals(GameModeType.SPECTATOR, policy.gameMode(), type + " / keepInventory=" + keepInventory);
        assertFalse(policy.hardcoreView(), "a spectator has no hearts to harden");
      }
    }
  }

  @Test
  void theSetupTravelsWithTheExperience() {
    Experience hardcore = experience(true, false);
    assertTrue(hardcore.setup().hardcore());
    assertTrue(hardcore.setup().toModeArgs(hardcore.challenges()).contains("hardcore:true"));
    // …and back out again, which is what makes it survive a restart with the rest of the match state.
    assertTrue(ExperienceSetup.fromArgs(hardcore.setup().toModeArgs(hardcore.challenges())).hardcore());

    Experience normal = experience(false, false);
    assertFalse(normal.setup().hardcore());
    assertTrue(normal.setup().toModeArgs(normal.challenges()).contains("hardcore:false"));
  }

  @Test
  void hardcoreReachesTheWorldAsACreationTimeSetting() {
    // The bug this locks: hardcore applied to a world people are already standing in leaves them with
    // ordinary hearts, because the client is told at world-send time. It has to ride the world request.
    WorldSettings settings = WorldSettings.forPersistentWorld(0, 20, 0.2, true, "NORMAL")
        .withGeneration(WorldGeneration.DEFAULT.asHardcore(true));

    assertTrue(settings.hardcore());
    // …and it survives the other generation options being layered on, so a hardcore SkyBlock stays both.
    assertTrue(settings.asVoid(true).hardcore());
    assertTrue(settings.withTerrain(com.sexidium.core.platform.model.WorldTerrain.AMPLIFIED).hardcore());
  }

  @Test
  void anOrdinaryWorldRequestIsUnchangedByTheNewFlag() {
    // The default must stay the "nothing to special-case" request, or every plain world starts taking
    // the generation path instead of the cheap one.
    assertTrue(WorldGeneration.DEFAULT.isDefault());
    assertFalse(WorldGeneration.DEFAULT.hardcore());
    assertFalse(WorldGeneration.DEFAULT.asHardcore(true).isDefault(), "hardcore is a real difference");
    assertFalse(WorldSettings.forPersistentWorld(0, 20, 0.2, true, "NORMAL").hardcore());
  }

  @Test
  void hardcoreSurvivesBeingPointedAtAnotherDimension() {
    // An experience's Nether and End are born from the same request; losing the flag there would hand a
    // player ordinary hearts the moment they stepped through a portal.
    WorldGeneration nether = WorldGeneration.DEFAULT.asHardcore(true)
        .inDimension(com.sexidium.core.platform.model.WorldDimension.NETHER);

    assertTrue(nether.hardcore());
    assertEquals(com.sexidium.core.platform.model.WorldDimension.NETHER, nether.dimension());
  }

  private static Experience experience(boolean hardcore, boolean dead) {
    return new Experience("ab12cd34", OWNER, "ashu", "ashu/run_ab12cd34", "Run",
        List.of("doubledrops"), false, 0L, Experience.MODE_EXPERIENCE,
        ExperienceWorldType.NORMAL.id(), true, hardcore, dead);
  }
}
