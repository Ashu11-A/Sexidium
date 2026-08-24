package com.sexidium.core.command;

import com.sexidium.core.game.experience.ExperienceBackup;
import com.sexidium.core.game.experience.ExperienceManager;
import com.sexidium.core.game.experience.ExperienceService;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.sexidium.core.command.CommandText.lower;

/**
 * {@code /sx admin backup …} — the headless surface for experience backups.
 *
 * <p>There was none. Until this existed the ONLY way to take, restore, refresh, duplicate or even
 * <em>see</em> a backup was the tile in the lobby menu, which means the whole feature could not be
 * exercised on the live network without a client standing in the lobby. That makes this a prerequisite
 * for testing any of it on the server rather than a convenience.</p>
 *
 * <h2>Console-safe, on purpose</h2>
 *
 * <p>Nothing here calls {@code ctx.requirePlayer}. A console has no UUID and the service methods are
 * owner-scoped by design, so every verb resolves the <b>true owner off the row</b> and passes that as
 * the requester; the ANSWER goes back to whoever typed it. That is the only shape that lets an
 * operator repair a player's world without impersonating them at the database level.</p>
 *
 * <p>Output is operator-facing raw MiniMessage — the codebase's rule is that <em>player</em>-facing
 * text must be a message key, and an operator reading a console is not that. Anything that came out of
 * the database is escaped first, because MiniMessage would happily render a tag inside a world name a
 * player chose.</p>
 *
 * <p>No permission node of its own: the whole {@code /sx admin} subtree is already behind
 * {@code sexidium.admin} at the root gate. No node-capability gate either — every verb routes itself
 * to the node that holds the folder, so typing it on the lobby is correct.</p>
 */
final class BackupCommands {

  private static final String USAGE =
      "<gray>Usage: <white>/sx admin backup <list [<experienceId>|<player>]|info <backupId>"
          + "|create <experienceId>|restore <backupId>|refresh <backupId>"
          + "|duplicate <experienceId>|delete <backupId>|pending></white></gray>";

  private final CommandContext ctx;

  BackupCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  /** {@code args = [backup, <verb>, …]}. */
  void handle(CommandSource source, String[] args) {
    ExperienceService service = ctx.core.experienceService();
    ExperienceManager registry = ctx.core.experiences();
    if (service == null || registry == null || !registry.available()) {
      // Saying so beats an empty list that reads like "this player has no backups".
      ctx.send(source, "<gray>Experiences are not available on this node: there is no database"
          + " behind them, so there is nothing to back up.</gray>");
      return;
    }
    if (args.length < 2) {
      ctx.send(source, USAGE);
      return;
    }
    switch (lower(args[1])) {
      case "list" -> list(source, registry, args.length > 2 ? args[2] : null);
      case "info" -> info(source, registry, args.length > 2 ? args[2] : null);
      case "create" -> create(source, service, registry, args.length > 2 ? args[2] : null);
      case "restore" -> restore(source, service, registry, args.length > 2 ? args[2] : null);
      case "refresh" -> refresh(source, service, registry, args.length > 2 ? args[2] : null);
      case "duplicate" -> duplicate(source, service, registry, args.length > 2 ? args[2] : null);
      case "delete" -> delete(source, service, registry, args.length > 2 ? args[2] : null);
      case "pending" -> pending(source, service);
      default -> ctx.send(source, USAGE);
    }
  }

  // ----- reads --------------------------------------------------------------------------------

