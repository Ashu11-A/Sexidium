package com.sexidium.core.game;

import com.sexidium.core.platform.MobHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.BlockPosition;
import com.sexidium.core.platform.model.DamageCauseType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.WorldPosition;

/**
 * Every game-event type in one cohesive place (formerly one file each). {@link GameEventRouter} is the
 * dispatcher and stays separate. Each type keeps its original simple name, so a call site only changes
 * its import to {@code com.sexidium.core.game.GameEvents.<Name>}; pattern matching and field access are
 * unchanged. The hierarchy: a sealed {@link GameEvent} root, a non-sealed {@link CancellableGameEvent}
 * branch (with the shared {@link AbstractCancellableGameEvent} base), and immutable record events for the
 * purely-observational signals.
 */
public final class GameEvents {
  private GameEvents() {
  }

  /** Root of the experience/game event hierarchy. */
  public sealed interface GameEvent
      permits CancellableGameEvent, EntityDeathGameEvent, MobDamageGameEvent, PlayerAdvancementGameEvent,
      PlayerChangedWorldGameEvent, PlayerDeathGameEvent, PlayerJoinGameEvent, PlayerJumpGameEvent,
      PlayerQuitGameEvent, PlayerRespawnGameEvent, PlayerToggleSneakGameEvent {
  }

  /** A game event whose default outcome a challenge may cancel. */
  public non-sealed interface CancellableGameEvent extends GameEvent {
    boolean cancelled();

    void setCancelled(boolean cancelled);
  }

  /** Shared cancellable-flag base. */
  public abstract static class AbstractCancellableGameEvent implements CancellableGameEvent {
    private boolean cancelled;

    @Override
    public final boolean cancelled() {
      return cancelled;
    }

    @Override
    public final void setCancelled(boolean cancelled) {
      this.cancelled = cancelled;
    }
  }

  public static final class BlockBreakGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter playerAdapter;
    private final BlockPosition blockPosition;
    private final ItemKey blockKey;
    private boolean dropItems = true;

    public BlockBreakGameEvent(PlayerAdapter playerAdapter, BlockPosition blockPosition, ItemKey blockKey) {
      this.playerAdapter = playerAdapter;
      this.blockPosition = blockPosition;
      this.blockKey = blockKey;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    public BlockPosition blockPosition() {
      return blockPosition;
    }

    public ItemKey blockKey() {
      return blockKey;
    }

    public boolean dropItems() {
      return dropItems;
    }

    public void setDropItems(boolean dropItems) {
      this.dropItems = dropItems;
    }
  }

  public static final class BlockPlaceGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter playerAdapter;
    private final BlockPosition blockPosition;
    private final ItemKey blockKey;

    public BlockPlaceGameEvent(PlayerAdapter playerAdapter, BlockPosition blockPosition, ItemKey blockKey) {
      this.playerAdapter = playerAdapter;
      this.blockPosition = blockPosition;
      this.blockKey = blockKey;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    public BlockPosition blockPosition() {
      return blockPosition;
    }

    public ItemKey blockKey() {
      return blockKey;
    }
  }

  public static final class PlayerDamageGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter victim;
    private final PlayerAdapter attacker;
    private final DamageCauseType damageCauseType;
    private final double finalDamage;

    public PlayerDamageGameEvent(
        PlayerAdapter victim,
        PlayerAdapter attacker,
        DamageCauseType damageCauseType,
        double finalDamage
    ) {
      this.victim = victim;
      this.attacker = attacker;
      this.damageCauseType = damageCauseType == null ? DamageCauseType.UNKNOWN : damageCauseType;
      this.finalDamage = Math.max(0.0, finalDamage);
    }

    public PlayerAdapter victim() {
      return victim;
    }

    public PlayerAdapter attacker() {
      return attacker;
    }

    public DamageCauseType damageCauseType() {
      return damageCauseType;
    }

