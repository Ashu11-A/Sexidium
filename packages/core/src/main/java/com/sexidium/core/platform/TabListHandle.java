package com.sexidium.core.platform;

/**
 * A number shown beside every player's name in the tab player list.
 *
 * <h2>What this is for</h2>
 * A per-player figure that is interesting <em>about other people</em>. The sidebar and the corner
 * overlay both answer "how am I doing"; the tab list is the one surface where a player reads the same
 * statistic for everybody at once, which is what makes it the right home for something like a death
 * count in a mode where everyone's deaths cost everyone.
 *
 * <h2>Numbers only</h2>
 * The backing mechanism (a scoreboard objective in the player-list display slot) renders the score and
 * nothing else — no label, no colour, no unit. That is a vanilla limitation, not an omission here: the
 * objective's title is never drawn in the tab list. So whatever this shows has to be self-evident from
 * context, and anything that needs explaining belongs on the sidebar instead.
 *
 * <h2>Viewers and subjects are different sets</h2>
 * {@link #show} enrols a VIEWER — somebody who should see the column. {@link #count} sets a SUBJECT's
 * number. They usually overlap, but they are separate on purpose: a spectator is a viewer who is not a
 * subject, and a player who has disconnected mid-run is a subject who is not a viewer, and both should
 * keep working.
 *
 * <p>Per-viewer enrolment matters more than it looks, because a viewer may be looking at a private
 * scoreboard rather than the server's main one (that is how {@link HudPanelHandle} draws a sidebar). A
 * column installed only on the main board would be invisible to exactly those players, so the
 * implementation follows the viewer, not the server.</p>
 */
public interface TabListHandle {
  /** The handle every platform without a tab-list column returns. Never shows anything. */
  TabListHandle NOOP = new TabListHandle() {
    @Override
    public void count(PlayerAdapter playerAdapter, int value) {
    }

    @Override
    public void remove(PlayerAdapter playerAdapter) {
    }

    @Override
    public void show(PlayerAdapter playerAdapter) {
    }

    @Override
    public void hide(PlayerAdapter playerAdapter) {
    }

    @Override
    public void close() {
    }
  };

  /** Sets the number shown beside this player's name, for every viewer. */
  void count(PlayerAdapter playerAdapter, int value);

  /** Drops a player's number entirely, so no figure is drawn beside their name. */
  void remove(PlayerAdapter playerAdapter);

  /** Starts drawing the column for a viewer. */
  void show(PlayerAdapter playerAdapter);

  /** Stops drawing the column for a viewer, leaving their own number intact for everyone else. */
  void hide(PlayerAdapter playerAdapter);

  /**
   * Re-asserts the column on every viewer's current board. Cheap and idempotent — callers push on their
   * own refresh cadence rather than tracking which viewer's scoreboard was swapped out from under them.
   */
  default void refresh() {
  }

  /** Tears the column down for everyone and releases whatever the platform allocated for it. */
  void close();
}
