package com.sexidium.core.game.experience.compose;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shared base for every experience contributor list — drop transforms, damage handlers, health
 * sources, … — so they all expose the SAME {@code register}/{@code unregister}/{@code isEmpty} API
 * and the same lazy-ordering semantics. Subclasses supply an optional {@link Comparator}: when present
 * (drops, damage) {@link #contributors()} returns the list in comparator order; when absent (health,
 * which resolves a winner by priority on every read) it returns insertion order. Registration is the
 * single seam the live editor / Chaos cycle uses to bind and unbind a challenge's mechanics.
 */
public abstract class ContributorRegistry<C> {
  private final List<C> contributors = new ArrayList<>();
  private final Comparator<C> ordering;
  private boolean sorted = true;

  protected ContributorRegistry() {
    this.ordering = null;
  }

  protected ContributorRegistry(Comparator<C> ordering) {
    this.ordering = ordering;
  }

  public final void register(C contributor) {
    if (contributor != null) {
      contributors.add(contributor);
      if (ordering != null) {
        sorted = false;
      }
    }
  }

  /** Removes a previously registered contributor (live challenge removal / chaos cycle reset). */
  public final void unregister(C contributor) {
    if (contributor != null) {
      contributors.remove(contributor);
    }
  }

  public final boolean isEmpty() {
    return contributors.isEmpty();
  }

  /** The registered contributors, lazily ordered when a comparator was supplied (else insertion order). */
  protected final List<C> contributors() {
    if (!sorted) {
      contributors.sort(ordering);
      sorted = true;
    }
    return contributors;
  }
}
