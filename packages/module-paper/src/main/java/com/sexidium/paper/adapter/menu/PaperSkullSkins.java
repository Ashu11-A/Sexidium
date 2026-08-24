package com.sexidium.paper.adapter.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

/**
 * Textures a chest-GUI {@code player_head} with the skin its owner actually wears, so the friends /
 * party / picker rosters show real faces instead of a wall of default Steve heads (the old behaviour,
 * since nothing ever set the skull's profile). This matters most for mobile/Bedrock players, who pick
 * teammates and friends from these rosters by face.
 *
 * <p>Resolution order, best skin first:</p>
 * <ol>
 *   <li><b>Online player</b> — the live {@link Player#getPlayerProfile()} already carries whatever skin
 *       the player wears right now, including a SkinsRestorer {@code /skin} or a Floodgate/Bedrock skin.
 *       This is the common case (rosters are mostly online players) and needs no extra plugin.</li>
 *   <li><b>SkinsRestorer</b> (optional, soft-depend) — for an <em>offline</em> player we ask
 *       SkinsRestorer for the skin they set with {@code /skin}, applied via reflection so the plugin is
 *       a true soft dependency (absent → skipped, never a {@link ClassNotFoundException} at load).</li>
 *   <li><b>Offline fallback</b> — resolve the stored Mojang profile by UUID; better than nothing.</li>
 * </ol>
 */
final class PaperSkullSkins {
  // Resolved once: whether the SkinsRestorer API is on the classpath and how to read a stored skin.
  // null = not yet probed; FALSE-state = absent/unusable (skip forever); present = cached method handles.
  private static volatile Boolean skinsRestorerProbed;
  private static Object skinsRestorerPlayerStorage;
  private static Method getSkinOfPlayer;
  private static Method skinPropertyValue;
  private static Method skinPropertySignature;

  private PaperSkullSkins() {
  }

  /** Apply {@code owner}'s real skin to the head meta. No-op when either argument is null. */
  static void apply(SkullMeta meta, UUID owner) {
    if (meta == null || owner == null) {
      return;
    }
    Player online = Bukkit.getPlayer(owner);
    if (online != null) {
      meta.setPlayerProfile(online.getPlayerProfile());
      return;
    }
    if (applyFromSkinsRestorer(meta, owner)) {
      return;
    }
    meta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
  }

  private static boolean applyFromSkinsRestorer(SkullMeta meta, UUID owner) {
    if (!ensureSkinsRestorer()) {
      return false;
    }
    try {
      Object result = getSkinOfPlayer.invoke(skinsRestorerPlayerStorage, owner);
      if (!(result instanceof Optional<?> optional) || optional.isEmpty()) {
        return false;
      }
      Object skinProperty = optional.get();
      String value = (String) skinPropertyValue.invoke(skinProperty);
      String signature = (String) skinPropertySignature.invoke(skinProperty);
      if (value == null || value.isBlank()) {
        return false;
      }
      PlayerProfile profile = Bukkit.createProfile(owner);
      profile.setProperty(new ProfileProperty("textures", value, signature));
      meta.setPlayerProfile(profile);
      return true;
    } catch (ReflectiveOperationException | RuntimeException exception) {
      // A SkinsRestorer API change or a transient lookup failure must never break the menu — fall back.
      return false;
    }
  }

  private static boolean ensureSkinsRestorer() {
    Boolean probed = skinsRestorerProbed;
    if (probed != null) {
      return probed && skinsRestorerPlayerStorage != null;
    }
    synchronized (PaperSkullSkins.class) {
      if (skinsRestorerProbed != null) {
        return skinsRestorerProbed && skinsRestorerPlayerStorage != null;
      }
      try {
        Class<?> provider = Class.forName("net.skinsrestorer.api.SkinsRestorerProvider");
        Object skinsRestorer = provider.getMethod("get").invoke(null);
        Object playerStorage = skinsRestorer.getClass().getMethod("getPlayerStorage").invoke(skinsRestorer);
        Method lookup = playerStorage.getClass().getMethod("getSkinOfPlayer", UUID.class);
        Class<?> skinProperty = Class.forName("net.skinsrestorer.api.property.SkinProperty");
        skinPropertyValue = skinProperty.getMethod("getValue");
        skinPropertySignature = skinProperty.getMethod("getSignature");
        skinsRestorerPlayerStorage = playerStorage;
        getSkinOfPlayer = lookup;
        skinsRestorerProbed = Boolean.TRUE;
      } catch (ReflectiveOperationException | RuntimeException exception) {
        skinsRestorerProbed = Boolean.FALSE;
      }
      return skinsRestorerProbed && skinsRestorerPlayerStorage != null;
    }
  }
}
