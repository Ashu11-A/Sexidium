package com.sexidium.core.game;

import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.Set;
import java.util.UUID;

/**
 * Recording boss-bar / HUD-panel handles shared by the {@code AbstractGame} test
 * suite. Extracted verbatim from the former nested classes of
 * {@code AbstractGameTest}.
 */
final class GameTestUiHandles {
  private GameTestUiHandles() {}
}

final class RecordingBossBar implements com.sexidium.core.platform.BossBarHandle {
  final Set<UUID> hidden = new java.util.HashSet<>();
  final Set<UUID> shown = new java.util.HashSet<>();
  boolean closed;
  @Override public void title(LocalizedText localizedText) {}
  @Override public void progress(float progress) {}
  @Override public void show(PlayerAdapter playerAdapter) { if (playerAdapter != null) shown.add(playerAdapter.uniqueId()); }
  @Override public void hide(PlayerAdapter playerAdapter) { if (playerAdapter != null) hidden.add(playerAdapter.uniqueId()); }
  @Override public void close() { closed = true; }
}

final class RecordingPanel implements com.sexidium.core.platform.HudPanelHandle {
  final Set<UUID> hidden = new java.util.HashSet<>();
  final Set<UUID> shown = new java.util.HashSet<>();
  @Override public void line(int index, LocalizedText localizedText) {}
  @Override public void removeLine(int index) {}
  @Override public void show(PlayerAdapter playerAdapter) { if (playerAdapter != null) shown.add(playerAdapter.uniqueId()); }
  @Override public void hide(PlayerAdapter playerAdapter) { if (playerAdapter != null) hidden.add(playerAdapter.uniqueId()); }
  @Override public void close() {}
}
