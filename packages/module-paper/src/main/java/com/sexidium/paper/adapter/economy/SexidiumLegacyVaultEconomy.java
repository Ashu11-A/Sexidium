package com.sexidium.paper.adapter.economy;

import com.sexidium.core.economy.EconomyAccount;
import com.sexidium.core.economy.EconomyResult;
import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.economy.Money;
import net.milkbowl.vault.economy.AbstractEconomy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The same balances, exposed as the LEGACY {@code net.milkbowl.vault.economy.Economy} service.
 *
 * <p>Registered as well as the {@code vault2} one, and not instead of it, because VaultUnlocked does
 * NOT bridge vault2 back to vault1 — while the overwhelming majority of shop, job and quest plugins
 * still ask for vault1 and nothing else. Registering only the modern interface would mean a correctly
 * installed economy that no shop on the server can see.</p>
 *
 * <p>{@link AbstractEconomy} already implements every {@code OfflinePlayer} overload on top of the
 * {@code String playerName} ones, so only the name-addressed half is written here.</p>
 *
 * <p>Every {@code double} crosses to {@link Money} through {@code BigDecimal.valueOf(double)} and
 * NEVER {@code new BigDecimal(double)}. The constructor takes the exact binary value — {@code 0.1}
 * becomes {@code 0.1000000000000000055511151231257827021181583404541015625} — which then rounds to a
 * cent that is off by one often enough to matter. {@code valueOf} goes through
 * {@code Double.toString} and gives the number the caller meant.</p>
 */
final class SexidiumLegacyVaultEconomy extends AbstractEconomy {

  private final EconomyService economy;

  SexidiumLegacyVaultEconomy(EconomyService economy) {
    this.economy = economy;
  }

  @Override
  public boolean isEnabled() {
    return economy.enabled();
  }

  @Override
  public String getName() {
    return "Sexidium";
  }

  @Override
  public int fractionalDigits() {
    return Money.SCALE;
  }

  @Override
  public String format(double amount) {
    return economy.format().format(BigDecimal.valueOf(amount));
  }

  @Override
  public String currencyNamePlural() {
    return economy.format().namePlural();
  }

  @Override
  public String currencyNameSingular() {
    return economy.format().nameSingular();
  }

  // ----- accounts ----------------------------------------------------------------------------------

  @Override
  public boolean hasAccount(String playerName) {
    UUID playerId = resolve(playerName);
    return playerId != null && economy.hasAccount(playerId);
  }

  @Override
  public boolean hasAccount(String playerName, String worldName) {
    return hasAccount(playerName);
  }

  @Override
  public boolean createPlayerAccount(String playerName) {
    UUID playerId = resolve(playerName);
    return playerId != null && economy.ensureAccount(playerId, playerName, true);
  }

  @Override
  public boolean createPlayerAccount(String playerName, String worldName) {
    return createPlayerAccount(playerName);
  }

  // ----- balances ----------------------------------------------------------------------------------

  @Override
  public double getBalance(String playerName) {
    UUID playerId = resolve(playerName);
    return playerId == null ? 0.0D : economy.balance(playerId).toBigDecimal().doubleValue();
  }

  @Override
  public double getBalance(String playerName, String worldName) {
    return getBalance(playerName);
  }

  @Override
  public boolean has(String playerName, double amount) {
    UUID playerId = resolve(playerName);
    return playerId != null && economy.has(playerId, Money.of(BigDecimal.valueOf(amount)));
  }

  @Override
  public boolean has(String playerName, String worldName, double amount) {
    return has(playerName, amount);
  }

  // ----- mutations ---------------------------------------------------------------------------------

  @Override
  public EconomyResponse withdrawPlayer(String playerName, double amount) {
    UUID playerId = resolve(playerName);
    if (playerId == null) {
      return unknown();
    }
    return respond(economy.withdraw(playerId, Money.of(BigDecimal.valueOf(amount)), "vault1:withdraw"));
  }

  @Override
  public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
    return withdrawPlayer(playerName, amount);
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, double amount) {
    UUID playerId = resolve(playerName);
    if (playerId == null) {
      return unknown();
    }
    return respond(economy.deposit(playerId, Money.of(BigDecimal.valueOf(amount)), "vault1:deposit"));
  }

  @Override
  public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
    return depositPlayer(playerName, amount);
  }

  // ----- banks: not supported ----------------------------------------------------------------------
  //
  // NOT_IMPLEMENTED and not FAILURE, because the two mean different things to a Vault consumer: a
  // failure invites a retry, "not implemented" tells it to stop asking.

  @Override
  public boolean hasBankSupport() {
    return false;
  }

  @Override
  public EconomyResponse createBank(String name, String playerName) {
    return notImplemented();
  }

  @Override
  public EconomyResponse deleteBank(String name) {
    return notImplemented();
  }

  @Override
  public EconomyResponse bankBalance(String name) {
    return notImplemented();
  }

  @Override
  public EconomyResponse bankHas(String name, double amount) {
    return notImplemented();
  }

  @Override
  public EconomyResponse bankWithdraw(String name, double amount) {
    return notImplemented();
  }

  @Override
  public EconomyResponse bankDeposit(String name, double amount) {
    return notImplemented();
  }

  @Override
  public EconomyResponse isBankOwner(String name, String playerName) {
    return notImplemented();
  }

  @Override
  public EconomyResponse isBankMember(String name, String playerName) {
    return notImplemented();
  }

  @Override
  public List<String> getBanks() {
    return List.of();
  }

  // ----- helpers -----------------------------------------------------------------------------------

  /**
   * Name to uuid, in the order that gets the right answer most often.
   *
   * <p>The account table comes BEFORE {@code Bukkit.getOfflinePlayer}: on an online-mode server that
   * call can be a blocking Mojang lookup on whatever thread the shop plugin used, and on a cracked
   * server it derives the offline uuid from the name — which is right until a player renames, at which
   * point it silently addresses a different account from the one {@code /balance} shows. A row we
   * already have is both faster and more correct.</p>
   */
  private UUID resolve(String playerName) {
    if (playerName == null || playerName.isBlank()) {
      return null;
    }
    EconomyAccount account = economy.accountByName(playerName);
    if (account != null) {
      try {
        return UUID.fromString(account.accountId());
      } catch (IllegalArgumentException notAUuid) {
        return null;
      }
    }
    try {
      return Bukkit.getOfflinePlayer(playerName).getUniqueId();
    } catch (RuntimeException | LinkageError unavailable) {
      // No server (unit tests), or a fork without the lookup. "Unknown player" is the honest answer.
      return null;
    }
  }

  private static EconomyResponse respond(EconomyResult result) {
    return new EconomyResponse(
        result.amount().toBigDecimal().doubleValue(),
        result.balance().toBigDecimal().doubleValue(),
        result.ok() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
        result.ok() ? "" : result.detail());
  }

  private static EconomyResponse unknown() {
    return new EconomyResponse(0.0D, 0.0D, EconomyResponse.ResponseType.FAILURE, "unknown player");
  }

  private static EconomyResponse notImplemented() {
    return new EconomyResponse(0.0D, 0.0D, EconomyResponse.ResponseType.NOT_IMPLEMENTED,
        "Sexidium has no bank accounts");
  }
}
