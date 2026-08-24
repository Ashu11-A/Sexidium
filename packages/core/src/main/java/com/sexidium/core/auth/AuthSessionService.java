package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.auth.AuthResults.DecisionOutcome;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.auth.AuthResults.SessionView;
import com.sexidium.core.auth.AuthSessionRepository.SessionRow;
import com.sexidium.core.auth.premium.PremiumLookup;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.sexidium.core.platform.LoggerAdapter;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * The whole frictionless gate, in one decision matrix.
 *
 * <p>What it is for: a cracked player has no cryptographic identity, so anything they could type is a
 * password — and typing a password on every join is the friction this exists to delete. So nothing
 * here asks the player for a secret. Instead:</p>
 *
 * <ul>
 *   <li>a Mojang-verified connection is already proven and is simply let in;</li>
 *   <li>a network this name was approved from recently is remembered as a SESSION, keyed by
 *       {@code (identity, ip_hash)} — never by IP alone, because one Brazilian CGNAT address is
 *       shared by a whole neighbourhood;</li>
 *   <li>anything else opens ONE Discord approval and the player presses a button.</li>
 * </ul>
 *
 * <p>It never throws. A SQL failure on a login is a rejection with a readable screen, not a stack
 * trace on the proxy's Netty thread.</p>
 */
public final class AuthSessionService {

  /** What a backend needs to know to hold a player and to know when to stop. */
  public record HoldTicket(String requestId, String identityId, String state, long expiresAt) {

    public boolean decided() {
      return AuthRequestRepository.STATE_APPROVED.equals(state)
          || AuthRequestRepository.STATE_DENIED.equals(state);
    }

    public boolean approved() {
      return AuthRequestRepository.STATE_APPROVED.equals(state);
    }
  }

  /**
   * The in-memory signal that THIS gate just told a connection to hold.
   *
   * <p>Keyed by the connection uuid the platform handed the gate, which on a backend is the player
   * uuid at both {@code AsyncPlayerPreLoginEvent} and {@code PlayerJoinEvent} alike — so the join
   * handler finds it without a database read. That is the point: it makes "should I freeze this
   * player?" a question the join handler can answer fail-CLOSED (a SQL hiccup cannot turn a held
   * player loose), and it sidesteps the case-sensitive offline-uuid mismatch a proxy-less Paper
   * server would otherwise hit looking the identity up by player uuid. The durable truth stays in
   * {@code auth_requests.hold}; this is the low-latency, failure-proof copy of the same fact.</p>
   */
  public record HoldIntent(String identityId, String requestId, long expiresAt) {
  }

  /**
   * How the gate answers a linked player when NO approval can be delivered.
   *
   * <p>The normal path is strict; this is the degraded boundary only, and it deliberately defaults to
   * availability — a Discord outage must not lock a whole server out.</p>
   */
  private enum Fallback {
    ALLOW_LINKED,
    CODE,
    DENY;

