package com.sexidium.paper.adapter.ui.betterhud;

import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.HudDriver;
import com.sexidium.core.platform.HudSurfaceHandle;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.hud.HudCapability;
import com.sexidium.core.platform.hud.HudSurfaceSpec;
import org.bukkit.entity.Player;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Paper {@link HudDriver}: renders declared surfaces through BetterHud.
 *
 * <h2>Declarations are compiled, not configured</h2>
 * BetterHud 2.0.0 offers no way to create an object at runtime, so a spec becomes yml on disk
 * ({@link BetterHudAssetCompiler}), is written into a subtree this driver owns
 * ({@link BetterHudAssetStore}), and is picked up by a reload. Specs are therefore registered UP
 * FRONT — at plugin start, from the catalogue of everything that could be declared — rather than
 * lazily when a match opens one, because generating a file mid-match would mean reloading another
 * plugin mid-match.
 *
 * <h2>What it can and cannot draw</h2>
 * Text rows and bars (bars render as their glyph run through the same row placeholder) and popups.
 * NOT icons, which would need generated image assets, and not conditions, which the sidebar cannot
 * mirror. A spec containing an icon is simply not offered here; the stack hands it to the sidebar
 * renderer whole, which is better than drawing three of its four rows.
 */
public final class BetterHudDriver implements HudDriver {
  private static final Set<HudCapability> CAPABILITIES =
      Set.of(HudCapability.TEXT, HudCapability.BAR, HudCapability.POPUP);

  /**
   * How often an animated surface is republished. Every tick: BetterHud's own {@code tick-speed} ships
   * at 1, so the client is being repainted that fast anyway, and anything slower here would show the
   * frames of an animation rather than the animation.
   */
  private static final long ANIMATION_PERIOD_TICKS = 1L;

  private final BetterHudLink link;
  private final BetterHudApi api;
  private final BetterHudClaims claims;
  private final BetterHudRows rows;
  private final Function<PlayerAdapter, Player> nativePlayer;
  private final Consumer<String> log;
  /** Every spec this driver has been told about, so a reload can regenerate the whole tree. */
  private final Map<String, HudSurfaceSpec> declared = new LinkedHashMap<>();
  /** Live surfaces the publisher walks. Weakly ordered; entries are removed when a surface closes. */
  private final Set<BetterHudSurfaceHandle> open = ConcurrentHashMap.newKeySet();

  public BetterHudDriver(
      Supplier<MessageService> messageService,
      Function<PlayerAdapter, Player> nativePlayer,
      Consumer<String> log
  ) {
    this.log = log == null ? message -> { } : log;
    this.link = new BetterHudLink(this.log);
    this.api = new BetterHudApi(this.log);
    this.claims = new BetterHudClaims(link, api);
    this.rows = new BetterHudRows(messageService);
    this.nativePlayer = nativePlayer;
  }

  BetterHudLink link() {
    return link;
  }

  BetterHudClaims claims() {
    return claims;
  }

  public void enabled(boolean value) {
    link.enabled(value);
  }

  public void exclusive(boolean value) {
    claims.exclusive(value);
  }

  public void capabilityProbe(boolean value) {
    link.probeCapability(value);
  }

  public boolean installed() {
    return link.installed();
  }

  /**
   * Declares a spec so its yml is generated. Idempotent, and safe to call before BetterHud exists.
   *
   * <p>Separate from {@link #open} because the two happen at very different times: everything that
   * COULD be drawn is declared once at startup so the generated tree and the reload settle before any
   * player is online, while opening happens per match.</p>
   */
  public void declare(HudSurfaceSpec spec) {
    if (spec != null) {
      declared.put(spec.id(), spec);
    }
  }

  /**
   * Writes the generated tree for every declared spec.
   *
   * @return true when something changed on disk, i.e. when a {@code betterhud reload} is warranted
   */
  public boolean syncAssets(java.nio.file.Path betterHudFolder, boolean disableDemoAssets) {
    return new BetterHudAssetStore(betterHudFolder, disableDemoAssets, log)
        .sync(List.copyOf(declared.values()));
  }

