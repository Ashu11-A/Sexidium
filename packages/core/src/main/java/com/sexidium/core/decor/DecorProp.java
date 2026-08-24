package com.sexidium.core.decor;
import com.sexidium.core.decor.DecorTypes.*;

/**
 * A platform-agnostic description of one in-world decor display entity (an animated lobby-hub
 * centerpiece, an NPC podium's spinning mode icon, a glowing pedestal, …). This is pure data with no
 * Bukkit / JOML imports, mirroring {@link com.sexidium.core.world.npc.NpcDefinition}, so the {@link DecorManager}
 * decisions that build props stay headless-testable; the {@code PaperDecorAdapter} turns each prop into a
 * native {@code ItemDisplay}/{@code BlockDisplay}/{@code TextDisplay}/{@code Interaction}.
 *
 * <p>{@code baseItem} is a vanilla material id (e.g. {@code minecraft:clock}) used for an
 * {@code ITEM_DISPLAY} so a Java client that declined the resource pack still sees a sensible item;
 * {@code itemModelId} is the additive {@code sexidium:<section>/<name>} {@code item_model} overlay that
 * pack-loaded clients see (reuse {@link com.sexidium.core.menu.MenuArt#modeModel(String)} /
 * {@link com.sexidium.core.menu.MenuArt#model(String)}). {@code glowArgb} is a packed ARGB color the
 * adapter maps to the nearest named glow color via a scoreboard team.</p>
 */
public record DecorProp(
    String id,
    DecorKind kind,
    String world,
    double x,
    double y,
    double z,
    float yaw,
    float pitch,
    String baseItem,
    String itemModelId,
    String blockData,
    String text,
    float scale,
    DecorBillboard billboard,
    boolean glowing,
    Integer glowArgb,
    DecorAnimation animation) {

  public DecorProp {
    kind = kind == null ? DecorKind.ITEM_DISPLAY : kind;
    billboard = billboard == null ? DecorBillboard.FIXED : billboard;
    animation = animation == null ? DecorAnimation.NONE : animation;
    scale = scale <= 0.0F ? 1.0F : scale;
  }

  /** An {@code ITEM_DISPLAY} prop: a base vanilla item plus an additive custom {@code item_model}. */
  public static DecorProp item(String id, String world, double x, double y, double z,
      String baseItem, String itemModelId, float scale, DecorBillboard billboard,
      boolean glowing, Integer glowArgb, DecorAnimation animation) {
    return new DecorProp(id, DecorKind.ITEM_DISPLAY, world, x, y, z, 0.0F, 0.0F,
        baseItem, itemModelId, null, null, scale, billboard, glowing, glowArgb, animation);
  }

  /** A {@code BLOCK_DISPLAY} prop (a pedestal / frame), optionally glowing. */
  public static DecorProp block(String id, String world, double x, double y, double z,
      String blockData, float scale, boolean glowing, Integer glowArgb) {
    return new DecorProp(id, DecorKind.BLOCK_DISPLAY, world, x, y, z, 0.0F, 0.0F,
        null, null, blockData, null, scale, DecorBillboard.FIXED, glowing, glowArgb, DecorAnimation.NONE);
  }

  /** A {@code TEXT_DISPLAY} sign that always faces the viewer. */
  public static DecorProp text(String id, String world, double x, double y, double z,
      String text, float scale) {
    return new DecorProp(id, DecorKind.TEXT_DISPLAY, world, x, y, z, 0.0F, 0.0F,
        null, null, null, text, scale, DecorBillboard.CENTER, false, null, DecorAnimation.NONE);
  }
}
