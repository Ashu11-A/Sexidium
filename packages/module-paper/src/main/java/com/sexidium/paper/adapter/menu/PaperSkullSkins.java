package com.sexidium.paper.adapter.menu;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.sexidium.paper.adapter.util.SkinsRestorerSupport;
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
  // Resolved once through the shared probe (SkinsRestorerSupport): whether the API is on the classpath
  // and how to read THIS consumer's storage. FALSE-state = absent/unusable (skip forever).
  private static volatile boolean skinsRestorerProbed;
  private static Object skinsRestorerPlayerStorage;
  private static Method getSkinOfPlayer;

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
      String value = (String) SkinsRestorerSupport.propertyValue().invoke(skinProperty);
      String signature = (String) SkinsRestorerSupport.propertySignature().invoke(skinProperty);
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
    if (skinsRestorerProbed) {
      return skinsRestorerPlayerStorage != null;
    }
    synchronized (PaperSkullSkins.class) {
      if (skinsRestorerProbed) {
        return skinsRestorerPlayerStorage != null;
      }
      try {
        Object skinsRestorer = com.sexidium.paper.adapter.util.SkinsRestorerSupport.apiOrNull();
        Object playerStorage = skinsRestorer.getClass().getMethod("getPlayerStorage").invoke(skinsRestorer);
        Method lookup = playerStorage.getClass().getMethod("getSkinOfPlayer", UUID.class);
        skinsRestorerPlayerStorage = playerStorage;
        getSkinOfPlayer = lookup;
      } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
        // NOT remembered: this probes a PLUGIN, and plugins enable late. One lookup arriving before
        // SkinsRestorer had enabled used to pin the answer to "no" for the whole session.
        skinsRestorerPlayerStorage = null;
        return false;
      }
      skinsRestorerProbed = true;
      return skinsRestorerPlayerStorage != null;
    }
  }
}
