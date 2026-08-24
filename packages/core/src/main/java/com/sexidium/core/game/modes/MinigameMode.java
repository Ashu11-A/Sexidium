package com.sexidium.core.game.modes;

import com.sexidium.core.game.hud.HudCadence;
import com.sexidium.core.game.GameContext;
import com.sexidium.core.game.hud.GameHud;
import com.sexidium.core.game.team.Team;
import com.sexidium.core.game.team.TeamAllocator;
import com.sexidium.core.game.team.TeamHudContributor;
import com.sexidium.core.game.team.Teams;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageArg;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.platform.PlayerAdapter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class MinigameMode extends BaseTimedGame {
  /**
   * Team assignment for this match. Empty for a free-for-all; populated by {@link #formTeams()} (or by
   * a host match-lobby that pre-assigns teams) for team play. Minigames that read {@code teams} make
   * their scoring/win team-aware — e.g. a teammate finding a race item credits the whole team.
   */
  protected Teams teams = Teams.empty();

  /** Per-match arguments (e.g. {@code team:2} from a host match-lobby, or {@code ffa}). */
  private final List<String> modeArgs;

  protected MinigameMode(GameContext gameContext, String modeId, String displayName, int minPlayers) {
    this(gameContext, modeId, displayName, minPlayers, List.of());
  }

  protected MinigameMode(GameContext gameContext, String modeId, String displayName, int minPlayers, List<String> modeArgs) {
    super(gameContext, modeId, displayName, minPlayers);
    this.modeArgs = modeArgs == null ? List.of() : List.copyOf(modeArgs);
  }

  @Override
  protected String configPrefix() {
    return "minigames." + id();
  }

  @Override
  protected void announceStarted() {
    announce(MessageKey.COMMAND_START_SUCCESS,
        MessageArg.text("mode", displayName()),
        MessageArg.text("count", online().size()));
  }

  /**
   * True when this match should split players into coloured teams: either a host-chosen team count
   * ({@code teams:<n>}) or a configured/overridden players-per-team. {@code ffa} forces free-for-all.
   */
  protected boolean teamPlay() {
    Integer count = teamCountFromArgs();
    if (count != null) {
      return count >= 2;
    }
    return playersPerTeamConfig() > 0;
  }

  /**
   * Explicit team COUNT from the mode args: {@code teams:<n>} (host-chosen number of coloured teams)
   * or {@code ffa} (0). Null when neither is present, so team count falls back to size-derived.
   */
  protected Integer teamCountFromArgs() {
    for (String arg : modeArgs) {
      if (arg == null) {
        continue;
      }
      String normalized = arg.toLowerCase(java.util.Locale.ROOT).trim();
      if (normalized.equals("ffa")) {
        return 0;
      }
      if (normalized.startsWith("teams:") || normalized.startsWith("teams=")) {
        try {
          return Integer.parseInt(normalized.substring(6).trim());
        } catch (NumberFormatException ignored) {
          // fall through
        }
      }
    }
    return null;
  }

  protected Map<UUID, Integer> teamAssignmentsFromArgs() {
    Map<UUID, Integer> assignments = new LinkedHashMap<>();
    for (String arg : modeArgs) {
      if (arg == null) {
        continue;
      }
      String trimmed = arg.trim();
      if (!trimmed.toLowerCase(java.util.Locale.ROOT).startsWith("assign:")) {
        continue;
      }
      String[] parts = trimmed.substring(7).split(":", 2);
      if (parts.length != 2) {
        continue;
      }
      try {
        assignments.put(UUID.fromString(parts[0].trim()), Integer.parseInt(parts[1].trim()));
      } catch (IllegalArgumentException ignored) {
        // Ignore malformed optional lobby assignment args.
      }
    }
    return assignments;
  }

  private int playersPerTeamConfig() {
    Integer fromArgs = teamSizeFromArgs();
    if (fromArgs != null) {
      return Math.max(0, fromArgs);
    }
    return Math.max(0, cfg().getInt(configPath("players-per-team"), 0));
  }

  /**
   * Per-match players-per-team override from the mode args: {@code size:<n>}, legacy {@code team:<n>},
   * or {@code ffa} (0). Null when none is present (fall back to config).
   */
  private Integer teamSizeFromArgs() {
    for (String arg : modeArgs) {
      if (arg == null) {
        continue;
      }
      String normalized = arg.toLowerCase(java.util.Locale.ROOT).trim();
      if (normalized.equals("ffa")) {
        return 0;
      }
      if (normalized.startsWith("size:") || normalized.startsWith("size=")
          || normalized.startsWith("team:") || normalized.startsWith("team=")) {
        try {
          return Integer.parseInt(normalized.substring(5).trim());
        } catch (NumberFormatException ignored) {
          // fall through to config
        }
      }
    }
    return null;
  }

  protected int playersPerTeam() {
    int configured = playersPerTeamConfig();
    return configured > 0 ? configured : 2;
  }

  /** Allocates balanced teams from the current participants. Call after {@code super.start(...)}. */
  protected void formTeams() {
    Integer count = teamCountFromArgs();
    Map<UUID, Integer> assignments = teamAssignmentsFromArgs();
    teams = count != null && count >= 2
        ? TeamAllocator.allocateAssigned(new ArrayList<>(players), count, assignments)
        : TeamAllocator.allocate(new ArrayList<>(players), playersPerTeam());
    if (usesTeamSidebar()) {
      // The shared team card is contributed to the unified HUD, so it gets the toggle/compact view and
      // one panel per viewer (read through this::teams so a live switch is reflected on the next refresh).
      long refresh = HudCadence.ticks(cfg(), configPath("team-hud.refresh-ticks"));
      GameHud hud = installHud(LocalizedText.of(MessageKey.TEAM_HUD_TITLE), refresh, null,
          new TeamHudContributor(gameContext.server(), this::teams));
      for (PlayerAdapter player : online()) {
        hud.show(player);
      }
    }
  }

  /**
   * Whether this mode shows the shared right-side team panel (team colour + allies + rivals) when
   * teams are formed. Modes that already own the sidebar with their own board (Race, TNT War) override
   * this to {@code false} and fold team information into their own board instead.
   */
  protected boolean usesTeamSidebar() {
    return true;
  }

  public Teams teams() {
    return teams;
  }

  protected boolean sameTeam(PlayerAdapter first, PlayerAdapter second) {
    return first != null && second != null && !teams.isEmpty() && teams.sameTeam(first.uniqueId(), second.uniqueId());
  }

  /**
   * When teams are active, the one team that still has an online member while all others are wiped, or
   * null if zero or more than one team remains. Used for last-team-standing win conditions.
   */
  protected Team lastTeamStanding() {
    if (teams.isEmpty()) {
      return null;
    }
    List<java.util.UUID> alive = new ArrayList<>();
    for (PlayerAdapter player : remainingOnlineParticipants()) {
      alive.add(player.uniqueId());
    }
    List<Team> standing = teams.teamsWithAnyOf(alive);
    return standing.size() == 1 ? standing.get(0) : null;
  }

  /** One operator-configured template map: its {@code id} and the world folder it is cloned from. */
  public record ConfiguredMap(String id, String world) {
  }

  /**
   * Picks a random configured template map from {@code minigames.<id>.maps} — a list of {@code {id, world}}
   * entries — or null when none are configured. The single reader behind every mode's "use a hand-built map
   * unless none is configured, then fall back to a generated one" rule (TNT War, Combat, Fugitive, Race), so
   * the map-registry shape is identical across modes. A mode that needs a stable pick (e.g. to also load the
   * map's spawn sidecar) should call this ONCE and cache the result, since each call re-rolls.
   */
  protected ConfiguredMap chooseConfiguredMap() {
    List<Map<String, Object>> maps = cfg().getMapList(configPath("maps"));
    if (maps == null || maps.isEmpty()) {
      return null;
    }
    Map<String, Object> chosen = maps.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(maps.size()));
    if (chosen == null) {
      return null;
    }
    String id = chosen.get("id") == null ? "" : String.valueOf(chosen.get("id")).trim();
    String world = chosen.get("world") == null ? "" : String.valueOf(chosen.get("world")).trim();
    if (world.isEmpty()) {
      world = id;
    }
    if (world.isEmpty()) {
      return null;
    }
    return new ConfiguredMap(id.isEmpty() ? world : id, world);
  }

  /**
   * Resolves an optional, operator-configured template map for modes that may run on EITHER a freshly
   * generated world OR a hand-built map (Fugitive, Race) and returns the chosen entry's {@code world}
   * folder, so {@code GameManager} clones it into the match world. Returns null when no maps are
   * configured, leaving the mode on a generated/pooled world. Each {@code start} mints a fresh game
   * instance, so the random pick naturally rotates across matches.
   */
  protected String chooseConfiguredMapTemplate() {
    ConfiguredMap map = chooseConfiguredMap();
    return map == null ? null : map.world();
  }

  /** Awards a win to every (still-known) member of the team. */
  protected void awardTeamWin(Team team) {
    if (team == null) {
      return;
    }
    for (java.util.UUID memberId : team.members()) {
      gameContext.server().player(memberId).ifPresent(this::awardWin);
    }
  }
}
