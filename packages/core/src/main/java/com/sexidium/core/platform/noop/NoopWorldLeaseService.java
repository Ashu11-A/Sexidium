package com.sexidium.core.platform.noop;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldLease;
import com.sexidium.core.platform.WorldLeaseService;
import com.sexidium.core.platform.model.WorldPosition;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.function.Consumer;

public final class NoopWorldLeaseService implements WorldLeaseService {
  @Override
  public boolean enabled() {
    return false;
  }

  @Override
  public void start() {
  }

  @Override
  public void preserve(Collection<String> worldNames) {
  }

  @Override
  public Optional<WorldLease> reacquireByName(String worldName) {
    return Optional.empty();
  }

  @Override
  public void discardByName(String worldName) {
  }

  @Override
  public String lobbyName() {
    return "lobby";
  }

  @Override
  public Path worldRoot() {
    return Path.of("worlds");
  }

  @Override
  public Path tempSubdir() {
    return worldRoot().resolve("temp");
  }

  @Override
  public Optional<WorldPosition> lobbySpawn() {
    return Optional.empty();
  }

  @Override
  public Optional<WorldLease> acquireReady(com.sexidium.core.world.WorldProfile profile) {
    return Optional.empty();
  }

  @Override
  public void acquireOrCreate(Collection<? extends PlayerAdapter> viewers, Consumer<WorldLease> onReady, Runnable onFailure) {
    if (onFailure != null) {
      onFailure.run();
    }
  }

  @Override
  public void shutdown() {
  }
}
