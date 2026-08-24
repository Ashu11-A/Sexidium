package com.sexidium.core.network;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * WHERE a world should live. Pure: it reads, it decides, it mutates nothing.
 *
 * <p>Split out of {@code PlacementDecider.check}, which interleaved the decision with the claim so
 * that "where does this live" could not be answered without taking out a lease you did not want. The
 * split is what makes {@link com.sexidium.core.world.ExperienceLocator} possible at all, and it is
 * what lets every one of the seven placement guarantees be tested without a database race in the way.</p>
 *
 * <p>Order matters more than anything else here, and it is strictly least-surprising-first:</p>
 *
 * <ol>
 *   <li><b>An idle world on shared storage is served by whoever is asking</b>, provided the asker can
 *       host experiences and the folder is really there. This is the rule that makes every worker able
 *       to resume any world, and the one branch that deliberately overrides the existing home.</li>
 *   <li><b>An existing home wins.</b> Its chunks are on that machine — or, on a shared tree, its node
 *       is alive and moving the row would only churn. The one exception is a plan whose target never
 *       took it up and is not answering: nothing exists to lose.</li>
 *   <li><b>The lineage home.</b> A regenerated world is not a new world; Death Resets builds the
 *       successor alongside its predecessor and swaps the folders, so the two must share a disk.</li>
 *   <li><b>Otherwise the planner chooses</b>, on capability and load.</li>
 * </ol>
 */
public final class PlacementPlanner {

  private final DbWorldLeaseAuthority placements;
  private final NodePlacementPlanner nodes;
  private final NodeIdentity identity;
  private final Predicate<String> localDisk;
  /**
   * "Can this node actually run that world?" — {@code NetworkService.canHostContent}.
   *
   * <p>A seam and not a dependency, exactly like {@link #localDisk}: the placement layer must not
   * import the experience layer, and an un-wired platform has to keep planning as it does today.
   * Defaults to allow-all for that reason.</p>
   */
  private final Predicate<String> contentGate;
  /**
   * What a world needs of ANY node — the reader-side half, used when the choice is a peer's.
   *
   * <p>{@link #contentGate} answers "may <em>I</em> adopt this?"; this answers "which of my peers
   * could?". Both are needed: a world refused here and then planned onto a peer that is equally
   * incapable is a world nobody opens.</p>
   */
  private final WorldContentRequirements requirements;

  public PlacementPlanner(DbWorldLeaseAuthority placements, NodePlacementPlanner nodes,
      NodeIdentity identity, Predicate<String> localDisk) {
    this(placements, nodes, identity, localDisk, worldKey -> true, WorldContentRequirements.NONE);
  }

  public PlacementPlanner(DbWorldLeaseAuthority placements, NodePlacementPlanner nodes,
      NodeIdentity identity, Predicate<String> localDisk, Predicate<String> contentGate,
      WorldContentRequirements requirements) {
    this.placements = placements;
    this.nodes = nodes;
    this.identity = identity;
    this.localDisk = localDisk == null ? worldKey -> false : localDisk;
    this.contentGate = contentGate == null ? worldKey -> true : contentGate;
    this.requirements = requirements == null ? WorldContentRequirements.NONE : requirements;
  }

