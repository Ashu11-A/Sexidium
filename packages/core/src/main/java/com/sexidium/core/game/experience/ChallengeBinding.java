package com.sexidium.core.game.experience;

import com.sexidium.core.platform.BossBarHandle;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.ScheduledTask;
import com.sexidium.core.platform.TabListHandle;

import java.util.ArrayList;
import java.util.List;

/**
 * Records everything one {@link Challenge} created while it was wired into a live host, so the host can
 * tear that single challenge down again without ending the whole match. This is what makes the live
 * experience editor and the Chaos mode's cycle reset possible: removing a challenge cancels exactly its
 * timers/boss bars/HUD panels and runs its {@code undo} hooks (which unregister its pipeline
 * contributions), leaving every other challenge untouched.
 *
 * <p>Resources are attributed to the binding currently being wired via the host's "active binding"
 * pointer ({@link ExperienceGame#recordUndo}, and the {@code runTimer}/{@code track} overrides).</p>
 */
public final class ChallengeBinding {
  public final Challenge challenge;
  /** Pipeline unregisters + capability unpublishes (run on teardown, reverse order). */
  public final List<Runnable> undo = new ArrayList<>();
  public final List<ScheduledTask> tasks = new ArrayList<>();
  public final List<BossBarHandle> bars = new ArrayList<>();
  public final List<HudPanelHandle> panels = new ArrayList<>();
  public final List<HudSurfaceHandle> surfaces = new ArrayList<>();
  public final List<TabListHandle> tabLists = new ArrayList<>();

  public ChallengeBinding(Challenge challenge) {
    this.challenge = challenge;
  }

  /** Cancels every tracked resource and runs every undo hook (reverse registration order). */
  public void teardown() {
    for (ScheduledTask task : tasks) {
      task.cancel();
    }
    for (BossBarHandle bar : bars) {
      bar.close();
    }
    for (HudPanelHandle panel : panels) {
      panel.close();
    }
    for (HudSurfaceHandle surface : surfaces) {
      surface.close();
    }
    for (TabListHandle tabList : tabLists) {
      tabList.close();
    }
    for (int index = undo.size() - 1; index >= 0; index--) {
      undo.get(index).run();
    }
    tasks.clear();
    bars.clear();
    panels.clear();
    surfaces.clear();
    tabLists.clear();
    undo.clear();
  }
}
