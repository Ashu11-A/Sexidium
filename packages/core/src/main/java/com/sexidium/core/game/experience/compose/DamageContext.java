package com.sexidium.core.game.experience.compose;

import com.sexidium.core.platform.PlayerAdapter;
import com.sexidium.core.platform.model.DamageCauseType;

/**
 * One incoming player hit as it flows through the {@link DamagePipeline}. {@link #amount} is the
 * remaining unhandled damage: a contributor reduces it (Resistance), drains it into a pool
 * (Shared Life) or converts it (XP Health), and may {@link #absorb()} so the native hit deals no
 * heart damage. Exactly one contributor may {@link #markFatalHandled()} to claim a lethal hit, so
 * Shared Life and Chained no longer both reset the team.
 */
public final class DamageContext {
  private final PlayerAdapter victim;
  private final PlayerAdapter attacker;
  private final DamageCauseType cause;
  private final double originalAmount;
  private double amount;
  private boolean absorbed;
  private boolean fatalHandled;

  public DamageContext(PlayerAdapter victim, PlayerAdapter attacker, DamageCauseType cause, double amount) {
    this.victim = victim;
    this.attacker = attacker;
    this.cause = cause == null ? DamageCauseType.UNKNOWN : cause;
    this.originalAmount = Math.max(0.0, amount);
    this.amount = this.originalAmount;
  }

  public PlayerAdapter victim() {
    return victim;
  }

  public PlayerAdapter attacker() {
    return attacker;
  }

  public DamageCauseType cause() {
    return cause;
  }

  /** Damage as it originally arrived (before any contributor reduced it). */
  public double originalAmount() {
    return originalAmount;
  }

  /** Remaining unhandled damage. */
  public double amount() {
    return amount;
  }

  /** Reduce remaining damage by {@code by} (clamped at 0). */
  public void reduce(double by) {
    amount = Math.max(0.0, amount - Math.max(0.0, by));
  }

  /** Consume all remaining damage (a downstream contributor sees 0). */
  public void consume() {
    amount = 0.0;
  }

  /** Whether the native hit should be cancelled (no heart damage applied). */
  public boolean absorbed() {
    return absorbed;
  }

  /** Cancel the native hit; the contributor has accounted for the damage itself. */
  public void absorb() {
    absorbed = true;
  }

  public boolean fatalHandled() {
    return fatalHandled;
  }

  /** Claim a lethal hit so later contributors do not also trigger a death/reset. */
  public void markFatalHandled() {
    fatalHandled = true;
    absorbed = true;
  }
}
