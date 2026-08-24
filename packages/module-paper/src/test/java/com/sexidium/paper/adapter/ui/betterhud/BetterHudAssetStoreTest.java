package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The store writes into another plugin's folder, so what it must NOT do matters as much as what it
 * must. These pin both halves: the generated subtree is always current, and nothing outside it is ever
 * touched.
 */
class BetterHudAssetStoreTest {
  private static final HudSurfaceSpec SPEC = HudSurfaceSpec.persistent("deathresets")
      .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
      .build();

  private final List<String> log = new ArrayList<>();

  private BetterHudAssetStore store(Path folder) {
    return new BetterHudAssetStore(folder, true, log::add);
  }

  @Test
  void aFreshInstallWritesTheTreeAndReportsAChange(@TempDir Path folder) {
    assertTrue(store(folder).sync(List.of(SPEC)), "a first boot must ask for a reload");

    assertTrue(Files.isRegularFile(folder.resolve("texts/sexidium/font.yml")));
    assertTrue(Files.isRegularFile(folder.resolve("layouts/sexidium/deathresets.yml")));
    assertTrue(Files.isRegularFile(folder.resolve("huds/sexidium/deathresets.yml")));
  }

  /**
   * The steady state. Reloading BetterHud restarts another plugin's world, so a boot that produces
   * byte-identical output must be silent about it.
   */
  @Test
  void asecondBootWithTheSameDeclarationsChangesNothing(@TempDir Path folder) {
    store(folder).sync(List.of(SPEC));

    assertFalse(store(folder).sync(List.of(SPEC)),
        "an unchanged tree must not dispatch a reload of another plugin");
  }

  /** A changed declaration must actually reach disk, or the readout silently keeps the old shape. */
  @Test
  void aChangedDeclarationIsRewritten(@TempDir Path folder) throws IOException {
    store(folder).sync(List.of(SPEC));

    HudSurfaceSpec extended = HudSurfaceSpec.persistent("deathresets")
        .text("duration", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DURATION))
        .text("days", LocalizedText.of(MessageKey.EXPERIENCE_DEATHRESETS_DAYS))
        .build();

