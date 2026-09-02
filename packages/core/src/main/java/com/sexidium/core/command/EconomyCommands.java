package com.sexidium.core.command;

import com.sexidium.core.economy.EconomyAccount;
import com.sexidium.core.economy.EconomyResult;
import com.sexidium.core.economy.EconomyService;
import com.sexidium.core.economy.Money;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.CommandSource;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.sexidium.core.command.CommandText.arg;
import static com.sexidium.core.command.CommandText.equalsAny;
import static com.sexidium.core.command.CommandText.lower;

/**
 * Handles {@code /pay}, {@code /balance}, {@code /baltop} and {@code /sx admin eco <…>}.
 *
 * <p>The admin verbs live under {@code /sx admin} and not at the root, per rule 3 of
 * {@code docs/guides/add-a-command.md}: a new top-level command is a name taken from every other
 * plugin on the server forever, and {@code /eco} is one of the most contested names there is.</p>
 *
 * <p>Two things every branch here does, and both have bitten this repo before:</p>
 *
 * <ul>
 *   <li><b>Player names are echoed through {@code MessageArg.text}</b>, which escapes MiniMessage. A
 *       name is attacker-controlled text on a cracked server, and pasting one into a message that is
 *       then parsed as MiniMessage is how a player writes colours — or a click event — into somebody
 *       else's chat.</li>
 *   <li><b>Amounts go through {@link Money#parse}</b>, never {@code Double.parseDouble}. A bad amount
 *       is an {@code ECONOMY_INVALID_AMOUNT} message, never a {@code NumberFormatException} escaping
 *       into the command dispatcher.</li>
 * </ul>
 */
final class EconomyCommands {

  static final String PAY_PERMISSION = "sexidium.economy.pay";
  static final String BALANCE_OTHERS_PERMISSION = "sexidium.economy.balance.others";

  private final CommandContext ctx;

  /**
   * When each player last paid somebody. Node-local and in-memory on purpose: the player is on
   * exactly one node, and a cross-node cooldown would be a database write on a chat command.
   */
  private final ConcurrentHashMap<UUID, Long> lastPayAt = new ConcurrentHashMap<>();

  EconomyCommands(CommandContext ctx) {
    this.ctx = ctx;
  }

  // ----- /pay --------------------------------------------------------------------------------------