  /**
   * {@code list [<experienceId>|<player>]} — every copy, oldest first, with its age and its source.
   *
   * <p>With an argument this is exact: an experience id lists that world's copies, a player name lists
   * every copy that player owns. With NO argument it can only cover the players this node can see, and
   * it says so rather than printing a short list that reads like a complete one — the registry has no
   * "every row" query, and adding one belongs in {@code ExperienceManager}.</p>
   */
  private void list(CommandSource source, ExperienceManager registry, String target) {
    if (target != null && !target.isBlank()) {
      ExperienceManager.Experience experience = registry.get(target);
      if (experience != null) {
        listOne(source, registry, experience);
        return;
      }
      PlayerAdapter player = ctx.server.playerExact(target).orElse(null);
      if (player == null) {
        ctx.send(source, "<gray>No experience <white>" + escape(target) + "</white>, and no online"
            + " player by that name. A copy is listed by its source's id, or by its owner's name"
            + " while they are connected.</gray>");
        return;
      }
      listOwner(source, registry, player.uniqueId(), player.name());
      return;
    }
    java.util.Collection<PlayerAdapter> online = ctx.server.onlinePlayers();
    if (online.isEmpty()) {
      ctx.send(source, "<gray>Nobody is online, and the registry has no 'every row' query: name an"
          + " <white>experienceId</white> or a connected <white>player</white>.</gray>");
      return;
    }
    ctx.send(source, "<gold>Backups held by the <white>" + online.size()
        + "</white> connected player(s)</gold>");
    // The header alone reads like "every backup on the server", and an operator acting on that during
    // an incident acts on a list that silently omits everybody who happens to be logged off.
    ctx.send(source, "<gray>This is ONLY the connected players: copies owned by anyone offline are"
        + " not listed, because the registry has no 'every row' query. Name an"
        + " <white>experienceId</white> or a connected <white>player</white> for an exact"
        + " answer.</gray>");
    for (PlayerAdapter player : online) {
      listOwner(source, registry, player.uniqueId(), player.name());
    }
  }

  /** Every copy of one world, or of the world THIS row is a copy of when an operator names a copy. */
  private void listOne(CommandSource source, ExperienceManager registry,
      ExperienceManager.Experience experience) {
    if (experience.isBackup()) {
      ExperienceManager.Experience owner = registry.get(experience.backupOf());
      ctx.send(source, "<gray><white>" + escape(experience.id()) + "</white> is itself a copy"
          + (owner == null ? " of a world that has been deleted." : " — listing its source instead."));
      if (owner == null) {
        copyLine(source, experience, null);
        return;
      }
      experience = owner;
    }
    List<ExperienceManager.Experience> backups = registry.backupsOf(experience.id());
    ctx.send(source, "<gold>" + escape(experience.displayName()) + "</gold> <gray>("
        + escape(experience.id()) + ") — <white>" + backups.size() + "</white> cop"
        + (backups.size() == 1 ? "y" : "ies") + "</gray>");
    if (backups.isEmpty()) {
      ctx.send(source, "<gray>· none. <white>/sx admin backup create " + escape(experience.id())
          + "</white> takes one.</gray>");
      return;
    }
    for (ExperienceManager.Experience backup : backups) {
      copyLine(source, backup, experience.displayName());
    }
  }

  private void listOwner(CommandSource source, ExperienceManager registry, UUID owner, String name) {
    List<ExperienceManager.Experience> rows = registry.byOwner(owner);
    // Grouped by source so a copy is never printed without the thing it is a copy of; the display
    // names are byte-identical, so the id is the only thing that tells them apart on a console.
    Map<String, String> sourceNames = new LinkedHashMap<>();
    for (ExperienceManager.Experience candidate : rows) {
      sourceNames.put(candidate.id(), candidate.displayName());
    }
    List<ExperienceManager.Experience> backups = new ArrayList<>();
    for (ExperienceManager.Experience candidate : rows) {
      if (candidate.isBackup()) {
        backups.add(candidate);
      }
    }
    ctx.send(source, "<gold>" + escape(name) + "</gold> <gray>— <white>" + backups.size()
        + "</white> cop" + (backups.size() == 1 ? "y" : "ies") + " across <white>"
        + (rows.size() - backups.size()) + "</white> world(s)</gray>");
    for (ExperienceManager.Experience backup : backups) {
      copyLine(source, backup, sourceNames.get(backup.backupOf()));
    }
  }

  /** One copy on one line: id, source, age. The id leads because the names are identical. */
  private void copyLine(CommandSource source, ExperienceManager.Experience backup, String sourceName) {
    ctx.send(source, "<gray>· <white>" + escape(backup.id()) + "</white>"
        + " <gray>name=</gray>" + escape(backup.displayName())
        + " <gray>of=</gray>" + escape(sourceName == null ? "(deleted)" : sourceName)
        + " <gray>age=</gray>" + age(backup.createdAt())
        + " <gray>world=</gray>" + escape(backup.worldKey()));
  }

