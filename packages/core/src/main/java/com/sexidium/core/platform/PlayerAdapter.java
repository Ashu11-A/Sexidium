package com.sexidium.core.platform;

import com.sexidium.core.platform.model.EffectSnapshot;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.core.platform.model.SoundKey;
import com.sexidium.core.platform.model.TitleSpec;
import com.sexidium.core.platform.model.WorldPosition;

import java.util.List;
import java.util.UUID;

public interface PlayerAdapter extends NetworkPlayer {
  @Override
  UUID uniqueId();

  @Override
  boolean online();

  boolean dead();

  WorldAdapter world();

  WorldPosition position();

  void teleport(WorldPosition targetPosition);

  /**
   * Teleports, then reports the OUTCOME — true if the player arrived, false if the move did not happen.
   *
   * <p>A platform teleport is not necessarily complete when the call returns: Paper's is asynchronous,
   * because moving a player into a world whose destination chunk is not generated yet has to wait for
   * that chunk. Everything that is only true of an ARRIVED player therefore belongs in the callback —
   * re-asserting a game mode, setting a compass target, deciding whether a player still needs moving.</p>
   *
   * <p><b>The callback always runs</b>, exactly once, including on failure. That is the whole point of
   * the boolean, and it is deliberately not a {@code Runnable}: a success-only callback makes a refused
   * teleport indistinguishable from a slow one, so whatever the caller was tracking — a pending-move set,
   * a grace window, a retry — is never released and the player is stranded in a state nothing corrects.
   * A teleport can be refused for ordinary reasons: the destination world no longer resolves, another
   * plugin cancels the move, the destination chunk fails to load, or the player is still on a death
   * screen. Handle {@code false}; do not assume it cannot happen.</p>
   *
   * <p>The callback runs on the platform's main thread. It does not run if the player disconnects first,
   * so it must not be the only release path for anything that outlives the session.</p>
   *
   * <p>The default is for platforms whose teleport really is synchronous: move, then report success.</p>
   */
  default void teleport(WorldPosition targetPosition, java.util.function.Consumer<Boolean> onSettled) {
    teleport(targetPosition);
    if (onSettled != null) {
      onSettled.accept(Boolean.TRUE);
    }
  }

  GameModeType gameMode();

  void setGameMode(GameModeType gameModeType);

  double health();

  double maxHealth();

  void setHealth(double health);

  int foodLevel();

  void setFoodLevel(int foodLevel);

  InventoryAdapter inventory();

  /**
   * The item the player is currently holding in their main hand, or {@code null} when empty / unsupported.
   * Used by the map editor to keep its selection tools non-destructive in Creative mode (suppressing the
   * block break only while a tool is held) without blocking ordinary creative building. Default null so an
   * unported platform simply never suppresses.
   */
  default ItemKey heldItem() {
    return null;
  }

  void playSound(SoundKey soundKey, float volume, float pitch);

  void showTitle(TitleSpec titleSpec);

  void sendActionBar(String miniMessage);

  void setCompassTarget(WorldPosition targetPosition);

  /**
   * Shows the native item-cooldown overlay (the grey radial sweep) on every stack of the given item for
   * {@code ticks} ticks, so a player can see how long until an ability tied to that item is ready again.
   * Default no-op so a platform without a cooldown overlay simply shows nothing.
   */
  default void setItemCooldown(ItemKey itemKey, int ticks) {
  }

  /** Round-trip latency to the client in milliseconds, or -1 when the platform cannot report it. */
  default int ping() {
    return -1;
  }

  /**
   * Milliseconds since this client last did anything the server heard about, or -1 where the platform
   * cannot report it.
   *
   * <p>Deliberately NOT latency. {@link #ping()} is a rolling keep-alive average, so when a client stops
   * answering it FREEZES at its last good value instead of rising — which makes it useless for noticing
   * that somebody has dropped off the network. This is the other measurement: how long since the player
   * last acted, which keeps climbing for exactly as long as nothing is heard from them.</p>
   *
   * <p>What it therefore means is "this player is not driving their character" — a frozen client, a
   * connection on its way out, and somebody who simply walked away all read the same. That conflation is
   * intended: in a mode where one death costs everyone the world, all three want the same answer.</p>
   */
  default long idleMillis() {
    return -1L;
  }

  /** Runs a command as this player (leading slash optional). Used by clickable lobby NPCs. */
  default void performCommand(String command) {
  }

