package com.sexidium.core.menu;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.ItemKey;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Interface and base utilities for screens containing two-tap confirmation actions (Bedrock-safe).
 */
public interface ConfirmableScreen {

  /**
   * Builds a two-tap confirmation button. The first tap arms the action and re-renders the screen;
   * the second matching tap executes {@code onConfirm}.
   */
  default MenuButton confirmButton(MenuSupport support, PlayerAdapter viewer, ItemKey icon, String model,
      String token, String idleName, List<String> idleLore, String armedName, List<String> armedLore,
      Consumer<MenuContext> onConfirm, Consumer<PlayerAdapter> reRender) {
    return support.confirmButton(viewer, icon, model, token, idleName, idleLore, armedName, armedLore,
        onConfirm, reRender);
  }

  /**
   * Checks whether the given token is currently armed for the player.
   */
  default boolean isArmed(MenuSupport support, UUID playerId, String token) {
    return support.isArmed(playerId, token);
  }

  /**
   * Clears any pending confirm token for the player.
   */
  default void clearConfirm(MenuSupport support, UUID playerId) {
    support.clearConfirm(playerId);
  }
}
