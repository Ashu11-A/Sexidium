package com.sexidium.core.game.hud.surface;

import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.game.hud.HudContext;
import com.sexidium.core.game.hud.HudContributor;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudElement;
import com.sexidium.core.platform.hud.HudSurfaceSpec;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A persistent surface rendered as lines on the shared {@link GameHud} sidebar.
 *
 * <p>Registers itself as an ordinary {@link HudContributor}, so it competes for space with every
 * other source on equal terms and inherits the whole render pass for free — the per-player view
 * toggle, the debug gate, the stale-line trimming. It draws only for a player who is BOTH a viewer of
 * this surface and not already being served by the platform driver.</p>
 */
final class SidebarHudSurface implements HudSurfaceHandle, HudContributor {
  /**
   * Below the mode's own lines and below the team card, because a surface that fell back to here is
   * supplementary by definition — it is the readout the platform could not draw somewhere better.
   */
  private static final int ORDER = 200;

  private final HudSurfaceSpec spec;
  private final Supplier<GameHud> hudSupplier;
  private final Predicate<PlayerAdapter> suppressed;
  private final HudValues values = new HudValues();
  private final Set<UUID> viewers = ConcurrentHashMap.newKeySet();
  private volatile GameHud registeredWith;
  private volatile boolean closed;

  SidebarHudSurface(HudSurfaceSpec spec, Supplier<GameHud> hudSupplier, Predicate<PlayerAdapter> suppressed) {
    this.spec = spec;
    this.hudSupplier = hudSupplier;
    this.suppressed = suppressed == null ? player -> false : suppressed;
  }

  @Override
  public int order() {
    return ORDER;
  }

  @Override
  public boolean active() {
    return !closed && hudSupplier.get() != null;
  }

  @Override
  public boolean activeFor(PlayerAdapter playerAdapter) {
    return active() && draws(playerAdapter);
  }

  @Override
  public void text(String key, LocalizedText value) {
    values.text(key, value);
  }

  @Override
  public void number(String key, double value) {
    values.number(key, value);
  }

  @Override
  public void flag(String key, boolean value) {
    values.flag(key, value);
  }

  @Override
  public void progress(String key, double value) {
    values.progress(key, value);
  }

  @Override
  public void show(PlayerAdapter playerAdapter) {
    if (playerAdapter == null || closed) {
      return;
    }
    viewers.add(playerAdapter.uniqueId());
    attach();
  }

  @Override
  public void hide(PlayerAdapter playerAdapter) {
    if (playerAdapter != null) {
      viewers.remove(playerAdapter.uniqueId());
    }
  }

  @Override
  public void refresh() {
    // The GameHud render pass pulls; there is nothing to flush. Re-attaching here is what lets a
    // surface opened before the host installed its HUD still find one later.
    attach();
  }

  @Override
  public void close() {
    closed = true;
    viewers.clear();
    GameHud hud = registeredWith;
    if (hud != null) {
      hud.unregister(this);
      registeredWith = null;
    }
  }

  @Override
  public void describe(HudContext context) {
    if (closed || context == null || !draws(context.player())) {
      return;
    }
    for (HudElement element : spec.elements()) {
      LocalizedText line = values.render(element);
      if (line != null) {
        context.line(line);
      }
    }
  }

  private boolean draws(PlayerAdapter playerAdapter) {
    return playerAdapter != null
        && viewers.contains(playerAdapter.uniqueId())
        && !suppressed.test(playerAdapter);
  }

  /**
   * Joins the host's HUD the first time one exists. Deliberately re-checked rather than done once at
   * construction: a challenge registers its surfaces during {@code register(...)}, which runs before
   * the game has installed a HUD, and a live-edited challenge can be bound to a game whose HUD was
   * replaced since.
   */
  private void attach() {
    if (closed) {
      return;
    }
    GameHud hud = hudSupplier.get();
    if (hud == null || hud == registeredWith) {
      return;
    }
    GameHud previous = registeredWith;
    if (previous != null) {
      previous.unregister(this);
    }
    hud.register(this);
    registeredWith = hud;
  }
}
