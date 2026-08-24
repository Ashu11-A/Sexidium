package com.sexidium.core.game.hud.surface;

import com.sexidium.core.game.experience.ExperienceWorldReset;
import com.sexidium.core.game.experience.challenges.DeathResetsChallenge;
import com.sexidium.core.game.experience.challenges.RandomEventsChallenge;
import com.sexidium.core.game.experience.challenges.SharedLifeChallenge;
import com.sexidium.core.game.modes.minigames.TntWarGame;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.List;

/**
 * Every HUD surface this plugin can declare, in one list.
 *
 * <h2>Why a catalogue, when surfaces are opened per match</h2>
 * A platform driver may have to BUILD something before a surface can be drawn — the Paper one
 * compiles each spec to yml and needs the backing plugin to reload before the object exists. Doing
 * that when a match opens a surface would mean reloading another plugin mid-match, in front of
 * players. Declaring everything up front instead lets the generated assets and the one reload settle
 * during startup, before anybody is online, and lets a driver answer honestly at open time about
 * whether a given surface was ever built.
 *
 * <p>A driver that needs no such preparation — the sidebar renderer — ignores this entirely and
 * renders whatever it is handed.</p>
 *
 * <h2>Adding one</h2>
 * Expose the spec as a {@code public static} factory on the class that owns it, and add it here. The
 * duplication is deliberate and small: it is what keeps "what can be drawn" answerable without
 * classpath scanning, and a spec missing from this list fails loudly at open time with a log line
 * naming it, rather than quietly drawing nothing.
 */
public final class HudSurfaceCatalog {
  private HudSurfaceCatalog() {
  }

  public static List<HudSurfaceSpec> all() {
    return List.of(
        DeathResetsChallenge.readoutSpec(),
        ExperienceWorldReset.countdownSpec(),
        SharedLifeChallenge.poolSpec(),
        RandomEventsChallenge.eventSpec(),
        RandomEventsChallenge.announcementSpec(),
        TntWarGame.destructionSpec()
    );
  }
}
