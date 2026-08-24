package com.sexidium.velocity;

import com.sexidium.core.network.NodeRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lobby selection, which used to be "return whatever is called lobby" and therefore blocked lobby
 * scale-out entirely.
 */
class LobbySelectorTest {

  private static final long NOW = 1_000_000L;
  private static final long TIMEOUT = 30_000L;

  private static NodeRegistry.Node lobby(String id, String state, int players, int maxPlayers,
      int worlds, long heartbeatAge) {
    return new NodeRegistry.Node(id, id, "lobby", "lobby,minigames", id, 25565, state, players,
        worlds, 1L, NOW - heartbeatAge, 1L, null, null, null, null, maxPlayers, 0, -1, -1, 0, 0);
  }

  private static Optional<String> choose(List<NodeRegistry.Node> nodes) {
    return LobbySelector.choose(nodes, NOW, TIMEOUT, 100, 10);
  }

  @Test
  @DisplayName("the least-loaded lobby wins, so a second lobby actually receives joins")
  void picksLeastLoadedNonDrainingLobby() {
    assertEquals(Optional.of("lobby-2"), choose(List.of(
        lobby("lobby-1", NodeRegistry.STATE_UP, 40, 100, 0, 0),
        lobby("lobby-2", NodeRegistry.STATE_UP, 5, 100, 0, 0))));
  }

  @Test
  @DisplayName("a DRAINING lobby is never chosen — it is trying to reach zero")
  void ignoresDrainingNamedLobby() {
    // It stays routable for the players already on it; that is what DRAINING means. Handing it new
    // ones adds work to the node that is trying to empty, and the player would be moved out again.
    assertEquals(Optional.of("lobby-2"), choose(List.of(
        lobby("lobby-1", NodeRegistry.STATE_DRAINING, 0, 100, 0, 0),
        lobby("lobby-2", NodeRegistry.STATE_UP, 60, 100, 0, 0))));
  }

  @Test
  @DisplayName("a DOWN or stale lobby is never chosen")
  void ignoresDeadLobbies() {
    assertEquals(Optional.of("lobby-2"), choose(List.of(
        lobby("lobby-1", NodeRegistry.STATE_DOWN, 0, 100, 0, 0),
        lobby("lobby-3", NodeRegistry.STATE_UP, 0, 100, 0, 60_000L),
        lobby("lobby-2", NodeRegistry.STATE_UP, 60, 100, 0, 0))));
  }

  @Test
  @DisplayName("a node without the lobby capability is not a lobby, whatever it is called")
  void requiresTheLobbyCapability() {
    NodeRegistry.Node worker = new NodeRegistry.Node("lobby", "lobby", "worker",
        "experiences,minigames", "lobby", 25565, NodeRegistry.STATE_UP, 0, 0, 1L, NOW,
        1L, null, null, null, null, 100, 0, -1, -1, 0, 0);
    assertTrue(choose(List.of(worker)).isEmpty());
    assertTrue(choose(List.of()).isEmpty());
    assertTrue(choose(null).isEmpty());
  }

  @Test
  @DisplayName("one player's difference does not flip the choice — load is banded")
  void bandedLoadDoesNotFlipOnOnePlayer() {
    // Without banding, every single join would re-target the other lobby and the two would ping-pong
    // one player at a time. Inside a band the tiebreak is worlds, then node id, so it is stable.
    assertEquals(Optional.of("lobby-1"), choose(List.of(
        lobby("lobby-1", NodeRegistry.STATE_UP, 15, 100, 0, 0),
        lobby("lobby-2", NodeRegistry.STATE_UP, 14, 100, 0, 0))));
    // A whole band apart, and the emptier node wins as it should.
    assertEquals(Optional.of("lobby-2"), choose(List.of(
        lobby("lobby-1", NodeRegistry.STATE_UP, 20, 100, 0, 0),
        lobby("lobby-2", NodeRegistry.STATE_UP, 9, 100, 0, 0))));
  }

  @Test
  @DisplayName("a node that has not published max_players is banded against the assumed capacity")
  void unknownCapacityFallsBackToTheAssumedOne() {
    // Otherwise a node in its first seconds looks infinitely loaded (or infinitely empty) and the
    // band means nothing on exactly the node a scale-out just started.
    assertEquals(2, LobbySelector.band(lobby("x", NodeRegistry.STATE_UP, 20, 0, 0, 0), 100, 10));
    assertEquals(0, LobbySelector.band(lobby("x", NodeRegistry.STATE_UP, 20, 0, 0, 0), 1000, 10));
  }

