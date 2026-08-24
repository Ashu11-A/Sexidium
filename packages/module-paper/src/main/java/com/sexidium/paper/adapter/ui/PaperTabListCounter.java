package com.sexidium.paper.adapter.ui;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.TabListHandle;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Draws a number beside each name in the tab player list, using a scoreboard objective in the
 * {@link DisplaySlot#PLAYER_LIST} slot.
 *
 * <h2>Why it follows the viewer instead of the server</h2>
 * The obvious implementation puts one objective on the server's main scoreboard and is done. It does not
 * work here, and the reason is worth stating because it is invisible in testing with one account: a
 * player who is being shown a sidebar is not on the main board at all —
 * {@link PaperScoreboardPanelHandle} gives every such viewer their own {@code getNewScoreboard()}, which
 * is what stops one player's sidebar from being everybody's. An objective installed on the main board is
 * therefore invisible to exactly the players who are most likely to be in a mode that wants this column.
 *
 * <p>So the objective is installed on <em>whichever board each viewer is currently looking at</em>, and
 * every subject's score is written to each of those boards. Boards are deduplicated by identity, so the
 * common case — several viewers all on the main board — is one objective and one write per subject, not
 * one per viewer.</p>
 *
 * <h2>Re-asserting, and why the write is conditional</h2>
 * A viewer's board can be swapped underneath us at any time (the lobby HUD handing them back on join, a
 * sidebar going up or down as a challenge claims the screen). {@link #refresh()} therefore re-resolves
 * every viewer's board each pass rather than caching one, in the same self-healing spirit as
 * {@code PaperScoreboardPanelHandle.applyLines}. The score itself is only written when it actually
 * differs: the tab list is re-sent to clients on every score change, so an unconditional write once a
 * second would push a packet per player per second for a number that changes on death.
 */
final class PaperTabListCounter implements TabListHandle {
  private final String objectiveName;
  /** Subjects and their numbers — kept by UUID so a player who reconnects keeps their figure. */
  private final Map<UUID, Integer> counts = new ConcurrentHashMap<>();
  private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();

  PaperTabListCounter(String columnId) {
    // Scoreboard object names are length-limited and must be stable across a reload, so the caller's id
    // is namespaced and truncated rather than hashed — a readable name is worth more here than a unique
    // one, since two features sharing a column id are meant to collide loudly.
    String safe = "sx_" + (columnId == null ? "count" : columnId);
    this.objectiveName = safe.length() > 16 ? safe.substring(0, 16) : safe;
  }

  @Override
  public void count(PlayerAdapter playerAdapter, int value) {
    Player player = nativePlayer(playerAdapter);
    if (player == null) {
      return;
    }
    counts.put(player.getUniqueId(), value);
    writeScore(player.getName(), value);
  }

  @Override
  public void remove(PlayerAdapter playerAdapter) {
    Player player = nativePlayer(playerAdapter);
    if (player == null) {
      return;
    }
    counts.remove(player.getUniqueId());
    for (Scoreboard scoreboard : viewerBoards().keySet()) {
      Objective objective = scoreboard.getObjective(objectiveName);
      if (objective != null) {
        resetScore(objective, player.getName());
      }
    }
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    Player player = nativePlayer(playerAdapter);
    if (player == null) {
      return;
    }
    viewers.add(player.getUniqueId());
    Scoreboard scoreboard = player.getScoreboard();
    if (scoreboard != null) {
      paint(scoreboard);
    }
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    Player player = nativePlayer(playerAdapter);
    if (player == null) {
      return;
    }
    viewers.remove(player.getUniqueId());
    Scoreboard scoreboard = player.getScoreboard();
    // Only tear the column off this board if nobody still watching is on it. The main board is shared,
    // so removing it because ONE viewer left would blank the column for everyone else on it.
    if (scoreboard != null && !viewerBoards().containsKey(scoreboard)) {
      unregister(scoreboard);
    }
  }

  @Override
  public void refresh() {
    for (Scoreboard scoreboard : viewerBoards().keySet()) {
      paint(scoreboard);
    }
  }

  @Override
  public void close() {
    for (Scoreboard scoreboard : viewerBoards().keySet()) {
      unregister(scoreboard);
    }
    viewers.clear();
    counts.clear();
  }

  /**
   * The distinct boards currently being looked at by our viewers. An identity-keyed map because two
   * viewers on the main board are one board, and {@link Scoreboard} has no meaningful {@code equals}.
   */
  private Map<Scoreboard, Boolean> viewerBoards() {
    Map<Scoreboard, Boolean> boards = new java.util.IdentityHashMap<>();
    for (UUID viewerId : viewers) {
      Player viewer = Bukkit.getPlayer(viewerId);
      if (viewer != null && viewer.isOnline() && viewer.getScoreboard() != null) {
        boards.put(viewer.getScoreboard(), Boolean.TRUE);
      }
    }
    return boards;
  }

  /** Ensures the column exists on this board and carries every subject's current number. */
  private void paint(Scoreboard scoreboard) {
    Objective objective = ensureObjective(scoreboard);
    if (objective == null) {
      return;
    }
    for (Map.Entry<UUID, Integer> entry : new LinkedHashMap<>(counts).entrySet()) {
      String name = playerName(entry.getKey());
      if (name != null) {
        setScore(objective, name, entry.getValue());
      }
    }
  }

  private Objective ensureObjective(Scoreboard scoreboard) {
    Objective objective = scoreboard.getObjective(objectiveName);
    if (objective == null) {
      objective = scoreboard.registerNewObjective(objectiveName, Criteria.DUMMY,
          net.kyori.adventure.text.Component.empty());
    }
    // Re-asserted rather than set once: another plugin (or our own sidebar handle rebuilding a board)
    // can take the slot, and the column silently stops being drawn with no other symptom.
    if (objective.getDisplaySlot() != DisplaySlot.PLAYER_LIST) {
      objective.setDisplaySlot(DisplaySlot.PLAYER_LIST);
    }
    return objective;
  }

  private void writeScore(String entry, int value) {
    for (Scoreboard scoreboard : viewerBoards().keySet()) {
      Objective objective = ensureObjective(scoreboard);
      if (objective != null) {
        setScore(objective, entry, value);
      }
    }
  }

  /** Writes only on a real change — every score change re-sends the tab list to every client. */
  private void setScore(Objective objective, String entry, int value) {
    Score score = objective.getScore(entry);
    if (!score.isScoreSet() || score.getScore() != value) {
      score.setScore(value);
    }
  }

  /**
   * Clears one entry from THIS objective only.
   *
   * <p>Deliberately not {@code scoreboard.resetScores(entry)}, which drops the entry from every
   * objective on the board — including the sidebar's line entries and anything another plugin owns.</p>
   */
  private void resetScore(Objective objective, String entry) {
    Score score = objective.getScore(entry);
    if (score.isScoreSet()) {
      score.resetScore();
    }
  }

  private void unregister(Scoreboard scoreboard) {
    Objective objective = scoreboard.getObjective(objectiveName);
    if (objective != null) {
      objective.unregister();
    }
  }

  private String playerName(UUID playerId) {
    Player player = Bukkit.getPlayer(playerId);
    if (player != null) {
      return player.getName();
    }
    // An offline subject keeps their column entry: their name is what the tab list is keyed by, and a
    // player who logged out mid-run has not stopped having a death count.
    String cached = Bukkit.getOfflinePlayer(playerId).getName();
    return cached == null || cached.isBlank() ? null : cached;
  }

  private static Player nativePlayer(PlayerAdapter playerAdapter) {
    return playerAdapter instanceof PaperPlayerAdapter paperPlayerAdapter ? paperPlayerAdapter.handle() : null;
  }
}
