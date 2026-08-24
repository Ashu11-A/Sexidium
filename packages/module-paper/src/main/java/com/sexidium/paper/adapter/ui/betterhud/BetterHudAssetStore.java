package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Owns the generated yml inside BetterHud's plugin folder: writes it, keeps it in step with the
 * declarations, and reports whether anything changed so the caller knows when a reload is warranted.
 *
 * <h2>Owned, not merged</h2>
 * Everything this writes goes under a {@code sexidium/} subdirectory of BetterHud's own
 * {@code huds/}, {@code layouts/}, {@code texts/} and {@code popups/} folders, and that subtree is
 * ours outright — regenerated and overwritten every boot, with files for surfaces that no longer exist
 * deleted. BetterHud picks it up because its own loader recurses into subdirectories (and skips any
 * name beginning with {@code -}), so a subfolder costs nothing and buys a clean boundary.
 *
 * <p>That boundary is the point. The provisioner this replaces installed generated content into the
 * SAME flat folders an operator edits, and had to promise never to overwrite anything as a result —
 * which meant a server that had once run an older layout kept it forever, and the documented upgrade
 * path was "delete the file and restart". Nothing outside {@code sexidium/} is touched, so both rules
 * can hold at once: operator files are never clobbered, and ours are always current.</p>
 *
 * <h2>Reload only on a real change</h2>
 * A manifest of per-file SHA-256 digests is written alongside the tree. A boot that produces byte-
 * identical output writes nothing and reports no change, because dispatching {@code betterhud reload}
 * restarts another plugin's world and a no-op boot has no business doing that.
 *
 * <h2>Two edits to BetterHud's own config</h2>
 * Both are gated behind {@code hud.betterhud.manage}, and both exist because the default is wrong for a
 * server that is driving BetterHud rather than configuring it by hand.
 *
 * <ul>
 *   <li>{@code default-hud: []} — its bundled demo hud is otherwise given to every player.</li>
 *   <li>{@code merge-boss-bar: false} — the one that keeps a readout where it was put. BetterHud draws
 *       every hud inside a boss bar, and its shader converts back to screen space by subtracting a
 *       constant baked into the resource pack at build time: {@code 10 + 17 × (bossbar-line − 1)}, i.e.
 *       the pack assumes the payload rides a bar on ONE PARTICULAR line. With merging on, BetterHud does
 *       not use its own reserved bar — it rewrites whatever boss-bar packet is passing to carry the
 *       payload instead. So the moment an Ender Dragon appears (or one of our own {@code Countdown}
 *       bars), the carrier changes: a different line, and a title that is no longer empty. The shader's
 *       constant no longer cancels anything, and every readout is displaced both down the screen and
 *       across it. Turning merging off costs one boss-bar slot — real bars render a line lower — and buys
 *       an origin that is fixed for the session, because the reserved bars are sent when the player
 *       connects, before any dragon exists.</li>
 * </ul>
 */
public final class BetterHudAssetStore {
  /** BetterHud's own convention: a file or folder starting with this is skipped by its loader. */
  static final String SKIP_PREFIX = "-";

  private static final String MANIFEST = ".sexidium-manifest";
  private static final String CONFIG_FILE = "config.yml";
  private static final String DEFAULT_HUD_KEY = "default-hud";
  /** BetterHud's switch for riding somebody else's boss bar instead of its own. See {@link #sync}. */
  static final String MERGE_BOSS_BAR_KEY = "merge-boss-bar";

  /** The object folders this driver generates into; each gets a {@code sexidium/} subdirectory. */
  private static final List<String> MANAGED_FOLDERS = List.of("texts", "layouts", "huds", "popups");

  /**
   * The hand-authored files the previous integration installed into the flat folders. Renamed out of
   * the way on first run so BetterHud skips them; the generated tree supersedes them entirely, and
   * leaving both would give it two objects claiming the id {@code sexidium_deathresets}.
   */
  private static final List<String> LEGACY_FILES = List.of(
      "texts/sexidium-overlay-text.yml",
      "layouts/sexidium-overlay-layout.yml",
      "huds/sexidium-overlay-hud.yml"
  );

