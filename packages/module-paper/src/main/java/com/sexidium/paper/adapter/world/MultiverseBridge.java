package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.paper.adapter.util.PlatformProbes;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * Best-effort reflective bridge to Multiverse-Core, used by {@link PaperWorldControl} to keep the
 * Multiverse world registry in sync with the worlds Sexidium creates and disposes natively.
 *
 * <p>The worlds themselves are always created/loaded with the platform's native {@code WorldCreator}
 * (keyed, so they land at {@code world/dimensions/<namespace>/<key>} on MC 26.1+); Multiverse is then
 * <em>imported</em> on top so {@code /mv tp <world>} and the rest of the MV tooling resolve them, and is
 * <em>removed</em> when the world is disposed. We deliberately do not let MV drive the actual
 * create/load: on MC 26.1+ dimension storage that path has historically failed
 * ({@code WORLD_FOLDER_INVALID}), whereas native keyed creation is reliable. Every call here is wrapped
 * so a missing/older Multiverse, or any API drift, degrades to a no-op rather than breaking world
 * management.</p>
 *
 * <p>Targets Multiverse-Core v5 ({@code org.mvplugins.multiverse.core.MultiverseCoreApi}, the current
 * line) and falls back to the v4 {@code getMVWorldManager()} API.</p>
 *
 * <p>This is THE Multiverse bridge (Plan.md Stage 0h): the second, near-identical one that used to
 * live in {@code PaperLobbyBootstrap.MVWorldBridge} was folded into here, because two hand-rolled
 * probes for the same plugin is how their version handling drifts apart.</p>
 */
public final class MultiverseBridge {
  public static final String PLUGIN_NAME = "Multiverse-Core";
  private static final String V5_API_CLASS = "org.mvplugins.multiverse.core.MultiverseCoreApi";
  private static final String V5_IMPORT_OPTIONS = "org.mvplugins.multiverse.core.world.options.ImportWorldOptions";

  private final Object worldManager;
  private final LoggerAdapter logger;
  private final boolean v5;

  private MultiverseBridge(Object worldManager, LoggerAdapter logger, boolean v5) {
    this.worldManager = worldManager;
    this.logger = logger;
    this.v5 = v5;
  }

  /** Binds to Multiverse when it is installed and enabled, else returns null (MV optional at runtime). */
  public static MultiverseBridge tryBind(org.bukkit.plugin.PluginManager plugins, LoggerAdapter logger) {
    if (plugins == null) {
      return null;
    }
    return tryBind(plugins.getPlugin(PLUGIN_NAME), logger);
  }

  /**
   * Binds from the plugin instance itself, for callers that already resolved it.
   *
   * <p>Every failure below is caught, {@link LinkageError} included, because this runs inside
   * {@link PaperWorldControl}'s constructor — which runs inside the server adapter's constructor, which
   * runs during {@code onEnable}. A Multiverse compiled against a newer Java, or one whose static
   * initialiser throws, would otherwise take the entire plugin down over an optional integration. A
   * version probe must never be able to brick boot.</p>
   */
  public static MultiverseBridge tryBind(Plugin multiverse, LoggerAdapter logger) {
    if (multiverse == null || !multiverse.isEnabled()) {
      return null;
    }
    // v4: plugin instance exposes getMVWorldManager().
    try {
      Object wm = multiverse.getClass().getMethod("getMVWorldManager").invoke(multiverse);
      if (wm != null) {
        return new MultiverseBridge(wm, logger, false);
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError notV4) {
      // not v4, or a v4 API that will not link here — either way, try v5 below
    }
    // v5: MultiverseCoreApi.get().getWorldManager().
    try {
      Class<?> apiClass = PlatformProbes.linkableClass(V5_API_CLASS, MultiverseBridge.class.getClassLoader());
      Object api = apiClass == null ? null : apiClass.getMethod("get").invoke(null);
      Object wm = api == null ? null : api.getClass().getMethod("getWorldManager").invoke(api);
      if (wm != null) {
        logger.info("Bound to Multiverse v5 WorldManager.");
        return new MultiverseBridge(wm, logger, true);
      }
      logger.warning("Multiverse-Core is enabled but exposes neither the v4 nor the v5 world manager;"
          + " Sexidium's worlds will not be registered with Multiverse.");
    } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
      logger.warning("Multiverse-Core present but its world manager could not be bound: " + exception);
    }
    return null;
  }

