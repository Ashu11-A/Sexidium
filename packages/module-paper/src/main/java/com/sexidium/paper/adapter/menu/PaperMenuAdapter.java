package com.sexidium.paper.adapter.menu;

import com.sexidium.core.menu.MenuArt;
import com.sexidium.core.menu.MenuButton;
import com.sexidium.core.menu.MenuContext;
import com.sexidium.core.menu.MenuView;
import com.sexidium.core.menu.scene.SceneTemplates;
import com.sexidium.core.platform.MenuAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;
import com.sexidium.paper.adapter.player.PaperGeyser;
import com.sexidium.paper.adapter.player.PaperPlayerAdapter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Chest-GUI {@link MenuAdapter} for Paper.
 *
 * <p>Implementation route: <b>Bukkit-native</b> (not InvUI). A custom {@link SexidiumMenuHolder}
 * carries the {@link MenuView} on the opened chest inventory and this class doubles as a Bukkit
 * {@link Listener} on {@link InventoryClickEvent}/{@link InventoryDragEvent}. This satisfies the
 * core menu SPI with zero external dependencies — no shading, relocation, or NMS-version coupling
 * that the InvUI library would have introduced against this Paper API build. {@code InventoryClickEvent}
 * fires on the server main thread, so button handlers run on the main thread as required.
 */
public final class PaperMenuAdapter implements MenuAdapter, Listener {
  // The plugin is needed only to schedule Bedrock form-click handlers back onto the main thread. It is
  // null in headless tests (and the no-arg constructor), in which case forms are simply never used.
  private final JavaPlugin plugin;
  // Lazily created so the optional Floodgate/Cumulus classes are only loaded once a Bedrock player
  // actually opens a menu on a server that has Floodgate. Disabled permanently on first failure.
  private PaperFormRenderer formRenderer;
  private boolean formsUnavailable;
  // Per-player resource-pack gate: art (glyph backgrounds + custom item models) is only rendered for
  // players known to have loaded the pack. Defaults to "nobody" so menus are plain until the pack
  // service is wired in (and stay plain in headless tests).
  private Predicate<UUID> packLoaded = id -> false;
  // Open animated menus (per player) whose interactive tiles carry nameFrames the animation task cycles
  // in place — e.g. the minigames grid's per-mode colour sweep. Started lazily on the first animated open.
  // Concurrent because it is touched from the global animation tick and per-player region threads (Folia).
  private final Map<UUID, SexidiumMenuHolder> animatedMenus = new ConcurrentHashMap<>();
  private boolean animationStarted;
  private int animationPhase;

  public PaperMenuAdapter() {
    this(null);
  }

  public PaperMenuAdapter(JavaPlugin plugin) {
    this.plugin = plugin;
  }

  /** Supplies the per-player pack-load predicate (from {@link PaperResourcePackService}). */
  public void setPackGate(Predicate<UUID> gate) {
    this.packLoaded = gate == null ? id -> false : gate;
  }

  @Override
  public void open(PlayerAdapter player, MenuView view) {
    if (player == null || view == null) {
      return;
    }
    // Bedrock/Geyser players get a native Cumulus form when Floodgate is present; everyone else (and
    // any form failure) gets the chest GUI, which is the universal floor.
    if (player instanceof PaperPlayerAdapter bedrock && shouldUseForm(bedrock) && tryForm(bedrock, view)) {
      return;
    }
    openChest(player, view);
  }

  private boolean shouldUseForm(PaperPlayerAdapter player) {
    return plugin != null && !formsUnavailable && player.isBedrock() && PaperGeyser.bedrockUiAvailable();
  }

  private boolean tryForm(PaperPlayerAdapter player, MenuView view) {
    try {
      if (formRenderer == null) {
        formRenderer = new PaperFormRenderer(plugin);
      }
      return formRenderer.open(player, view);
    } catch (LinkageError error) {
      // Cumulus/Floodgate classes are missing or incompatible — disable forms permanently and use chest.
      formsUnavailable = true;
      plugin.getLogger().warning("Bedrock forms unavailable (Floodgate/Cumulus not loadable), using chest GUI: " + error);
      return false;
    } catch (RuntimeException exception) {
      // Transient failure (e.g. a single send error): fall back this time, keep forms enabled for next time.
      plugin.getLogger().warning("Bedrock form send failed, using chest GUI this time: " + exception.getMessage());
      return false;
    }
  }