    static Fallback parse(String raw) {
      return switch (raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)) {
        case "code" -> CODE;
        case "deny" -> DENY;
        default -> ALLOW_LINKED;
      };
    }
  }

  /** What the code-based {@code /auth <code>} path does when a player has no Discord link at all. */
  @FunctionalInterface
  public interface UnlinkedGate {
    GateOutcome onUnlinked(AuthConnection connection, AuthIdentity identity);
  }

  /** Told after every Approve/Deny, so the deciding node can release a held player and tell peers. */
  @FunctionalInterface
  public interface DecisionListener {
    void onDecided(RequestRow row, boolean approved);
  }

  private static final SecureRandom RANDOM = new SecureRandom();

  private final Database database;
  private final ConfigurationAdapter configuration;
  private final LoggerAdapter logger;
  private final MessageService messages;
  private final AuthService authService;
  private final PremiumLookup premium;
  private final String nodeId;
  private final BooleanSupplier botHostAlive;

  private final AuthIdentityRepository identityRepository;
  private final AuthSessionRepository sessionRepository;
  private final AuthRequestRepository requestRepository;
  private final AuthIpBlockRepository blockRepository;
  private final AuthRateLimiter rateLimiter = new AuthRateLimiter();
  /** See {@link HoldIntent}; cleared as each held player is released or quits. */
  private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, HoldIntent> holdIntents =
      new java.util.concurrent.ConcurrentHashMap<>();

  private volatile UnlinkedGate unlinkedGate;
  private volatile DecisionListener decisionListener;
  private volatile BooleanSupplier holdAvailable = () -> false;
  /**
   * Whether THIS node is the one that can force a client into Mojang's session handshake.
   *
   * <p>Only the proxy can — online-mode is per-connection there and nowhere else. It matters because
   * a backend sees every arrival as unverified (Paper is told nothing about how the proxy
   * authenticated them), so applying {@code protect-verified-names} on a backend would refuse every
   * premium player the proxy had just let in.</p>
   */
  private volatile boolean premiumGate;

  public AuthSessionService(
      Database database,
      ConfigurationAdapter configuration,
      LoggerAdapter logger,
      MessageService messages,
      AuthService authService,
      PremiumLookup premium,
      String nodeId,
      BooleanSupplier botHostAlive) {
    this.database = database;
    this.configuration = configuration;
    this.logger = logger;
    this.messages = messages;
    this.authService = authService;
    this.premium = premium;
    this.nodeId = nodeId;
    this.botHostAlive = botHostAlive == null ? () -> true : botHostAlive;
    this.identityRepository = new AuthIdentityRepository(database);
    this.sessionRepository = new AuthSessionRepository(database);
    this.requestRepository = new AuthRequestRepository(database);
    this.blockRepository = new AuthIpBlockRepository(database);
    refreshLimits();
  }

  // --- collaborators ------------------------------------------------------

  public AuthIdentityRepository identities() {
    return identityRepository;
  }

  public AuthSessionRepository sessions() {
    return sessionRepository;
  }

  public AuthRequestRepository requests() {
    return requestRepository;
  }

  public AuthIpBlockRepository blocks() {
    return blockRepository;
  }

  public AuthRateLimiter rateLimiter() {
    return rateLimiter;
  }

  public void setUnlinkedGate(UnlinkedGate unlinkedGate) {
    this.unlinkedGate = unlinkedGate;
  }

  public void setDecisionListener(DecisionListener decisionListener) {
    this.decisionListener = decisionListener;
  }

  /**
   * Whether an in-world hold is actually reachable right now.
   *
   * <p>Asked rather than assumed: the proxy answers "is the hold on AND is a lobby alive to perform
   * it", and when the answer is no the gate falls back to deny-with-a-reconnect, which needs nothing
   * but the disconnect screen.</p>
   */
  public void setHoldAvailable(BooleanSupplier holdAvailable) {
    this.holdAvailable = holdAvailable == null ? () -> false : holdAvailable;
  }

  /** Declare this node the premium gate. See {@link #premiumGate}; only the proxy may say true. */
  public void setPremiumGate(boolean premiumGate) {
    this.premiumGate = premiumGate;
  }

  // --- configuration ------------------------------------------------------

  /** Sessions are the switch for this whole path; with it off the legacy code gate is unchanged. */
  public boolean enabled() {
    return configuration.getBoolean("auth.session.enabled", false);
  }

  public boolean approvalsEnabled() {
    return configuration.getBoolean("auth.approval.enabled", false);
  }

  public boolean premiumEnabled() {
    return configuration.getBoolean("auth.premium.enabled", false);
  }

  public boolean holdEnabled() {
    return configuration.getBoolean("auth.hold.enabled", false);
  }

  public int sessionHours() {
    return (int) Math.max(1L, configuration.getLong("auth.session.ttl-hours", 18L));
  }

  public long requestTtlMillis() {
    return Math.max(30L, configuration.getLong("auth.approval.request-ttl-seconds", 300L)) * 1000L;
  }

  /** Re-read the rate-limit bounds. Called at construction and after a config reload. */
  public void refreshLimits() {
    rateLimiter.limits(
        (int) configuration.getLong("auth.session.max-logins-per-minute-per-ip", 10L),
        (int) configuration.getLong("auth.session.max-requests-per-hour-per-identity", 6L),
        (int) configuration.getLong("auth.session.max-requests-per-hour-per-ip", 20L));
  }

  // --- the gate -----------------------------------------------------------

  /** The canonical identity of a connecting name, created on first sight. Null only on failure. */
  public AuthIdentity identity(String username) {
    try {
      return identityRepository.resolveOrCreate(username, System.currentTimeMillis());
    } catch (SQLException failed) {
      logger.warning("Could not resolve the identity of " + username + ": " + failed.getMessage());
      return null;
    }
  }

  /**
   * The whole gate, in one call. Never throws.
   *
   * <p>Order matters and is not arbitrary: the cheap, unconditional refusals (rate limit, deny block)
   * come before anything that costs a Mojang call or a Discord message, and the name-protection check
   * runs before the session check so a squatter cannot ride a session onto a verified name.</p>
   */
  public GateOutcome authorize(AuthConnection connection) {
    long now = System.currentTimeMillis();
    try {
      AuthIdentity identity = identityRepository.resolveOrCreate(connection.username(), now);
      if (identity == null) {
        return GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_ERROR));
      }
      String ipHash = ipHash(connection.ip());
      String ipPrefix = IpHasher.redact(connection.ip());

      if (!rateLimiter.allowLogin(ipHash)) {
        return GateOutcome.reject(bilingual(MessageKey.AUTH_SESSION_RATE_LIMITED,
            MessageArg.text("seconds", rateLimiter.loginRetrySeconds(ipHash))));
      }
      if (blockRepository.blocked(identity.identityId(), ipHash, now)) {
        return GateOutcome.reject(bilingual(MessageKey.AUTH_SESSION_DENIED,
            MessageArg.text("hours", denyBlockHours())));
      }

      // A Mojang-verified connection has already proved who it is, cryptographically, to a party we
      // do not have to trust ourselves. Asking it for a Discord button on top is friction for nothing.
      if (connection.premiumVerified()) {
        return authorizePremium(connection, identity, ipHash, ipPrefix, now);
      }
      // Xbox Live authenticated a Bedrock player at the Geyser boundary, same argument. Off by
      // default because Geyser is not installed on the proxy; see auth.bedrock.* in config.yml.
      if (connection.bedrock() && configuration.getBoolean("auth.bedrock.enabled", false)
          && configuration.getBoolean("auth.bedrock.auto-login", true)) {
        mintSession(identity, ipHash, ipPrefix, "bedrock", "bedrock");
        return GateOutcome.allow();
      }
      // The anti-squat rule, and the reason the premium lookup runs even for a cracked-looking
      // client: once a name is known to belong to a Mojang account, an offline connection to it is
      // refused outright, whoever holds the Discord link.
      if (identity.premiumState() == PremiumState.PREMIUM
          && configuration.getBoolean("auth.premium.protect-verified-names", true)
          && premiumEnabled() && premiumGate) {
        return GateOutcome.reject(bilingual(MessageKey.AUTH_PREMIUM_REQUIRED,
            MessageArg.text("player", identity.displayName())));
      }

      // A live session for this exact network. The common path, and the one that costs nothing.
      //
      // Consulted BEFORE the Discord-link requirement, deliberately: a session is the receipt of a
      // recent approval, so demanding a link on top of it would re-introduce the very friction
      // sessions exist to delete. It is also what lets a premium/Bedrock player the proxy just
      // admitted reach a backend: the backend sees every arrival as unverified (it cannot do the
      // Mojang handshake itself), but the proxy minted this session on the way through, and reading
      // it back here is how the backend agrees.
      if (!strictIdentity(identity.nameLower())) {
        SessionRow session = sessionRepository.find(identity.identityId(), ipHash);
        if (session != null && session.live(now)) {
          sessionRepository.renew(identity.identityId(), ipHash, now, now + sessionTtlMillis());
          return GateOutcome.allow();
        }
      }

      // A name durably known to be premium. On a backend behind a proxy this is the proxy's word that
      // the connection was Mojang-verified (it records PREMIUM at GameProfileRequestEvent and refuses
      // offline impostors first); on a standalone node there is no protect-verified-names gate anyway,
      // so accepting it is no less strict than the rest of that deployment. Either way the player need
      // not hold a Discord link, which is the premium auto-login promise.
      if (identity.premiumState() == PremiumState.PREMIUM) {
        return GateOutcome.allow();
      }

      String linkedDiscordId = linkedDiscordId(identity);
      if (linkedDiscordId == null || linkedDiscordId.isBlank()) {
        return unlinked(connection, identity);
      }

      return openApproval(connection, identity, linkedDiscordId, ipHash, ipPrefix,
          AuthRequestRepository.KIND_SESSION, null, now);
    } catch (SQLException failed) {
      logger.warning("Auth gate failed for " + connection.username(), failed);
      return GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_ERROR));
    } catch (RuntimeException unexpected) {
      logger.warning("Auth gate failed for " + connection.username(), unexpected);
      return GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_ERROR));
    }
  }

  /**
   * A connection Mojang already vouched for.
   *
   * <p>The one case that is not simply "let them in" is a name a cracked player registered first: the
   * existing Discord owner is asked before the premium account inherits it, because on a server where
   * cracked players registered first, trusting Mojang silently would hand somebody's account away.</p>
   */
  private GateOutcome authorizePremium(AuthConnection connection, AuthIdentity identity,
      String ipHash, String ipPrefix, long now) throws SQLException {
    identityRepository.recordPremium(
        identity.nameLower(), connection.premiumUuid(), PremiumState.PREMIUM, now);

    String linkedDiscordId = linkedDiscordId(identity);
    boolean firstPremiumSighting = identity.premiumUuid() == null || identity.premiumUuid().isBlank();
    if (linkedDiscordId != null && !linkedDiscordId.isBlank() && firstPremiumSighting) {
      switch (conflictPolicy()) {
        case "deny" -> {
          return GateOutcome.reject(bilingual(MessageKey.AUTH_PREMIUM_REQUIRED,
              MessageArg.text("player", identity.displayName())));
        }
        case "adopt" -> {
          // Documented as unsafe in config.yml; honoured because an operator asked for it.
        }
        default -> {
          return openApproval(connection, identity, linkedDiscordId, ipHash, ipPrefix,
              AuthRequestRepository.KIND_PREMIUM_CONFLICT, connection.premiumUuid(), now);
        }
      }
    }
    boolean noLink = linkedDiscordId == null || linkedDiscordId.isBlank();
    if (noLink && !configuration.getBoolean("auth.premium.bypass-link", true)) {
      return unlinked(connection, identity);
    }
    mintSession(identity, ipHash, ipPrefix, "premium", "mojang");
    return GateOutcome.allow();
  }

  /** No Discord link at all: hand back to the legacy {@code /auth <code>} flow. */
  private GateOutcome unlinked(AuthConnection connection, AuthIdentity identity) {
    UnlinkedGate gate = unlinkedGate;
    return gate == null
        ? GateOutcome.reject(bilingual(MessageKey.AUTH_LOGIN_UNAVAILABLE))
        : gate.onUnlinked(connection, identity);
  }

  /**
   * Open (or reuse) one "is this you?" push and answer the connection accordingly.
   *
   * <p>Reuse is the anti-spam measure that matters: reconnecting ten times must produce one Discord
   * message with one request id, not ten of each.</p>
   */
  private GateOutcome openApproval(AuthConnection connection, AuthIdentity identity,
      String discordUserId, String ipHash, String ipPrefix, String kind, String detail, long now)
      throws SQLException {
    if (!approvalsEnabled() || !botHostAlive.getAsBoolean()) {
      return degraded(connection, identity);
    }
    RequestRow existing = requestRepository.findLivePending(identity.identityId(), ipHash, now);
    if (existing != null) {
      recordHoldIntent(connection, identity, existing);
      return answerFor(existing, kind, ipPrefix, now);
    }
    if (!rateLimiter.allowRequest(identity.identityId(), ipHash)) {
      // Refused WITHOUT sending anything: the whole point of the bound is that a stranger cannot
      // make somebody's phone buzz by reconnecting.
      return GateOutcome.reject(bilingual(MessageKey.AUTH_SESSION_RATE_LIMITED,
          MessageArg.text("seconds", 60)));
    }
    boolean hold = holdEnabled() && holdAvailable.getAsBoolean();
    RequestRow row = new RequestRow(
        randomId(), identity.identityId(), identity.nameLower(), identity.displayName(),
        discordUserId, ipHash, ipPrefix, kind, AuthRequestRepository.STATE_PENDING, hold,
        nodeId, null, 0L, null, null, null, 0,
        now, now + requestTtlMillis(), detail);
    requestRepository.insert(row);
    recordHoldIntent(connection, identity, row);
    return answerFor(row, kind, ipPrefix, now);
  }

  private GateOutcome answerFor(RequestRow row, String kind, String ipPrefix, long now) {
    long seconds = Math.max(1L, (row.expiresAt() - now) / 1000L);
    if (row.hold()) {
      return GateOutcome.hold(row.requestId(), row.expiresAt());
    }
    MessageKey key = AuthRequestRepository.KIND_PREMIUM_CONFLICT.equals(kind)
        ? MessageKey.AUTH_PREMIUM_CONFLICT
        : MessageKey.AUTH_SESSION_APPROVE_PENDING;
    return GateOutcome.pending(
        bilingual(key,
            MessageArg.text("seconds", seconds),
            MessageArg.text("network", ipPrefix == null || ipPrefix.isBlank() ? "?" : ipPrefix)),
        row.requestId(),
        row.expiresAt());
  }

  /** No approval can be delivered. See {@link Fallback}. */
  private GateOutcome degraded(AuthConnection connection, AuthIdentity identity) {
    return switch (Fallback.parse(configuration.getString("auth.session.fallback", "allow-linked"))) {
      case CODE -> unlinked(connection, identity);
      case DENY -> GateOutcome.reject(bilingual(MessageKey.AUTH_SESSION_NO_CHANNEL));
      case ALLOW_LINKED -> GateOutcome.allow();
    };
  }

  // --- decisions ----------------------------------------------------------

  /**
   * Apply one Discord Approve/Deny press.
   *
   * <p>Ownership is re-checked HERE, against the row, and never taken from the bot's word for it: a
   * customId is a client-visible string, so a forwarded message or a hand-crafted button id must be
   * refused by the server rather than by the UI.</p>
   */
  public DecisionOutcome decide(String requestId, String discordUserId, boolean approve) {
    if (!enabled()) {
      return DecisionOutcome.of(DecisionOutcome.Status.DISABLED, "");
    }
    long now = System.currentTimeMillis();
    try {
      RequestRow row = requestRepository.byId(requestId);
      if (row == null) {
        return DecisionOutcome.of(DecisionOutcome.Status.NOT_FOUND, "");
      }
      if (row.discordUserId() == null || !row.discordUserId().equals(discordUserId)) {
        return DecisionOutcome.of(DecisionOutcome.Status.FORBIDDEN, "");
      }
      if (row.expiresAt() <= now) {
        requestRepository.decide(requestId, AuthRequestRepository.STATE_EXPIRED, discordUserId, now);
        return DecisionOutcome.of(DecisionOutcome.Status.EXPIRED, row.displayName());
      }
      String state = approve ? AuthRequestRepository.STATE_APPROVED : AuthRequestRepository.STATE_DENIED;
      if (!requestRepository.decide(requestId, state, discordUserId, now)) {
        return DecisionOutcome.of(DecisionOutcome.Status.ALREADY_DECIDED, row.displayName());
      }

      AuthIdentity identity = identityRepository.find(row.nameLower());
      if (approve) {
        if (AuthRequestRepository.KIND_PREMIUM_CONFLICT.equals(row.kind()) && row.detail() != null) {
          identityRepository.recordPremium(row.nameLower(), row.detail(), PremiumState.PREMIUM, now);
        }
        if (identity != null) {
          mintSession(identity, row.ipHash(), row.ipPrefix(), "java", discordUserId);
        }
      } else {
        // Deny is not "not this time": it is "that was not me". Everything this identity has from
        // anywhere goes, and this network is shut out of it for a day.
        sessionRepository.revokeAll(row.identityId());
        blockRepository.block(row.identityId(), row.ipHash(), "denied", now,
            now + denyBlockHours() * 3_600_000L);
      }
      DecisionListener listener = decisionListener;
      if (listener != null) {
        listener.onDecided(row, approve);
      }
      return new DecisionOutcome(
          approve ? DecisionOutcome.Status.APPROVED : DecisionOutcome.Status.DENIED,
          row.displayName() == null ? "" : row.displayName(),
          approve ? sessionHours() : 0);
    } catch (SQLException failed) {
      logger.warning("Auth decision failed for request " + requestId, failed);
      return DecisionOutcome.of(DecisionOutcome.Status.NOT_FOUND, "");
    }
  }

  /** Mint or refresh the session that makes the NEXT login on this network cost nothing. */
  public void mintSession(AuthIdentity identity, String ipHash, String ipPrefix, String device,
      String approvedBy) {
    if (identity == null || ipHash == null) {
      return;
    }
    long now = System.currentTimeMillis();
    long absolute = now + Math.max(1L, configuration.getLong("auth.session.absolute-ttl-hours", 72L))
        * 3_600_000L;
    long expires = Math.min(now + sessionTtlMillis(), absolute);
    try {
      SessionRow existing = sessionRepository.find(identity.identityId(), ipHash);
      sessionRepository.upsert(new SessionRow(
          identity.identityId(), ipHash,
          existing == null ? randomId() : existing.sessionId(),
          identity.nameLower(), ipPrefix, device, nodeId, approvedBy,
          existing == null ? now : existing.createdAt(), now,
          expires,
          existing == null ? absolute : existing.absoluteExpiresAt(),
          null));
      sessionRepository.trimTo(identity.identityId(),
          (int) Math.max(1L, configuration.getLong("auth.session.max-per-identity", 5L)));
    } catch (SQLException failed) {
      // A session we could not store means one extra approval later, not a failed login now.
      logger.warning("Could not store an auth session for " + identity.nameLower()
          + ": " + failed.getMessage());
    }
  }

  // --- Discord-facing queries --------------------------------------------

  public List<SessionView> sessionsOf(String discordUserId) {
    List<SessionView> views = new ArrayList<>();
    try {
      for (SessionRow row : sessionRepository.byDiscordUser(discordUserId, System.currentTimeMillis())) {
        views.add(row.view());
      }
    } catch (SQLException failed) {
      logger.warning("Could not list auth sessions for " + discordUserId + ": " + failed.getMessage());
    }
    return views;
  }

  /** Revoke one session, or every session this Discord user owns when {@code sessionId} is blank. */
  public int revoke(String discordUserId, String sessionId) {
    try {
      if (sessionId == null || sessionId.isBlank()) {
        int revoked = 0;
        for (SessionRow row : sessionRepository.byDiscordUser(discordUserId, System.currentTimeMillis())) {
          revoked += sessionRepository.revokeOne(row.sessionId());
        }
        return revoked;
      }
      if (!sessionRepository.ownedBy(sessionId, discordUserId)) {
        return 0;
      }
      return sessionRepository.revokeOne(sessionId);
    } catch (SQLException failed) {
      logger.warning("Could not revoke auth sessions for " + discordUserId + ": " + failed.getMessage());
      return 0;
    }
  }

  /** The hold a backend should apply — or, once decided, release. */
  public Optional<HoldTicket> pendingHold(String identityId) {
    try {
      RequestRow row = requestRepository.pendingHoldFor(identityId, System.currentTimeMillis());
      return row == null
          ? Optional.empty()
          : Optional.of(new HoldTicket(row.requestId(), row.identityId(), row.state(), row.expiresAt()));
    } catch (SQLException failed) {
      logger.warning("Could not read the pending hold for " + identityId + ": " + failed.getMessage());
      return Optional.empty();
    }
  }

  /**
   * The in-memory hold intent this gate recorded for a connection, if any.
   *
   * <p>No database: a backend's join handler calls this FIRST, so a held player is frozen even when
   * the shared database is momentarily unreachable (the fail-closed answer), and the row-based
   * {@link #pendingHold} read after it is the corroborating belt-and-braces rather than the load-
   * bearing path.</p>
   */
  public Optional<HoldIntent> holdIntent(java.util.UUID connectionUuid) {
    return connectionUuid == null
        ? Optional.empty()
        : Optional.ofNullable(holdIntents.get(connectionUuid));
  }

  /** Drop the intent for a connection once the player is released or has quit. */
  public void forgetHold(java.util.UUID connectionUuid) {
    if (connectionUuid != null) {
      holdIntents.remove(connectionUuid);
    }
  }

  private void recordHoldIntent(AuthConnection connection, AuthIdentity identity, RequestRow row) {
    if (row.hold() && connection != null && connection.connectionUuid() != null) {
      holdIntents.put(connection.connectionUuid(),
          new HoldIntent(identity.identityId(), row.requestId(), row.expiresAt()));
    }
  }

  /** Mark a decided request as acted upon, so a released player is not released twice. */
  public void consumeRequest(String requestId) {
    try {
      requestRepository.consume(requestId, System.currentTimeMillis());
    } catch (SQLException failed) {
      logger.warning("Could not consume auth request " + requestId + ": " + failed.getMessage());
    }
  }

  // --- helpers ------------------------------------------------------------

  /** The hash a session is keyed by. See {@link IpHasher} for what is bound and what is stored. */
  public String ipHash(String ip) {
    int prefix = (int) configuration.getLong("auth.session.bind-ipv6-prefix",
        IpHasher.DEFAULT_V6_PREFIX_BITS);
    return IpHasher.hash(IpHasher.normalize(ip, prefix), pepper());
  }

  /**
   * The salt on the stored IP hashes.
   *
   * <p>Derived from {@code api.token} when unset rather than left empty: an empty pepper would make
   * the hashes comparable across every deployment that ever leaked one, which is exactly the property
   * a pepper exists to remove.</p>
   */
  private String pepper() {
    String configured = configuration.getString("auth.session.ip-pepper", "");
    if (configured != null && !configured.isBlank()) {
      return configured;
    }
    String token = configuration.getString("api.token", "");
    return "sexidium:" + (token == null ? "" : token);
  }

  private long sessionTtlMillis() {
    return sessionHours() * 3_600_000L;
  }

  private long denyBlockHours() {
    return Math.max(1L, configuration.getLong("auth.approval.deny-block-hours", 24L));
  }

  private String conflictPolicy() {
    String raw = configuration.getString("auth.premium.on-conflict", "require-approval");
    return raw == null ? "require-approval" : raw.trim().toLowerCase(Locale.ROOT);
  }

  /** Names that never get an IP session and always require a fresh Discord approval. */
  private boolean strictIdentity(String nameLower) {
    for (String entry : configuration.getStringList("auth.session.strict-identities")) {
      if (entry != null && entry.trim().toLowerCase(Locale.ROOT).equals(nameLower)) {
        return true;
      }
    }
    return false;
  }

  private String linkedDiscordId(AuthIdentity identity) throws SQLException {
    return authService == null ? null : authService.linkedDiscordId(identity.identityId());
  }

  /** 128 bits of {@link SecureRandom}: guessing a request id is not a strategy. */
  private static String randomId() {
    byte[] bytes = new byte[16];
    RANDOM.nextBytes(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private String bilingual(MessageKey key, MessageArg... args) {
    return AuthMessages.bilingual(messages, key, args);
  }

  /** Visible for the sweeper and for tests: the shared connection every repository above holds. */
  public Database database() {
    return database;
  }

  /** Visible for the gate wiring: the premium lookup this service was built with, if any. */
  public PremiumLookup premiumLookup() {
    return premium;
  }
}