  /**
   * {@code info <backupId>} — the identity card, as console text.
   *
   * <p>Every fact printed here is on the row. Deliberately NOT printed: the in-game day count and the
   * death count. They live in {@code experiences.challenge_state}, and on the live server that column
   * lags the {@code state.yml} inside the folder by hours and can be missing a challenge's counters
   * altogether — a wrong number is worse than no number on the one screen whose job is telling two
   * byte-identical worlds apart. Same for a member list: {@code experience_players} is not copied, so
   * every copy would read as empty.</p>
   */
  private void info(CommandSource source, ExperienceManager registry, String backupId) {
    ExperienceManager.Experience row = resolve(source, registry, backupId, "info");
    if (row == null) {
      return;
    }
    ExperienceManager.Experience origin = row.isBackup() ? registry.get(row.backupOf()) : null;
    ctx.send(source, "<gold>" + escape(row.displayName()) + "</gold> <gray>("
        + escape(row.id()) + ")</gray>");
    ctx.send(source, "<gray>· is a copy: <white>" + (row.isBackup() ? "yes" : "no") + "</white>"
        + (row.isBackup()
            ? " <gray>of=</gray><white>" + escape(row.backupOf()) + "</white> <gray>(" + escape(
                origin == null ? "deleted" : origin.displayName()) + ")</gray>"
            : "") + "</gray>");
    ctx.send(source, "<gray>· owner: <white>" + escape(row.ownerName()) + "</white> <gray>("
        + row.owner() + ")</gray></gray>");
    ctx.send(source, "<gray>· taken: <white>" + age(row.createdAt()) + " ago</white> <gray>("
        + java.time.Instant.ofEpochMilli(row.createdAt()) + ")</gray></gray>");
    ctx.send(source, "<gray>· world: <white>" + escape(row.worldKey()) + "</white> <gray>type=</gray>"
        + escape(row.type().displayName()) + "</gray>");
    ctx.send(source, "<gray>· twists: <white>"
        + escape(row.challenges().isEmpty() ? "none" : String.join(", ", row.challenges()))
        + "</white></gray>");
    ctx.send(source, "<gray>· hardcore: <white>" + row.hardcore() + "</white>"
        + " <gray>lost=</gray><white>" + row.isLost() + "</white>"
        + " <gray>keepInventory=</gray><white>" + row.keepInventory() + "</white>"
        + " <gray>public=</gray><white>" + row.isPublic() + "</white></gray>");
    ctx.send(source, "<gray>· run statistics are NOT shown: challenge_state on the row lags the"
        + " state.yml inside the folder, so any day or death count here would be stale.</gray>");
  }

  /**
   * {@code pending} — the request ids the router is still waiting on <em>in memory</em>.
   *
   * <p>Not the same set as "everything that answered QUEUED": a verb is answered QUEUED once its row is
   * written and sent, and every path that does so drops the entry from this wait map before answering.
   * So the empty case is the normal one, and it says what the durable record actually is rather than
   * leaving an operator reading it as "the queued delete I just ran has vanished".</p>
   */
  private void pending(CommandSource source, ExperienceService service) {
    List<String> ids = service.commands().pendingRequestIds();
    if (ids.isEmpty()) {
      ctx.send(source, "<gray>No experience commands are outstanding.</gray>");
      ctx.send(source, "<gray>A verb that answered <white>queued</white> is not listed here — it is a"
          + " row in <white>experience_commands</white>, run by the node holding the folder when it"
          + " next reads the table.</gray>");
      return;
    }
    ctx.send(source, "<gold>Outstanding experience commands (" + ids.size() + ")</gold>");
    for (String id : ids) {
      ctx.send(source, "<gray>· <white>" + escape(id) + "</white></gray>");
    }
  }

  // ----- verbs --------------------------------------------------------------------------------

