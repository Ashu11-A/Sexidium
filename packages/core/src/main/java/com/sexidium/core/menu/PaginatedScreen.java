package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Generic paginated 54-slot chest screen displaying up to 35 items per page (Columns 2–8, Rows 0–4).
 *
 * <p>Includes built-in page controls at Row 5:
 * <ul>
 *   <li>Slot 47: Back / Close</li>
 *   <li>Slot 48: Previous Page</li>
 *   <li>Slot 49: Page Indicator</li>
 *   <li>Slot 50: Next Page</li>
 *   <li>Slot 53: Primary Action</li>
 * </ul>
 * </p>
 *
 * <p>Supports both inheritance (subclassing {@link #items(PlayerAdapter)} and {@link #renderItem})
 * and fluent composition via {@link #of(String)}.</p>
 *
 * @param <T> element type displayed in the grid
 */
public abstract class PaginatedScreen<T> extends AbstractScreen {

  private String customTitle;
  private int customRows = ChestLayout.ROWS;
  private int customPlainRows = ChestLayout.ROWS;
  private String customBackgroundArt;
  private String customScreenArt;
  private boolean customAnimated;
  private final Map<UUID, Integer> pages = new ConcurrentHashMap<>();

  public PaginatedScreen(MenuSupport support, MenuService menuService) {
    super(support, menuService);
  }

  public PaginatedScreen(String title, int rows) {
    super(null, null);
    this.customTitle = title != null ? title : "";
    this.customRows = Math.max(1, Math.min(6, rows));
    this.customPlainRows = this.customRows;
  }

  public static <T> PaginatedBuilder<T> of(String title) {
    return new PaginatedBuilder<>(title);
  }

  public static <T> PaginatedBuilder<T> of(String title, int rows) {
    return new PaginatedBuilder<>(title, rows);
  }

  @Override
  public String title() {
    return customTitle != null ? customTitle : "<gold><bold>Menu</bold></gold>";
  }

  @Override
  public int rows() {
    return customRows;
  }

  @Override
  public int plainRows() {
    return customPlainRows;
  }

  @Override
  public String backgroundArt() {
    return customBackgroundArt;
  }

  @Override
  public String screenArt() {
    return customScreenArt;
  }

  @Override
  public boolean isAnimated() {
    return customAnimated;
  }

  public int getPage(PlayerAdapter player) {
    return page(player);
  }

  public int totalPages(PlayerAdapter player) {
    List<T> allItems = items(player);
    int count = allItems != null ? allItems.size() : 0;
    int capacity = Math.max(1, pageSize());
    return Math.max(1, (count + capacity - 1) / capacity);
  }

  public int clampedPage(PlayerAdapter player) {
    return Math.max(0, Math.min(page(player), totalPages(player) - 1));
  }

  public int page(PlayerAdapter player) {
    if (player == null) {
      return 0;
    }
    return pages.getOrDefault(player.uniqueId(), 0);
  }

  public void setPage(PlayerAdapter player, int page) {
    if (player != null) {
      pages.put(player.uniqueId(), Math.max(0, page));
    }
  }

  public void resetPage(PlayerAdapter player) {
    if (player != null) {
      pages.remove(player.uniqueId());
    }
  }

  protected abstract List<T> items(PlayerAdapter player);

  protected abstract MenuButton renderItem(PlayerAdapter player, T item, int index);

  protected int pageSize() {
    return ChestLayout.CONTENT_CAPACITY;
  }

  protected MenuButton emptyStateButton(PlayerAdapter player) {
    return MenuButton.label(ItemKey.minecraft("paper"), "<gray>No items found</gray>", List.of());
  }

  protected int emptyStateSlot() {
    return 24;
  }

  @Override
  protected void populate(MenuView view, PlayerAdapter player) {
    fillSeparator(view);
    buildSidebar(view, player);
    buildContent(view, player);
    buildBottomNav(view, player);
  }

  protected void fillSeparator(MenuView view) {
    MenuButton separator = separatorButton();
    for (int slot : ChestLayout.SEPARATOR_SLOTS) {
      if (slot < view.size()) {
        view.set(slot, separator);
      }
    }
    for (int slot : ChestLayout.ROW_DIVIDER_SLOTS) {
      if (slot < view.size()) {
        view.set(slot, separator);
      }
    }
  }

  protected MenuButton separatorButton() {
    return ChestLayout.separatorButton();
  }

  protected void buildSidebar(MenuView view, PlayerAdapter player) {
    // Default no-op
  }

  protected void buildContent(MenuView view, PlayerAdapter player) {
    List<T> allItems = items(player);
    if (allItems == null) {
      allItems = List.of();
    }
    int capacity = Math.max(1, pageSize());
    int totalPages = Math.max(1, (allItems.size() + capacity - 1) / capacity);
    int currentPage = Math.max(0, Math.min(page(player), totalPages - 1));
    setPage(player, currentPage);

    if (allItems.isEmpty()) {
      MenuButton emptyBtn = emptyStateButton(player);
      if (emptyBtn != null) {
        view.set(emptyStateSlot(), emptyBtn);
      }
      return;
    }

    int start = currentPage * capacity;
    int end = Math.min(start + capacity, allItems.size());
    for (int i = start; i < end; i++) {
      T item = allItems.get(i);
      int contentIndex = i - start;
      int slot = ChestLayout.contentSlot(contentIndex);
      MenuButton btn = renderItem(player, item, i);
      if (btn != null) {
        view.set(slot, btn);
      }
    }
  }

  protected void buildBottomNav(MenuView view, PlayerAdapter player) {
    MenuButton back = buildBackButton(player);
    if (back != null) {
      view.set(ChestLayout.SLOT_BACK, back);
    }

    List<T> allItems = items(player);
    if (allItems == null) {
      allItems = List.of();
    }
    int capacity = Math.max(1, pageSize());
    int totalPages = Math.max(1, (allItems.size() + capacity - 1) / capacity);
    int currentPage = Math.max(0, Math.min(page(player), totalPages - 1));

    if (totalPages > 1) {
      if (currentPage > 0) {
        view.set(ChestLayout.SLOT_PREV, MenuButton.of(ItemKey.minecraft("arrow"), "<yellow>« Previous Page</yellow>",
            List.of("<gray>Go to page " + currentPage + " of " + totalPages + "</gray>"),
            ctx -> {
              setPage(ctx.player(), currentPage - 1);
              refresh(ctx.player());
            }).withModel(MenuArt.model(MenuArt.ICON_BACK)));
      }

      view.set(ChestLayout.SLOT_PAGE, MenuButton.label(ItemKey.minecraft("paper"),
          "<aqua><bold>Page " + (currentPage + 1) + "/" + totalPages + "</bold></aqua>",
          List.of("<gray>Total items: <white>" + allItems.size() + "</white></gray>")));

      if (currentPage < totalPages - 1) {
        view.set(ChestLayout.SLOT_NEXT, MenuButton.of(ItemKey.minecraft("arrow"), "<yellow>Next Page »</yellow>",
            List.of("<gray>Go to page " + (currentPage + 2) + " of " + totalPages + "</gray>"),
            ctx -> {
              setPage(ctx.player(), currentPage + 1);
              refresh(ctx.player());
            }));
      }
    }

    MenuButton primary = buildPrimaryButton(player);
    if (primary != null) {
      view.set(ChestLayout.SLOT_PRIMARY, primary);
    }
  }

  protected MenuButton buildBackButton(PlayerAdapter player) {
    if (support != null && menuService != null && player != null) {
      return support.backButton(() -> menuService.openMain(player));
    }
    if (support != null) {
      return support.back(ctx -> {});
    }
    return MenuButton.of(ItemKey.minecraft("arrow"), "<red>« Back</red>", ctx -> {})
        .withModel(MenuArt.model(MenuArt.ICON_BACK));
  }

  protected MenuButton buildPrimaryButton(PlayerAdapter player) {
    return null;
  }

  public MenuView build() {
    return build(null);
  }

  // ==============================================================================================
  // PAGINATED BUILDER
  // ==============================================================================================

  public static final class PaginatedBuilder<T> {
    private final String title;
    private final int rows;
    private int plainRows;
    private String background;
    private String screenArt;
    private boolean animated;
    private boolean fillSeparators = true;
    private List<T> items = List.of();
    private Function<PlayerAdapter, List<T>> dynamicItems;
    private Function<T, MenuButton> itemMapper;
    private BiFunction<PlayerAdapter, T, MenuButton> itemRenderer;
    private int page = 0;
    private MenuButton emptyIndicator;
    private Consumer<Integer> pageChangeCallback;
    private MenuButton backButton;
    private MenuButton primaryActionButton;
    private final Map<Integer, MenuButton> sidebarButtons = new LinkedHashMap<>();
    private final Map<Integer, MenuButton> contentButtons = new LinkedHashMap<>();

    public PaginatedBuilder(String title) {
      this(title, ChestLayout.ROWS);
    }

    public PaginatedBuilder(String title, int rows) {
      this.title = title != null ? title : "";
      this.rows = Math.max(1, Math.min(6, rows));
      this.plainRows = this.rows;
    }

    public PaginatedBuilder<T> background(String background) {
      this.background = background;
      return this;
    }

    public PaginatedBuilder<T> screenArt(String screenArt) {
      this.screenArt = screenArt;
      return this;
    }

    public PaginatedBuilder<T> animated(boolean animated) {
      this.animated = animated;
      return this;
    }

    public PaginatedBuilder<T> plainRows(int plainRows) {
      this.plainRows = Math.max(1, Math.min(6, plainRows));
      return this;
    }

    public PaginatedBuilder<T> fillSeparators(boolean fill) {
      this.fillSeparators = fill;
      return this;
    }

    public PaginatedBuilder<T> items(Collection<? extends T> items) {
      this.items = items == null ? List.of() : new ArrayList<>(items);
      return this;
    }

    public PaginatedBuilder<T> items(Function<PlayerAdapter, List<T>> dynamicItems) {
      this.dynamicItems = dynamicItems;
      return this;
    }

    public PaginatedBuilder<T> page(int page) {
      this.page = page;
      return this;
    }

    public int totalPages() {
      return totalPages(null);
    }

    public int totalPages(PlayerAdapter player) {
      int count = 0;
      if (dynamicItems != null) {
        try {
          List<T> list = dynamicItems.apply(player);
          count = list != null ? list.size() : 0;
        } catch (Exception ignored) {
          count = items != null ? items.size() : 0;
        }
      } else if (items != null) {
        count = items.size();
      }
      return Math.max(1, (count + ChestLayout.CONTENT_CAPACITY - 1) / ChestLayout.CONTENT_CAPACITY);
    }

    public int clampedPage() {
      return clampedPage(null);
    }

    public int clampedPage(PlayerAdapter player) {
      return Math.max(0, Math.min(page, totalPages(player) - 1));
    }

    public PaginatedBuilder<T> emptyIndicator(MenuButton emptyIndicator) {
      this.emptyIndicator = emptyIndicator;
      return this;
    }

    public PaginatedBuilder<T> itemMapper(Function<T, MenuButton> itemMapper) {
      this.itemMapper = itemMapper;
      return this;
    }

    public PaginatedBuilder<T> itemRenderer(BiFunction<PlayerAdapter, T, MenuButton> itemRenderer) {
      this.itemRenderer = itemRenderer;
      return this;
    }

    public PaginatedBuilder<T> onPageChange(Consumer<Integer> callback) {
      this.pageChangeCallback = callback;
      return this;
    }

    public PaginatedBuilder<T> back(MenuButton backButton) {
      this.backButton = backButton;
      return this;
    }

    public PaginatedBuilder<T> back(Consumer<MenuContext> onClick) {
      this.backButton = MenuButton.of(ItemKey.minecraft("arrow"), "<red>« Back</red>", onClick)
          .withModel(MenuArt.model(MenuArt.ICON_BACK));
      return this;
    }

    public PaginatedBuilder<T> primaryAction(MenuButton button) {
      this.primaryActionButton = button;
      return this;
    }

    public PaginatedBuilder<T> sidebar(int slotOrIndex, MenuButton button) {
      int slot = (slotOrIndex >= 0 && slotOrIndex < ChestLayout.SIDEBAR_CAPACITY && slotOrIndex != 0)
          ? ChestLayout.sidebarSlot(slotOrIndex) : slotOrIndex;
      if (button != null) {
        sidebarButtons.put(slot, button);
      } else {
        sidebarButtons.remove(slot);
      }
      return this;
    }

    public PaginatedBuilder<T> sidebarIndex(int sidebarIndex, MenuButton button) {
      int slot = ChestLayout.sidebarSlot(sidebarIndex);
      if (button != null) {
        sidebarButtons.put(slot, button);
      } else {
        sidebarButtons.remove(slot);
      }
      return this;
    }

    public PaginatedBuilder<T> content(int slotOrIndex, MenuButton button) {
      int slot = (slotOrIndex >= 0 && slotOrIndex < ChestLayout.CONTENT_CAPACITY && !ChestLayout.isContentSlot(slotOrIndex))
          ? ChestLayout.contentSlot(slotOrIndex) : slotOrIndex;
      if (button != null) {
        contentButtons.put(slot, button);
      } else {
        contentButtons.remove(slot);
      }
      return this;
    }

    public PaginatedBuilder<T> contentIndex(int contentIndex, MenuButton button) {
      int slot = ChestLayout.contentSlot(contentIndex);
      if (button != null) {
        contentButtons.put(slot, button);
      } else {
        contentButtons.remove(slot);
      }
      return this;
    }

    public MenuView build() {
      return build(null);
    }

    public MenuView build(PlayerAdapter player) {
      MenuView view = new MenuView(title, rows).plainRows(plainRows).animated(animated);
      if (background != null && !background.isBlank()) {
        view.background(background);
      }
      if (screenArt != null && !screenArt.isBlank()) {
        view.screenArt(screenArt);
      }
      if (fillSeparators) {
        ChestLayout.fillSeparators(view);
      }

      for (Map.Entry<Integer, MenuButton> entry : sidebarButtons.entrySet()) {
        view.set(entry.getKey(), entry.getValue());
      }
      for (Map.Entry<Integer, MenuButton> entry : contentButtons.entrySet()) {
        view.set(entry.getKey(), entry.getValue());
      }

      List<T> allItems = null;
      if (dynamicItems != null && player != null) {
        try {
          allItems = dynamicItems.apply(player);
        } catch (Exception ignored) {
        }
      }
      if (allItems == null) {
        allItems = items != null ? items : List.of();
      }

      int capacity = ChestLayout.CONTENT_CAPACITY;
      int totalPages = Math.max(1, (allItems.size() + capacity - 1) / capacity);
      int currentPage = Math.max(0, Math.min(page, totalPages - 1));

      if (allItems.isEmpty()) {
        MenuButton emptyBtn = emptyIndicator != null ? emptyIndicator
            : MenuButton.label(ItemKey.minecraft("paper"), "<gray>No items found</gray>", List.of());
        view.set(24, emptyBtn);
      } else {
        int start = currentPage * capacity;
        int end = Math.min(start + capacity, allItems.size());
        for (int i = start; i < end; i++) {
          T item = allItems.get(i);
          int contentIndex = i - start;
          int slot = ChestLayout.contentSlot(contentIndex);
          MenuButton btn = itemRenderer != null ? itemRenderer.apply(player, item)
              : (itemMapper != null ? itemMapper.apply(item) : null);
          if (btn != null) {
            view.set(slot, btn);
          }
        }
      }

      if (backButton != null) {
        view.set(ChestLayout.SLOT_BACK, backButton);
      }
      if (primaryActionButton != null) {
        view.set(ChestLayout.SLOT_PRIMARY, primaryActionButton);
      }

      if (totalPages > 1) {
        if (currentPage > 0) {
          view.set(ChestLayout.SLOT_PREV, MenuButton.of(ItemKey.minecraft("arrow"), "<yellow>« Previous Page</yellow>",
              List.of("<gray>Go to page " + currentPage + " of " + totalPages + "</gray>"),
              ctx -> {
                if (pageChangeCallback != null) {
                  pageChangeCallback.accept(currentPage - 1);
                }
              }).withModel(MenuArt.model(MenuArt.ICON_BACK)));
        }

        view.set(ChestLayout.SLOT_PAGE, MenuButton.label(ItemKey.minecraft("paper"),
            "<aqua><bold>Page " + (currentPage + 1) + "/" + totalPages + "</bold></aqua>",
            List.of("<gray>Total items: <white>" + allItems.size() + "</white></gray>")));

        if (currentPage < totalPages - 1) {
          view.set(ChestLayout.SLOT_NEXT, MenuButton.of(ItemKey.minecraft("arrow"), "<yellow>Next Page »</yellow>",
              List.of("<gray>Go to page " + (currentPage + 2) + " of " + totalPages + "</gray>"),
              ctx -> {
                if (pageChangeCallback != null) {
                  pageChangeCallback.accept(currentPage + 1);
                }
              }));
        }
      }

      return view;
    }
  }
}
