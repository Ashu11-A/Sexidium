package com.sexidium.paper.adapter.util;

/**
 * The one soft-depend probe every gate shares.
 *
 * <p>{@code Class.forName} with {@code initialize=false}, catching {@link LinkageError} as well as
 * {@link ClassNotFoundException}: {@code initialize=false} links without running static initialisers,
 * and the {@code LinkageError} catch is what separates "absent" from "present but incompatible" — an
 * API too old or too new to link must degrade to "no", not crash whoever asked. Catching only
 * {@code ClassNotFoundException} lets {@code UnsupportedClassVersionError} escape and kill plugin
 * enable.</p>
 *
 * <p>Why one class and not a copy per gate: the copies drift. The version of this probe that omitted
 * {@code LinkageError} lived in four gates at once, and each was one incompatible soft-depend away from
 * taking the menu open — or the whole enable — down with it. Every new gate calls one of the two
 * methods below rather than writing its own {@code try}.</p>
 */
public final class PlatformProbes {
  private PlatformProbes() {
  }

  /** Whether {@code fqcn} can be linked on {@code cl} right now. Never throws. */
  public static boolean linkable(String fqcn, ClassLoader cl) {
    return linkableClass(fqcn, cl) != null;
  }

  /** Whether {@code fqcn} can be linked on this plugin's own loader. Never throws. */
  public static boolean linkable(String fqcn) {
    return linkable(fqcn, PlatformProbes.class.getClassLoader());
  }

  /**
   * The linked class, or null when it is absent or cannot link here. Never throws, and never runs the
   * class's static initialisers — callers that go on to reflect members need the {@link Class} itself,
   * not just a yes/no.
   */
  public static Class<?> linkableClass(String fqcn, ClassLoader cl) {
    try {
      return Class.forName(fqcn, /*initialize*/ false, cl);
    } catch (ClassNotFoundException | LinkageError unavailable) {
      return null;
    }
  }

  /** {@link #linkableClass(String, ClassLoader)} on this plugin's own loader. */
  public static Class<?> linkableClass(String fqcn) {
    return linkableClass(fqcn, PlatformProbes.class.getClassLoader());
  }
}
