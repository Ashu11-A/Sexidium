package com.sexidium.core.command;

import com.sexidium.core.game.experience.ChallengeCatalog;
import com.sexidium.core.game.experience.ExperienceGame;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.game.experience.ExperienceSetup;
import com.sexidium.core.game.experience.ExperienceWorldType;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.sexidium.core.command.CommandText.addCommaSeparated;
import static com.sexidium.core.command.CommandText.escape;
import static com.sexidium.core.command.CommandText.lower;

/** Handles {@code /sx experience} and {@code /sx start experience} — composable, owner-registered experiences. */
final class ExperienceCommands {
  /**
   * How long a typed experience name may be. Not cosmetic: {@code WorldKey.of(displayName, id)} builds
   * every later backup's {@code world_key} out of this name, and that column is a {@code VARCHAR(191)}
   * with a unique index.
   *
   * <p>This is the one entry point where an arbitrary length is TYPED, not the one place a long name
   * can reach the database: a name the server generates from a challenge list gets there too, and 12
   * of the 27 twists already make 199 characters. The cap therefore lives on
   * {@link ExperienceService#MAX_DISPLAY_NAME}, where {@code displayNameFor} enforces the same number
   * on every generated name, and this is the same limit read from there.</p>
   */
  private static final int MAX_DISPLAY_NAME = ExperienceService.MAX_DISPLAY_NAME;

  private final CommandContext ctx;

  ExperienceCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  void handle(CommandSource source, String[] args) {
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    ExperienceService service = ctx.core.experienceService();
    // Bare "/sx experience" opens the GUI (My Experiences -> manage -> Edit Challenges + green confirm).
    if (args.length <= 1) {
      ctx.core.menus().openExperiences(player);
      return;
    }
    String action = lower(args[1]);
    switch (action) {
      case "menu" -> ctx.core.menus().openExperiences(player);
      case "edit" -> {
        if (args.length < 3) {
          ctx.core.menus().openExperiences(player);
        } else {
          ctx.core.menus().openExperienceEdit(player, args[2]);
        }
      }
      case "list" -> {
        List<ExperienceManager.Experience> own = service.own(player.uniqueId());
        if (own.isEmpty()) {
          ctx.send(source, "<gray>You have no experiences yet. Create one with </gray><white>/sx start experience <challenges...></white>");
          return;
        }
        ctx.send(source, "<light_purple><bold>Your experiences:</bold></light_purple>");
        for (ExperienceManager.Experience experience : own) {
          ctx.send(source, " <white>" + escape(experience.displayName()) + "</white> <gray>(" + experience.id() + ")</gray> "
              + (experience.isPublic() ? "<green>[public]</green>" : "<dark_gray>[private]</dark_gray>")
              + " <yellow>/sx experience join " + experience.id() + "</yellow>");
        }
      }
      case "join" -> {
        if (args.length < 3) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience join <id></white>");
          return;
        }
        ctx.send(source, switch (service.enter(player, args[2])) {
          case ENTERED, STARTED -> "<green>Entering experience…</green>";
          case REQUEST_SENT -> "<yellow>Join request sent to the owner.</yellow>";
          case ALREADY_IN_MATCH -> "<red>You are already in an experience — use /leave first.</red>";
          case NOT_FOUND -> "<red>No such experience.</red>";
          case LIMIT_REACHED -> "<red>Experience-world limit reached.</red>";
          case HOST_OFFLINE -> "<red>The owner of that experience is offline.</red>";
          case OFFLINE, FAILED -> "<red>Could not enter that experience.</red>";
        });
      }
      case "requests" -> {
        List<ExperienceService.JoinRequest> requests = service.requestsFor(player.uniqueId());
        if (requests.isEmpty()) {
          ctx.send(source, "<gray>No pending join requests.</gray>");
          return;
        }
        ctx.send(source, "<light_purple><bold>Join requests:</bold></light_purple>");
        for (ExperienceService.JoinRequest request : requests) {
          ctx.send(source, " <white>" + escape(request.requesterName()) + "</white> <gray>→ " + escape(request.experienceName())
              + "</gray> <green>/sx experience accept " + escape(request.requesterName()) + "</green>");
        }
      }
      case "accept", "deny" -> {
        if (args.length < 3) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience " + action + " <player></white>");
          return;
        }
        PlayerAdapter target = ctx.server.playerExact(args[2]).orElse(null);
        if (target == null) {
          ctx.send(source, MessageKey.COMMAND_KIT_PLAYER_OFFLINE, MessageArg.text("player", args[2]));
          return;
        }
        if (action.equals("accept")) {
          ExperienceService.EnterOutcome outcome = service.accept(player.uniqueId(), target.uniqueId());
          ctx.send(source, outcome == ExperienceService.EnterOutcome.ENTERED || outcome == ExperienceService.EnterOutcome.STARTED
              ? "<green>Approved " + escape(target.name()) + ".</green>"
              : "<red>No pending request from that player.</red>");
        } else {
          ctx.send(source, service.deny(player.uniqueId(), target.uniqueId())
              ? "<yellow>Denied " + escape(target.name()) + ".</yellow>"
              : "<red>No pending request from that player.</red>");
        }
      }
      case "public", "private" -> {
        if (args.length < 3) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience " + action + " <id></white>");
          return;
        }
        boolean isPublic = action.equals("public");
        ctx.send(source, service.setVisibility(player.uniqueId(), args[2], isPublic)
            ? "<green>Experience is now " + action + ".</green>"
            : "<red>You do not own that experience.</red>");
      }
      case "hardcore" -> {
        if (args.length < 4) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience hardcore <id> <on|off></white>");
          return;
        }
        boolean enabled = args[3].equalsIgnoreCase("on") || args[3].equalsIgnoreCase("true");
        // Refused once the world has been lost: hardcore that could be switched off afterwards would
        // mean nothing, so the only thing left to do with a dead world is delete it.
        ctx.send(source, service.setHardcore(player.uniqueId(), args[2], enabled)
            ? (enabled
                ? "<dark_red>HARDCORE ON.</dark_red> <gray>One death ends this world for good.</gray>"
                : "<green>Hardcore off. Deaths are survivable again.</green>")
            : "<red>You do not own that experience, or it has already been lost.</red>");
      }
      case "rename" -> {
        if (args.length < 4) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience rename <id> <name></white>");
          return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        // Capped here because the name does not stay a label: every backup taken afterwards derives its
        // world_key from it, and world_key is a VARCHAR(191) with a unique index — a name long enough to
        // overflow it makes the copy's INSERT fail with nothing on any screen to explain why.
        if (name.length() > MAX_DISPLAY_NAME) {
          ctx.send(source, "<red>That name is too long — keep it to " + MAX_DISPLAY_NAME
              + " characters or fewer.</red>");
          return;
        }
        ctx.send(source, service.rename(player.uniqueId(), args[2], name)
            ? "<green>Renamed experience.</green>"
            : "<red>You do not own that experience.</red>");
      }
      case "delete" -> {
        if (args.length < 3) {
          ctx.send(source, "<red>Usage:</red> <white>/sx experience delete <id></white>");
          return;
        }
        // Every failure used to be reported as "you do not own that experience", including the one
        // that actually happened: a lobby refusing to delete a world it is not allowed to open.
        ctx.send(source, MessageKey.EXPERIENCE_DELETE_WORKING);
        service.delete(player.uniqueId(), args[2], outcome -> ctx.send(source, switch (outcome) {
          case DELETED -> MessageKey.EXPERIENCE_DELETE_DONE;
          case QUEUED -> MessageKey.EXPERIENCE_DELETE_QUEUED;
          case NOT_OWNER -> MessageKey.EXPERIENCE_DELETE_NOT_OWNER;
          case REFUSED -> MessageKey.EXPERIENCE_DELETE_REFUSED;
        }));
      }
      default -> ctx.send(source, "<gray>/sx experience <menu|edit|list|join|requests|accept|deny|public|private|hardcore|rename|delete></gray>");
    }
  }

  List<String> suggest(CommandSource source, String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("menu", "edit", "list", "join", "requests", "accept", "deny", "public", "private", "hardcore", "rename", "delete"), args[1]);
    }
    if (args.length == 3) {
      return switch (lower(args[1])) {
        case "join" -> ctx.filter(joinableExperienceIds(source), args[2]);
        case "edit", "public", "private", "hardcore", "rename", "delete" -> ctx.filter(ownExperienceIds(source), args[2]);
        case "accept", "deny" -> ctx.filter(ctx.playerNames(), args[2]);
        default -> List.of();
      };
    }
    if (args.length == 4 && lower(args[1]).equals("hardcore")) {
      return ctx.filter(List.of("on", "off"), args[3]);
    }
    return List.of();
  }

  private List<String> ownExperienceIds(CommandSource source) {
    PlayerAdapter player = ctx.playerSource(source);
    if (player == null) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (ExperienceManager.Experience experience : ctx.core.experienceService().own(player.uniqueId())) {
      ids.add(experience.id());
    }
    return ids;
  }

  private List<String> joinableExperienceIds(CommandSource source) {
    PlayerAdapter player = ctx.playerSource(source);
    if (player == null) {
      return List.of();
    }
    List<String> ids = new ArrayList<>(ownExperienceIds(source));
    for (ExperienceManager.Experience experience : ctx.core.experienceService().browsable(player.uniqueId())) {
      ids.add(experience.id());
    }
    return ids;
  }

  void handleStart(CommandSource source, String[] args) {
    List<String> challengeIds = new ArrayList<>();
    List<String> playerNames = new ArrayList<>();
    ExperienceWorldType worldType = null;
    boolean keepInventory = ExperienceSetup.DEFAULT.keepInventory();
    for (int index = 2; index < args.length; index++) {
      String arg = args[index];
      if (arg == null || arg.isBlank()) {
        continue;
      }
      if (arg.startsWith("--players=")) {
        addCommaSeparated(playerNames, arg.substring("--players=".length()));
      } else if (arg.equals("--players") && index + 1 < args.length) {
        addCommaSeparated(playerNames, args[++index]);
      } else if (arg.startsWith("--world=")) {
        worldType = ExperienceWorldType.of(arg.substring("--world=".length()));
      } else if (arg.startsWith("--keep-inventory=")) {
        keepInventory = Boolean.parseBoolean(arg.substring("--keep-inventory=".length()).trim());
      } else if (arg.startsWith("--")) {
        // Unknown flag — ignore for forward compatibility.
      } else if (ChallengeCatalog.contains(arg)) {
        String id = arg.toLowerCase(Locale.ROOT);
        if (!challengeIds.contains(id)) {
          challengeIds.add(id);
        }
      } else {
        ctx.send(source, "<red>Unknown challenge:</red> <white>" + escape(arg) + "</white>");
      }
    }
    // Only ONE challenge may generate the world — two of them would build two maps into the same world.
    // A named map challenge also decides the map type, so "--world" is only needed for a nether/end start.
    List<String> mapChallenges = ChallengeCatalog.mapChallenges(challengeIds);
    if (mapChallenges.size() > 1) {
      ctx.send(source, "<red>Pick only one world-generating challenge:</red> <white>"
          + String.join(", ", mapChallenges) + "</white>");
      return;
    }
    if (!mapChallenges.isEmpty()) {
      ExperienceWorldType mapType = ExperienceWorldType.forChallenge(mapChallenges.get(0));
      if (worldType != null && worldType != mapType) {
        ctx.send(source, "<red>" + escape(mapChallenges.get(0)) + " generates its own world — drop --world.</red>");
        return;
      }
      worldType = mapType;
    }
    ExperienceWorldType type = worldType == null ? ExperienceWorldType.NORMAL : worldType;
    ExperienceSetup setup = new ExperienceSetup(type, keepInventory);
    challengeIds = ExperienceService.challengesFor(challengeIds, type);
    if (challengeIds.isEmpty()) {
      ctx.send(source, "<red>Usage:</red> <white>/sx start experience <challenge...> "
          + "[--world=normal|nether|end|superflat|largebiomes|amplified] "
          + "[--keep-inventory=true|false]</white> "
          + "<gray>(e.g. xphealth sharedlife sharedinventory)</gray>");
      ctx.send(source, "<gray>Available:</gray> <white>" + String.join(", ", challengeIds()) + "</white>");
      return;
    }
    // Only admins may seed an experience with arbitrary named players; a normal player brings
    // themselves and their online party.
    List<PlayerAdapter> participants = (!playerNames.isEmpty() && source != null && source.hasPermission(CoreCommandService.ADMIN_PERMISSION))
        ? ctx.participants(playerNames, source)
        : ctx.participants(List.of(), source);
    if (participants.isEmpty()) {
      ctx.send(source, MessageKey.COMMAND_START_NO_PLAYERS);
      return;
    }
    PlayerAdapter owner = ctx.playerSource(source);
    // Persistent, owner-registered experience when a database is available; otherwise a transient one
    // in a leased world (same composed challenges, just not kept across restarts).
    if (owner != null && ctx.core.experiences().available()) {
      int maxPerPlayer = ctx.core.experienceService().maxPerPlayer();
      if (ctx.core.experiences().countByOwner(owner.uniqueId()) >= maxPerPlayer) {
        ctx.send(source, MessageKey.EXPERIENCE_LIMIT_REACHED, MessageArg.text("max", maxPerPlayer));
        return;
      }
      ExperienceManager.Experience experience = ctx.core.experiences().create(
          owner.uniqueId(), owner.name(), challengeIds, ExperienceService.displayNameFor(challengeIds),
          ExperienceManager.Experience.MODE_EXPERIENCE, setup, System.currentTimeMillis());
      if (experience != null) {
        boolean started = ctx.core.games().startExperience(experience.key(), participants, source,
            setup.toModeArgs(experience.challenges()));
        if (started && !ctx.core.games().isStarting()) {
          ctx.send(source, MessageKey.COMMAND_START_SUCCESS,
              MessageArg.text("mode", "experience"),
              MessageArg.text("count", participants.size()));
        }
        return;
      }
    }
    boolean started = ctx.core.games().start(ExperienceGame.MODE_ID, participants, source, setup.toModeArgs(challengeIds));
    if (started && !ctx.core.games().isStarting()) {
      ctx.send(source, MessageKey.COMMAND_START_SUCCESS,
          MessageArg.text("mode", "experience"),
          MessageArg.text("count", participants.size()));
    }
  }

  /** Ids of every selectable experience challenge, in catalog (display) order. */
  List<String> challengeIds() {
    List<String> ids = new ArrayList<>();
    for (ChallengeCatalog.Entry entry : ChallengeCatalog.available()) {
      ids.add(entry.id());
    }
    return ids;
  }
}
