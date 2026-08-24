package com.sexidium.core.world;

import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemStackData;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.WorldBorderSpec;
import com.sexidium.core.platform.model.WorldPosition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * An {@link AbstractWorldControl} with no Minecraft under it: worlds are names in a set, and every
 * unload, save and evacuation is recorded so a test can assert the ORDER of the shutdown and drain
 * paths rather than a screenshot of their result.
 *
 * <p>Public and shared because the same fake is needed from two packages — the world tests, and the
 * core-level test that boots a whole node to check the drain is wired to this at all.</p>
 */
public class FakeWorldControl extends AbstractWorldControl {

  private final Path home;
  /** Every side effect, in order, so "released before closed" is a failing assertion and not a guess. */
  public final List<String> log;
  public final List<String> unloaded = new ArrayList<>();
  public final List<String> evacuated = new ArrayList<>();
  public final Set<String> onDisk = new HashSet<>();
  public final List<WorldHandle> live = new ArrayList<>();
  public final AtomicBoolean savedOnUnload = new AtomicBoolean(false);
  /** Flip to model the world that will not close — the case that pins a drain at worlds=1. */
  public boolean unloadSucceeds = true;
  /** Workers — the nodes that host experiences — have no lobby world. Flip to model one. */
  public boolean hasLobbyWorld;

  public FakeWorldControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home) {
    this(configuration, logger, home, new ArrayList<>());
  }

  public FakeWorldControl(ConfigurationAdapter configuration, LoggerAdapter logger, Path home,
      List<String> log) {
    super(configuration, logger);
    this.home = home;
    this.log = log;
  }

  @Override protected void runOnWorldThread(Runnable task) {
    task.run();
  }

  @Override protected Path serverHome() {
    return home;
  }

  @Override protected Path experiencesDiskRoot() {
    return home.resolve("experiences");
  }

  @Override protected Path lobbyDiskFolder() {
    return home.resolve("lobby");
  }

  @Override protected Optional<WorldHandle> backendAcquire(WorldRequest request, boolean create) {
    if (!create && !onDisk.contains(request.runtimeName())) {
      return Optional.empty();
    }
    onDisk.add(request.runtimeName());
    FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
        home.resolve(request.runtimeName().replace('/', '_')), evacuated);
    live.add(handle);
    return Optional.of(handle);
  }

  @Override protected boolean backendExistsOnDisk(WorldRequest request) {
    return onDisk.contains(request.runtimeName());
  }

  @Override protected Optional<WorldHandle> backendAdopt(WorldHandle pooled, WorldRequest request) {
    live.remove(pooled);
    onDisk.add(request.runtimeName());
    FakeHandle handle = new FakeHandle(request.runtimeName(), request.kind(),
        home.resolve(request.runtimeName().replace('/', '_')), evacuated);
    live.add(handle);
    return Optional.of(handle);
  }

  @Override protected Optional<WorldHandle> backendResolveLoaded(String runtimeName, WorldKind kind) {
    for (WorldHandle handle : live) {
      if (WorldNaming.sameWorld(handle.runtimeName(), runtimeName)) {
        return Optional.of(handle);
      }
    }
    return Optional.empty();
  }

  @Override protected boolean backendUnload(WorldHandle handle, boolean save) {
    if (handle.kind() != WorldKind.PERSISTENT) {
      live.remove(handle);
      return true;
    }
    if (!unloadSucceeds) {
      return false;
    }
    savedOnUnload.set(save);
    unloaded.add(handle.runtimeName());
    log.add("unload:" + handle.runtimeName());
    live.remove(handle);
    return true;
  }

  @Override protected Optional<WorldHandle> backendLobby() {
    return Optional.empty();
  }

  @Override public Optional<WorldPosition> lobbySpawn() {
    return hasLobbyWorld
        ? Optional.of(new WorldPosition("lobby", 0.5, 64, 0.5, 0f, 0f))
        : Optional.empty();
  }

  private record FakeHandle(String runtimeName, WorldKind kind, Path canonicalFolder,
      List<String> evacuated) implements WorldHandle {
    @Override public com.sexidium.core.platform.WorldAdapter adapter() {
      return new FakeWorld(runtimeName, evacuated);
    }
  }

  private record FakeWorld(String name, List<String> evacuated)
      implements com.sexidium.core.platform.WorldAdapter {
    @Override public WorldPosition spawnPosition() {
      return new WorldPosition(name, 0.5, 64, 0.5, 0f, 0f);
    }

    @Override public List<PlayerAdapter> players() {
      evacuated.add(name);
      return List.of();
    }

    @Override public void dropItem(WorldPosition target, ItemStackData item) { }

    @Override public void playSound(WorldPosition target, SoundKey sound, float volume, float pitch) { }

    @Override public void setBorder(WorldBorderSpec spec) { }

    @Override public void resetBorder() { }

    @Override public void loadChunk(int chunkX, int chunkZ, boolean generate) { }
  }
}