  private void openChest(PlayerAdapter player, MenuView view) {
    Player handle = resolve(player);
    if (handle == null) {
      return;
    }

    boolean loaded = packLoaded.test(handle.getUniqueId());
    // A baked-overlay view (the hub) hides item icons and routes clicks via the tile groups. It is keyed on
    // the OVERLAY glyph (the transparent cards), not the background (which is the chest frame, a BG_* marker).
    boolean screenLoaded = loaded && view.screenArt() != null && MenuArt.glyph(view.overlayArt()) != null;
    Map<Integer, MenuButton> routedButtons = screenLoaded ? screenButtons(view) : view.buttons();
    SexidiumMenuHolder holder = new SexidiumMenuHolder(view, routedButtons);
    Component title = PaperMenuArt.title(view, loaded);
    // Baked-art viewers always get the full six-row window the art is drawn for; a plain viewer gets the
    // view's plain-mode size (defaults to size(), but the hub asks for a compact three rows). The buttons
    // that layoutCentered placed into the first content row fall inside the smaller window unchanged.
    int inventorySize = screenLoaded ? 6 * 9 : view.plainSize();
    Inventory inventory = Bukkit.createInventory(holder, inventorySize, title);
    holder.attach(inventory);

    for (Map.Entry<Integer, MenuButton> entry : routedButtons.entrySet()) {
      int slot = entry.getKey();
      if (slot < 0 || slot >= inventorySize) {
        continue;
      }
      if (screenLoaded && entry.getValue().headOwner() == null) {
        continue;
      }
      inventory.setItem(slot, toItemStack(entry.getValue(), loaded));
    }

    handle.openInventory(inventory);

    // Track (or forget) this menu for the in-place name animation.
    if (view.animated()) {
      animatedMenus.put(handle.getUniqueId(), holder);
      ensureAnimationTask();
    } else {
      animatedMenus.remove(handle.getUniqueId());
    }
  }

