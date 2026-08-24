package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One entry in the {@code /sx} hub: a single self-describing interface (its icon, optional custom
 * art, its visibility rule, and how to open it). The main menu is built by iterating the
 * {@link MenuCatalog} of these, so adding a new interface to the hub is exactly one
 * {@link MenuCatalog#register} call — no edits to the layout code. This is the "easy maintenance and
 * scalability" the unified menu system is for.
 *
 * @param id        stable identifier (telemetry / tests)
 * @param icon      vanilla fallback material for the icon
 * @param iconModel optional custom {@code item_model} id ({@link MenuArt#model}); rendered only for a
 *                  viewer who loaded the resource pack, else the {@code icon} material is shown
 * @param title     MiniMessage button title, computed per viewer (usually constant)
 * @param lore      MiniMessage lore lines, computed per viewer (state-aware tiles vary this)
 * @param visible   whether this tab shows for a viewer (e.g. admin-only); {@code null} = always
 * @param open      opens the interface for the clicking player
 */
public record MenuTab(String id, ItemKey icon, String iconModel,
    Function<PlayerAdapter, String> title,
    Function<PlayerAdapter, List<String>> lore,
    Predicate<PlayerAdapter> visible,
    Consumer<PlayerAdapter> open) {

  /** A tab with a constant title/lore. */
  public static MenuTab of(String id, ItemKey icon, String iconModel, String title, List<String> lore,
      Consumer<PlayerAdapter> open) {
    return new MenuTab(id, icon, iconModel, viewer -> title, viewer -> lore, null, open);
  }

  /** A tab with a constant title and a per-viewer (state-aware) lore. */
  public static MenuTab dynamic(String id, ItemKey icon, String iconModel, String title,
      Function<PlayerAdapter, List<String>> lore, Consumer<PlayerAdapter> open) {
    return new MenuTab(id, icon, iconModel, viewer -> title, lore, null, open);
  }

  /** This tab gated behind a visibility predicate (admin-only tiles). */
  public MenuTab visibleWhen(Predicate<PlayerAdapter> rule) {
    return new MenuTab(id, icon, iconModel, title, lore, rule, open);
  }

  public boolean visibleTo(PlayerAdapter viewer) {
    return visible == null || visible.test(viewer);
  }

  /** Renders this tab as a clickable hub button (wearing its custom icon model when set). */
  public MenuButton toButton(PlayerAdapter viewer) {
    MenuButton button = MenuButton.of(icon, title.apply(viewer), lore.apply(viewer),
        ctx -> open.accept(ctx.player()));
    return iconModel == null ? button : button.withModel(iconModel);
  }
}
