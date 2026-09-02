package com.sexidium.core.platform.backend;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Backends stacked in preference order: the platform's implementation first, guaranteed floors behind
 * it. {@link #capabilities()} is the union — a spec only needs ONE layer to serve it — and
 * {@link #select(Object)} walks the stack asking each layer what it can draw <em>right now</em>.
 *
 * <p>This is the reusable half of {@code HudDriverStack}'s idea. What it deliberately does NOT
 * generalise is HUD's per-player composition (platform handle + sidebar fallback wrapped into one
 * composite) — that contract stays where it belongs; stacks whose layers are mutually exclusive
 * select through here instead.
 *
 * @param <T> the capability vocabulary every layer reports in
 */
public final class BackendStack<T> implements Backend<T> {

  private final List<Backend<T>> layers;

  private BackendStack(List<Backend<T>> layers) {
    this.layers = List.copyOf(layers);
  }

  /** Stacks {@code first} over every remaining layer, most preferred first. */
  @SafeVarargs
  public static <T> BackendStack<T> of(Backend<T> first, Backend<T>... rest) {
    if (first == null) {
      throw new IllegalArgumentException("a backend stack needs at least one layer");
    }
    var stacked = new java.util.ArrayList<Backend<T>>(1 + (rest == null ? 0 : rest.length));
    stacked.add(first);
    if (rest != null) {
      for (Backend<T> layer : rest) {
        if (layer != null) {
          stacked.add(layer);
        }
      }
    }
    return new BackendStack<>(stacked);
  }

  /** Everything ANY layer can serve right now, in stack order. Immutable. */
  @Override
  public Set<T> capabilities() {
    Set<T> all = new LinkedHashSet<>();
    for (Backend<T> layer : layers) {
      all.addAll(layer.capabilities());
    }
    return java.util.Collections.unmodifiableSet(all);
  }

  /**
   * Whether any layer can serve {@code capability} right now.
   *
   * <p>Overridden rather than inherited from {@link Backend}: the default asks {@link #capabilities()},
   * which re-walks and re-unions every layer on each call — quadratic once stacks nest, for a question
   * {@link #select} already answers by short-circuiting at the first layer that says yes.</p>
   */
  @Override
  public boolean supports(T capability) {
    return select(capability).isPresent();
  }

  /**
   * The first layer that can serve {@code capability}, most preferred first — or empty when no layer
   * can, which is the caller's signal to take its inert path.
   */
  public Optional<Backend<T>> select(T capability) {
    for (Backend<T> layer : layers) {
      if (layer.supports(capability)) {
        return Optional.of(layer);
      }
    }
    return Optional.empty();
  }

  /** The stack in preference order; first = most preferred. Immutable. */
  public List<Backend<T>> layers() {
    return layers;
  }

  /**
   * Closes every layer, least preferred first so the floors outlive the things layered over them.
   *
   * <p>Every layer is closed even when one throws: a stack exists precisely because its layers come
   * from different plugins, so letting the first failure abort the loop would leak the resources of
   * every layer behind it — the ones most likely to be ours. The first failure is rethrown once the
   * rest are closed, with any others attached to it.</p>
   */
  @Override
  public void close() {
    RuntimeException failure = null;
    for (int index = layers.size() - 1; index >= 0; index--) {
      try {
        layers.get(index).close();
      } catch (RuntimeException thrown) {
        if (failure == null) {
          failure = thrown;
        } else {
          failure.addSuppressed(thrown);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
  }
}
