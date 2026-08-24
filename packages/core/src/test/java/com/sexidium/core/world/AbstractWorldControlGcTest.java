package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbstractWorldControlGcTest {
  @TempDir
  Path tempDir;

  @Test
  void collectGarbage_deletesDiskOnlyOrphanTempFolder() throws Exception {
    FakeWorldControl control = control();
    Path orphan = tempFolder("sexidium_temp_orphan");

    int reclaimed = control.collectGarbage(List.of());

    assertEquals(1, reclaimed);
    assertFalse(Files.exists(orphan));
  }

  @Test
  void collectGarbage_keepsProtectedDiskFolder() throws Exception {
    FakeWorldControl control = control();
    Path protectedFolder = tempFolder("sexidium_temp_keep");

    int reclaimed = control.collectGarbage(List.of("sexidium_temp/sexidium_temp_keep"));

    assertEquals(0, reclaimed);
    assertTrue(Files.exists(protectedFolder));
  }

  @Test
  void collectGarbage_unloadsAndDeletesLoadedOrphanWorld() throws Exception {
    FakeWorldControl control = control();
    Path folder = tempFolder("sexidium_temp_loaded");
    FakeHandle handle = new FakeHandle("sexidium_temp/sexidium_temp_loaded", folder);
    control.loaded.put(handle.runtimeName(), handle);

    int reclaimed = control.collectGarbage(List.of());

    assertEquals(1, reclaimed);
    assertTrue(control.unloaded.contains(handle.runtimeName()));
    assertFalse(Files.exists(folder));
    assertTrue(control.loaded.isEmpty());
  }

  @Test
  void collectGarbage_keepsPreservedDiskFolder() throws Exception {
    FakeWorldControl control = control();
    Path preserved = tempFolder("sexidium_temp_preserved");
    control.preserve(List.of("sexidium_temp/sexidium_temp_preserved"));

    int reclaimed = control.collectGarbage(List.of());

    assertEquals(0, reclaimed);
    assertTrue(Files.exists(preserved));
  }

  private FakeWorldControl control() throws Exception {
    Path tempRoot = tempDir.resolve("temp");
    Files.createDirectories(tempRoot);
    return new FakeWorldControl(new PropertiesConfigurationAdapter(), new StdoutLoggerAdapter("test"), tempDir, tempRoot);
  }

  private Path tempFolder(String name) throws Exception {
    Path folder = tempDir.resolve("temp").resolve(name);
    Files.createDirectories(folder.resolve("region"));
    return folder;
  }

  private static final class FakeWorldControl extends AbstractWorldControl {
    private final Path serverHome;
    private final Path tempRoot;
    private final Map<String, FakeHandle> loaded = new LinkedHashMap<>();
    private final List<String> unloaded = new ArrayList<>();

    private FakeWorldControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path serverHome, Path tempRoot) {
      super(configuration, logger);
      this.serverHome = serverHome;
      this.tempRoot = tempRoot;
    }

    @Override
    protected void runOnWorldThread(Runnable task) {
      task.run();
    }

    @Override
    protected Path serverHome() {
      return serverHome;
    }

    @Override
    protected Path experiencesDiskRoot() {
      return serverHome.resolve("experiences");
    }

    @Override
    protected Path lobbyDiskFolder() {
      return serverHome.resolve("lobby");
    }

    @Override
    protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean createIfMissing) {
      return Optional.empty();
    }

    @Override
    protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
      for (FakeHandle handle : loaded.values()) {
        if (WorldNaming.sameWorld(handle.runtimeName(), runtimeName)) {
          return Optional.of(handle);
        }
      }
      return Optional.empty();
    }

    @Override
    protected boolean backendUnload(WorldHandle handle, boolean save) {
      unloaded.add(handle.runtimeName());
      loaded.remove(handle.runtimeName());
      return true;
    }

    @Override
    protected Optional<WorldHandle> backendLobby() {
      return Optional.empty();
    }

    @Override
    protected List<WorldHandle> backendLoadedTempWorlds() {
      return new ArrayList<>(loaded.values());
    }

    @Override
    protected Path backendTempDiskRoot() {
      return tempRoot;
    }
  }

  private static final class FakeHandle implements WorldHandle {
    private final String runtimeName;
    private final Path folder;
    private final WorldAdapter adapter;

    private FakeHandle(String runtimeName, Path folder) {
      this.runtimeName = runtimeName;
      this.folder = folder;
      this.adapter = new FakeWorldAdapter(runtimeName);
    }

    @Override
    public String runtimeName() {
      return runtimeName;
    }

    @Override
    public WorldKind kind() {
      return WorldKind.TEMP;
    }

    @Override
    public WorldAdapter adapter() {
      return adapter;
    }

    @Override
    public Path canonicalFolder() {
      return folder;
    }
  }

  private static final class FakeWorldAdapter implements WorldAdapter {
    private final String name;

    private FakeWorldAdapter(String name) {
      this.name = name;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override
    public List<PlayerAdapter> players() {
      return List.of();
    }

    @Override
    public void dropItem(WorldPosition targetPosition, ItemStackData itemStackData) {
    }

    @Override
    public void playSound(WorldPosition targetPosition, SoundKey soundKey, float volume, float pitch) {
    }

    @Override
    public void setBorder(WorldBorderSpec worldBorderSpec) {
    }

    @Override
    public void resetBorder() {
    }

    @Override
    public void loadChunk(int chunkX, int chunkZ, boolean generate) {
    }
  }
}
