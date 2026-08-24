package com.sexidium.core.world;

/**
 * Whether a world request may CREATE the world it names, or only load one that already exists.
 *
 * <p>Invariant I5 in one type. "No folder on disk ⇒ generate one" used to be an implicit
 * fall-through — {@code backendAcquire(request, true)}, with the {@code true} hard-coded — so every
 * caller that merely wanted to RESUME an experience carried the authority to manufacture a brand-new
 * one. On a shared tree that is the most expensive default in the system: a node that cannot see a
 * folder for any reason (a stale key, a slow mount, a lineage pointer that drifted, a peer mid-reset)
 * generates an empty world under a name a player's save answers to, and the warm pool makes it
 * instant and silent — no terrain generation to notice, no error to read.</p>
 *
 * <p>So the two intents are separate values, and a caller has to say which it means.</p>
 */
public enum CreatePolicy {
  /** Load what is there. If nothing is there, FAIL — do not invent a world. */
  LOAD_ONLY,
  /** Load what is there, and generate it when genuinely absent everywhere. */
  CREATE_IF_MISSING
}
