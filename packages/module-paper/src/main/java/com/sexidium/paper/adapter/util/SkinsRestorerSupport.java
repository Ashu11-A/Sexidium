package com.sexidium.paper.adapter.util;

import java.lang.reflect.Method;

/**
 * The one SkinsRestorer probe shared by its two consumers ({@code PaperSkullSkins} for menu avatars,
 * {@code PaperNpcSkinResolver} for NPC skins). Resolves {@code SkinsRestorerProvider#get()} and the
 * texture-property accessors exactly once; each consumer still binds its own storage API locally,
 * because they deliberately target different generations of it.
 *
 * <p>Everything here fails soft: absent plugin, changed API or a hostile classloader all answer
 * {@code null} — a missing skin must never cost more than the skin.
 *
 * <h2>Only success is cached</h2>
 * A FAILED probe is retried on the next call, because this probes a <em>plugin</em> and plugins enable
 * late. Caching the first "no" for the process lifetime meant that one avatar request arriving during
 * another plugin's {@code onEnable} — before SkinsRestorer had enabled — turned skins off for the whole
 * session, and made the capability registry report the misleading "installed but its storage API does
 * not link" for a plugin that was merely not ready yet. Server facts are probed once; plugin facts are
 * re-asked, exactly as {@code BetterHudLink.available()} does.
 */
public final class SkinsRestorerSupport {
  private static final String PROVIDER_CLASS = "net.skinsrestorer.api.SkinsRestorerProvider";

  private static volatile boolean bound;
  private static volatile Object api;
  private static volatile Method propertyValue;
  private static volatile Method propertySignature;

  private SkinsRestorerSupport() {
  }

  /**
   * The SkinsRestorer API instance, or null when it cannot be reached right now. Resolved once on
   * success; re-attempted after a failure, because the plugin may not have enabled yet.
   */
  public static Object apiOrNull() {
    if (bound) {
      return api;
    }
    synchronized (SkinsRestorerSupport.class) {
      if (bound) {
        return api;
      }
      try {
        Class<?> provider = PlatformProbes.linkableClass(PROVIDER_CLASS,
            SkinsRestorerSupport.class.getClassLoader());
        if (provider == null) {
          return null;
        }
        Object resolved = provider.getMethod("get").invoke(null);
        Class<?> skinProperty = PlatformProbes.linkableClass("net.skinsrestorer.api.property.SkinProperty",
            SkinsRestorerSupport.class.getClassLoader());
        if (resolved == null || skinProperty == null) {
          return null;
        }
        propertyValue = skinProperty.getMethod("getValue");
        propertySignature = skinProperty.getMethod("getSignature");
        api = resolved;
        // Written LAST: the volatile write publishes the three fields above to every other thread.
        bound = true;
        return api;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError notReady) {
        propertyValue = null;
        propertySignature = null;
        return null;
      }
    }
  }

  /** {@code SkinProperty#getValue()} — the base64 texture. Null when SkinsRestorer is unusable. */
  public static Method propertyValue() {
    apiOrNull();
    return propertyValue;
  }

  /** {@code SkinProperty#getSignature()} — the Mojang signature. Null when SkinsRestorer is unusable. */
  public static Method propertySignature() {
    apiOrNull();
    return propertySignature;
  }

  /** Whether SkinsRestorer resolved to something callable. */
  public static boolean available() {
    return apiOrNull() != null && propertyValue != null && propertySignature != null;
  }
}