    assertTrue(store(folder).sync(List.of(extended)));
    assertTrue(Files.readString(folder.resolve("layouts/sexidium/deathresets.yml")).contains("days"));
  }

  /**
   * A surface that no longer exists must have its files removed. A stale object is not inert: BetterHud
   * still loads and validates it, and it still claims an id.
   */
  @Test
  void aRemovedSurfaceHasItsGeneratedFilesDeleted(@TempDir Path folder) {
    store(folder).sync(List.of(SPEC));

    assertTrue(store(folder).sync(List.of()));
    assertFalse(Files.exists(folder.resolve("huds/sexidium/deathresets.yml")));
    assertFalse(Files.exists(folder.resolve("layouts/sexidium/deathresets.yml")));
    assertTrue(Files.exists(folder.resolve("texts/sexidium/font.yml")),
        "the shared font is not tied to any one surface");
  }

  /**
   * The boundary that lets the store own its subtree outright: an operator's own files live in the
   * flat folders and are never candidates for anything.
   */
  @Test
  void operatorFilesOutsideTheManagedSubtreeAreNeverTouched(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("huds"));
    Path operator = folder.resolve("huds/my-own-hud.yml");
    Files.writeString(operator, "my_hud:\n  layouts: {}\n");

    store(folder).sync(List.of(SPEC));
    store(folder).sync(List.of());

    assertEquals("my_hud:\n  layouts: {}\n", Files.readString(operator),
        "the store must only ever be able to undo its own work");
  }

  /**
   * The previous integration hand-installed three flat files. Left in place they would claim the same
   * object ids as the generated ones, so they are renamed with the leading hyphen BetterHud's own
   * loader skips — moved, never deleted, so an operator's edits are recoverable.
   */
  @Test
  void theOldHandInstalledFilesAreRetiredOnce(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("layouts"));
    Path legacy = folder.resolve("layouts/sexidium-overlay-layout.yml");
    Files.writeString(legacy, "sexidium_deathresets_layout:\n");

    assertTrue(store(folder).sync(List.of(SPEC)));
    assertFalse(Files.exists(legacy), "the old file must stop being loaded");
    assertTrue(Files.isRegularFile(folder.resolve("layouts/-sexidium-overlay-layout.yml")),
        "renamed, not deleted");

    assertFalse(store(folder).sync(List.of(SPEC)), "retiring is one-shot, not churn on every boot");
  }

  /** BetterHud's demo hud rides along on every player otherwise. */
  @Test
  void theDemoAssetsAreRenamedOutOfTheWay(@TempDir Path folder) throws IOException {
    Files.createDirectories(folder.resolve("compasses"));
    Files.writeString(folder.resolve("compasses/default_compass.yml"), "default_compass:\n  default: true\n");

    assertTrue(store(folder).sync(List.of(SPEC)));
    assertTrue(Files.isRegularFile(folder.resolve("compasses/-default_compass.yml")));
  }

  /** The line-based rewrite has to preserve BetterHud's heavy commenting, not re-serialize it away. */
  @Test
  void emptyingTheDefaultHudListKeepsEveryOtherByte(@TempDir Path folder) throws IOException {
    Files.writeString(folder.resolve("config.yml"),
        "# a comment worth keeping\ndebug: false\ndefault-hud: [test_hud]\nnamespace: betterhud\n");

    assertTrue(store(folder).sync(List.of(SPEC)));

    String config = Files.readString(folder.resolve("config.yml"));
    assertTrue(config.contains("# a comment worth keeping"), config);
    assertTrue(config.contains("default-hud: []"), config);
    assertTrue(config.contains("namespace: betterhud"), config);
  }

  /**
   * The readouts have to stay where they were put when a boss bar appears.
   *
   * <p>BetterHud draws a hud inside a boss bar and converts back to screen space by subtracting a
   * constant the resource pack bakes in for one assumed bar line. With {@code merge-boss-bar} on it does
   * not use its own reserved bar — it rewrites whatever boss-bar packet is passing to carry the payload —
   * so an Ender Dragon, or one of our own countdown bars, becomes the carrier on a different line and
   * drags every readout off its anchor with it.</p>
   */
  @Test
  void bossBarMergingIsTurnedOffSoTheHudCannotBeDraggedAroundByOne(@TempDir Path folder)
      throws IOException {
    Files.writeString(folder.resolve("config.yml"),
        "# a comment worth keeping\nbossbar-line: 1\nmerge-boss-bar: true\nnamespace: betterhud\n");

    assertTrue(store(folder).sync(List.of(SPEC)));

    String config = Files.readString(folder.resolve("config.yml"));
    assertTrue(config.contains("merge-boss-bar: false"), config);
    assertTrue(config.contains("# a comment worth keeping"), config);
    assertTrue(config.contains("bossbar-line: 1"),
        "the line the pack's constant is built for is the operator's to choose; only merging is ours");
  }

  /**
   * An absent key is not a neutral one: BetterHud defaults {@code merge-boss-bar} to TRUE, so a config an
   * operator has trimmed is already in the state that moves the readouts. It has to be written in.
   */
  @Test
  void anAbsentMergeKeyIsWrittenRatherThanAssumed(@TempDir Path folder) throws IOException {
    Files.writeString(folder.resolve("config.yml"), "debug: false\ndefault-hud: []\n");

    assertTrue(store(folder).sync(List.of(SPEC)));

    assertTrue(Files.readString(folder.resolve("config.yml")).contains("merge-boss-bar: false"));
  }

  /**
   * A server already in the right state must not be rewritten — the return value is what dispatches
   * {@code betterhud reload}, which restarts another plugin's world.
   */
  @Test
  void anAlreadyPinnedConfigIsLeftAlone(@TempDir Path folder) throws IOException {
    String settled = "default-hud: []\nmerge-boss-bar: false  # pinned by Sexidium\n";
    Files.writeString(folder.resolve("config.yml"), settled);

    store(folder).sync(List.of(SPEC));           // first run writes the generated tree
    log.clear();
    assertFalse(store(folder).sync(List.of(SPEC)),
        "a second boot with the same declarations has nothing to write and no reason to reload");
    assertEquals(settled, Files.readString(folder.resolve("config.yml")),
        "the trailing comment is the operator's; a no-op pass must not touch the file at all");
  }

  /** No BetterHud, nothing to do, and nothing to complain about — the driver is optional by design. */
  @Test
  void aMissingBetterHudFolderIsSilentlyNothing(@TempDir Path folder) {
    BetterHudAssetStore store = store(folder.resolve("not-installed"));

    assertFalse(store.available());
    assertFalse(store.sync(List.of(SPEC)));
    assertTrue(log.isEmpty(), "an absent optional plugin is not a problem to report: " + log);
  }
}
