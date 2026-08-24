package com.sexidium.core.network;

import com.sexidium.core.network.transfer.TransferReason;
import com.sexidium.core.network.transfer.TransferState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The answer to "how does a developer know they have to bump {@link Protocol#VERSION}?".
 *
 * <p>Two nodes on different builds share a database, a bus and a set of fenced tables. Nothing warns
 * you when you change what crosses between them — you find out when a rolling update strands a node.
 * So this test digests the canonical wire surface and pins it against a committed constant. Touching
 * a bus topic value, a placement state constant, a transfer enum name or the column list in
 * {@code NodeRegistry}'s upsert fails the build.</p>
 *
 * <p><b>It is brittle on purpose, and that is the feature.</b> The fix is never to "just update the
 * digest": it is to read {@link Protocol}'s bump rules, decide whether the change is one that
 * requires a tag bump, and then update the digest in the same commit — which puts a reviewer in front
 * of the decision. Some changes genuinely need no bump (a NEW topic an old node simply has no
 * subscriber for, a NEW column only the new build reads); those still move the digest, and saying so
 * in the commit message is the whole point.</p>
 */
class ProtocolContractTest {

  /**
   * SHA-256 over the wire surface described in {@link #wireSurface()}.
   *
   * <p>Update this ONLY together with a conscious decision about {@link Protocol#VERSION}. See the
   * class javadoc.</p>
   *
   * <p>Change log, so the next person can see what kind of change moves this and what it cost:</p>
   * <ul>
   *   <li>the initial surface, at {@code Protocol.VERSION = 1}.</li>
   *   <li>the drain surface added at {@code VERSION = 1}: {@code node_drains}' columns, the
   *       {@code DrainState} shape and the {@code node.drain} payload grammar. No bump — the three
   *       were always part of the protocol, they were simply not covered, so nothing about what a
   *       peer reads changed on the day this line was written.</li>
   *   <li>{@code experience.command.result} and the {@code experience_commands} table added, at
   *       {@code Protocol.VERSION = 1} — <b>no bump</b>. A new topic an older node has no subscriber
   *       for, and a new table only a build that knows the op ever reads: an old node never writes a
   *       request, and a new node's request addressed to an old one simply sits PENDING until that
   *       node is upgraded, which is the deferred behaviour the delete path wants anyway.</li>
   *   <li>{@code auth.decided} added, at {@code Protocol.VERSION = 1} — <b>no bump</b>. It is the
   *       worked example below exactly: a new topic that a node compiled before it existed simply
   *       has no subscriber for. It is also advisory rather than load-bearing — a held player is
   *       released by the deciding node directly, and failing that by that node's own one-second
   *       re-read of {@code auth_requests}, so a peer that never hears the message strands nobody.</li>
   * </ul>
   *
   * <p><b>Worked example, for the first person this test stops.</b> Adding a brand-new bus topic
   * moves the digest and needs <em>no</em> bump: a new topic is the one thing the bus is genuinely
   * forward-compatible about, because a node compiled before it existed has no subscriber and simply
   * ignores the row — nothing about ownership or fencing changed. Update the digest, add a line here
   * saying which topic and why it was a no-bump, and move on. Contrast: changing the payload
   * <em>grammar</em> of an existing topic, or the meaning of a column both builds write, is a bump.</p>
   */
  private static final String WIRE_DIGEST =
      "fba1b787d00444fbcd76c676d6890f72e214eaf189344a3b0745cfc2d77a833f";

  @Test
  @DisplayName("the wire surface has not moved without a conscious decision about the protocol tag")
  void wireSurfaceDigestMatchesDeclaredProtocol() {
    assertEquals(WIRE_DIGEST, digest(wireSurface()),
        "The cross-node wire surface changed. Read com.sexidium.core.network.Protocol: decide"
            + " whether this needs a VERSION bump (a changed column meaning, a changed payload"
            + " grammar, a new REQUIRED drain behaviour) or not (a new topic, a new column only this"
            + " build reads), then update WIRE_DIGEST in the same commit.\n\nActual surface:\n"
            + String.join("\n", wireSurface()));
  }

  @Test
  @DisplayName("MIN_PEER is never raised past VERSION — that would strand every node on the last build")
  void minPeerNeverExceedsVersion() {
    // Raising MIN_PEER is a two-release operation: ship VERSION=k everywhere first, THEN raise the
    // floor to k. A build whose floor is above its own tag refuses every peer including its own kind.
    assertEquals(true, Protocol.MIN_PEER <= Protocol.VERSION,
        "MIN_PEER (" + Protocol.MIN_PEER + ") is above VERSION (" + Protocol.VERSION + ")");
  }

