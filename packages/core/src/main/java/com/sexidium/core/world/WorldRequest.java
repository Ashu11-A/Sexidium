package com.sexidium.core.world;

/**
 * Everything a backend needs to create or load one managed world. Built by {@link AbstractWorldControl}
 * (which owns all naming/policy) and handed to the platform backend, so the backend is a thin executor
 * with no naming logic of its own.
 *
 * @param kind                what lifecycle class to create (drives disposal + settings)
 * @param namespace           the world's namespace (e.g. {@code experiences}, {@code sexidium_temp},
 *                            {@code minecraft}) — first path element under {@code world/dimensions/}
 * @param keyPath             the key path within the namespace (e.g. {@code <nick>/<map>_<id>}); a
 *                            forward slash produces real sub-directories under the namespace
 * @param runtimeName         the canonical runtime name the resulting handle must report
 * @param templateRuntimeName for a {@link WorldKind#CLONE}, the canonical name of the template to copy;
 *                            otherwise {@code null}
 * @param seed                world seed; {@code null} for a random/default seed
 * @param settings            world settings to apply once created/loaded
 * @param key                 for a {@link WorldKind#PERSISTENT} request, the experience identity this
 *                            world IS — the placement key, the folder name and the runtime name all
 *                            derive from it; {@code null} for every other kind
 */
public record WorldRequest(
    WorldKind kind,
    String namespace,
    String keyPath,
    String runtimeName,
    String templateRuntimeName,
    Long seed,
    WorldSettings settings,
    WorldKey key
) {

  /** The pre-{@link WorldKey} shape, for temp/clone/lobby requests that have no experience identity. */
  public WorldRequest(WorldKind kind, String namespace, String keyPath, String runtimeName,
      String templateRuntimeName, Long seed, WorldSettings settings) {
    this(kind, namespace, keyPath, runtimeName, templateRuntimeName, seed, settings, null);
  }
}
