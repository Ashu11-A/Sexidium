package com.sexidium.core.game.hud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.HudPanelHandle;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * The single, unified per-player game HUD — one center-right scoreboard/BetterHud panel (the same
 * {@link com.sexidium.core.platform.UiAdapter#createPanel} library {@code LobbyHud} uses) that every
 * source (challenges, a minigame's scoreboard, the team card) writes its live info into. Replaces the
 * old scatter of per-feature boss bars, action bars and lone panels. Each source registers a
 * {@link HudContributor}; the host may also install a shared section (common stats) so it appears once,
 * not per source.
 *
 * <p>Generalized from the experience-only HUD so Experience, Chaos and every minigame share one
 * implementation. Each player has a {@link HudView} (FULL → COMPACT → HIDDEN) that the toggle gestures
 * {@link #cycle}; a HIDDEN player's panel is closed and rebuilt when they cycle back.</p>
 */
public final class GameHud {
  private final HudHost host;
  private final LocalizedText title;
  private final List<HudContributor> contributors = new ArrayList<>();
  private final Map<UUID, HudPanelHandle> panels = new HashMap<>();
  private final Map<UUID, Integer> lineCounts = new HashMap<>();
  // Per-player view; absent == FULL. Cycled by the double-sneak / look-up gestures.
  private final Map<UUID, HudView> views = new HashMap<>();
  /** Players whose inherited sidebar has already been stripped while suppressed; reset on un-suppress. */
  private final Set<UUID> cleared = new HashSet<>();
  private Consumer<HudContext> sharedSection = context -> {};
  private boolean sorted = true;
  // Who gets no panel at all: a mode that owns its own on-screen surface (Death Resets renders in
  // BetterHud's top-left overlay) does not also want the scoreboard sidebar competing with it. A
  // PREDICATE rather than a flag because the owned surface does not reach everyone — BetterHud rides
  // boss bars, so the Bedrock player in the same match has to keep the sidebar or see nothing at all.
  private Predicate<PlayerAdapter> panelSuppressed = player -> false;

  public GameHud(HudHost host, LocalizedText title) {
    this.host = host;
    this.title = title == null ? LocalizedText.of(MessageKey.GAME_HUD_TITLE) : title;
  }

  public void register(HudContributor contributor) {
    if (contributor != null) {
      contributors.add(contributor);
      sorted = false;
    }
  }

  /** Removes a previously registered contributor (live challenge removal / chaos cycle reset). */
  public void unregister(HudContributor contributor) {
    if (contributor != null) {
      contributors.remove(contributor);
      sorted = false;
    }
  }

  /**
   * Whether this HUD is currently drawing no sidebar for the given player.
   *
   * <p>Asked by whoever put a sidebar up BEFORE the match started — the lobby HUD — because its normal
   * handover assumes the match replaces the board. A player whose panel is suppressed has nothing
   * replacing it, so without this the lobby's board simply stays on screen for their whole match.</p>
   */
  public boolean panelSuppressed(PlayerAdapter player) {
    return player != null && panelSuppressed.test(player);
  }

  /**
   * Decides, per player, who gets no scoreboard panel — for a mode that renders its own on-screen
   * surface instead (Death Resets uses BetterHud's top-left overlay and wants nothing on the sidebar).
   * Contributors stay registered and the HUD stays installed — so nothing that reaches for {@code hud()}
   * breaks — there is simply no panel drawn for the players the predicate accepts. Closes their panels.
   *
   * <p>Idempotent and cheap to re-run: the caller reinstalls the predicate whenever the underlying
   * surface comes up or goes away, and a player it no longer accepts gets their panel rebuilt by the
   * next render pass.</p>
   */
  public void suppressPanel(Predicate<PlayerAdapter> suppressed) {
    this.panelSuppressed = suppressed == null ? player -> false : suppressed;
    for (PlayerAdapter player : host.online()) {
      if (player == null || !panelSuppressed.test(player)) {
        // Un-suppressing is left to the render pass, which rebuilds their panel and forgets that their
        // board was ever stripped.
        continue;
      }
      HudPanelHandle panel = panels.remove(player.uniqueId());
      if (panel != null) {
        // close() without hide(): hide() would restore whatever board this player had before, which is
        // the very lobby card the next line is about to take down.
        panel.close();
      }
      lineCounts.remove(player.uniqueId());
      // Closing OUR panel is not enough. The board on screen is very often not ours at all — a player
      // who walked in from the lobby is still wearing the lobby's server/points/rank card, which the
      // lobby deliberately hands over without clearing because it expects the match to draw over it. A
      // suppressed player never does, so the lobby card just sits there beside a corner readout that is
      // supposed to be the whole interface. Clear it from the player directly.
      clearInheritedSidebar(player);
    }
  }

  /**
   * Strips any sidebar a player brought with them into a suppressed HUD. Once per player: the board is
   * replaced with an empty one, and re-doing that every render pass would churn scoreboards for nothing.
   */
  private void clearInheritedSidebar(PlayerAdapter player) {
    if (player != null && player.online() && cleared.add(player.uniqueId())) {
      player.clearSidebar();
    }
  }

  /** Lines the host renders at the top for every source (shared stats). */
  public void sharedSection(Consumer<HudContext> section) {
    if (section != null) {
      this.sharedSection = section;
    }
  }

  /** Renders/refreshes the panel for every online participant. Drive from the host's tick. */
  public void render() {
    for (PlayerAdapter player : host.online()) {
      renderPlayer(player);
    }
  }

  public void show(PlayerAdapter player) {
    renderPlayer(player);
  }

  /** Hide + forget the player's panel (on leave). To preserve a view preference, use {@link #cycle}. */
  public void hide(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    closePanel(player);
    views.remove(player.uniqueId());
  }

  /** Advance the HUD view (FULL → COMPACT → HIDDEN → FULL) for one player (the toggle gesture). */
  public void cycle(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    UUID id = player.uniqueId();
    views.put(id, view(player).next());
    // renderPlayer closes the panel when HIDDEN and (re)builds it otherwise.
    renderPlayer(player);
  }

  public HudView view(PlayerAdapter player) {
    return player == null ? HudView.FULL : views.getOrDefault(player.uniqueId(), HudView.FULL);
  }

  public void close() {
    panels.values().forEach(HudPanelHandle::close);
    panels.clear();
    lineCounts.clear();
    views.clear();
  }

  private void closePanel(PlayerAdapter player) {
    HudPanelHandle panel = panels.remove(player.uniqueId());
    if (panel != null) {
      panel.hide(player);
      panel.close();
    }
    lineCounts.remove(player.uniqueId());
  }

  private void ensureSorted() {
    if (!sorted) {
      contributors.sort(Comparator.comparingInt(HudContributor::order));
      sorted = true;
    }
  }

  private void renderPlayer(PlayerAdapter player) {
    if (player == null) {
      return;
    }
    HudView playerView = view(player);
    boolean suppressed = panelSuppressed.test(player);
    if (!suppressed) {
      // Their board is theirs again — either a panel is about to be drawn over it, or the view toggle
      // took it down on purpose. Either way the strip we did is spent: if suppression comes back (their
      // overlay started drawing again), it has to strip whatever they are wearing by then.
      cleared.remove(player.uniqueId());
    }
    // Toggled-off, offline (disconnected reconnectable participant), no-context, or a mode that owns
    // this player's screen: ensure no panel lingers, and skip. render() re-creates it once they are back
    // and the HUD is neither hidden nor suppressed.
    if (suppressed || !player.online() || host.gameContext() == null || playerView.isHidden()) {
      closePanel(player);
      if (suppressed) {
        // Catches whoever arrived AFTER suppression was decided — a player joining a running match, or
        // reconnecting into one — who would otherwise walk in still wearing the lobby's board.
        clearInheritedSidebar(player);
      }
      return;
    }
    ensureSorted();
    HudPanelHandle panel = panels.get(player.uniqueId());
    if (panel == null) {
      panel = host.gameContext().server().ui().createPanel(title);
      panels.put(player.uniqueId(), panel);
      panel.show(player);
    }
    boolean debug = host.gameContext().server().configuration().getBoolean("debug", false);
    HudContext context = new HudContext(player, debug, playerView);
    sharedSection.accept(context);
    for (HudContributor contributor : contributors) {
      contributor.describe(context);
    }
    int line = 0;
    for (LocalizedText fragment : context.lines()) {
      panel.line(line++, fragment);
    }
    int previous = lineCounts.getOrDefault(player.uniqueId(), 0);
    for (int stale = line; stale < previous; stale++) {
      panel.removeLine(stale);
    }
    lineCounts.put(player.uniqueId(), line);
    panel.refresh();
  }
}
