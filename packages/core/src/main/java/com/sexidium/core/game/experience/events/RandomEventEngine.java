package com.sexidium.core.game.experience.events;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * A reusable random-event engine: holds a weighted set of {@link RandomEvent}s and fires one at a time.
 * It is intentionally decoupled from any specific challenge — any challenge (or a test) can hand it a
 * {@link RandomEventContext} and call {@link #fire}.
 *
 * <p>Selection is a weighted <em>shuffle bag</em>: an event that has already fired since the last reset is
 * given a much lower recurrence weight ({@code weight / RECURRENCE_DIVISOR}), so events that have <em>not
 * yet</em> occurred are strongly preferred; once every event has fired at least once the bag resets and all
 * weights return to full. This spreads the catalog out — you see fresh events before repeats — while still
 * allowing a rare early repeat. The engine works with any non-empty catalog, so "more than 20" events is
 * just a longer list.</p>
 */
public final class RandomEventEngine {
  /** A fired event's weight is divided by this until the bag resets (higher = rarer repeats). */
  private static final int RECURRENCE_DIVISOR = 8;

  private final List<RandomEvent> events;
  private final Set<String> firedSinceReset = new HashSet<>();

  public RandomEventEngine(List<RandomEvent> events) {
    if (events == null || events.isEmpty()) {
      throw new IllegalArgumentException("event engine needs at least one event");
    }
    this.events = List.copyOf(events);
  }

  public int size() {
    return events.size();
  }

  public List<RandomEvent> events() {
    return events;
  }

  /** How many distinct events have fired since the last bag reset (0 right after a reset). */
  public int firedCount() {
    return firedSinceReset.size();
  }

  /**
   * Weighted-random selection where already-fired events are down-weighted (see the class doc). Records the
   * choice, and resets the bag once every event has fired at least once.
   */
  public RandomEvent pick(Random random) {
    int[] effective = new int[events.size()];
    int total = 0;
    for (int index = 0; index < events.size(); index++) {
      RandomEvent event = events.get(index);
      effective[index] = firedSinceReset.contains(event.id())
          ? Math.max(1, event.weight() / RECURRENCE_DIVISOR)
          : event.weight();
      total += effective[index];
    }
    int target = random.nextInt(total);
    int cumulative = 0;
    int index = 0;
    // Walk until the target falls in a bucket, or the last event soaks up the remaining weight.
    while (true) {
      cumulative += effective[index];
      if (index == events.size() - 1 || target < cumulative) {
        break;
      }
      index++;
    }
    RandomEvent chosen = events.get(index);
    markFired(chosen);
    return chosen;
  }

  /** Picks an event, announces it, and runs it against {@code context}. Returns the fired event. */
  public RandomEvent fire(RandomEventContext context) {
    RandomEvent event = pick(context.random());
    context.announce("<gold>⚡ Random Event: <yellow>" + event.name() + "</yellow></gold>");
    event.run(context);
    return event;
  }

  private void markFired(RandomEvent event) {
    firedSinceReset.add(event.id());
    if (firedSinceReset.size() >= events.size()) {
      firedSinceReset.clear(); // every event has fired — refill the bag
    }
  }
}
