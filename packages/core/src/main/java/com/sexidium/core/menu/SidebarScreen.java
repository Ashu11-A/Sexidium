package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A standard 54-slot (6 rows × 9 columns) double-chest screen with a Column 0 sidebar,
 * Column 1 separator, 28-slot content area (Columns 2–8, Rows 0–3), and Row 5 bottom navigation bar.
 *
 * <p>Supports both inheritance (subclassing {@link #buildSidebar}, {@link #buildContent}, {@link #buildBottomNav})
 * and fluent composition via {@link #of(String)}.</p>
 */
public class SidebarScreen extends AbstractScreen {

  private String customTitle;
  private int customRows = ChestLayout.ROWS;
  private int customPlainRows = ChestLayout.ROWS;
  private String customBackgroundArt;
  private String customScreenArt;
  private boolean customAnimated;
  private boolean fillSeparators = true;
  private final Map<Integer, MenuButton> fluentButtons = new LinkedHashMap<>();
  private MenuButton customBackButton;
  private MenuButton primaryActionButton;

  public SidebarScreen(MenuSupport support, MenuService menuService) {
    super(support, menuService);
  }

  public SidebarScreen(String title, int rows) {
    super(null, null);
    this.customTitle = title != null ? title : "";
    this.customRows = Math.max(1, Math.min(6, rows));
    this.customPlainRows = this.customRows;
  }

  public static Builder of(String title) {
    return new Builder(title);
  }

  public static Builder of(String title, int rows) {
    return new Builder(title, rows);
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

  public SidebarScreen background(String glyphId) {
    this.customBackgroundArt = glyphId;
    return this;
  }

  public SidebarScreen screenArt(String sceneId) {
    this.customScreenArt = sceneId;
    return this;
  }

  public SidebarScreen animated(boolean animated) {
    this.customAnimated = animated;
    return this;
  }

  public SidebarScreen plainRows(int plainRows) {
    this.customPlainRows = Math.max(1, Math.min(6, plainRows));
    return this;
  }

  public SidebarScreen fillSeparators(boolean fill) {
    this.fillSeparators = fill;
    return this;
  }

  public SidebarScreen sidebar(int slotOrIndex, MenuButton button) {
    int slot = (slotOrIndex >= 0 && slotOrIndex < ChestLayout.SIDEBAR_CAPACITY && slotOrIndex != 0)
        ? ChestLayout.sidebarSlot(slotOrIndex) : slotOrIndex;
    if (button != null) {
      fluentButtons.put(slot, button);
    } else {
      fluentButtons.remove(slot);
    }
    return this;
  }

  public SidebarScreen sidebarIndex(int sidebarIndex, MenuButton button) {
    int slot = ChestLayout.sidebarSlot(sidebarIndex);
    if (button != null) {
      fluentButtons.put(slot, button);
    } else {
      fluentButtons.remove(slot);
    }
    return this;
  }

  public SidebarScreen content(int slotOrIndex, MenuButton button) {
    int slot = (slotOrIndex >= 0 && slotOrIndex < ChestLayout.CONTENT_CAPACITY && !ChestLayout.isContentSlot(slotOrIndex))
        ? ChestLayout.contentSlot(slotOrIndex) : slotOrIndex;
    if (button != null) {
      fluentButtons.put(slot, button);
    } else {
      fluentButtons.remove(slot);
    }
    return this;
  }

  public SidebarScreen contentIndex(int contentIndex, MenuButton button) {
    int slot = ChestLayout.contentSlot(contentIndex);
    if (button != null) {
      fluentButtons.put(slot, button);
    } else {
      fluentButtons.remove(slot);
    }
    return this;
  }

  public SidebarScreen content(List<MenuButton> contentButtons) {
    if (contentButtons != null) {
      int count = Math.min(contentButtons.size(), ChestLayout.CONTENT_CAPACITY);
      for (int i = 0; i < count; i++) {
        MenuButton button = contentButtons.get(i);
        int slot = ChestLayout.contentSlot(i);
        if (button != null) {
          fluentButtons.put(slot, button);
        } else {
          fluentButtons.remove(slot);
        }
      }
    }
    return this;
  }

  public SidebarScreen back(MenuButton backButton) {
    this.customBackButton = backButton;
    if (backButton != null) {
      fluentButtons.put(ChestLayout.SLOT_BACK, backButton);
    } else {
      fluentButtons.remove(ChestLayout.SLOT_BACK);
    }
    return this;
  }

  public SidebarScreen back(Consumer<MenuContext> onClick) {
    this.customBackButton = MenuButton.of(ItemKey.minecraft("arrow"), "<red>« Back</red>", onClick)
        .withModel(MenuArt.model(MenuArt.ICON_BACK));
    fluentButtons.put(ChestLayout.SLOT_BACK, this.customBackButton);
    return this;
  }

  public SidebarScreen primaryAction(MenuButton button) {
    this.primaryActionButton = button;
    if (button != null) {
      fluentButtons.put(ChestLayout.SLOT_PRIMARY, button);
    } else {
      fluentButtons.remove(ChestLayout.SLOT_PRIMARY);
    }
    return this;
  }

  public SidebarScreen set(int slot, MenuButton button) {
    if (button != null) {
      fluentButtons.put(slot, button);
    } else {
      fluentButtons.remove(slot);
    }
    return this;
  }

  public MenuView build() {
    return build(null);
  }

  @Override
  protected void populate(MenuView view, PlayerAdapter player) {
    if (fillSeparators) {
      fillSeparator(view);
    }
    buildSidebar(view, player);
    buildContent(view, player);
    buildBottomNav(view, player);

    for (Map.Entry<Integer, MenuButton> entry : fluentButtons.entrySet()) {
      view.set(entry.getKey(), entry.getValue());
    }
    if (customBackButton != null) {
      view.set(ChestLayout.SLOT_BACK, customBackButton);
    }
    if (primaryActionButton != null) {
      view.set(ChestLayout.SLOT_PRIMARY, primaryActionButton);
    }
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
    // Subclasses override
  }

  protected void buildContent(MenuView view, PlayerAdapter player) {
    // Subclasses override
  }

  protected void buildBottomNav(MenuView view, PlayerAdapter player) {
    MenuButton back = buildBackButton(player);
    if (back != null) {
      view.set(ChestLayout.SLOT_BACK, back);
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

  public static class Builder extends SidebarScreen {
    public Builder(String title) {
      super(title, ChestLayout.ROWS);
    }

    public Builder(String title, int rows) {
      super(title, rows);
    }

    @Override
    public Builder background(String glyphId) {
      super.background(glyphId);
      return this;
    }

    @Override
    public Builder screenArt(String sceneId) {
      super.screenArt(sceneId);
      return this;
    }

    @Override
    public Builder animated(boolean animated) {
      super.animated(animated);
      return this;
    }

    @Override
    public Builder plainRows(int plainRows) {
      super.plainRows(plainRows);
      return this;
    }

    @Override
    public Builder fillSeparators(boolean fill) {
      super.fillSeparators(fill);
      return this;
    }

    @Override
    public Builder sidebar(int slotOrIndex, MenuButton button) {
      super.sidebar(slotOrIndex, button);
      return this;
    }

    @Override
    public Builder sidebarIndex(int sidebarIndex, MenuButton button) {
      super.sidebarIndex(sidebarIndex, button);
      return this;
    }

    @Override
    public Builder content(int slotOrIndex, MenuButton button) {
      super.content(slotOrIndex, button);
      return this;
    }

    @Override
    public Builder contentIndex(int contentIndex, MenuButton button) {
      super.contentIndex(contentIndex, button);
      return this;
    }

    @Override
    public Builder content(List<MenuButton> contentButtons) {
      super.content(contentButtons);
      return this;
    }

    @Override
    public Builder back(MenuButton backButton) {
      super.back(backButton);
      return this;
    }

    @Override
    public Builder back(Consumer<MenuContext> onClick) {
      super.back(onClick);
      return this;
    }

    @Override
    public Builder primaryAction(MenuButton button) {
      super.primaryAction(button);
      return this;
    }

    @Override
    public Builder set(int slot, MenuButton button) {
      super.set(slot, button);
      return this;
    }
  }
}