  /**
   * Whether this player is connected from Bedrock Edition (e.g. mobile via Geyser/Floodgate). The lobby
   * GUIs are fully click-driven so Bedrock players never need to type a command or a player name; this
   * lets callers route any remaining command hint to a GUI affordance instead. Detection is a UX hint
   * only (see {@link BedrockPlayers}); the default keys off the player's UUID and works on every
   * platform.
   */
  default boolean isBedrock() {
    return BedrockPlayers.isBedrockUuid(uniqueId());
  }

  default int experiencePoints() {
    return 0;
  }

  default void setExperiencePoints(int points) {
  }

  /**
   * Sets the vanilla XP bar's level number and green fill independently of the total-points coupling of
   * {@link #setExperiencePoints} — {@code level} is the number shown above the bar and {@code progress}
   * (clamped 0..1) is how full it is. The lobby uses this to render a player's progression points as a
   * bar that fills toward the next level; challenges that repurpose the XP bar as a health meter use it
   * the same way. Default no-op so a platform without an XP bar simply shows nothing.
   */
  default void setExperienceBar(int level, float progress) {
  }

  default void setHealthScale(double scale) {
  }

  /**
   * Shows a live counter under this player's nameplate (the vanilla {@code below_name} scoreboard slot),
   * visible above their head to everyone — e.g. "{@code 42 Blocks}". {@code label} names the counter,
   * {@code value} is the number. Used by the Random-Skyblock challenge to show blocks broken. Default
   * no-op on platforms without a below-name display.
   */
  default void setBelowName(String label, int value) {
  }

  /** Clears any below-name counter previously shown via {@link #setBelowName}. Default no-op. */
  default void clearBelowName() {
  }

  default void resetHealthScale() {
  }

  /**
   * Adds the given velocity vector to the player. Platforms that cannot apply
   * velocity may leave this as a no-op.
   */
  default void setVelocity(double velocityX, double velocityY, double velocityZ) {
  }

  /**
   * Replaces the player's velocity with exactly this vector (unlike {@link #setVelocity}, which is
   * additive). Use for a dash/launch that must behave the same whether the player is standing or already
   * falling — adding to a large downward fall velocity otherwise swallows the impulse mid-air. Default
   * falls back to the additive {@link #setVelocity}.
   */
  default void launch(double velocityX, double velocityY, double velocityZ) {
    setVelocity(velocityX, velocityY, velocityZ);
  }

  /**
   * Sets the player's flight speed for spectator/creative free-cam movement. Range -1..1; {@code 0.1} is
   * the vanilla "normal" speed. Used to keep the Fugitive head-start spectator camera from crawling.
   * Default no-op for platforms without a flight-speed control.
   */
  default void setFlySpeed(float speed) {
  }

  /**
   * Sets the player's body scale (size/hitbox). {@code 1.0} is normal size; values grow or shrink the
   * player. Used by the Endless-Growth challenge. Default no-op where the scale attribute is
   * unavailable.
   */
  default void setScale(double scale) {
  }

  /**
   * This player's view distance in chunks (the server-side send distance). Used to size per-player
   * render-distance-based effects such as the Break-One-Break-All sweep. Defaults to 10 where the
   * platform cannot report it.
   */
  default int viewDistanceChunks() {
    return 10;
  }

  /**
   * Adds a potion effect to the player (mirrors {@link MobHandle#addEffect}). {@code effectKey} is a
   * vanilla effect id such as {@code strength} or {@code resistance}. Default no-op.
   */
  default void addEffect(String effectKey, int amplifier, int durationTicks) {
  }

  /**
   * The player's currently active potion effects, so they can be captured into a snapshot and later
   * re-applied via {@link #addEffect(String, int, int)} on return. Default empty for platforms that
   * cannot enumerate effects.
   */
  default List<EffectSnapshot> activeEffects() {
    return List.of();
  }

  /**
   * Damages the item currently held in the main hand by {@code amount} durability points, breaking it
   * if its durability is exceeded. Used by the Total-Cleave challenge. Default no-op.
   */
  default void damageHeldItem(int amount) {
  }

  /**
   * Adds {@code level} of the given enchantment to one randomly chosen non-empty item in the player's
   * inventory (unsafe — applied even to items the enchantment would not normally accept). Used by the
   * Jump-Enchants challenge. Returns true when an item was actually enchanted. Default no-op returns
   * false.
   */
  default boolean enchantRandomItem(String enchantKey, int level) {
    return false;
  }

