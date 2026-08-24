package com.sexidium.core.menu.pack;

/**
 * The identity of a built resource pack: the bytes to serve, the URL a client downloads it from, and
 * the SHA-1 the platform hands to {@code setResourcePack(url, sha1)} so the client caches and verifies
 * it. Produced by {@link SexidiumResourcePack#build} and the hosting {@code ResourcePackServer},
 * consumed by each adapter's join hook.
 */
public record ResourcePackInfo(byte[] bytes, String sha1Hex, String url) {
  public ResourcePackInfo withUrl(String resolvedUrl) {
    return new ResourcePackInfo(bytes, sha1Hex, resolvedUrl);
  }
}
