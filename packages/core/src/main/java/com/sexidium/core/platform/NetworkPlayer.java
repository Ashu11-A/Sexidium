package com.sexidium.core.platform;

import com.sexidium.core.network.NodeIdentity;

import java.util.UUID;

/**
 * A player as a proxy can see them: identity, locale, permissions, messaging. No world, no body.
 *
 * <p>This is the half of {@link PlayerAdapter} that does not require a running Minecraft server.
 * {@code PlayerAdapter} adds ~20 abstract and ~38 default methods covering position, teleport,
 * inventory, health and game mode — none of which a proxy can implement honestly, and an adapter full
 * of no-op stubs that <em>claim</em> to teleport is worse than one that cannot be asked to.</p>
 *
 * <p>Splitting it here is what keeps the Velocity module at roughly 1,200 lines instead of reimplementing
 * a 32-interface surface.</p>
 */
public interface NetworkPlayer extends CommandSource {

  UUID uniqueId();

  boolean online();

  /** Which node currently owns this player's connection. Always the local node when standalone. */
  default String nodeId() {
    return NodeIdentity.LOCAL_NODE;
  }

  @Override
  default boolean playerSource() {
    return true;
  }
}