  /** {@code args = [pay, <player>, <amount>]}. */
  void handlePay(CommandSource source, String[] args) {
    EconomyService economy = available(source);
    if (economy == null) {
      return;
    }
    // Player-only, and checked before the argument count: a console operator who types /pay should be
    // told they cannot pay from console, not shown a usage line they can never satisfy.
    PlayerAdapter player = ctx.requirePlayer(source);
    if (player == null) {
      return;
    }
    if (!source.hasPermission(PAY_PERMISSION)) {
      ctx.send(source, MessageKey.COMMAND_NO_PERMISSION);
      return;
    }
    if (!economy.payEnabled()) {
      ctx.send(source, MessageKey.ECONOMY_PAY_DISABLED);
      return;
    }
    if (args.length < 3) {
      ctx.send(source, MessageKey.ECONOMY_PAY_USAGE);
      return;
    }
    String targetName = args[1];
    Optional<Money> parsed = Money.parse(args[2]);
    if (parsed.isEmpty()) {
      ctx.send(source, MessageKey.ECONOMY_INVALID_AMOUNT, MessageArg.text("amount", args[2]));
      return;
    }
    Money amount = parsed.get();
    if (!amount.isPositive()) {
      ctx.send(source, MessageKey.ECONOMY_AMOUNT_NOT_POSITIVE);
      return;
    }
    if (amount.compareTo(economy.payMinimum()) < 0) {
      ctx.send(source, MessageKey.ECONOMY_PAY_MINIMUM,
          MessageArg.text("amount", economy.format().format(economy.payMinimum())));
      return;
    }
    Money maximum = economy.payMaximum();
    if (maximum.isPositive() && amount.compareTo(maximum) > 0) {
      ctx.send(source, MessageKey.ECONOMY_PAY_MAXIMUM,
          MessageArg.text("amount", economy.format().format(maximum)));
      return;
    }

    UUID targetId = resolvePayTarget(source, economy, targetName);
    if (targetId == null) {
      return;
    }
    if (targetId.equals(player.uniqueId()) && !economy.paySelfAllowed()) {
      ctx.send(source, MessageKey.ECONOMY_PAY_SELF);
      return;
    }
    long remaining = cooldownRemaining(economy, player.uniqueId());
    if (remaining > 0L) {
      ctx.send(source, MessageKey.ECONOMY_PAY_COOLDOWN, MessageArg.text("seconds", remaining));
      return;
    }

    EconomyResult result = economy.transfer(player.uniqueId(), targetId, amount, "pay");
    if (!result.ok()) {
      if (result.status() == EconomyResult.Status.INSUFFICIENT_FUNDS) {
        ctx.send(source, MessageKey.ECONOMY_INSUFFICIENT_FUNDS,
            MessageArg.text("balance", economy.format().format(result.balance())));
      } else if (result.status() == EconomyResult.Status.LIMIT_EXCEEDED) {
        ctx.send(source, MessageKey.ECONOMY_LIMIT_EXCEEDED);
      } else {
        ctx.send(source, MessageKey.ECONOMY_PAY_FAILED);
      }
      return;
    }
    // Recorded only on a payment that actually happened: a refusal must not start a cooldown, or a
    // player who mistypes a name is locked out of paying for the next minute.
    lastPayAt.put(player.uniqueId(), System.currentTimeMillis());

    String displayName = ctx.server.player(targetId).map(PlayerAdapter::name).orElse(targetName);
    ctx.send(source, MessageKey.ECONOMY_PAY_SENT,
        MessageArg.text("amount", economy.format().format(amount)),
        MessageArg.text("player", displayName),
        MessageArg.text("balance", economy.format().format(result.balance())));
    ctx.server.player(targetId).ifPresent(target -> ctx.send(target, MessageKey.ECONOMY_PAY_RECEIVED,
        MessageArg.text("amount", economy.format().format(amount)),
        MessageArg.text("player", player.name())));
  }

  /**
   * Online first, then the account table when {@code economy.pay.allow-offline} is on.
   *
   * <p>The order matters: an online player is the one the payer means, and their uuid is authoritative
   * where a stored name may be a stale spelling of somebody who has since renamed.</p>
   */
  private UUID resolvePayTarget(CommandSource source, EconomyService economy, String targetName) {
    Optional<PlayerAdapter> online = ctx.server.playerExact(targetName);
    if (online.isPresent()) {
      return online.get().uniqueId();
    }
    if (economy.payAllowOffline()) {
      EconomyAccount account = economy.accountByName(targetName);
      if (account != null) {
        try {
          return UUID.fromString(account.accountId());
        } catch (IllegalArgumentException notAUuid) {
          // A shared account this version does not create. Treated as "no such player" rather than
          // as an error, because that is what it is from the payer's point of view.
          ctx.send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", targetName));
          return null;
        }
      }
    }
    ctx.send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", targetName));
    return null;
  }

  private long cooldownRemaining(EconomyService economy, UUID playerId) {
    long cooldownSeconds = economy.payCooldownSeconds();
    if (cooldownSeconds <= 0L) {
      return 0L;
    }
    Long last = lastPayAt.get(playerId);
    if (last == null) {
      return 0L;
    }
    long elapsed = System.currentTimeMillis() - last;
    // A clock that stepped backwards would otherwise hold the player in cooldown for the difference.
    if (elapsed < 0L) {
      return 0L;
    }
    long remaining = cooldownSeconds - elapsed / 1000L;
    return Math.max(0L, remaining);
  }

  // ----- /balance ----------------------------------------------------------------------------------

