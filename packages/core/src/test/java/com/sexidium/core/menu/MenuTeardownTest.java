package com.sexidium.core.menu;

import com.sexidium.core.SexidiumCore;
import com.sexidium.core.SexidiumCoreDependencies;
import com.sexidium.core.game.GameRegistry;
import com.sexidium.core.platform.MenuAdapter;
import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.noop.NoopKitAdapter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Open menus and teardown.
 *
 * <p>{@code MenuService} had no stop at all and was absent from {@code SexidiumCore.close()}, so a
 * chest GUI outlived the listener that cancels its clicks — and every button in it became a
 * <b>real item</b> the player could drag into their own inventory. That is not a cosmetic glitch on
 * the way down; it is item duplication, on every stop, and a rolling update is a stop per node.</p>
 */
class MenuTeardownTest {

  /** Counts closeAll, so the test asserts the teardown ORDER rather than a screenshot. */
  private static final class CountingMenus implements MenuAdapter {
    final AtomicInteger closedAll = new AtomicInteger();

    @Override public void open(PlayerAdapter player, MenuView view) { }

    @Override public void closeAll() {
      closedAll.incrementAndGet();
    }
  }

  /** {@link com.sexidium.core.TestServerAdapter} with one method made real: the menu adapter. */
  private static final class MenuTestServerAdapter extends com.sexidium.core.TestServerAdapter {
    private final Path dataDirectory;
    private final MenuAdapter menus;

    MenuTestServerAdapter(Path dataDirectory, MenuAdapter menus) {
      this.dataDirectory = dataDirectory;
      this.menus = menus;
    }

    @Override public Path dataDirectory() {
      return dataDirectory;
    }

    @Override public MenuAdapter menus() {
      return menus;
    }
  }

  @TempDir
  Path tmp;

  @Test
  @DisplayName("closing the core closes every open menu")
  void closeAllRunsOnTeardown() {
    CountingMenus menus = new CountingMenus();
    MenuTestServerAdapter server = new MenuTestServerAdapter(tmp, menus);
    SexidiumCore core = new SexidiumCore(new SexidiumCoreDependencies(
        server, new NoopKitAdapter(), new GameRegistry(), null, null, () -> false));

    core.close();

    assertEquals(1, menus.closedAll.get(),
        "an open chest GUI whose listener has gone away is an inventory full of takeable items");
  }

  @Test
  @DisplayName("MenuService.closeAll never throws, whatever the platform does")
  void closeAllSwallowsPlatformFailures() {
    MenuAdapter exploding = new MenuAdapter() {
      @Override public void open(PlayerAdapter player, MenuView view) { }

      @Override public void closeAll() {
        throw new IllegalStateException("no server");
      }
    };
    MenuTestServerAdapter server = new MenuTestServerAdapter(tmp, exploding);
    SexidiumCore core = new SexidiumCore(new SexidiumCoreDependencies(
        server, new NoopKitAdapter(), new GameRegistry(), null, null, () -> false));

    core.menus().closeAll();
    core.close();
    assertTrue(true, "it runs on the way down, where a throw costs the rest of the teardown");
  }
}