  /** Which node should host {@code worldKey}, given whatever is currently recorded about it. */
  public String plan(String worldKey, Optional<DbWorldLeaseAuthority.Placement> existing) {
    if (existing.isPresent() && adoptableHere(worldKey, existing.get())) {
      return identity.nodeId();
    }
    if (existing.isPresent() && !replannable(existing.get())) {
      return existing.get().nodeId();
    }
    Optional<String> lineage = existing.isPresent() ? Optional.empty() : lineageHome(worldKey);
    if (lineage.isPresent()) {
      return lineage.get();
    }
    // An UNREGISTERED world whose folder is right here, on a node allowed to host worlds.
    //
    // The capability check is what makes this safe on a shared tree, and it is the whole difference
    // from the branch this replaces: that one was `!sharedStorage && localDisk.test(worldKey)`, so on
    // the deployed model it never ran, and had it run it would have matched on EVERY node including
    // the lobby. Guarded on EXPERIENCES it does the job it was written for — planning a world that is
    // physically here onto some other node makes that node find no folder and generate an empty one
    // under the same name, while the real save sits unreachable — without handing the lobby the park.
    if (existing.isEmpty() && identity.can(NodeCapability.EXPERIENCES) && localDisk.test(worldKey)
        && contentGate.test(worldKey)) {
      return identity.nodeId();
    }
    // No capable node alive is a real answer, not a reason to host it here anyway: this node may be
    // a lobby the operator excluded from hosting worlds. Falling back to self creates the folder in
    // the one place it must not be, and folders do not move by themselves.
    Optional<String> chosen = nodes.choose(NodeCapability.EXPERIENCES, requiredCodes(worldKey));
    if (chosen.isPresent()
        && (!identity.nodeId().equals(chosen.get()) || contentGate.test(worldKey))) {
      return chosen.get();
    }
    // Either nobody answered, or the only answer was THIS node and this node cannot run the world.
    //
    // The second case is the one worth spelling out: `choose` is generous about peers on purpose — a
    // peer that has published no content set is unknown, not empty, or the first roll of an untagged
    // fleet would have no candidates at all — and its last resort is "well, me". About OURSELVES
    // there is nothing to be generous with: we know exactly what this build can run. So a world we
    // cannot run stays where it is rather than being moved onto a node that would open it wrong.
    if (existing.isPresent()) {
      return existing.get().nodeId();
    }
    // Nada gravado, ninguém capaz, e este nó também não pode rodar o mundo. O fallback para
    // SI era o caso que o comentário logo acima proíbe em palavras e a linha executava: num
    // lobby (sem EXPERIENCES) ele gravava uma linha de placement apontando para o lobby, o
    // door guard recusava abrir, e `unclaim` PRESERVA `node_id` de propósito -- então
    // `replannable` nunca volta a valer, porque o lobby está sempre vivo. O mundo ficava
    // inabrível na rede inteira até alguém apagar a linha na mão.
    //
    // `null` é "não há nó capaz", e o decisor o trata como recusa SEM gravar claim nenhum.
    // Recusar agora é recuperável -- basta um worker capaz aparecer; gravar a linha errada
    // não é.
    if (identity.can(NodeCapability.EXPERIENCES) && contentGate.test(worldKey)) {
      return identity.nodeId();
    }
    return null;
  }

  /** Never throws: a requirements probe that fails degrades to "unconstrained", never to a refusal. */
  private java.util.List<String> requiredCodes(String worldKey) {
    try {
      java.util.List<String> codes = requirements.requiredCodes(worldKey);
      return codes == null ? java.util.List.of() : codes;
    } catch (RuntimeException unavailable) {
      return java.util.List.of();
    }
  }

  /**
   * The node already holding an earlier generation of this same run, if any.
   *
   * <p>Every generation of one run is the same logical world and belongs on one disk. Walks back from
   * the immediate predecessor to the original: the predecessor is the usual answer, and the original
   * covers a lineage whose middle generations have already been cleaned up.</p>
   *
   * <p><b>Placement rows only.</b> This used to end with
   * {@code if (localDisk.test(sibling)) return identity.nodeId();} — no shared-storage check and no
   * capability check. On the deployed topology the LOBBY sees every folder in the network through the
   * symlink, so that line planned a regenerated experience onto the lobby: precisely the bug this
   * class exists to prevent, and with zero coverage, because every lineage test constructed the
   * decider with {@code sharedStorage = false}.</p>
   */
  private Optional<String> lineageHome(String worldKey) {
    int generation = com.sexidium.core.world.WorldNaming.generationOf(worldKey);
    if (generation <= 0) {
      return Optional.empty();
    }
    String base = com.sexidium.core.world.WorldNaming.baseExperienceKey(worldKey);
    for (int previous = generation - 1; previous >= 0; previous--) {
      String sibling = com.sexidium.core.world.WorldNaming.experienceKeyForGeneration(base, previous);
      Optional<DbWorldLeaseAuthority.Placement> placement = placements.lookup(sibling);
      if (placement.isPresent() && !replannable(placement.get())) {
        return Optional.of(placement.get().nodeId());
      }
    }
    return Optional.empty();
  }

