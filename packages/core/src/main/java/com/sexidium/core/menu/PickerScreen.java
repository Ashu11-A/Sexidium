package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generic interactive selection / picker screen extending {@link PaginatedScreen}.
 *
 * @param <T> type of choice items
 */
public class PickerScreen<T> extends PaginatedScreen<T> {

  private final String title;
  private final Function<PlayerAdapter, List<T>> itemsProvider;
  private final BiFunction<PlayerAdapter, T, MenuButton> buttonRenderer;
  private final Consumer<PlayerAdapter> onBack;

  public PickerScreen(MenuSupport support, MenuService menuService, String title,
      Function<PlayerAdapter, List<T>> itemsProvider,
      BiFunction<PlayerAdapter, T, MenuButton> buttonRenderer,
      Consumer<PlayerAdapter> onBack) {
    super(support, menuService);
    this.title = title == null ? "<aqua><bold>Select Item</bold></aqua>" : title;
    this.itemsProvider = itemsProvider;
    this.buttonRenderer = buttonRenderer;
    this.onBack = onBack;
  }

  public static <T> PickerScreen<T> of(MenuSupport support, MenuService menuService, String title,
      List<T> staticItems,
      BiFunction<PlayerAdapter, T, MenuButton> buttonRenderer,
      Consumer<PlayerAdapter> onBack) {
    return new PickerScreen<>(support, menuService, title, p -> staticItems, buttonRenderer, onBack);
  }

  public static <T> PickerScreen<T> of(MenuSupport support, MenuService menuService, String title,
      Function<PlayerAdapter, List<T>> itemsProvider,
      Function<T, MenuButton> simpleRenderer,
      BiConsumer<PlayerAdapter, T> onSelect,
      Consumer<PlayerAdapter> onBack) {
    return new PickerScreen<>(support, menuService, title, itemsProvider, (player, item) -> {
      MenuButton base = simpleRenderer.apply(item);
      if (base == null) {
        return null;
      }
      return MenuButton.of(base.icon(), base.name(), base.lore(), ctx -> onSelect.accept(ctx.player(), item))
          .withModel(base.model());
    }, onBack);
  }

  @Override
  public String title() {
    return title;
  }

  @Override
  protected List<T> items(PlayerAdapter player) {
    return itemsProvider != null ? itemsProvider.apply(player) : List.of();
  }

  @Override
  protected MenuButton renderItem(PlayerAdapter player, T item, int index) {
    return buttonRenderer != null ? buttonRenderer.apply(player, item) : null;
  }

  @Override
  protected MenuButton buildBackButton(PlayerAdapter player) {
    if (onBack != null) {
      return support.back(ctx -> onBack.accept(ctx.player()));
    }
    return super.buildBackButton(player);
  }
}