  /** {@code args = [balance, [player]]}. Works from console for the {@code [player]} form. */
  void handleBalance(CommandSource source, String[] args) {
    EconomyService economy = available(source);
    if (economy == null) {
      return;
    }
    String targetName = arg(args, 1);
    if (targetName == null || targetName.isBlank()) {
      PlayerAdapter player = ctx.requirePlayer(source);
      if (player == null) {
        return;
      }
      ctx.send(source, MessageKey.ECONOMY_BALANCE_SELF,
          MessageArg.text("balance", economy.format().format(economy.balance(player.uniqueId()))));
      return;
    }
    if (!source.hasPermission(BALANCE_OTHERS_PERMISSION)) {
      ctx.send(source, MessageKey.COMMAND_NO_PERMISSION);
      return;
    }
    Optional<PlayerAdapter> online = ctx.server.playerExact(targetName);
    if (online.isPresent()) {
      ctx.send(source, MessageKey.ECONOMY_BALANCE_OTHER,
          MessageArg.text("player", online.get().name()),
          MessageArg.text("balance", economy.format().format(economy.balance(online.get().uniqueId()))));
      return;
    }
    EconomyAccount account = economy.accountByName(targetName);
    if (account == null) {
      ctx.send(source, MessageKey.ECONOMY_BALANCE_UNKNOWN, MessageArg.text("player", targetName));
      return;
    }
    ctx.send(source, MessageKey.ECONOMY_BALANCE_OTHER,
        MessageArg.text("player", account.name()),
        MessageArg.text("balance", economy.format().format(account.balance())));
  }

  // ----- /baltop -----------------------------------------------------------------------------------

  /** {@code args = [baltop, [page]]}. */
  void handleBaltop(CommandSource source, String[] args) {
    EconomyService economy = available(source);
    if (economy == null) {
      return;
    }
    int size = economy.baltopSize();
    int page = Math.max(1, CommandText.parseIntOr(arg(args, 1) == null ? "" : args[1], 1));
    // Read one page's worth beyond the offset, then slice: `top` is a single indexed ORDER BY with a
    // LIMIT, so asking for page*size rows is one query rather than one per page.
    List<EconomyAccount> accounts = economy.top(page * size);
    int from = (page - 1) * size;
    if (from >= accounts.size()) {
      ctx.send(source, MessageKey.ECONOMY_BALTOP_EMPTY);
      return;
    }
    ctx.send(source, MessageKey.ECONOMY_BALTOP_TITLE);
    for (int index = from; index < accounts.size(); index++) {
      EconomyAccount account = accounts.get(index);
      ctx.send(source, MessageKey.ECONOMY_BALTOP_ROW,
          MessageArg.text("rank", index + 1),
          MessageArg.text("player", account.name()),
          MessageArg.text("balance", economy.format().format(account.balance())));
    }
  }

  // ----- /sx admin eco -----------------------------------------------------------------------------

  /** {@code args = [eco, <verb>, …]} — already resliced by {@link AdminCommands}. */
  void handleAdmin(CommandSource source, String[] args) {
    EconomyService economy = available(source);
    if (economy == null) {
      return;
    }
    String verb = lower(arg(args, 1));
    switch (verb) {
      case "give", "take", "set" -> adminAdjust(source, economy, verb, args);
      case "reset" -> adminReset(source, economy, args);
      case "balance", "bal" -> adminBalance(source, economy, args);
      case "top" -> adminTop(source, economy, args);
      default -> ctx.send(source, MessageKey.ECONOMY_ADMIN_USAGE);
    }
  }

