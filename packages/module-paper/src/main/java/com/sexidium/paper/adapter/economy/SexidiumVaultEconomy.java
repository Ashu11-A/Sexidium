package com.sexidium.paper.adapter.economy;

import com.sexidium.core.economy.EconomyResult;
import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.economy.Money;
import net.milkbowl.vault2.economy.AccountPermission;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;
import net.milkbowl.vault2.economy.MultiEconomyResponse;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sexidium's balances, exposed as the modern {@code vault2} Economy service.
 *
 * <p>Sexidium is the PROVIDER here, not a consumer. VaultUnlocked's own jar has no economy in it —
 * {@code Vault.java} is a services-manager lookup and nothing else — so a network that installs it and
 * nothing else has no money at all. This class is what makes shops, jobs and every other Vault
 * consumer read the same balances {@code /balance} does, out of the same table, with no second source
 * of truth to reconcile.</p>
 *
 * <p>This class DOES name Vault types, which means it must only ever be loaded from behind
 * {@link VaultUnlockedLink#modernLinkable()}. {@link PaperEconomyBridge} is the only thing that
 * constructs it, and it does so inside a {@code try} after the gate.</p>
 *
 * <h2>The world overloads ignore the world, on purpose</h2>
 * Sexidium is a NETWORK with one shared database, and a player's money follows them between worlds
 * and between nodes. A per-world balance would mean a player's money changing when they walked
 * through a portal, which nothing in this project wants and no operator would expect.
 *
 * <h2>Two overrides that are not optional</h2>
 * <ul>
 *   <li><b>{@code set}.</b> The interface default is a get-then-adjust, i.e. two statements with a
 *       window between them. {@link EconomyService#set} is one.</li>
 *   <li><b>{@code transfer}.</b> The interface default is withdraw-then-deposit with a COMPENSATING
 *       re-deposit if the deposit fails. That is not atomic, and if the compensation also fails the
 *       money has been taken off one player and given to nobody, with no third step that can fix it.
 *       {@link EconomyService#transfer} is one database transaction.</li>
 * </ul>
 */
final class SexidiumVaultEconomy implements Economy {

  private final EconomyService economy;

  SexidiumVaultEconomy(EconomyService economy) {
    this.economy = economy;
  }

  // ----- identity ----------------------------------------------------------------------------------

  @Override
  public boolean isEnabled() {
    return economy.enabled();
  }

  @Override
  public String getName() {
    return "Sexidium";
  }

  /** No shared/bank accounts in v1: every account is one player's. Refusals below are honest, not lazy. */
  @Override
  public boolean hasSharedAccountSupport() {
    return false;
  }

  /** One currency. {@code currencies()} still answers with it, because consumers iterate that list. */
  @Override
  public boolean hasMultiCurrencySupport() {
    return false;
  }

  @Override
  public int fractionalDigits(String pluginName) {
    return Money.SCALE;
  }

  // ----- formatting --------------------------------------------------------------------------------

  @Override
  public String format(BigDecimal amount) {
    return economy.format().format(amount);
  }

  @Override
  public String format(String pluginName, BigDecimal amount) {
    return economy.format().format(amount);
  }

  @Override
  public String format(BigDecimal amount, String currency) {
    return economy.format().format(amount);
  }

  @Override
  public String format(String pluginName, BigDecimal amount, String currency) {
    return economy.format().format(amount);
  }

  @Override
  public boolean hasCurrency(String currency) {
    return economy.format().currencyId().equalsIgnoreCase(currency);
  }

  @Override
  public String getDefaultCurrency(String pluginName) {
    return economy.format().currencyId();
  }

  @Override
  public String defaultCurrencyNamePlural(String pluginName) {
    return economy.format().namePlural();
  }

  @Override
  public String defaultCurrencyNameSingular(String pluginName) {
    return economy.format().nameSingular();
  }

  @Override
  public Collection<String> currencies() {
    return List.of(economy.format().currencyId());
  }

  // ----- accounts ----------------------------------------------------------------------------------

  @Override
  public boolean createAccount(UUID accountID, String name) {
    return economy.ensureAccount(accountID, name, true);
  }

  @Override
  public boolean createAccount(UUID accountID, String name, boolean player) {
    return economy.ensureAccount(accountID, name, player);
  }

  @Override
  public boolean createAccount(UUID accountID, String name, String worldName) {
    return economy.ensureAccount(accountID, name, true);
  }

  @Override
  public boolean createAccount(UUID accountID, String name, String worldName, boolean player) {
    return economy.ensureAccount(accountID, name, player);
  }

  /**
   * Every account, capped. The interface offers no paging, so a large network would otherwise
   * materialise the whole table into a HashMap on whatever thread a consumer happened to call from —
   * quite possibly the main one. {@code EconomyService.NAME_MAP_LIMIT} is where it stops and warns.
   */
  @Override
  public Map<UUID, String> getUUIDNameMap() {
    return economy.nameMap();
  }

  @Override
  public Optional<String> getAccountName(UUID accountID) {
    var account = economy.account(accountID);
    return account == null ? Optional.empty() : Optional.ofNullable(account.name());
  }

  @Override
  public boolean hasAccount(UUID accountID) {
    return economy.hasAccount(accountID);
  }

  @Override
  public boolean hasAccount(UUID accountID, String worldName) {
    return economy.hasAccount(accountID);
  }

  @Override
  public boolean renameAccount(UUID accountID, String name) {
    return economy.renameAccount(accountID, name);
  }

  @Override
  public boolean renameAccount(String pluginName, UUID accountID, String name) {
    return economy.renameAccount(accountID, name);
  }

  @Override
  public boolean deleteAccount(String pluginName, UUID accountID) {
    return economy.deleteAccount(accountID);
  }

  @Override
  public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency) {
    return hasCurrency(currency);
  }

  @Override
  public boolean accountSupportsCurrency(String pluginName, UUID accountID, String currency, String world) {
    return hasCurrency(currency);
  }

  // ----- balances ----------------------------------------------------------------------------------

  @Override
  public BigDecimal getBalance(String pluginName, UUID accountID) {
    return economy.balance(accountID).toBigDecimal();
  }

  @Override
  public BigDecimal getBalance(String pluginName, UUID accountID, String world) {
    return economy.balance(accountID).toBigDecimal();
  }

  @Override
  public BigDecimal getBalance(String pluginName, UUID accountID, String world, String currency) {
    return economy.balance(accountID).toBigDecimal();
  }

  @Override
  public boolean has(String pluginName, UUID accountID, BigDecimal amount) {
    return economy.has(accountID, Money.of(amount));
  }

  @Override
  public boolean has(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
    return economy.has(accountID, Money.of(amount));
  }

  @Override
  public boolean has(String pluginName, UUID accountID, String worldName, String currency, BigDecimal amount) {
    return economy.has(accountID, Money.of(amount));
  }

  // ----- mutations ---------------------------------------------------------------------------------

  @Override
  public EconomyResponse withdraw(String pluginName, UUID accountID, BigDecimal amount) {
    return respond(economy.withdraw(accountID, Money.of(amount), reason(pluginName, "withdraw")));
  }

  @Override
  public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
    return withdraw(pluginName, accountID, amount);
  }

  @Override
  public EconomyResponse withdraw(String pluginName, UUID accountID, String worldName, String currency,
                                  BigDecimal amount) {
    return withdraw(pluginName, accountID, amount);
  }

  @Override
  public EconomyResponse deposit(String pluginName, UUID accountID, BigDecimal amount) {
    return respond(economy.deposit(accountID, Money.of(amount), reason(pluginName, "deposit")));
  }

  @Override
  public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, BigDecimal amount) {
    return deposit(pluginName, accountID, amount);
  }

  @Override
  public EconomyResponse deposit(String pluginName, UUID accountID, String worldName, String currency,
                                 BigDecimal amount) {
    return deposit(pluginName, accountID, amount);
  }

  /** Overridden so the balance is overwritten by ONE statement, not by the default's read-then-write. */
  @Override
  public EconomyResponse set(String pluginName, UUID accountID, BigDecimal amount) {
    return respond(economy.set(accountID, Money.of(amount), reason(pluginName, "set"), pluginName));
  }

  /** Overridden so a move is one transaction. See the class javadoc for what the default does instead. */
  @Override
  public MultiEconomyResponse transfer(String pluginName, UUID from, UUID to, BigDecimal amount) {
    Money value = Money.of(amount);
    EconomyResult result = economy.transfer(from, to, value, reason(pluginName, "transfer"));
    MultiEconomyResponse response = new MultiEconomyResponse(
        value.toBigDecimal(),
        result.ok() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
        result.ok() ? "" : result.detail());
    response.addBalance(from, economy.balance(from).toBigDecimal());
    response.addBalance(to, economy.balance(to).toBigDecimal());
    return response;
  }

  // ----- shared accounts: refused, and refused honestly --------------------------------------------
  //
  // Every one of these answers false rather than pretending. A consumer that is told a shared account
  // was created and then finds no money in it is a far worse outcome than one told "no" up front, and
  // hasSharedAccountSupport() already said so.

  @Override
  public boolean createSharedAccount(String pluginName, UUID accountID, String name, UUID owner) {
    return false;
  }

  @Override
  public boolean isAccountOwner(String pluginName, UUID accountID, UUID uuid) {
    // A player IS the owner of their own account, which is the only ownership this version models.
    return accountID != null && accountID.equals(uuid);
  }

  @Override
  public boolean setOwner(String pluginName, UUID accountID, UUID uuid) {
    return false;
  }

  @Override
  public boolean isAccountMember(String pluginName, UUID accountID, UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid) {
    return false;
  }

  @Override
  public boolean addAccountMember(String pluginName, UUID accountID, UUID uuid,
                                  AccountPermission... initialPermissions) {
    return false;
  }

  @Override
  public boolean removeAccountMember(String pluginName, UUID accountID, UUID uuid) {
    return false;
  }

  @Override
  public boolean hasAccountPermission(String pluginName, UUID accountID, UUID uuid,
                                      AccountPermission permission) {
    return false;
  }

  @Override
  public boolean updateAccountPermission(String pluginName, UUID accountID, UUID uuid,
                                         AccountPermission permission, boolean value) {
    return false;
  }

  // ----- helpers -----------------------------------------------------------------------------------

  /** The ledger's {@code reason}, so an audit row names the plugin that caused the movement. */
  private static String reason(String pluginName, String operation) {
    return (pluginName == null || pluginName.isBlank() ? "vault" : pluginName) + ":" + operation;
  }

  private static EconomyResponse respond(EconomyResult result) {
    return new EconomyResponse(
        result.amount().toBigDecimal(),
        result.balance().toBigDecimal(),
        result.ok() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,
        result.ok() ? "" : result.detail());
  }
}
