package com.sexidium.core.game.experience;

import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hud.HudHost;
import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.WorldAdapter;

import java.util.List;

/**
 * The surface an {@link ExperienceGame} exposes to its {@link Challenge}s. Challenges never subclass
 * the game framework directly; they receive a host and drive the shared participant set, world,
 * scheduler and surfaces through it. Resources obtained via {@link #runTimer}/{@link #track} are owned
 * by the host and torn down centrally when the experience stops, so a challenge never leaks a task or
 * boss bar.
 */
public interface ExperienceHost extends HudHost {
  @Override
  GameContext gameContext();

  /** Online participants of the experience (shared across every challenge). */
  @Override
  List<PlayerAdapter> online();

  boolean isParticipant(PlayerAdapter playerAdapter);

  ScheduledTask runTimer(Runnable runnable, long delayTicks, long periodTicks);

  ScheduledTask runLater(Runnable runnable, long delayTicks);

  BossBarHandle track(BossBarHandle bossBarHandle);

  HudPanelHandle track(HudPanelHandle hudPanelHandle);

  /**
   * The stacked HUD driver a challenge declares its surfaces through.
   *
   * <p>Defaulted to {@link HudDriver#NOOP} rather than abstract so a host that draws no HUD at all —
   * a test double, a headless scope — is not incomplete. Every real host inherits the concrete stack
   * from {@code AbstractGame}.</p>
   */
  default HudDriver hudDriver() {
    return HudDriver.NOOP;
  }

  /**
   * Tracks a declared surface so it is shown/hidden with the rest of a player's match UI and closed
   * when the challenge that opened it goes away.
   *
   * <p>Defaulted rather than abstract because a surface is optional by nature — a host that does not
   * care to track them is not incomplete. The default hands the handle straight back untracked, which
   * is correct for the {@link HudSurfaceHandle#NOOP} it will be given on a platform that draws
   * nothing.</p>
   */
  default HudSurfaceHandle track(HudSurfaceHandle hudSurfaceHandle) {
    return hudSurfaceHandle == null ? HudSurfaceHandle.NOOP : hudSurfaceHandle;
  }

  /**
   * Tracks a tab-list column, on the same terms and for the same reason as the surface above: optional
   * by nature, so defaulted rather than abstract, and handed back untracked by a host that has no use
   * for it.
   */
  default TabListHandle track(TabListHandle tabListHandle) {
    return tabListHandle == null ? TabListHandle.NOOP : tabListHandle;
  }

  /**
   * Tells the host that a challenge's own on-screen surface has come up or gone away, so it can decide
   * again whether the scoreboard sidebar is still needed.
   *
   * <p>Only a challenge that returns true from {@link Challenge#ownsHud()} has anything to report, and
   * only one whose surface is not a declared {@link com.sexidium.core.platform.hud.HudSurfaceSpec} —
   * those answer {@link Challenge#hudSurfaceActive(PlayerAdapter)} from live driver state, so the host
   * re-reads them every pass and needs no call.</p>
   */
  default void hudSurfaceChanged() {
  }

  /** The world the experience runs in (the owner's persistent experience world), or null in tests. */
  WorldAdapter world();

  /**
   * The shared, persistent gameplay state of the experience. Every challenge reads/writes the same
   * instance, so a stat (e.g. the Double Drops multiplier) is shared across all connected players and
   * survives disconnects and restarts. Never null.
   */
  ExperienceState sharedState();

  /**
   * Open-ended "death" used in place of elimination: heals and feeds the player, returns them to
   * survival and teleports them to the experience spawn. Experiences never end on death and never
   * eject a player to the lobby — that only happens via {@code /leave}.
   */
  void softRespawn(PlayerAdapter playerAdapter);

  /**
   * A REAL death for a life-challenge depletion (XP Health / Shared Life running out): pauses the merged
   * health governance for the player then deals a lethal blow, so the vanilla death animation plays and
   * the death screen appears — instead of the player being silently teleported to spawn. keepInventory
   * (set on experience worlds) preserves their items + XP; {@link #softRespawn} re-seats them and resumes
   * governance when they respawn.
   */
  void killParticipant(PlayerAdapter playerAdapter);
}