  /**
   * Starts the publisher: renders every live surface for every viewer and writes it into their
   * BetterHud variable maps, on a thread that is not the server thread.
   *
   * <h2>Why this is off the game loop, and why that is safe here</h2>
   * Publishing means a MiniMessage parse and a plain-text serialize per row per viewer. On the server
   * thread that cost scales with players and competes with the tick; here it does not touch the tick at
   * all, which is what lets the cadence drop below a second without the game loop paying for it.
   *
   * <p>Safe because of what it touches, not by assumption. The values are a concurrent map; BetterHud's
   * {@code getVariableMap()} is a {@code ConcurrentHashMap} that BetterHud itself reads from its own
   * async redraw (its {@code tick-speed} ships at 1, so it repaints every tick regardless of us); and
   * the one genuinely thread-bound read — the viewer's locale — was captured on the server thread when
   * the surface was shown to them.</p>
   *
   * <p>The SIDEBAR is a different story and stays on the server thread: the scoreboard API may not be
   * touched from anywhere else, and on a region-threaded server it must be the thread owning that
   * player. Nothing here writes a scoreboard.</p>
   */
  public void startPublishing(org.bukkit.plugin.java.JavaPlugin plugin, long periodTicks) {
    long period = Math.max(1L, periodTicks);
    schedule(plugin, period, false);
    // A second pass, every tick, for the surfaces that animate between pushes — see
    // HudSurfaceSpec#animated. The two are disjoint, so nothing is published twice: the slow pass
    // skips animated surfaces and this one skips everything else. Running ONE pass this fast instead
    // would have made every static readout on the server twenty times more expensive to draw so that
    // one countdown could move smoothly.
    schedule(plugin, ANIMATION_PERIOD_TICKS, true);
  }

  private void schedule(org.bukkit.plugin.java.JavaPlugin plugin, long periodTicks, boolean animated) {
    plugin.getServer().getAsyncScheduler().runAtFixedRate(
        plugin,
        task -> publish(animated),
        periodTicks * 50L,
        periodTicks * 50L,
        java.util.concurrent.TimeUnit.MILLISECONDS);
  }

  private void publish(boolean animated) {
    if (!link.available()) {
      return;
    }
    for (BetterHudSurfaceHandle surface : open) {
      if (surface.spec().animated() != animated) {
        continue;
      }
      try {
        surface.publishAll();
      } catch (RuntimeException | LinkageError exception) {
        // One surface failing must not stop the others, and must not kill the repeating task — a dead
        // publisher would freeze every readout on the server with nothing on screen to say why.
        log.accept("A HUD surface failed to publish (" + exception + ").");
      }
    }
  }

  /** Called by a surface when it closes, so the publisher stops walking it. */
  void forget(BetterHudSurfaceHandle surface) {
    open.remove(surface);
  }

  /** Builds the reconcile listener. {@code teardownOnly} strips our surfaces and adds nothing. */
  public BetterHudReconciler reconciler(
      org.bukkit.plugin.java.JavaPlugin plugin, long periodTicks, boolean teardownOnly) {
    return new BetterHudReconciler(plugin, claims, periodTicks, teardownOnly);
  }

  @Override
  public Set<HudCapability> capabilities() {
    return link.available() ? CAPABILITIES : Set.of();
  }

  @Override
  public HudSurfaceHandle open(HudSurfaceSpec spec) {
    if (spec == null || !link.available()) {
      return HudSurfaceHandle.NOOP;
    }
    if (!declared.containsKey(spec.id())) {
      // Never generated, so BetterHud has no object by this name and never will this session. Saying
      // so plainly beats returning a handle that reports itself alive and draws nothing.
      log.accept("A HUD surface '" + spec.id() + "' was opened without having been declared at"
          + " startup, so no BetterHud assets exist for it; it renders on the scoreboard sidebar"
          + " instead.");
      return HudSurfaceHandle.NOOP;
    }
    BetterHudSurfaceHandle surface = new BetterHudSurfaceHandle(spec, claims, rows, nativePlayer, this);
    open.add(surface);
    return surface;
  }
}