  private void adminAdjust(CommandSource source, EconomyService economy, String verb, String[] args) {
    if (args.length < 4) {
      ctx.send(source, MessageKey.ECONOMY_ADMIN_USAGE);
      return;
    }
    Optional<Money> parsed = Money.parse(args[3]);
    if (parsed.isEmpty()) {
      ctx.send(source, MessageKey.ECONOMY_INVALID_AMOUNT, MessageArg.text("amount", args[3]));
      return;
    }
    Money amount = parsed.get();
    if (amount.isNegative() || (amount.isZero() && !"set".equals(verb))) {
      ctx.send(source, MessageKey.ECONOMY_AMOUNT_NOT_POSITIVE);
      return;
    }
    Target target = resolveTarget(source, economy, args[2]);
    if (target == null) {
      return;
    }
    String actor = source.name();
    EconomyResult result = switch (verb) {
      case "give" -> economy.deposit(target.id(), amount, "admin-give", actor);
      case "take" -> economy.withdraw(target.id(), amount, "admin-take", actor);
      default -> economy.set(target.id(), amount, "admin-set", actor);
    };
    if (!result.ok()) {
      reportFailure(source, economy, result);
      return;
    }
    MessageKey confirmation = switch (verb) {
      case "give" -> MessageKey.ECONOMY_ADMIN_GIVEN;
      case "take" -> MessageKey.ECONOMY_ADMIN_TAKEN;
      default -> MessageKey.ECONOMY_ADMIN_SET;
    };
    ctx.send(source, confirmation,
        MessageArg.text("player", target.name()),
        MessageArg.text("amount", economy.format().format(amount)),
        MessageArg.text("balance", economy.format().format(result.balance())));
    // The player is told too, and only when they are here to be told. Money changing with no
    // explanation is the single most reported "bug" any economy gets.
    MessageKey notice = switch (verb) {
      case "give" -> MessageKey.ECONOMY_ADMIN_NOTIFY_GIVEN;
      case "take" -> MessageKey.ECONOMY_ADMIN_NOTIFY_TAKEN;
      default -> MessageKey.ECONOMY_ADMIN_NOTIFY_SET;
    };
    ctx.server.player(target.id()).ifPresent(player -> ctx.send(player, notice,
        MessageArg.text("amount", economy.format().format(amount)),
        MessageArg.text("balance", economy.format().format(result.balance()))));
  }

  private void adminReset(CommandSource source, EconomyService economy, String[] args) {
    if (args.length < 3) {
      ctx.send(source, MessageKey.ECONOMY_ADMIN_USAGE);
      return;
    }
    Target target = resolveTarget(source, economy, args[2]);
    if (target == null) {
      return;
    }
    EconomyResult result = economy.reset(target.id(), "admin-reset", source.name());
    if (!result.ok()) {
      reportFailure(source, economy, result);
      return;
    }
    ctx.send(source, MessageKey.ECONOMY_ADMIN_RESET,
        MessageArg.text("player", target.name()),
        MessageArg.text("balance", economy.format().format(result.balance())));
    ctx.server.player(target.id()).ifPresent(player -> ctx.send(player,
        MessageKey.ECONOMY_ADMIN_NOTIFY_SET,
        MessageArg.text("amount", economy.format().format(result.balance())),
        MessageArg.text("balance", economy.format().format(result.balance()))));
  }

  /** {@code /sx admin eco top [n]} — the same leaderboard, with an explicit row count. */
  private void adminTop(CommandSource source, EconomyService economy, String[] args) {
    int limit = CommandText.parseIntOr(arg(args, 2) == null ? "" : args[2], economy.baltopSize());
    List<EconomyAccount> accounts = economy.top(Math.max(1, limit));
    if (accounts.isEmpty()) {
      ctx.send(source, MessageKey.ECONOMY_BALTOP_EMPTY);
      return;
    }
    ctx.send(source, MessageKey.ECONOMY_BALTOP_TITLE);
    for (int index = 0; index < accounts.size(); index++) {
      EconomyAccount account = accounts.get(index);
      ctx.send(source, MessageKey.ECONOMY_BALTOP_ROW,
          MessageArg.text("rank", index + 1),
          MessageArg.text("player", account.name()),
          MessageArg.text("balance", economy.format().format(account.balance())));
    }
  }

  private void adminBalance(CommandSource source, EconomyService economy, String[] args) {
    if (args.length < 3) {
      ctx.send(source, MessageKey.ECONOMY_ADMIN_USAGE);
      return;
    }
    Target target = resolveTarget(source, economy, args[2]);
    if (target == null) {
      return;
    }
    ctx.send(source, MessageKey.ECONOMY_BALANCE_OTHER,
        MessageArg.text("player", target.name()),
        MessageArg.text("balance", economy.format().format(economy.balance(target.id()))));
  }

