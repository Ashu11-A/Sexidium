package com.sexidium.velocity.adapter;

import com.sexidium.core.testing.AbstractCoreApiSurfaceTest;

import java.nio.file.Path;

/**
 * The Velocity proxy's half of the golden core-API surface. All of the logic lives in
 * {@link AbstractCoreApiSurfaceTest}; this supplies only where to look.
 *
 * <p>The proxy deserves the closer look of the two: it is the adapter with the least reason to touch
 * core internals, so anything it grows is worth a second reading.</p>
 */
class GoldenApiSurfaceTest extends AbstractCoreApiSurfaceTest {

  @Override
  protected Path mainSources() {
    return moduleDir().resolve(Path.of("src", "main", "java"));
  }

  @Override
  protected Path goldenFile() {
    return moduleDir().resolve(Path.of("src", "test", "resources", "golden", "core-api-surface.txt"));
  }

  @Override
  protected String moduleName() {
    return ":packages:module-velocity";
  }
}
