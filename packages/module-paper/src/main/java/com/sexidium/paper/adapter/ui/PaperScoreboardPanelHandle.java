package com.sexidium.paper.adapter.ui;

import com.sexidium.core.i18n.Language;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A per-player sidebar HUD backed by a single, persistent scoreboard. The board, its objective and
 * its line teams are created ONCE per viewer; every {@link #refresh()} updates the existing teams'
 * prefixes (and adds/removes lines) IN PLACE rather than building a brand-new scoreboard. That is the
 * key to not flickering — a full {@code getNewScoreboard()} + {@code setScoreboard()} every cycle
 * makes the sidebar blink, and on Bedrock (Geyser) clients it also briefly flashes the per-line score
 * numbers before the blank number-format is re-applied. Reusing the board removes both.
 */
final class PaperScoreboardPanelHandle implements HudPanelHandle {
  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
  private static final int MAX_LINES = 15;
  private final PaperMessageAdapter messageAdapter;
  private final LocalizedText title;
  private final Map<Integer, LocalizedText> lines = new HashMap<>();
  // The scoreboard we installed for the player (so refresh updates it in place).
  private final Map<UUID, BoardState> active = new HashMap<>();
  // The scoreboard a player had before we took over, restored on hide.
  private final Map<UUID, Scoreboard> previousScoreboards = new HashMap<>();

  private record BoardState(Scoreboard scoreboard, Objective objective) {
  }

  PaperScoreboardPanelHandle(PaperMessageAdapter messageAdapter, LocalizedText title) {
    this.messageAdapter = messageAdapter;
    this.title = title;
  }

  @Override
  public void line(int index, LocalizedText localizedText) {
    // Mutate only; callers repaint once with refresh() after a batch of line() calls.
    if (index >= 0 && localizedText != null) {
      lines.put(index, localizedText);
    }
  }

  @Override
  public void removeLine(int index) {
    lines.remove(index);
  }

  @Override
  public void refresh() {
    if (Bukkit.getScoreboardManager() == null) {
      return;
    }
    for (UUID playerId : active.keySet().toArray(UUID[]::new)) {
      org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        applyLines(new PaperPlayerAdapter(player), active.get(playerId));
      }
    }
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    org.bukkit.entity.Player player = nativePlayer(playerAdapter);
    if (player == null || Bukkit.getScoreboardManager() == null) {
      return;
    }
    previousScoreboards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
    BoardState state = ensureBoard(player);
    if (state != null) {
      player.setScoreboard(state.scoreboard());
      applyLines(playerAdapter, state);
    }
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    org.bukkit.entity.Player player = nativePlayer(playerAdapter);
    if (player == null) {
      return;
    }
    active.remove(player.getUniqueId());
    if (!previousScoreboards.containsKey(player.getUniqueId())) {
      return;
    }
    Scoreboard previous = previousScoreboards.remove(player.getUniqueId());
    ScoreboardManager manager = Bukkit.getScoreboardManager();
    if (previous != null) {
      player.setScoreboard(previous);
    } else if (manager != null) {
      player.setScoreboard(manager.getMainScoreboard());
    }
  }

  @Override
  public void forget(PlayerAdapter playerAdapter) {
    // Drop our tracking of the player but leave their current scoreboard alone — the caller knows a
    // different board (a match's) now owns the slot, so restoring our remembered "previous" board would
    // overwrite it. This is what stops the lobby HUD from stealing the match sidebar on join.
    org.bukkit.entity.Player player = nativePlayer(playerAdapter);
    if (player != null) {
      active.remove(player.getUniqueId());
      previousScoreboards.remove(player.getUniqueId());
    }
  }

  @Override
  public void close() {
    UUID[] ids = previousScoreboards.keySet().toArray(UUID[]::new);
    for (UUID playerId : ids) {
      org.bukkit.entity.Player player = Bukkit.getPlayer(playerId);
      if (player != null) {
        hide(new PaperPlayerAdapter(player));
      } else {
        previousScoreboards.remove(playerId);
      }
    }
    active.clear();
  }

  /** Creates (once) the persistent board + objective for a player. */
  private BoardState ensureBoard(org.bukkit.entity.Player player) {
    BoardState existing = active.get(player.getUniqueId());
    if (existing != null) {
      return existing;
    }
    ScoreboardManager manager = Bukkit.getScoreboardManager();
    if (manager == null) {
      return null;
    }
    Scoreboard scoreboard = manager.getNewScoreboard();
    Objective objective = scoreboard.registerNewObjective("sexidium", Criteria.DUMMY,
        MINI_MESSAGE.deserialize(render(new PaperPlayerAdapter(player), title)));
    objective.setDisplaySlot(DisplaySlot.SIDEBAR);
    // Hide the red per-line score number (it is only used to order the sidebar). A blank format
    // removes it on clients that honour it; reused board + Bedrock re-assert (below) keep it gone.
    objective.numberFormat(NumberFormat.blank());
    BoardState state = new BoardState(scoreboard, objective);
    active.put(player.getUniqueId(), state);
    return state;
  }

  /**
   * Updates the existing board's title and lines IN PLACE. Each line is an Adventure {@code Component}
   * carried as a {@link Team#prefix} on a unique invisible entry, so colour and translatable
   * components (e.g. {@code <lang:item.minecraft.iron_ingot>}) render per-viewer. No teams or entries
   * are recreated unless the line count changed, so the sidebar never blinks.
   */
  private void applyLines(PlayerAdapter viewer, BoardState state) {
    if (state == null) {
      return;
    }
    Scoreboard scoreboard = state.scoreboard();
    // Re-assert ownership: if something external swapped the player off our board between refreshes —
    // the lobby HUD handing them back to the main board when they join a match, a world change, another
    // plugin — put our board back. Only fires when actually detached, so it is a cheap no-op normally and
    // self-heals the sidebar within one refresh tick (the fix for the "Race board vanishes on join" bug).
    org.bukkit.entity.Player nativePlayer = nativePlayer(viewer);
    if (nativePlayer != null && nativePlayer.getScoreboard() != scoreboard) {
      nativePlayer.setScoreboard(scoreboard);
    }
    Objective objective = state.objective();
    objective.displayName(MINI_MESSAGE.deserialize(render(viewer, title)));
    // Bedrock/Geyser clients can drop the objective number-format between updates; re-assert it so the
    // line-count numbers never leak back in on mobile (no-op/cheap on Java).
    if (viewer != null && viewer.isBedrock()) {
      objective.numberFormat(NumberFormat.blank());
    }
    List<Map.Entry<Integer, LocalizedText>> sortedLines = lines.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .limit(MAX_LINES)
        .toList();
    int count = sortedLines.size();
    int score = count;
    for (Map.Entry<Integer, LocalizedText> entry : sortedLines) {
      String lineEntry = invisibleEntry(score);
      Team team = scoreboard.getTeam("sx_" + score);
      if (team == null) {
        team = scoreboard.registerNewTeam("sx_" + score);
      }
      if (!team.hasEntry(lineEntry)) {
        team.addEntry(lineEntry);
      }
      team.prefix(MINI_MESSAGE.deserialize(render(viewer, entry.getValue())));
      Score handle = objective.getScore(lineEntry);
      if (!handle.isScoreSet() || handle.getScore() != score) {
        handle.setScore(score);
      }
      score--;
    }
    // Drop rows beyond the current line count (when the HUD shrank).
    for (int stale = count + 1; stale <= MAX_LINES; stale++) {
      scoreboard.resetScores(invisibleEntry(stale));
      Team team = scoreboard.getTeam("sx_" + stale);
      if (team != null) {
        team.unregister();
      }
    }
  }

  private String render(PlayerAdapter viewer, LocalizedText localizedText) {
    if (localizedText == null || localizedText.messageKey() == null) {
      return "";
    }
    try {
      MessageService service = messageAdapter.service();
      return service.renderMini(viewer == null ? Language.EN : service.language(viewer), localizedText);
    } catch (IllegalStateException exception) {
      return localizedText.messageKey().path();
    }
  }

  /** A unique, visually-empty sidebar entry for the given score (a colour code plus reset). */
  private String invisibleEntry(int score) {
    return "§" + Integer.toString(Math.max(0, Math.min(MAX_LINES, score)), 16) + "§r";
  }

  private org.bukkit.entity.Player nativePlayer(PlayerAdapter playerAdapter) {
    return playerAdapter instanceof PaperPlayerAdapter paperPlayerAdapter ? paperPlayerAdapter.handle() : null;
  }
}
