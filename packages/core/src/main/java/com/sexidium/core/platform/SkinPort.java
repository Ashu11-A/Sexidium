package com.sexidium.core.platform;

import java.util.Optional;

/**
 * Resolves a player's skin (the skin they set via SkinsRestorer) to its raw Mojang texture property.
 * The bridge decodes the base64 {@code value} to pull the texture PNG URL and hands it to the bot,
 * which composites a 2D head for rank cards. Default {@link ServerAdapter#skins()} resolves nothing.
 */
public interface SkinPort {
  /** The raw Mojang texture property: {@code value} is base64-encoded JSON, {@code signature} may be null. */
  record SkinTexture(String value, String signature) {
  }

  /** Resolve by player name / skin identifier. May block (network); callers run it off the main thread. */
  Optional<SkinTexture> resolve(String target);

  SkinPort NOOP = target -> Optional.empty();
}
