package com.sexidium.core.menu;

import com.sexidium.core.Branding;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameModeDescriptor;
import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.game.experience.ExperienceSetup;
import com.sexidium.core.game.experience.ExperienceWorldType;
import com.sexidium.core.data.FriendService;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.world.lobby.LobbyResult;
import com.sexidium.core.world.lobby.LobbyEnums.LobbyVisibility;
import com.sexidium.core.world.npc.NpcManager;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Shared toolkit for the lobby chest-GUI screens. Holds every platform dependency the menu screens
 * need plus the cross-cutting state (the per-player experience-builder selection and the LEFT-tap
 * "tap again to confirm" gesture) and the small text / button / navigation helpers reused across
 * screens. The individual {@code *Menu} screen classes compose this; {@link MenuService} owns the
 * single instance and remains the public facade.
 */
final class MenuSupport {
  final ServerAdapter serverAdapter;
  final GameManager gameManager;
  final LobbyManager lobbyManager;
  final FriendService friendService;
  final ExperienceService experienceService;
  final NpcManager npcManager;

  // Per-player in-progress challenge selection for the experience builder (reset at each entry point).
  final Map<UUID, Set<String>> builderSelection = new ConcurrentHashMap<>();
  // Per-player map type chosen in the experience builder (absent = a normal terrain world). Single-valued
  // on purpose: it is what makes the map-generating twists mutually exclusive.
  final Map<UUID, ExperienceWorldType> builderWorldType = new ConcurrentHashMap<>();
  // Per-player keep-inventory choice in the builder. Absent = the default (ON), so a player who never
  // touches the toggle gets the behaviour experiences have always had.
  final Map<UUID, Boolean> builderKeepInventory = new ConcurrentHashMap<>();
  // Per-player HARDCORE choice in the builder. Absent = OFF, so hardcore is only ever something the
  // player deliberately turned on — never something they can end up in by not noticing a tile.
  final Map<UUID, Boolean> builderHardcore = new ConcurrentHashMap<>();
  // Which experience id a player's current selection was seeded from (null/absent = the create builder).
  // Keying by id closes the leak where an edited set bled into a fresh "Create Experience" (Bedrock
  // players close menus by tapping out, never reaching the Back button that used to clear it).
  final Map<UUID, String> editingExperience = new ConcurrentHashMap<>();
  // Per-player armed destructive confirm: a LEFT-tap-friendly "tap again to confirm" gesture. Geyser
  // maps every Bedrock tap to a plain LEFT click, so shift-click confirms are physically impossible on
  // mobile; arming on the first tap and acting on the second works identically on Java and Bedrock.
  //
  // Concurrent for the same reason liveScreens below is: a tile is armed from a click on the player's
  // own region thread (Folia) while the callbacks that clear it — a routed delete or restore answering
  // on the router's recovery tick — arrive on the global region. Every map on this block is per-player
  // state written from both, and a plain HashMap resized under two threads loses entries silently.
  private final Map<UUID, String> pendingConfirm = new ConcurrentHashMap<>();
  private final Map<UUID, Long> pendingConfirmAt = new ConcurrentHashMap<>();
  static final long CONFIRM_WINDOW_MS = 5000L;
  // The clock that window is measured on. Package-private and swappable for one reason: whether an
  // armed Delete goes cold is worth a test, and a test that really waits five seconds for each case is
  // a test somebody eventually deletes. Nothing in production ever assigns it.
  java.util.function.LongSupplier clock = System::currentTimeMillis;

  MenuSupport(ServerAdapter serverAdapter, GameManager gameManager, LobbyManager lobbyManager,
      FriendService friendService, ExperienceService experienceService, NpcManager npcManager) {
    this.serverAdapter = serverAdapter;
    this.gameManager = gameManager;
    this.lobbyManager = lobbyManager;
    this.friendService = friendService;
    this.experienceService = experienceService;
    this.npcManager = npcManager;
  }

