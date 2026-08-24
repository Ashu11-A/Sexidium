package com.sexidium.core.game.hardcore;

import java.util.List;

/**
 * What a set of challenges demands of the hardcore system, resolved once and answered everywhere.
 *
 * <h2>Why this exists</h2>
 * "Is this experience hardcore?" had three different answers depending on who asked. The owner's toggle
 * said one thing; {@code Challenge#requiresHardcore} forced another; and the death outcome — whether a
 * death ends the world or replaces it — was a third fact, resolved separately again. Each caller stitched
 * its own version together, and they disagreed: the manage screen offered "Hardcore: OFF — click to turn
 * it on" for an experience the service had already forced hardcore ON and would refuse to turn off. The
 * player was being shown a switch that did nothing.
 *
 * <p>So the question is asked once, here, and this is the single answer. A caller that renders a menu, a
 * caller that builds a world, and a caller that handles a death all read the same object, so they cannot
 * drift apart.</p>
 *
 * <h2>Modular by construction</h2>
 * Nothing in here knows what Death Resets is. A challenge declares two independent things — whether it
 * needs hardcore at all ({@link com.sexidium.core.game.experience.Challenge#requiresHardcore()}) and what
 * a death should cost ({@link com.sexidium.core.game.experience.Challenge#hardcoreDeathOutcome()}) — and
 * this resolves a selection of them into one coherent demand. A new mode gets the whole system by
 * answering those two questions; a mode with a different peculiarity adds a case to
 * {@link HardcoreDeathOutcome} rather than a branch in the host.
 *
 * @param required whether hardcore is forced on regardless of what the owner chose
 * @param outcome  what a death costs; null when nothing has an opinion (the mode's own default stands)
 * @param reason   which challenge forced it, for telling the player why the switch is locked; null when
 *                 hardcore is the owner's free choice
 */
public record HardcoreDemand(boolean required, HardcoreDeathOutcome outcome, String reason) {

  /** Nothing demanded: hardcore is entirely the owner's choice and a death means what the mode says. */
  public static final HardcoreDemand NONE = new HardcoreDemand(false, null, null);

  /**
   * Resolves the demand of a whole selection.
   *
   * <p>The FIRST challenge with an opinion on the outcome wins, so two of them can never fight over one
   * death. Requiring hardcore is a logical OR — one challenge that needs the stakes is enough, and no
   * other challenge can vote them away.</p>
   *
   * @param entries the live challenges, each described by what it requires and what a death costs
   */
  public static HardcoreDemand of(List<Source> entries) {
    if (entries == null || entries.isEmpty()) {
      return NONE;
    }
    boolean required = false;
    HardcoreDeathOutcome outcome = null;
    String reason = null;
    for (Source entry : entries) {
      if (entry == null) {
        continue;
      }
      if (entry.requiresHardcore() && !required) {
        required = true;
        reason = entry.displayName();
      }
      if (outcome == null) {
        outcome = entry.deathOutcome();
      }
    }
    return required || outcome != null ? new HardcoreDemand(required, outcome, reason) : NONE;
  }

  /**
   * Whether the owner may still change the hardcore setting. False once something requires it — and the
   * menu must render the control as locked-ON rather than as an off switch, or it offers a choice the
   * service will refuse.
   */
  public boolean ownerMayChoose() {
    return !required;
  }

  /** The effective hardcore flag for an owner who chose {@code chosen}. */
  public boolean appliesTo(boolean chosen) {
    return required || chosen;
  }

  /**
   * What a death costs, falling back to {@code fallback} when nothing has an opinion. Kept as a method
   * rather than resolved eagerly so the caller's own default stays the caller's business.
   */
  public HardcoreDeathOutcome outcomeOr(HardcoreDeathOutcome fallback) {
    return outcome == null ? fallback : outcome;
  }

  /** What this system needs to know about one challenge, so it never has to import the challenge API. */
  public interface Source {
    boolean requiresHardcore();

    HardcoreDeathOutcome deathOutcome();

    String displayName();
  }
}
