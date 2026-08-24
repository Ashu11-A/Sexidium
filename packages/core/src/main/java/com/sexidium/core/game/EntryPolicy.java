package com.sexidium.core.game;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.WorldAdapter;
import com.sexidium.core.platform.model.GameModeType;
import com.sexidium.core.platform.model.WorldPosition;
import com.sexidium.core.world.WorldNaming;

/**
 * How a mode wants a player to ARRIVE and to STAY: which game mode they are in, whether they are healed,
 * fed and emptied out on the way in, whether the client renders hardcore hearts, and whether the mode is
 * allowed to be talked out of any of it once they are inside.
 *
 * <h2>Why this is a value and not a method call</h2>
 * A player's game mode used to be set by whoever happened to touch them last on the way in — the host
 * forcing survival, the saved snapshot restoring whatever they had when they left, a service setting
 * something else a tick later. Three writers, no order, and the result was a player walking back into a
 * world they had permanently lost, in survival, able to keep playing it.
 *
 * <p>So a mode now <em>declares</em> what arrival should look like ({@link Game#entryPolicy}) and the
 * framework applies it — at every entry path, and <b>last</b>, after the snapshot restore that would
 * otherwise overwrite it. A mode cannot forget to enforce it, because a mode is not what enforces it.</p>
 *
 * <h2>Fail-safe by construction</h2>
 * The default is {@link #SURVIVAL}, which is exactly what every mode did before this existed, so a mode
 * that says nothing behaves as it always has. A mode that wants something else says so once and gets it
 * everywhere: on a fresh start, on joining a running match, on reconnecting after a restart, after any
 * saved state is restored, and — for an {@link #enforced()} policy — for as long as the player stays,
 * because "applied once at entry" is only as good as the next thing that writes a game mode.
 *
 * @param gameMode       the mode the player arrives in
 * @param heal           restore health on arrival
 * @param feed           restore hunger on arrival
 * @param clearInventory empty the player's inventory on the way in (before any saved one is restored)
 * @param hardcoreView   render hardcore (hardened) hearts on the client while inside — see
 *                       {@link #prepareArrival}
 * @param enforced       re-assert {@code gameMode} for as long as the player is inside, not just on the
 *                       way in. For a world that must never be playable again, entry-time-only is not
 *                       enough: anything from a plugin to an operator to a challenge can hand the mode
 *                       back afterwards.
 * @param notice         an optional message explaining the arrival, sent once
 */
