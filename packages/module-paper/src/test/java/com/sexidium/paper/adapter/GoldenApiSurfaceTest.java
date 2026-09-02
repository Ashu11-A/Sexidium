package com.sexidium.paper.adapter;

import com.sexidium.core.testing.AbstractCoreApiSurfaceTest;

import java.nio.file.Path;

/**
 * The Paper adapter's half of the golden core-API surface. All of the logic — and every reason for it —
 * lives in {@link AbstractCoreApiSurfaceTest}; this supplies only where to look.
 *
 * <p>Paper is the module that matters most here: it is 14% of the codebase and carries the entire
 * Minecraft-coupled surface, so it is where the boundary would erode first.</p>
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
    return ":packages:module-paper";
  }
}
