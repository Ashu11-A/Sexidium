package com.sexidium.core.network.transfer;

import java.util.List;

/**
 * The proxy's half. Claim and complete are SEPARATE operations, and that is the whole design.
 *
 * <p>The previous consumer SELECTed pending routes and DELETEd them in the same breath, then attempted
 * the connect. Everything after the delete was best-effort with no record: a failed connect logged one
 * warning and lost the intent, a full or starting backend produced a red chat line and nothing, and a
 * proxy restart between the delete and the connect dropped the transfer entirely. The requesting node
 * was never told in any of those cases.</p>
 *
 * <p>Claiming takes a LEASE rather than deleting, so a proxy that dies mid-transfer leaves a ticket
 * another proxy reclaims when the lease lapses. Written this way from day one because "safe because
 * there is only one proxy" is an argument that collapses the moment there are two.</p>
 */
public interface TransferExecutor {

  /** Take up to {@code max} actionable tickets, leasing them to {@code executorId}. */
  List<TransferTicket> claim(String executorId, int max, long leaseMillis);

  /** Record a terminal outcome. The requesting node reads this; it is the acknowledgement. */
  void complete(String token, TransferState terminal, String detail);

  /** Hand a ticket back for another attempt (or expire it, once the attempt bound is reached). */
  void retry(String token, String detail);
}
