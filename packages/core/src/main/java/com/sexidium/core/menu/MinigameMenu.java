package com.sexidium.core.menu;

import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Minigame discovery screens: the per-category mode grid and the per-mode detail screen offering the
 * two real ways into a match (quick-play queue or hosting a lobby) plus a live list of open lobbies.
 */
final class MinigameMenu {
  private final MenuSupport support;
  private final MenuService menus;

  MinigameMenu(MenuSupport support, MenuService menus) {
    this.support = support;
    this.menus = menus;
  }

  void openCategory(PlayerAdapter player, String category, String title) {
    GameManager gameManager = support.gameManager;
    List<GameModeDescriptor> modes = new ArrayList<>();
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      if (MenuSupport.normalize(descriptor.category()).equals(MenuSupport.normalize(category))) {
        modes.add(descriptor);
      }
    }
    int rows = Math.max(3, Math.min(6, (modes.size() / 9) + 2));
    // animated(): the platform cycles each mode tile's per-mode colour gradient (its nameFrames) in place.
    MenuView view = new MenuView(title, rows).background(MenuArt.BG_MINIGAMES).animated(true);
    int groupSize = support.roster(player).size();
    boolean inMatch = gameManager.matchOf(player) != null;
    int slot = 0;
    for (GameModeDescriptor descriptor : modes) {
      String modeId = descriptor.modeId();
      // Live player count for this mode: everyone in a running match of it, plus the quick-play queue.
      int playing = gameManager.playersInMode(modeId)
          + (support.lobbyManager != null ? support.lobbyManager.queueSize(modeId) : 0);
      // No more dead-end clicks: the detail screen always offers a working path (queue or host a lobby),
      // so a lone player never triggers the old "needs N players" error just by clicking a mode.
      String status = inMatch
          ? "<red>✖ Leave your current game first</red>"
          : "<green>✔ Click to play</green>";
      List<String> colors = modeColors(modeId);
      List<String> frames = animatedNameFrames(MenuSupport.escape(descriptor.displayName()), colors);
      // Distinct per-mode colour + a live-updating "playing now" count; the stack size badges the count too.
      MenuButton tile = new MenuButton(support.icon(modeId), Math.max(1, Math.min(64, playing)),
          frames.get(0),
          support.modeLore(descriptor,
              "<gray>Playing now: <white>" + playing + "</white></gray>",
              "<gray>Your group: <white>" + groupSize + "</white></gray>",
              status),
          ctx -> {
            if (gameManager.matchOf(ctx.player()) != null) {
              ctx.player().sendActionBar("<red>Leave your current game first (/leave).</red>");
              return;
            }
            menus.openModeDetail(ctx.player(), modeId);
          }, null, MenuArt.modeModel(modeId), frames);
      view.set(slot++, tile);
    }
    view.set(view.size() - 9, support.backButton(() -> menus.openMain(player)));
    support.open(player, view);
  }

  // Distinct colour theme per game mode, used both for the static gradient title and the animated
  // colour-sweep frames. A mode with no entry uses a neutral white/grey theme.
  private static final Map<String, List<String>> MODE_COLORS = Map.of(
      "race", List.of("#55ffff", "#5599ff", "#5555ff"),
      "gather", List.of("#7cff4f", "#33cc33", "#aaff55"),
      "tntwar", List.of("#ff5555", "#ffaa00", "#ff2200"),
      "combat", List.of("#ffaa00", "#ff5555", "#ffdd55"),
      "fugitive", List.of("#ff55ff", "#aa55ff", "#ff88ff"));
  private static final List<String> DEFAULT_COLORS = List.of("#ffffff", "#c8c8c8", "#ffffff");

  private static List<String> modeColors(String modeId) {
    return MODE_COLORS.getOrDefault(modeId, DEFAULT_COLORS);
  }

  /**
   * Builds the animation frames for a mode title: the same bold text painted with a {@code <gradient>} in
   * the mode's colours, with the colour order rotated one step per frame so the platform cycling them in
   * place reads as a colour sweep. Frame 0 is the static fallback title.
   */
  private static List<String> animatedNameFrames(String plainName, List<String> colors) {
    List<String> frames = new ArrayList<>();
    int steps = Math.max(2, colors.size());
    for (int frame = 0; frame < steps; frame++) {
      List<String> rotated = new ArrayList<>(colors.size());
      for (int index = 0; index < colors.size(); index++) {
        rotated.add(colors.get((index + frame) % colors.size()));
      }
      frames.add("<gradient:" + String.join(":", rotated) + "><bold>" + plainName + "</bold></gradient>");
    }
    return frames;
  }

  /**
   * Per-minigame screen offering the two ways to actually get into a match:
   * <ul>
   *   <li><b>Quick Play</b> — join the matchmaking queue and auto-start with strangers (and your party)
   *       when enough players are waiting. This is what makes a lone / mobile player able to play at all
   *       instead of hitting a minimum-players error.</li>
   *   <li><b>Create Lobby</b> — host a match lobby to invite friends and pick fixed teams.</li>
   *   <li><b>Join in progress</b> — only shown when a match of this mode a friend/party member is in is
   *       running.</li>
   * </ul>
   */
  void openModeDetail(PlayerAdapter player, String modeId) {
    LobbyManager lobbyManager = support.lobbyManager;
    ServerAdapter serverAdapter = support.serverAdapter;
    GameModeDescriptor descriptor = support.descriptorOf(modeId);
    if (descriptor == null) {
      menus.openCategory(player, "minigames", "<aqua><bold>Minigames</bold></aqua>");
      return;
    }
    String mode = descriptor.modeId();
    MenuView view = new MenuView("<aqua><bold>" + MenuSupport.escape(descriptor.displayName()) + "</bold></aqua>", 6)
        .background(MenuArt.BG_MINIGAMES);
    // Shared info header: same icon + description + min-players the mode shows on the grid and lobby rows.
    view.set(4, MenuButton.label(support.icon(mode), "<white><bold>" + descriptor.displayName() + "</bold></white>",
        support.modeLore(descriptor)));
    boolean queuedHere = lobbyManager != null && mode.equals(lobbyManager.queuedMode(player.uniqueId()));
    view.set(11, MenuButton.of(ItemKey.minecraft("diamond_sword"),
        queuedHere ? "<yellow><bold>In quick-play queue</bold></yellow>" : "<green><bold>Quick Play</bold></green>",
        List.of("<gray>Auto-match with anyone online</gray>",
            queuedHere
                ? "<red>Click to leave the queue</red>"
                : "<yellow>Click to join the queue</yellow>"),
        ctx -> {
          if (lobbyManager == null) {
            ctx.player().sendActionBar("<red>Quick play is unavailable.</red>");
            return;
          }
          if (mode.equals(lobbyManager.queuedMode(ctx.player().uniqueId()))) {
            lobbyManager.dequeue(ctx.player());
            ctx.player().sendActionBar("<yellow>Left the quick-play queue.</yellow>");
            menus.openModeDetail(ctx.player(), mode);
            return;
          }
          LobbyResult result = lobbyManager.queue(ctx.player(), mode);
          switch (result) {
            case QUEUED, ALREADY_QUEUED -> {
              serverAdapter.menus().close(ctx.player());
              ctx.player().sendActionBar("<green>Searching for a match… we'll start when enough players are ready.</green>");
            }
            case ALREADY_IN_MATCH -> ctx.player().sendActionBar("<red>Leave your current game first (/leave).</red>");
            case NOT_LEADER -> ctx.player().sendActionBar("<red>Only your group leader can queue. Leave your group to play solo.</red>");
            case NOT_MINIGAME -> ctx.player().sendActionBar("<red>That mode can't be quick-played.</red>");
            default -> ctx.player().sendActionBar("<red>Could not join the queue.</red>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_QUICK_PLAY)));
    view.set(13, MenuButton.of(ItemKey.minecraft("oak_sign"), "<gold><bold>Create Lobby</bold></gold>",
        List.of("<gray>Host a private/public lobby</gray>",
            "<gray>Invite friends, pick teams, start</gray>"),
        ctx -> {
          if (lobbyManager == null) {
            ctx.player().sendActionBar("<red>Lobbies are unavailable.</red>");
            return;
          }
          LobbyResult result = lobbyManager.configure(ctx.player(), mode);
          switch (result) {
            case CONFIGURED -> menus.openLobby(ctx.player());
            case NOT_LEADER -> ctx.player().sendActionBar("<red>Only your group leader can host a match.</red>");
            case ALREADY_IN_MATCH -> ctx.player().sendActionBar("<red>You are already in a match.</red>");
            default -> ctx.player().sendActionBar("<red>Could not create the lobby.</red>");
          }
        }).withModel(MenuArt.model(MenuArt.ICON_CREATE_LOBBY)));
    // Live list of open lobbies for this mode (public + lobbies the player was invited to). This
    // replaces the old "Join in progress" — players join a lobby, not a running match.
    int lobbySlot = 18;
    if (lobbyManager != null) {
      for (Lobby lobby : lobbyManager.joinableFor(player.uniqueId(), mode)) {
        if (lobbySlot >= 45) {
          break;
        }
        view.set(lobbySlot++, menus.lobbyButton(lobby));
      }
    }
    if (lobbySlot == 18) {
      view.set(31, MenuButton.label(ItemKey.minecraft("paper"), "<gray>No open lobbies for this mode</gray>",
          List.of("<gray>Use <white>Create Lobby</white> or <white>Quick Match</white> above</gray>")));
    }
    view.set(view.size() - 9, support.back(ctx -> menus.openCategory(ctx.player(), "minigames", "<aqua><bold>Minigames</bold></aqua>")));
    support.open(player, view);
  }
}
