package com.sexidium.core.platform.version;

/**
 * Version facts about the running server, as far as the platform can honestly read them: the Minecraft
 * version itself and the resource-pack format that goes with it.
 *
 * <p>Carried on {@code ServerAdapter#versions()} beside the other narrow ports ({@code serverInfo()},
 * {@code health()}), so consumers ask one question in one place instead of re-deriving the version from
 * whatever string their platform happens to expose.
 *
 * <h2>The pack format keeps its doctrine</h2>
 * {@link #packFormat()} returns {@code -1}, not an exception, when the adapter has no opinion — an
 * unrecognised version string must never read as a failure, because refusing over a parsing detail
 * breaks a working server. The mapping table itself lives with whoever knows it (Paper:
 * {@code PackFormats}); this port only carries the answer.
 */
public interface ServerVersionPort {

  /** Knows nothing: {@code UNKNOWN} version, no pack-format opinion. */
  ServerVersionPort UNKNOWN = new ServerVersionPort() {
    @Override
    public ServerVersion version() {
      return ServerVersion.UNKNOWN;
    }
  };

  /** The running server's Minecraft version, parsed; {@link ServerVersion#UNKNOWN} when unreadable. */
  ServerVersion version();

  /**
   * The resource-pack format of the running server, or {@code -1} for "no opinion" — deliberately not
   * an error, per the {@code PackFormats} doctrine.
   */
  default int packFormat() {
    return -1;
  }
}