  /**
   * Whether <em>this</em> node may take an idle world over simply because it is the one being asked.
   *
   * <p>This is the answer to two requirements that look opposed. "Any worker must be able to resume
   * any world" wants the home free to change; "stop the world bouncing between workers" wants it not
   * to. They are only compatible if the choice is <b>stable</b>, and re-planning by load is exactly
   * the unstable choice — a fresh sort on every entry over inputs that change on every entry, observed
   * live as one world announced on worker-2 and, seconds later, on worker-1.</p>
   *
   * <p>So there is no sort here: <b>whoever asks, serves</b>. The player is already connected to some
   * worker; that worker opens the world and nobody is transferred. Each condition is an invariant:</p>
   *
   * <ul>
   *   <li>{@code EXPERIENCES} — keeps the lobby out. It asks constantly (every player passes through
   *       it) and would otherwise collect every world in the network.</li>
   *   <li><b>no live lease</b> — a world somebody has open is never contested. Two servers writing one
   *       set of region files is data loss, not a conflict to resolve.</li>
   *   <li>{@code localDisk} — the folder must actually exist, so "assume it" can never mean
   *       "regenerate it empty".</li>
   *   <li>{@code KIND_PERSISTENT} — a temp match world belongs to the node running the match.</li>
   *   <li><b>content</b> — this node must implement every challenge the world uses. A build one
   *       version behind answers "no, this does not need a void world" to the four world-shaping
   *       questions and generates NORMAL terrain over what is supposed to be an empty SkyBlock:
   *       silent, permanent and reachable during any roll. Refusing to adopt leaves the world shut
   *       until a node that has the content opens it, which is strictly better than opening it
   *       <em>wrong</em>.</li>
   * </ul>
   *
   * <p><b>The absence of a load sort here is a pin, not an omission.</b> Adding one re-plans the home
   * on every entry over inputs every entry changes — observed live as one world announced on worker-2
   * and, seconds later, on worker-1. The content gate above is a <em>filter</em>, deliberately: it can
   * only ever say no, so it cannot reorder anything.</p>
   */
  private boolean adoptableHere(String worldKey, DbWorldLeaseAuthority.Placement placement) {
    if (!identity.can(NodeCapability.EXPERIENCES)) {
      return false;
    }
    if (placement.leaseHeld(System.currentTimeMillis())) {
      return false;
    }
    if (!DbWorldLeaseAuthority.KIND_PERSISTENT.equals(placement.kind())) {
      return false;
    }
    if (!contentGate.test(worldKey)) {
      return false;
    }
    return localDisk.test(worldKey);
  }

  /**
   * Whether a placement may be re-planned onto a different node.
   *
   * <p>A materialised world with no live lease STAYS PUT while the node holding it is alive. "Safe"
   * is the wrong question: a lease lapses within seconds of the last player leaving, so idle is the
   * NORMAL state of an experience between sessions, and re-planning by load on every entry re-sorts
   * the world onto whichever worker is quietest at that instant. That is churn, not balance — every
   * hop costs a transfer and buys nothing, because there is no load to spread in a world nobody is
   * in. The balancing that pays is choosing where a NEW world is born, and taking a world over when
   * the node holding it is genuinely gone.</p>
   */
  private boolean replannable(DbWorldLeaseAuthority.Placement placement) {
    long now = System.currentTimeMillis();
    if (placement.leaseHeld(now)) {
      return false;
    }
    // The isAlive check is load-bearing: without it EVERY node a player passes through recomputes the
    // planner with the load their own arrival just changed, picks a different worker, and transfers
    // them again. The player ping-pongs, the world never opens, and they end up back in the lobby.
    return !nodes.isAlive(placement.nodeId());
  }
}
