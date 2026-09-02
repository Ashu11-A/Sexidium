package com.sexidium.core.economy;

/**
 * The answer to one money operation: what was asked for, what the account settled at, and why it did
 * not happen when it did not.
 *
 * <p>{@code balance} is the balance AFTER the operation, and it is a contract rather than a
 * convenience: {@code /pay} reports the payer's new balance in the same tick, and Vault's
 * {@code EconomyResponse} carries the resulting balance as part of its own contract. That is exactly
 * why every mutation in {@link EconomyService} is synchronous — an answer that has to be looked up
 * again a tick later is not an answer.</p>
 */
public record EconomyResult(Status status, Money amount, Money balance, String detail) {

  public enum Status {
    SUCCESS,
    INSUFFICIENT_FUNDS,
    LIMIT_EXCEEDED,
    UNKNOWN_ACCOUNT,
    INVALID_AMOUNT,
    DISABLED,
    NOT_IMPLEMENTED,
    ERROR
  }

  public boolean ok() {
    return status == Status.SUCCESS;
  }

  public static EconomyResult success(Money amount, Money balance) {
    return new EconomyResult(Status.SUCCESS, amount, balance, "");
  }

  public static EconomyResult failure(Status status, String detail) {
    return new EconomyResult(status, Money.ZERO, Money.ZERO, detail == null ? "" : detail);
  }

  /** A failure that still knows where the account stands — what an overdraw must report. */
  public static EconomyResult failure(Status status, Money amount, Money balance, String detail) {
    return new EconomyResult(status, amount, balance, detail == null ? "" : detail);
  }
}
