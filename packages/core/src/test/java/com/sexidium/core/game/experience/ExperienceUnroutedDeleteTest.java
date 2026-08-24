package com.sexidium.core.game.experience;

import com.sexidium.core.TestServerAdapter;
import com.sexidium.core.data.RankAwardPort;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.GameManager;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.network.NetworkBus;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.InventoryAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.MessageAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import com.sexidium.core.world.ExperienceLocator;
import com.sexidium.core.world.WorldKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Nothing records where this world lives" is not "there is no folder anywhere".
 *
 * <p>The delete path treated the two as one, on a premise written down in {@code ExperienceLocator}:
 * a world only ever gets a {@code world_placements} row by being opened or created, so a world with
 * no placement has no folder to orphan. That premise is false for every BACKUP and every DUPLICATE.
 * {@code ExperienceBackupService.copyInto} builds the folders through
 * {@code WorldLeaseService.copyExperienceWorld}, which never touches the placement gate, and then
 * writes only the {@code experiences} row — and with {@code network.shared-world-storage} on (the
 * default) the reconciler skips the adopt that would have made one.</p>
 *
 * <p>So the lobby, which is where every owner clicks Delete from, would drop the rows, report
 * DELETED, and leave several hundred megabytes that nothing names and no sweep can ever find: every
 * collector in this codebase needs a row to start from.</p>
 */
class ExperienceUnroutedDeleteTest {

  private static final LoggerAdapter SILENT = new LoggerAdapter() {
    @Override public void info(String message) { }
    @Override public void warning(String message) { }
    @Override public void severe(String message) { }
    @Override public void warning(String message, Throwable throwable) { }
    @Override public void severe(String message, Throwable throwable) { }
  };

  /** A placement table that reads cleanly and holds nothing — the answer that used to license a wipe. */
  private static final ExperienceLocator NOTHING_RECORDED = new ExperienceLocator() {
    @Override public Optional<Placement> locate(WorldKey key) {
      return Optional.empty();
    }

    @Override public Home home(WorldKey key) {
      return Home.none();
    }

    @Override public List<Placement> heldBy(String nodeId) {
      return List.of();
    }

    @Override public Optional<WorldKey> keyOf(String experienceId) {
      return Optional.empty();
    }
  };

  private static final NetworkBus QUIET = new NetworkBus() {
    @Override public void start() { }

    @Override public void publish(String topic, String key, String payload) { }

    @Override public AutoCloseable subscribe(String topic, BusListener listener) { return () -> { }; }

    @Override public void close() { }
  };

  @TempDir
  Path tmp;

  private ExperienceManager experiences;
  private ExperienceService service;
  private FakeExperienceWorlds worlds;
  private ExperienceManager.Experience backup;
  private final UUID owner = UUID.randomUUID();
  private final FakePlayer ownerPlayer = new FakePlayer(owner);
  private final List<MessageKey> told = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    Database database = new Database(new File(tmp.toFile(), "unrouted.db"));
    experiences = new ExperienceManager(SILENT, database);
    worlds = new FakeExperienceWorlds();
    // The owner is STANDING HERE, which is the whole point of the second half of this class: the
    // instruction that gets them out of this state has to reach them and not only the console.
    MessageAdapter messages = new MessageAdapter() {
      @Override public void send(CommandSource source, LocalizedText text) {
        told.add(text.messageKey());
      }

      @Override public void send(CommandSource source, String message) { }

      @Override public void raw(CommandSource source, LocalizedText text) { }

      @Override public void raw(CommandSource source, String message) { }

      @Override public void broadcast(LocalizedText text) { }

      @Override public void broadcast(String message) { }
    };
    TestServerAdapter server = new TestServerAdapter() {
      @Override public LoggerAdapter logger() { return SILENT; }
      @Override public WorldLeaseService worlds() { return worlds; }
      @Override public MessageAdapter messages() { return messages; }
      @Override public Optional<PlayerAdapter> player(UUID id) {
        return owner.equals(id) ? Optional.of(ownerPlayer) : Optional.empty();
      }
    };
    GameManager games = new GameManager(
        new GameContext(server, new NoopKitAdapter(), RankAwardPort.noop()), new GameRegistry(), null);
    service = new ExperienceService(server, games, experiences, null, null);
    // The LOBBY: it can read the placement table, and it may not open experience worlds. Both halves
    // matter — this is the only node an owner ever reaches the manage screen from.
    service.commands().attach(NOTHING_RECORDED, QUIET, "lobby-1", null, () -> false);