  /**
   * BetterHud's bundled demo objects. Two of them cannot be taken off a player at runtime at all:
   * {@code default_compass} carries {@code default: true} inside its own yml, which no config key
   * overrides, and {@code entity_health_popup} is trigger-driven, so BetterHud shows it straight from
   * its own damage handler without consulting what the player is wearing. Renaming is the only thing
   * that stops either — and it is BetterHud's own documented mechanism, undone by renaming back.
   */
  private static final List<String> DEMO_ASSETS = List.of(
      "huds/default-hud.yml",
      "popups/entity-popup.yml",
      "compasses/default_compass.yml"
  );

  private final Path betterHudFolder;
  private final boolean disableDemoAssets;
  private final Consumer<String> log;

  public BetterHudAssetStore(Path betterHudFolder, boolean disableDemoAssets, Consumer<String> log) {
    this.betterHudFolder = betterHudFolder;
    this.disableDemoAssets = disableDemoAssets;
    this.log = log == null ? message -> { } : log;
  }

  /** Whether BetterHud has generated its folder, i.e. whether there is anywhere to write. */
  public boolean available() {
    return betterHudFolder != null && Files.isDirectory(betterHudFolder);
  }

  /**
   * Regenerates the managed tree for exactly these surfaces.
   *
   * @return true when the tree changed, which is the caller's cue to reload BetterHud. False is the
   *     steady state on every boot after the first with the same declarations.
   */
  public boolean sync(List<HudSurfaceSpec> specs) {
    if (!available()) {
      // BetterHud is not installed, or has not started yet. Nothing to do and nothing to complain
      // about: the driver is optional and the sidebar already covers every player.
      return false;
    }
    Map<String, String> desired = new LinkedHashMap<>();
    desired.put("texts/" + BetterHudAssetCompiler.MANAGED_DIR + "/font.yml",
        BetterHudAssetCompiler.fontYml());
    for (HudSurfaceSpec spec : specs == null ? List.<HudSurfaceSpec>of() : specs) {
      desired.putAll(BetterHudAssetCompiler.compile(spec));
    }

    boolean changed = writeManaged(desired);
    changed |= retireLegacyFiles();
    changed |= tuneConfig();
    if (disableDemoAssets) {
      changed |= disableDemoAssets();
    }
    return changed;
  }

