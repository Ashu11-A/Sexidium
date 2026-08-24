package com.sexidium.velocity.auth;

import com.sexidium.core.auth.AuthConnection;
import com.sexidium.core.auth.AuthIdentity;
import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.auth.AuthLoginService;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.auth.AuthSessionService;
import com.sexidium.core.auth.premium.PremiumLookup;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.platform.ConfigurationAdapter;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent.PreLoginComponentResult;
import com.velocitypowered.api.event.player.GameProfileRequestEvent;
import com.velocitypowered.api.proxy.InboundConnection;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole proxy-side login gate, as one registered listener.
 *
 * <p>Three events, in the order Velocity fires them:</p>
 *
 * <ol>
 *   <li>{@code PreLoginEvent} — decide whether the client must prove itself to Mojang. This is the
 *       ONLY place premium auto-login can happen: online-mode is per-connection here and nowhere
 *       else, so a Paper backend cannot do it at all.</li>
 *   <li>{@code GameProfileRequestEvent} — rewrite the uuid to the canonical identity. Cracked,
 *       premium and Bedrock alike, so the same human is the same row in every table however they
 *       connected. Skin properties survive, because {@code withId} keeps them.</li>
 *   <li>{@code LoginEvent} — run the actual gate (session / approval / code) and allow or deny.</li>
 * </ol>
 *
 * <p>Running here rather than on each backend is the point: a refused player never touches a
 * backend at all. Backends run {@code online-mode=false} under modern forwarding, so their own gate
 * is the last line rather than the first.</p>
 */
public final class VelocityAuthGate {

  private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

  /** What PreLogin learned, carried forward to the two events that cannot re-derive it. */
  private record PendingContext(String username, String ip, boolean bedrock, boolean forcedOnline,
      String premiumUuid) {
  }

  private final ProxyServer proxy;
  private final Logger logger;
  private final ConfigurationAdapter configuration;
  private final MessageService messages;
  private final AuthLoginService loginService;
  private final AuthSessionService sessionService;
  private final PremiumLookup premium;

  /**
   * Per-connection scratch, keyed by remote address + lowercase name.
   *
   * <p>Not by uuid: at {@code PreLoginEvent} the uuid is not settled yet, and the whole point of
   * this class is that it changes at the next event. Evicted on {@code DisconnectEvent}, and every
   * reader tolerates a miss — a lost entry costs a re-derivation, never a wrong decision.</p>
   */
  private final Map<String, PendingContext> pending = new ConcurrentHashMap<>();

  public VelocityAuthGate(ProxyServer proxy, Logger logger, ConfigurationAdapter configuration,
      MessageService messages, AuthLoginService loginService, AuthSessionService sessionService,
      PremiumLookup premium) {
    this.proxy = proxy;
    this.logger = logger;
    this.configuration = configuration;
    this.messages = messages;
    this.loginService = loginService;
    this.sessionService = sessionService;
    this.premium = premium;
  }

  // --- 1. premium / bedrock decision -------------------------------------

  /**
   * {@link EventTask#async} because the plan may cost a Mojang round trip and a database read, and
   * neither belongs on a Netty thread. {@code PostOrder.FIRST} so nothing else has allowed the
   * connection first.
   */
  @Subscribe(order = PostOrder.FIRST)
  public EventTask onPreLogin(PreLoginEvent event) {
    return EventTask.async(() -> {
      try {
        event.setResult(resolve(event));
      } catch (RuntimeException failed) {
        // A gate that throws here would leave Velocity's default (allow) standing, which is the one
        // outcome that must never happen by accident on a name we could not check.
        logger.warn("Pre-login check failed for {}; refusing", event.getUsername(), failed);
        event.setResult(PreLoginComponentResult.denied(
            MINI_MESSAGE.deserialize(bilingual(com.sexidium.core.i18n.MessageKey.AUTH_LOGIN_ERROR))));
      }
    });
  }