  /** Registers an already-on-disk world with Multiverse so {@code /mv tp} resolves it. Best effort. */
  public void importWorld(World world) {
    if (world == null) {
      return;
    }
    importWorld(world.getName(), world.getEnvironment(),
        org.bukkit.WorldType.NORMAL, null);
  }

  /**
   * Registers an already-on-disk world, carrying environment/type/generator through whichever options
   * object this Multiverse line accepts. Best effort: any refusal or API drift logs and answers
   * {@code false}, never breaks world management.
   */
  @SuppressWarnings("BooleanMethodIsAlwaysUsed") // best effort by contract: false is information, not failure
  public boolean importWorld(String name, World.Environment environment,
      org.bukkit.WorldType type, String generator) {
    if (name == null || name.isBlank()) {
      return false;
    }
    try {
      if (isRegistered(name)) {
        return true;
      }
      boolean imported = v5
          ? importV5(name, environment, type, generator)
          : addWorldV4(name, environment, type, generator);
      if (!imported) {
        logger.warning("Multiverse import of '" + name + "' skipped: the world manager refused.");
      }
      return imported;
    } catch (NoSuchMethodException drifted) {
      // Named separately because getMessage() on this one is a raw JVM signature string, which reads
      // as noise unless it is labelled as what it is: the Multiverse API moved under us.
      logger.warning("Multiverse import of '" + name + "' skipped: this Multiverse build has no"
          + " matching world-manager method (" + drifted.getMessage() + ").");
      return false;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
      logger.warning("Multiverse import of '" + name + "' skipped: " + exception);
      return false;
    }
  }

