package com.sexidium.core.platform;

/**
 * Live server information surfaced to the Discord bot (address, port, players online, version, TPS).
 * Platforms provide a real implementation; the default {@link ServerAdapter#serverInfo()} returns a
 * best-effort snapshot from the online-player count and server name.
 */
public interface ServerInfoPort {
  /** A `tps` of {@code -1} means "unknown" (the bot renders it as "—"). */
  record ServerInfo(String ip, int port, int online, int max, String motd, String version, double tps) {
  }

  ServerInfo snapshot();
}