  // Which viewer has which SELF-REFRESHING screen open, and how to draw it again. A screen is built
  // once, from the rows as they were at that instant, and the experiences it lists belong to other
  // players who keep changing them — so a browse list drawn a minute ago is a minute out of date, and
  // on a network the change was very likely made on a different node entirely.
  //
  // Every ordinary open() clears the entry. That is what stops a redraw from yanking a player out of
  // whatever screen they moved on to: only the screen they are actually looking at is ever redrawn.
  //
  // Concurrent, unlike the per-player builder state above, for the same reason PaperMenuAdapter's
  // animated-menu map is: the redraw is driven by a bus message hopped onto the global region thread,
  // while a menu is opened from a click on the player's own region thread (Folia).
  private final Map<UUID, Consumer<PlayerAdapter>> liveScreens = new java.util.concurrent.ConcurrentHashMap<>();

  void open(PlayerAdapter player, MenuView view) {
    if (player != null) {
      liveScreens.remove(player.uniqueId());
    }
    if (serverAdapter != null && serverAdapter.menus() != null) {
      serverAdapter.menus().open(player, view);
    }
  }

  /**
   * Marks the screen just opened for {@code player} as one that must be redrawn when the data behind
   * it changes. Called right after {@link #open}, which is what cleared the previous registration.
   */
  void trackLive(PlayerAdapter player, Consumer<PlayerAdapter> render) {
    if (player != null && render != null) {
      liveScreens.put(player.uniqueId(), render);
    }
  }

  /**
   * Redraw every tracked screen that is still on a viewer's client.
   *
   * <p>The render re-registers itself (it goes through {@link #open} and {@link #trackLive} again),
   * or registers whatever screen it decided to show instead — a manage screen for an experience that
   * has just been deleted sends the owner back to their list, and it is that list which is tracked
   * from then on.</p>
   */
  void redrawLiveScreens() {
    if (liveScreens.isEmpty()) {
      return;
    }
    for (Map.Entry<UUID, Consumer<PlayerAdapter>> entry : new ArrayList<>(liveScreens.entrySet())) {
      Consumer<PlayerAdapter> render = liveScreens.remove(entry.getKey());
      if (render == null || serverAdapter == null) {
        continue;
      }
      PlayerAdapter viewer = serverAdapter.player(entry.getKey())
          .filter(PlayerAdapter::online).orElse(null);
      // Not online any more: dropping the entry IS the cleanup.
      if (viewer == null) {
        continue;
      }
      // Onto the region that owns this player. Reading what they have open and opening a screen are
      // both entity operations, and this loop is driven from the global region (a bus message, or a
      // change made by somebody else entirely) — which on Folia is the wrong thread for every one of
      // these viewers, and throws rather than racing.
      if (serverAdapter.scheduler() != null) {
        serverAdapter.scheduler().runForPlayer(viewer, () -> {
          // Re-asked here rather than above: between the hop being scheduled and it running, the viewer
          // can log out or move to a screen of their own.
          if (viewer.online() && serverAdapter.menus() != null && serverAdapter.menus().isOpen(viewer)) {
            render.accept(viewer);
          }
        }, null);
      }
    }
  }

  String name(UUID playerId) {
    return serverAdapter.player(playerId).map(PlayerAdapter::name).orElse("?");
  }

  String brandLabel() {
    return Branding.label(serverAdapter.configuration());
  }

  /** The shared menu icon for a mode, read from its descriptor so every screen shows the same item. */
  ItemKey icon(String modeId) {
    GameModeDescriptor descriptor = descriptorOf(modeId);
    return ItemKey.minecraft(descriptor != null ? descriptor.iconItem() : "paper");
  }

  /**
   * The standard descriptive lore every minigame tile shares: the mode's description lines followed by
   * its minimum-players line, then any screen-specific {@code extra} lines (group size, status, …). All
   * the discovery and lobby screens build their tiles through this so a mode reads identically across
   * the mode grid, the mode-detail header, and joinable-lobby rows.
   */
  List<String> modeLore(GameModeDescriptor descriptor, String... extra) {
    List<String> lore = new ArrayList<>(descriptor.description());
    lore.add("<gray>Min players: <white>" + descriptor.minPlayers() + "</white></gray>");
    for (String line : extra) {
      lore.add(line);
    }
    return lore;
  }

  static String escape(String value) {
    return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
  }

  static String normalize(String value) {
    return value == null ? "" : value.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
  }

  MenuButton backButton(Runnable openMain) {
    return back(ctx -> openMain.run());
  }

  /** A "« Back" button wearing the custom back icon (pack-loaded viewers), with a custom target. */
  MenuButton back(Consumer<MenuContext> onClick) {
    return MenuButton.of(ItemKey.minecraft("arrow"), "<red>« Back</red>", onClick)
        .withModel(MenuArt.model(MenuArt.ICON_BACK));
  }

