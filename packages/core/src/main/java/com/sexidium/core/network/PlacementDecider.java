package com.sexidium.core.network;

import com.sexidium.core.world.WorldPlacementGate;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The gate the world-acquisition path consults, and the one place a home is chosen.
 *
 * <p>Before this existed the gate called {@code claim()} directly, which assigns an unplaced world to
 * whichever node asks. Since the proxy sends every player to the lobby, the lobby was always the node
 * that asked — so every experience was homed on the lobby, three workers held nothing but their warm
 * pool, and the {@code EXPERIENCES} capability was never read by anything. The fix is small and lives
 * here: decide <em>where</em> first, then claim.</p>
 *
 * <p>Order matters more than anything else in this class, and it is strictly least-surprising-first:</p>
 *
 * <ol>
 *   <li><b>An idle world on shared storage is served by whoever is asking</b>, provided the asker can
 *       host experiences and the folder is really there. See {@link #adoptableHere}: this is the rule
 *       that makes every worker able to resume any world, and it is the one branch that deliberately
 *       overrides the existing home.</li>
 *   <li><b>An existing home wins.</b> Its chunks are on that machine. The only exception is a plan
 *       whose target never took it up and is not answering — nothing exists to lose.</li>
 *   <li><b>This node's own disk wins next.</b> A world nobody registered but which is right here must
 *       be claimed here; planning it elsewhere makes the other node generate an empty world under the
 *       same name while the real save sits unreachable on this one.</li>
 *   <li><b>Otherwise the planner chooses</b>, on capability and load.</li>
 * </ol>
 */
public final class PlacementDecider implements WorldPlacementGate {

  private final DbWorldLeaseAuthority placements;
  private final PlacementPlanner planner;
  private final NodeIdentity identity;

  public PlacementDecider(
      DbWorldLeaseAuthority placements,
      NodePlacementPlanner nodes,
      NodeIdentity identity,
      Predicate<String> localDisk) {
    this(placements, new PlacementPlanner(placements, nodes, identity, localDisk), identity);
  }

  /**
   * The networked shape, with the content gate wired.
   *
   * <p>{@code contentGate} answers "may this node adopt that world?" and {@code requirements} answers
   * "what does that world need of anyone?" — the two halves of the reader-side check that was missing
   * entirely. Without them a node one build behind adopts a world whose challenges it does not have
   * and generates normal terrain over an empty SkyBlock.</p>
   */
  public PlacementDecider(
      DbWorldLeaseAuthority placements,
      NodePlacementPlanner nodes,
      NodeIdentity identity,
      Predicate<String> localDisk,
      Predicate<String> contentGate,
      WorldContentRequirements requirements) {
    this(placements,
        new PlacementPlanner(placements, nodes, identity, localDisk, contentGate, requirements),
        identity);
  }

  public PlacementDecider(
      DbWorldLeaseAuthority placements, PlacementPlanner planner, NodeIdentity identity) {
    this.placements = placements;
    this.planner = planner;
    this.identity = identity;
  }

  /**
   * Plan, then claim. Two steps, in that order, and neither does the other's job.
   *
   * <p>What was here interleaved them: it read the row, decided in Java, and wrote — so "where does
   * this live" could not be answered without mutating the table, and the decision was made in a gap
   * another node could write into. The plan is pure ({@link PlacementPlanner}) and the claim is one
   * conditional UPDATE ({@link DbWorldLeaseAuthority#claim}), so two nodes asking at the same instant
   * cannot both be granted.</p>
   */
  @Override
  public Decision check(String worldKey) {
    java.util.Optional<DbWorldLeaseAuthority.Placement> existing = placements.lookup(worldKey);
    String planned = planner.plan(worldKey, existing);
    if (planned == null) {
      // Nenhum nó capaz de rodar este mundo, e nada gravado. Recusa SEM claim: gravar um
      // dono errado aqui é o que tornava o mundo inabrível para sempre.
      return Decision.ownedBy(null);
    }
    if (!identity.nodeId().equals(planned)) {
      // Not ours to open. Recording the plan is what makes two nodes planning the same world at the
      // same instant converge: the primary key means one insert wins and the loser reads the winner.
      placements.claimOn(worldKey, DbWorldLeaseAuthority.KIND_PERSISTENT, null, planned);
      boolean busy = existing.map(row -> row.leaseHeld(System.currentTimeMillis())).orElse(false);
      return busy ? Decision.busyOn(planned) : Decision.ownedBy(planned);
    }

    com.sexidium.core.world.WorldKey key =
        com.sexidium.core.world.WorldKey.tryParse(worldKey).orElse(null);
    if (key == null) {
      // A non-experience key (a temp or lobby world) still goes through the legacy claim, which is
      // where KIND_TEMP and KIND_LOBBY keep their current semantics.
      return placements.claimOn(worldKey, DbWorldLeaseAuthority.KIND_PERSISTENT, null, planned)
          instanceof DbWorldLeaseAuthority.ClaimResult.Granted
          ? Decision.allow() : Decision.ownedBy(planned);
    }

    com.sexidium.core.world.ClaimOutcome outcome = placements.claim(key, null);
    if (outcome instanceof com.sexidium.core.world.ClaimOutcome.Granted granted) {
      lastClaim = granted.claim();
      return Decision.allow();
    }
    if (outcome instanceof com.sexidium.core.world.ClaimOutcome.Elsewhere elsewhere) {
      return elsewhere.busy()
          ? Decision.busyOn(elsewhere.nodeId())
          : Decision.ownedBy(elsewhere.nodeId());
    }
    return Decision.ownedBy(planned);
  }

  /**
   * The claim the most recent successful {@link #check} minted, or null.
   *
   * <p>A seam, not a design: the world layer needs the fence, and {@link WorldPlacementGate} still
   * answers with a boolean-shaped {@code Decision}. Stage 6 replaces the gate with the authority
   * itself and this goes away.</p>
   */
  private volatile com.sexidium.core.world.WorldClaim lastClaim;

  public com.sexidium.core.world.WorldClaim lastClaim() {
    return lastClaim;
  }
}