  /**
   * Ray-traces from the player's eyes along their line of sight (out to {@code maxDistance} blocks) and
   * duplicates whatever eligible entity is in the crosshair, up to {@code copies} extra copies: a mob
   * spawns same-type clones, a dropped item stack copies itself, an arrow spawns parallel arrows with a
   * slight spread (free multishot). The {@code includeItems}/{@code includeMobs}/{@code includeProjectiles}
   * flags gate which entity kinds are eligible. Used by the Look-Multiplies challenge. Returns the number
   * of copies actually spawned (0 when nothing eligible was looked at). Platforms cap the spawn count
   * defensively regardless of {@code copies}. Default no-op returns 0.
   */
  default int duplicateLookedAtEntity(double maxDistance, int copies,
      boolean includeItems, boolean includeMobs, boolean includeProjectiles) {
    return 0;
  }

  /**
   * Clones EVERY eligible entity within {@code radius} blocks of the player, {@code copiesPerEntity}
   * times each, and returns how many clones were actually spawned. Untargeted by design — this is the
   * seam behind the Jump-Multiplies challenge, whose whole rule is that a jump doubles everything nearby
   * at once, so it is deliberately not a variant of {@link #duplicateLookedAtEntity} (that one resolves
   * a single crosshair target and is a different question).
   *
   * <p>A clone is expected to be a <b>real</b> copy, not a same-type respawn: aggro target, taming and
   * its owner, baby state, equipment, custom name, item-stack NBT, a projectile's shooter and a primed
   * TNT's fuse all carry over, because a copy that forgets it was chasing you — or forgets whose wolf it
   * is — is a visibly different mechanic. Reconstructing from a type string or an {@link
   * com.sexidium.core.platform.model.ItemKey} does not qualify.</p>
   *
   * <p><b>Players are never duplicated</b>, whatever is in {@code kinds} — there is no
   * {@code DuplicableKind} for them. <b>Item entities copy per ENTITY, not per item</b>: a dropped stack
   * of 64 is one entity and yields one more entity carrying the same stack. That is the rule the mode's
   * signature exploit (throw the stack out as individual drops first, <em>then</em> jump) depends on.</p>
   *
   * <p>{@code maxSpawns} is the caller's total ceiling for this one call; platforms additionally apply
   * their own defensive cap, so a caller can never spend more than the platform is willing to. Returns 0
   * when nothing eligible is in range. Default no-op returns 0 — an unported platform simply never
   * duplicates, which is the parity pattern, so the challenge stays selectable and inert there.</p>
   */
  default int duplicateNearbyEntities(double radius, int copiesPerEntity, int maxSpawns,
      java.util.Set<com.sexidium.core.platform.model.DuplicableKind> kinds) {
    return 0;
  }

  /**
   * Whether this platform really implements {@link #duplicateNearbyEntities}. Lets a mode tell "nothing
   * was nearby" apart from "this platform cannot do it at all" — both return 0 — so a HUD can say which.
   * Default false; a platform that implements the seam overrides it to true.
   */
  default boolean supportsEntityDuplication() {
    return false;
  }

  void clearInventory();

  void clearPotionEffects();

  /**
   * Removes every boss bar currently shown to this player. Platforms that cannot enumerate a player's
   * boss bars may leave this as a no-op (game-owned boss bars are still hidden via the per-match UI
   * release), but Paper clears any lingering bar here.
   */
  default void clearBossBars() {
  }

  /**
   * Clears any title, subtitle and action-bar text currently displayed to the player.
   */
  default void clearTitle() {
  }

  /**
   * Resets the player's compass needle back to its natural target (the spawn point of their world),
   * undoing any per-player compass override such as the fugitive tracker.
   */
  default void resetCompass() {
  }

  /**
   * Tells this player's CLIENT whether it is in a hardcore world — the hardened heart texture and the
   * "you cannot respawn here" death screen. Distinct from the world's own hardcore flag (which the
   * server uses for difficulty and death handling), because the two live in different places: the world
   * flag is server state that can be changed at any time, while the client is told once, when it is sent
   * a world, and believes it until it is sent another.
   *
   * <p>Consequently this may only be called immediately before a teleport that actually changes world —
   * {@link com.sexidium.core.game.EntryPolicy#prepareArrival} is the one place that decides that, and
   * enforces the guard. Default no-op: a platform that cannot re-tell a client simply leaves the hearts
   * as they were, which is exactly the behaviour everything had before this existed.</p>
   */
  default void setHardcoreView(boolean hardcore) {
  }