  GameModeDescriptor descriptorOf(String modeId) {
    if (modeId == null) {
      return null;
    }
    String wanted = modeId.toLowerCase(Locale.ROOT).trim();
    for (GameModeDescriptor descriptor : gameManager.descriptors()) {
      if (descriptor.modeId().equals(wanted) || descriptor.aliases().contains(wanted)) {
        return descriptor;
      }
    }
    return null;
  }

  List<PlayerAdapter> roster(PlayerAdapter player) {
    Map<UUID, PlayerAdapter> roster = new LinkedHashMap<>();
    roster.put(player.uniqueId(), player);
    if (lobbyManager != null) {
      Lobby lobby = lobbyManager.lobbyOf(player.uniqueId());
      if (lobby != null) {
        for (PlayerAdapter member : lobby.onlineMembers(serverAdapter)) {
          roster.put(member.uniqueId(), member);
        }
      }
    }
    return new ArrayList<>(roster.values());
  }

  /**
   * A reusable click-to-select roster of online players — the keystone of the mobile/Bedrock experience,
   * since it replaces every "type a player name" command (friend add, party invite) with a tap.
   */
  void openPlayerPicker(PlayerAdapter viewer, String title, Predicate<PlayerAdapter> include,
      Consumer<PlayerAdapter> onPick, MenuButton back) {
    openPlayerPicker(viewer, title, 0, include, onPick, back);
  }

  void openPlayerPicker(PlayerAdapter viewer, String title, int page, Predicate<PlayerAdapter> include,
      Consumer<PlayerAdapter> onPick, MenuButton back) {
    List<PlayerAdapter> candidates = new ArrayList<>();
    for (PlayerAdapter candidate : serverAdapter.onlinePlayers()) {
      if (include == null || include.test(candidate)) {
        candidates.add(candidate);
      }
    }
    MenuView view = PaginatedScreen.<PlayerAdapter>of(title)
        .background(MenuArt.BG_FRIENDS)
        .items(candidates)
        .page(page)
        .emptyIndicator(MenuButton.label(ItemKey.minecraft("barrier"), "<gray>No players available</gray>", List.of()))
        .itemMapper(candidate -> MenuButton.head(candidate.uniqueId(), "<white>" + escape(candidate.name()) + "</white>",
            List.of("<yellow>Click to select</yellow>"), ctx -> onPick.accept(candidate)))
        .onPageChange(p -> openPlayerPicker(viewer, title, p, include, onPick, back))
        .back(back)
        .build();
    open(viewer, view);
  }

  int pendingInviteCount(UUID playerId) {
    int count = 0;
    if (friendService != null) {
      count += friendService.incomingRequests(playerId).size();
    }
    if (lobbyManager != null) {
      count += lobbyManager.pendingInviteLobbies(playerId).size();
    }
    return count;
  }

  String challengesSummary(ExperienceManager.Experience experience) {
    List<String> names = new ArrayList<>();
    for (String id : experience.challenges()) {
      names.add(ChallengeCatalog.displayNameFor(id));
    }
    return names.isEmpty() ? "none" : String.join(", ", names);
  }

  void announceEnter(PlayerAdapter player, ExperienceService.EnterOutcome outcome) {
    player.sendMiniMessage(switch (outcome) {
      case ENTERED, STARTED -> "<green>Entering experience…</green>";
      case REQUEST_SENT -> "<yellow>Join request sent to the owner.</yellow>";
      case ALREADY_IN_MATCH -> "<red>Leave your current experience first (/leave).</red>";
      case NOT_FOUND -> "<red>That experience no longer exists.</red>";
      case LIMIT_REACHED -> "<red>You have reached your limit of " + experienceService.maxPerPlayer()
          + " experience worlds. Delete one first.</red>";
      case HOST_OFFLINE -> "<red>The owner of that experience is offline.</red>";
      case OFFLINE, FAILED -> "<red>Could not enter that experience.</red>";
    });
  }

