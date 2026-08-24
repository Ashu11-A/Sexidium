package com.sexidium.core.game.experience;

import java.util.List;

/**
 * Thrown when a world-shaping question is asked about challenge ids this build does not have.
 *
 * <p>The failure it replaces was silent and permanent. {@link ChallengeCatalog#create} drops ids it
 * does not know, and {@code anyRequiresVoidWorld} is answered from that list — so a node one build
 * behind (or with a typo in {@code experiences.challenges}) answered <b>false</b> instead of
 * erroring, and the world was generated with the normal terrain generator over what should have been
 * an empty void SkyBlock. The player's island is then buried under a mountain, and nothing anywhere
 * logged a word about it.</p>
 *
 * <p>This is a <b>backstop, not the normal path</b>. On a network the content gate refuses the world
 * first, naming the node that can run it; this exception is what catches the case where the gate was
 * never wired — a standalone server, or a code path that opens a world without going through it.</p>
 */
public final class UnknownChallengeException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  private final List<String> unknownIds;

  public UnknownChallengeException(List<String> unknownIds) {
    super("This build does not have the challenge(s) " + String.join(", ", unknownIds)
        + ". Refusing to decide how to generate a world from a challenge list it cannot read:"
        + " guessing here generates normal terrain over a void SkyBlock and destroys the save.");
    this.unknownIds = List.copyOf(unknownIds);
  }

  /** The ids that were not in the catalog, in request order. */
  public List<String> unknownIds() {
    return unknownIds;
  }
}
