package com.sexidium.paper.adapter.npc;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Resolves a skin identifier to its raw texture property through <b>SkinsRestorer</b>, applied entirely
 * via reflection so the plugin stays a true soft dependency (absent → {@link Optional#empty()}, never a
 * {@link ClassNotFoundException} at load).
 *
 * <p>Two lookups, mirroring how SkinsRestorer's own {@code /skin set} works:</p>
 * <ul>
 *   <li>{@link #resolve(String)} → {@code SkinStorage.findSkinData(String)}: offline and cheap (no network,
 *       main-thread safe), it returns an already-known skin (player name, custom id, or a URL skin already
 *       generated). Used at every NPC spawn.</li>
 *   <li>{@link #resolveOrCreate(String)} → {@code SkinStorage.findOrCreateSkinData(String)}: the same
 *       resolver SkinsRestorer's command uses, which additionally <b>generates</b> a skin from a URL via
 *       MineSkin and caches it. This does blocking network I/O and MUST run off the main thread; callers
 *       use it once on a {@link #resolve} miss to warm the cache, then re-apply to the live NPC.</li>
 * </ul>
 * <p>Callers fall back to FancyNpcs' own name-based lookup when both return empty.</p>
 */
final class PaperNpcSkinResolver {
  /** The raw Mojang texture property (base64 value + signature) for a resolved skin. */
  record TextureProperty(String value, String signature) {
  }

  // Resolved once through the shared probe (SkinsRestorerSupport); FALSE-state = absent/unusable.
  private static volatile boolean probed;
  private static Object skinStorage;
  private static Method findSkinData;
  private static Method findOrCreateSkinData;
  private static Method getProperty;

  private PaperNpcSkinResolver() {
  }

  /** Offline, main-thread-safe lookup of an already-known skin. */
  static Optional<TextureProperty> resolve(String skinIdentifier) {
    return lookup(skinIdentifier, false);
  }

  /**
   * Blocking lookup that generates the skin if needed (URL → MineSkin). Does network I/O — call only from
   * an async thread.
   */
  static Optional<TextureProperty> resolveOrCreate(String skinIdentifier) {
    return lookup(skinIdentifier, true);
  }

  private static Optional<TextureProperty> lookup(String skinIdentifier, boolean create) {
    if (skinIdentifier == null || skinIdentifier.isBlank() || !ensureSkinsRestorer()) {
      return Optional.empty();
    }
    try {
      Method lookup = create ? findOrCreateSkinData : findSkinData;
      Object result = lookup.invoke(skinStorage, skinIdentifier);
      if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
        return Optional.empty();
      }
      Object skinProperty = getProperty.invoke(optional.get());
      String value = (String) com.sexidium.paper.adapter.util.SkinsRestorerSupport.propertyValue().invoke(skinProperty);
      String signature = (String) com.sexidium.paper.adapter.util.SkinsRestorerSupport.propertySignature().invoke(skinProperty);
      if (value == null || value.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(new TextureProperty(value, signature));
    } catch (ReflectiveOperationException | RuntimeException exception) {
      // An API change, a bad URL, or a transient MineSkin/network failure must never break the spawn.
      return Optional.empty();
    }
  }

  private static boolean ensureSkinsRestorer() {
    if (probed) {
      return skinStorage != null;
    }
    synchronized (PaperNpcSkinResolver.class) {
      if (probed) {
        return skinStorage != null;
      }
      try {
        Object skinsRestorer = com.sexidium.paper.adapter.util.SkinsRestorerSupport.apiOrNull();
        Object storage = skinsRestorer.getClass().getMethod("getSkinStorage").invoke(skinsRestorer);
        Method find = storage.getClass().getMethod("findSkinData", String.class);
        Method findOrCreate = storage.getClass().getMethod("findOrCreateSkinData", String.class);
        Class<?> inputDataResult = Class.forName("net.skinsrestorer.api.property.InputDataResult");
        Method property = inputDataResult.getMethod("getProperty");
        skinStorage = storage;
        findSkinData = find;
        findOrCreateSkinData = findOrCreate;
        getProperty = property;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
        // NOT remembered: this probes a PLUGIN, and plugins enable late. One lookup arriving before
        // SkinsRestorer had enabled used to pin the answer to "no" for the whole session.
        skinStorage = null;
        return false;
      }
      probed = true;
      return skinStorage != null;
    }
  }
}
