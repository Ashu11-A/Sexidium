package com.sexidium.paper.adapter.ui.betterhud;

import kr.toxicity.hud.api.update.UpdateEvent;
import kr.toxicity.hud.api.update.UpdateReason;

/**
 * The event one of our popups is fired with — and the reason it is not {@code UpdateEvent.EMPTY}.
 *
 * <h2>What sharing one key costs</h2>
 * Every generated popup carries {@code unique: true}, which makes BetterHud file each live popup in the
 * player's {@code getPopupKeyMap()} under {@code UpdateEvent#getKey()}. Showing a popup whose key is
 * already in that map does not show anything: it calls {@code update()} on whatever updater the key
 * names and returns null. {@code UpdateEvent.EMPTY} is a singleton whose key is ONE process-wide random
 * UUID (verified in the 2.0.0 jar: {@code UpdateEvent$1} holds a single {@code UUID.randomUUID()}), so
 * every Sexidium popup shared one slot per player. Firing the reset countdown while a Random Events
 * toast was still on screen therefore refreshed the toast and drew no countdown at all — and since a
 * popup's liveness is answered from our own ledger, which records what we FIRED, the surface would have
 * gone on reporting itself as reaching that player and holding the vanilla-title fallback quiet.
 *
 * <p>Keyed by surface id instead: two of our popups can no longer collide, and re-firing one of ours
 * still updates it in place rather than stacking a second copy beside it.</p>
 *
 * <h2>Why this is its own file</h2>
 * It names a BetterHud type in its {@code implements} clause, so loading it requires the plugin. Kept
 * out of {@link BetterHudApi} because a nested or anonymous version puts an assignability check into
 * that class's own bytecode, and the verifier resolves those when it links the class — which would make
 * {@code BetterHudApi} unloadable on a server (or a test JVM) with no BetterHud installed, the exact
 * property the split from {@link BetterHudLink} exists to preserve. Reached only through
 * {@link #of(String)}, whose return type is the interface, so nothing here is loaded until a popup is
 * genuinely being shown.
 */
final class BetterHudPopupEvent implements UpdateEvent {
  private final String key;

  private BetterHudPopupEvent(String key) {
    this.key = key;
  }

  /** An event that keys a popup by its own surface id. */
  static UpdateEvent of(String surfaceId) {
    return new BetterHudPopupEvent(surfaceId);
  }

  @Override
  public UpdateReason getType() {
    return UpdateReason.EMPTY;
  }

  @Override
  public Object getKey() {
    return key;
  }
}