  /**
   * Writes the desired tree and removes anything of ours that is no longer wanted.
   *
   * <p>The deletion half matters as much as the writing half: a challenge that is renamed or removed
   * leaves a hud object behind, and a stale object that nothing ever claims is still an object
   * BetterHud loads, validates and can fail on.</p>
   */
  private boolean writeManaged(Map<String, String> desired) {
    boolean changed = false;
    Map<String, String> previous = readManifest();
    Map<String, String> manifest = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : desired.entrySet()) {
      String relative = entry.getKey();
      String contents = entry.getValue();
      String digest = sha256(contents);
      manifest.put(relative, digest);
      Path destination = betterHudFolder.resolve(relative);
      if (digest.equals(previous.get(relative)) && Files.isRegularFile(destination)) {
        continue;
      }
      try {
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
        changed = true;
      } catch (IOException | RuntimeException exception) {
        log.accept("Could not write " + relative + " into BetterHud (" + exception.getMessage()
            + "); that surface falls back to the scoreboard sidebar.");
        manifest.remove(relative);
      }
    }
    changed |= removeStale(desired.keySet());
    if (changed) {
      writeManifest(manifest);
    }
    return changed;
  }

  /** Deletes every file in the managed subtree that is not in the desired set. */
  private boolean removeStale(Set<String> desired) {
    boolean changed = false;
    for (String folder : MANAGED_FOLDERS) {
      Path managed = betterHudFolder.resolve(folder).resolve(BetterHudAssetCompiler.MANAGED_DIR);
      if (!Files.isDirectory(managed)) {
        continue;
      }
      List<Path> present;
      try (Stream<Path> walk = Files.list(managed)) {
        present = walk.filter(Files::isRegularFile).sorted(Comparator.naturalOrder()).toList();
      } catch (IOException exception) {
        log.accept("Could not list " + folder + "/" + BetterHudAssetCompiler.MANAGED_DIR
            + " in BetterHud: " + exception.getMessage());
        continue;
      }
      for (Path file : present) {
        String relative = folder + "/" + BetterHudAssetCompiler.MANAGED_DIR + "/"
            + file.getFileName();
        if (desired.contains(relative)) {
          continue;
        }
        try {
          Files.delete(file);
          changed = true;
        } catch (IOException exception) {
          log.accept("Could not remove the stale generated file " + relative + ": "
              + exception.getMessage());
        }
      }
    }
    return changed;
  }

  /**
   * Renames the previous integration's hand-installed files out of the way, once.
   *
   * <p>Renamed rather than deleted, and never onto an existing target, on the same terms as the demo
   * assets: an operator who edited one still has it, and dropping the {@code -} brings it back.</p>
   */
  private boolean retireLegacyFiles() {
    List<String> retired = new ArrayList<>();
    for (String relative : LEGACY_FILES) {
      Path source = betterHudFolder.resolve(relative);
      Path target = source.resolveSibling(SKIP_PREFIX + source.getFileName());
      if (!Files.isRegularFile(source) || Files.exists(target)) {
        continue;
      }
      try {
        Files.move(source, target);
        retired.add(relative);
      } catch (IOException | RuntimeException exception) {
        log.accept("Could not retire the old hand-installed " + relative + " ("
            + exception.getMessage() + "); it may still claim the same object id as the generated one.");
      }
    }
    if (!retired.isEmpty()) {
      log.accept("Retired the previously hand-installed BetterHud files, now generated instead: "
          + String.join(", ", retired) + ". They were renamed with a leading '-' so BetterHud skips"
          + " them; delete them once you are happy with the generated layouts.");
    }
    return !retired.isEmpty();
  }

  /** Renames BetterHud's bundled demo objects so its loader skips them. */
  private boolean disableDemoAssets() {
    List<String> disabled = new ArrayList<>();
    for (String relative : DEMO_ASSETS) {
      Path source = betterHudFolder.resolve(relative);
      Path target = source.resolveSibling(SKIP_PREFIX + source.getFileName());
      if (!Files.isRegularFile(source) || Files.exists(target)) {
        continue;
      }
      try {
        Files.move(source, target);
        disabled.add(relative);
      } catch (IOException | RuntimeException exception) {
        log.accept("Could not disable BetterHud's " + relative + " (" + exception.getMessage()
            + "); it may still be drawn on top of Sexidium's own surfaces.");
      }
    }
    if (!disabled.isEmpty()) {
      log.accept("Disabled BetterHud's bundled demo objects by renaming them out of the way: "
          + String.join(", ", disabled) + ". To bring one back, rename it (drop the leading '-') and"
          + " set hud.betterhud.disable-demo-assets to false so it is not disabled again on boot.");
    }
    return !disabled.isEmpty();
  }

  /**
   * The two edits this driver needs in BetterHud's own {@code config.yml}, applied in one pass.
   *
   * <p>Line-based rather than a YAML round-trip because BetterHud's config is heavily commented and a
   * re-serialize would strip the lot. One pass rather than two so a boot that changes both keys rewrites
   * the file once.</p>
   *
   * @return true when the file was rewritten
   */
  private boolean tuneConfig() {
    Path config = betterHudFolder.resolve(CONFIG_FILE);
    if (!Files.isRegularFile(config)) {
      return false;
    }
    List<String> lines;
    try {
      lines = Files.readAllLines(config, StandardCharsets.UTF_8);
    } catch (IOException exception) {
      log.accept("Could not read BetterHud's config.yml (" + exception.getMessage() + "), so its demo "
          + "hud may still be shown to every player and its huds may drift when a boss bar appears.");
      return false;
    }
    List<String> rewritten = new ArrayList<>(lines.size());
    List<String> notes = new ArrayList<>();
    boolean sawMerge = false;
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (line.startsWith(MERGE_BOSS_BAR_KEY + ":")) {
        sawMerge = true;
        if (isFalse(line.substring(MERGE_BOSS_BAR_KEY.length() + 1))) {
          rewritten.add(line); // already pinned — the steady state
          continue;
        }
        rewritten.add(MERGE_BOSS_BAR_KEY + ": false");
        notes.add(MERGE_BOSS_BAR_KEY);
        continue;
      }
      if (!line.startsWith(DEFAULT_HUD_KEY + ":")) {
        rewritten.add(line);
        continue;
      }
      String value = line.substring(DEFAULT_HUD_KEY.length() + 1).trim();
      if (value.isEmpty()) {
        // Block form. Count what follows before deciding: an entry-less block is already empty, and
        // rewriting it would churn the file for nothing on a server already in the right state.
        int end = index + 1;
        while (end < lines.size() && isListEntry(lines.get(end))) {
          end++;
        }
        if (end == index + 1) {
          rewritten.add(line);
          continue;
        }
        rewritten.add(DEFAULT_HUD_KEY + ": []");
        index = end - 1;
        notes.add(DEFAULT_HUD_KEY);
        continue;
      }
      if (value.startsWith("[]")) {
        // Already empty — the steady state. startsWith rather than equals so a trailing comment is
        // not mistaken for an entry.
        rewritten.add(line);
        continue;
      }
      rewritten.add(DEFAULT_HUD_KEY + ": []");
      notes.add(DEFAULT_HUD_KEY);
    }
    if (!sawMerge) {
      // Absent is not neutral: BetterHud defaults this key to TRUE, so a config an operator has trimmed
      // is in exactly the state that moves the readouts. Append it rather than assume.
      rewritten.add(MERGE_BOSS_BAR_KEY + ": false");
      notes.add(MERGE_BOSS_BAR_KEY);
    }
    if (notes.isEmpty()) {
      return false;
    }
    try {
      Files.write(config, String.join(System.lineSeparator(), rewritten)
          .concat(System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
      if (notes.contains(DEFAULT_HUD_KEY)) {
        log.accept("Emptied BetterHud's 'default-hud' list: its bundled demo hud was being shown to"
            + " every player. Set hud.betterhud.manage to false to manage BetterHud's config yourself.");
      }
      if (notes.contains(MERGE_BOSS_BAR_KEY)) {
        log.accept("Set BetterHud's 'merge-boss-bar' to false so its huds keep a fixed position: with it"
            + " on, the hud rides whichever boss bar happens to be on screen, and an Ender Dragon (or a"
            + " Sexidium countdown) moves every readout with it. Set hud.betterhud.manage to false to"
            + " manage BetterHud's config yourself.");
      }
      return true;
    } catch (IOException exception) {
      log.accept("Could not rewrite BetterHud's config.yml (" + exception.getMessage() + "), so its "
          + "demo hud may still be shown to every player and its huds may drift when a boss bar appears.");
      return false;
    }
  }

  /** Whether a scalar value (with any trailing comment) reads as {@code false}. */
  private static boolean isFalse(String rawValue) {
    String value = rawValue.trim();
    int comment = value.indexOf('#');
    if (comment >= 0) {
      value = value.substring(0, comment).trim();
    }
    return value.equalsIgnoreCase("false");
  }

  /** A YAML block-list item under the key we just emptied ({@code   - test_hud}). */
  private static boolean isListEntry(String line) {
    String trimmed = line.trim();
    return line.startsWith(" ") && trimmed.startsWith("-");
  }

  private Map<String, String> readManifest() {
    Map<String, String> manifest = new LinkedHashMap<>();
    Path path = betterHudFolder.resolve(MANIFEST);
    if (!Files.isRegularFile(path)) {
      return manifest;
    }
    try {
      for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
        int separator = line.indexOf(' ');
        if (separator > 0) {
          manifest.put(line.substring(separator + 1), line.substring(0, separator));
        }
      }
    } catch (IOException exception) {
      // An unreadable manifest is not a failure, only a lost optimisation: every file is rewritten
      // and the reload happens once more than it strictly had to.
      log.accept("Could not read the generated-asset manifest (" + exception.getMessage()
          + "); regenerating everything.");
    }
    return manifest;
  }

  private void writeManifest(Map<String, String> manifest) {
    StringBuilder builder = new StringBuilder();
    for (Map.Entry<String, String> entry : manifest.entrySet()) {
      builder.append(entry.getValue()).append(' ').append(entry.getKey()).append('\n');
    }
    try {
      Files.writeString(betterHudFolder.resolve(MANIFEST), builder.toString(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      log.accept("Could not write the generated-asset manifest (" + exception.getMessage()
          + "); BetterHud will be reloaded again on the next boot.");
    }
  }

  private static String sha256(String contents) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(contents.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      // SHA-256 is required of every JVM; if it is genuinely missing, a broken manifest is the least
      // of the problems, so fail loudly rather than silently disabling change detection.
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Visible for tests: the paths this store would consider its own inside {@code folder}. */
  static Set<String> managedFolders() {
    return new HashSet<>(MANAGED_FOLDERS);
  }
}
