package com.sexidium.paper.adapter.player;

import com.sexidium.paper.adapter.util.PlatformProbes;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a player's client render HARDCORE hearts for the world they are being sent into — and stop
 * rendering them when they are sent back to the lobby.
 *
 * <h2>Why this needs a packet at all</h2>
 * {@code World#setHardcore} is a server-side property: it locks the difficulty and drives vanilla's
 * death handling, but it does not tell any client anything. A client is told whether it is in a hardcore
 * world exactly once — in the login packet, built from the world it logs in to — and believes it until
 * it is sent another. Everything that follows from that is what a player actually reported: a hardcore
 * experience entered from the lobby drew ordinary hearts, and a player who logged in inside a hardcore
 * world kept hardened hearts after walking back into the lobby. No amount of setting the flag on the
 * world fixes either, because neither is about the world.
 *
 * <p>So the client is re-told, by re-sending it a login packet with the flag it should have. That is a
 * heavy thing to say to a client: it throws away its loaded world and waits to be sent a new one. Which
 * is why {@link com.sexidium.core.game.EntryPolicy#prepareArrival} only ever calls this immediately
 * before a teleport that changes world — the change re-sends the level, the chunks, the position,
 * abilities, health, XP, inventory and effects, which is exactly the resynchronisation this needs and
 * gets for free. Called at any other moment it would leave a client staring at an empty world, so it is
 * not offered at any other moment.</p>
 *
 * <h2>Fail-safe</h2>
 * Every server-internal touch here is reflective and optional. The first failure gives up permanently
 * and logs once; the world flag (difficulty, vanilla hardcore death) is unaffected, so the worst case is
 * the cosmetic one this class exists to fix. Nothing here can stop a player entering a world.
 */
public final class PaperHardcoreView {
  private static final String LOGIN_PACKET = "net.minecraft.network.protocol.game.ClientboundLoginPacket";

  /**
   * Every record component {@link #buildLoginPacket} knows how to fill — the case labels below, named
   * once so the enable-time shape check ({@link #loginPacketShapeReport()}) and the builder cannot
   * drift apart silently. When Minecraft grows a component, the fix is one new case here PLUS one new
   * name in this set; forgetting the set turns a green boot check into the warning that finds it.
   *
   * <h2>This is a UNION across supported versions, not one version's packet</h2>
   * One jar runs on every Minecraft its {@code api-version} floor admits, and those versions do not
   * agree on this record: 26.1.2 has 11 components, 26.2 has 12 — it added {@code onlineMode} before
   * {@code enforcesSecureChat}. So a name in this set that the RUNNING server's packet lacks is the
   * normal state of affairs on the older version, not drift, and {@link #loginPacketShapeReport()}
   * deliberately does not report it.
   *
   * <p>The asymmetry is the whole point. An <em>unrecognised</em> component is a real blocker: the
   * builder iterates the actual components and would hit {@code default -> null} and give up. A
   * <em>missing</em> one costs nothing, because a case that is never reached is never a problem. An
   * earlier version of this class reported both, and on the pinned 26.1.2 that turned a working
   * feature into a red line in the boot log.</p>
   */
  private static final Set<String> KNOWN_LOGIN_COMPONENTS = Set.of(
      "playerId", "hardcore", "levels", "maxPlayers", "chunkRadius", "simulationDistance",
      "reducedDebugInfo", "showDeathScreen", "doLimitedCrafting", "commonPlayerSpawnInfo",
      "onlineMode", "enforcesSecureChat");

  // What we believe each client currently thinks. Seeded on join from the world they logged in to (which
  // is where the client got its own answer), updated on every packet we send, forgotten on quit. Kept so
  // an entry into a non-hardcore world after another non-hardcore world costs nothing at all.
  private static final Map<UUID, Boolean> CLIENT_VIEW = new ConcurrentHashMap<>();

  // null = not yet probed, TRUE = usable, FALSE = unavailable on this server (give up quietly).
  private static volatile Boolean supported;
  private static Method getHandle;
  private static Field connectionField;
  private static Method sendPacket;
  private static Method serverPlayerId;
  private static Method serverPlayerLevel;
  private static Method createCommonSpawnInfo;
  private static Method levelGetServer;
  private static Method serverLevelKeys;
  private static Method enforceSecureProfile;
  private static Constructor<?> loginPacket;
  private static RecordComponent[] loginComponents;

  private PaperHardcoreView() {
  }

  /**
   * Records what the client was told when it logged in: the login world's own hardcore flag. Without
   * this the first entry after a join would either send a pointless packet or, worse, skip a needed one.
   */
  public static void seed(Player player) {
    World world = player == null ? null : player.getWorld();
    if (world != null) {
      CLIENT_VIEW.put(player.getUniqueId(), world.isHardcore());
    }
  }

  /** Forgets a disconnected player: their next login tells the client afresh. */
  public static void forget(UUID playerId) {
    if (playerId != null) {
      CLIENT_VIEW.remove(playerId);
    }
  }

  /**
   * The enable-time shape check: reflects the running server's {@code ClientboundLoginPacket} record
   * components and diffs them against what {@link #buildLoginPacket} knows how to fill.
   *
   * <p>This is the check that converts the next silent Minecraft regression into a startup line. MC
   * 26.2 added {@code onlineMode} and stopped every hardcore toggle until anyone noticed in-game; with
   * this, the same change announces itself at boot instead. Needs no player — pure reflection on the
   * packet class — so it runs before anybody is online.</p>
   *
   * @return one human-readable line per problem; empty means the packet shape matches and hardcore
   *     hearts are expected to work
   */
  public static List<String> loginPacketShapeReport() {
    Class<?> packet = PlatformProbes.linkableClass(LOGIN_PACKET, PaperHardcoreView.class.getClassLoader());
    if (packet == null) {
      // A server without NMS under this name cannot ever send the packet — but that is a question about
      // AVAILABILITY, not about shape, and it is answered by unavailableReason() below. Reporting it
      // here as a shape problem would alarm every boot on a fork that simply has no NMS.
      return List.of();
    }
    RecordComponent[] components = packet.getRecordComponents();
    if (components == null) {
      return List.of("the login packet (" + LOGIN_PACKET + ") is not a record on this server;"
          + " hardcore hearts cannot be updated");
    }
    List<String> problems = new ArrayList<>();
    for (RecordComponent component : components) {
      String name = component.getName();
      if (!KNOWN_LOGIN_COMPONENTS.contains(name)) {
        problems.add("unrecognised login-packet field '" + name + "': add a case that DERIVES it"
            + " from the server to buildLoginPacket (never a literal), plus its name to"
            + " KNOWN_LOGIN_COMPONENTS");
      }
    }
    return problems;
  }

  /**
   * Why hardcore hearts cannot be toggled on this server, or empty when they can — the answer the
   * capability registry records for {@code HARDCORE_VIEW_PACKET}.
   *
   * <p>Split from {@link #loginPacketShapeReport()} because the two questions have different answers on
   * the same server. A fork with no {@code ClientboundLoginPacket} has a perfectly fine SHAPE report
   * (there is no shape to disagree with) and no CAPABILITY at all. Deriving the capability from the
   * shape report alone reported "supported" on exactly the servers where {@link #bind} had already
   * given up — green in the boot log and in {@code /sx admin capabilities}, with hearts that never
   * rendered. A registry that guesses yes is worse than one that says nothing.</p>
   */
  public static Optional<String> unavailableReason() {
    if (PlatformProbes.linkableClass(LOGIN_PACKET, PaperHardcoreView.class.getClassLoader()) == null) {
      return Optional.of("this server does not expose " + LOGIN_PACKET + "; hardcore hearts keep the"
          + " client's own view (the world's hardcore flag itself is unaffected)");
    }
    List<String> problems = loginPacketShapeReport();
    return problems.isEmpty() ? Optional.empty() : Optional.of(String.join("; ", problems));
  }

  /**
   * Tells {@code player}'s client to render hardcore hearts (or to stop). No-op when the client already
   * believes that, and when the server does not expose what this needs.
   *
   * <p>MUST be followed immediately by a teleport into another world — see the class notes.</p>
   */
  public static void apply(Player player, boolean hardcore) {
    if (player == null || !player.isOnline()) {
      return;
    }
    Boolean current = CLIENT_VIEW.get(player.getUniqueId());
    if (current != null && current == hardcore) {
      return;
    }
    if (!resolve(player)) {
      return;
    }
    try {
      Object serverPlayer = getHandle.invoke(player);
      Object level = serverPlayerLevel.invoke(serverPlayer);
      Object packet = buildLoginPacket(player, serverPlayer, level, hardcore);
      if (packet == null) {
        return;
      }
      sendPacket.invoke(connectionField.get(serverPlayer), packet);
      CLIENT_VIEW.put(player.getUniqueId(), hardcore);
    } catch (ReflectiveOperationException | RuntimeException exception) {
      giveUp("Could not update the hardcore view for " + player.getName(), exception);
    }
  }

  /**
   * Fills the login packet by record-component NAME rather than by position, so a server that adds or
   * reorders a field is a component this does not recognise — and an unrecognised component aborts the
   * send. Guessing at one would mean telling a client something arbitrary about its own session.
   *
   * <p>A Minecraft release can add a component here, and when it does this is exactly what should
   * happen: the abort is the signal. MC 26.2 added {@code onlineMode} between {@code
   * commonPlayerSpawnInfo} and {@code enforcesSecureChat}, which stopped every hardcore toggle until
   * the case below was added. When the warning names a field, add a case that DERIVES the value from
   * the server — never a literal.</p>
   */
  @SuppressWarnings("removal") // GameRule constants are deprecated-for-removal; no replacement API yet
  private static Object buildLoginPacket(Player player, Object serverPlayer, Object level, boolean hardcore)
      throws ReflectiveOperationException {
    World world = player.getWorld();
    Object server = levelGetServer.invoke(level);
    Object[] arguments = new Object[loginComponents.length];
    for (int index = 0; index < loginComponents.length; index++) {
      String name = loginComponents[index].getName();
      Object value = switch (name) {
        case "playerId" -> serverPlayerId.invoke(serverPlayer);
        case "hardcore" -> hardcore;
        case "levels" -> serverLevelKeys.invoke(server);
        case "maxPlayers" -> Bukkit.getMaxPlayers();
        case "chunkRadius" -> world.getViewDistance();
        case "simulationDistance" -> world.getSimulationDistance();
        case "reducedDebugInfo" -> rule(world, GameRule.REDUCED_DEBUG_INFO, false);
        // The death screen is the whole point of a hardcore death, so it is derived from the same
        // gamerule vanilla derives it from rather than assumed.
        case "showDeathScreen" -> !rule(world, GameRule.DO_IMMEDIATE_RESPAWN, false);
        case "doLimitedCrafting" -> rule(world, GameRule.DO_LIMITED_CRAFTING, false);
        case "commonPlayerSpawnInfo" -> createCommonSpawnInfo.invoke(serverPlayer, level);
        // Added in MC 26.2. Whether the server authenticates against Mojang — the client's own session
        // state, so it comes from the server rather than from anything about this hardcore toggle.
        case "onlineMode" -> Bukkit.getOnlineMode();
        case "enforcesSecureChat" -> enforcesSecureChat(server);
        default -> null;
      };
      if (value == null) {
        giveUp("Unrecognised login-packet field '" + name + "' — hardcore hearts stay as the client has them", null);
        return null;
      }
      arguments[index] = value;
    }
    return loginPacket.newInstance(arguments);
  }

  private static boolean rule(World world, GameRule<Boolean> gameRule, boolean fallback) {
    Boolean value = world.getGameRuleValue(gameRule);
    return value == null ? fallback : value;
  }

  private static Object enforcesSecureChat(Object server) {
    if (enforceSecureProfile == null) {
      return Boolean.FALSE;
    }
    try {
      return enforceSecureProfile.invoke(server);
    } catch (ReflectiveOperationException | RuntimeException exception) {
      return Boolean.FALSE;
    }
  }

  /** Resolves (once) everything needed off the running server. False = unavailable, never retried. */
  private static synchronized boolean resolve(Player player) {
    if (supported != null) {
      return supported;
    }
    try {
      getHandle = player.getClass().getMethod("getHandle");
      getHandle.setAccessible(true);
      Object serverPlayer = getHandle.invoke(player);
      Class<?> serverPlayerType = serverPlayer.getClass();
      connectionField = serverPlayerType.getField("connection");
      sendPacket = findSend(connectionField.get(serverPlayer).getClass());
      serverPlayerId = serverPlayerType.getMethod("getId");
      serverPlayerLevel = serverPlayerType.getMethod("level");
      Object level = serverPlayerLevel.invoke(serverPlayer);
      // By name, not by parameter type: the level's runtime class may be a subclass (Folia, a fork) that
      // the declared single-argument signature would not match.
      createCommonSpawnInfo = findByName(serverPlayerType, "createCommonSpawnInfo", 1);
      levelGetServer = level.getClass().getMethod("getServer");
      Object server = levelGetServer.invoke(level);
      serverLevelKeys = server.getClass().getMethod("levelKeys");
      enforceSecureProfile = optional(server.getClass(), "enforceSecureProfile");
      Class<?> packetType = PlatformProbes.linkableClass(LOGIN_PACKET,
          PaperHardcoreView.class.getClassLoader());
      if (packetType == null) {
        giveUp("This server does not expose " + LOGIN_PACKET, null);
        return false;
      }
      loginComponents = packetType.getRecordComponents();
      if (loginComponents == null) {
        giveUp("The login packet is not a record on this server", null);
        return false;
      }
      Class<?>[] parameters = new Class<?>[loginComponents.length];
      for (int index = 0; index < loginComponents.length; index++) {
        parameters[index] = loginComponents[index].getType();
      }
      loginPacket = packetType.getDeclaredConstructor(parameters);
      loginPacket.setAccessible(true);
      supported = Boolean.TRUE;
      return true;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
      // LinkageError included: everything above touches server internals, and "present but does not
      // link" has to reach giveUp() like any other refusal. Without it the error escaped resolve(),
      // escaped apply(), and surfaced inside EntryPolicy.prepareArrival — i.e. in a player's world
      // entry — over a cosmetic heart texture.
      giveUp("Hardcore hearts cannot be updated on this server", exception);
      return false;
    }
  }

  /** The connection's {@code send(Packet)} — matched by shape, since the packet type is not on our path. */
  private static Method findSend(Class<?> connectionType) throws NoSuchMethodException {
    return findByName(connectionType, "send", 1);
  }

  /** A public method by name + arity, walking up the hierarchy. Used where the argument type is NMS. */
  private static Method findByName(Class<?> owner, String name, int parameters) throws NoSuchMethodException {
    for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
      for (Method method : type.getMethods()) {
        if (method.getName().equals(name) && method.getParameterCount() == parameters) {
          method.setAccessible(true);
          return method;
        }
      }
    }
    throw new NoSuchMethodException(owner.getName() + "#" + name + "/" + parameters);
  }

  private static Method optional(Class<?> type, String name) {
    try {
      return type.getMethod(name);
    } catch (NoSuchMethodException exception) {
      return null;
    }
  }

  private static void giveUp(String reason, Throwable cause) {
    if (!Boolean.FALSE.equals(supported)) {
      supported = Boolean.FALSE;
      Bukkit.getLogger().warning("[Sexidium] " + reason
          + ". Hardcore worlds still lock difficulty and end on death; only the heart texture is affected."
          + (cause == null ? "" : " (" + cause + ")"));
    }
  }
}