  private PreLoginComponentResult resolve(PreLoginEvent event) {
    String username = event.getUsername();
    String ip = addressOf(event.getConnection());
    boolean bedrock = looksBedrock(username);
    boolean premiumEnabled = configuration.getBoolean("auth.premium.enabled", false);

    // Rate-limit BEFORE any expensive work. Pre-login is the one place an unauthenticated stranger
    // can make this gate do two costly things for free — insert a player_identities row and burn a
    // Mojang lookup — and neither is bounded without this. The same per-IP window the login gate
    // uses is consulted here so a single address cannot flood identities or drain the Mojang token
    // bucket; a rate-limited connection is refused before a row is written or a request is made.
    if (sessionService != null
        && !sessionService.rateLimiter().allowLogin(sessionService.ipHash(ip))) {
      return PreLoginComponentResult.denied(MINI_MESSAGE.deserialize(
          bilingual(com.sexidium.core.i18n.MessageKey.AUTH_SESSION_RATE_LIMITED,
              com.sexidium.core.i18n.MessageArg.text(
                  "seconds", sessionService.rateLimiter().loginRetrySeconds(sessionService.ipHash(ip))))));
    }

    AuthIdentity identity = sessionService == null ? null : sessionService.identity(username);
    boolean knownPremium = identity != null && identity.premiumState() == PremiumState.PREMIUM;

    PremiumLookup.Verdict verdict = null;
    if (premiumEnabled && premium != null && !bedrock) {
      verdict = premium.lookup(username);
    }
    PreLoginPlanner.Plan plan = PreLoginPlanner.plan(username, bedrock, knownPremium, verdict,
        PreLoginPlanner.Settings.of(
            premiumEnabled,
            configuration.getBoolean("auth.premium.protect-verified-names", true),
            configuration.getBoolean("auth.bedrock.enabled", false),
            configuration.getString("auth.premium.unknown-on-outage", "deny")));

    pending.put(key(ip, username), new PendingContext(
        username, ip, bedrock, plan == PreLoginPlanner.Plan.ONLINE,
        verdict == null ? null : verdict.premiumUuid()));

    return switch (plan) {
      case ONLINE -> PreLoginComponentResult.forceOnlineMode();
      case OFFLINE -> PreLoginComponentResult.forceOfflineMode();
      case ALLOW -> PreLoginComponentResult.allowed();
      case DENY -> PreLoginComponentResult.denied(MINI_MESSAGE.deserialize(
          bilingual(com.sexidium.core.i18n.MessageKey.AUTH_PREMIUM_UNAVAILABLE)));
    };
  }

  // --- 2. canonical identity ---------------------------------------------

  /**
   * Pin every connection to its canonical uuid.
   *
   * <p>Without this, enabling premium auto-login makes the same human arrive as a stranger: a Mojang
   * uuid matches nothing in {@code players}, {@code discord_accounts}, {@code experiences},
   * {@code friends} or any of the other seven tables keyed by player uuid. It also collapses the
   * case-aliasing bug, where {@code Foo} and {@code foo} were two accounts with two point balances.</p>
   */
  @Subscribe(order = PostOrder.FIRST)
  public void onGameProfileRequest(GameProfileRequestEvent event) {
    if (sessionService == null) {
      return;
    }
    String username = event.getUsername();
    try {
      AuthIdentity identity = sessionService.identity(username);
      if (identity == null) {
        return;
      }
      if (event.isOnlineMode()) {
        // Mojang just proved this name. Record its uuid durably, so a later outage has an answer and
        // an offline connection to the same name can be refused.
        sessionService.identities().recordPremium(
            identity.nameLower(), event.getOriginalProfile().getUndashedId(),
            PremiumState.PREMIUM, System.currentTimeMillis());
        String ip = addressOf(event.getConnection());
        pending.computeIfPresent(key(ip, username), (ignored, context) -> new PendingContext(
            context.username(), context.ip(), context.bedrock(), true,
            event.getOriginalProfile().getUndashedId()));
      }
      UUID identityUuid = identity.identityUuid();
      if (identityUuid != null && !identityUuid.equals(event.getGameProfile().getId())) {
        // withId keeps the profile's properties, so a premium player keeps their real skin while
        // carrying our canonical uuid downstream.
        event.setGameProfile(event.getGameProfile().withId(identityUuid));
      }
    } catch (Exception failed) {
      // A profile we could not canonicalise is a profile that arrives as itself. The LoginEvent gate
      // below still runs, so this degrades to "no identity anchor", never to "no gate".
      logger.warn("Could not canonicalise the game profile for {}", username, failed);
    }
  }

