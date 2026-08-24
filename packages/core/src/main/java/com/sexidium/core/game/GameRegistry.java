package com.sexidium.core.game;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class GameRegistry {
  private final Map<String, RegisteredMode> modesByAlias = new LinkedHashMap<>();
  private final Map<String, GameModeDescriptor> descriptorsByModeId = new LinkedHashMap<>();

  public void register(GameModeDescriptor descriptor, GameFactory factory) {
    if (descriptor == null || factory == null) {
      return;
    }
    descriptorsByModeId.put(descriptor.modeId(), descriptor);
    modesByAlias.put(normalize(descriptor.modeId()), new RegisteredMode(descriptor, factory));
    for (String alias : descriptor.aliases()) {
      modesByAlias.put(normalize(alias), new RegisteredMode(descriptor, factory));
    }
  }

  public Optional<Game> create(GameContext gameContext, String requestedModeId, List<String> modeArgs) {
    RegisteredMode registeredMode = modesByAlias.get(normalize(requestedModeId));
    if (registeredMode == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(registeredMode.factory().create(gameContext, requestedModeId, modeArgs));
  }

  public boolean contains(String requestedModeId) {
    return modesByAlias.containsKey(normalize(requestedModeId));
  }

  public List<GameModeDescriptor> descriptors() {
    return List.copyOf(descriptorsByModeId.values());
  }

  public List<String> modeIds() {
    List<String> modeIds = new ArrayList<>();
    for (GameModeDescriptor descriptor : descriptorsByModeId.values()) {
      modeIds.add(descriptor.modeId());
    }
    return List.copyOf(modeIds);
  }

  private static String normalize(String requestedModeId) {
    return requestedModeId == null
        ? ""
        : requestedModeId.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
  }

  private record RegisteredMode(GameModeDescriptor descriptor, GameFactory factory) {
  }
}
