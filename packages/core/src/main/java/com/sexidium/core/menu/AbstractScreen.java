package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Base implementation of {@link Screen} providing lifecycle management, view population,
 * live-screen tracking, and shared menu helper integrations.
 */
public abstract class AbstractScreen implements Screen {

  protected final MenuSupport support;
  protected final MenuService menuService;

  public AbstractScreen(MenuSupport support, MenuService menuService) {
    this.support = support;
    this.menuService = menuService;
  }

  public MenuSupport support() {
    return support;
  }

  public MenuService menuService() {
    return menuService;
  }

  /**
   * Evaluates the title for a specific viewer. Defaults to {@link #title()}.
   */
  protected String title(PlayerAdapter player) {
    return title();
  }

  @Override
  public MenuView build(PlayerAdapter player) {
    MenuView view = new MenuView(title(player), rows())
        .plainRows(plainRows())
        .background(backgroundArt())
        .animated(isAnimated());
    if (screenArt() != null) {
      view.screenArt(screenArt());
    }
    populate(view, player);
    return view;
  }

  /**
   * Populates the given {@link MenuView} with buttons and layout components for the player.
   *
   * @param view view instance being assembled
   * @param player viewing player
   */
  protected abstract void populate(MenuView view, PlayerAdapter player);

  @Override
  public void open(PlayerAdapter player) {
    onOpen(player);
    MenuView view = build(player);
    if (support != null && player != null) {
      support.open(player, view);
      if (isLiveTracked()) {
        trackLive(player);
      }
    }
  }

  @Override
  public void refresh(PlayerAdapter player) {
    open(player);
  }

  /**
   * Registers this screen with {@link MenuSupport#trackLive} to automatically redraw when
   * backing data changes across the cluster.
   */
  public void trackLive(PlayerAdapter player) {
    if (support != null && player != null) {
      support.trackLive(player, this::open);
    }
  }

  /**
   * Whether this screen should automatically register for live refresh on open.
   */
  protected boolean isLiveTracked() {
    return false;
  }

  // ----- Helper methods -------------------------------------------------------------------------

  protected MenuButton backButton(Consumer<MenuContext> onClick) {
    return support != null ? support.back(onClick) : MenuButton.of(ItemKey.minecraft("arrow"), "<red>« Back</red>", onClick);
  }

  protected MenuButton backButton(Runnable onClick) {
    return backButton(ctx -> {
      if (onClick != null) {
        onClick.run();
      }
    });
  }

  protected MenuButton confirmButton(PlayerAdapter viewer, ItemKey icon, String model, String token,
      String idleName, List<String> idleLore, String armedName, List<String> armedLore,
      Consumer<MenuContext> onConfirm, Consumer<PlayerAdapter> reRender) {
    if (support != null) {
      return support.confirmButton(viewer, icon, model, token, idleName, idleLore, armedName, armedLore,
          onConfirm, reRender);
    }
    return MenuButton.of(icon, idleName, idleLore, onConfirm);
  }

  protected boolean isTap(MenuContext ctx) {
    return MenuSupport.isTap(ctx);
  }

  protected boolean isArmed(UUID id, String token) {
    return support != null && support.isArmed(id, token);
  }

  protected void clearConfirm(UUID id) {
    if (support != null && id != null) {
      support.clearConfirm(id);
    }
  }
}
