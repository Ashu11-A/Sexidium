package com.sexidium.core.world;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.ResourceAdapter;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Command-line entry point that seeds the jar's bundled map templates into a directory and exits, so the
 * provisioning container can populate a shared map folder <em>before</em> any backend starts.
 *
 * <h2>Why this exists at all</h2>
 *
 * <p>Until now the only thing that ever ran {@link MapBundle#seedBundledMaps} was a running Paper server
 * ({@code PaperLobbyBootstrap.provisionBundledMaps}). That is fine while every node owns its own copy of
 * the templates, and wrong the moment they share one: the writers would then be N game servers, racing,
 * each with players online, with no ordering between them — which is precisely the failure class the
 * shared folder is supposed to remove. The provisioner already has the ordering barrier
 * ({@code run/.provisioned}, honoured by {@code node-entry.sh}), it simply had no way to <em>call</em>
 * the seeding code, because that code lived behind a plugin bootstrap. This class is that call.</p>
 *
 * <p>It needs nothing but a JVM. Extraction is {@code java.util.zip} inside this process (see
 * {@link MapBundle}), so no {@code unzip}, {@code tar} or any other binary has to exist in the image; and
 * because it runs the same {@code MapBundle} the servers run, there is exactly one implementation of the
 * seed/refresh/adopt rules — reimplementing the manifest comparison in shell would have made the rule
 * true in two languages and eventually in neither.</p>
 *
 * <h2>Interface (stable — {@code docker/provision.sh} depends on it)</h2>
 *
 * <pre>
 *   java -cp &lt;sexidium.jar&gt; com.sexidium.core.world.MapBundleCli &lt;maps-dir&gt; [flags]
 *
 *   &lt;maps-dir&gt;      where the map folders live, i.e. what a server would call its world root.
 *                   Created if missing. May also be given as SEXIDIUM_MAPS_DIR.
 *   --no-refresh    never replace a map folder that already has world data, even when the bundled
 *                   copy changed (the config `worlds.map-bundle.refresh-when-changed: false` rule).
 *                   Also settable as SEXIDIUM_MAP_BUNDLE_REFRESH=false.
 *   --exit-code     report "nothing to do" as {@value #EXIT_NOTHING_TO_DO} instead of 0 (see below).
 *   --quiet         only warnings and the final summary line.
 *   --help          print this interface and exit 0.
 * </pre>
 *
 * <h2>Exit codes</h2>
 *
 * <ul>
 *   <li>{@value #EXIT_OK} — success. By default this covers both "wrote maps" and "everything was
 *       already current", because the second is the <em>steady state</em>: every re-provision after the
 *       first one is a no-op, and a shell running under {@code set -e} must not treat the normal case as
 *       an error.</li>
 *   <li>{@value #EXIT_NOTHING_TO_DO} — nothing was written. Only ever returned with {@code --exit-code},
 *       for a caller that wants to skip a follow-up step (a restart, a log line) when nothing changed.
 *       Modelled on {@code git diff --exit-code}: opt-in, so the safe reading is the default one.</li>
 *   <li>{@value #EXIT_FAILED} — at least one manifested map is not on disk after the pass, or the
 *       directory could not be prepared. The provisioner must stop here: booting nodes against a
 *       half-seeded shared folder is the silent-corruption scenario this whole design exists to avoid.</li>
 *   <li>{@value #EXIT_USAGE} — bad arguments. A bug in the caller, never in the data.</li>
 *   <li>{@value #EXIT_NO_BUNDLE} — the jar carries no map manifest. Distinct from a failure because it
 *       is a build problem (a jar built without {@code prepareMapBundle}), not a disk problem, and the
 *       fix is in a different place entirely.</li>
 * </ul>
 *
 * <h2>Two of these at once</h2>
 *
 * <p>Nothing here serialises anything: it inherits the {@code .map-bundle.lock} that
 * {@link MapBundle#seedBundledMaps} takes for the whole pass, which is an OS-level {@link
 * java.nio.channels.FileLock} and therefore the only kind that means anything between two processes. Two
 * concurrent invocations against the same directory are safe by that lock: the loser blocks, then finds
 * the winner's stamps and reports every map as current. This class must not add a lock of its own — a
 * second, coarser one around the same work would deadlock against nothing and hide the real one.</p>
 *
 * <h2>How the report is produced</h2>
 *
 * <p>{@link MapBundle#seedBundledMaps} returns a count, not a per-map outcome, and swallows a failed map
 * into "not written" — which is indistinguishable from "already current" in that number. So the outcome
 * is derived here, from the disk itself: a snapshot of (present?, stamp) per manifested map before and
 * after the pass. That is strictly more truthful than a return value, since it also catches a map that
 * was never seeded because its zip is missing from the jar, and it keeps {@link MapBundle} unchanged.</p>
 */
public final class MapBundleCli {
  public static final int EXIT_OK = 0;
  public static final int EXIT_FAILED = 1;
  public static final int EXIT_USAGE = 2;
  public static final int EXIT_NOTHING_TO_DO = 3;
  public static final int EXIT_NO_BUNDLE = 4;

  /** Directory to seed, when it is not given as an argument. */
  public static final String ENV_MAPS_DIR = "SEXIDIUM_MAPS_DIR";
  /** {@code false} disables replacing changed maps, mirroring {@code worlds.map-bundle.refresh-when-changed}. */
  public static final String ENV_REFRESH = "SEXIDIUM_MAP_BUNDLE_REFRESH";

  private static final String USAGE = """
      Seeds the map templates bundled in this jar into a directory, then exits.

        java -cp <sexidium.jar> com.sexidium.core.world.MapBundleCli <maps-dir> [flags]

        <maps-dir>     where the map folders go (created if missing); or $SEXIDIUM_MAPS_DIR
        --no-refresh   never replace an existing map folder; or $SEXIDIUM_MAP_BUNDLE_REFRESH=false
        --exit-code    exit 3 when nothing was written (default: 0, because that is the steady state)
        --quiet        warnings and the summary line only
        --help         this text

      Exit: 0 ok · 1 a map is missing after the pass · 2 bad usage
            3 nothing written (only with --exit-code) · 4 this jar has no bundled maps""";

  private MapBundleCli() {
  }

  public static void main(String[] args) {
    // The classloader is the jar this class was loaded from, which is where prepareMapBundle put the
    // maps: `java -cp sexidium.jar` is therefore all the wiring this needs.
    ResourceAdapter resources = new ClassLoaderResourceAdapter(MapBundleCli.class.getClassLoader());
    System.exit(run(args, System::getenv, resources, System.out, System.err));
  }

  /**
   * The whole CLI, with every ambient dependency passed in so a test can run it without a process.
   *
   * @param env how to read an environment variable ({@code System::getenv} in production)
   */
  public static int run(String[] args, Function<String, String> env, ResourceAdapter resources,
      PrintStream out, PrintStream err) {
    Options options;
    try {
      options = Options.parse(args, env);
    } catch (IllegalArgumentException rejected) {
      err.println("map-bundle: " + rejected.getMessage());
      err.println(USAGE);
      return EXIT_USAGE;
    }
    if (options.help()) {
      out.println(USAGE);
      return EXIT_OK;
    }
    if (options.mapsDir() == null) {
      err.println("map-bundle: no target directory (pass one, or set " + ENV_MAPS_DIR + ").");
      err.println(USAGE);
      return EXIT_USAGE;
    }

    LoggerAdapter logger = new StreamLogger(out, err, options.quiet());
    Path mapsDir = options.mapsDir().toAbsolutePath().normalize();
    try {
      // Ahead of MapBundle so a read-only mount or a typo'd path fails HERE, with the path named, rather
      // than as a warning buried in the middle of a seeding pass that then reports "nothing written".
      Files.createDirectories(mapsDir);
    } catch (IOException exception) {
      err.println("map-bundle: cannot use '" + mapsDir + "': " + exception.getMessage());
      return EXIT_FAILED;
    }
    if (!Files.isWritable(mapsDir)) {
      err.println("map-bundle: '" + mapsDir + "' is not writable by this process.");
      return EXIT_FAILED;
    }

    List<String> worldPaths = MapBundle.bundledWorldPaths(resources);
    if (worldPaths.isEmpty()) {
      err.println("map-bundle: this jar has no bundled maps (" + MapBundle.MANIFEST_RESOURCE
          + " is missing or empty). Built without the prepareMapBundle task?");
      return EXIT_NO_BUNDLE;
    }

    Map<String, State> before = snapshot(mapsDir, worldPaths);
    // The count is the only thing that reports AUTHORSHIP: the snapshots are taken outside the seeding
    // lock (taking it here would deadlock — MapBundle wants the write side of the same in-JVM lock), so
    // a concurrent provisioner's work shows up in the diff as though this process had done it.
    int written = new MapBundle(resources, logger).seedBundledMaps(mapsDir, options.refresh());
    Map<String, State> after = snapshot(mapsDir, worldPaths);

    return report(mapsDir, worldPaths, before, after, written, options, out, err);
  }

  /** Classifies every map by comparing the two snapshots, prints one line each, and picks the exit code. */
  private static int report(Path mapsDir, List<String> worldPaths, Map<String, State> before,
      Map<String, State> after, int written, Options options, PrintStream out, PrintStream err) {
    int seeded = 0;
    int refreshed = 0;
    int current = 0;
    List<String> missing = new ArrayList<>();
    for (String worldPath : worldPaths) {
      State was = before.get(worldPath);
      State is = after.get(worldPath);
      if (!is.present()) {
        missing.add(worldPath);
        err.println("  FAILED    " + worldPath + " (not on disk after the pass)");
      } else if (written == 0) {
        // This process wrote nothing, so whatever the diff shows was another provisioner running at the
        // same time: it queued on the file lock, found this map complete, and correctly did nothing.
        // Reporting that as "seeded" would credit this process for bytes it did not write.
        current++;
        if (!options.quiet()) {
          out.println("  current   " + worldPath + (was.present() ? "" : " (seeded by a concurrent pass)"));
        }
      } else if (!was.present()) {
        seeded++;
        out.println("  seeded    " + worldPath);
      } else if (!was.sameStampAs(is)) {
        refreshed++;
        // The old copy is beside it as <name>.replaced-<ts>; MapBundle already logged where.
        out.println("  refreshed " + worldPath);
      } else {
        current++;
        if (!options.quiet()) {
          out.println("  current   " + worldPath);
        }
      }
    }
    // One machine-readable line, always, quiet or not: this is what a human reads in the init container's
    // logs after the fact, when the process itself is long gone.
    String summary = "map-bundle: " + mapsDir + " seeded=" + seeded + " refreshed=" + refreshed
        + " current=" + current + " failed=" + missing.size();
    if (!missing.isEmpty()) {
      err.println(summary);
      err.println("map-bundle: refusing to report success; nodes must not boot against a half-seeded "
          + "map directory. Missing: " + String.join(", ", missing));
      return EXIT_FAILED;
    }
    out.println(summary);
    if (options.exitCodeOnNoChange() && seeded == 0 && refreshed == 0) {
      return EXIT_NOTHING_TO_DO;
    }
    return EXIT_OK;
  }

  /** Whether a map folder holds world data, and the bundle digest stamped in it. */
  private record State(boolean present, String stamp) {
    boolean sameStampAs(State other) {
      // Null stamps on both sides (a digest-less manifest, older jars) count as unchanged: with nothing
      // to compare, MapBundle's rule is never-clobber, so "unchanged" is the honest report.
      return stamp == null ? other.stamp() == null : stamp.equals(other.stamp());
    }
  }

  private static Map<String, State> snapshot(Path mapsDir, List<String> worldPaths) {
    Map<String, State> states = new LinkedHashMap<>();
    for (String worldPath : worldPaths) {
      Path folder = mapsDir.resolve(worldPath).normalize();
      // Deliberately the same test MapBundle uses to decide "already seeded" (region/ or level.dat), and
      // deliberately duplicated rather than exported from it: this is a reporting heuristic on the
      // outside of a class this batch does not own, and coupling the report to a private predicate would
      // make the two files change together for no benefit.
      boolean present = Files.isDirectory(folder)
          && (Files.isDirectory(folder.resolve("region")) || Files.exists(folder.resolve("level.dat")));
      states.put(worldPath, new State(present, present ? readStamp(folder) : null));
    }
    return states;
  }

  private static String readStamp(Path folder) {
    Path stamp = folder.resolve(MapBundle.STAMP_FILE);
    if (!Files.isRegularFile(stamp)) {
      return null;
    }
    try {
      String content = Files.readString(stamp, StandardCharsets.UTF_8).trim();
      return content.isEmpty() ? null : content;
    } catch (IOException unreadable) {
      // Treated as "no stamp": the worst that costs is reporting a refresh as a seed in the summary.
      return null;
    }
  }

  /** Parsed command line, with the environment folded in (arguments win over environment). */
  private record Options(Path mapsDir, boolean refresh, boolean exitCodeOnNoChange, boolean quiet,
      boolean help) {

    static Options parse(String[] args, Function<String, String> env) {
      Path mapsDir = null;
      // Environment first so an explicit flag can override it; `false`/`0`/`no` all disable, because
      // this is read by shell authors, not by a YAML parser.
      boolean refresh = !isFalsey(env.apply(ENV_REFRESH));
      boolean exitCode = false;
      boolean quiet = false;
      boolean help = false;
      for (String argument : args == null ? new String[0] : args) {
        if (argument == null || argument.isBlank()) {
          continue;
        }
        switch (argument) {
          case "--no-refresh" -> refresh = false;
          case "--refresh" -> refresh = true;
          case "--exit-code" -> exitCode = true;
          case "--quiet", "-q" -> quiet = true;
          case "--help", "-h" -> help = true;
          default -> {
            if (argument.startsWith("-")) {
              throw new IllegalArgumentException("unknown flag '" + argument + "'.");
            }
            if (mapsDir != null) {
              throw new IllegalArgumentException("more than one target directory given ('" + mapsDir
                  + "' and '" + argument + "').");
            }
            mapsDir = Paths.get(argument);
          }
        }
      }
      if (mapsDir == null) {
        String fromEnv = env.apply(ENV_MAPS_DIR);
        if (fromEnv != null && !fromEnv.isBlank()) {
          mapsDir = Paths.get(fromEnv.trim());
        }
      }
      return new Options(mapsDir, refresh, exitCode, quiet, help);
    }

    private static boolean isFalsey(String value) {
      if (value == null) {
        return false;
      }
      String normalised = value.trim().toLowerCase(Locale.ROOT);
      return normalised.equals("false") || normalised.equals("0") || normalised.equals("no")
          || normalised.equals("off");
    }
  }

  /**
   * {@link LoggerAdapter} over the two streams the caller handed in.
   *
   * <p>{@code StdoutLoggerAdapter} would have been the natural reuse, but it writes to
   * {@code System.out} directly, so a test could only assert on it by swapping the JVM's global streams —
   * and it has no notion of quiet. Warnings from {@link MapBundle} always go to stderr here: in the init
   * container's log they are the only thing worth grepping for.</p>
   */
  private record StreamLogger(PrintStream out, PrintStream err, boolean quiet) implements LoggerAdapter {
    @Override
    public void info(String message) {
      if (!quiet) {
        out.println("  " + message);
      }
    }

    @Override
    public void warning(String message) {
      err.println("  WARN " + message);
    }

    @Override
    public void severe(String message) {
      err.println("  ERROR " + message);
    }

    @Override
    public void warning(String message, Throwable throwable) {
      warning(message + (throwable == null ? "" : " (" + throwable + ")"));
    }

    @Override
    public void severe(String message, Throwable throwable) {
      severe(message + (throwable == null ? "" : " (" + throwable + ")"));
    }
  }
}
