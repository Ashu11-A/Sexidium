package com.sexidium.core.economy;

import com.sexidium.core.lib.data.Database;
import com.sexidium.core.lib.data.WriterQueues;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The money ledger: balances, the operations that move them, and the audit trail of both.
 *
 * <h2>Why every mutation is synchronous</h2>
 * {@code RankService} pushes its writes onto a single-threaded executor and returns immediately,
 * which is right for points — nobody notices a leaderboard being a tick behind. It is WRONG for
 * money, twice over. {@code /pay} has to report the payer's new balance in the reply the player is
 * reading, and Vault's {@code EconomyResponse} carries the resulting balance as part of its
 * contract; a mutation that answers "I will get to it" cannot fill in either. So every deposit,
 * withdrawal, set and transfer runs on the CALLING thread, inside {@code synchronized
 * (database.lock())}, and writes through to the cache before it returns.
 *
 * <h2>The correctness primitive: one conditional UPDATE</h2>
 * A balance is never read, checked and then written. That sequence has a window between the read and
 * the write, and two withdrawals inside that window both see enough money and both succeed —
 * money out of nothing. Instead the check IS the write:
 *
 * <pre>{@code
 * UPDATE economy_accounts SET balance = balance + ?, updated_at = ?
 *  WHERE account_id = ? AND balance + ? >= ?
 * }</pre>
 *
 * <p>{@code executeUpdate() == 1} means the debit happened AND the floor held; {@code 0} means there
 * was not enough money and nothing changed. One statement, no window, and identical semantics on
 * SQLite, MySQL and PostgreSQL. {@code EconomyConcurrencyTest} is the proof: eight threads racing
 * 1600 withdrawals against 1000 cents settle at exactly 1000 successes and a balance of zero.
 *
 * <h2>What the writer thread is still for</h2>
 * Non-interactive work only — materialising an account on join, and the ledger retention sweep. It
 * also gives {@link #flushWrites()} and {@link #shutdown()} something real to wait on, which is what
 * keeps a drain from dropping queued rows the way all three writers used to (see
 * {@link WriterQueues}).
 *
 * <p><b>Ledger rows go on that thread and never inside a mutation's own statement sequence.</b> An
 * audit row that fails to insert must not fail a payment that already happened; the alternative —
 * rolling the payment back because its receipt could not be written — is strictly worse for the
 * player and no better for the operator.</p>
 */
public final class EconomyService implements EconomyPort {

  /** Ledger {@code op} values. Strings, so a new operation cannot renumber the old rows. */
  public static final String OP_DEPOSIT = "deposit";
  public static final String OP_WITHDRAW = "withdraw";
  public static final String OP_SET = "set";
  public static final String OP_TRANSFER_OUT = "transfer-out";
  public static final String OP_TRANSFER_IN = "transfer-in";

  /**
   * How many rows {@code getUUIDNameMap()} will hand a Vault consumer before it stops and warns.
   * The method has no paging in the interface, so on a large network it is a full table scan
   * materialised into a HashMap on whatever thread asked; the cap is what keeps another plugin's
   * one-line call from being a multi-second stall on the main thread.
   */
  public static final int NAME_MAP_LIMIT = 10_000;

  /** Notified after a local balance change, so the node can publish it on the bus. */
  @FunctionalInterface
  public interface BalanceChangeListener {
    void onChange(UUID accountId, String accountName);
  }

  private record CachedBalance(long minorUnits, long readAt) {
  }

  private final ConfigurationAdapter configuration;
  private final LoggerAdapter logger;
  private final Database database;
  private final CurrencyFormat format;
  private final String nodeId;

  private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
    Thread writerThread = new Thread(runnable, "Sexidium-Economy-DB");
    writerThread.setDaemon(true);
    return writerThread;
  });

  /**
   * Read cache, and only ever a READ cache: no mutation trusts it. Every local mutation writes
   * through, a peer's mutation arrives on the bus and calls {@link #invalidate(UUID)}, and the TTL is
   * the self-healing net for the bus message that never came.
   */
  private final ConcurrentHashMap<UUID, CachedBalance> cache = new ConcurrentHashMap<>();

  private volatile BalanceChangeListener balanceChangeListener = (accountId, accountName) -> { };

  public EconomyService(
      ConfigurationAdapter configuration,
      LoggerAdapter logger,
      Database database,
      CurrencyFormat format,
      String nodeId
  ) {
    this.configuration = configuration;
    this.logger = logger;
    this.database = database;
    this.format = format;
    this.nodeId = nodeId == null ? "" : nodeId;
    // Once per boot, on the writer thread. There is no scheduler here and a ledger sweep does not
    // need one: the table only grows on transactions, so a boot-time prune keeps it bounded on any
    // node that is ever restarted, which is all of them.
    writer.execute(this::pruneLedger);
  }

  public void setBalanceChangeListener(BalanceChangeListener listener) {
    this.balanceChangeListener = listener == null ? (accountId, accountName) -> { } : listener;
  }

  public CurrencyFormat format() {
    return format;
  }

  @Override
  public boolean enabled() {
    return database != null && configuration.getBoolean("economy.enabled", true);
  }

  // ----- reads -------------------------------------------------------------------------------------

  /**
   * The balance, from cache when it is fresh and from one indexed primary-key read when it is not.
   *
   * <p>An account with no row answers {@link #startingBalance()} rather than zero. The row is created
   * lazily (on join, or on the first mutation), and a brand-new player who has not been written yet
   * must not be shown a balance of nothing that then jumps to 100 a second later.</p>
   */
  @Override
  public Money balance(UUID playerId) {
    if (playerId == null || database == null) {
      return Money.ZERO;
    }
    long now = System.currentTimeMillis();
    CachedBalance cached = cache.get(playerId);
    if (cached != null && now - cached.readAt() < cacheTtlMillis()) {
      return Money.ofMinor(cached.minorUnits());
    }
    try {
      Long stored = readBalance(playerId.toString());
      long value = stored == null ? startingBalance().minorUnits() : stored;
      cache.put(playerId, new CachedBalance(value, now));
      return Money.ofMinor(value);
    } catch (SQLException exception) {
      logger.warning("Failed to read the balance of " + playerId, exception);
      // A stale answer beats a wrong one: the cache is not refreshed and not cleared, so the next
      // call retries. Never throws -- one of the callers is a HUD render tick.
      return cached == null ? startingBalance() : Money.ofMinor(cached.minorUnits());
    }
  }

  @Override
  public String formattedBalance(UUID playerId) {
    if (!enabled()) {
      return "";
    }
    return format.format(balance(playerId));
  }

  public EconomyAccount account(UUID accountId) {
    if (accountId == null || database == null) {
      return null;
    }
    return readAccount("account_id = ?", accountId.toString());
  }

  /** Case-insensitive, through {@code LOWER(name) = LOWER(?)} — portable on all three dialects. */
  public EconomyAccount accountByName(String name) {
    if (name == null || name.isBlank() || database == null) {
      return null;
    }
    return readAccount("LOWER(name) = LOWER(?)", name);
  }

  public boolean hasAccount(UUID accountId) {
    return account(accountId) != null;
  }

  /** The richest player accounts, balance descending. Shared accounts (if any) are excluded. */
  public List<EconomyAccount> top(int limit) {
    List<EconomyAccount> accounts = new ArrayList<>();
    if (database == null) {
      return accounts;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT account_id, name, is_player, balance, created_at, updated_at FROM economy_accounts"
              + " WHERE is_player = 1 ORDER BY balance DESC LIMIT ?")) {
        statement.setInt(1, Math.max(1, Math.min(100, limit)));
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            accounts.add(read(resultSet));
          }
        }
      } catch (SQLException exception) {
        logger.warning("Failed to read the money leaderboard", exception);
      }
    }
    return accounts;
  }

  /**
   * Every account id and name, capped at {@link #NAME_MAP_LIMIT}. Exists for Vault's
   * {@code getUUIDNameMap()}, which has no paging in its interface — see that constant.
   */
  public Map<UUID, String> nameMap() {
    Map<UUID, String> names = new LinkedHashMap<>();
    if (database == null) {
      return names;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT account_id, name FROM economy_accounts LIMIT ?")) {
        statement.setInt(1, NAME_MAP_LIMIT + 1);
        try (ResultSet resultSet = statement.executeQuery()) {
          while (resultSet.next()) {
            if (names.size() >= NAME_MAP_LIMIT) {
              logger.warning("A plugin asked Vault for the whole account/name map and this network has"
                  + " more than " + NAME_MAP_LIMIT + " accounts; the answer was truncated. That call has"
                  + " no paging in the Vault interface, so the alternative is a multi-second stall.");
              break;
            }
            try {
              names.put(UUID.fromString(resultSet.getString("account_id")), resultSet.getString("name"));
            } catch (IllegalArgumentException notAUuid) {
              // A non-uuid account id is a shared account this version does not create. Skipped
              // rather than fatal: one odd row must not cost the whole map.
            }
          }
        }
      } catch (SQLException exception) {
        logger.warning("Failed to read the account name map", exception);
      }
    }
    return names;
  }

  // ----- mutations ---------------------------------------------------------------------------------

  @Override
  public EconomyResult deposit(UUID playerId, Money amount, String reason) {
    return deposit(playerId, amount, reason, null);
  }

  public EconomyResult deposit(UUID playerId, Money amount, String reason, String actor) {
    if (amount == null || amount.isNegative()) {
      return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, "amount must not be negative");
    }
    return applyDelta(playerId, amount, amount.minorUnits(), OP_DEPOSIT, reason, actor);
  }

  @Override
  public EconomyResult withdraw(UUID playerId, Money amount, String reason) {
    return withdraw(playerId, amount, reason, null);
  }

  public EconomyResult withdraw(UUID playerId, Money amount, String reason, String actor) {
    if (amount == null || amount.isNegative()) {
      return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, "amount must not be negative");
    }
    return applyDelta(playerId, amount, -amount.minorUnits(), OP_WITHDRAW, reason, actor);
  }

  /** Overwrites the balance outright. One statement, so it is atomic against a concurrent transfer. */
  public EconomyResult set(UUID accountId, Money amount, String reason, String actor) {
    if (!enabled()) {
      return EconomyResult.failure(EconomyResult.Status.DISABLED, "economy is not available");
    }
    if (accountId == null) {
      return EconomyResult.failure(EconomyResult.Status.UNKNOWN_ACCOUNT, "no account");
    }
    if (amount == null || amount.isNegative()) {
      return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, "amount must not be negative");
    }
    String id = accountId.toString();
    long now = System.currentTimeMillis();
    long delta;
    String name;
    synchronized (database.lock()) {
      try {
        materialise(database.connection(), id, now);
        // Read the previous value inside the same lock, only so the ledger row can carry a SIGNED
        // delta like every other op does. A ledger where one operation records an absolute and the
        // rest record deltas cannot be summed, and summing it is the entire point.
        Settled previous = readSettled(id);
        delta = amount.minorUnits() - previous.minorUnits();
        name = previous.name();
        try (PreparedStatement statement = database.connection().prepareStatement(
            "UPDATE economy_accounts SET balance = ?, updated_at = ? WHERE account_id = ?")) {
          statement.setLong(1, amount.minorUnits());
          statement.setLong(2, now);
          statement.setString(3, id);
          statement.executeUpdate();
        }
      } catch (SQLException exception) {
        logger.warning("Failed to set the balance of " + id, exception);
        return EconomyResult.failure(EconomyResult.Status.ERROR, exception.getMessage());
      }
    }
    settle(accountId, amount.minorUnits(), name, now);
    recordLedger(id, null, OP_SET, delta, amount.minorUnits(), reason, actor);
    return EconomyResult.success(amount, amount);
  }

  /** Back to {@link #startingBalance()} — what a brand-new account would have had. */
  public EconomyResult reset(UUID accountId, String reason, String actor) {
    return set(accountId, startingBalance(), reason, actor);
  }

  /**
   * Moves money between two accounts, or moves none of it.
   *
   * <p>The ONLY place here that needs a real transaction, and it genuinely does: a debit that
   * committed while its matching credit failed is money destroyed, and the reverse is money created.
   * The debit is the conditional UPDATE above (so an overdrawing transfer never even starts), the
   * credit is unconditional, and a failure of either rolls both back.</p>
   *
   * <p>Deliberately NOT withdraw-then-deposit-with-a-compensating-re-deposit, which is what Vault's
   * own {@code transfer} default does: if the compensation also fails, that pattern has taken money
   * off one player and given it to nobody, and there is no third step that can fix it.</p>
   */
  public EconomyResult transfer(UUID from, UUID to, Money amount, String reason) {
    if (!enabled()) {
      return EconomyResult.failure(EconomyResult.Status.DISABLED, "economy is not available");
    }
    if (from == null || to == null) {
      return EconomyResult.failure(EconomyResult.Status.UNKNOWN_ACCOUNT, "no account");
    }
    if (amount == null || !amount.isPositive()) {
      return EconomyResult.failure(EconomyResult.Status.INVALID_AMOUNT, "amount must be positive");
    }
    String fromId = from.toString();
    String toId = to.toString();
    long now = System.currentTimeMillis();
    long fromAfter;
    long toAfter;
    String fromName;
    String toName;
    synchronized (database.lock()) {
      Connection connection = database.connection();
      boolean debited;
      try {
        materialise(connection, fromId, now);
        materialise(connection, toId, now);
        connection.setAutoCommit(false);
        try {
          debited = conditionalDelta(connection, fromId, -amount.minorUnits(), now) == 1;
          if (!debited) {
            connection.rollback();
          } else {
            try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE economy_accounts SET balance = balance + ?, updated_at = ? WHERE account_id = ?")) {
              statement.setLong(1, amount.minorUnits());
              statement.setLong(2, now);
              statement.setString(3, toId);
              statement.executeUpdate();
            }
            connection.commit();
          }
        } catch (SQLException failed) {
          connection.rollback();
          throw failed;
        } finally {
          // In a finally and never after the commit: an exception between commit() and here would
          // otherwise leave the ONE shared connection in manual-commit mode for every other
          // subsystem, and the next unrelated write would sit uncommitted until something else
          // happened to commit it.
          connection.setAutoCommit(true);
        }
        if (!debited) {
          Long current = readBalance(fromId);
          Money balance = current == null ? startingBalance() : Money.ofMinor(current);
          return EconomyResult.failure(EconomyResult.Status.INSUFFICIENT_FUNDS, amount, balance,
              "insufficient funds");
        }
        Settled settledFrom = readSettled(fromId);
        Settled settledTo = readSettled(toId);
        fromAfter = settledFrom.minorUnits();
        toAfter = settledTo.minorUnits();
        fromName = settledFrom.name();
        toName = settledTo.name();
      } catch (SQLException exception) {
        logger.warning("Failed to transfer money from " + fromId + " to " + toId, exception);
        return EconomyResult.failure(EconomyResult.Status.ERROR, exception.getMessage());
      }
    }
    settle(from, fromAfter, fromName, now);
    settle(to, toAfter, toName, now);
    recordLedger(fromId, toId, OP_TRANSFER_OUT, -amount.minorUnits(), fromAfter, reason, fromId);
    recordLedger(toId, fromId, OP_TRANSFER_IN, amount.minorUnits(), toAfter, reason, fromId);
    return EconomyResult.success(amount, Money.ofMinor(fromAfter));
  }

  /**
   * The shared body of deposit and withdraw: materialise the row, then one conditional UPDATE.
   *
   * <p>The failure is DIAGNOSED rather than assumed. A zero-row update means either the floor or the
   * ceiling refused, and telling a player "insufficient funds" when they actually hit
   * {@code limits.maximum-balance} sends them looking for money they already have. The extra read
   * only happens on the failure path.</p>
   */
  private EconomyResult applyDelta(UUID accountId, Money amount, long deltaMinor, String op,
                                   String reason, String actor) {
    if (!enabled()) {
      return EconomyResult.failure(EconomyResult.Status.DISABLED, "economy is not available");
    }
    if (accountId == null) {
      return EconomyResult.failure(EconomyResult.Status.UNKNOWN_ACCOUNT, "no account");
    }
    String id = accountId.toString();
    long now = System.currentTimeMillis();
    long after;
    String name;
    synchronized (database.lock()) {
      try {
        Connection connection = database.connection();
        materialise(connection, id, now);
        if (conditionalDelta(connection, id, deltaMinor, now) != 1) {
          Long current = readBalance(id);
          long balance = current == null ? startingBalance().minorUnits() : current;
          long ceiling = maximumBalance().minorUnits();
          boolean overCeiling = ceiling > 0L && balance + deltaMinor > ceiling;
          return EconomyResult.failure(
              overCeiling ? EconomyResult.Status.LIMIT_EXCEEDED : EconomyResult.Status.INSUFFICIENT_FUNDS,
              amount, Money.ofMinor(balance),
              overCeiling ? "maximum balance exceeded" : "insufficient funds");
        }
        Settled settled = readSettled(id);
        after = settled.minorUnits();
        name = settled.name();
      } catch (SQLException exception) {
        logger.warning("Failed to " + op + " on " + id, exception);
        return EconomyResult.failure(EconomyResult.Status.ERROR, exception.getMessage());
      }
    }
    settle(accountId, after, name, now);
    recordLedger(id, null, op, deltaMinor, after, reason, actor);
    return EconomyResult.success(amount, Money.ofMinor(after));
  }

  /**
   * The primitive. Returns the number of rows changed: 1 = it happened, 0 = a bound refused it.
   *
   * <p>{@code balance + ?} is evaluated by the database against the row it is locking, which is what
   * makes it a check and a write at the same instant. Binding the delta twice is not redundancy — the
   * SET and the WHERE need the same number, and a placeholder cannot be reused across clauses.</p>
   */
  private int conditionalDelta(Connection connection, String accountId, long deltaMinor, long now)
      throws SQLException {
    long ceiling = maximumBalance().minorUnits();
    String sql = "UPDATE economy_accounts SET balance = balance + ?, updated_at = ?"
        + " WHERE account_id = ? AND balance + ? >= ?"
        + (ceiling > 0L ? " AND balance + ? <= ?" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setLong(1, deltaMinor);
      statement.setLong(2, now);
      statement.setString(3, accountId);
      statement.setLong(4, deltaMinor);
      // The floor is zero and not Long.MIN_VALUE: an account is never allowed to go negative, and
      // "the withdrawal would overdraw" has to be a refusal rather than a debt.
      statement.setLong(5, 0L);
      if (ceiling > 0L) {
        statement.setLong(6, deltaMinor);
        statement.setLong(7, ceiling);
      }
      return statement.executeUpdate();
    }
  }

  // ----- account lifecycle -------------------------------------------------------------------------

  /**
   * Creates the row if it is missing, and refreshes the stored name when it is not.
   *
   * <p>The name refresh is what keeps {@code /baltop} and {@code /balance <name>} honest after a name
   * change, and it is why there is no UNIQUE index on {@code name}: names are not unique OVER TIME,
   * and a unique index on one would turn a perfectly ordinary rename into a boot-time SEVERE that
   * degrades the index for good (see {@code SchemaMigrator.createUniqueIndex}).</p>
   */
  public boolean ensureAccount(UUID accountId, String name, boolean player) {
    if (accountId == null || database == null) {
      return false;
    }
    long now = System.currentTimeMillis();
    // Through SqlDialect.upsert, whose update list is deliberately (name, updated_at) and NOTHING
    // else: on conflict the row's BALANCE must survive untouched. An upsert that re-listed `balance`
    // would reset every returning player to the starting amount on their first join of the day.
    String sql = database.dialect().upsert(
        "economy_accounts",
        new String[] {"account_id", "name", "is_player", "balance", "created_at", "updated_at"},
        new String[] {"account_id"},
        new String[] {"name", "updated_at"});
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(sql)) {
        statement.setString(1, accountId.toString());
        statement.setString(2, name == null || name.isBlank() ? accountId.toString() : name);
        statement.setLong(3, player ? 1L : 0L);
        statement.setLong(4, startingBalance().minorUnits());
        statement.setLong(5, now);
        statement.setLong(6, now);
        statement.executeUpdate();
        return true;
      } catch (SQLException exception) {
        logger.warning("Failed to create the money account for " + name, exception);
        return false;
      }
    }
  }

  /** The join path. Off the main thread because a first join must not wait on an INSERT. */
  public void ensureAccountAsync(UUID accountId, String name) {
    if (accountId == null || database == null || !enabled()) {
      return;
    }
    writer.execute(() -> ensureAccount(accountId, name, true));
  }

  public boolean renameAccount(UUID accountId, String name) {
    return ensureAccount(accountId, name, true);
  }

  public boolean deleteAccount(UUID accountId) {
    if (accountId == null || database == null) {
      return false;
    }
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "DELETE FROM economy_accounts WHERE account_id = ?")) {
        statement.setString(1, accountId.toString());
        boolean deleted = statement.executeUpdate() > 0;
        cache.remove(accountId);
        return deleted;
      } catch (SQLException exception) {
        logger.warning("Failed to delete the money account " + accountId, exception);
        return false;
      }
    }
  }

  /** Drop this node's cached balance for an account a PEER changed. The row stays the truth. */
  public void invalidate(UUID accountId) {
    if (accountId != null) {
      cache.remove(accountId);
    }
  }

  // ----- configuration -----------------------------------------------------------------------------

  public Money startingBalance() {
    return configuredMoney("economy.starting-balance", "100.00");
  }

  public Money payMinimum() {
    return configuredMoney("economy.pay.minimum", "0.01");
  }

  public Money payMaximum() {
    return configuredMoney("economy.pay.maximum", "1000000.00");
  }

  /** The balance ceiling, or {@link Money#ZERO} for unbounded. */
  public Money maximumBalance() {
    return configuredMoney("economy.limits.maximum-balance", "0.00");
  }

  public boolean payEnabled() {
    return configuration.getBoolean("economy.pay.enabled", true);
  }

  public boolean payAllowOffline() {
    return configuration.getBoolean("economy.pay.allow-offline", true);
  }

  public boolean paySelfAllowed() {
    return configuration.getBoolean("economy.pay.self", false);
  }

  public long payCooldownSeconds() {
    return Math.max(0L, configuration.getLong("economy.pay.cooldown-seconds", 0L));
  }

  public int baltopSize() {
    return Math.max(1, Math.min(100, configuration.getInt("economy.baltop.size", 10)));
  }

  public boolean ledgerEnabled() {
    return configuration.getBoolean("economy.ledger.enabled", true);
  }

  public int ledgerRetentionDays() {
    return Math.max(0, configuration.getInt("economy.ledger.retention-days", 30));
  }

  private long cacheTtlMillis() {
    return Math.max(0L, configuration.getLong("economy.cache-ttl-seconds", 10L)) * 1000L;
  }

  /**
   * Reads a money amount out of the config.
   *
   * <p>Through {@code getString} and {@code new BigDecimal(String)}, never {@code getDouble}: reading
   * {@code 0.01} as a double and multiplying by 100 gives {@code 1.0000000000000002} minor units,
   * which is exactly the float error the whole cents design exists to avoid. That is also why the
   * config literals are QUOTED — Bukkit's {@code getString} returns {@code value.toString()}, so an
   * unquoted {@code 100.00} arrives here as the string {@code "100.0"}.</p>
   */
  private Money configuredMoney(String path, String fallback) {
    String raw = configuration.getString(path, fallback);
    return Money.parse(raw).orElseGet(() -> {
      logger.warning("Config value " + path + " is not a valid amount (" + raw + "); using " + fallback);
      return Money.parse(fallback).orElse(Money.ZERO);
    });
  }

  public void reload() {
    format.reload();
    // The starting balance is what an account with no row reports, so a change to it has to reach
    // every cached "this player has no row yet" answer immediately.
    cache.clear();
  }

  // ----- writer lifecycle --------------------------------------------------------------------------

  /** Stop taking writes and WAIT for the queued ones. See {@link WriterQueues}. */
  public void shutdown() {
    WriterQueues.shutdown(writer, "economy", logger);
  }

  /** Wait for the queue without stopping the writer — the reversible half, used by a drain. */
  public void flushWrites() {
    WriterQueues.flush(writer, "economy", logger);
  }

  // ----- internals ---------------------------------------------------------------------------------

  /**
   * Cache write-through + the change notification, in one place so no mutation can forget half of it.
   *
   * <p>The name is PASSED IN rather than looked up. It is already sitting in the row the mutation just
   * read back, and going to the database for it again would make an ordinary deposit four statements
   * under the one global connection lock instead of three — on a path a minigame can hit per kill.</p>
   */
  private void settle(UUID accountId, long minorUnits, String name, long now) {
    cache.put(accountId, new CachedBalance(minorUnits, now));
    try {
      balanceChangeListener.onChange(accountId, name == null ? "" : name);
    } catch (RuntimeException listenerFailure) {
      logger.warning("Balance change listener failed for " + accountId, listenerFailure);
    }
  }

  /** The row as it stands after a mutation: what the balance settled at, and under what name. */
  private record Settled(long minorUnits, String name) {
  }

  /** One statement for both, because {@link #settle} needs both. Caller holds {@code database.lock()}. */
  private Settled readSettled(String accountId) throws SQLException {
    try (PreparedStatement statement = database.connection().prepareStatement(
        "SELECT balance, name FROM economy_accounts WHERE account_id = ?")) {
      statement.setString(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next()
            ? new Settled(resultSet.getLong("balance"), resultSet.getString("name"))
            : new Settled(0L, "");
      }
    }
  }

  /**
   * INSERT-if-absent, for the mutation paths, which know a uuid and not a name.
   *
   * <p>Insert-if-ABSENT and not an upsert, and the difference is the whole point: the conflict branch
   * must touch NO column at all here, least of all {@code balance}. The name falls back to the account
   * id when the caller does not know it (a Vault plugin depositing to a uuid it read out of its own
   * storage); the next join overwrites it with the real one through {@link #ensureAccount}.</p>
   *
   * <p>Spelled per dialect because there is no portable form: MySQL has {@code INSERT IGNORE} and no
   * {@code ON CONFLICT}, and MySQL also rejects the {@code SELECT ... WHERE NOT EXISTS} shape that
   * SQLite and Postgres accept ({@code WHERE} without {@code FROM} is a syntax error there).</p>
   */
  private void materialise(Connection connection, String accountId, long now) throws SQLException {
    String sql = database.dialect().isMysql()
        ? "INSERT IGNORE INTO economy_accounts(account_id, name, is_player, balance, created_at,"
            + " updated_at) VALUES(?, ?, ?, ?, ?, ?)"
        : "INSERT INTO economy_accounts(account_id, name, is_player, balance, created_at,"
            + " updated_at) VALUES(?, ?, ?, ?, ?, ?) ON CONFLICT(account_id) DO NOTHING";
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, accountId);
      statement.setString(2, accountId);
      statement.setLong(3, 1L);
      statement.setLong(4, startingBalance().minorUnits());
      statement.setLong(5, now);
      statement.setLong(6, now);
      statement.executeUpdate();
    }
  }

  /** The raw stored balance, or null when there is no row. Caller holds {@code database.lock()}. */
  private Long readBalance(String accountId) throws SQLException {
    try (PreparedStatement statement = database.connection().prepareStatement(
        "SELECT balance FROM economy_accounts WHERE account_id = ?")) {
      statement.setString(1, accountId);
      try (ResultSet resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getLong("balance") : null;
      }
    }
  }

  private EconomyAccount readAccount(String whereClause, String binding) {
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "SELECT account_id, name, is_player, balance, created_at, updated_at FROM economy_accounts"
              + " WHERE " + whereClause + " LIMIT 1")) {
        statement.setString(1, binding);
        try (ResultSet resultSet = statement.executeQuery()) {
          return resultSet.next() ? read(resultSet) : null;
        }
      } catch (SQLException exception) {
        logger.warning("Failed to read the money account " + binding, exception);
        return null;
      }
    }
  }

  private static EconomyAccount read(ResultSet resultSet) throws SQLException {
    return new EconomyAccount(
        resultSet.getString("account_id"),
        resultSet.getString("name"),
        resultSet.getLong("is_player") != 0L,
        Money.ofMinor(resultSet.getLong("balance")),
        resultSet.getLong("created_at"),
        resultSet.getLong("updated_at"));
  }

  /**
   * Queues one audit row. Signed {@code amount}, and {@code balance_after} is what the account
   * actually settled at — so "a player says their money vanished" is answerable with a SELECT, and a
   * gap in the chain is visible without recomputing anything.
   */
  private void recordLedger(String accountId, String counterparty, String op, long amount,
                            long balanceAfter, String reason, String actor) {
    if (!ledgerEnabled()) {
      return;
    }
    long now = System.currentTimeMillis();
    writer.execute(() -> {
      synchronized (database.lock()) {
        try (PreparedStatement statement = database.connection().prepareStatement(
            "INSERT INTO economy_ledger(account_id, counterparty, op, amount, balance_after,"
                + " reason, actor, node_id, created_at) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
          statement.setString(1, accountId);
          statement.setString(2, counterparty);
          statement.setString(3, op);
          statement.setLong(4, amount);
          statement.setLong(5, balanceAfter);
          statement.setString(6, reason);
          statement.setString(7, actor);
          statement.setString(8, nodeId);
          statement.setLong(9, now);
          statement.executeUpdate();
        } catch (SQLException exception) {
          // Warned and swallowed, deliberately: the money already moved. Failing here would be an
          // audit row taking down a payment that succeeded, which helps nobody.
          logger.warning("Failed to write the money ledger row for " + accountId, exception);
        }
      }
    });
  }

  /** Drops ledger rows older than {@code economy.ledger.retention-days}. 0 days keeps them forever. */
  public void pruneLedger() {
    int days = ledgerRetentionDays();
    if (database == null || days <= 0) {
      return;
    }
    long cutoff = System.currentTimeMillis() - days * 86_400_000L;
    synchronized (database.lock()) {
      try (PreparedStatement statement = database.connection().prepareStatement(
          "DELETE FROM economy_ledger WHERE created_at < ?")) {
        statement.setLong(1, cutoff);
        int removed = statement.executeUpdate();
        if (removed > 0) {
          logger.info("Pruned " + removed + " money ledger row(s) older than " + days + " day(s).");
        }
      } catch (SQLException exception) {
        logger.warning("Failed to prune the money ledger", exception);
      }
    }
  }
}