  /**
   * Unregisters a world from Multiverse without deleting files. Called when a temp world is disposed and
   * — more importantly — when an experience is deleted: a registration Multiverse keeps for a folder that
   * no longer exists makes it fail to autoload that world on every boot from then on.
   *
   * <p>MV v5 overloads {@code removeWorld} for several argument types and only some accept a bare name,
   * so we try the name form first and then resolve the world object and pass that. Every path is
   * best-effort — a Multiverse that has drifted must never break world management.</p>
   */
  public void forgetWorld(String name) {
    if (name == null || name.isBlank() || !isRegistered(name)) {
      return;
    }
    try {
      worldManager.getClass().getMethod("removeWorld", String.class).invoke(worldManager, name);
      if (!isRegistered(name)) {
        return;
      }
    } catch (ReflectiveOperationException | RuntimeException ignored) {
      // fall through to the object-taking overloads
    }
    Object world = world(name);
    if (world == null) {
      return;
    }
    for (java.lang.reflect.Method method : worldManager.getClass().getMethods()) {
      if (!method.getName().equals("removeWorld") || method.getParameterCount() != 1
          || !method.getParameterTypes()[0].isInstance(world)) {
        continue;
      }
      try {
        method.invoke(worldManager, world);
        if (!isRegistered(name)) {
          return;
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next overload
      }
    }
  }

  /**
   * Every world name Multiverse currently has on its books, so a caller can reconcile them against what
   * is really on disk. Returns empty when the registry cannot be read — a reconciliation that finds
   * nothing simply does nothing.
   *
   * <h2>The unloaded ones are the whole point</h2>
   * This used to ask only for {@code getWorlds}, which on Multiverse v5 answers with the LOADED worlds
   * — and that made the stale-registration sweep that consumes this list incapable of ever doing its
   * job. A registration whose folder has been deleted fails to autoload
   * ({@code Failure{reason=WORLD_FOLDER_INVALID}}) and is therefore, by definition, not loaded: the
   * only entries the sweep could see were the ones that were not stale. It ran clean every boot and
   * removed nothing, while the books grew to 290 temp registrations against 24 folders on disk and
   * every boot replayed the whole failed-autoload wall.
   *
   * <p>So the unloaded accessors are unioned in rather than used as a fallback. Order matters only for
   * readability — duplicates are dropped, because a world can legitimately appear in both lists on the
   * versions where {@code getWorlds} means "all of them".</p>
   */
  public java.util.List<String> registeredWorldNames() {
    java.util.Set<String> names = new java.util.LinkedHashSet<>();
    for (String accessor : new String[] {
        "getWorlds", "getMVWorlds", "getWorldsArray",
        // v5's unloaded registrations — the ones a stale sweep exists to find.
        "getUnloadedWorlds", "getPotentialWorlds", "getWorldsUnloaded"}) {
      try {
        Object result = worldManager.getClass().getMethod(accessor).invoke(worldManager);
        names.addAll(namesOf(result));
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // try the next accessor; an absent one is normal across Multiverse versions
      }
    }
    return java.util.List.copyOf(names);
  }

  /** Reads the {@code getName()} of every element of a collection/array of Multiverse world objects. */
  private java.util.List<String> namesOf(Object result) {
    java.util.List<String> names = new java.util.ArrayList<>();
    Iterable<?> elements;
    if (result instanceof Iterable<?> iterable) {
      elements = iterable;
    } else if (result instanceof Object[] array) {
      elements = java.util.Arrays.asList(array);
    } else {
      return names;
    }
    for (Object element : elements) {
      if (element == null) {
        continue;
      }
      try {
        Object name = element.getClass().getMethod("getName").invoke(element);
        if (name instanceof String text && !text.isBlank()) {
          names.add(text);
        }
      } catch (ReflectiveOperationException | RuntimeException ignored) {
        // an element we cannot name is simply skipped
      }
    }
    return names;
  }

  /** The Multiverse world object registered under {@code name} (a vavr Option unwrapped on v5), or null. */
  public Object world(String name) {
    try {
      Object result = worldManager.getClass().getMethod(v5 ? "getWorld" : "getMVWorld", String.class)
          .invoke(worldManager, name);
      if (!v5 || result == null) {
        return result;
      }
      Object defined = result.getClass().getMethod("isDefined").invoke(result);
      return defined instanceof Boolean present && present
          ? result.getClass().getMethod("get").invoke(result) : null;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      return null;
    }
  }

  private boolean isRegistered(String name) {
    try {
      Object result = worldManager.getClass().getMethod(v5 ? "getWorld" : "getMVWorld", String.class)
          .invoke(worldManager, name);
      if (!v5) {
        return result != null;
      }
      // v5 returns a vavr Option; treat a defined Option as registered.
      if (result == null) {
        return false;
      }
      Object defined = result.getClass().getMethod("isDefined").invoke(result);
      return defined instanceof Boolean present && present;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      return false;
    }
  }

  /**
   * The v5 import path: options built from the static {@code worldName(String)} factory, fluent
   * mutators applied where this Multiverse build has them (an absent one keeps the previous builder),
   * then the manager's Attempt unwrapped for a real answer.
   */
  private boolean importV5(String name, World.Environment environment,
      org.bukkit.WorldType type, String generator) throws ReflectiveOperationException {
    Class<?> optionsClass = Class.forName(V5_IMPORT_OPTIONS);
    Object options = optionsClass.getMethod("worldName", String.class).invoke(null, name);
    options = applyOption(options, "environment", World.Environment.class, environment);
    options = applyOption(options, "worldType", org.bukkit.WorldType.class, type);
    if (generator != null && !generator.isBlank() && !"default".equalsIgnoreCase(generator)) {
      options = applyOption(options, "generator", String.class, generator);
    }
    Object attempt = worldManager.getClass().getMethod("importWorld", optionsClass)
        .invoke(worldManager, options);
    return attemptSucceeded(attempt, name);
  }

  /**
   * The v4 import path: the six-argument {@code addWorld}, seed left null.
   *
   * <p>The signature is <em>found</em> rather than named. MV4 declares
   * {@code addWorld(String, Environment, String seed, WorldType, Boolean generateStructures, String generator)}
   * — but the seed parameter is {@code String} on some builds and {@code Long} on others, and
   * {@code generateStructures} is boxed, so a single {@code getMethod(...)} with hard-coded parameter
   * types answers {@code NoSuchMethodException} on every build it was not written against. Matching by
   * name and arity instead makes the one path work across the line.</p>
   *
   * <p>{@code type} is passed through. It used to be accepted and then ignored in favour of a literal
   * {@code NORMAL}, which was invisible only because both call sites happen to pass {@code NORMAL}
   * today — a trap primed for the first caller that asks for {@code FLAT}.</p>
   */
  private boolean addWorldV4(String name, World.Environment environment,
      org.bukkit.WorldType type, String generator) throws ReflectiveOperationException {
    java.lang.reflect.Method addWorld = findAddWorldV4();
    if (addWorld == null) {
      throw new NoSuchMethodException("MVWorldManager.addWorld(String, Environment, <seed>, WorldType,"
          + " boolean, String) is absent on this Multiverse build");
    }
    // Seed null on either boxing: "no seed given", which is what the native creator already used.
    Object result = addWorld.invoke(worldManager, name, environment, null,
        type == null ? org.bukkit.WorldType.NORMAL : type, Boolean.TRUE, generator);
    return Boolean.TRUE.equals(result);
  }

  /**
   * The six-argument {@code addWorld}, whichever seed/flag boxing this build declares. Null when the
   * method is absent entirely, which the caller reports as API drift.
   */
  private java.lang.reflect.Method findAddWorldV4() {
    for (java.lang.reflect.Method method : worldManager.getClass().getMethods()) {
      if (!method.getName().equals("addWorld") || method.getParameterCount() != 6) {
        continue;
      }
      Class<?>[] parameters = method.getParameterTypes();
      boolean seedShaped = parameters[2] == String.class || parameters[2] == Long.class;
      boolean flagShaped = parameters[4] == boolean.class || parameters[4] == Boolean.class;
      if (parameters[0] == String.class && parameters[1] == World.Environment.class && seedShaped
          && parameters[3] == org.bukkit.WorldType.class && flagShaped
          && parameters[5] == String.class) {
        return method;
      }
    }
    return null;
  }

  /** Applies a fluent builder mutator, keeping the original builder on any failure. */
  private static Object applyOption(Object options, String method, Class<?> paramType, Object value) {
    if (options == null || value == null) {
      return options;
    }
    try {
      Object updated = options.getClass().getMethod(method, paramType).invoke(options, value);
      return updated != null ? updated : options;
    } catch (ReflectiveOperationException exception) {
      return options;
    }
  }

  /** Unwraps a v5 {@code Attempt} so a refusal is reported rather than assumed successful. */
  private boolean attemptSucceeded(Object attempt, String name) {
    if (attempt == null) {
      return false;
    }
    try {
      Object success = attempt.getClass().getMethod("isSuccess").invoke(attempt);
      if (success instanceof Boolean ok && !ok) {
        Object reason = null;
        try {
          reason = attempt.getClass().getMethod("getFailureReason").invoke(attempt);
        } catch (ReflectiveOperationException ignored) {
          // older Attempt shape without a reason
        }
        logger.info("Multiverse import of '" + name + "' did not succeed"
            + (reason == null ? "." : ": " + reason));
        return false;
      }
      return true;
    } catch (ReflectiveOperationException unknownShape) {
      // Unknown Attempt shape; assume the call went through.
      return true;
    }
  }
}