  private void create(CommandSource source, ExperienceService service, ExperienceManager registry,
      String experienceId) {
    ExperienceManager.Experience row = resolve(source, registry, experienceId, "create");
    if (row == null) {
      return;
    }
    // Both of these are refused by the service as LIMIT_REACHED, which on a console reads "the cap is
    // already reached. Delete one first." — untrue in both cases, and it sends the operator hunting for
    // a copy to delete that may not even exist. The real reason is a property of the ROW.
    if (row.isBackup()) {
      ctx.send(source, "<red><white>" + escape(row.id()) + "</white> is already a copy — a copy of a"
          + " copy is not taken. Name the world it is a copy of, or use <white>/sx admin backup"
          + " duplicate</white> to turn it into a playable world.</red>");
      return;
    }
    if (row.isLost()) {
      ctx.send(source, "<red><white>" + escape(row.id()) + "</white> is a lost hardcore world — the"
          + " run is over, so there is nothing left to carry forward.</red>");
      return;
    }
    ctx.send(source, "<yellow>Copying <white>" + escape(row.displayName())
        + "</white> — the node holding the folder answers when it can.</yellow>");
    service.backup(row.owner(), row.id(), outcome -> report(source, "create", row.id(), outcome));
  }

  private void restore(CommandSource source, ExperienceService service, ExperienceManager registry,
      String backupId) {
    ExperienceManager.Experience row = resolve(source, registry, backupId, "restore");
    if (row == null) {
      return;
    }
    if (!row.isBackup()) {
      // Guarded because the service answers GONE for a row that is not a copy, and GONE renders as
      // "the world this is a copy OF has been deleted" — which on a live world's id reads like data
      // loss that never happened.
      ctx.send(source, "<red><white>" + escape(row.id()) + "</white> is not a copy — there is nothing"
          + " to restore from. Name a backup id; <white>/sx admin backup list " + escape(row.id())
          + "</white> shows them.</red>");
      return;
    }
    ctx.send(source, "<yellow>Restoring <white>" + escape(row.displayName())
        + "</white>. The world it replaces is KEPT as a copy; nobody may be inside either.</yellow>");
    service.restore(row.owner(), row.id(), outcome -> report(source, "restore", row.id(), outcome));
  }

  private void refresh(CommandSource source, ExperienceService service, ExperienceManager registry,
      String backupId) {
    ExperienceManager.Experience row = resolve(source, registry, backupId, "refresh");
    if (row == null) {
      return;
    }
    if (!row.isBackup()) {
      // Same GONE-reads-as-data-loss trap as restore.
      ctx.send(source, "<red><white>" + escape(row.id()) + "</white> is not a copy — there is nothing"
          + " to re-take it from. Name a backup id; <white>/sx admin backup list " + escape(row.id())
          + "</white> shows them.</red>");
      return;
    }
    ctx.send(source, "<yellow>Re-taking <white>" + escape(row.displayName())
        + "</white> from its source. This is a FULL re-copy, not an incremental one.</yellow>");
    service.refresh(row.owner(), row.id(), outcome -> report(source, "refresh", row.id(), outcome));
  }

  private void duplicate(CommandSource source, ExperienceService service, ExperienceManager registry,
      String experienceId) {
    ExperienceManager.Experience row = resolve(source, registry, experienceId, "duplicate");
    if (row == null) {
      return;
    }
    ctx.send(source, "<yellow>Duplicating <white>" + escape(row.displayName())
        + "</white> into a new playable world. It spends one of the owner's world slots.</yellow>");
    service.duplicate(row.owner(), row.id(),
        outcome -> report(source, "duplicate", row.id(), outcome));
  }

  private void delete(CommandSource source, ExperienceService service, ExperienceManager registry,
      String backupId) {
    ExperienceManager.Experience row = resolve(source, registry, backupId, "delete");
    if (row == null) {
      return;
    }
    if (!row.isBackup()) {
      // Guarded because the same call would happily delete a live world, and an operator reaching for
      // "backup delete" is not reaching for that.
      ctx.send(source, "<red><white>" + escape(row.id()) + "</white> is not a copy — it is a world"
          + " somebody plays. Use the owner's own delete for that.</red>");
      return;
    }
    ctx.send(source, "<yellow>Deleting copy <white>" + escape(row.id()) + "</white>.</yellow>");
    service.delete(row.owner(), row.id(), outcome ->
        ctx.send(source, switch (outcome) {
          case DELETED -> "<green>backup delete <white>" + escape(row.id()) + "</white>: deleted."
              + "</green>";
          case QUEUED -> "<yellow>backup delete <white>" + escape(row.id()) + "</white>: written"
              + " down; the node holding the world runs it when it reads its table.</yellow>";
          case NOT_OWNER -> "<red>backup delete <white>" + escape(row.id()) + "</white>: the row's"
              + " owner was refused ownership of it — the registry disagrees with itself.</red>";
          case REFUSED -> "<red>backup delete <white>" + escape(row.id()) + "</white>: refused."
              + " Somebody is inside it, or the unload failed.</red>";
        }));
  }