  /** Every part of the surface, in a stable order, one line each. */
  private static List<String> wireSurface() {
    List<String> lines = new ArrayList<>();
    for (String topic : new TreeSet<>(stringConstants(NetworkBus.Topics.class))) {
      lines.add("topic=" + topic);
    }
    for (String state : new TreeSet<>(stringConstants(DbWorldLeaseAuthority.class))) {
      lines.add("placement=" + state);
    }
    for (String state : new TreeSet<>(stringConstants(NodeRegistry.class))) {
      lines.add("nodestate=" + state);
    }
    for (TransferState state : TransferState.values()) {
      lines.add("transferState=" + state.name());
    }
    for (TransferReason reason : TransferReason.values()) {
      lines.add("transferReason=" + reason.name());
    }
    for (DrainPhase phase : DrainPhase.values()) {
      lines.add("drainPhase=" + phase.name());
    }
    for (String column : columnsOf(NodeRegistry.UPSERT_UPDATE_SQL)) {
      lines.add("nodeColumn=" + column);
    }
    for (String column : columnsOf(NodeRegistry.SELECT_ALL_SQL)) {
      lines.add("nodeRead=" + column);
    }
    // The drain protocol's own three surfaces, none of which were covered: the node_drains column
    // list a mixed-build fleet reads, the DrainState shape GET /node/drain freezes, and the grammar
    // of the node.drain payload -- all three renameable in silence before this.
    for (String column : DbDrainStore.COLUMNS.split(",")) {
      lines.add("drainColumn=" + column.trim());
    }
    for (RecordComponent component : DrainState.class.getRecordComponents()) {
      lines.add("drainState=" + component.getName() + ":" + component.getType().getSimpleName());
    }
    // The owner-command protocol's own surface: the experience_commands columns a mixed-build fleet
    // reads, and the states it writes into them.
    for (String column : DbExperienceCommandStore.COLUMNS.split(",")) {
      lines.add("experienceCommandColumn=" + column.trim());
    }
    for (ExperienceCommandStore.State state : ExperienceCommandStore.State.values()) {
      lines.add("experienceCommandState=" + state.name());
    }
    lines.add("drainPayload=" + DrainCoordinator.drainPayload(DrainPhase.ANNOUNCED, 7L, 3L));
    lines.add("drainPayload=" + DrainCoordinator.drainPayload(DrainPhase.NONE, 7L));
    return lines;
  }

  /** Public static final Strings declared on a class — the "constant set" of a wire vocabulary. */
  private static List<String> stringConstants(Class<?> type) {
    List<String> values = new ArrayList<>();
    for (Field field : type.getDeclaredFields()) {
      if (!Modifier.isStatic(field.getModifiers()) || !Modifier.isFinal(field.getModifiers())
          || !Modifier.isPublic(field.getModifiers()) || field.getType() != String.class) {
        continue;
      }
      try {
        Object value = field.get(null);
        if (value != null) {
          values.add(String.valueOf(value));
        }
      } catch (IllegalAccessException inaccessible) {
        throw new AssertionError("Could not read " + type.getSimpleName() + "." + field.getName(),
            inaccessible);
      }
    }
    return values;
  }

  /**
   * The column names inside a statement, in the order they appear.
   *
   * <p>A regex over a SQL string is exactly as fragile as it looks, and it is meant to be: the point
   * is that reordering or renaming what a node publishes cannot happen quietly.</p>
   */
  private static List<String> columnsOf(String sql) {
    List<String> columns = new ArrayList<>();
    Matcher matcher = Pattern.compile("\\b([a-z_]+)\\s*(?:=\\s*\\?|,|\\bFROM\\b)").matcher(sql);
    while (matcher.find()) {
      String candidate = matcher.group(1);
      if (!candidate.isBlank() && !"from".equals(candidate) && !columns.contains(candidate)) {
        columns.add(candidate);
      }
    }
    return columns;
  }

  private static String digest(List<String> lines) {
    try {
      byte[] hash = MessageDigest.getInstance("SHA-256")
          .digest(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(64);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception impossible) {
      throw new AssertionError(impossible);
    }
  }
}