  String lobbyInviteMessage(LobbyResult result, String targetName) {
    return switch (result) {
      case INVITE_SENT -> "<green>Invited " + escape(targetName) + " to your lobby.</green>";
      case SELF -> "<red>You cannot invite yourself.</red>";
      case NOT_LEADER -> "<red>Only the lobby leader can invite.</red>";
      case FULL -> "<red>Your lobby is full.</red>";
      case TARGET_IN_PARTY -> "<red>" + escape(targetName) + " is already in a group.</red>";
      default -> "<red>Could not invite " + escape(targetName) + ".</red>";
    };
  }

  String lobbyAcceptMessage(LobbyResult result, String leaderName) {
    return switch (result) {
      case JOINED -> "<green>You joined " + escape(leaderName) + "'s lobby.</green>";
      case NO_INVITE -> "<red>That invite expired.</red>";
      case AMBIGUOUS -> "<red>Multiple invites — pick one.</red>";
      case FULL -> "<red>That lobby is full.</red>";
      case DISBANDED -> "<red>That lobby no longer exists.</red>";
      case ALREADY_IN_PARTY -> "<red>You are already in a group.</red>";
      default -> "<red>Could not join that lobby.</red>";
    };
  }

  static ItemKey visibilityIcon(LobbyVisibility visibility) {
    return switch (visibility) {
      case PUBLIC -> ItemKey.minecraft("lime_dye");
      case FRIENDS_ONLY -> ItemKey.minecraft("yellow_dye");
      case INVITE_ONLY -> ItemKey.minecraft("gray_dye");
    };
  }

  static String visibilityTitle(LobbyVisibility visibility) {
    return switch (visibility) {
      case PUBLIC -> "<green><bold>Public</bold></green>";
      case FRIENDS_ONLY -> "<yellow><bold>Friends only</bold></yellow>";
      case INVITE_ONLY -> "<red><bold>Invite-only</bold></red>";
    };
  }

  static String visibilityHint(LobbyVisibility visibility) {
    return switch (visibility) {
      case PUBLIC -> "<gray>Anyone can join from the browser</gray>";
      case FRIENDS_ONLY -> "<gray>Your friends can join from the browser</gray>";
      case INVITE_ONLY -> "<gray>Only invited players can join</gray>";
    };
  }

  static LobbyVisibility nextVisibility(LobbyVisibility visibility) {
    return switch (visibility) {
      case PUBLIC -> LobbyVisibility.FRIENDS_ONLY;
      case FRIENDS_ONLY -> LobbyVisibility.INVITE_ONLY;
      case INVITE_ONLY -> LobbyVisibility.PUBLIC;
    };
  }

  // ----- experience-builder selection state ----------------------------------------------------

  Set<String> builderSelectionFor(UUID id) {
    return builderSelection.computeIfAbsent(id, ignored -> java.util.Collections.synchronizedSet(new LinkedHashSet<>()));
  }

  /** The map type the player currently has chosen in the builder (normal terrain until they change it). */
  ExperienceWorldType builderWorldTypeFor(UUID id) {
    return builderWorldType.getOrDefault(id, ExperienceWorldType.NORMAL);
  }

  void setBuilderWorldType(UUID id, ExperienceWorldType worldType) {
    builderWorldType.put(id, worldType == null ? ExperienceWorldType.NORMAL : worldType);
  }

  /** The full option set the builder currently has (map type + keep inventory). */
  ExperienceSetup builderSetupFor(UUID id) {
    return new ExperienceSetup(builderWorldTypeFor(id),
        builderKeepInventory.getOrDefault(id, ExperienceSetup.DEFAULT.keepInventory()),
        builderHardcore.getOrDefault(id, ExperienceSetup.DEFAULT.hardcore()));
  }

  void setBuilderKeepInventory(UUID id, boolean keepInventory) {
    builderKeepInventory.put(id, keepInventory);
  }

  void setBuilderHardcore(UUID id, boolean hardcore) {
    builderHardcore.put(id, hardcore);
  }

  String editingExperience(UUID id) {
    return editingExperience.get(id);
  }

  void setEditingExperience(UUID id, String expId) {
    if (expId == null) {
      editingExperience.remove(id);
    } else {
      editingExperience.put(id, expId);
    }
  }

  void clearEditingExperience(UUID id) {
    editingExperience.remove(id);
  }

  /** Resets the experience-builder selection for a fresh "Create Experience" / "Edit challenges" entry. */
  void resetBuilder(UUID id) {
    builderSelection.remove(id);
    builderWorldType.remove(id);
    builderKeepInventory.remove(id);
    builderHardcore.remove(id);
    editingExperience.remove(id);
  }