  /** Starts the shared name-animation loop once, on the first animated menu open. */
  private void ensureAnimationTask() {
    if (animationStarted || plugin == null) {
      return;
    }
    animationStarted = true;
    plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> animationTick(), 4L, 4L);
  }

  private void animationTick() {
    if (animatedMenus.isEmpty()) {
      return;
    }
    animationPhase++;
    int phase = animationPhase;
    for (Map.Entry<UUID, SexidiumMenuHolder> entry : new ArrayList<>(animatedMenus.entrySet())) {
      Player player = Bukkit.getPlayer(entry.getKey());
      if (player == null || !player.isOnline()) {
        animatedMenus.remove(entry.getKey());
        continue;
      }
      SexidiumMenuHolder holder = entry.getValue();
      // Repaint on the player's region thread (Folia-safe; direct on regular Paper).
      player.getScheduler().run(plugin, ignored -> animatePlayerMenu(player, holder, phase), null);
    }
  }

  private void animatePlayerMenu(Player player, SexidiumMenuHolder holder, int phase) {
    Inventory top = player.getOpenInventory().getTopInventory();
    if (top.getHolder() != holder) {
      animatedMenus.remove(player.getUniqueId());
      return;
    }
    boolean loaded = packLoaded.test(player.getUniqueId());
    for (int slot = 0; slot < top.getSize(); slot++) {
      MenuButton button = holder.button(slot);
      if (button == null || button.nameFrames() == null) {
        continue;
      }
      ItemStack item = top.getItem(slot);
      if (item == null) {
        continue;
      }
      List<String> frames = button.nameFrames();
      String frame = frames.get(Math.floorMod(phase, frames.size()));
      ItemMeta meta = item.getItemMeta();
      if (meta != null) {
        meta.displayName(PaperMenuArt.text(frame, loaded).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        top.setItem(slot, item);
      }
    }
  }

  @EventHandler
  public void onMenuClose(InventoryCloseEvent event) {
    if (event.getInventory().getHolder() instanceof SexidiumMenuHolder) {
      animatedMenus.remove(event.getPlayer().getUniqueId());
    }
  }

  private Map<Integer, MenuButton> screenButtons(MenuView view) {
    // The two baked hub variants route clicks across their (different) card grids; every other screen keeps
    // its declared per-slot buttons.
    boolean op;
    if ("main-hub".equals(view.screenArt())) {
      op = false;
    } else if ("main-hub-op".equals(view.screenArt())) {
      op = true;
    } else {
      return view.buttons();
    }
    List<Map.Entry<Integer, MenuButton>> buttons = new ArrayList<>(view.buttons().entrySet());
    buttons.sort(Comparator.comparingInt(Map.Entry::getKey));
    // Each baked hub card spans several slots, so route a click anywhere on the card (its whole slot group)
    // to that tab — otherwise only one slot under each card would be clickable.
    int[][] groups = SceneTemplates.hubHitGroups(op);
    Map<Integer, MenuButton> routed = new LinkedHashMap<>();
    int count = Math.min(buttons.size(), groups.length);
    for (int index = 0; index < count; index++) {
      MenuButton button = buttons.get(index).getValue();
      for (int slot : groups[index]) {
        routed.put(slot, button);
      }
    }
    return routed;
  }

  @Override
  public void close(PlayerAdapter player) {
    Player handle = resolve(player);
    if (handle == null) {
      return;
    }
    if (handle.getOpenInventory().getTopInventory().getHolder() instanceof SexidiumMenuHolder) {
      handle.closeInventory();
    }
  }

  /**
   * The same holder check {@link #close} and {@link #closeAll} use: a player standing in a vanilla
   * chest, an anvil or their own inventory is not looking at one of our screens.
   *
   * <p>A Bedrock viewer holding a Cumulus FORM answers false, and that is correct rather than
   * unfortunate: a form is a one-shot dialog, and re-sending it under the player's fingers would
   * discard the tap they were half-way through.</p>
   */
  @Override
  public boolean isOpen(PlayerAdapter player) {
    Player handle = resolve(player);
    return handle != null
        && handle.getOpenInventory().getTopInventory().getHolder() instanceof SexidiumMenuHolder;
  }

  /**
   * Close every Sexidium menu on the server, and stop animating.
   *
   * <p>Teardown and drain both call this. The holder check is what keeps it honest: a player standing
   * in a vanilla chest, an anvil or their own inventory is left alone — only screens this plugin
   * opened are closed.</p>
   */
  @Override
  public void closeAll() {
    for (Player player : Bukkit.getOnlinePlayers()) {
      try {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof SexidiumMenuHolder) {
          player.closeInventory();
        }
      } catch (RuntimeException ignored) {
        // One player whose view cannot be read must not stop the other thirty-nine from being closed.
      }
    }
    animatedMenus.clear();
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onClick(InventoryClickEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof SexidiumMenuHolder holder)) {
      return;
    }
    // Never let vanilla move items into or out of a Sexidium menu.
    event.setCancelled(true);

    // Only act on clicks inside the menu itself, not the player's own inventory rows.
    if (event.getClickedInventory() == null || !event.getClickedInventory().equals(top)) {
      return;
    }
    if (!(event.getWhoClicked() instanceof Player player)) {
      return;
    }

    MenuButton button = holder.button(event.getSlot());
    if (button == null || button.onClick() == null) {
      return;
    }
    MenuContext context = new MenuContext(new PaperPlayerAdapter(player), mapClickType(event.getClick()));
    button.onClick().accept(context);
  }

  @EventHandler(priority = EventPriority.HIGHEST)
  public void onDrag(InventoryDragEvent event) {
    Inventory top = event.getView().getTopInventory();
    if (!(top.getHolder() instanceof SexidiumMenuHolder)) {
      return;
    }
    // Block any drag that touches the menu's slots.
    int topSize = top.getSize();
    for (int rawSlot : event.getRawSlots()) {
      if (rawSlot < topSize) {
        event.setCancelled(true);
        return;
      }
    }
  }

  private ItemStack toItemStack(MenuButton button, boolean packLoaded) {
    // Render through the one shared UiItem materializer, so chest buttons and lobby hotbar items look
    // identical and item rendering lives in a single place.
    return PaperUiItemFactory.build(button.visual(), packLoaded);
  }

  /**
   * Bukkit's click kinds, narrowed to the ones a menu button is allowed to read as a deliberate tap.
   *
   * <p>{@code DOUBLE_CLICK} is deliberately NOT a LEFT click: Bukkit delivers a double-click as a LEFT
   * event followed by a DOUBLE_CLICK one, so mapping it to LEFT made a single flick of the mouse two
   * taps — which is exactly both halves of {@code MenuSupport}'s arm-then-confirm gesture. It falls
   * through to OTHER with the number keys, the drops and the swaps, none of which
   * {@code MenuSupport.isTap} accepts as the second half of a destructive confirm.</p>
   */
  private MenuContext.ClickType mapClickType(org.bukkit.event.inventory.ClickType clickType) {
    if (clickType == null) {
      return MenuContext.ClickType.OTHER;
    }
    return switch (clickType) {
      case LEFT -> MenuContext.ClickType.LEFT;
      case RIGHT -> MenuContext.ClickType.RIGHT;
      case SHIFT_LEFT -> MenuContext.ClickType.SHIFT_LEFT;
      case SHIFT_RIGHT -> MenuContext.ClickType.SHIFT_RIGHT;
      case MIDDLE -> MenuContext.ClickType.MIDDLE;
      default -> MenuContext.ClickType.OTHER;
    };
  }

  private Player resolve(PlayerAdapter player) {
    if (player instanceof PaperPlayerAdapter paperPlayer) {
      return paperPlayer.handle();
    }
    return null;
  }
}
