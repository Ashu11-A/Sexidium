package com.sexidium.core.platform.capability;

/**
 * One thing a server backend may or may not be able to do at runtime, named once so every layer —
 * adapters, boot log, admin command — uses the same word for it.
 *
 * <p>This is capability-not-version: a constant here is answered by PROBING the running server, not
 * by comparing version numbers. Version numbers are the tiebreaker only for what cannot be probed
 * (see {@code DIMENSION_STORAGE_KEYED}), because probing survived the last Minecraft update and
 * version-keying is what broke.
 */
public enum Capability {
  /**
   * {@code ItemMeta#setItemModel(NamespacedKey)} — custom item-model components on UI/hotbar items.
   * Absent on older servers; the degradation is the vanilla material icon.
   */
  ITEM_MODEL_COMPONENT,

  /**
   * Re-sending {@code ClientboundLoginPacket} to flip a client's hardcore-heart rendering. Probed by
   * reflecting the packet's record components against the names the builder knows how to fill — an
   * unrecognised component means "no", never "send it anyway".
   */
  HARDCORE_VIEW_PACKET,

  /**
   * Keyed dimension storage ({@code world/dimensions/<namespace>/<key>}). The one probe that cannot be
   * done from the API surface alone, so it falls back to the server version — MC 26.1 or newer.
   */
  DIMENSION_STORAGE_KEYED,

  /** The region/entity/async scheduler family (Folia-safe scheduling), including GlobalRegionScheduler. */
  FOLIA_REGION_SCHEDULER,

  /** Native Cumulus forms for Bedrock players (Floodgate/Geyser present and linkable); chest GUI otherwise. */
  BEDROCK_FORMS,

  /** Offline skin lookup through SkinsRestorer, for rank-card avatars and NPC skins. */
  SKIN_LOOKUP_OFFLINE,

  /** The BetterHud overlay surface. False when BetterHud is absent OR installed-but-mismatched. */
  HUD_OVERLAY,

  /** Lobby NPCs via FancyNpcs + FancyHolograms (both installed). No backend means no NPCs spawn. */
  LOBBY_NPCS,
}
