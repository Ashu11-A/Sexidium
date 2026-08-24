package com.sexidium.core.auth;

import com.sexidium.core.auth.AuthIdentity.PremiumState;
import com.sexidium.core.auth.AuthRequestRepository.RequestRow;
import com.sexidium.core.auth.AuthResults.DecisionOutcome;
import com.sexidium.core.auth.AuthResults.GateOutcome;
import com.sexidium.core.auth.AuthResults.SessionView;
import com.sexidium.core.auth.AuthSessionRepository.SessionRow;
import com.sexidium.core.auth.premium.PremiumLookup;
import com.sexidium.core.i18n.MessageService;
import com.sexidium.core.lib.data.Database;
import com.sexidium.core.platform.noop.ClassLoaderResourceAdapter;
import com.sexidium.core.platform.noop.PropertiesConfigurationAdapter;
import com.sexidium.core.platform.noop.StdoutLoggerAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decision matrix. This is the class that decides who gets in, so every branch of
 * {@link AuthSessionService#authorize} has a case here, and the ones that could hand a name away are
 * asserted for direction rather than merely for behaviour.
 */
class AuthSessionServiceTest {

  private Database db;
  private PropertiesConfigurationAdapter config;
  private AuthService authService;
  private AuthSessionService service;
  private boolean botHostAlive;

  @BeforeEach
  void setUp() throws Exception {
    Path dir = Files.createTempDirectory("auth-gate-test");
    db = new Database(dir.resolve("gate.db").toFile());
    config = new PropertiesConfigurationAdapter();
    config.set("auth.session.enabled", "true");
    config.set("auth.approval.enabled", "true");
    config.set("auth.session.ip-pepper", "test-pepper");
    authService = new AuthService(db, () -> true);
    botHostAlive = true;
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    messages.reload();
    service = new AuthSessionService(db, config, new StdoutLoggerAdapter("T"), messages,
        authService, null, "node-1", () -> botHostAlive);
    // The code flow, as the gate would hand it back: a player with no Discord link at all.
    service.setUnlinkedGate((connection, identity) -> GateOutcome.reject("run /auth in Discord"));
  }

  @AfterEach
  void tearDown() {
    db.close();
  }

  // --- the gate -----------------------------------------------------------

  @Test
  @DisplayName("a player with no Discord link falls back to the code flow, not to a session")
  void unlinkedFallsBackToTheCodeFlow() {
    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));

    assertFalse(outcome.allowed());
    assertEquals("run /auth in Discord", outcome.rejectionMessage());
  }

  @Test
  @DisplayName("a linked player from an unknown network is asked on Discord, and told so")
  void linkedFromANewNetworkOpensAnApproval() throws Exception {
    link("Steve", "discord-1");

    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));

    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL, outcome.kind());
    assertNotNull(outcome.requestId());
    assertTrue(outcome.rejectionMessage().contains("Approve"));
    assertNotNull(service.requests().byId(outcome.requestId()));
  }

  @Test
  @DisplayName("reconnecting reuses the one open request, so one player means one Discord message")
  void repeatedConnectsReuseTheRequest() throws Exception {
    link("Steve", "discord-1");

    String first = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    String second = service.authorize(connection("Steve", "187.61.4.9")).requestId();

    assertEquals(first, second);
  }

  @Test
  @DisplayName("a live session lets the player straight in, which is the whole point")
  void aLiveSessionAllows() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", true);

    assertTrue(service.authorize(connection("Steve", "187.61.4.9")).allowed());
  }

  @Test
  @DisplayName("a session is bound to the network, so the same name from elsewhere is asked again")
  void aSessionDoesNotFollowTheNameAcrossNetworks() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", true);

    GateOutcome elsewhere = service.authorize(connection("Steve", "200.1.2.3"));
    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL, elsewhere.kind());
  }

  @Test
  @DisplayName("an expired session is not a session")
  void anExpiredSessionIsNotHonoured() throws Exception {
    link("Steve", "discord-1");
    AuthIdentity identity = service.identity("Steve");
    String ipHash = service.ipHash("187.61.4.9");
    service.sessions().upsert(new SessionRow(identity.identityId(), ipHash, "s1", "steve", null,
        "java", "node-1", "discord-1", 0L, 0L, 1L, 1L, null));

    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL,
        service.authorize(connection("Steve", "187.61.4.9")).kind());
  }

  @Test
  @DisplayName("a strict identity never rides a session and always re-approves")
  void strictIdentitiesAlwaysReApprove() throws Exception {
    link("Staff", "discord-1");
    config.set("auth.session.strict-identities", "staff,admin");
    String requestId = service.authorize(connection("Staff", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", true);

    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL,
        service.authorize(connection("Staff", "187.61.4.9")).kind());
  }

  @Test
  @DisplayName("a denied network is refused outright, before anything else is considered")
  void aBlockedNetworkIsRefused() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", false);

    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));
    assertEquals(GateOutcome.Kind.REJECT, outcome.kind());
    assertTrue(outcome.rejectionMessage().contains("denied"));
  }

  @Test
  @DisplayName("a login flood is refused without opening a request or sending anything")
  void loginFloodIsRateLimited() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.session.max-logins-per-minute-per-ip", "2");
    service.refreshLimits();

    service.authorize(connection("Steve", "187.61.4.9"));
    service.authorize(connection("Steve", "187.61.4.9"));
    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));

    assertEquals(GateOutcome.Kind.REJECT, outcome.kind());
    assertTrue(outcome.rejectionMessage().contains("Too many"));
  }

  @Test
  @DisplayName("approval spam is bounded, and beyond the bound NO Discord message is opened")
  void approvalSpamIsBounded() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.session.max-requests-per-hour-per-identity", "1");
    service.refreshLimits();

    // One request per network; the second network is over the per-identity bound.
    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL,
        service.authorize(connection("Steve", "187.61.4.9")).kind());
    GateOutcome second = service.authorize(connection("Steve", "200.1.2.3"));

    assertEquals(GateOutcome.Kind.REJECT, second.kind());
    assertEquals(1, service.requests().countSince("identity_id", service.identity("Steve").identityId(), 0L),
        "the bound exists so a stranger cannot make somebody's phone buzz by reconnecting");
  }

  @Test
  @DisplayName("with no bot host alive the gate degrades to today's behaviour, not to a lockout")
  void noBotHostFallsBackToAllowLinked() throws Exception {
    link("Steve", "discord-1");
    botHostAlive = false;

    assertTrue(service.authorize(connection("Steve", "187.61.4.9")).allowed(),
        "a Discord outage must not lock a whole server out");
  }

  @Test
  @DisplayName("fallback: code sends a linked player back through the /auth flow")
  void fallbackCode() throws Exception {
    link("Steve", "discord-1");
    botHostAlive = false;
    config.set("auth.session.fallback", "code");

    assertEquals("run /auth in Discord",
        service.authorize(connection("Steve", "187.61.4.9")).rejectionMessage());
  }

  @Test
  @DisplayName("fallback: deny refuses, and says why in a way a player can act on")
  void fallbackDeny() throws Exception {
    link("Steve", "discord-1");
    botHostAlive = false;
    config.set("auth.session.fallback", "deny");

    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));
    assertFalse(outcome.allowed());
    assertTrue(outcome.rejectionMessage().contains("Discord"));
  }

  @Test
  @DisplayName("approvals switched off degrades the same way as a dead bot host")
  void approvalsDisabledDegrades() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.approval.enabled", "false");

    assertTrue(service.authorize(connection("Steve", "187.61.4.9")).allowed());
  }

  @Test
  @DisplayName("a Mojang-verified connection is simply let in and gets a premium session")
  void premiumVerifiedIsAllowed() throws Exception {
    GateOutcome outcome = service.authorize(premium("Notch", "187.61.4.9", "069a79f4"));

    assertTrue(outcome.allowed());
    AuthIdentity identity = service.identity("Notch");
    assertEquals(PremiumState.PREMIUM, identity.premiumState());
    assertEquals("premium",
        service.sessions().find(identity.identityId(), service.ipHash("187.61.4.9")).device());
  }

  @Test
  @DisplayName("a premium arrival on a name a cracked player already linked asks the OWNER first")
  void premiumConflictAsksTheExistingOwner() throws Exception {
    link("Notch", "discord-1");

    GateOutcome outcome = service.authorize(premium("Notch", "200.1.2.3", "069a79f4"));

    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL, outcome.kind());
    RequestRow request = service.requests().byId(outcome.requestId());
    assertEquals(AuthRequestRepository.KIND_PREMIUM_CONFLICT, request.kind());
    assertEquals("discord-1", request.discordUserId());
    assertEquals("069a79f4", request.detail());
  }

  @Test
  @DisplayName("on-conflict: deny refuses the premium login and keeps the name where it is")
  void premiumConflictDeny() throws Exception {
    link("Notch", "discord-1");
    config.set("auth.premium.on-conflict", "deny");

    GateOutcome outcome = service.authorize(premium("Notch", "200.1.2.3", "069a79f4"));
    assertEquals(GateOutcome.Kind.REJECT, outcome.kind());
    assertTrue(outcome.rejectionMessage().contains("Mojang"));
  }

  @Test
  @DisplayName("on-conflict: adopt trusts Mojang over the existing link, as documented")
  void premiumConflictAdopt() throws Exception {
    link("Notch", "discord-1");
    config.set("auth.premium.on-conflict", "adopt");

    assertTrue(service.authorize(premium("Notch", "200.1.2.3", "069a79f4")).allowed());
  }

  @Test
  @DisplayName("a second premium login is no longer a conflict, because the uuid is already recorded")
  void premiumConflictOnlyFiresOnce() throws Exception {
    link("Notch", "discord-1");
    String requestId = service.authorize(premium("Notch", "200.1.2.3", "069a79f4")).requestId();
    service.decide(requestId, "discord-1", true);

    assertTrue(service.authorize(premium("Notch", "200.1.2.3", "069a79f4")).allowed());
  }

  @Test
  @DisplayName("bypass-link off makes an unlinked premium player go through the code flow")
  void premiumWithoutBypassStillNeedsALink() {
    config.set("auth.premium.bypass-link", "false");

    assertEquals("run /auth in Discord",
        service.authorize(premium("Notch", "187.61.4.9", "069a79f4")).rejectionMessage());
  }

  @Test
  @DisplayName("an OFFLINE connection to a verified name is refused — the anti-squat rule")
  void protectVerifiedNames() throws Exception {
    config.set("auth.premium.enabled", "true");
    service.setPremiumGate(true);
    service.identity("Notch");
    service.identities().recordPremium("notch", "069a79f4", PremiumState.PREMIUM, 1L);

    GateOutcome outcome = service.authorize(connection("Notch", "200.1.2.3"));

    assertEquals(GateOutcome.Kind.REJECT, outcome.kind());
    assertTrue(outcome.rejectionMessage().contains("launcher"));
  }

  @Test
  @DisplayName("a BACKEND never enforces that rule, or it would refuse the players the proxy admitted")
  void protectVerifiedNamesIsProxyOnly() throws Exception {
    config.set("auth.premium.enabled", "true");
    link("Notch", "discord-1");
    service.identities().recordPremium("notch", "069a79f4", PremiumState.PREMIUM, 1L);

    // premiumGate defaults to false: Paper cannot verify anybody, so every arrival looks unverified.
    assertNotEquals(GateOutcome.Kind.REJECT, service.authorize(connection("Notch", "200.1.2.3")).kind());
  }

  @Test
  @DisplayName("Bedrock auto-login is off by default, and works when it is on")
  void bedrockAutoLogin() throws Exception {
    AuthConnection bedrock = new AuthConnection(".Phone", UUID.randomUUID(), "187.61.4.9",
        null, 0, true, false, null);

    assertFalse(service.authorize(bedrock).allowed(), "off by default: Geyser is not installed");

    config.set("auth.bedrock.enabled", "true");
    assertTrue(service.authorize(bedrock).allowed());
    assertEquals("bedrock", service.sessions()
        .find(service.identity(".Phone").identityId(), service.ipHash("187.61.4.9")).device());
  }

  @Test
  @DisplayName("a hold is offered instead of a kick when a node can actually perform one")
  void holdIsOfferedWhenAvailable() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.hold.enabled", "true");
    service.setHoldAvailable(() -> true);

    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));

    assertEquals(GateOutcome.Kind.HOLD, outcome.kind());
    assertTrue(service.requests().byId(outcome.requestId()).hold());
  }

  @Test
  @DisplayName("with no node able to hold, the gate falls back to deny-with-a-reconnect")
  void holdFallsBackWhenUnavailable() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.hold.enabled", "true");
    service.setHoldAvailable(() -> false);

    assertEquals(GateOutcome.Kind.REJECT_PENDING_APPROVAL,
        service.authorize(connection("Steve", "187.61.4.9")).kind());
  }

  @Test
  @DisplayName("a database that will not answer is a readable screen, not a stack trace on Netty")
  void aSqlFailureIsARejection() {
    db.close();

    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));
    assertEquals(GateOutcome.Kind.REJECT, outcome.kind());
    assertFalse(outcome.rejectionMessage().isBlank());
  }

  @Test
  @DisplayName("a nameless connection is refused rather than resolved to a shared empty identity")
  void aNamelessConnectionIsRefused() {
    assertFalse(service.authorize(connection("  ", "187.61.4.9")).allowed());
  }

  @Test
  @DisplayName("with no unlinked gate installed the answer is 'unavailable', never 'allowed'")
  void aMissingUnlinkedGateFailsClosed() {
    service.setUnlinkedGate(null);
    GateOutcome outcome = service.authorize(connection("Steve", "187.61.4.9"));
    assertFalse(outcome.allowed());
  }

  // --- decisions ----------------------------------------------------------

  @Test
  @DisplayName("approving mints a session with the configured TTL and reports it")
  void approveMintsASession() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.session.ttl-hours", "6");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();

    DecisionOutcome outcome = service.decide(requestId, "discord-1", true);

    assertEquals(DecisionOutcome.Status.APPROVED, outcome.status());
    assertEquals("Steve", outcome.minecraftName());
    assertEquals(6, outcome.sessionHours());
    assertNotNull(service.sessions()
        .find(service.identity("Steve").identityId(), service.ipHash("187.61.4.9")));
  }

  @Test
  @DisplayName("denying revokes every session of that account and blocks the network")
  void denyRevokesEverything() throws Exception {
    link("Steve", "discord-1");
    String first = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(first, "discord-1", true);
    String second = service.authorize(connection("Steve", "200.1.2.3")).requestId();

    DecisionOutcome outcome = service.decide(second, "discord-1", false);

    assertEquals(DecisionOutcome.Status.DENIED, outcome.status());
    assertTrue(service.sessions().byIdentity(service.identity("Steve").identityId()).isEmpty(),
        "Deny means 'that was not me', so everything this account has from anywhere goes");
    assertTrue(service.blocks().blocked(service.identity("Steve").identityId(),
        service.ipHash("200.1.2.3"), System.currentTimeMillis()));
  }

  @Test
  @DisplayName("a forwarded message or a hand-crafted button id is refused BY THE SERVER")
  void ownershipIsCheckedServerSide() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();

    assertEquals(DecisionOutcome.Status.FORBIDDEN,
        service.decide(requestId, "someone-else", true).status());
    assertEquals(AuthRequestRepository.STATE_PENDING, service.requests().byId(requestId).state());
  }

  @Test
  @DisplayName("pressing the same button twice is 'already decided', not a second approval")
  void replayIsRefused() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();

    assertEquals(DecisionOutcome.Status.APPROVED, service.decide(requestId, "discord-1", true).status());
    assertEquals(DecisionOutcome.Status.ALREADY_DECIDED,
        service.decide(requestId, "discord-1", false).status());
  }

  @Test
  @DisplayName("a request that timed out cannot be approved after the fact")
  void expiredRequestsCannotBeApproved() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.approval.request-ttl-seconds", "30");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    expire(requestId);

    assertEquals(DecisionOutcome.Status.EXPIRED, service.decide(requestId, "discord-1", true).status());
  }

  @Test
  @DisplayName("an unknown request id is 'not found' rather than an error the bot has to guess at")
  void unknownRequestIsNotFound() {
    assertEquals(DecisionOutcome.Status.NOT_FOUND, service.decide("nope", "discord-1", true).status());
  }

  @Test
  @DisplayName("with sessions off the whole decision path reports disabled")
  void decisionsAreDisabledWithSessionsOff() {
    config.set("auth.session.enabled", "false");
    assertEquals(DecisionOutcome.Status.DISABLED, service.decide("req", "discord-1", true).status());
  }

  @Test
  @DisplayName("a decision tells the node holding the player, so a hold can be released in place")
  void decisionsNotifyTheListener() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    StringBuilder seen = new StringBuilder();
    service.setDecisionListener((row, approved) -> seen.append(row.identityId()).append(approved));

    service.decide(requestId, "discord-1", true);

    assertEquals(service.identity("Steve").identityId() + "true", seen.toString());
  }

  @Test
  @DisplayName("a decision on a closed database is reported, not thrown at the bot")
  void aFailedDecisionIsReported() {
    db.close();
    assertEquals(DecisionOutcome.Status.NOT_FOUND, service.decide("req", "discord-1", true).status());
  }

  // --- Discord-facing reads ----------------------------------------------

  @Test
  @DisplayName("a Discord user sees their own live sessions and nobody else's")
  void sessionsOf() throws Exception {
    link("Steve", "discord-1");
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", true);

    List<SessionView> mine = service.sessionsOf("discord-1");
    assertEquals(1, mine.size());
    assertEquals("steve", mine.get(0).minecraftName());
    assertEquals("187.61.*.*", mine.get(0).ipPrefix());
    assertTrue(service.sessionsOf("discord-2").isEmpty());
  }

  @Test
  @DisplayName("revoking without an id takes every session the user owns")
  void revokeAll() throws Exception {
    link("Steve", "discord-1");
    service.decide(service.authorize(connection("Steve", "187.61.4.9")).requestId(), "discord-1", true);
    service.decide(service.authorize(connection("Steve", "200.1.2.3")).requestId(), "discord-1", true);

    assertEquals(2, service.revoke("discord-1", null));
    assertTrue(service.sessionsOf("discord-1").isEmpty());
  }

  @Test
  @DisplayName("revoking somebody else's session revokes nothing")
  void revokeChecksOwnership() throws Exception {
    link("Steve", "discord-1");
    service.decide(service.authorize(connection("Steve", "187.61.4.9")).requestId(), "discord-1", true);
    String sessionId = service.sessionsOf("discord-1").get(0).sessionId();

    assertEquals(0, service.revoke("discord-2", sessionId));
    assertEquals(1, service.revoke("discord-1", sessionId));
  }

  @Test
  @DisplayName("Discord-facing reads on a closed database answer empty rather than throwing")
  void discordReadsDegradeQuietly() {
    db.close();
    assertTrue(service.sessionsOf("discord-1").isEmpty());
    assertEquals(0, service.revoke("discord-1", "s1"));
    assertTrue(service.pendingHold("id-1").isEmpty());
    assertNull(service.identity("Steve"));
    assertDoesNotThrow(() -> service.consumeRequest("req"));
  }

  // --- holds --------------------------------------------------------------

  @Test
  @DisplayName("the hold ticket carries the state a backend releases or kicks on")
  void pendingHold() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.hold.enabled", "true");
    service.setHoldAvailable(() -> true);
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    String identityId = service.identity("Steve").identityId();

    AuthSessionService.HoldTicket pending = service.pendingHold(identityId).orElseThrow();
    assertEquals(requestId, pending.requestId());
    assertFalse(pending.decided());

    service.decide(requestId, "discord-1", true);
    AuthSessionService.HoldTicket decided = service.pendingHold(identityId).orElseThrow();
    assertTrue(decided.decided());
    assertTrue(decided.approved());

    service.consumeRequest(requestId);
    assertEquals(AuthRequestRepository.STATE_CONSUMED, service.requests().byId(requestId).state());
  }

  @Test
  @DisplayName("a denied hold reads as decided-but-not-approved, which is the kick cue")
  void deniedHoldTicket() throws Exception {
    link("Steve", "discord-1");
    config.set("auth.hold.enabled", "true");
    service.setHoldAvailable(() -> true);
    String requestId = service.authorize(connection("Steve", "187.61.4.9")).requestId();
    service.decide(requestId, "discord-1", false);

    AuthSessionService.HoldTicket ticket =
        service.pendingHold(service.identity("Steve").identityId()).orElseThrow();
    assertTrue(ticket.decided());
    assertFalse(ticket.approved());
  }

  // --- configuration ------------------------------------------------------

  @Test
  @DisplayName("the pepper falls back to the api token, never to the empty string")
  void pepperFallsBackToTheApiToken() {
    config.set("auth.session.ip-pepper", "");
    config.set("api.token", "token-a");
    String withTokenA = service.ipHash("187.61.4.9");

    config.set("api.token", "token-b");
    assertNotEquals(withTokenA, service.ipHash("187.61.4.9"),
        "an empty pepper makes every deployment's hashes comparable, which is what it exists to stop");
  }

  @Test
  @DisplayName("the feature flags read straight from config, so a reload is enough to move them")
  void flags() {
    assertTrue(service.enabled());
    assertTrue(service.approvalsEnabled());
    assertFalse(service.premiumEnabled());
    assertFalse(service.holdEnabled());
    assertEquals(18, service.sessionHours());
    assertEquals(300_000L, service.requestTtlMillis());
    assertNotNull(service.database());
    assertNull(service.premiumLookup());
    assertNotNull(service.rateLimiter());
  }

  @Test
  @DisplayName("a premium lookup handed in at construction is the one the gate would consult")
  void premiumLookupIsExposed() {
    PremiumLookup lookup = username -> PremiumLookup.Verdict.cracked();
    MessageService messages = new MessageService(
        new ClassLoaderResourceAdapter(getClass().getClassLoader()), config, new StdoutLoggerAdapter("T"));
    AuthSessionService withLookup = new AuthSessionService(db, config, new StdoutLoggerAdapter("T"),
        messages, authService, lookup, "node-1", null);

    assertSame(lookup, withLookup.premiumLookup());
  }

  // --- helpers ------------------------------------------------------------

  private static AuthConnection connection(String username, String ip) {
    return AuthConnection.of(UUID.randomUUID(), username, ip);
  }

  private static AuthConnection premium(String username, String ip, String premiumUuid) {
    return new AuthConnection(username, UUID.randomUUID(), ip, null, 0, false, true, premiumUuid);
  }

  /** Links a name to a Discord account under its CANONICAL identity, as a real link would be. */
  private void link(String username, String discordUserId) throws Exception {
    AuthIdentity identity = service.identity(username);
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO players(uuid, name, discord_user_id, points, level, wins, kills, games,"
              + " updated_at) VALUES(?,?,?,0,0,0,0,0,0)")) {
        ps.setString(1, identity.identityId());
        ps.setString(2, username);
        ps.setString(3, discordUserId);
        ps.executeUpdate();
      }
      try (PreparedStatement ps = db.connection().prepareStatement(
          "INSERT INTO discord_accounts(minecraft_uuid, discord_user_id, minecraft_name,"
              + " created_at, updated_at) VALUES(?,?,?,0,0)")) {
        ps.setString(1, identity.identityId());
        ps.setString(2, discordUserId);
        ps.setString(3, username);
        ps.executeUpdate();
      }
    }
  }

  /** Pushes a request's expiry into the past without waiting for it. */
  private void expire(String requestId) throws Exception {
    synchronized (db.lock()) {
      try (PreparedStatement ps = db.connection().prepareStatement(
          "UPDATE auth_requests SET expires_at = 1 WHERE request_id = ?")) {
        ps.setString(1, requestId);
        ps.executeUpdate();
      }
    }
  }
}