  // ----- LEFT-tap confirm gesture (Bedrock-safe) -----------------------------------------------

  /**
   * Whether this click is the deliberate TAP the confirm tiles ask for, and not some other way of
   * touching a slot.
   *
   * <p>A chest menu receives far more than clicks: a number key, {@code Q}, {@code Ctrl+Q}, {@code F},
   * a creative middle-drag and a double-click all arrive as an {@code InventoryClickEvent} on whatever
   * slot the cursor happens to be over, and {@code PaperMenuAdapter.mapClickType} folds every one of
   * them into {@link MenuContext.ClickType#OTHER}. Over an armed "⚠ Tap again to delete" that is a
   * keypress the owner never aimed at the tile — so only LEFT and RIGHT, the gesture the tile's own
   * text promises, can be the second half of the gesture.</p>
   */
  static boolean isTap(MenuContext ctx) {
    return ctx.clickType() == MenuContext.ClickType.LEFT || ctx.clickType() == MenuContext.ClickType.RIGHT;
  }

  /**
   * Builds a destructive-action button that confirms with a second tap. The first tap arms it (and
   * re-renders the owning screen so its label flips to "tap again"); a second tap on the SAME action
   * within {@link #CONFIRM_WINDOW_MS} runs {@code onConfirm}. There is no shortcut past it: a
   * shift-click used to confirm instantly, which in a chest is the reflex gesture for "move this item"
   * and fired Restore, Refresh and Delete off a tap the player never meant as one. This is also the one
   * gesture a Bedrock/mobile player can perform, since Geyser delivers every tap as a plain LEFT click.
   */
  MenuButton confirmButton(PlayerAdapter viewer, ItemKey icon, String model, String token,
      String idleName, List<String> idleLore, String armedName, List<String> armedLore,
      Consumer<MenuContext> onConfirm, Consumer<PlayerAdapter> reRender) {
    boolean armed = isArmed(viewer.uniqueId(), token);
    return MenuButton.of(icon, armed ? armedName : idleName, armed ? armedLore : idleLore, ctx -> {
      if (confirmStep(ctx, token)) {
        clearConfirm(ctx.player().uniqueId());
        onConfirm.accept(ctx);
      } else if (isTap(ctx)) {
        // Only a tap arms anything, so only a tap has something to report and something to redraw. A
        // number key or a drop that landed on the slot left the armed state exactly as it was: the
        // tile on screen already reads whichever face is true, and telling a player to "tap again" for
        // a verb they have not armed is how the tap they then make becomes the confirming one.
        ctx.player().sendActionBar("<red>Tap again to confirm.</red>");
        reRender.accept(ctx.player());
      }
    }).withModel(model);
  }

  /**
   * Arms (first tap → false) then confirms (second matching TAP within the window → true).
   *
   * <p>Both halves are needed every time, on Java as well as Bedrock: this is a chest, and there is no
   * click a player can make in one that runs a destructive verb outright. Anything that is not a
   * {@link #isTap tap} — a hotbar number key, a drop, a swap, a double-click's second event — does
   * nothing at all: it neither confirms an armed tile nor ARMS an idle one. Arming was the other half
   * of the same defect: {@code Q} pressed over an idle Refresh armed it and flipped the tile to "⧉ Tap
   * again to refresh", so the one deliberate tap the owner then made on it was the confirming tap of a
   * verb they never armed. It also means a stray event cannot keep re-stamping the window, which used
   * to hold an armed tile open for as long as the keypresses kept landing on the slot.</p>
   */
  boolean confirmStep(MenuContext ctx, String token) {
    UUID id = ctx.player().uniqueId();
    if (!isTap(ctx)) {
      return false;
    }
    if (isArmed(id, token)) {
      return true;
    }
    pendingConfirm.put(id, token);
    pendingConfirmAt.put(id, clock.getAsLong());
    return false;
  }

  boolean isArmed(UUID id, String token) {
    Long at = pendingConfirmAt.get(id);
    return token.equals(pendingConfirm.get(id)) && at != null
        && clock.getAsLong() - at <= CONFIRM_WINDOW_MS;
  }

  void clearConfirm(UUID id) {
    pendingConfirm.remove(id);
    pendingConfirmAt.remove(id);
  }
}