public record EntryPolicy(GameModeType gameMode, boolean heal, boolean feed, boolean clearInventory,
    boolean hardcoreView, boolean enforced, LocalizedText notice) {

  /** Arrive alive and playing — what every mode does unless it says otherwise. */
  public static final EntryPolicy SURVIVAL =
      new EntryPolicy(GameModeType.SURVIVAL, true, true, true, null);

  public EntryPolicy {
    gameMode = gameMode == null ? GameModeType.SURVIVAL : gameMode;
  }

  /** The arrival rules without the stay-this-way ones: ordinary worlds, unchanged. */
  public EntryPolicy(GameModeType gameMode, boolean heal, boolean feed, boolean clearInventory,
      LocalizedText notice) {
    this(gameMode, heal, feed, clearInventory, false, false, notice);
  }

  /**
   * Arrive as a SPECTATOR and stay one: no heal, no feed, nothing cleared, and the mode re-asserted for
   * as long as they are inside. Used for a world that can be looked at but never played again — a
   * hardcore run that has been lost. Deliberately touches nothing about the player's body, because
   * nothing about such a world should look playable.
   */
  public static EntryPolicy spectator(LocalizedText notice) {
    return new EntryPolicy(GameModeType.SPECTATOR, false, false, false, false, true, notice);
  }

  /** Arrive in CREATIVE (a build/edit world). Nothing is cleared — the player keeps what they carry. */
  public static EntryPolicy creative(LocalizedText notice) {
    return new EntryPolicy(GameModeType.CREATIVE, true, true, false, notice);
  }

  /** Arrive in ADVENTURE: alive and fed, but unable to freely break the world. */
  public static EntryPolicy adventure(LocalizedText notice) {
    return new EntryPolicy(GameModeType.ADVENTURE, true, true, true, notice);
  }

  /** This policy with a message attached. */
  public EntryPolicy withNotice(LocalizedText notice) {
    return new EntryPolicy(gameMode, heal, feed, clearInventory, hardcoreView, enforced, notice);
  }

  /** This policy, with the client rendering (or not rendering) hardcore hearts while inside. */
  public EntryPolicy withHardcoreView(boolean hardcoreView) {
    return new EntryPolicy(gameMode, heal, feed, clearInventory, hardcoreView, enforced, notice);
  }

  /** This policy, re-asserted for as long as the player stays rather than only on the way in. */
  public EntryPolicy alwaysEnforced() {
    return new EntryPolicy(gameMode, heal, feed, clearInventory, hardcoreView, true, notice);
  }

  /** Whether the arriving player can act on the world at all (drives entry hygiene and UI wording). */
  public boolean playable() {
    return gameMode != GameModeType.SPECTATOR;
  }

  /**
   * Applies the game mode (and, when asked, health and hunger) to a player.
   *
   * <p>Called by the framework at every entry point and again after any saved state is restored, so it
   * must be idempotent — it is, because every operation here is a set, not a change.</p>
   */
  public void applyTo(PlayerAdapter player) {
    if (player == null || !player.online()) {
      return;
    }
    if (heal) {
      player.setHealth(Math.max(1.0, player.maxHealth()));
    }
    if (feed) {
      player.setFoodLevel(20);
    }
    player.setGameMode(gameMode);
  }

  /**
   * Re-asserts the game mode of a player who is already inside, and <em>only</em> the game mode: no
   * healing, no feeding, nothing that would treat a player mid-session as if they had just walked in.
   * Writes only when the mode is actually wrong, so this is safe to run on a timer — a repeated
   * {@code setGameMode} fires a platform event and resets client state (spectator fly speed) every time.
   *
   * @return true when the player's mode was wrong and has been corrected
   */
  public boolean enforce(PlayerAdapter player) {
    if (!enforced || player == null || !player.online() || player.gameMode() == gameMode) {
      return false;
    }
    player.setGameMode(gameMode);
    return true;
  }

  /**
   * Tells the client whether to render hardcore hearts, immediately BEFORE the entry teleport that is
   * about to move the player into {@code target}.
   *
   * <p>Two things make this the only correct moment. A client is told a world is hardcore exactly once,
   * when it is sent a world — so a player who logged in somewhere else keeps whatever they were told
   * then, which is why a hardcore experience showed ordinary hearts and why hardcore hearts followed a
   * player back to the lobby. And re-telling it costs the client its loaded world, so it may only be
   * done when a world change is about to hand it a new one anyway. Hence the guard: a "teleport" that
   * does not leave the current world is not a moment where this can be said, and is skipped.</p>
   */
  public void prepareArrival(PlayerAdapter player, WorldPosition target) {
    applyHardcoreView(player, target, hardcoreView);
  }

  /**
   * The whole entry, as ONE operation: warm the destination, tell the client what kind of world it is
   * about to get, move it there, and apply this policy on arrival.
   *
   * <p>Callers should prefer this over calling {@link #prepareArrival} and a teleport themselves. The
   * two halves have a contract between them — the client discards its world when told, and must be
   * handed a new one immediately — and a caller that writes them as two statements silently breaks it,
   * because a platform teleport is asynchronous. What the player sees when it breaks is a world that
   * never finishes loading: frozen entities, missing chunks, nothing breakable, fixed only by
   * reconnecting. Warming the chunk first is what makes "immediately" true.</p>
   *
   * @param world    any adapter able to resolve {@code target}'s world; used only to warm the chunk
   * @param onSettled receives true if the player arrived, false if the move was refused. Always called.
   */
  public void arrive(PlayerAdapter player, WorldAdapter world, WorldPosition target,
      java.util.function.Consumer<Boolean> onSettled) {
    if (player == null || target == null) {
      if (onSettled != null) {
        onSettled.accept(Boolean.FALSE);
      }
      return;
    }
    Runnable move = () -> {
      prepareArrival(player, target);
      player.teleport(target, moved -> {
        if (Boolean.TRUE.equals(moved)) {
          applyTo(player);
        }
        if (onSettled != null) {
          onSettled.accept(moved);
        }
      });
    };
    if (world == null) {
      move.run();
      return;
    }
    world.preloadChunk(target, move);
  }

  /**
   * The counterpart, applied by the framework on every release to the lobby: the lobby is never hardcore.
   * Static because it is not the leaving world's opinion that matters here — nothing that is leaving gets
   * a say in what the lobby looks like.
   */
  public static void leaveHardcoreWorld(PlayerAdapter player, WorldPosition lobbyTarget) {
    applyHardcoreView(player, lobbyTarget, false);
  }

  private static void applyHardcoreView(PlayerAdapter player, WorldPosition target, boolean hardcore) {
    if (player == null || !player.online() || target == null || target.worldName() == null) {
      return;
    }
    WorldAdapter current = player.world();
    if (current != null && WorldNaming.sameWorld(current.name(), target.worldName())) {
      return;
    }
    player.setHardcoreView(hardcore);
  }
}