  @Test
  @DisplayName("the choice is deterministic, so two proxies deciding at once agree")
  void tiesAreBrokenDeterministically() {
    assertEquals(Optional.of("lobby-a"), choose(List.of(
        lobby("lobby-b", NodeRegistry.STATE_UP, 5, 100, 0, 0),
        lobby("lobby-a", NodeRegistry.STATE_UP, 5, 100, 0, 0))));
  }

  // ----- the fallback tiers ---------------------------------------------------------------------
  //
  // Reached whenever the registry names no LIVE lobby: nothing registered yet, every lobby stale, or
  // every lobby draining. It used to be "return whatever is called lobby, else the emptiest server of
  // any kind", which fed a draining lobby forever and could hand a kicked player to a worker.

  private static NodeRegistry.Node worker(String id, String state) {
    return new NodeRegistry.Node(id, id, "worker", "experiences,minigames", id, 25565, state, 0, 0,
        1L, NOW, 1L, null, null, null, null, 100, 0, -1, -1, 0, 0);
  }

  private static LobbySelector.Candidate server(String name, int players) {
    return new LobbySelector.Candidate(name, players);
  }

  @Test
  @DisplayName("the server literally named lobby wins when nothing is registered")
  void theNamedLobbyIsStillTheAnswerForAnUnregisteredTopology() {
    assertEquals(Optional.of("lobby"),
        LobbySelector.fallback(List.of(), List.of(server("lobby", 8), server("worker-1", 0)), "lobby"));
  }

  @Test
  @DisplayName("a DRAINING named lobby is passed over for a registered one that is not draining")
  void aDrainingNamedLobbyIsNotFedNewPlayers() {
    // The bug this closes: tier 1 returns empty exactly when every lobby node is draining or stale,
    // and the tier below it was a bare name lookup -- so the node trying to reach zero was handed
    // every arriving and every kicked player, and its drain could never finish.
    List<NodeRegistry.Node> nodes = List.of(
        lobby("lobby", NodeRegistry.STATE_DRAINING, 0, 100, 0, 0),
        lobby("lobby-2", NodeRegistry.STATE_UP, 0, 100, 0, 90_000L));
    assertEquals(Optional.of("lobby-2"),
        LobbySelector.fallback(nodes, List.of(server("lobby", 0), server("lobby-2", 3)), "lobby"));
  }

  @Test
  @DisplayName("a kicked player is never handed a worker, however empty it is")
  void neverFallsBackToAWorker() {
    List<NodeRegistry.Node> nodes = List.of(
        lobby("lobby-2", NodeRegistry.STATE_DOWN, 0, 100, 0, 90_000L),
        worker("worker-1", NodeRegistry.STATE_UP));
    // worker-1 is the emptiest server on the proxy, and the old tier 3 would have chosen it: no
    // lobby world, no menu, no NPCs and no way back.
    assertEquals(Optional.of("lobby-2"),
        LobbySelector.fallback(nodes, List.of(server("lobby-2", 9), server("worker-1", 0)), "lobby"));
  }

  @Test
  @DisplayName("with several registered lobbies to fall back to, the emptiest wins")
  void theEmptiestRegisteredLobbyWins() {
    List<NodeRegistry.Node> nodes = List.of(
        lobby("lobby-1", NodeRegistry.STATE_DOWN, 0, 100, 0, 90_000L),
        lobby("lobby-2", NodeRegistry.STATE_DOWN, 0, 100, 0, 90_000L));
    assertEquals(Optional.of("lobby-2"),
        LobbySelector.fallback(nodes, List.of(server("lobby-1", 12), server("lobby-2", 2)), "x"));
  }

  @Test
  @DisplayName("a draining lobby still beats disconnecting the player when it is the only one")
  void aDrainingLobbyIsTheLastResortRatherThanADisconnect() {
    // The single-lobby force-drain. Refusing to answer here does not keep anybody off that node --
    // it drops them from the network entirely, because this is what KickedFromServerEvent redirects
    // to instead of letting the disconnect stand. A stalled drain is one command to recover from.
    List<NodeRegistry.Node> nodes = List.of(
        lobby("lobby", NodeRegistry.STATE_DRAINING, 0, 100, 0, 0),
        worker("worker-1", NodeRegistry.STATE_UP));
    assertEquals(Optional.of("lobby"),
        LobbySelector.fallback(nodes, List.of(server("lobby", 0), server("worker-1", 0)), "lobby"));
  }

  @Test
  @DisplayName("no servers at all means no answer, rather than a made-up one")
  void nothingToChooseFrom() {
    assertTrue(LobbySelector.fallback(List.of(), List.of(), "lobby").isEmpty());
    assertTrue(LobbySelector.fallback(null, null, "lobby").isEmpty());
  }
}
