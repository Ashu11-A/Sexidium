package com.sexidium.paper.adapter.npc;

import com.sexidium.core.platform.SkinPort;

import java.util.Optional;

/**
 * {@link SkinPort} backed by SkinsRestorer. Lives in this package so it can use the package-private
 * {@link PaperNpcSkinResolver}. Uses the blocking {@code resolveOrCreate} lookup (the same one
 * SkinsRestorer's own {@code /skin} command uses) so a URL/custom skin is generated on demand; the
 * bridge only calls this from a background dispatch thread, never the main thread.
 */
public final class PaperSkinPort implements SkinPort {
  @Override
  public Optional<SkinTexture> resolve(String target) {
    return PaperNpcSkinResolver.resolveOrCreate(target)
        .map(texture -> new SkinTexture(texture.value(), texture.signature()));
  }
}