    public double finalDamage() {
      return finalDamage;
    }
  }

  public static final class PlayerInteractGameEvent extends AbstractCancellableGameEvent {
    public enum ActionType {
      LEFT_CLICK,
      RIGHT_CLICK,
      PHYSICAL,
      UNKNOWN
    }

    private final PlayerAdapter playerAdapter;
    private final ActionType actionType;
    private final ItemKey itemKey;
    private final BlockPosition blockPosition;

    public PlayerInteractGameEvent(
        PlayerAdapter playerAdapter,
        ActionType actionType,
        ItemKey itemKey,
        BlockPosition blockPosition
    ) {
      this.playerAdapter = playerAdapter;
      this.actionType = actionType == null ? ActionType.UNKNOWN : actionType;
      this.itemKey = itemKey;
      this.blockPosition = blockPosition;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    public ActionType actionType() {
      return actionType;
    }

    public ItemKey itemKey() {
      return itemKey;
    }

    public BlockPosition blockPosition() {
      return blockPosition;
    }
  }

  public static final class PlayerMoveGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter playerAdapter;
    private final WorldPosition fromPosition;
    private final WorldPosition toPosition;

    public PlayerMoveGameEvent(PlayerAdapter playerAdapter, WorldPosition fromPosition, WorldPosition toPosition) {
      this.playerAdapter = playerAdapter;
      this.fromPosition = fromPosition;
      this.toPosition = toPosition;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    public WorldPosition fromPosition() {
      return fromPosition;
    }

    public WorldPosition toPosition() {
      return toPosition;
    }
  }

  public static final class InventoryChangeGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter playerAdapter;

    public InventoryChangeGameEvent(PlayerAdapter playerAdapter) {
      this.playerAdapter = playerAdapter;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }
  }

  /**
   * A player threw an item on the ground, BEFORE it happens — so a mode can refuse it.
   *
   * <p>Separate from {@link InventoryChangeGameEvent}, which a drop also produces, because the two mean
   * different things: the change event is a "your inventory is now different, reconcile" signal that
   * several sources raise after the fact, and giving it a meaningful cancel would start feeding refusals
   * to code that only ever wanted to be told. A refused drop raises no change event at all — nothing
   * changed.</p>
   */
  public static final class PlayerDropItemGameEvent extends AbstractCancellableGameEvent {
    private final PlayerAdapter playerAdapter;
    private final ItemKey itemKey;

    public PlayerDropItemGameEvent(PlayerAdapter playerAdapter, ItemKey itemKey) {
      this.playerAdapter = playerAdapter;
      this.itemKey = itemKey;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    /** What was thrown, or null when the platform could not identify it. */
    public ItemKey itemKey() {
      return itemKey;
    }
  }

  public record EntityDeathGameEvent(String entityType, WorldPosition deathPosition) implements GameEvent {
  }

  /**
   * A mob (non-player living entity) took damage caused by a player. Unlike {@link PlayerDamageGameEvent}
   * (fires when the victim is a player), this fires when a mob is the victim — the signal the "Mobs
   * Duplicate When Hit" challenge needs. Non-cancellable: the damage already happened.
   */
  public record MobDamageGameEvent(MobHandle mob, PlayerAdapter attacker, double amount) implements GameEvent {
  }

  public record PlayerAdvancementGameEvent(PlayerAdapter playerAdapter, String advancementId) implements GameEvent {
  }

  public record PlayerChangedWorldGameEvent(PlayerAdapter playerAdapter, String fromWorld, String toWorld) implements GameEvent {
  }

  /**
   * A participant DIED — fired at the death itself, not at the respawn that may follow it.
   *
   * <p>The distinction matters. A respawn is not a reliable proxy for a death: in a hardcore world the
   * client's death screen offers no Respawn button at all, so a mode that waited for the respawn event
   * to notice a death would wait for ever — which is exactly how a lost hardcore world was never marked
   * as lost. Anything that must happen "when a player dies" belongs here.</p>
   *
   * @param deathPosition where they died, or null when the platform cannot report it
   */
  public record PlayerDeathGameEvent(PlayerAdapter playerAdapter, WorldPosition deathPosition)
      implements GameEvent {
  }

  public record PlayerJoinGameEvent(PlayerAdapter playerAdapter) implements GameEvent {
  }

  public record PlayerQuitGameEvent(PlayerAdapter playerAdapter) implements GameEvent {
  }

  /**
   * A player is respawning. Carries where the platform intends to put them ({@link #vanillaPosition()} —
   * a bed / respawn anchor, or the world spawn) and lets a game REDIRECT that via
   * {@link #setRespawnPosition}. Redirecting through the event means the platform places the player
   * correctly in the first place; a post-hoc teleport would fight the respawn placement and flicker.
   */
  public static final class PlayerRespawnGameEvent implements GameEvent {
    private final PlayerAdapter playerAdapter;
    private final WorldPosition vanillaPosition;
    private WorldPosition respawnPosition;

    public PlayerRespawnGameEvent(PlayerAdapter playerAdapter) {
      this(playerAdapter, null);
    }

    public PlayerRespawnGameEvent(PlayerAdapter playerAdapter, WorldPosition vanillaPosition) {
      this.playerAdapter = playerAdapter;
      this.vanillaPosition = vanillaPosition;
    }

    public PlayerAdapter playerAdapter() {
      return playerAdapter;
    }

    /** Where the platform would respawn the player if nothing redirects it; null when unknown. */
    public WorldPosition vanillaPosition() {
      return vanillaPosition;
    }

    /** The redirect a game asked for, or null to let {@link #vanillaPosition()} stand. */
    public WorldPosition respawnPosition() {
      return respawnPosition;
    }

    public void setRespawnPosition(WorldPosition respawnPosition) {
      this.respawnPosition = respawnPosition;
    }
  }

  public record PlayerToggleSneakGameEvent(PlayerAdapter playerAdapter, boolean sneaking) implements GameEvent {
  }

  /**
   * A participant pressed JUMP — the input, never merely upward motion.
   *
   * <p>This is deliberately not derivable from {@link PlayerMoveGameEvent}: mob knockback, an explosion
   * punt and a piston all produce the same upward Y delta, and the modes that consume this one (Jump
   * Multiplies) are built on the promise that being launched is not the player's fault. The platform is
   * therefore asked for its genuine jump signal and the bridge vetoes anything that followed a launch;
   * where a platform has no such signal the event simply never fires.</p>
   *
   * <p>Non-cancellable on purpose. Paper rubber-bands a refused jump back to {@code getFrom()}, so a mode
   * that "declined" a jump would teleport the player instead — the trigger is a fact to react to, not a
   * decision to make.</p>
   *
   * @param fromPosition where the player was standing when they jumped; null when the platform cannot say
   */
  public record PlayerJumpGameEvent(PlayerAdapter playerAdapter, WorldPosition fromPosition) implements GameEvent {
  }
}
