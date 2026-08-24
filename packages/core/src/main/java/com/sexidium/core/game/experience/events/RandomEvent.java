package com.sexidium.core.game.experience.events;

import java.util.function.Consumer;

/**
 * One random event: a stable {@code id}, a player-facing {@code name}, a selection {@code weight}
 * (higher = more likely) and the {@code action} that carries it out against a {@link RandomEventContext}.
 * Events are plain values, so a catalog is just a list of them and new events plug in without touching the
 * engine — the events engine "supports more than 20" by construction.
 */
public record RandomEvent(String id, String name, int weight, Consumer<RandomEventContext> action) {
  public RandomEvent {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("event id cannot be blank");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("event name cannot be blank");
    }
    if (action == null) {
      throw new IllegalArgumentException("event action cannot be null");
    }
    weight = Math.max(1, weight);
  }

  /** Convenience for an evenly-weighted event. */
  public static RandomEvent of(String id, String name, Consumer<RandomEventContext> action) {
    return new RandomEvent(id, name, 10, action);
  }

  /** Runs the event against the given context. */
  public void run(RandomEventContext context) {
    action.accept(context);
  }
}
