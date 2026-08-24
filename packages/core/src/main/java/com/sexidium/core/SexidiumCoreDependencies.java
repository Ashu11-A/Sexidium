package com.sexidium.core;

import com.sexidium.core.auth.AuthService;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.platform.KitAdapter;
import com.sexidium.core.platform.ServerAdapter;
import com.sexidium.core.lib.data.Database;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public record SexidiumCoreDependencies(
    ServerAdapter serverAdapter,
    KitAdapter kitAdapter,
    GameRegistry gameRegistry,
    Database database,
    AuthService authService,
    BooleanSupplier authEnabled
) {
  public SexidiumCoreDependencies {
    serverAdapter = Objects.requireNonNull(serverAdapter, "serverAdapter");
    kitAdapter = Objects.requireNonNull(kitAdapter, "kitAdapter");
    gameRegistry = Objects.requireNonNull(gameRegistry, "gameRegistry");
    authEnabled = authEnabled == null ? () -> true : authEnabled;
  }
}
