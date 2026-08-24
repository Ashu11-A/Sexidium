package com.sexidium.core.game.experience;

import com.sexidium.core.game.ActiveMatch;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.experience.ExperienceManager.Experience;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.model.GameModeType;
// ChallengeCatalog and ExperienceGame are in this same package.
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.world.lobby.Lobby;
import com.sexidium.core.world.lobby.LobbyManager;
import com.sexidium.core.data.FriendService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrates player-owned experiences end to end: entering/resuming them in their persistent world,
 * the friends/party-auto + request-others join model, owner controls (visibility, rename, delete) and
 * the transient join-request inbox. Both the command layer and the lobby GUI route through here so the
 * authorization rules live in exactly one place.
 *
 * <p>Join model: the owner, the owner's friends, and the owner's online party members enter directly.
 * Anyone else gets a request delivered to the owner, who approves it ({@link #accept}) to let them in.
 * Visibility (public/private) only controls whether the map is listed to strangers, not whether
 * friends/party can join.</p>
 */
public final class ExperienceService {
  public enum EnterOutcome { ENTERED, STARTED, REQUEST_SENT, ALREADY_IN_MATCH, NOT_FOUND, OFFLINE, FAILED, LIMIT_REACHED, HOST_OFFLINE }

  /** Default cap on how many experience worlds a single player may own. */
  public static final int DEFAULT_MAX_PER_PLAYER = 10;

  /** A pending request from {@code requester} to enter {@code experienceId}. */
  public record JoinRequest(String experienceId, String experienceName, UUID requester, String requesterName, long at) {
  }

  private final ServerAdapter server;
  private final GameManager games;
  private final ExperienceManager experiences;
  private final LobbyManager lobbies;
  private final FriendService friends;
  // experienceId -> (requesterId -> request). Transient; requests do not survive a restart.
  private final Map<String, Map<UUID, JoinRequest>> pendingRequests = new LinkedHashMap<>();

  public ExperienceService(ServerAdapter server, GameManager games, ExperienceManager experiences,
      LobbyManager lobbies, FriendService friends) {
    this.server = server;
    this.games = games;
    this.commands = new ExperienceCommandRouter(server.logger(), this::applyCommand, "standalone");
    // The reason channel: the router's LocalExecutor answers in a boolean, and "your friend is still
    // standing in it" and "the disk is full" are completely different instructions to the owner. This
    // is where the code comes from when the handler that just ran recorded one.
    this.commands.attachReasons(this::reasonForLocalOp);
    this.experiences = experiences;
    this.lobbies = lobbies;
    this.friends = friends;
  }

  public ExperienceManager registry() {
    return experiences;
  }

  /**
   * How long ANY experience display name may be — typed by a player or generated here.
   *
   * <p>Not cosmetic, and it lives here rather than next to the command that types one because a
   * generated name reaches the same column by the same route: {@code WorldKey.of(displayName, id)}
   * builds the world key as {@code <sanitized name>_<8 hex>}, and {@code display_name} and
   * {@code world_key} are both {@code keyText()} — a {@code VARCHAR(191)} on MariaDB, the second with
   * a unique index. The name also GROWS afterwards without ever being asked again: a copy wears
   * {@code " (backup)"}, the world a restore displaces wears {@code " (before restore)"} and a
   * duplicate wears {@code " (copy)"}. 48 leaves room for the longest of those, the {@code _}, the id,
   * and a second round of the same, and still lands nowhere near 191.</p>
   */
  public static final int MAX_DISPLAY_NAME = 48;

  /**
   * Human-readable name for an experience composed of the given challenges, never longer than
   * {@link #MAX_DISPLAY_NAME} characters.
   *
   * <p>The cap is enforced HERE and not only where a name is typed, because this is the name every
   * experience is BORN with: 12 of the catalog's 27 twists joined with {@code " + "} already make 199
   * characters against a {@code VARCHAR(191)}, and the whole list makes 451. The INSERT then failed
   * with {@code Data too long}, {@code create} logged a warning and answered null, and the player who
   * ticked a dozen twists in the builder got no world and nothing on screen. This is also the first
   * name the GUI renamer offers, so capping it here caps that too.</p>
   *
   * <p>Truncation keeps WHOLE twist names — as many as fit, then {@code "+ N more"} for the rest, and
   * {@code "N twists"} when not even the first one leaves room for that tail. A blind cut is shorter
   * to write and unreadable in a list, which is the one place this string is ever used.</p>
   */
  public static String displayNameFor(List<String> challengeIds) {
    if (challengeIds == null || challengeIds.isEmpty()) {
      return "Experience";
    }
    List<String> names = new ArrayList<>();
    for (String id : challengeIds) {
      names.add(ChallengeCatalog.displayNameFor(id));
    }
    String joined = String.join(" + ", names);
    if (joined.length() <= MAX_DISPLAY_NAME) {
      return joined;
    }
    for (int kept = names.size() - 1; kept >= 1; kept--) {
      String shortened = String.join(" + ", names.subList(0, kept))
          + " + " + (names.size() - kept) + " more";
      if (shortened.length() <= MAX_DISPLAY_NAME) {
        return shortened;
      }
    }
    // Not even one name plus the tail fits, so nothing can be listed: say how many there are. A single
    // challenge whose own name is over the cap (only reachable through an unknown id, which the catalog
    // echoes back verbatim) is cut, and the cut never lands inside a surrogate pair — half of one is
    // not a character any column will accept.
    if (names.size() == 1) {
      String only = names.get(0);
      int end = MAX_DISPLAY_NAME;
      if (Character.isHighSurrogate(only.charAt(end - 1))) {
        end--;
      }
      return only.substring(0, end);
    }
    return names.size() + " twists";
  }

  /**
   * Creates a new owner-registered experience (persisted when a database is available) and starts it
   * in its persistent world with the given roster. Used by both {@code /sx start experience} and the
   * lobby experience-builder GUI.
   */
  public EnterOutcome createAndStart(PlayerAdapter owner, List<String> challengeIds, List<PlayerAdapter> roster) {
    return createAndStart(owner, challengeIds, ExperienceWorldType.NORMAL, roster);
  }

  /**
   * As {@link #createAndStart(PlayerAdapter, List, List)} but on a chosen map type: a normal world, a
   * Nether/End start, or one of the generated SkyBlock maps. A generated map contributes its own
   * challenge, and any OTHER map-generating challenge in the request is dropped — two of them would
   * build two maps into the same world, which is exactly what the type selector exists to prevent.
   */
  public EnterOutcome createAndStart(PlayerAdapter owner, List<String> challengeIds, ExperienceWorldType worldType,
      List<PlayerAdapter> roster) {
    return createAndStart(owner, challengeIds, ExperienceSetup.DEFAULT.withWorldType(worldType), roster);
  }

  /**
   * As above but on the full {@link ExperienceSetup} the owner chose in the builder — the map type and
   * whether deaths keep their inventory.
   */
  public EnterOutcome createAndStart(PlayerAdapter owner, List<String> challengeIds, ExperienceSetup setup,
      List<PlayerAdapter> roster) {
    if (owner == null || !owner.online()) {
      return EnterOutcome.OFFLINE;
    }
    if (games.matchOf(owner) != null) {
      return EnterOutcome.ALREADY_IN_MATCH;
    }
    ExperienceSetup options = setup == null ? ExperienceSetup.DEFAULT : setup;
    List<String> challenges = challengesFor(challengeIds, options.worldType());
    if (challenges.isEmpty()) {
      return EnterOutcome.FAILED;
    }
    List<PlayerAdapter> participants = (roster == null || roster.isEmpty()) ? List.of(owner) : roster;
    if (experiences.available()) {
      if (experiences.countByOwner(owner.uniqueId()) >= maxPerPlayer()) {
        return EnterOutcome.LIMIT_REACHED;
      }
      Experience experience = experiences.create(owner.uniqueId(), owner.name(), challenges,
          displayNameFor(challenges), Experience.MODE_EXPERIENCE, options, System.currentTimeMillis());
      if (experience != null) {
        if (!games.startExperience(experience.key(), participants, null,
            options.toModeArgs(experience.challenges()))) {
          return EnterOutcome.FAILED;
        }
        rememberEntry(experience, participants);
        return EnterOutcome.STARTED;
      }
    }
    // No database: a transient experience in a leased world (same challenges + options).
    return games.start(ExperienceGame.MODE_ID, participants, null, options.toModeArgs(challenges))
        ? EnterOutcome.STARTED : EnterOutcome.FAILED;
  }

  /**
   * The challenge set an experience of {@code worldType} actually runs: the requested twists with every
   * map-generating challenge stripped out, plus the one belonging to the chosen type. This is the single
   * gate that guarantees an experience never runs two world generators at once, no matter which entry
   * point (GUI, command, live edit) asked for it.
   */
  public static List<String> challengesFor(List<String> requested, ExperienceWorldType worldType) {
    ExperienceWorldType type = worldType == null ? ExperienceWorldType.NORMAL : worldType;
    List<String> challenges = new ArrayList<>();
    if (type.generatesMap()) {
      challenges.add(type.challengeId());
    }
    if (requested != null) {
      for (String id : requested) {
        if (id != null && !id.isBlank() && !ChallengeCatalog.isMapChallenge(id) && !challenges.contains(id)) {
          challenges.add(id);
        }
      }
    }
    return challenges;
  }

  /**
   * Creates a persistent Chaos world (random reshuffling twists) and starts it with the given roster.
   * Chaos worlds save inventory + position exactly like a normal experience — they are the same
   * persistent-world path with the {@code chaos} mode — appear in "My Experiences", and count against the
   * owner's per-player experience limit. With no database, falls back to a transient Chaos match.
   */
  public EnterOutcome createAndStartChaos(PlayerAdapter owner, List<PlayerAdapter> roster) {
    if (owner == null || !owner.online()) {
      return EnterOutcome.OFFLINE;
    }
    if (games.matchOf(owner) != null) {
      return EnterOutcome.ALREADY_IN_MATCH;
    }
    List<PlayerAdapter> participants = (roster == null || roster.isEmpty()) ? List.of(owner) : roster;
    if (experiences.available()) {
      if (experiences.countByOwner(owner.uniqueId()) >= maxPerPlayer()) {
        return EnterOutcome.LIMIT_REACHED;
      }
      Experience experience = experiences.create(
          owner.uniqueId(), owner.name(), List.of(), chaosDisplayName(owner), Experience.MODE_CHAOS, System.currentTimeMillis());
      if (experience != null) {
        if (!games.startExperience(experience.key(), com.sexidium.core.game.chaos.ChaosGame.MODE_ID,
            participants, null, List.of())) {
          return EnterOutcome.FAILED;
        }
        rememberEntry(experience, participants);
        return EnterOutcome.STARTED;
      }
    }
    // No database: a transient Chaos match in a leased world (not saved).
    return games.start(com.sexidium.core.game.chaos.ChaosGame.MODE_ID, participants, null, List.of())
        ? EnterOutcome.STARTED : EnterOutcome.FAILED;
  }

  private static String chaosDisplayName(PlayerAdapter owner) {
    String name = owner == null || owner.name() == null ? "" : owner.name();
    return name.isBlank() ? "Chaos" : name + "'s Chaos";
  }

  public List<Experience> own(UUID owner) {
    return experiences.byOwner(owner);
  }

  /**
   * Enters the single most recently created experience reachable by the player: the newest one owned by
   * the player OR any of their friends (the "last experience created by you or your friends" rule for the
   * lobby End portal). The player and friends are treated as one pool, so a friend's freshly-created world
   * wins over an older one of the player's own. Returns {@link EnterOutcome#NOT_FOUND} when neither the
   * player nor their friends own any experience.
   */
  public EnterOutcome enterLatest(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return EnterOutcome.OFFLINE;
    }
    if (!experiences.available()) {
      return EnterOutcome.NOT_FOUND;
    }
    List<UUID> owners = new ArrayList<>();
    owners.add(player.uniqueId());
    if (friends != null) {
      for (FriendService.Entry friend : friends.friends(player.uniqueId())) {
        owners.add(friend.playerId());
      }
    }
    Experience target = newestNonBackup(owners);
    return target == null ? EnterOutcome.NOT_FOUND : enter(player, target.id());
  }

  /**
   * The newest experience among these owners that is NOT a backup — what "take me to my latest world"
   * has to mean.
   *
   * <p>This used to be {@code latestByOwners}, which is {@code ORDER BY created_at DESC LIMIT 1} with no
   * opinion about backups, and a backup is a real row created NOW that wears the source's display name.
   * So the moment anybody took a backup, the lobby End portal started dropping the whole friend pool
   * into a frozen copy of the world — under its real name, with nothing on screen to say so. Nobody asks
   * a portal for a copy: a backup is only ever entered by being picked, deliberately, out of the owner's
   * own list.</p>
   *
   * <p>Package-private so the choice can be tested without standing up a world layer. Reads
   * {@code byOwners}, which is already newest-first, and takes the first row that is not a copy.</p>
   */
  Experience newestNonBackup(java.util.Collection<UUID> owners) {
    for (Experience candidate : experiences.byOwners(owners)) {
      if (candidate != null && !candidate.isBackup()) {
        return candidate;
      }
    }
    return null;
  }

  /**
   * Experiences a player can browse and join from the world browser: every experience owned by one of the
   * player's friends (joinable without being public) PLUS every public experience owned by anyone else.
   * Friends' worlds list first (newest), then public worlds; the viewer's own worlds are excluded (they
   * live in "My Experiences") and entries are de-duplicated by id.
   *
   * <p>A friend's BACKUPS are not listed. The friends half is "everything my friend owns", private rows
   * included, and a backup is a real row wearing the source's name, icon and lore — so it arrived in the
   * browser as a second, indistinguishable copy of a world a friend could then walk straight into. The
   * backup/source distinction exists only in the OWNER's own list, which is the only place it was ever
   * meant to be reachable from. The public half below is deliberately left alone: backups are inserted
   * private, so a backup only ever appears there because its owner went and published it on purpose.</p>
   */
  public List<Experience> browsable(UUID viewer) {
    Map<String, Experience> byId = new LinkedHashMap<>();
    if (friends != null && viewer != null) {
      List<UUID> friendOwners = new ArrayList<>();
      for (FriendService.Entry friend : friends.friends(viewer)) {
        friendOwners.add(friend.playerId());
      }
      for (Experience experience : experiences.byOwners(friendOwners)) {
        if (experience.isBackup()) {
          continue;
        }
        byId.put(experience.id(), experience);
      }
    }
    for (Experience experience : experiences.publicExperiencesExcluding(viewer)) {
      byId.putIfAbsent(experience.id(), experience);
    }
    return new ArrayList<>(byId.values());
  }

  /** Public experiences owned by other players (for the "other players' worlds" browser). */
  public List<Experience> publicOthers(UUID viewer) {
    return experiences.publicExperiencesExcluding(viewer);
  }

  /** Whether {@code owner} is on {@code viewer}'s friends list (used to label browser entries). */
  public boolean areFriends(UUID viewer, UUID owner) {
    return friends != null && viewer != null && owner != null && friends.areFriends(viewer, owner);
  }

  /**
   * Enters the experience: directly if the player is the owner / a friend / a party member of the
   * owner (resuming or recreating its persistent world as needed), otherwise files a join request to
   * the owner.
   */
  public EnterOutcome enter(PlayerAdapter player, String experienceId) {
    if (player == null || !player.online()) {
      return EnterOutcome.OFFLINE;
    }
    Experience experience = experiences.get(experienceId);
    if (experience == null) {
      return EnterOutcome.NOT_FOUND;
    }
    if (games.matchOf(player) != null) {
      return EnterOutcome.ALREADY_IN_MATCH;
    }
    if (canEnterDirectly(player.uniqueId(), experience)) {
      return enterOrStart(player, experience);
    }
    fileRequest(player, experience);
    return EnterOutcome.REQUEST_SENT;
  }

  /**
   * Enter the experience whose world is {@code worldKey} — the arrival half of a cross-node handoff.
   *
   * <p>The transfer records a world key, because that is what placement is about; this node has to
   * turn it back into an experience. It goes through the ordinary {@link #enter} path rather than
   * straight to the launcher so a player who was routed here is still checked against the same
   * ownership/friend/party/public rules they would face at home — a route is a delivery mechanism,
   * not an authorisation.</p>
   */
  public EnterOutcome enterByWorld(PlayerAdapter player, com.sexidium.core.world.WorldKey worldKey) {
    if (worldKey == null) {
      return EnterOutcome.NOT_FOUND;
    }
    // One index lookup. This used to have to be canonical-TOLERANT, scanning the whole table through
    // a caller-supplied normaliser, because a transferred player's handoff named the world the way the
    // placement layer did while the registry stored the way the launcher spelled it -- and answering
    // NOT_FOUND here sends the player straight back to the lobby, which is one half of an entry loop.
    Experience experience = experiences.byWorld(worldKey);
    return experience == null ? EnterOutcome.NOT_FOUND : enter(player, experience.id());
  }

  public boolean canEnterDirectly(UUID playerId, Experience experience) {
    if (playerId == null || experience == null) {
      return false;
    }
    if (playerId.equals(experience.owner())) {
      return true;
    }
    // A public experience is open to everyone — that is what "public" means to the owner who toggled it.
    if (experience.isPublic()) {
      return true;
    }
    if (friends != null && friends.areFriends(playerId, experience.owner())) {
      return true;
    }
    if (lobbies != null) {
      Lobby ownerLobby = lobbies.lobbyOf(experience.owner());
      if (ownerLobby != null && ownerLobby.isMember(playerId)) {
        return true;
      }
    }
    // The local lobby map only knows parties formed on THIS node. On a network the party was almost
    // certainly formed on the lobby node, and this check is running on a worker that never saw it,
    // so fall through to the shared directory before refusing.
    if (lobbyDirectory != null && lobbyDirectory.sameGroup(playerId, experience.owner())) {
      return true;
    }
    return false;
  }

  /**
   * Shared party membership, so a worker can answer "are these two in a party?" about a group it
   * never saw form. Null standalone, where the in-memory lobby map above is the whole truth.
   */
  private volatile com.sexidium.core.network.LobbyDirectory lobbyDirectory;

  public void setLobbyDirectory(com.sexidium.core.network.LobbyDirectory lobbyDirectory) {
    this.lobbyDirectory = lobbyDirectory;
  }

  private EnterOutcome enterOrStart(PlayerAdapter player, Experience experience) {
    ActiveMatch live = games.matchByWorldKey(experience.key());
    if (live != null) {
      // Read-on-open. A world that is already up composed its challenges and its setup when it
      // started, and the only thing that had ever changed them since was a bus message nobody
      // acknowledged — so a visitor could walk into a map whose owner had edited it hours ago and
      // play the old one. Reconciling here makes every entry re-read the row, which is what makes a
      // shared map show the owner's LATEST state rather than the state it happened to start with.
      reconcileLive(experience.id());
      // The world is already live and loaded — entry resolves the joiner's own validated spawn inside it.
      if (games.enterMatch(player, live) != GameManager.JoinResult.JOINED) {
        return EnterOutcome.FAILED;
      }
      rememberEntry(experience, List.of(player));
      return EnterOutcome.ENTERED;
    }
    // Host-inside validation (opt-in). Spinning up a non-owner into an experience whose owning host is
    // offline is allowed by default — these worlds are persistent and designed to be entered any time —
    // but a server may require the host to be present via worlds.experiences.require-owner-online. Under
    // that flag a friend/party/public visitor may enter a PRIVATE world (no public flag needed) only
    // while the creator is online AND currently inside that experience. Owners always enter their own.
    if (!player.uniqueId().equals(experience.owner()) && requireOwnerOnline() && !ownerInside(experience)) {
      return EnterOutcome.HOST_OFFLINE;
    }
    // Re-open the world running the right mode: a Chaos world re-rolls fresh twists (its challenge list is
    // empty) while a normal experience re-composes its saved challenges. Both restore inventory + position.
    boolean started = experience.isChaos()
        ? games.startExperience(experience.key(), com.sexidium.core.game.chaos.ChaosGame.MODE_ID, List.of(player), null, List.of())
        : games.startExperience(experience.key(), List.of(player), null,
            experience.setup().toModeArgs(experience.challenges()));
    // NB: the spectator lock for a LOST world is applied by the host (ExperienceGame.prepareEntry), not
    // here. Doing it from this side raced two things that overwrite the game mode — the host forcing
    // survival on entry, and the saved snapshot being restored right after — which is why a player
    // re-entering a dead world still arrived in survival.
    if (!started) {
      return EnterOutcome.FAILED;
    }
    rememberEntry(experience, List.of(player));
    return EnterOutcome.STARTED;
  }

  /**
   * Record, durably, that these players are now in this experience.
   *
   * <p>The pointer used to be written only on the way OUT, when a player's state is captured, which
   * means a player who walked in and whose node then died — a crash, a restart, a proxy kick — left no
   * trace at all of where they were, and came back to whichever overworld the next node happened to
   * boot with. Writing it on the way IN is what makes the pointer describe the present rather than the
   * last clean exit; the capture on exit still refreshes it, since a reset can move the world underneath
   * a player who never left.</p>
   */
  private void rememberEntry(Experience experience, List<PlayerAdapter> participants) {
    if (experience == null || participants == null || !experiences.available()) {
      return;
    }
    long now = System.currentTimeMillis();
    for (PlayerAdapter participant : participants) {
      if (participant != null) {
        experiences.rememberPlayerWorld(experience.id(), participant.uniqueId(), experience.key(), now);
      }
    }
  }

  /**
   * Whether a non-owner may enter an experience only while its owning host is online. Default {@code
   * false} to preserve the persistent, enter-anytime model; a server can opt in to the stricter rule.
   */
  private boolean requireOwnerOnline() {
    return server.configuration().getBoolean("worlds.experiences.require-owner-online", false);
  }

  /** True when the experience's creator is online AND currently inside that experience's live world. */
  private boolean ownerInside(Experience experience) {
    PlayerAdapter owner = server.player(experience.owner()).filter(PlayerAdapter::online).orElse(null);
    if (owner == null) {
      return false;
    }
    ActiveMatch live = games.matchByWorldKey(experience.key());
    return live != null && games.matchOf(owner) == live;
  }

  private void fileRequest(PlayerAdapter requester, Experience experience) {
    JoinRequest request = new JoinRequest(experience.id(), experience.displayName(),
        requester.uniqueId(), requester.name(), System.currentTimeMillis());
    pendingRequests.computeIfAbsent(experience.id(), ignored -> new LinkedHashMap<>())
        .put(requester.uniqueId(), request);
    server.player(experience.owner()).filter(PlayerAdapter::online).ifPresent(owner ->
        owner.sendMiniMessage("<yellow><b>" + escape(requester.name()) + "</b> wants to join your experience <white>"
            + escape(experience.displayName()) + "</white>. <green>/sx experience accept " + escape(requester.name())
            + "</green> <gray>to let them in.</gray>"));
  }

  /** Owner-side: list every pending request across all of the owner's experiences. */
  public List<JoinRequest> requestsFor(UUID owner) {
    List<JoinRequest> result = new ArrayList<>();
    for (Experience experience : experiences.byOwner(owner)) {
      Map<UUID, JoinRequest> byRequester = pendingRequests.get(experience.id());
      if (byRequester != null) {
        result.addAll(byRequester.values());
      }
    }
    return result;
  }

  /** Owner approves a requester: removes the request and enters the requester into the experience. */
  public EnterOutcome accept(UUID owner, UUID requesterId) {
    for (Experience experience : experiences.byOwner(owner)) {
      Map<UUID, JoinRequest> byRequester = pendingRequests.get(experience.id());
      if (byRequester != null && byRequester.remove(requesterId) != null) {
        PlayerAdapter requester = server.player(requesterId).filter(PlayerAdapter::online).orElse(null);
        if (requester == null) {
          return EnterOutcome.OFFLINE;
        }
        if (games.matchOf(requester) != null) {
          return EnterOutcome.ALREADY_IN_MATCH;
        }
        return enterOrStart(requester, experience);
      }
    }
    return EnterOutcome.NOT_FOUND;
  }

  public boolean deny(UUID owner, UUID requesterId) {
    for (Experience experience : experiences.byOwner(owner)) {
      Map<UUID, JoinRequest> byRequester = pendingRequests.get(experience.id());
      if (byRequester != null && byRequester.remove(requesterId) != null) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether {@code owner} may still CHANGE this experience — as opposed to look at it, rename it or
   * delete it.
   *
   * <p>A hardcore world that has been lost is frozen: its settings are the record of a run that is over,
   * and being able to edit them afterwards would make hardcore mean nothing. Enforced here rather than
   * only in the GUI, because a menu that hides a button is a suggestion — a command, a stale open menu or
   * another client can still reach the service.</p>
   */
  private boolean mutable(String experienceId, UUID owner) {
    Experience experience = experiences.get(experienceId);
    return experience != null && experience.owner().equals(owner) && !experience.isLost();
  }

  public boolean setVisibility(UUID owner, String experienceId, boolean isPublic) {
    if (!experiences.isOwner(experienceId, owner) || !mutable(experienceId, owner)) {
      return false;
    }
    experiences.setVisibility(experienceId, isPublic, System.currentTimeMillis());
    // Visibility decides who may even SEE this map, so a browser open on another node has to be able
    // to learn it changed rather than keep offering (or keep hiding) it until the viewer reopens.
    announceUpdated(experienceId, "visibility");
    return true;
  }

  /**
   * Replaces which challenges an experience runs (owner editing it in the GUI). Any live match in the
   * world is ended so it restarts cleanly with the new challenge set the next time it is entered.
   */
  public boolean updateChallenges(UUID owner, String experienceId, List<String> challengeIds) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner) || experience.isLost()
        || challengeIds == null || challengeIds.isEmpty()) {
      return false;
    }
    experiences.updateChallenges(experienceId, challengeIds, System.currentTimeMillis());
    announceUpdated(experienceId, "challenges");
    // Routed: "end the match so it restarts clean" is a no-op on a node that is not running it, and
    // the node an owner reaches this from is the lobby, which never is.
    return commands.execute(experienceId, experience.key(),
        ExperienceCommandRouter.Op.END_MATCH, java.util.Map.of()).ok();
  }

  /**
   * Like {@link #updateChallenges} but applies the change in REAL TIME to a running experience instead of
   * ending the match: persists the new set, then diffs it against the live challenge set and
   * adds/removes each changed challenge on the live host (see {@code ExperienceGame.addChallengeLive} /
   * {@code removeChallengeLive}). Falls back to a pure persist (next entry applies it) when no match is live.
   */
  public boolean updateChallengesLive(UUID owner, String experienceId, List<String> challengeIds) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner) || experience.isLost()
        || challengeIds == null || challengeIds.isEmpty()) {
      return false;
    }
    // The world's own map generator is never editable — the terrain already exists — so it is re-pinned
    // from the stored type and any other map challenge the request smuggled in is dropped.
    challengeIds = challengesFor(challengeIds, experience.type());
    experiences.updateChallenges(experienceId, challengeIds, System.currentTimeMillis());
    // Announced as well as routed. The routed command is the fast path and is not acknowledged; the
    // announcement is what makes a node that missed it re-read the row instead of running the old set
    // for as long as the world stays up.
    announceUpdated(experienceId, "challenges");
    return commands.execute(experienceId, experience.key(),
        ExperienceCommandRouter.Op.SET_CHALLENGES,
        java.util.Map.of("challenges", String.join(",", challengeIds))).ok();
  }

  // ----- keeping a SHARED map current ------------------------------------------------------------
  //
  // The registry row is the source of truth and always was: every read here is a fresh query and
  // every owner action is an UPDATE, so a rename or a visibility toggle is visible to every node the
  // moment it commits. What was NOT current was everything downstream of the row — the live world,
  // which read it once at start, and an open menu, which read it once at open. Both are fixed the
  // same way: the row carries a version (`updated_at`), and a change announces itself on the bus so
  // the node holding the world and the nodes holding the viewers can go and re-read it.

  /** Where "this experience changed" is announced. Null until wired; standalone still gets a bus. */
  private volatile com.sexidium.core.network.NetworkBus updateChannel;
  private volatile String nodeId = "standalone";
  /** What THIS node does about a change, whoever made it. Set alongside the channel. */
  private volatile java.util.function.Consumer<String> updateWatcher = experienceId -> { };

  /**
   * Wires the cross-node "an experience row changed" notice.
   *
   * <p>{@code watcher} is called on this node for every change — the ones made here as well as the
   * ones a peer announces. That is deliberate: the bus never delivers a node its own publications, and
   * the node an owner edits from is the LOBBY, which is exactly where the other viewers' menus are.</p>
   */
  public void setUpdateChannel(com.sexidium.core.network.NetworkBus bus, String nodeId,
      java.util.function.Consumer<String> watcher) {
    this.updateChannel = bus;
    if (nodeId != null && !nodeId.isBlank()) {
      this.nodeId = nodeId;
    }
    if (watcher != null) {
      this.updateWatcher = watcher;
    }
  }

  /**
   * Announce a change to an experience: act on it here, then tell the fleet.
   *
   * <p>{@code field} is for the operator reading the message column, not for the receiver — every
   * receiver re-reads the row, because a payload that carried the new value would be a second copy of
   * the truth and would go stale the moment two changes crossed.</p>
   */
  private void announceUpdated(String experienceId, String field) {
    if (experienceId == null) {
      return;
    }
    try {
      updateWatcher.accept(experienceId);
    } catch (RuntimeException failed) {
      server.logger().warning("Failed to apply a local experience update for " + experienceId
          + ": " + failed.getMessage());
    }
    com.sexidium.core.network.NetworkBus channel = updateChannel;
    if (channel != null) {
      channel.publish(com.sexidium.core.network.NetworkBus.Topics.EXPERIENCE_UPDATED, experienceId,
          "field=" + field + ";by=" + nodeId);
    }
  }

  /** A peer's announcement. Ignores our own echo, which standalone's in-process bus does deliver. */
  public void onExperienceUpdated(String experienceId, String payload) {
    if (experienceId == null) {
      return;
    }
    if (payload != null) {
      for (String pair : payload.split(";")) {
        if (pair.startsWith("by=") && nodeId.equals(pair.substring(3))) {
          return; // already applied where it was made
        }
      }
    }
    updateWatcher.accept(experienceId);
  }

  /**
   * Make the live world on THIS node agree with the registry row.
   *
   * <p>Cheap and idempotent, so it can sit on the entry path: one primary-key read of the row's
   * {@code updated_at}, and nothing at all when it has not moved since the last reconcile. Returns
   * whether anything was applied.</p>
   *
   * <p>Two worlds are deliberately left alone. A CHAOS world has no stored challenge set — its twists
   * are re-rolled by the running game — so applying the empty list would strip it. And a LOST hardcore
   * world is frozen: its settings are the record of a run that is over.</p>
   */
  public boolean reconcileLive(String experienceId) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || experience.isChaos() || experience.isLost()) {
      return false;
    }
    ActiveMatch live = games.matchByWorldKey(experience.key());
    if (live == null || !(live.game() instanceof ExperienceGame game)) {
      return false; // nothing running here; the next start composes the stored set
    }
    long stamp = experiences.updatedAt(experienceId);
    if (stamp > 0 && stamp == game.appliedRegistryStamp()) {
      return false;
    }
    game.markRegistryApplied(stamp);
    applyChallengesLive(experienceId, experience.challenges());
    game.setKeepInventoryLive(experience.keepInventory());
    game.setHardcoreLive(experience.hardcore());
    return true;
  }

  /** The live half of {@link #updateChallengesLive}, run on whichever node holds the world. */
  private boolean applyChallengesLive(String experienceId, List<String> challengeIds) {
    ActiveMatch live = liveMatchOf(experienceId);
    if (live == null || !(live.game() instanceof ExperienceGame game)) {
      return true; // nothing running here; the next entry composes the stored set
    }
    java.util.Set<String> target = new java.util.LinkedHashSet<>();
    for (String id : challengeIds) {
      if (id != null && !id.isBlank()) {
        target.add(id.trim().toLowerCase(java.util.Locale.ROOT));
      }
    }
    java.util.List<String> current = new java.util.ArrayList<>(game.activeChallengeIds());
    for (String id : current) {
      if (!target.contains(id)) {
        game.removeChallengeLive(id);
      }
    }
    for (String id : target) {
      if (!current.contains(id)) {
        game.addChallengeLive(id);
      }
    }
    return true;
  }

  /**
   * Changes which dimension an experience drops its players into. Only the plain start dimensions are
   * changeable: a generated map (SkyBlock and friends) or a terrain preset (Superflat, Large Biomes,
   * Amplified) is fixed at creation because the world has already been generated that way — and an
   * experience that RUNS one cannot be re-pointed either.
   * Returns false when the change is not allowed, so the GUI can explain why.
   */
  public boolean updateWorldType(UUID owner, String experienceId, ExperienceWorldType worldType) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner) || experience.isLost() || worldType == null) {
      return false;
    }
    // Anything that decides GENERATION — a generated map or a terrain preset — is baked into the world
    // the moment it exists, so it can be neither switched to nor away from afterwards.
    if (worldType.fixedAtCreation() || experience.type().fixedAtCreation()) {
      return false;
    }
    experiences.updateWorldType(experienceId, worldType, System.currentTimeMillis());
    announceUpdated(experienceId, "worldtype");
    return true;
  }

  /**
   * Turns the keep-inventory rule on or off for an experience. Applied LIVE when a match is running (the
   * world gamerule is pushed to every dimension immediately), so it takes effect on the next death rather
   * than the next restart.
   */
  public boolean setKeepInventory(UUID owner, String experienceId, boolean keepInventory) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner) || experience.isLost()) {
      return false;
    }
    experiences.updateKeepInventory(experienceId, keepInventory, System.currentTimeMillis());
    announceUpdated(experienceId, "keepinventory");
    return commands.execute(experienceId, experience.key(),
        ExperienceCommandRouter.Op.SET_KEEP_INVENTORY,
        java.util.Map.of("value", Boolean.toString(keepInventory))).ok();
  }

  /**
   * Turns HARDCORE on or off for an experience, applied LIVE when a match is running.
   *
   * <p>Refused once the world has already been lost: hardcore's whole weight is that the death cannot be
   * taken back, so switching it off afterwards would make it mean nothing. Turning it OFF is also refused
   * while a challenge that exists because of the stakes is selected (Death Resets) — its world was
   * generated hardcore and its death handling assumes it still is, so the toggle reads as locked on.</p>
   */
  public boolean setHardcore(UUID owner, String experienceId, boolean hardcore) {
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner) || experience.isLost()) {
      return false;
    }
    if (!hardcore && ChallengeCatalog.anyRequiresHardcore(experience.challenges())) {
      return false;
    }
    experiences.updateHardcore(experienceId, hardcore, System.currentTimeMillis());
    announceUpdated(experienceId, "hardcore");
    return commands.execute(experienceId, experience.key(),
        ExperienceCommandRouter.Op.SET_HARDCORE,
        java.util.Map.of("value", Boolean.toString(hardcore))).ok();
  }

  public boolean rename(UUID owner, String experienceId, String displayName) {
    if (!experiences.isOwner(experienceId, owner) || displayName == null || displayName.isBlank()) {
      return false;
    }
    experiences.rename(experienceId, displayName, System.currentTimeMillis());
    // The name is what every other player sees this map AS, in a list they may already have open.
    announceUpdated(experienceId, "name");
    return true;
  }

  /** How a delete ended, from the owner's point of view. */
  public enum DeleteOutcome {
    /** The world is gone: folder removed and every row that named it forgotten. */
    DELETED,
    /** Not this player's experience (or there is no such experience). */
    NOT_OWNER,
    /**
     * Written down and sent, but the node holding the world has not answered yet — it is restarting,
     * or busy. The request stands and that node runs it the moment it reads its own table.
     */
    QUEUED,
    /** The node holding the world tried and refused: players still inside, or an unload that failed. */
    REFUSED
  }

  /**
   * Permanently deletes an experience the player owns: ends any live match in its world, deletes the
   * persistent world folder and forgets every row that named it. This is the ONLY path that removes
   * an experience map (owner action via GUI/command, or a ban).
   *
   * <p>{@code onOutcome} is called exactly once, on the node the owner is on — immediately when the
   * answer is already known, and otherwise from the router's acknowledgement tick once the node
   * holding the world has answered (or has stayed silent long enough to be reported as queued). It
   * takes an outcome and not a boolean because the boolean was thrown away by both callers, and
   * "refused" and "you do not own it" were reported as the same thing.</p>
   */
  public void delete(UUID owner, String experienceId,
      java.util.function.Consumer<DeleteOutcome> onOutcome) {
    java.util.function.Consumer<DeleteOutcome> tell =
        onOutcome == null ? outcome -> { } : onOutcome;
    Experience experience = experiences.get(experienceId);
    if (experience == null || !experience.owner().equals(owner)) {
      tell.accept(DeleteOutcome.NOT_OWNER);
      return;
    }
    pendingRequests.remove(experienceId);
    // F-A2. This used to end only the LOCAL match and then delete the folder, whose only protection
    // was a local loaded-world lookup -- which on the lobby answers empty about a world worker-2 is
    // running. It deleted a shared folder out from under a live world. The whole operation now runs
    // on the node holding it, and that node does all three steps in order.
    //
    // The world key travels WITH the request: the node that runs it may find the registry row
    // already gone (a retry, a replay, a delete that half-happened) and still has to be able to
    // remove the folder that row used to name.
    commands.execute(experienceId, experience.key(),
        ExperienceCommandRouter.Op.DELETE,
        java.util.Map.of("key", experience.key().key()),
        result -> {
          if (result.applied()) {
            tell.accept(DeleteOutcome.DELETED);
          } else if (result.unrouted()) {
            // "Nothing records where this world lives" is NOT "there is no folder anywhere". That
            // premise -- a world only ever gets a placement row by being opened or created -- is false
            // for every backup and every duplicate: `copyInto` builds the folders through
            // WorldLeaseService.copyExperienceWorld, which never touches WorldPlacementGate, and then
            // writes only the `experiences` row. No `world_placements` row exists, and with
            // `network.shared-world-storage` on (the default) PlacementReconciler skips the adopt that
            // would have made one. So dropping the rows here reported DELETED over several hundred
            // megabytes that nothing names any more and no sweep collects -- permanently, because
            // every collector this codebase has needs a row to start from.
            //
            // The lobby cannot tell the two cases apart: it has no folder to look at that means
            // anything (on the live deployment the experiences tree is a symlink shared by every node,
            // so it is present from everywhere for everything). REFUSED is the answer that keeps the
            // world recoverable -- while it still has a name the owner can try again and the node that
            // opens it can delete it for real -- and the severe line is what an operator needs to see
            // when a row genuinely has no folder and should simply be dropped.
            server.logger().severe("Refusing to forget experience " + experienceId
                + " ('" + experience.key().key() + "'): no node is recorded as holding it, and a"
                + " backup or duplicate has folders on the shared tree without ever having a placement"
                + " row. Dropping the rows from here would orphan them for good. Enter the world once"
                + " so it is placed, then delete it.");
            // The way out was in the severe line and nowhere else, and the owner does not read the
            // console: the generic refusal tells them somebody may be inside and to try again in a
            // moment, and here BOTH halves are false. Nobody is inside a world no node is even
            // holding, and this state does not clear on its own -- it clears when a placement row
            // exists, which is what entering the world once writes (and `release` keeps, IDLE rather
            // than deleted, so one entry makes it deletable for good).
            explainRefusal(owner, MessageKey.EXPERIENCE_DELETE_UNPLACED);
            tell.accept(DeleteOutcome.REFUSED);
          } else if (result.routed()) {
            tell.accept(DeleteOutcome.QUEUED);
          } else {
            // The other refusal: a node HAS the world and would not give it up. Here "somebody may
            // still be inside" and "try again in a moment" are the right advice, which is exactly why
            // the two cannot share one sentence.
            explainRefusal(owner, MessageKey.EXPERIENCE_DELETE_BUSY);
            tell.accept(DeleteOutcome.REFUSED);
          }
        });
  }

  /**
   * Tells the owner WHY a delete was refused, when the reason is one they can act on.
   *
   * <p>Sent from here rather than chosen by the caller because {@link DeleteOutcome} has exactly one
   * word for "refused" and both screens that report it switch over the enum exhaustively — a fifth
   * constant is a change to {@code ExperienceMenu}, which is not this fix. So the outcome stays the
   * flow-control answer ("nothing was removed"), {@code experience.delete.refused} says only that
   * much, and the sentence that differs between the two refusals comes from the one place that knows
   * which one happened. Silent when the owner is not on this node: the ack can arrive long after they
   * have gone, and there is nobody to tell.</p>
   */
  private void explainRefusal(UUID owner, MessageKey reason) {
    if (owner == null) {
      return;
    }
    server.player(owner).filter(PlayerAdapter::online).ifPresent(player ->
        server.messages().send(player, LocalizedText.of(reason)));
  }

  /**
   * The whole delete, on the node that holds the world: end the match, remove the folder, forget the
   * row. In that order, and the registry row goes LAST — a row deleted before the folder leaves a
   * folder nothing names, which is exactly the orphan the janitor then has to guess about.
   *
   * <p>Idempotent, because a request can be delivered twice and re-run after a restart: a world that
   * is already gone deletes cleanly (the world layer answers true for a folder that is not there) and
   * a registry row that is already gone is not an error. {@code worldKey} is the key the requester
   * recorded, used when the registry row has already been forgotten.</p>
   */
  private boolean applyDelete(String experienceId, String worldKey) {
    ActiveMatch live = liveMatchOf(experienceId);
    if (live != null) {
      games.endMatch(live, null);
    }
    Experience experience = experiences.get(experienceId);
    com.sexidium.core.world.WorldKey key = experience != null ? experience.key()
        : (worldKey == null || worldKey.isBlank()
            ? null : com.sexidium.core.world.WorldKey.parse(worldKey));
    if (key != null && !server.worlds().deletePersistent(key)) {
      // A recusa do mundo era DESCARTADA e a linha do registro sumia mesmo assim: o dono
      // via "apagado", e o que sobrava era uma pasta de mundo mais uma linha de placement
      // que ninguém mais nomeia -- órfãs para sempre, porque `forget()` só larga linha sem
      // pasta e a pasta está lá. Foi assim que a árvore compartilhada juntou 24 mundos
      // órfãos (~223 MB) sem um único erro visível ao jogador.
      //
      // Guardar a linha é o que mantém o mundo RECUPERÁVEL: enquanto ele tem nome, o dono
      // pode tentar de novo e o nó certo pode apagá-lo. E devolver false faz o jogador
      // saber que não aconteceu, em vez de descobrir meses depois pelo disco.
      server.logger().warning("Refusing to forget experience " + experienceId
          + ": its world could not be deleted here, and a row dropped now would orphan the folder.");
      return false;
    }
    pendingRequests.remove(experienceId);
    return forgetRows(experienceId);
  }

  /**
   * Everything that NAMES a deleted experience, once its world is gone.
   *
   * <p>The registry row was the only one dropped, and {@code experience_players} kept a durable
   * pointer into a world that no longer exists for every OTHER member — read back on their next join.
   * True when nothing names it any more, which for a row that was already gone is still true: this
   * runs again on a replayed request.</p>
   */
  private boolean forgetRows(String experienceId) {
    pendingRequests.remove(experienceId);
    ExperienceManager.Removal removal = experiences.remove(experienceId);
    if (removal == ExperienceManager.Removal.UNKNOWN) {
      // "The database could not say" is not "the row is gone", and this used to read it as gone: a
      // failed DELETE answers false, the confirming get() answers null on the very same failure, and
      // `dropped || get(id) == null` turned two failures into a success. By then the FOLDER is
      // already deleted, so the outcome was a map that still lists for everyone, still cannot be
      // entered, and whose owner was told it was deleted.
      server.logger().warning("Deleted the world of experience " + experienceId
          + " but could not confirm its registry row was dropped. Reporting it as NOT deleted so the"
          + " owner tries again rather than finding out from the list.");
      return false;
    }
    experiences.forgetAllPlayers(experienceId);
    // Every copy taken of this world becomes a world in its own right, because that is what it now is:
    // the only surviving record of that run. Without this it keeps a dead `backup_of`, and countByOwner
    // excludes anything carrying one -- so it counts against NOTHING, for ever. Create, back up three
    // times, delete the source, repeat: unbounded worlds at a few hundred megabytes an iteration, with
    // the per-player cap looking perfectly healthy the whole time.
    //
    // The answer is READ, because the failure it hides is that exact leak: -1 means the UPDATE did not
    // run, so every copy of this now-deleted world still carries a dead `backup_of`. It is retried once
    // (the statement is idempotent) and then LOGGED -- it is not the return value.
    //
    // It used to answer false, and by this line that is a lie the owner cannot act on: the folder is
    // gone (applyDelete), the registry row is gone (remove, just above) and the players who named it
    // have been forgotten. "Not deleted" therefore reported REFUSED over a world that IS deleted, and
    // the advice it was reported for -- try again -- cannot even be followed: `delete` reads the row
    // first and answers NOT_OWNER for a row that no longer exists, and the request row this ran from is
    // terminal FAILED, so the drain never re-runs the promotion either. The leak this guards persisted
    // regardless; all the false bought was a wrong message.
    if (experiences.promoteOrphanBackups(experienceId) < 0
        && experiences.promoteOrphanBackups(experienceId) < 0) {
      server.logger().severe("Deleted the world of experience " + experienceId
          + " but could not promote the copies taken of it. They still name a source that is gone, so"
          + " they count against nothing and the owner's world allowance is understated. Clear it by"
          + " hand: UPDATE experiences SET backup_of=NULL WHERE backup_of='" + experienceId + "'.");
    }
    // Announced from the node that actually did it, so a browser open on the lobby stops offering a
    // map whose world no longer exists — clicking one of those is a guaranteed NOT_FOUND.
    announceUpdated(experienceId, "deleted");
    return true;
  }

  // ----- backups ----------------------------------------------------------------------------------
  //
  // A backup is a REAL experience: its own row, its own world_key, its own three dimension folders,
  // and a `backup_of` pointing at what it was copied from. That is what makes "restore" nothing but
  // "enter the backup" — there is no second engine putting bytes back underneath a running world.
  //
  // This class does not copy anything. It owns the three things the copy engine cannot: who is allowed
  // to ask, where the request has to run, and what the owner is told about how it ended.

  /** Default cap on how many backups ONE experience may keep. Backups are never auto-deleted. */
  public static final int DEFAULT_MAX_BACKUPS_PER_EXPERIENCE = 3;

  /** The copy engine, attached by the core. Null on a node that cannot open experience worlds. */
  private volatile ExperienceBackup backupEngine;

  /**
   * Wires the engine that can actually read the folder.
   *
   * <p>Deliberately an attach rather than a constructor argument: only a node with the world layer
   * behind it can copy a world, and this service is constructed on every node.</p>
   */
  public void attachBackup(ExperienceBackup backup) {
    this.backupEngine = backup;
  }

  /**
   * Where a backup that ran on THIS node writes what actually happened, keyed by the nonce that
   * travelled with the request.
   *
   * <p>{@link ExperienceCommandRouter.LocalExecutor} answers in a boolean, which cannot tell BUSY from
   * LIMIT_REACHED from a disk that filled up — and those are exactly the answers the owner can act on.
   * The boolean still decides whether the request row is DONE or FAILED; this carries the reason
   * alongside it, for the case where the node that ran it is the node the owner is standing on.</p>
   *
   * <p>So a slot that is still EMPTY when the answer comes back means the request was run by a PEER,
   * and a peer's boolean can say only "accepted" or "refused". Accepted becomes {@link
   * ExperienceBackup.Outcome#QUEUED} — it means the copy was STARTED, never that it finished, because
   * the copy engine hands the folder to a staging executor and returns. Refused degrades to {@link
   * ExperienceBackup.Outcome#FAILED}: over this channel a peer that was BUSY, a peer that was at its
   * backup limit and a peer whose disk filled up are one and the same word. Carrying the reason across
   * the wire is a change to the result format, and that is a follow-up, not this fix.</p>
   */
  private final Map<String, java.util.concurrent.atomic.AtomicReference<ExperienceBackup.Outcome>>
      localBackupOutcomes = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Copies an experience the player owns into a new experience of their own.
   *
   * <p>Same shape as {@link #delete}: the ownership question is answered here, the work is routed to
   * the node that holds the folder, and {@code onOutcome} is called exactly once — immediately when
   * the answer is already known, otherwise from the router's acknowledgement tick.</p>
   *
   * <p>{@link ExperienceBackup.Outcome#QUEUED} covers "nobody has answered yet", "the copy is under way
   * here" and "a peer accepted it": in none of those is there a finished world to promise, and in all of
   * them the owner's list gains the row on its own when there is (see {@link #announceUpdated}). Only a
   * copy this node watched FINISH is reported as {@link ExperienceBackup.Outcome#CREATED} — the ack from
   * a peer says the work started, and saying "Backup created." over a copy still being written is the
   * normal path on a network, where the owner always clicks from the lobby.</p>
   */
  public void backup(UUID requester, String experienceId,
      java.util.function.Consumer<ExperienceBackup.Outcome> onOutcome) {
    java.util.function.Consumer<ExperienceBackup.Outcome> tell =
        onOutcome == null ? outcome -> { } : onOutcome;
    Experience experience = experiences.get(experienceId);
    if (experience == null || requester == null || !requester.equals(experience.owner())) {
      tell.accept(ExperienceBackup.Outcome.NOT_OWNER);
      return;
    }
    // The cap, and the "a backup is never copied itself" rule, asked HERE as well as in the engine.
    // Not belt-and-braces: this is the node the owner is standing on, so it is the only one that can
    // answer without a round trip to a worker that may be restarting — and refusing without routing is
    // what stops a full-up experience from writing a request row per click.
    if (!backupAllowedFor(experience)) {
      tell.accept(ExperienceBackup.Outcome.LIMIT_REACHED);
      return;
    }
    // The world key travels with the request for the same reason a delete's does: the node that runs
    // it may be reading the row back long after the message that carried it.
    //
    // `appliedMeans` is null: a peer's yes means the copy STARTED, never that it finished, because the
    // engine hands the folder to a staging executor and returns long before the bytes land. Reporting
    // CREATED there promised a world that might never exist, and said nothing at all when the copy
    // then failed.
    route(experienceId, experience.key(), ExperienceCommandRouter.Op.BACKUP, requester,
        java.util.Map.of("key", experience.key().key()), null, tell);
  }

  /**
   * Whether this experience may be copied again at all: a backup is never copied itself, a LOST
   * hardcore world has nothing left to carry forward, and an experience keeps at most
   * {@link #maxBackupsPerExperience()} copies.
   *
   * <p>The engine asks the same two questions again. That is not belt-and-braces: this is the node the
   * owner is standing on, so it is the only one that can answer without a round trip to a worker that
   * may be restarting, and refusing without routing is what stops a full-up experience from writing a
   * request row per click.</p>
   */
  private boolean backupAllowedFor(Experience experience) {
    if (experience.isBackup() || experience.dead()) {
      return false;
    }
    int cap = maxBackupsPerExperience();
    return cap > 0
        && (!experiences.available() || experiences.countBackupsOf(experience.id()) < cap);
  }

  /**
   * Makes a backup the live world of the experience it was taken from, swapping the world it replaces
   * into its place as a copy.
   *
   * <p>Same shape as {@link #backup}: ownership is answered here, the surgery is routed to the node
   * that holds the folders, and {@code onOutcome} is called exactly once.</p>
   *
   * <p>Unlike a backup, a peer's {@code applied} genuinely means DONE. The whole restore is four
   * statements in one transaction — there is no staged folder still being written when the handler
   * returns — so {@link ExperienceBackup.Outcome#RESTORED} over a peer's yes is not a promise about
   * work still in flight.</p>
   */
  public void restore(UUID requester, String backupId,
      java.util.function.Consumer<ExperienceBackup.Outcome> onOutcome) {
    java.util.function.Consumer<ExperienceBackup.Outcome> tell =
        onOutcome == null ? outcome -> { } : onOutcome;
    Experience backup = experiences.get(backupId);
    if (backup == null || requester == null || !requester.equals(backup.owner())) {
      tell.accept(ExperienceBackup.Outcome.NOT_OWNER);
      return;
    }
    if (!backup.isBackup()) {
      tell.accept(ExperienceBackup.Outcome.GONE);
      return;
    }
    Experience source = experiences.get(backup.backupOf());
    if (source == null) {
      // An orphan copy has nothing to restore OVER. Saying so is not a leak: the requester already
      // owns the backup, and it is the only answer they can act on (enter it, or duplicate it).
      tell.accept(ExperienceBackup.Outcome.GONE);
      return;
    }
    // Routed on the SOURCE's world: that is the live one, and its placement row is what says where the
    // shared tree is mounted from this experience's point of view. Both folders are then proved
    // present by the executor before a single row moves.
    route(backupId, source.key(), ExperienceCommandRouter.Op.RESTORE, requester,
        java.util.Map.of("key", source.key().key(), "backup", backupId),
        ExperienceBackup.Outcome.RESTORED, tell);
  }

  /**
   * Takes a backup again from its source, replacing the folder underneath it and keeping its id.
   *
   * <p>A full re-copy. Like {@link #backup}, a peer's yes means the copy STARTED, so it is reported
   * {@link ExperienceBackup.Outcome#QUEUED} rather than done.</p>
   */
  public void refresh(UUID requester, String backupId,
      java.util.function.Consumer<ExperienceBackup.Outcome> onOutcome) {
    java.util.function.Consumer<ExperienceBackup.Outcome> tell =
        onOutcome == null ? outcome -> { } : onOutcome;
    Experience backup = experiences.get(backupId);
    if (backup == null || requester == null || !requester.equals(backup.owner())) {
      tell.accept(ExperienceBackup.Outcome.NOT_OWNER);
      return;
    }
    if (!backup.isBackup()) {
      tell.accept(ExperienceBackup.Outcome.GONE);
      return;
    }
    Experience source = experiences.get(backup.backupOf());
    if (source == null) {
      tell.accept(ExperienceBackup.Outcome.GONE);
      return;
    }
    route(backupId, source.key(), ExperienceCommandRouter.Op.REFRESH, requester,
        java.util.Map.of("key", source.key().key(), "backup", backupId),
        null, tell);
  }

  /**
   * Copies an experience (usually a backup) into a brand-new playable world of the owner's own.
   *
   * <p>This is the verb that SPENDS a slot of {@link #maxPerPlayer()}, because the result is a world
   * and not a copy: {@code backup_of} is null and deleting the original changes nothing about it. The
   * cap is checked here as well as in the engine, so a player at their limit never wakes a worker.</p>
   */
  public void duplicate(UUID requester, String backupId,
      java.util.function.Consumer<ExperienceBackup.Outcome> onOutcome) {
    java.util.function.Consumer<ExperienceBackup.Outcome> tell =
        onOutcome == null ? outcome -> { } : onOutcome;
    Experience source = experiences.get(backupId);
    if (source == null || requester == null || !requester.equals(source.owner())) {
      tell.accept(ExperienceBackup.Outcome.NOT_OWNER);
      return;
    }
    if (experiences.available() && experiences.countByOwner(source.owner()) >= maxPerPlayer()) {
      tell.accept(ExperienceBackup.Outcome.LIMIT_REACHED);
      return;
    }
    route(backupId, source.key(), ExperienceCommandRouter.Op.DUPLICATE, requester,
        java.util.Map.of("key", source.key().key()), null, tell);
  }

  /**
   * The shared routing half of every copy verb: nonce in, one answer out.
   *
   * <p>{@code appliedMeans} is what a PEER's plain "yes" is worth for this op. For a restore that is
   * {@link ExperienceBackup.Outcome#RESTORED}, because the whole thing is one transaction and there is
   * nothing left running when the handler returns. For anything that copies bytes it is null, meaning
   * {@link ExperienceBackup.Outcome#QUEUED}: the engine hands the folder to a staging thread and
   * returns long before the bytes land, and "Backup created." over a copy still being written is a lie
   * on the normal path, where the owner always clicks from the lobby.</p>
   */
  private void route(String experienceId, com.sexidium.core.world.WorldKey key,
      ExperienceCommandRouter.Op op, UUID requester, Map<String, String> extra,
      ExperienceBackup.Outcome appliedMeans,
      java.util.function.Consumer<ExperienceBackup.Outcome> tell) {
    String nonce = UUID.randomUUID().toString();
    java.util.concurrent.atomic.AtomicReference<ExperienceBackup.Outcome> localAnswer =
        new java.util.concurrent.atomic.AtomicReference<>();
    localBackupOutcomes.put(nonce, localAnswer);
    Map<String, String> args = new java.util.LinkedHashMap<>(extra);
    args.put("by", requester.toString());
    args.put("nonce", nonce);
    commands.execute(experienceId, key, op, args, result -> {
      ExperienceBackup.Outcome local = localBackupOutcomes.remove(nonce) == null
          ? null : localAnswer.get();
      if (local != null) {
        // It ran HERE, so the exact reason survived unmangled.
        tell.accept(local);
        return;
      }
      // A peer ran it. Its boolean is all that crosses the bus -- except for the code the reason
      // channel now writes into `detail`, which is exactly the difference between "leave the world"
      // and "something broke".
      ExperienceBackup.Outcome reported = result.reason().map(ExperienceService::outcomeOf)
          .orElse(null);
      if (reported != null) {
        tell.accept(reported);
      } else if (result.applied()) {
        tell.accept(appliedMeans == null ? ExperienceBackup.Outcome.QUEUED : appliedMeans);
      } else if (result.routed()) {
        tell.accept(ExperienceBackup.Outcome.QUEUED);
      } else {
        // Refused, unreadable -- or UNROUTED, which for a copy is a failure and not a promise.
        // Unrouted means the placement table was read and no node holds this world, so the request was
        // never even written down: there is no folder anywhere to copy FROM. A delete answers the
        // opposite way to the same word on purpose -- nothing to remove IS a completed delete -- which
        // is why these two paths must not be "simplified" back together.
        tell.accept(ExperienceBackup.Outcome.FAILED);
      }
    });
  }

  /** An {@link ExperienceBackup.Outcome} named by a peer, or null when the code means nothing here. */
  private static ExperienceBackup.Outcome outcomeOf(String code) {
    try {
      return ExperienceBackup.Outcome.valueOf(code);
    } catch (IllegalArgumentException fromAnotherBuild) {
      return null;
    }
  }

  /**
   * The code the handler that just ran recorded, for {@link ExperienceCommandRouter.ReasonReader}.
   *
   * <p>Read once, immediately after the handler returned, and REMOVED — so a synchronous refusal
   * carries its code and a copy still running carries none, which is right: "still running" is
   * precisely the queued case and has no reason yet.</p>
   */
  private String reasonForLocalOp(String experienceId, ExperienceCommandRouter.Op op,
      Map<String, String> args) {
    String nonce = args == null ? null : args.get("nonce");
    if (nonce == null) {
      return null;
    }
    ExperienceBackup.Outcome outcome = synchronousOutcomes.remove(nonce);
    return outcome == null ? null : outcome.name();
  }

  /**
   * What a folder op answered on THIS node before its handler returned, keyed by the request's nonce.
   *
   * <p>Distinct from {@link #localBackupOutcomes}, which belongs to whoever is WAITING: this one
   * belongs to whoever RAN it, and on a network those are different nodes. Entries are removed by the
   * router's read a moment later; the size guard is only there because a router with no reason reader
   * attached would never read them at all.</p>
   */
  private final Map<String, ExperienceBackup.Outcome> synchronousOutcomes =
      new java.util.concurrent.ConcurrentHashMap<>();

  /** The owner of an experience, or null when there is no such row. For operator-facing callers. */
  public UUID ownerOf(String experienceId) {
    Experience experience = experiences.get(experienceId);
    return experience == null ? null : experience.owner();
  }

  /**
   * The copy itself, on the node that holds the folder.
   *
   * <p>Returns whether the request may be answered as a success RIGHT NOW. A copy that has not
   * finished by the time this returns is neither — the engine is allowed to take as long as the folder
   * needs, and this runs on the server thread — so the request row is DEFERRED instead: it stays
   * RUNNING, which is what it is, and the engine's callback completes it with the truth.</p>
   *
   * <p>That deferral is the whole of the fix for a copy that failed AFTER it started. The row used to
   * be marked DONE the moment the copy was handed off — terminal, unreadable by anything afterwards —
   * so an I/O error or a source that would not hold still landed in a callback whose only outlet was
   * an {@code AtomicReference} the requester had already dropped. The owner was told "queued" and then
   * nothing, for ever, about a copy that did not happen.</p>
   */
  private boolean applyBackupVerb(ExperienceCommandRouter.Op op, String experienceId,
      java.util.Map<String, String> args) {
    ExperienceBackup engine = backupEngine;
    if (engine == null) {
      server.logger().warning("Asked to run " + op + " on experience " + experienceId
          + " but no backup engine is attached on this node.");
      return false;
    }
    String nonce = args.get("nonce");
    java.util.concurrent.atomic.AtomicReference<ExperienceBackup.Outcome> slot =
        nonce == null ? null : localBackupOutcomes.get(nonce);
    java.util.concurrent.atomic.AtomicReference<ExperienceBackup.Outcome> answered =
        new java.util.concurrent.atomic.AtomicReference<>();
    // Taken BEFORE the engine is called, because the engine may answer before it returns. Null when
    // there is no request row to hold open (standalone, or an op that ran through the local path).
    ExperienceCommandRouter.Completion pending = commands.deferCurrentRequest();
    // Whether this method has already returned by the time the outcome arrives — i.e. whether this is
    // one of the post-start failures that used to go nowhere at all.
    java.util.concurrent.atomic.AtomicBoolean handedOff =
        new java.util.concurrent.atomic.AtomicBoolean();
    UUID by = parseRequester(args.get("by"));
    java.util.function.Consumer<ExperienceBackup.Outcome> record = outcome -> {
      ExperienceBackup.Outcome value = outcome == null ? ExperienceBackup.Outcome.FAILED : outcome;
      answered.set(value);
      if (slot != null) {
        slot.set(value);
      }
      if (handedOff.get() && !succeeded(value)) {
        // Nobody is holding a callback for this any more: the owner's click was answered QUEUED long
        // ago. The row below is the durable record; this line is what an operator reads.
        server.logger().severe("The " + op + " for experience " + experienceId
            + " failed after it had already started (" + value + "). Nothing was published; the"
            + " request is recorded as FAILED.");
      }
      // The request row, completed with what actually happened rather than with "it started".
      if (pending != null) {
        pending.complete(succeeded(value), value.name());
      }
      switch (value) {
        // Each of these CHANGES a list the owner may be looking at from the lobby while this ran on a
        // worker: a new row, a renamed one, or a world that now runs different terrain. Without the
        // announcement the change exists and nobody's open menu shows it until they reopen it.
        case CREATED, DUPLICATED, RESTORED, REFRESHED ->
            announceUpdated(experienceId, op.name().toLowerCase(java.util.Locale.ROOT));
        default -> { }
      }
    };
    switch (op) {
      case BACKUP -> engine.backup(by, experienceId, record);
      case RESTORE -> engine.restore(by, experienceId, record);
      case REFRESH -> engine.refresh(by, experienceId, record);
      case DUPLICATE -> engine.duplicate(by, experienceId, record);
      default -> {
        return false;
      }
    }
    // Read once. The callback is allowed to arrive on any thread and at any time, so a second read
    // below could disagree with the one the return value was decided from.
    ExperienceBackup.Outcome outcome = answered.get();
    if (outcome != null && nonce != null) {
      // Only a SYNCHRONOUS answer becomes a reason code. A copy still running has no reason yet, and
      // that is exactly the queued case. See reasonForLocalOp.
      if (synchronousOutcomes.size() > 256) {
        synchronousOutcomes.clear();
      }
      synchronousOutcomes.put(nonce, outcome);
    }
    // First writer wins. A plain set() here overwrote the real answer with QUEUED whenever the callback
    // landed between the read above and this line — the owner then waited for a copy that had already
    // been refused as BUSY.
    if (slot != null) {
      slot.compareAndSet(null, ExperienceBackup.Outcome.QUEUED);
    }
    handedOff.set(true);
    // A verb that has not finished by the time this returns is NOT a failure: the engine is allowed to
    // take as long as the folder needs and this runs on the server thread. When there is a request row
    // it is left RUNNING for exactly that case (see `pending` above); this boolean is what the paths
    // with no row -- standalone, and the local branch of `execute` -- answer with.
    return outcome == null || succeeded(outcome);
  }

  /** The four outcomes that mean the work finished and did what it said. */
  private static boolean succeeded(ExperienceBackup.Outcome outcome) {
    return outcome == ExperienceBackup.Outcome.CREATED
        || outcome == ExperienceBackup.Outcome.RESTORED
        || outcome == ExperienceBackup.Outcome.REFRESHED
        || outcome == ExperienceBackup.Outcome.DUPLICATED;
  }

  private static UUID parseRequester(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  /**
   * Per-experience backup cap from {@code worlds.experiences.max-backups-per-experience} (default 3).
   * {@code 0} turns backups off. Backups do NOT count against {@link #maxPerPlayer()}.
   */
  public int maxBackupsPerExperience() {
    return Math.max(0, server.configuration().getInt("worlds.experiences.max-backups-per-experience",
        DEFAULT_MAX_BACKUPS_PER_EXPERIENCE));
  }

  /** The live match of an experience on THIS node, or null. */
  private ActiveMatch liveMatchOf(String experienceId) {
    Experience experience = experiences.get(experienceId);
    return experience == null ? null : games.matchByWorldKey(experience.key());
  }

  /**
   * Routes owner actions to the node holding the world. Standalone runs everything locally, which is
   * exactly what it did before this existed.
   */
  private final ExperienceCommandRouter commands;

  public ExperienceCommandRouter commands() {
    return commands;
  }

  /** The local half of every routed op. */
  private boolean applyCommand(
      String experienceId, ExperienceCommandRouter.Op op, java.util.Map<String, String> args) {
    switch (op) {
      case DELETE -> {
        return applyDelete(experienceId, args.get("key"));
      }
      case END_MATCH -> {
        ActiveMatch live = liveMatchOf(experienceId);
        if (live != null) {
          games.endMatch(live, null);
        }
        return true;
      }
      case SET_CHALLENGES -> {
        String csv = args.getOrDefault("challenges", "");
        return applyChallengesLive(experienceId,
            csv.isBlank() ? List.of() : java.util.Arrays.asList(csv.split(",")));
      }
      case SET_KEEP_INVENTORY -> {
        ActiveMatch live = liveMatchOf(experienceId);
        if (live != null && live.game() instanceof ExperienceGame game) {
          game.setKeepInventoryLive(Boolean.parseBoolean(args.getOrDefault("value", "true")));
        }
        return true;
      }
      case SET_HARDCORE -> {
        ActiveMatch live = liveMatchOf(experienceId);
        if (live != null && live.game() instanceof ExperienceGame game) {
          game.setHardcoreLive(Boolean.parseBoolean(args.getOrDefault("value", "false")));
        }
        return true;
      }
      case BACKUP, RESTORE, REFRESH, DUPLICATE -> {
        return applyBackupVerb(op, experienceId, args);
      }
      default -> {
        return false;
      }
    }
  }

  /** Per-player experience-world cap from {@code worlds.experiences.max-per-player} (min 1, default 10). */
  public int maxPerPlayer() {
    return Math.max(1, server.configuration().getInt("worlds.experiences.max-per-player", DEFAULT_MAX_PER_PLAYER));
  }

  private static String escape(String value) {
    return value == null ? "" : value.replace("<", "&lt;").replace(">", "&gt;");
  }
}