  /**
   * Resets the player's body scale (size/hitbox) back to the vanilla default, undoing any growth or
   * shrink applied by a challenge such as Endless-Growth via {@link #setScale(double)}. Default no-op
   * where the scale attribute is unavailable.
   */
  default void resetScale() {
  }

  /**
   * Resets every transient player and on-screen state so a player leaving a match returns to a clean
   * lobby state: inventory, potion effects, health scaling, body scale, health, food, game mode, the XP
   * bar, AND the graphical overlays (boss bars, titles/action bar, compass override). Resetting the XP
   * bar here stops the lobby points-bar from leaking into a minigame that uses it as a health meter, and
   * vice versa (known-issues F61).
   */
  default void resetStatuses() {
    clearInventory();
    clearPotionEffects();
    resetHealthScale();
    resetScale();
    setHealth(maxHealth());
    setFoodLevel(20);
    setGameMode(GameModeType.SURVIVAL);
    setExperienceBar(0, 0f);
    clearBossBars();
    clearTitle();
    resetCompass();
    // Damage immunity is NOT a potion effect and is NOT cleared by anything above, so a player coming off
    // a death → respawn → world-swap (Death Resets) would otherwise keep the i-frames, fall distance and
    // fire ticks they had at the moment they died — and read as "not taking damage" in the world they
    // land in. Clearing it here covers every reset path: the swap into a new world AND releaseToLobby.
    clearDamageImmunity();
  }

  /**
   * Clears every form of damage immunity that is NOT a potion effect: the invulnerability i-frames a hit
   * or a respawn leaves behind, accumulated fall distance, lingering fire, and the blanket
   * {@link #setInvulnerable(boolean)} flag. Default no-op so a platform without the primitives simply
   * leaves them as they were.
   *
   * <p>The blanket flag belongs here specifically because it is the only one that never expires on its
   * own. It is handed out for short grace windows and taken back by a scheduled task, so every path that
   * loses the schedule — a failed teleport, a disconnect mid-move, a match that ends inside the window
   * and cancels its own tasks — used to leave a player permanently unkillable, in the lobby and in every
   * match afterwards. Grouping it with the rest means "reset this player" always includes it.</p>
   */
  /**
   * Removes whatever sidebar this player currently has, whoever put it there.
   *
   * <p>Distinct from hiding a panel: hiding RESTORES the board that was showing before, which is right
   * when one owner hands over to another. This is for a mode that wants no sidebar at all, where the
   * board to remove was put up by somebody else entirely (the lobby's server/points/rank card) and there
   * is nothing sensible to restore.</p>
   */
  default void clearSidebar() {
  }

  default void clearDamageImmunity() {
    // The blanket flag has its own seam, so even a platform that cannot reach i-frames or fall distance
    // can honour the important half of this contract. Overriding implementations must keep doing this.
    setInvulnerable(false);
  }

  /**
   * Toggles the player's blanket invulnerability flag (immune to all damage, including void/fire/lava,
   * not just entity hits). Used for a SHORT, deliberate grace window — e.g. a freshly-wiped player dropped
   * into a fresh hard-difficulty world needs a few seconds to orient before they can be killed again,
   * otherwise they die on arrival and re-trigger the very reset that just moved them. Always paired with a
   * scheduled {@code setInvulnerable(false)}. Default no-op where the platform has no such flag.
   */
  default void setInvulnerable(boolean invulnerable) {
  }

  /**
   * Takes a dead player off their death screen as if they had clicked Respawn.
   *
   * <p>This exists for HARDCORE worlds, where the death screen has no Respawn button at all — it offers
   * only "Spectate world" and "Title screen". A mode that intends to continue after a hardcore death
   * (Death Resets regenerates the world instead of losing it) therefore has nothing else that will ever
   * move the player: no respawn will happen on its own, and a teleport does not clear the screen.</p>
   *
   * <p>Safe to call on a living player — implementations must ignore it. Default no-op: a platform that
   * cannot force a respawn simply leaves the player where they are.</p>
   */
  default void forceRespawn() {
  }

  default boolean playerSource() {
    return true;
  }
}