    ExperienceManager.Experience source = experiences.create(owner, "Ashu11a",
        List.of("randomdrops"), "Death Resets", System.currentTimeMillis());
    assertNotNull(source);
    String id = ExperienceManager.newExperienceId();
    // A BACKUP: folders on the shared tree, a registry row, and no placement row anywhere.
    backup = experiences.createBackup(source, "Death Resets (backup)", id,
        WorldKey.of("Death Resets", id), System.currentTimeMillis(), null, Integer.MAX_VALUE);
    assertNotNull(backup);
  }

  @Test
  @DisplayName("a delete nobody can be asked to run is REFUSED, and the rows survive")
  void anUnroutedDeleteKeepsTheRows() {
    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.REFUSED), outcomes,
        "REFUSED is a click the owner repeats. DELETED over a folder still on disk is a copy nothing"
            + " will ever name again, and the owner never finds out");
    assertNotNull(experiences.get(backup.id()),
        "while the row exists the world is still reachable, still listable, and still deletable by"
            + " the node that ends up holding it");
    assertTrue(worlds.deleted.isEmpty(), "and nothing on disk was touched from here");
  }

  @Test
  @DisplayName("the node that CAN open the world still deletes it for real")
  void aNodeThatHoldsTheWorldStillDeletes() {
    // Same locator, same "nothing recorded" answer — the difference is I3's door. A worker may open
    // experience worlds, so it runs the delete itself and the folder goes with the row.
    service.commands().attach(NOTHING_RECORDED, QUIET, "worker-1", null, () -> true);

    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.DELETED), outcomes);
    assertEquals(List.of(backup.worldKey()), worlds.deleted,
        "the folder goes first and the row goes second, which is what makes it collectable at all");
  }

  @Test
  @DisplayName("a node that COULD run it is asked, rather than refusing the owner for good")
  void anUnplacedDeleteIsRoutedToACapableNode() {
    // What the live fleet answers: nothing records this world, and worker-2 can host experience
    // worlds and sees the same shared tree. See NetworkService.homeForUnplacedWorld.
    List<WorldKey> asked = new ArrayList<>();
    service.commands().attachHomeFinder(key -> {
      asked.add(key);
      return "worker-2";
    });

    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    told.clear();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.QUEUED), outcomes,
        "the request is on its way to the node that can run it; REFUSED here was a dead end the"
            + " owner could only leave by entering a world they are trying to throw away");
    assertEquals(List.of(backup.key()), asked, "asked about the world being deleted, and once");
    assertNotNull(experiences.get(backup.id()),
        "the rows go when the holder says the folder went, and not one moment sooner");
    assertTrue(worlds.deleted.isEmpty(),
        "the lobby still touches nothing on disk -- it may not open these worlds, which is why it is"
            + " asking somebody else in the first place");
    assertFalse(told.contains(MessageKey.EXPERIENCE_DELETE_UNPLACED),
        "there is nothing for the owner to fix by hand any more: " + told);
  }

  @Test
  @DisplayName("no capable node, or storage the nodes do not share, keeps the old refusal")
  void aFinderThatAnswersNothingChangesNothing() {
    // Both of NetworkService.homeForUnplacedWorld's declines look like this. On per-node disks the
    // folder is on exactly one of them and nothing here knows which -- and a delete sent to the
    // wrong node deletes nothing and reports success, because a folder that was never there deletes
    // cleanly. Refusing stays recoverable; guessing is not.
    service.commands().attachHomeFinder(key -> null);

    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    told.clear();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.REFUSED), outcomes);
    assertNotNull(experiences.get(backup.id()));
    assertTrue(worlds.deleted.isEmpty());
    assertTrue(told.contains(MessageKey.EXPERIENCE_DELETE_UNPLACED),
        "and the owner still gets the instruction that does work here: " + told);
  }

  @Test
  @DisplayName("a finder that throws leaves the request exactly where it was")
  void aFinderThatThrowsIsNotAnAnswer() {
    service.commands().attachHomeFinder(key -> {
      throw new IllegalStateException("the registry blinked");
    });

    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.REFUSED), outcomes,
        "a lookup that failed has not named a node, and routing a folder delete at a guess is how it"
            + " reaches a machine that cannot see the folder");
    assertNotNull(experiences.get(backup.id()));
    assertTrue(worlds.deleted.isEmpty());
  }

  @Test
  @DisplayName("a node that holds the world is never second-guessed by the fallback")
  void theFallbackNeverOverridesARecordedHome() {
    List<WorldKey> asked = new ArrayList<>();
    service.commands().attachHomeFinder(key -> {
      asked.add(key);
      return "worker-2";
    });
    // A worker, which may open experience worlds: it runs the delete itself and the fallback -- whose
    // entire premise is "this node cannot do it and nobody is recorded" -- must stay out of the way.
    service.commands().attach(NOTHING_RECORDED, QUIET, "worker-1", null, () -> true);

    List<ExperienceService.DeleteOutcome> outcomes = new ArrayList<>();
    service.delete(owner, backup.id(), outcomes::add);

    assertEquals(List.of(ExperienceService.DeleteOutcome.DELETED), outcomes);
    assertEquals(List.of(backup.worldKey()), worlds.deleted);
    assertTrue(asked.isEmpty(),
        "the work was doable right here; asking the fleet would have sent it on a round trip to"
            + " arrive at the same place");
  }

  @Test
  @DisplayName("the owner is told the way OUT, not that somebody may be inside")
  void theOwnerIsToldHowToClearIt() {
    told.clear();
    service.delete(owner, backup.id(), outcome -> { });

    assertTrue(told.contains(MessageKey.EXPERIENCE_DELETE_UNPLACED),
        "the only instruction that works — enter the world once so it is placed — used to live in a"
            + " logger.severe the player never reads: " + told);
    assertFalse(told.contains(MessageKey.EXPERIENCE_DELETE_BUSY),
        "nobody is inside a world no node is even holding, and 'try again in a moment' never comes"
            + " true here: this state is stable until a placement row exists");
  }

  /** The owner, present on this node, so a refusal has somebody to explain itself to. */
  private static final class FakePlayer implements PlayerAdapter {
    private final UUID id;

    FakePlayer(UUID id) {
      this.id = id;
    }

    @Override public UUID uniqueId() { return id; }
    @Override public String name() { return "Ashu11a"; }
    @Override public Locale locale() { return Locale.ENGLISH; }
    @Override public boolean online() { return true; }
    @Override public boolean dead() { return false; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public void sendMiniMessage(String miniMessage) { }
    @Override public void sendPlainMessage(String message) { }
    @Override public WorldAdapter world() { return null; }
    @Override public WorldPosition position() { return null; }
    @Override public void teleport(WorldPosition targetPosition) { }
    @Override public GameModeType gameMode() { return GameModeType.SURVIVAL; }
    @Override public void setGameMode(GameModeType gameModeType) { }
    @Override public double health() { return 20.0; }
    @Override public double maxHealth() { return 20.0; }
    @Override public void setHealth(double health) { }
    @Override public int foodLevel() { return 20; }
    @Override public void setFoodLevel(int foodLevel) { }
    @Override public InventoryAdapter inventory() { return null; }
    @Override public void playSound(SoundKey soundKey, float volume, float pitch) { }
    @Override public void showTitle(TitleSpec titleSpec) { }
    @Override public void sendActionBar(String miniMessage) { }
    @Override public void setCompassTarget(WorldPosition targetPosition) { }
    @Override public void clearInventory() { }
    @Override public void clearPotionEffects() { }
  }
}