  // --- 3. the gate --------------------------------------------------------

  /**
   * Refuse an unauthorised player before they touch a backend.
   *
   * <p>When the gate is disabled — or no database is configured — this is a no-op and every login
   * proceeds, which is the standalone behaviour.</p>
   */
  @Subscribe(order = PostOrder.FIRST)
  public void onLogin(LoginEvent event) {
    if (loginService == null) {
      return;
    }
    String username = event.getPlayer().getUsername();
    String ip = addressOf(event.getPlayer().getRemoteAddress());
    PendingContext context = pending.get(key(ip, username));
    AuthConnection connection = new AuthConnection(
        username,
        event.getPlayer().getUniqueId(),
        ip,
        event.getPlayer().getVirtualHost().map(InetSocketAddress::getHostString).orElse(null),
        event.getPlayer().getProtocolVersion().getProtocol(),
        context != null && context.bedrock(),
        context != null && context.forcedOnline(),
        context == null ? null : context.premiumUuid());

    GateOutcome outcome = loginService.verify(connection);
    if (outcome.allowed()) {
      return;
    }
    logger.info("Refused login for {} ({})", username, outcome.kind());
    event.setResult(ResultedEvent.ComponentResult.denied(
        MINI_MESSAGE.deserialize(outcome.rejectionMessage())));
  }

  @Subscribe
  public void onDisconnect(DisconnectEvent event) {
    pending.remove(key(addressOf(event.getPlayer().getRemoteAddress()),
        event.getPlayer().getUsername()));
  }

  // --- helpers ------------------------------------------------------------

  /**
   * Whether this looks like a Bedrock connection.
   *
   * <p>Name-prefix only. Floodgate is not installed on the proxy in this deployment, so there is no
   * API to ask and pretending otherwise would be a reflective probe that always answers false. The
   * prefix is also what makes a Bedrock name incapable of colliding with a Java one, which is why
   * {@code protect-verified-names} does not apply to them.</p>
   */
  private boolean looksBedrock(String username) {
    if (!configuration.getBoolean("auth.bedrock.enabled", false) || username == null) {
      return false;
    }
    String prefix = configuration.getString("auth.bedrock.name-prefix", ".");
    return prefix != null && !prefix.isEmpty() && username.startsWith(prefix);
  }

  private static String addressOf(InboundConnection connection) {
    return connection == null ? null : addressOf(connection.getRemoteAddress());
  }

  private static String addressOf(InetSocketAddress address) {
    if (address == null || address.getAddress() == null) {
      return address == null ? null : address.getHostString();
    }
    return address.getAddress().getHostAddress();
  }

  private static String key(String ip, String username) {
    return (ip == null ? "" : ip) + "|"
        + (username == null ? "" : username.toLowerCase(Locale.ROOT));
  }

  private String bilingual(com.sexidium.core.i18n.MessageKey key) {
    return com.sexidium.core.auth.AuthMessages.bilingual(messages, key);
  }

  private String bilingual(com.sexidium.core.i18n.MessageKey key,
      com.sexidium.core.i18n.MessageArg... args) {
    return com.sexidium.core.auth.AuthMessages.bilingual(messages, key, args);
  }

  /** The proxy this gate is registered on; kept so a future release can message a held player. */
  public ProxyServer proxy() {
    return proxy;
  }
}
