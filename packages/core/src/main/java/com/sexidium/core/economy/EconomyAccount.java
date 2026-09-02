package com.sexidium.core.economy;

/**
 * One row of {@code economy_accounts}.
 *
 * <p>{@code player} discriminates a real player's account from anything else that may later hold
 * money (Vault's shared accounts). Sexidium creates only player accounts today; the flag exists so
 * that a future non-player account does not need a schema change, and so {@code /baltop} can already
 * be written to exclude anything that is not a player.</p>
 */
public record EconomyAccount(
    String accountId,
    String name,
    boolean player,
    Money balance,
    long createdAt,
    long updatedAt
) {
}
