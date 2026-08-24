package com.sexidium.paper.adapter.ui.betterhud;

import kr.toxicity.hud.api.BetterHudAPI;
import kr.toxicity.hud.api.configuration.HudObject;
import kr.toxicity.hud.api.hud.Hud;
import kr.toxicity.hud.api.player.HudPlayer;
import kr.toxicity.hud.api.popup.Popup;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Every typed call into BetterHud, in one file.
 *
 * <p>The split from {@link BetterHudLink} is the reason this integration can be unit-tested on a JVM
 * with no BetterHud on the classpath at all: the gate names no vendor type and so always links, and
 * nothing constructs one of these until the gate has confirmed the API resolves. Individual calls are
 * still wrapped, because "the class links" and "this method exists in the version actually installed"
 * are different claims, and only the first one has been checked by the time we get here.</p>
 *
 * <h2>API surface used, all verified present in 2.0.0</h2>
 * {@code BetterHudAPI.inst()}, {@code getHudManager().getHud}, {@code getPopupManager().getPopup},
 * {@code getPlayerManager().getHudPlayer},
 * {@code HudPlayer#getHuds/getPopups/getCompasses/isHudEnabled/getVariableMap},
 * {@code HudObject#getName/add/remove}, {@code Popup#show/hide}, {@code UpdateEvent}/{@code
 * UpdateReason}.
 */
final class BetterHudApi {
  private final Consumer<String> log;

  BetterHudApi(Consumer<String> log) {
    this.log = log == null ? message -> { } : log;
  }

  /**
   * Publishes this player's rendered rows into their own BetterHud variable map.
   *
   * <p>Per player, not shared: the map belongs to one {@code HudPlayer}, so two viewers of the same
   * surface can hold the same row rendered in two different languages. That is the whole reason the
   * readout can be translated at all — the values are pre-rendered strings, but they are pre-rendered
   * <em>for the player who will read them</em>.</p>
   */
  void pushVariables(Player player, Map<String, String> values) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null || values.isEmpty()) {
      return;
    }
    try {
      hudPlayer.getVariableMap().putAll(values);
    } catch (RuntimeException | LinkageError ignored) {
      // A failed push leaves the previous frame's text on screen, which is the right failure: stale
      // beats blank, and the next refresh is 20 ticks away.
    }
  }

  /** Drops this surface's variables from a player who is no longer a viewer. */
  void clearVariables(Player player, Collection<String> keys) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null || keys.isEmpty()) {
      return;
    }
    try {
      hudPlayer.getVariableMap().keySet().removeAll(keys);
    } catch (RuntimeException | LinkageError ignored) {
      // Nothing to recover: the object itself has already been taken off them.
    }
  }

  /** Whether an object with this id exists — i.e. whether our generated yml actually loaded. */
  boolean exists(String id) {
    return hud(id) != null || popup(id) != null;
  }

  /**
   * Takes off everything the player is wearing that is not in {@code keep} — huds, popups AND
   * compasses.
   *
   * <p>This is exclusive mode, and it is deliberately NOT bounded by our own id namespace: "show this
   * player nothing except what Sexidium asked for" has to be able to remove another plugin's objects,
   * or BetterHud's own demo hud and compass (the compass carries {@code default: true} inside its own
   * yml, which no config key overrides) stay on screen beside the mode's readout. The namespace-bounded
   * sweep is {@link #purge}, which is the teardown path and must never touch an operator's huds.</p>
   *
   * <p>An empty {@code keep} therefore means "wearing nothing", which is the correct state for a player
   * in the lobby while the driver is exclusive.</p>
   */
  void retain(Player player, Set<String> keep) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null) {
      return;
    }
    try {
      for (HudObject worn : worn(hudPlayer)) {
        if (!keep.contains(worn.getName())) {
          worn.remove(hudPlayer);
        }
      }
    } catch (RuntimeException | LinkageError ignored) {
      // A failed strip leaves an extra hud on screen; it must not take the match down with it.
    }
  }

  /**
   * Puts {@code id} on the player.
   *
   * <p>Idempotent by BetterHud's own contract, and re-asserted rather than set once because BetterHud
   * rebuilds a {@code HudPlayer} — re-applying its defaults — on join, on world change and on reload.</p>
   */
  boolean add(Player player, String id) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null) {
      return false;
    }
    try {
      Hud hud = hud(id);
      if (hud != null) {
        return hud.add(hudPlayer);
      }
      Popup popup = popup(id);
      if (popup != null) {
        return popup.show(BetterHudPopupEvent.of(id), hudPlayer) != null;
      }
      return false;
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  /** Takes off every object whose id starts with {@code prefix}. Returns whether anything came off. */
  boolean purge(Player player, String prefix) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null) {
      return false;
    }
    boolean removed = false;
    try {
      for (HudObject worn : worn(hudPlayer)) {
        String name = worn.getName();
        if (name != null && name.startsWith(prefix)) {
          removed |= worn.remove(hudPlayer);
        }
      }
    } catch (RuntimeException | LinkageError ignored) {
      return removed;
    }
    return removed;
  }

  /** Takes one popup off a player, for a toast that is being retracted before it expires. */
  void hidePopup(Player player, String id) {
    HudPlayer hudPlayer = hudPlayer(player);
    Popup popup = popup(id);
    if (hudPlayer == null || popup == null) {
      return;
    }
    try {
      popup.hide(hudPlayer);
    } catch (RuntimeException | LinkageError ignored) {
      // Nothing to recover: an un-hidden popup expires on its own duration anyway.
    }
  }

  /**
   * Whether BetterHud is drawing to this player at all — they have a {@code HudPlayer} and have not
   * toggled their HUD off.
   *
   * <p>Asked on its own, without a surface id, for the one thing {@link #showing} cannot answer: a
   * POPUP. {@code showing} scans what the player WEARS, and a popup is never worn — see the note on
   * {@link #worn}. So a fired popup's liveness is tracked by the caller's own ledger and this supplies
   * the other half of the question, which is whether the player can see anything of ours at all.</p>
   */
  boolean drawing(Player player) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null) {
      return false;
    }
    try {
      return hudPlayer.isHudEnabled();
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  /**
   * Whether this player is actually being drawn to, and is wearing {@code id}.
   *
   * <p>Only ever true of a persistent surface. A popup is fired rather than worn and never appears
   * here; ask {@link #drawing} plus the fired-popup ledger instead.</p>
   */
  boolean showing(Player player, String id) {
    HudPlayer hudPlayer = hudPlayer(player);
    if (hudPlayer == null) {
      return false;
    }
    try {
      if (!hudPlayer.isHudEnabled()) {
        return false;
      }
      for (HudObject worn : worn(hudPlayer)) {
        if (id.equals(worn.getName())) {
          return true;
        }
      }
      return false;
    } catch (RuntimeException | LinkageError ignored) {
      return false;
    }
  }

  /**
   * Every object the player currently wears, copied out before anything is removed.
   *
   * <p>The copy is load-bearing: {@code remove} mutates the very maps {@code getHuds}/{@code
   * getPopups}/{@code getCompasses} are views onto.</p>
   *
   * <h2>A fired popup is not in here, and cannot be put in here</h2>
   * All three getters are derived from {@code HudPlayer#getHudObjects()}, and the only thing that ever
   * writes to that map is {@code HudObject#add}. Showing a popup goes through {@code Popup#show},
   * which records the result in the player's popup-iterator and popup-key maps and never touches
   * {@code getHudObjects()} — so {@code getPopups()} is empty for a popup that is on screen right now.
   * That is a fact about BetterHud 2.0.0's API, verified against the jar, not an oversight here: no
   * view built on this method can ever be used to ask whether a popup is live. See
   * {@link BetterHudClaims#showingPopup}, which answers it from our own ledger instead.
   */
  private List<HudObject> worn(HudPlayer hudPlayer) {
    List<HudObject> worn = new ArrayList<>();
    worn.addAll(hudPlayer.getHuds());
    worn.addAll(hudPlayer.getCompasses());
    worn.addAll(hudPlayer.getPopups());
    return worn;
  }

  private Hud hud(String id) {
    try {
      return BetterHudAPI.inst().getHudManager().getHud(id);
    } catch (RuntimeException | LinkageError ignored) {
      return null;
    }
  }

  private Popup popup(String id) {
    try {
      return BetterHudAPI.inst().getPopupManager().getPopup(id);
    } catch (RuntimeException | LinkageError ignored) {
      return null;
    }
  }

  private HudPlayer hudPlayer(Player player) {
    if (player == null) {
      return null;
    }
    try {
      return BetterHudAPI.inst().getPlayerManager().getHudPlayer(player.getUniqueId());
    } catch (RuntimeException | LinkageError ignored) {
      return null;
    }
  }
}
