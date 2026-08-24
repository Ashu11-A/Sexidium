package com.sexidium.core.game.experience;

import com.sexidium.core.game.EntryPolicy;
import com.sexidium.core.game.hardcore.HardcoreRule;
import com.sexidium.core.i18n.LocalizedText;
import com.sexidium.core.i18n.MessageKey;
import com.sexidium.core.world.WorldGeneration;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The per-experience options that are NOT challenges: which map/dimension it runs
 * ({@link ExperienceWorldType}) and whether players keep their inventory when they die. The owner picks
 * them in the builder, they are stored on the experience row, and they travel into a running match as
 * {@code world:<id>} / {@code keepinv:<bool>} tokens in the mode args — which is what makes them survive
 * a server restart along with the rest of the persisted match state.
 *
 * <p>Owning both tokens here keeps ONE place that knows which mode args are options rather than
 * challenge ids, so {@link #stripArgs} can never fall out of sync with the encoders.</p>
 *
 * @param worldType     the map type / starting dimension
 * @param keepInventory whether death keeps items + XP (the default, and what the world gamerule is set to
 *                      across every dimension of the experience — see {@code ExperienceGame.applyKeepInventory})
 * @param hardcore      whether this is a HARDCORE world: hardcore hearts, hard difficulty, and one death
 *                      ends it for good — the world is marked dead, the player is returned to the lobby,
 *                      and re-entering only ever gets them a spectator's view of what they lost
 */
public record ExperienceSetup(ExperienceWorldType worldType, boolean keepInventory, boolean hardcore) {
  /** A normal terrain world where deaths keep your items — what a new experience starts as. */
  public static final ExperienceSetup DEFAULT =
      new ExperienceSetup(ExperienceWorldType.NORMAL, true, false);

  private static final String KEEP_PREFIX = "keepinv:";
  private static final String HARDCORE_PREFIX = "hardcore:";

  public ExperienceSetup {
    worldType = worldType == null ? ExperienceWorldType.NORMAL : worldType;
  }

  /** Back-compat: an experience with no hardcore choice recorded is a normal one. */
  public ExperienceSetup(ExperienceWorldType worldType, boolean keepInventory) {
    this(worldType, keepInventory, false);
  }

  public ExperienceSetup withWorldType(ExperienceWorldType worldType) {
    return new ExperienceSetup(worldType, keepInventory, hardcore);
  }

  public ExperienceSetup withKeepInventory(boolean keepInventory) {
    return new ExperienceSetup(worldType, keepInventory, hardcore);
  }

  public ExperienceSetup withHardcore(boolean hardcore) {
    return new ExperienceSetup(worldType, keepInventory, hardcore);
  }

  /**
   * How a player arrives in — and is kept in — an experience running these options.
   *
   * <p>Two rules, and they are the whole of what hardcore means to a player: a live hardcore world hands
   * its players hardcore hearts, and a world that has been LOST to a hardcore death can only ever be
   * spectated, enforced for as long as they stay rather than only checked at the door. Expressed here,
   * as a value derived from the options, so that both halves are decided in one place and the same
   * answer reaches every entry path, the respawn, and the watchdog that keeps them honest.</p>
   *
   * @param lost whether this experience has already been lost to a hardcore death
   */
  public EntryPolicy entryPolicy(boolean lost) {
    return HardcoreRule.entryPolicyFor(hardcore, lost,
        LocalizedText.of(MessageKey.EXPERIENCE_HARDCORE_SPECTATING));
  }

  /**
   * These options with hardcore forced on when one of the challenges demands it (Death Resets exists
   * only because of the stakes, so it cannot be run without them).
   *
   * <p>Applied wherever an experience's options are read for a real run, so a challenge cannot be
   * selected into a soft world by an owner who never touched the toggle. The owner's own choice is
   * otherwise untouched, and turning hardcore back off is refused for as long as the challenge is
   * selected — see {@code ExperienceService.setHardcore}.</p>
   */
  public ExperienceSetup forChallenges(List<String> challengeIds) {
    // Resolved through the shared demand rather than a local rule, so the setup, the menu tile and the
    // death handler cannot disagree about whether this experience is hardcore.
    boolean effective = ChallengeCatalog.hardcoreDemand(challengeIds).appliesTo(hardcore);
    return effective == hardcore ? this : withHardcore(effective);
  }

  /**
   * The world an experience running these challenges needs: the void flags the challenges demand, the
   * vanilla terrain preset the owner picked, and the hardcore flag.
   *
   * <p>One method rather than four lines at each call site, because there are now two places that create
   * this world — the first launch and a live regeneration — and a reset that built a different shape
   * from the launch would silently change the rules of a running experience.</p>
   */
  public WorldGeneration generationFor(List<String> challengeIds) {
    return new WorldGeneration(
        ChallengeCatalog.anyRequiresVoidWorld(challengeIds),
        ChallengeCatalog.anyRequiresVoidNether(challengeIds),
        worldType.terrain()
    ).asHardcore(hardcore);
  }

  /**
   * The mode args for a match: the challenge ids (plus the map's own challenge, for a generated map)
   * followed by one token per option.
   */
  public List<String> toModeArgs(List<String> challengeIds) {
    List<String> args = new ArrayList<>(worldType.toModeArgs(stripArgs(challengeIds)));
    args.add(KEEP_PREFIX + keepInventory);
    args.add(HARDCORE_PREFIX + hardcore);
    return args;
  }

  /** Reads both option tokens out of a mode-arg list; anything absent falls back to {@link #DEFAULT}. */
  public static ExperienceSetup fromArgs(List<String> modeArgs) {
    boolean keepInventory = DEFAULT.keepInventory();
    boolean hardcore = DEFAULT.hardcore();
    if (modeArgs != null) {
      for (String arg : modeArgs) {
        if (isKeepArg(arg)) {
          keepInventory = Boolean.parseBoolean(arg.substring(KEEP_PREFIX.length()).trim());
        } else if (isHardcoreArg(arg)) {
          hardcore = Boolean.parseBoolean(arg.substring(HARDCORE_PREFIX.length()).trim());
        }
      }
    }
    return new ExperienceSetup(ExperienceWorldType.fromArgs(modeArgs), keepInventory, hardcore);
  }

  /** The mode args with every option token removed, leaving only challenge ids. */
  public static List<String> stripArgs(List<String> modeArgs) {
    List<String> result = new ArrayList<>();
    if (modeArgs != null) {
      for (String arg : modeArgs) {
        if (!isOption(arg)) {
          result.add(arg);
        }
      }
    }
    return result;
  }

  /** Whether {@code arg} is one of the option tokens rather than a challenge id. */
  public static boolean isOption(String arg) {
    return ExperienceWorldType.isArg(arg) || isKeepArg(arg) || isHardcoreArg(arg);
  }

  private static boolean isKeepArg(String arg) {
    return arg != null && arg.toLowerCase(Locale.ROOT).startsWith(KEEP_PREFIX);
  }

  private static boolean isHardcoreArg(String arg) {
    return arg != null && arg.toLowerCase(Locale.ROOT).startsWith(HARDCORE_PREFIX);
  }
}