  private void reportFailure(CommandSource source, EconomyService economy, EconomyResult result) {
    switch (result.status()) {
      case INSUFFICIENT_FUNDS -> ctx.send(source, MessageKey.ECONOMY_INSUFFICIENT_FUNDS,
          MessageArg.text("balance", economy.format().format(result.balance())));
      case LIMIT_EXCEEDED -> ctx.send(source, MessageKey.ECONOMY_LIMIT_EXCEEDED);
      case DISABLED -> ctx.send(source, MessageKey.ECONOMY_DISABLED);
      default -> ctx.send(source, MessageKey.ECONOMY_PAY_FAILED);
    }
  }

  private record Target(UUID id, String name) {
  }

  /** Online player, then stored account. Admin verbs must work on somebody who is not here. */
  private Target resolveTarget(CommandSource source, EconomyService economy, String name) {
    Optional<PlayerAdapter> online = ctx.server.playerExact(name);
    if (online.isPresent()) {
      return new Target(online.get().uniqueId(), online.get().name());
    }
    EconomyAccount account = economy.accountByName(name);
    if (account != null) {
      try {
        return new Target(UUID.fromString(account.accountId()), account.name());
      } catch (IllegalArgumentException notAUuid) {
        ctx.send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", name));
        return null;
      }
    }
    ctx.send(source, MessageKey.COMMAND_PLAYER_NOT_FOUND, MessageArg.text("player", name));
    return null;
  }

  /**
   * The economy, or null with the reason already sent.
   *
   * <p>Two different answers on purpose: no service at all means there is no database on this node
   * ({@code ECONOMY_UNAVAILABLE}), while a service that reports disabled means the operator turned it
   * off ({@code ECONOMY_DISABLED}). Collapsing them sends an operator looking at their database
   * because they set a boolean.</p>
   */
  private EconomyService available(CommandSource source) {
    EconomyService economy = ctx.core.economy();
    if (economy == null) {
      ctx.send(source, MessageKey.ECONOMY_UNAVAILABLE);
      return null;
    }
    if (!economy.enabled()) {
      ctx.send(source, MessageKey.ECONOMY_DISABLED);
      return null;
    }
    return economy;
  }

  // ----- suggestions -------------------------------------------------------------------------------

  /**
   * Every branch below has a completion, because a branch without one is a bug rather than a missing
   * nicety (see {@code docs/guides/add-a-command.md}) — a player who cannot tab a name types it, and
   * a typed name is the one that goes to the wrong person.
   */
  private static final List<String> AMOUNTS = List.of("10", "100", "1000");
  private static final List<String> ADMIN_VERBS =
      List.of("give", "take", "set", "reset", "balance", "top");

  List<String> suggestPay(String[] args) {
    if (args.length == 2) {
      return ctx.filter(ctx.playerNames(), args[1]);
    }
    if (args.length == 3) {
      return ctx.filter(AMOUNTS, args[2]);
    }
    return List.of();
  }

  /** {@code /baltop [page]} — a page number is still a branch, so it still gets candidates. */
  List<String> suggestBaltop(String[] args) {
    return args.length == 2 ? ctx.filter(List.of("1", "2", "3"), args[1]) : List.of();
  }

  List<String> suggestBalance(CommandSource source, String[] args) {
    if (args.length == 2 && source != null && source.hasPermission(BALANCE_OTHERS_PERMISSION)) {
      return ctx.filter(ctx.playerNames(), args[1]);
    }
    // Without the permission the name argument is refused anyway; completing names there would only
    // advertise who is online to somebody who may not be allowed to know.
    return List.of();
  }

  /** {@code args = [eco, <verb>, <player>, <amount>]}. */
  List<String> suggestAdmin(String[] args) {
    if (args.length == 2) {
      return ctx.filter(ADMIN_VERBS, args[1]);
    }
    if (args.length == 3) {
      return equalsAny(args[1], "top") ? ctx.filter(List.of("10", "25"), args[2])
          : ctx.filter(ctx.playerNames(), args[2]);
    }
    if (args.length == 4 && equalsAny(args[1], "give", "take", "set")) {
      return ctx.filter(AMOUNTS, args[3]);
    }
    return List.of();
  }
}
