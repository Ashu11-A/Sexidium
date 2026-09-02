package com.sexidium.core.game.experience;

import com.sexidium.core.game.persist.PlayerSnapshot;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.world.WorldKey;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The persistent registry of player-owned experiences (one row in the {@code experiences} table per
 * map). It is the source of truth for ownership, the stable world name, the active challenge set and
 * the public/private flag — independent of whether the experience currently has a live match in
 * memory. Experience maps are NEVER removed here except by an explicit owner delete (via the GUI) or
 * a ban, satisfying the "experiences are never deleted" rule.
 *
 * <p>Modeled on {@link com.sexidium.core.data.FriendService}: synchronous SQLite access guarded by
 * {@link Database#lock()}. When no database is configured the whole feature degrades to no-ops.</p>
 */
public final class ExperienceManager {
  /**
   * One stored experience. {@code worldKey} is {@link WorldKey#key()} and nothing else — the single
   * spelling its folder, its runtime world, its placement row and this column all agree on.
   *
   * <p>It used to be owner-scoped ({@code Ashu11a/Diamond_Hunt_ab12cd34}), documented as producing an
   * {@code <nick>/} folder level the world layer silently dropped. That mismatch is why an exact
   * lookup by world could not succeed for ANY generation, why a tolerant scan of the whole table had
   * to exist behind it, and why one world could hold several placement rows.</p>
   */
  public record Experience(
      String id,
      UUID owner,
      String ownerName,
      String worldKey,
      String displayName,
      List<String> challenges,
      boolean isPublic,
      long createdAt,
      String mode,
      String worldType,
      boolean keepInventory,
      boolean hardcore,
      boolean dead,
      /**
       * The experience this one is a BACKUP of, or null for an ordinary experience.
       *
       * <p>LAST in the component list on purpose: every positional construction that existed before
       * backups did keeps compiling through the back-compat constructor below, so adding the concept
       * touched no caller that does not care about it.</p>
       */
      String backupOf
  ) {
    /** A normal composable experience: the owner hand-picks a fixed set of challenges. */
    public static final String MODE_EXPERIENCE = "experience";
    /** A persistent Chaos world: random twists reshuffled on a timer (no fixed challenge set). */
    public static final String MODE_CHAOS = "chaos";

    public Experience {
      challenges = challenges == null ? List.of() : List.copyOf(challenges);
      mode = mode == null || mode.isBlank() ? MODE_EXPERIENCE : mode;
      worldType = worldType == null || worldType.isBlank() ? ExperienceWorldType.NORMAL.id() : worldType;
    }

    /** Back-compat constructor for normal experiences (mode defaults to {@link #MODE_EXPERIENCE}). */
    public Experience(String id, UUID owner, String ownerName, String worldKey, String displayName,
        List<String> challenges, boolean isPublic, long createdAt) {
      this(id, owner, ownerName, worldKey, displayName, challenges, isPublic, createdAt, MODE_EXPERIENCE);
    }

    /** Back-compat constructor for a typed mode with the default (normal terrain) world type. */
    public Experience(String id, UUID owner, String ownerName, String worldKey, String displayName,
        List<String> challenges, boolean isPublic, long createdAt, String mode) {
      this(id, owner, ownerName, worldKey, displayName, challenges, isPublic, createdAt, mode,
          ExperienceWorldType.NORMAL.id(), ExperienceSetup.DEFAULT.keepInventory());
    }

    /** Back-compat constructor for a typed world with the default (enabled) keep-inventory rule. */
    public Experience(String id, UUID owner, String ownerName, String worldKey, String displayName,
        List<String> challenges, boolean isPublic, long createdAt, String mode, String worldType) {
      this(id, owner, ownerName, worldKey, displayName, challenges, isPublic, createdAt, mode, worldType,
          ExperienceSetup.DEFAULT.keepInventory());
    }

    /** Back-compat constructor for an experience with no hardcore choice recorded (a normal world). */
    public Experience(String id, UUID owner, String ownerName, String worldKey, String displayName,
        List<String> challenges, boolean isPublic, long createdAt, String mode, String worldType,
        boolean keepInventory) {
      this(id, owner, ownerName, worldKey, displayName, challenges, isPublic, createdAt, mode, worldType,
          keepInventory, false, false);
    }

    /** Back-compat constructor for an experience that is not a backup of anything. */
    public Experience(String id, UUID owner, String ownerName, String worldKey, String displayName,
        List<String> challenges, boolean isPublic, long createdAt, String mode, String worldType,
        boolean keepInventory, boolean hardcore, boolean dead) {
      this(id, owner, ownerName, worldKey, displayName, challenges, isPublic, createdAt, mode, worldType,
          keepInventory, hardcore, dead, null);
    }

    /** Whether this row is a copy taken of another experience rather than an experience of its own. */
    public boolean isBackup() {
      return backupOf != null && !backupOf.isBlank();
    }

    /** This experience's world identity. Parsed once here so no caller ever re-derives one. */
    public WorldKey key() {
      return WorldKey.parse(worldKey);
    }

    /** True when this is a Chaos world (random reshuffling twists) rather than a hand-built experience. */
    public boolean isChaos() {
      return MODE_CHAOS.equals(mode);
    }

    /**
     * The effective map type: whichever map-generating challenge this experience runs, else the stored
     * start dimension. Reading it through {@link ExperienceWorldType#resolve} means experiences created
     * before the selector existed still report the right type from their challenge list alone.
     */
    public ExperienceWorldType type() {
      return ExperienceWorldType.resolve(challenges, worldType);
    }

    /** The non-challenge options this experience runs with (map type, keep inventory, hardcore). */
    public ExperienceSetup setup() {
      return new ExperienceSetup(type(), keepInventory, hardcore);
    }

    /**
     * Whether this world has been LOST: a death in a hardcore experience ends it for good. It stays on
     * the owner's list — so they can look at what happened and choose when to let go — but it can only
     * ever be entered as a spectator, and deleting it is the only thing left to do with it.
     */
    public boolean isLost() {
      return hardcore && dead;
    }

    /**
     * What a node must implement before it may host this world — the content half of placement.
     *
     * <p>The codes are {@link com.sexidium.core.network.ContentManifest}'s and nothing else: a second
     * spelling of "the doubledrops challenge" would mean a node could satisfy one gate and fail the
     * other, which is worse than having no gate at all. A challenge id this build cannot construct is
     * dropped silently by {@code ChallengeCatalog.create}, and the world is then generated as if the
     * challenge had never been asked for — normal terrain over an empty SkyBlock. That is what these
     * codes exist to refuse.</p>
     *
     * <p>A Chaos world carries no fixed challenge list — its twists are drawn from the whole catalog at
     * runtime — so it additionally requires the host's catalog to be byte-identical to ours, expressed
     * as {@code d:<localDigest>}. Pass a blank digest to leave a chaos world unconstrained, which is
     * what a node with no manifest of its own has to do.</p>
     */
    public List<String> contentCodes(String localDigest) {
      List<String> codes = new ArrayList<>(challenges.size() + 2);
      for (String challenge : challenges) {
        codes.add(com.sexidium.core.network.ContentManifest.CHALLENGE_PREFIX + challenge);
      }
      codes.add(com.sexidium.core.network.ContentManifest.MODE_PREFIX + mode);
      if (isChaos() && localDigest != null && !localDigest.isBlank()) {
        codes.add(com.sexidium.core.network.ContentManifest.DIGEST_PREFIX + localDigest);
      }
      return List.copyOf(codes);
    }
  }

  private final LoggerAdapter logger;
  private final Database database;

  public ExperienceManager(LoggerAdapter logger, Database database) {
    this.logger = logger;
    this.database = database;
  }

  public boolean available() {
    return database != null;
  }

  /** Creates and persists a new experience owned by the player. Returns null if it could not be stored. */
  public Experience create(UUID owner, String ownerName, List<String> challenges, String displayName, long now) {
    return create(owner, ownerName, challenges, displayName, Experience.MODE_EXPERIENCE, now);
  }

  /**
   * Creates a persistent experience-style world of the given {@code mode} ({@link Experience#MODE_EXPERIENCE}
   * or {@link Experience#MODE_CHAOS}). A Chaos world stores no fixed challenges — its twists are random and
   * reshuffled at runtime — so callers pass an empty {@code challenges} list for it.
   */
  public Experience create(UUID owner, String ownerName, List<String> challenges, String displayName, String mode, long now) {
    return create(owner, ownerName, challenges, displayName, mode, ExperienceWorldType.NORMAL, now);
  }

  /**
   * As {@link #create(UUID, String, List, String, String, long)} but records the chosen map type (the
   * world the owner picked in the builder: normal terrain, a Nether/End start, or one of the generated
   * SkyBlock maps). The type is fixed at creation because it decides how the world is generated.
   */
  public Experience create(UUID owner, String ownerName, List<String> challenges, String displayName, String mode,
      ExperienceWorldType worldType, long now) {
    return create(owner, ownerName, challenges, displayName, mode,
        ExperienceSetup.DEFAULT.withWorldType(worldType), now);
  }

  /**
   * As above but recording the full {@link ExperienceSetup} the owner chose in the builder — the map
   * type AND whether deaths keep their inventory.
   */
  public Experience create(UUID owner, String ownerName, List<String> challenges, String displayName, String mode,
      ExperienceSetup setup, long now) {
    ExperienceSetup options = setup == null ? ExperienceSetup.DEFAULT : setup;
    ExperienceWorldType worldType = options.worldType();
    if (database == null || owner == null) {
      return null;
    }
    String id = newExperienceId();
    String name = displayName == null || displayName.isBlank() ? "Experience " + id : displayName;
    // <map>_<id>, no owner segment. The trailing 8-hex id is what makes the key unique; the nick
    // never was, and the <nick>/ level it implied was never created on disk by anything.
    String worldKey = WorldKey.of(name, id).key();
    Experience experience = new Experience(id, owner, ownerName, worldKey, name, challenges, false, now, mode,
        worldType.id(), options.keepInventory(), options.hardcore(), false);
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement("""
          INSERT INTO experiences(id, owner_uuid, owner_name, world_key, display_name, challenges, is_public, created_at, updated_at, mode, world_type, keep_inventory, hardcore)
          VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)""")) {
        statement.setString(1, id);
        statement.setString(2, owner.toString());
        statement.setString(3, ownerName == null ? "" : ownerName);
        statement.setString(4, worldKey);
        statement.setString(5, name);
        statement.setString(6, String.join(",", experience.challenges()));
        statement.setLong(7, now);
        statement.setLong(8, now);
        statement.setString(9, experience.mode());
        statement.setString(10, experience.worldType());
        statement.setInt(11, experience.keepInventory() ? 1 : 0);
        statement.setInt(12, experience.hardcore() ? 1 : 0);
        statement.executeUpdate();
      } catch (SQLException exception) {
        logger.warning("Failed to create experience for " + ownerName, exception);
        return null;
      }
    }
    return experience;
  }

  /** A fresh 8-hex experience id. One spelling, so a backup's id looks like any other experience's. */
  public static String newExperienceId() {
    return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
  }

  /**
   * Registers a BACKUP of {@code source} as an experience in its own right.
   *
   * <p>Its own id, its own {@code world_key}, its own folders — the only thing that marks it as a copy
   * is {@code backup_of}. That is what makes restoring "enter the backup" rather than a second engine
   * that has to put bytes back underneath a running world, and it is why nothing at read time treats a
   * backup as a special case.</p>
   *
   * <p>Everything that decides how the world is GENERATED is copied verbatim — the challenge list, the
   * world type, hardcore. A byte copy of the folder reproduces the terrain that already exists, but a
   * void world's generator is supplied programmatically and recorded nowhere on disk, so a chunk
   * generated after the copy is first loaded comes from whatever the row says. A backup that lost its
   * world type would grow ordinary terrain at the edge of a SkyBlock.</p>
   *
   * <p>{@code dead} travels too: a copy of a world that has already been lost is a copy of a lost
   * world, and quietly reviving it here would be the one thing hardcore promises cannot happen.</p>
   *
   * @return the stored backup, or null when it could not be written
   */
  public Experience createBackup(Experience source, String displayName, long now) {
    if (source == null) {
      return null;
    }
    String id = newExperienceId();
    String name = backupDisplayName(source, displayName, id);
    return createBackup(source, name, id, WorldKey.of(name, id), now);
  }

  /**
   * {@link #createBackup(Experience, String, long)} with the id and world key chosen by the caller.
   *
   * <p>The backup ENGINE needs this one: the folders are copied before the row is written (a row with
   * no folder is a map the owner can click and a world the entry path would then generate from
   * nothing), so the destination key has to exist before there is anything to insert.</p>
   *
   * <p>{@code worldKey} must be generation 0 of a FRESH base, never {@code nextGeneration()} of the
   * source's. A backup is a different run, not a later generation of the same one: sharing the base
   * would make {@code WorldKey.sameRun} and the newer-generation-on-disk check treat the copy as a
   * lineage sibling of the original and refuse legitimate opens of it.</p>
   */
  public Experience createBackup(Experience source, String displayName, String id, WorldKey worldKey, long now) {
    return createBackup(source, displayName, id, worldKey, now, null, Integer.MAX_VALUE);
  }

  /**
   * {@link #createBackup(Experience, String, String, WorldKey, long)} with the run's counters handed in
   * and the per-experience cap re-counted atomically with the insert.
   *
   * <h2>Why the counters are a parameter and not a column read</h2>
   *
   * <p>They used to be {@code challengeState(source.id())} — the {@code challenge_state} COLUMN of the
   * source. That column is a crash net written only when a reset wipes the folder the real file lives
   * in, so on a long-running world it is arbitrarily old: measured live, it said 39 558 seconds of
   * playtime while the world's own {@code state.yml} said 62 983, and it had no record at all of the
   * day count or the blocks broken. Copying it made every backup a promise to roll a run back six and a
   * half hours. So the caller — which is the engine, on the node that can actually read the folder —
   * passes the TRUTH from {@code state.yml}, and a null means "store nothing" rather than "store the
   * value we know to be wrong". A backup with no column still reads its counters out of the copied
   * file, which is where they were always going to come from.</p>
   *
   * <h2>Why the cap is re-counted here</h2>
   *
   * <p>The check upstream and this insert are a check-then-act: a double click, or two nodes, could
   * both pass a cap of 3 and land a fourth copy. Counting inside the same transaction as the insert is
   * what makes the cap true rather than likely. Pass {@link Integer#MAX_VALUE} to mean "no cap here",
   * which is what the callers that are not the backup engine want.</p>
   *
   * @return the stored backup, or null when it could not be written (including: the cap was reached)
   */
  public Experience createBackup(Experience source, String displayName, String id, WorldKey worldKey,
      long now, String carriedState, int cap) {
    return createBackup(source, displayName, id, worldKey, now, carriedState, cap, Integer.MAX_VALUE);
  }

  /**
   * {@link #createBackup(Experience, String, String, WorldKey, long, String, int)} with the cap the
   * ORPHAN branch spends handed in as well.
   *
   * <p>Two caps because this method can write two different rows. A copy of a source that is still
   * there is a backup and spends a slot of {@code cap}, the per-experience one. A copy whose source
   * was deleted while the folder was being copied is recorded as a world of its own (see below), and a
   * world of its own spends a slot of the PER-PLAYER one — which the single-cap version skipped
   * entirely, so that row was counted by nothing at the moment it was written. It is counted by
   * {@link #countByOwner} from then on, which is precisely why letting it in unmeasured is a cap the
   * owner is silently over rather than a limit they can act on.</p>
   *
   * @param ownWorldCap the per-player world cap, re-counted in this transaction but only on the orphan
   *     branch. {@link Integer#MAX_VALUE} means "no cap here", which is what every caller that is not
   *     the backup engine wants.
   */
  public Experience createBackup(Experience source, String displayName, String id, WorldKey worldKey,
      long now, String carriedState, int cap, int ownWorldCap) {
    if (database == null || source == null || id == null || worldKey == null) {
      return null;
    }
    String name = backupDisplayName(source, displayName, id);
    Experience backup = new Experience(id, source.owner(), source.ownerName(), worldKey.key(), name,
        source.challenges(), false, now, source.mode(), source.worldType(), source.keepInventory(),
        source.hardcore(), source.dead(), source.id());
    // The SAME row with no `backup_of`, for the case below where the source was deleted while the
    // folder was being copied.
    Experience ownWorld = new Experience(id, source.owner(), source.ownerName(), worldKey.key(), name,
        source.challenges(), false, now, source.mode(), source.worldType(), source.keepInventory(),
        source.hardcore(), source.dead(), null);
    return transact(connection -> {
      // Does the source still exist? There is no foreign key on `experiences.backup_of`, and the
      // window between the click and this insert is a multi-hundred-megabyte folder copy — long enough
      // for the owner to delete the source, which runs `promoteOrphanBackups` and finds nothing to
      // promote because this row is not written yet. The copy then landed pointing at a row that is
      // gone, and `countByOwner` excludes anything with a `backup_of`, so it counted against NOTHING,
      // for ever: create, back up, delete during the copy, repeat, and the per-player cap looks
      // healthy while the disk fills.
      //
      // It is recorded as a world OF ITS OWN rather than as a copy of a row that is gone, because
      // that is exactly what promoteOrphanBackups would have made of it a second later and it is the
      // honest description of what it now is: the only surviving record of that run. What it does NOT
      // get is a free pass on the cap. Being a world means spending a world's slot, so the count below
      // is the per-player one for this branch -- the same count, and the same refusal, a DUPLICATE of
      // the same bytes would have met.
      boolean sourceStillThere;
      try (PreparedStatement exists = connection.prepareStatement(
          "SELECT 1 FROM experiences WHERE id=?")) {
        exists.setString(1, source.id());
        try (ResultSet found = exists.executeQuery()) {
          sourceStillThere = found.next();
        }
      }
      if (sourceStillThere && cap < Integer.MAX_VALUE) {
        try (PreparedStatement counting = connection.prepareStatement(
            "SELECT COUNT(*) FROM experiences WHERE backup_of=?")) {
          counting.setString(1, source.id());
          try (ResultSet counted = counting.executeQuery()) {
            if (counted.next() && counted.getInt(1) >= Math.max(0, cap)) {
              // Rolls back and answers null: somebody else got the last slot between the click and here.
              return null;
            }
          }
        }
      }
      if (!sourceStillThere && ownWorldCap < Integer.MAX_VALUE) {
        // The predicate is `owner_uuid=? AND backup_of IS NULL`, character for character what
        // countByOwner counts, because that is the population this row is about to join.
        try (PreparedStatement counting = connection.prepareStatement(
            "SELECT COUNT(*) FROM experiences WHERE owner_uuid=? AND backup_of IS NULL")) {
          counting.setString(1, source.owner().toString());
          try (ResultSet counted = counting.executeQuery()) {
            if (counted.next() && counted.getInt(1) >= Math.max(0, ownWorldCap)) {
              return null;
            }
          }
        }
      }
      Experience row = sourceStillThere ? backup : ownWorld;
      insertRow(connection, row, carriedState, now);
      return row;
    }).orElse(null);
  }

  /**
   * Copies an experience into a row that is nobody's backup — a playable world in its own right.
   *
   * <p>Everything {@link #createBackup} carries, minus {@code backup_of}: deleting what it came from
   * changes nothing about it, and it counts against the per-player cap like any other world, because
   * that is exactly what it is.</p>
   */
  public Experience createFrom(Experience source, String displayName, String id, WorldKey worldKey,
      long now, String carriedState) {
    return createFrom(source, displayName, id, worldKey, now, carriedState, Integer.MAX_VALUE);
  }

  /**
   * {@link #createFrom(Experience, String, String, WorldKey, long, String)} with the PER-PLAYER cap
   * re-counted atomically with the insert.
   *
   * <h2>Why the cap has to be counted here and not only at the click</h2>
   *
   * <p>The duplicate verb checks {@link #countByOwner} and then copies several hundred megabytes of
   * folder tree off-thread before it gets here. That is a check-then-act with a window measured in
   * seconds: a player at 9 of 10 who clicks Duplicate on two different backups inside it passes both
   * checks, lands both copies and ends up with 11 worlds. Counting inside the same transaction as the
   * insert is what makes the cap true rather than likely — exactly as
   * {@link #createBackup(Experience, String, String, WorldKey, long, String, int)} does for the
   * per-experience one. Pass {@link Integer#MAX_VALUE} to mean "no cap here".</p>
   *
   * <p>The predicate is {@code owner_uuid=? AND backup_of IS NULL}, character for character what
   * {@link #countByOwner} counts: backups deliberately do not spend a slot, and a count here that
   * included them would refuse duplicates the owner is entitled to.</p>
   *
   * @return the stored copy, or null when it could not be written (including: the cap was reached)
   */
  public Experience createFrom(Experience source, String displayName, String id, WorldKey worldKey,
      long now, String carriedState, int cap) {
    if (database == null || source == null || id == null || worldKey == null) {
      return null;
    }
    String name = displayName == null || displayName.isBlank() ? "Experience " + id : displayName;
    Experience copy = new Experience(id, source.owner(), source.ownerName(), worldKey.key(), name,
        source.challenges(), false, now, source.mode(), source.worldType(), source.keepInventory(),
        source.hardcore(), source.dead(), null);
    return transact(connection -> {
      if (cap < Integer.MAX_VALUE) {
        try (PreparedStatement counting = connection.prepareStatement(
            "SELECT COUNT(*) FROM experiences WHERE owner_uuid=? AND backup_of IS NULL")) {
          counting.setString(1, source.owner().toString());
          try (ResultSet counted = counting.executeQuery()) {
            if (counted.next() && counted.getInt(1) >= Math.max(0, cap)) {
              // Rolls back and answers null: the owner spent their last slot between the click and here.
              return null;
            }
          }
        }
      }
      insertRow(connection, copy, carriedState, now);
      return copy;
    }).orElse(null);
  }

  /** The one INSERT both copy paths share. Runs inside a caller-owned transaction. */
  private static void insertRow(java.sql.Connection connection, Experience row, String carriedState,
      long now) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO experiences(id, owner_uuid, owner_name, world_key, display_name, challenges, is_public, created_at, updated_at, mode, world_type, keep_inventory, hardcore, dead, backup_of, challenge_state)
        VALUES (?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
      statement.setString(1, row.id());
      statement.setString(2, row.owner().toString());
      statement.setString(3, row.ownerName() == null ? "" : row.ownerName());
      statement.setString(4, row.worldKey());
      statement.setString(5, row.displayName());
      statement.setString(6, String.join(",", row.challenges()));
      statement.setLong(7, now);
      statement.setLong(8, now);
      statement.setString(9, row.mode());
      statement.setString(10, row.worldType());
      statement.setInt(11, row.keepInventory() ? 1 : 0);
      statement.setInt(12, row.hardcore() ? 1 : 0);
      statement.setInt(13, row.dead() ? 1 : 0);
      statement.setString(14, row.backupOf());
      statement.setString(15, carriedState);
      statement.executeUpdate();
    }
  }

  /**
   * The display name a copy wears when the caller has no opinion: {@code "<source> (backup)"}.
   *
   * <p>Package-visible because the ENGINE is the caller that decides, and for a while it decided by
   * accident: it passed the source's name verbatim, so this suffix never ran and the live database
   * ended up with two rows both called "Death Resets" and no way for their owner to tell which was
   * which. One spelling, in one place.</p>
   */
  static String backupDisplayName(Experience source, String displayName, String id) {
    if (displayName != null && !displayName.isBlank()) {
      return displayName;
    }
    String base = source.displayName() == null || source.displayName().isBlank()
        ? "Experience " + id : source.displayName();
    return base + " (backup)";
  }

  // ----- transactional surgery ------------------------------------------------------------------

  /** A unit of work that must either happen entirely or not at all. */
  @FunctionalInterface
  public interface SqlWork<T> {
    /** Returns the value to commit, or null to roll the whole block back. */
    T run(java.sql.Connection connection) throws SQLException;
  }

  /**
   * Runs {@code work} in one transaction, committing only if it returns non-null.
   *
   * <p>Three details are load-bearing and easy to get wrong:</p>
   *
   * <ul>
   *   <li>{@link Database#connection()} may <em>swap the Connection object</em> on a networked backend
   *       when its revalidation window lapses. So the connection is captured ONCE, and nothing inside
   *       the block may ask for it again — a second call could hand back a different connection, on
   *       which the {@code commit} would commit nothing and the {@code rollback} would roll back
   *       nothing.</li>
   *   <li>{@code database.lock()} is held for the whole block, because the single connection is shared
   *       by every subsystem and another thread's statement inside our transaction would be committed
   *       or discarded along with ours.</li>
   *   <li>{@code autoCommit} is restored in a {@code finally}. Leaking it off would silently turn every
   *       later write in the process into an uncommitted one.</li>
   * </ul>
   */
  public <T> Optional<T> transact(SqlWork<T> work) {
    if (database == null || work == null) {
      return Optional.empty();
    }
    synchronized (database.lock()) {
      java.sql.Connection connection = database.connection();
      // TRUE, which is the JDBC default and therefore the only safe guess. It used to be false, so a
      // getAutoCommit() that threw left the finally below switching autoCommit OFF on the ONE connection
      // every subsystem in this process shares -- turning every later write in the whole plugin into an
      // uncommitted one, silently, until the connection was replaced.
      boolean restore = true;
      try {
        restore = connection.getAutoCommit();
        connection.setAutoCommit(false);
        T value = work.run(connection);
        if (value == null) {
          connection.rollback();
          return Optional.empty();
        }
        connection.commit();
        return Optional.of(value);
      } catch (SQLException | RuntimeException failure) {
        try {
          connection.rollback();
        } catch (SQLException lost) {
          logger.warning("A failed experience transaction could not be rolled back", lost);
        }
        logger.warning("An experience transaction was rolled back", failure);
        return Optional.empty();
      } finally {
        try {
          connection.setAutoCommit(restore);
        } catch (SQLException ignored) {
          // Nothing useful left to do; the next connection() validation reopens it.
        }
      }
    }
  }

  /**
   * RESTORE: swaps which world folder a source experience and one of its backups name.
   *
   * <p>No bytes move. Both folders already exist, fully written, in exactly the state they need to be
   * in — so the whole restore is which row names which. Afterwards:</p>
   *
   * <ul>
   *   <li>the SOURCE row names the backup's world, and carries the settings that world was built with
   *       (challenge list, world type, keep-inventory, hardcore, {@code dead}, counters). Its
   *       {@code is_public} is deliberately untouched: who may see this experience is a property of the
   *       experience, not of the terrain under it.</li>
   *   <li>the BACKUP row names the world the source was just using, renamed {@code safetyDisplayName},
   *       forced private, and stamped {@code created_at = now} — because as a copy, that is when it was
   *       taken.</li>
   *   <li>every {@code experience_players} pointer on BOTH sides follows its own folder, in the same
   *       transaction. The invariant the whole class keeps is that a member row names the world its own
   *       {@code experience_id} names — so moving only the source's pointers is not "one side left
   *       alone", it is the backup's members left naming a world that now belongs to a different row.
   *       An owner who walked into the copy to look around and disconnected hard (so the leave path
   *       never dropped the row) would come back to {@code rememberedWorldOf} handing them the SOURCE's
   *       world, and to a saved position the entry guard rejects as foreign.</li>
   * </ul>
   *
   * <p>The count check at the end is what stands in for a unique index on a database that already
   * carries duplicates: if the two rows are not the only two naming those two worlds, nothing is
   * committed.</p>
   *
   * @return true when the swap committed; false leaves BOTH rows exactly as they were
   */
  public boolean swapWithBackup(String sourceId, String backupId, String safetyDisplayName, long now) {
    if (database == null || sourceId == null || backupId == null || sourceId.equals(backupId)) {
      return false;
    }
    Experience source = get(sourceId);
    Experience backup = get(backupId);
    if (source == null || backup == null || !sourceId.equals(backup.backupOf())) {
      return false;
    }
    String liveKey = source.worldKey();
    String copyKey = backup.worldKey();
    if (liveKey == null || copyKey == null || liveKey.equals(copyKey)) {
      return false;
    }
    String safetyName = safetyDisplayName == null || safetyDisplayName.isBlank()
        ? backup.displayName() : safetyDisplayName;
    String liveState = challengeState(sourceId);
    String copyState = challengeState(backupId);
    return Boolean.TRUE.equals(transact(connection -> {
      // Park the copy on a name nothing can collide with FIRST. Two rows may never both name one
      // world, and with a genuinely unique index the naive "source takes the copy's key" would be
      // refused outright — the copy still holds it at that instant.
      String parked = copyKey + "#swapping-" + Long.toHexString(now);
      try (PreparedStatement park = connection.prepareStatement(
          "UPDATE experiences SET world_key=? WHERE id=?")) {
        park.setString(1, parked);
        park.setString(2, backupId);
        park.executeUpdate();
      }
      try (PreparedStatement live = connection.prepareStatement("""
          UPDATE experiences SET world_key=?, challenges=?, world_type=?, keep_inventory=?, hardcore=?,
                 dead=?, challenge_state=?, updated_at=? WHERE id=?""")) {
        live.setString(1, copyKey);
        live.setString(2, String.join(",", backup.challenges()));
        live.setString(3, backup.worldType());
        live.setInt(4, backup.keepInventory() ? 1 : 0);
        live.setInt(5, backup.hardcore() ? 1 : 0);
        // The backup's own `dead`. For a copy taken BEFORE a hardcore death that is 0, which is not a
        // hole in "markDead is one-way": entering a pre-death backup is already the sanctioned way
        // back, documented and shipped. A restore only changes which row wears that world.
        live.setInt(6, backup.dead() ? 1 : 0);
        live.setString(7, copyState);
        live.setLong(8, now);
        live.setString(9, sourceId);
        live.executeUpdate();
      }
      try (PreparedStatement safety = connection.prepareStatement("""
          UPDATE experiences SET world_key=?, display_name=?, challenges=?, world_type=?,
                 keep_inventory=?, hardcore=?, dead=?, challenge_state=?, is_public=0, created_at=?,
                 updated_at=? WHERE id=?""")) {
        safety.setString(1, liveKey);
        safety.setString(2, safetyName);
        safety.setString(3, String.join(",", source.challenges()));
        safety.setString(4, source.worldType());
        safety.setInt(5, source.keepInventory() ? 1 : 0);
        safety.setInt(6, source.hardcore() ? 1 : 0);
        safety.setInt(7, source.dead() ? 1 : 0);
        safety.setString(8, liveState);
        safety.setLong(9, now);
        safety.setLong(10, now);
        safety.setString(11, backupId);
        safety.executeUpdate();
      }
      try (PreparedStatement members = connection.prepareStatement(
          "UPDATE experience_players SET world_key=?, updated_at=? WHERE experience_id=?")) {
        members.setString(1, copyKey);
        members.setLong(2, now);
        members.setString(3, sourceId);
        members.executeUpdate();
      }
      // ...and the copy's own members, symmetrically. Without this the backup's rows keep naming
      // copyKey -- which the SOURCE now runs -- so `rememberedWorldOf` answers a world belonging to
      // another row, and `loadPlayerState(backupId, ...)` reads a snapshot whose world_key is foreign
      // to the safety copy and is thrown away.
      try (PreparedStatement copyMembers = connection.prepareStatement(
          "UPDATE experience_players SET world_key=?, updated_at=? WHERE experience_id=?")) {
        copyMembers.setString(1, liveKey);
        copyMembers.setLong(2, now);
        copyMembers.setString(3, backupId);
        copyMembers.executeUpdate();
      }
      try (PreparedStatement counting = connection.prepareStatement(
          "SELECT COUNT(*) FROM experiences WHERE world_key IN (?, ?)")) {
        counting.setString(1, liveKey);
        counting.setString(2, copyKey);
        try (ResultSet counted = counting.executeQuery()) {
          if (!counted.next() || counted.getInt(1) != 2) {
            logger.severe("Refusing to restore " + backupId + " over " + sourceId
                + ": the two worlds are not named by exactly two rows. Nothing was changed.");
            return null;
          }
        }
      }
      return Boolean.TRUE;
    }).orElse(Boolean.FALSE));
  }

  /**
   * REFRESH: points an existing backup row at a freshly copied folder, keeping its id.
   *
   * <p>Its id is what anything holding on to this backup remembers, so re-pointing rather than
   * inserting is the difference between "your copy is up to date" and "you now have two copies and one
   * of them is stale". Runs BEFORE the old folder is deleted, so a failure here leaves the row naming a
   * folder that still exists.</p>
   *
   * <h2>Why it names the folder it EXPECTS to be replacing</h2>
   *
   * <p>The copy this re-point publishes started minutes earlier, and {@code expectedWorldKey} is what
   * the row said back then. In that window a RESTORE of the same backup can commit — {@link
   * #swapWithBackup} moves both rows onto each other's folders — and a blind {@code WHERE id=? AND
   * backup_of IS NOT NULL} would then re-point a row that has since taken over the world the SOURCE
   * had, leaving that folder named by nobody while the refresh goes on to delete the folder the source
   * is running now. So the update is conditional on the row still naming the folder the copy was taken
   * to replace, and zero rows means "this backup moved while we were copying": the caller removes the
   * new folder and reports a failure, having changed nothing.</p>
   *
   * <p>The member pointers follow the folder in the SAME transaction, for exactly the reason
   * {@link #swapWithBackup} moves them: the invariant is that a member row names the world its own
   * {@code experience_id} names. Without this the refresh left every {@code experience_players} row of
   * this backup naming the OLD folder — which the very next step deletes — so a member who walked into
   * the copy and disconnected hard came back to {@code rememberedWorldOf} handing them a world that no
   * longer exists.</p>
   *
   * @param expectedWorldKey the folder this backup must still name for the re-point to be valid
   */
  public boolean repointBackup(String backupId, WorldKey expectedWorldKey, WorldKey worldKey,
      String challengeState, long now) {
    if (database == null || backupId == null || worldKey == null || expectedWorldKey == null) {
      return false;
    }
    return Boolean.TRUE.equals(transact(connection -> {
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE experiences SET world_key=?, challenge_state=?, created_at=?, updated_at=?
          WHERE id=? AND backup_of IS NOT NULL AND world_key=?""")) {
        statement.setString(1, worldKey.key());
        statement.setString(2, challengeState);
        // A refreshed backup was taken NOW; leaving created_at at the first take would sort it as the
        // oldest copy in a list whose whole ordering is "when was this the world".
        statement.setLong(3, now);
        statement.setLong(4, now);
        statement.setString(5, backupId);
        statement.setString(6, expectedWorldKey.key());
        if (statement.executeUpdate() <= 0) {
          return null;
        }
      }
      // The same UPDATE rehomePlayers issues, inside this transaction because it is part of the
      // re-point and not a step after it.
      try (PreparedStatement members = connection.prepareStatement(
          "UPDATE experience_players SET world_key=?, updated_at=? WHERE experience_id=?")) {
        members.setString(1, worldKey.key());
        members.setLong(2, now);
        members.setString(3, backupId);
        members.executeUpdate();
      }
      return Boolean.TRUE;
    }).orElse(Boolean.FALSE));
  }

  /**
   * Turns every backup of a deleted experience into an experience of its own.
   *
   * <p>Without this, a copy whose source is gone keeps a dead {@code backup_of} — and
   * {@link #countByOwner} excludes anything with one, so it counts against NOTHING for ever. Create,
   * back up three times, delete the source, repeat: unbounded worlds at a few hundred megabytes an
   * iteration, with the per-player cap looking perfectly healthy the whole time.</p>
   *
   * <p>A backup that outlives its source IS a world in its own right — it is the only copy of that run
   * left — so promoting it is also the honest description of what it has become. It can push an owner
   * OVER the cap, which is a soft over-cap: the cap is only ever checked when creating, so nothing is
   * deleted and the owner simply cannot make another until they are back under it.</p>
   *
   * @return how many copies were promoted, or {@code -1} when the database could not say. The caller
   *     that forgets the rows of a deleted experience has to tell those two apart: "nothing to promote"
   *     finishes a delete, while "the UPDATE failed" leaves every copy of a world that no longer exists
   *     carrying a dead {@code backup_of} — counting against nothing, for ever — and reporting DELETED
   *     over that is how the cap stops meaning anything.
   */
  public int promoteOrphanBackups(String deletedExperienceId) {
    if (database == null || deletedExperienceId == null) {
      return 0;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "UPDATE experiences SET backup_of=NULL WHERE backup_of=?")) {
        statement.setString(1, deletedExperienceId);
        int promoted = statement.executeUpdate();
        if (promoted > 0) {
          logger.info("Promoted " + promoted + " backup(s) of the deleted experience "
              + deletedExperienceId + " to experiences of their own; they now count against the"
              + " owner's world allowance.");
        }
        return promoted;
      } catch (SQLException exception) {
        logger.warning("Failed to promote the backups of experience " + deletedExperienceId, exception);
        return -1;
      }
    }
  }

  /** How many backups this experience already has (the per-experience cap is checked against this). */
  public int countBackupsOf(String experienceId) {
    if (database == null || experienceId == null) {
      return 0;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT COUNT(*) FROM experiences WHERE backup_of=?")) {
        statement.setString(1, experienceId);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getInt(1) : 0;
        }
      } catch (SQLException exception) {
        logger.warning("Failed to count the backups of experience " + experienceId, exception);
        return 0;
      }
    }
  }

  /** Every backup taken of an experience, oldest first. */
  public List<Experience> backupsOf(String experienceId) {
    return experienceId == null ? List.of()
        // `, id` is not decoration: two copies written in the same millisecond -- a double click, or
        // two nodes -- otherwise come back in whatever order the engine feels like, so the owner's
        // list of backups can reshuffle between two renderings of the same unchanged rows.
        : query("SELECT * FROM experiences WHERE backup_of=? ORDER BY created_at, id", experienceId);
  }

  public Experience get(String id) {
    return queryOne("SELECT * FROM experiences WHERE id=?", id);
  }

  /**
   * When this experience's row was last written, or {@code 0} when there is no row (or no database).
   *
   * <p>Every mutator here already stamps {@code updated_at} and, until this existed, nothing ever
   * read it — so a node holding a live world had no way to tell that the owner had changed something
   * from the lobby. It is the version a live match compares itself against: one primary-key read,
   * cheap enough to do on every entry, instead of re-composing a challenge set to find out nothing
   * moved.</p>
   */
  public long updatedAt(String id) {
    if (database == null || id == null) {
      return 0L;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT updated_at FROM experiences WHERE id=?")) {
        statement.setString(1, id);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : 0L;
        }
      } catch (SQLException exception) {
        logger.warning("Failed to read the update stamp of experience " + id, exception);
        return 0L;
      }
    }
  }

  /** The encoded shared challenge state blob for an experience, or "" when none is stored. */
  public String challengeState(String id) {
    if (database == null || id == null) {
      return "";
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT challenge_state FROM experiences WHERE id=?")) {
        statement.setString(1, id);
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            String value = resultSet.getString(1);
            return value == null ? "" : value;
          }
        }
      } catch (SQLException exception) {
        logger.warning("Failed to load challenge state for experience " + id, exception);
      }
    }
    return "";
  }

  // ----- per-player saved position + inventory --------------------------------------------------
  // NOTE: experience state (shared challenge_state + per-player position/inventory) is now written to
  // per-experience .yml files by ExperienceStateStore. The DB read methods below (challengeState /
  // loadPlayerState) remain only as a ONE-TIME migration source for experiences created before that
  // change; the old write methods were removed. The challenge_state column + experience_players table
  // are therefore migration-read-only.

  /**
   * Record that a player is in an experience, and in WHICH world of it.
   *
   * <p>The one piece of a player's state that cannot live in the world folder, because it is what
   * tells you which world folder to look in. Everything else — inventory, position, the run's counters
   * — is written by {@link ExperienceStateStore} into the world itself, which is correct and survives
   * a node hop now that the folders are shared. This does not: a player arriving at a node has no
   * local session, and until something durable said where they were, the answer had to come from a
   * handoff row that only exists for the seconds around a transfer. Miss that window — a reconnect, a
   * node restart, a reset that renamed the world while they were offline — and they land in the
   * node's default overworld with no way to tell where they should have been.</p>
   *
   * <p>Stored per player rather than per experience because two players in one experience can be on
   * different generations of it: one online through a reset, one offline across it.</p>
   */
  public boolean rememberPlayerWorld(String experienceId, UUID playerId, WorldKey worldKey, long now) {
    if (database == null || experienceId == null || playerId == null) {
      return false;
    }
    // Delete-then-insert: the row is a statement of where the player is NOW, and an UPDATE that
    // matches nothing would silently record nothing for a player entering their first experience.
    // The two statements are also the reason this is a TRANSACTION and not two calls to `update`:
    // they ran on the shared connection with autoCommit on and BOTH exceptions swallowed, so a DELETE
    // that succeeded followed by an INSERT that did not left the player with no pointer at all --
    // which reads back as "they were never in an experience" and lands them in the node's default
    // overworld. Either both statements land or neither does. The boolean says which, but both call
    // sites drop it -- so this is an all-or-nothing write, not a reported one.
    return Boolean.TRUE.equals(transact(connection -> {
      try (PreparedStatement forget = connection.prepareStatement(
          "DELETE FROM experience_players WHERE experience_id=? AND player_uuid=?")) {
        forget.setString(1, experienceId);
        forget.setString(2, playerId.toString());
        forget.executeUpdate();
      }
      try (PreparedStatement remember = connection.prepareStatement(
          "INSERT INTO experience_players (experience_id, player_uuid, world_key, updated_at)"
              + " VALUES (?, ?, ?, ?)")) {
        remember.setString(1, experienceId);
        remember.setString(2, playerId.toString());
        remember.setString(3, worldKey == null ? null : worldKey.key());
        remember.setLong(4, now);
        remember.executeUpdate();
      }
      return Boolean.TRUE;
    }).orElse(Boolean.FALSE));
  }

  /** Drop the pointer when a player leaves an experience, so a later join does not drag them back. */
  public void forgetPlayerWorld(String experienceId, UUID playerId) {
    if (database == null || experienceId == null || playerId == null) {
      return;
    }
    update("DELETE FROM experience_players WHERE experience_id=? AND player_uuid=?", statement -> {
      statement.setString(1, experienceId);
      statement.setString(2, playerId.toString());
    });
  }

  /**
   * Drop EVERY player's pointer into an experience, because the experience itself is gone.
   *
   * <p>Only the per-player form existed, so a deleted map left one row per other member — each a
   * durable pointer into a world folder that no longer exists, read back by {@code rememberedWorldOf}
   * the next time they joined.</p>
   */
  public void forgetAllPlayers(String experienceId) {
    if (database == null || experienceId == null) {
      return;
    }
    update("DELETE FROM experience_players WHERE experience_id=?",
        statement -> statement.setString(1, experienceId));
  }

  /**
   * Move every player of an experience onto the generation that just replaced the old one.
   *
   * <p>Called by the reset. Without it the pointer keeps naming a world the teardown is about to
   * delete — the same class of drift the registry's own world column suffered, one level down.</p>
   */
  public void rehomePlayers(String experienceId, WorldKey worldKey, long now) {
    if (database == null || experienceId == null) {
      return;
    }
    update("UPDATE experience_players SET world_key=?, updated_at=? WHERE experience_id=?", statement -> {
      statement.setString(1, worldKey == null ? null : worldKey.key());
      statement.setLong(2, now);
      statement.setString(3, experienceId);
    });
  }

  /** Which experience world this player was last in, or null. */
  public WorldKey rememberedWorldOf(UUID playerId) {
    if (database == null || playerId == null) {
      return null;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT world_key FROM experience_players WHERE player_uuid=? AND world_key IS NOT NULL"
              + " ORDER BY updated_at DESC")) {
        statement.setString(1, playerId.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? WorldKey.tryParse(resultSet.getString(1)).orElse(null) : null;
        }
      } catch (SQLException exception) {
        logger.warning("Could not read the remembered world of " + playerId, exception);
        return null;
      }
    }
  }

  /** The player's saved position + inventory in an experience, or null when none is stored. */
  public PlayerSnapshot loadPlayerState(String experienceId, UUID playerId, String playerName) {
    if (database == null || experienceId == null || playerId == null) {
      return null;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT * FROM experience_players WHERE experience_id=? AND player_uuid=?")) {
        statement.setString(1, experienceId);
        statement.setString(2, playerId.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          if (!resultSet.next()) {
            return null;
          }
          PlayerSnapshot snapshot = new PlayerSnapshot(playerId, playerName, null);
          snapshot.worldName = resultSet.getString("world_key");
          snapshot.coordinateX = resultSet.getDouble("x");
          snapshot.coordinateY = resultSet.getDouble("y");
          snapshot.coordinateZ = resultSet.getDouble("z");
          snapshot.yaw = resultSet.getFloat("yaw");
          snapshot.pitch = resultSet.getFloat("pitch");
          String gameMode = resultSet.getString("gamemode");
          snapshot.gameMode = gameMode == null ? GameModeType.SURVIVAL.name() : gameMode;
          snapshot.health = resultSet.getDouble("health");
          snapshot.foodLevel = resultSet.getInt("food");
          snapshot.inventoryPayload = resultSet.getString("inv");
          return snapshot;
        }
      } catch (SQLException exception) {
        logger.warning("Failed to load experience player state for " + experienceId, exception);
        return null;
      }
    }
  }

  /** Replaces the active challenge set of an experience (owner editing which twists are running). */
  public void updateChallenges(String id, List<String> challenges, long now) {
    String csv = challenges == null ? "" : String.join(",", challenges);
    update("UPDATE experiences SET challenges=?, updated_at=? WHERE id=?", statement -> {
      statement.setString(1, csv);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /**
   * Changes which dimension an experience starts its players in. Only the plain-terrain types
   * (normal / nether / end) are meaningful here — a generated map cannot be swapped after the world
   * exists, so the caller ({@link ExperienceService#updateWorldType}) rejects those.
   */
  public void updateWorldType(String id, ExperienceWorldType worldType, long now) {
    String value = (worldType == null ? ExperienceWorldType.NORMAL : worldType).id();
    update("UPDATE experiences SET world_type=?, updated_at=? WHERE id=?", statement -> {
      statement.setString(1, value);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /** Turns the keep-inventory rule on or off for an experience (owner toggle in the GUI). */
  public void updateKeepInventory(String id, boolean keepInventory, long now) {
    update("UPDATE experiences SET keep_inventory=?, updated_at=? WHERE id=?", statement -> {
      statement.setInt(1, keepInventory ? 1 : 0);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /**
   * Turns HARDCORE on or off for an experience. Refused once the world has already been lost.
   *
   * <p>{@code dead IS NULL OR dead = 0}, not {@code dead = 0}: every row written before the column
   * existed reads NULL, and SQL's three-valued logic makes {@code NULL = 0} neither true nor false —
   * so the plain comparison silently matched nothing and the toggle did nothing at all on exactly the
   * oldest experiences. Same null-tolerant reading {@code readFlag} already applies coming the other
   * way.</p>
   */
  public void updateHardcore(String id, boolean hardcore, long now) {
    update("UPDATE experiences SET hardcore=?, updated_at=? WHERE id=? AND (dead IS NULL OR dead=0)",
        statement -> {
      statement.setInt(1, hardcore ? 1 : 0);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /**
   * Re-points an experience at a different world folder, keeping its id, owner, name and settings.
   *
   * <p>For a REGENERATED world (Death Resets). A regeneration cannot reuse the old world's name — the old
   * world is still loaded, with players standing in it, while the new one is prepared — so the new world
   * is created under the next generation name and the experience follows it here. This row is what every
   * other lookup resolves through ({@link #byWorld}, {@code ExperienceService}, the reconnect path), so
   * until it is updated the experience still points at a world that is about to be deleted.</p>
   */
  public void updateWorldKey(String id, WorldKey worldKey, long now) {
    update("UPDATE experiences SET world_key=?, updated_at=? WHERE id=?", statement -> {
      statement.setString(1, worldKey == null ? null : worldKey.key());
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /**
   * Writes the shared challenge-state blob back to the legacy {@code challenge_state} column.
   *
   * <p>Not the normal write path — {@code state.yml} in the world folder is, and it wins on load. This
   * is a CRASH NET for the one moment the file cannot be trusted: a world reset deletes the folder the
   * file lives in, so between the wipe and the new world's first flush there is nowhere on disk that
   * remembers the run's counters. Writing them here first means a server that dies in that window comes
   * back with them, because {@code ExperiencePersistence.loadState} rehydrates from this column exactly
   * when the file is missing or empty.</p>
   */
  public void updateChallengeState(String id, String encoded, long now) {
    update("UPDATE experiences SET challenge_state=?, updated_at=? WHERE id=?", statement -> {
      statement.setString(1, encoded);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /**
   * Records that a hardcore world has been LOST. One-way on purpose: the whole weight of hardcore is that
   * the death cannot be taken back, so there is no "revive" counterpart — deleting the world is the only
   * way out, and that is the owner's decision to make.
   */
  public void markDead(String id, long now) {
    update("UPDATE experiences SET dead=1, updated_at=? WHERE id=?", statement -> {
      statement.setLong(1, now);
      statement.setString(2, id);
    });
  }

  /**
   * The experience a world belongs to. An INDEX lookup on a UNIQUE column, not a scan.
   *
   * <p>What used to sit here was a canonical-TOLERANT variant: an exact lookup that missed on every
   * single start, backed by a scan of every row with a world —
   * canonicalising every row in Java, on the main thread, on the entry path — because the caller and
   * the column spelled the same world differently. One spelling makes the fallback unnecessary.</p>
   */
  public Experience byWorld(WorldKey key) {
    return key == null ? null : queryOne("SELECT * FROM experiences WHERE world_key=?", key.key());
  }

  /**
   * Which experience names a folder — as three answers, not two.
   *
   * <p>{@link #byWorld} funnels a failed read through {@link #query}, which logs a warning and answers
   * an empty list, so "nothing names this folder" and "the registry could not be asked" are the same
   * null. Every reader that only wants to DISPLAY the row can live with that. The one that cannot is
   * the refresh's last gate before {@code deletePersistent}: there, the lossy null reads as "the folder
   * is nobody's, remove it", and a lock-wait timeout or a database that just restarted would cash that
   * in over the terrain of a live experience.</p>
   *
   * <p>Same shape as {@link com.sexidium.core.world.ExperienceLocator.Home} and the same rule as
   * {@code PlacementGate.heldElsewhere}: an unanswerable question refuses.</p>
   */
  public Naming namedBy(WorldKey key) {
    if (key == null || database == null) {
      return Naming.unknown();
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT * FROM experiences WHERE world_key=?")) {
        statement.setString(1, key.key());
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? Naming.by(read(resultSet)) : Naming.nobody();
        }
      } catch (SQLException | RuntimeException unavailable) {
        logger.warning("Could not read which experience names '" + key.key() + "'", unavailable);
        return Naming.unknown();
      }
    }
  }

  /** The answer {@link #namedBy} gives: a row, nobody, or "could not ask". */
  public record Naming(boolean known, Experience experience) {

    /** This row names it. */
    public static Naming by(Experience row) {
      return new Naming(true, row);
    }

    /** Read successfully, and no row names this folder. */
    public static Naming nobody() {
      return new Naming(true, null);
    }

    /** The registry could not be read. Says nothing at all about whether the folder is anybody's. */
    public static Naming unknown() {
      return new Naming(false, null);
    }

    /** Whether a row positively names it. False both for "nobody" and for "could not ask". */
    public boolean named() {
      return known && experience != null;
    }
  }

  /**
   * The content codes a node must implement to host {@code worldKey} — the placement layer's view of
   * {@link Experience#contentCodes}.
   *
   * <p>Installed as {@code NetworkService.setContentRequirements} at startup, so the four enforcement
   * points (the door guard, {@code canHostContent}, {@code PlacementPlanner.choose} and
   * {@code adoptableHere}) all read the same single indexed lookup on {@code ux_experiences_world}.</p>
   *
   * <p><b>Never throws and never guesses.</b> It sits on the placement path, and the contract of
   * {@link com.sexidium.core.network.WorldContentRequirements} is that a lookup which cannot answer
   * degrades to "unconstrained" — a refused world is a player who cannot get into their own save,
   * which is a worse failure than the skew this gate exists to catch. A world with no registry row
   * (a temp world, a minigame map) is likewise unconstrained: it constrains nobody by construction.</p>
   */
  public List<String> contentCodesFor(String worldKey, String localDigest) {
    if (!available() || worldKey == null || worldKey.isBlank()) {
      return List.of();
    }
    try {
      Experience experience = WorldKey.tryParse(worldKey).map(this::byWorld).orElse(null);
      return experience == null ? List.of() : experience.contentCodes(localDigest);
    } catch (RuntimeException unavailable) {
      return List.of();
    }
  }

  public List<Experience> byOwner(UUID owner) {
    // `, id` for the same reason backupsOf carries one: created_at alone is not a total order, and a
    // list that reshuffles under the owner between openings is a list they cannot trust.
    return owner == null ? List.of() : query("SELECT * FROM experiences WHERE owner_uuid=? ORDER BY created_at, id", owner.toString());
  }

  /** The most recently created experience owned by the player, or null (uses {@code created_at}). */
  public Experience latestByOwner(UUID owner) {
    return owner == null ? null
        : queryOne("SELECT * FROM experiences WHERE owner_uuid=? ORDER BY created_at DESC LIMIT 1", owner.toString());
  }

  /** The most recently created experience owned by any of the given players, or null. */
  public Experience latestByOwners(java.util.Collection<UUID> owners) {
    if (database == null || owners == null || owners.isEmpty()) {
      return null;
    }
    List<String> ids = new ArrayList<>();
    for (UUID owner : owners) {
      if (owner != null) {
        ids.add(owner.toString());
      }
    }
    if (ids.isEmpty()) {
      return null;
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    String sql = "SELECT * FROM experiences WHERE owner_uuid IN (" + placeholders + ") ORDER BY created_at DESC LIMIT 1";
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        for (int index = 0; index < ids.size(); index++) {
          statement.setString(index + 1, ids.get(index));
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          if (resultSet.next()) {
            return read(resultSet);
          }
        }
      } catch (SQLException exception) {
        logger.warning("Failed to find latest experience among owners", exception);
      }
    }
    return null;
  }

  /** Every experience owned by any of the given players, newest first (for the friends' world browser). */
  public List<Experience> byOwners(java.util.Collection<UUID> owners) {
    if (database == null || owners == null || owners.isEmpty()) {
      return List.of();
    }
    List<String> ids = new ArrayList<>();
    for (UUID owner : owners) {
      if (owner != null) {
        ids.add(owner.toString());
      }
    }
    if (ids.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    String sql = "SELECT * FROM experiences WHERE owner_uuid IN (" + placeholders + ") ORDER BY created_at DESC";
    List<Experience> result = new ArrayList<>();
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        for (int index = 0; index < ids.size(); index++) {
          statement.setString(index + 1, ids.get(index));
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            result.add(read(resultSet));
          }
        }
      } catch (SQLException exception) {
        logger.warning("Failed to list experiences for owners", exception);
      }
    }
    return result;
  }

  /**
   * Number of experiences currently owned by the player (used to enforce the per-player cap).
   *
   * <p>BACKUPS DO NOT COUNT. A backup is a real experience row, so without {@code backup_of IS NULL}
   * taking a copy of a world would spend one of the owner's slots on it — and an owner at the cap
   * could not back anything up at all, which is precisely when a backup is worth having. Copies are
   * capped separately, per experience.</p>
   */
  public int countByOwner(UUID owner) {
    if (database == null || owner == null) {
      return 0;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT COUNT(*) FROM experiences WHERE owner_uuid=? AND backup_of IS NULL")) {
        statement.setString(1, owner.toString());
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getInt(1) : 0;
        }
      } catch (SQLException exception) {
        logger.warning("Failed to count experiences for " + owner, exception);
        return 0;
      }
    }
  }

  /** Public experiences owned by someone other than the viewer (for the "other players' worlds" menu). */
  public List<Experience> publicExperiencesExcluding(UUID viewer) {
    List<Experience> all = query("SELECT * FROM experiences WHERE is_public=1 ORDER BY display_name", (String) null);
    if (viewer == null) {
      return all;
    }
    List<Experience> filtered = new ArrayList<>();
    for (Experience experience : all) {
      if (!viewer.equals(experience.owner())) {
        filtered.add(experience);
      }
    }
    return filtered;
  }

  public boolean isOwner(String id, UUID player) {
    Experience experience = get(id);
    return experience != null && experience.owner().equals(player);
  }

  public void setVisibility(String id, boolean isPublic, long now) {
    update("UPDATE experiences SET is_public=?, updated_at=? WHERE id=?", statement -> {
      statement.setInt(1, isPublic ? 1 : 0);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  public void rename(String id, String displayName, long now) {
    update("UPDATE experiences SET display_name=?, updated_at=? WHERE id=?", statement -> {
      statement.setString(1, displayName);
      statement.setLong(2, now);
      statement.setString(3, id);
    });
  }

  /** What became of a row somebody asked to forget. */
  public enum Removal {
    /** The row was there and is gone. */
    DROPPED,
    /** There was no such row — which for a retried delete is success, not failure. */
    ABSENT,
    /** The database could not say. NOT the same as {@link #ABSENT}. */
    UNKNOWN
  }

  /** Permanently forgets an experience (the world deletion is the caller's responsibility). */
  public boolean delete(String id) {
    return remove(id) == Removal.DROPPED;
  }

  /**
   * {@link #delete}, as the three-way answer the delete path needs.
   *
   * <p>The boolean cannot carry it: a failed DELETE and a row that was never there both read false,
   * and the caller confirmed with {@code get(id) == null} — which ALSO answers null when the read
   * fails. So one unreadable database made "the row is gone" out of two failures in a row, and the
   * owner was told their map was deleted while it stayed in everybody's list.</p>
   */
  public Removal remove(String id) {
    if (database == null || id == null) {
      return Removal.UNKNOWN;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement("DELETE FROM experiences WHERE id=?")) {
        statement.setString(1, id);
        return statement.executeUpdate() > 0 ? Removal.DROPPED : Removal.ABSENT;
      } catch (SQLException exception) {
        logger.warning("Failed to delete experience " + id, exception);
        return Removal.UNKNOWN;
      }
    }
  }

  // ----- internals ----------------------------------------------------------------------------

  /**
   * Reduces an arbitrary nick or map name to a single safe path/world-name segment: lowercases letters,
   * keeps digits, {@code -} and {@code _}; every other run (spaces, punctuation, non-ASCII) collapses to
   * one {@code _}. Trims leading/trailing {@code _}, caps length, and falls back to {@code fallback} when
   * nothing usable remains. Lowercasing keeps the segment valid as a Minecraft dimension id (which must be
   * {@code [a-z0-9/._-]}) while staying a legal Bukkit world-name segment, so one key works on both
   * platforms; the result is always a valid, non-empty folder/world segment.
   */
  static String sanitizeSegment(String raw, String fallback) {
    if (raw == null) {
      return fallback;
    }
    StringBuilder builder = new StringBuilder(raw.length());
    boolean lastUnderscore = false;
    for (int i = 0; i < raw.length() && builder.length() < 40; i++) {
      char current = Character.toLowerCase(raw.charAt(i));
      boolean safe = (current >= 'a' && current <= 'z')
          || (current >= '0' && current <= '9') || current == '-' || current == '_';
      if (safe) {
        builder.append(current);
        lastUnderscore = false;
      } else if (!lastUnderscore) {
        builder.append('_');
        lastUnderscore = true;
      }
    }
    int start = 0;
    int end = builder.length();
    while (start < end && builder.charAt(start) == '_') {
      start++;
    }
    while (end > start && builder.charAt(end - 1) == '_') {
      end--;
    }
    String cleaned = builder.substring(start, end);
    return cleaned.isEmpty() ? fallback : cleaned;
  }

  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  private void update(String sql, Binder binder) {
    if (database == null) {
      return;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        binder.bind(statement);
        statement.executeUpdate();
      } catch (SQLException exception) {
        logger.warning("Experience update failed", exception);
      }
    }
  }

  private Experience queryOne(String sql, String key) {
    List<Experience> results = query(sql, key);
    return results.isEmpty() ? null : results.get(0);
  }

  private List<Experience> query(String sql, String key) {
    List<Experience> experiences = new ArrayList<>();
    if (database == null) {
      return experiences;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        if (key != null) {
          statement.setString(1, key);
        }
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            experiences.add(read(resultSet));
          }
        }
      } catch (SQLException exception) {
        logger.warning("Experience query failed", exception);
      }
    }
    return experiences;
  }

  /**
   * The stored keep-inventory flag. A row written before the column existed reads NULL, which must mean
   * ENABLED (the behaviour those experiences have always had), not disabled.
   */
  private static boolean readKeepInventory(ResultSet resultSet) throws SQLException {
    int value = resultSet.getInt("keep_inventory");
    return resultSet.wasNull() ? ExperienceSetup.DEFAULT.keepInventory() : value != 0;
  }

  /** A flag column that is absent on rows written before it existed, where NULL means false. */
  private static boolean readFlag(ResultSet resultSet, String column) throws SQLException {
    int value = resultSet.getInt(column);
    return !resultSet.wasNull() && value != 0;
  }

  private Experience read(ResultSet resultSet) throws SQLException {
    String challengesCsv = resultSet.getString("challenges");
    List<String> challenges = challengesCsv == null || challengesCsv.isBlank()
        ? List.of()
        : Arrays.stream(challengesCsv.split(",")).map(value -> value.trim().toLowerCase(Locale.ROOT)).filter(value -> !value.isEmpty()).toList();
    return new Experience(
        resultSet.getString("id"),
        UUID.fromString(resultSet.getString("owner_uuid")),
        resultSet.getString("owner_name"),
        resultSet.getString("world_key"),
        resultSet.getString("display_name"),
        challenges,
        resultSet.getInt("is_public") != 0,
        resultSet.getLong("created_at"),
        resultSet.getString("mode"),
        resultSet.getString("world_type"),
        readKeepInventory(resultSet),
        readFlag(resultSet, "hardcore"),
        readFlag(resultSet, "dead"),
        resultSet.getString("backup_of")
    );
  }
}
