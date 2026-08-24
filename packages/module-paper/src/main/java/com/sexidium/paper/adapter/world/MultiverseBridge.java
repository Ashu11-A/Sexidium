package com.sexidium.paper.adapter.world;

import com.sexidium.core.platform.LoggerAdapter;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;

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
  public static MultiverseBridge tryBind(PluginManager plugins, LoggerAdapter logger) {
    if (plugins == null) {
      return null;
    }
    Plugin multiverse = plugins.getPlugin(PLUGIN_NAME);
    if (multiverse == null || !plugins.isPluginEnabled(PLUGIN_NAME)) {
      return null;
    }
    // v4: plugin instance exposes getMVWorldManager().
    try {
      Object wm = multiverse.getClass().getMethod("getMVWorldManager").invoke(multiverse);
      if (wm != null) {
        return new MultiverseBridge(wm, logger, false);
      }
    } catch (ReflectiveOperationException ignored) {
      // not v4
    }
    // v5: MultiverseCoreApi.get().getWorldManager().
    try {
      Class<?> apiClass = Class.forName(V5_API_CLASS);
      Object api = apiClass.getMethod("get").invoke(null);
      Object wm = api == null ? null : api.getClass().getMethod("getWorldManager").invoke(api);
      if (wm != null) {
        logger.info("Bound to Multiverse v5 WorldManager for world registration.");
        return new MultiverseBridge(wm, logger, true);
      }
    } catch (ReflectiveOperationException exception) {
      logger.info("Multiverse-Core present but its world manager could not be bound: " + exception.getMessage());
    }
    return null;
  }

  /** Registers an already-on-disk world with Multiverse so {@code /mv tp} resolves it. Best effort. */
  public void importWorld(World world) {
    if (world == null) {
      return;
    }
    String name = world.getName();
    try {
      if (isRegistered(name)) {
        return;
      }
      if (v5) {
        importV5(name, world.getEnvironment());
      } else {
        worldManager.getClass()
            .getMethod("addWorld", String.class, World.Environment.class, String.class,
                org.bukkit.WorldType.class, boolean.class, String.class)
            .invoke(worldManager, name, world.getEnvironment(), null, org.bukkit.WorldType.NORMAL, true, null);
      }
    } catch (ReflectiveOperationException | RuntimeException exception) {
      logger.info("Multiverse import of '" + name + "' skipped: " + exception.getMessage());
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
    Object world = resolveWorld(name);
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

  /** The Multiverse world object registered under {@code name}, unwrapping a vavr Option on v5. */
  private Object resolveWorld(String name) {
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

  private void importV5(String name, World.Environment environment) throws ReflectiveOperationException {
    Class<?> optionsClass = Class.forName(V5_IMPORT_OPTIONS);
    Object options = optionsClass.getMethod("worldName", String.class).invoke(null, name);
    try {
      Object updated = options.getClass().getMethod("environment", World.Environment.class).invoke(options, environment);
      if (updated != null) {
        options = updated;
      }
    } catch (ReflectiveOperationException ignored) {
      // keep base options
    }
    worldManager.getClass().getMethod("importWorld", optionsClass).invoke(worldManager, options);
  }
}