  // ----- shared -------------------------------------------------------------------------------

  /** Resolves a row and complains usefully when it is not there. Null means "already answered". */
  private ExperienceManager.Experience resolve(CommandSource source, ExperienceManager registry,
      String id, String verb) {
    if (id == null || id.isBlank()) {
      ctx.send(source, "<gray>Usage: <white>/sx admin backup " + verb + " <id></white></gray>");
      return null;
    }
    ExperienceManager.Experience found = registry.get(id);
    if (found == null) {
      ctx.send(source, "<red>No experience <white>" + escape(id) + "</white>.</red>");
      return null;
    }
    return found;
  }

  /**
   * One line per answer, naming the verb and the row.
   *
   * <p>Exhaustive over the whole shared outcome enum with no {@code default}: a {@code default} is
   * exactly what would swallow the next constant somebody adds, and an operator reading a console is
   * the person least able to notice that an answer went missing.</p>
   */
  private void report(CommandSource source, String verb, String id, ExperienceBackup.Outcome outcome) {
    String subject = "backup " + verb + " <white>" + escape(id) + "</white>: ";
    ctx.send(source, switch (outcome) {
      case CREATED -> "<green>" + subject + "the copy is on disk and registered.</green>";
      case RESTORED -> "<green>" + subject + "restored. The world it replaced is kept as a copy."
          + "</green>";
      case REFRESHED -> "<green>" + subject + "re-taken in full from its source.</green>";
      case DUPLICATED -> "<green>" + subject + "duplicated into a new playable world.</green>";
      // NOT "/sx admin backup pending shows it": every path that answers QUEUED has already dropped the
      // request from the router's in-memory wait list, so that command answers "nothing outstanding"
      // for exactly the request this line is about. The durable record is the experience_commands row,
      // which the node holding the folder picks up when it next reads the table.
      case QUEUED -> "<yellow>" + subject + "written down and sent; the node holding the folder has"
          + " not answered yet. The row is in <white>experience_commands</white> until it does."
          + "</yellow>";
      case BUSY -> "<red>" + subject + "refused — a world involved is loaded or has a live match."
          + " Everybody has to leave and it has to finish unloading.</red>";
      case LIMIT_REACHED -> "<red>" + subject + "refused — the cap is already reached. Delete one"
          + " first.</red>";
      case NOT_OWNER -> "<red>" + subject + "refused — the requester does not own it.</red>";
      case GONE -> "<red>" + subject + "refused — the world this is a copy OF has been deleted,"
          + " so there is nothing to put it back over.</red>";
      case NO_SPACE -> "<red>" + subject + "refused — not enough free disk to hold the copy.</red>";
      case FAILED -> "<red>" + subject + "failed. Check this node's log; any half-written folder"
          + " has already been removed.</red>";
    });
  }

  /** A coarse age, because an operator scanning a list wants "3d" and not a timestamp. */
  private static String age(long at) {
    long seconds = Math.max(0L, (System.currentTimeMillis() - at) / 1000L);
    if (seconds < 60) {
      return seconds + "s";
    }
    if (seconds < 3600) {
      return (seconds / 60) + "m";
    }
    if (seconds < 86400) {
      return (seconds / 3600) + "h";
    }
    return (seconds / 86400) + "d";
  }

  /** Display names and world keys come from the database, and MiniMessage would render a tag. */
  private static String escape(String value) {
    return value == null ? "—" : value.replace("<", "\\<");
  }

  List<String> suggest(CommandSource source, String[] args) {
    if (args.length == 2) {
      return ctx.filter(List.of("list", "info", "create", "restore", "refresh", "duplicate",
          "delete", "pending"), args[1]);
    }
    if (args.length == 3) {
      // A player name is an argument to `list` and to nothing else — every other verb takes an
      // experience or backup id, and offering names there completes values that can never work. Ids
      // are not offered at all: the registry has no all-rows query to draw them from.
      return lower(args[1]).equals("list") ? ctx.filter(ctx.playerNames(), args[2]) : List.of();
    }
    return List.of();
  }
}
