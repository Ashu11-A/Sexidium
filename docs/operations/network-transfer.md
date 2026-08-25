# Network transfer, world placement and the fence

How a player gets from the Enter button to the worker that holds their world, and what stops two
workers from holding it at once.

This page exists because the previous design had neither guarantee, and both failures were observed
on the live network: a player ping-ponged lobby ⇄ worker eight times in forty-five seconds, and the
only thing standing between two JVMs and one set of region files was a lease nobody checked.

---

## 1. Why this is hard here

On the network, `world/dimensions/experiences` is a **symlink into one shared tree**. Every node sees
every experience folder, identically. That is deliberate — it is what lets any worker adopt any
experience, which is the load balancing.

Minecraft does not help:

> `session.lock` belongs to the **level root** (`world/`), which is node-local. It does not cover a
> keyed dimension folder. There is no `session.lock` inside a shared experience world, and there
> cannot be one.

So "only one node may open this world" is **entirely our invariant to enforce**. Nothing on the
filesystem will catch us if we get it wrong; the failure mode is silent region-file corruption.

---

## 2. Placement: one row is the authority

`world_placements` has one row per experience world, keyed by `WorldKey`. That row — not the disk,
not Bukkit's loaded-world set, not a local match map — answers *where does this live and who may open
it*. The disk cannot answer it, because on a shared tree "the folder is here" is true on every node
at once and therefore proves nothing.

```
                    ┌──────────────────────────────────────────────────┐
                    │                    FREE                          │
                    │  no holder; folder may or may not exist          │
                    └───────┬──────────────────────────────────────────┘
                claim() CAS │  grants a fresh, unique fence
                            ▼
                    ┌──────────────┐   unclaim(claim)        ┌────────┐
                    │   RESERVED   │ ──────────────────────▶ │  FREE  │
                    │ claimed, not │   open failed; nothing  └────────┘
                    │  yet open    │   was ever created
                    └──────┬───────┘
             confirmOpen() │
                           ▼
                    ┌──────────────┐   release(claim)        ┌────────┐
                    │    LOADED    │ ──────────────────────▶ │  IDLE  │
                    │  renew() ok  │   clean unload          └───┬────┘
                    └──────┬───────┘                             │ claim() by any
             renew() FALSE │                                     │ EXPERIENCES node
                           ▼                                     ▼
                    ┌──────────────┐  evacuate + unload    (back to RESERVED)
                    │   EVICTING   │ ─────────────────────▶  FREE
                    │ writes FROZEN│  within one lease period
                    └──────────────┘
```

`RESERVED` exists so that a claim can be **rolled back**. A world is claimed before it is opened, and
if the open fails the row must go back to `FREE` — otherwise the row looks like a materialised home
for a world that does not exist, and every later entry is routed to a node with no folder, which
generates a fresh one on top.

## 3. The fence

`world_placements.fence` is a **random non-zero token minted per grant**. Not a counter: fencing needs
uniqueness per grant, not ordering, and a random token needs no read-back (MariaDB has no
`UPDATE … RETURNING`) and no clock.

Claiming is one statement, so the race is settled by the database:

```sql
UPDATE world_placements
   SET node_id = ?, node_epoch = ?, fence = ?, state = 'RESERVED',
       players = 0, lease_expires_at = ?, updated_at = ?
 WHERE world_key = ? AND lease_expires_at <= ?
```

Every subsequent operation — `renew`, `confirmOpen`, `release`, `unclaim` — carries
`AND node_id = ? AND node_epoch = ? AND fence = ?` and **reports rows-affected**.

That last clause is the whole design:

> **A `renew` that affects zero rows is an eviction notice.**

Before, a dispossessed holder's renew was guarded on `node_id` alone, matched nothing, and was
discarded silently — so a node that stalled for 31 seconds, lost its world to the reaper, and then
woke up would carry on writing into region files another node had opened, forever, without ever
learning anything had happened. Now it finds out on its next heartbeat.

**On eviction the holder must, within one lease period:** freeze writes (the debounced state flush,
autosave and player snapshots all short-circuit), evacuate its players, and unload **with
`save = false`**. Saving is wrong here on purpose: another node may already be writing those files,
and flushing our now-stale chunk cache over its writes is strictly worse than losing a few seconds.

> **NOT YET IMPLEMENTED — the filesystem backstop.** The design calls for
> `<folder>/sexidium/holder.json` recording `{nodeId, epoch, fence, expiresAt}` as a second,
> **advisory** opinion, so that a database/clock disagreement surfaces as a refusal naming a node
> rather than as silent corruption. It does not exist. **Today the database lease is the only mutual
> exclusion in the system**, with no defence in depth: any window in which the table is wrong — a DB
> outage, clock skew between hosts, a stalled node — is two JVMs writing one set of region files
> rather than a caught error. Do not read the fence as belt-and-braces; it is currently the belt.

### Timing

| Setting | Value | Why |
|---|---|---|
| `network.heartbeat-seconds` | 5 | how often a node proves it is alive and renews its leases |
| `network.world-lease-seconds` | 15 | must be ≥ 3 heartbeats (survive two lost beats) … |
| `network.node-timeout-seconds` | 30 | … and **strictly less** than this |

The relationship is asserted at boot and the node refuses to enable if it is violated. It previously
was: a **60 s** lease against a **30 s** node timeout, meaning a reaper could declare a node dead and
reassign its world while the lease it was reasoning about was still live.

A reaper may only clear leases that have **already expired** (`AND lease_expires_at <= ?`). It can
never shorten a live one — that is what turned a slow node into a second writer.

---

## 4. Transfer: one addressed ticket per player

`player_transfers` has **at most one live row per player** — the primary key enforces it.

```
mint (source node)  →  PENDING  →  claim (proxy)  →  DISPATCHED  →  LANDED
                                                                 ↘  FAILED → retry (bounded)
                                                                 ↘  EXPIRED
```

A ticket carries `(token, player, target_node, target_epoch, reason, world_key, attempts)`.

Four properties, each of which was missing and each of which caused a live failure:

1. **Addressed.** The destination claims a ticket only if `target_node` is itself *and* `target_epoch`
   matches its current epoch. The old arrival query filtered on player alone — no node, no epoch, no
   `ORDER BY`, no `LIMIT` — so it could match a handoff meant for a different node, claim it, find no
   match, and swallow the arrival silently.
2. **Idempotent.** Re-requesting the same `(target, reason, world_key)` inside the TTL returns the
   *same* ticket and moves nobody. Previously the newest request simply overwrote the old one, so a
   repeated intent was indistinguishable from a first one.
3. **Acknowledged.** The proxy **claims** a ticket, attempts the connect, then **completes** it with a
   terminal state. Claim and complete are separate so a proxy that dies mid-transfer leaves the ticket
   reclaimable — and so a second proxy is safe. The old consumer deleted the row *before* it tried to
   connect, so every failure after that point vanished and the requester was never told.
4. **Bounded.** A circuit breaker refuses more than 3 transfers of one player to one node per 60 s
   (and 6 in any direction). On a trip it logs `SEVERE` with both node ids and increments
   `loopBreakerTrips`. This bounds *any* loop cause, including ones not yet identified.

Experience transfers do **not** share `match_handoffs` with minigames. Smuggling them in behind a
string prefix is what made an unrelated, never-deleted minigame row able to hijack an experience
arrival.

---

## 5. Reconciliation

At boot each node compares its disk against the placement table. It **never deletes a world folder**;
the only destructive action available to it is dropping a row proven to reference nothing in either
of the other two sources of truth.

One rule is worth stating on its own, because violating it destroys the record of where a world lives:

> **An unreadable disk is not an empty disk.** If the scan fails, the pass is skipped entirely and
> reports `diskUnreadable` — it is never reported "consistent". Reasoning "no folder here" from a scan
> that failed would fire the drop branch on every row homed on this node.

---

## 5b. Owner actions: `experience_commands`

An owner reaches the manage menu from the **lobby**, and the lobby never holds an experience world.
Live edits (end-match, challenges, keep-inventory, hardcore) are published to whichever node has the
world **open** and are fire-and-forget — a message lost against a world nobody is playing costs
nothing. **Delete is not that.** It touches the folder, so it has to reach the node that *holds* the
folder — `world_placements.node_id`, the durable assignment, whether or not the lease is live — and
the owner has to be told what actually happened.

So a delete is a row before it is a message:

| Step | Where |
|---|---|
| the owner clicks Delete | lobby writes an `experience_commands` row `PENDING`, then rings the bus (`experience.command`, carrying `req=<id>;from=<node>`) |
| the holder acts | conditional `UPDATE ... state='PENDING' → 'RUNNING'` — *that* is the dedupe, so a replayed or double-delivered message runs once — then end match, delete folder, drop the `experiences` and `experience_players` rows |
| the answer | `state='DONE'`/`'FAILED'` plus one advisory `experience.command.result` message addressed back to the requester |
| nobody answered | the requester re-reads the row on a one-second tick, and after 8s tells the player *queued*, not *deleted*; the row stands for 24h so a worker that was restarting still runs it when it reads its own pending rows at boot |

Two consequences worth knowing. A node without `NodeCapability.EXPERIENCES` **never** runs a delete
locally — that fallback is what used to make the lobby say "Experience deleted." about a world
invariant I3 had just refused to touch. And a world with *no* placement row anywhere has no folder on
any disk (a world only gets a row by being opened or created), so the rows that name it are the whole
delete and the requester drops them itself.

That second one is a licence to destroy rows, so it is only ever granted on a **positive** answer.
`ExperienceLocator.home()` exists to say "I could not read the table" out loud, because `locate()`
cannot: a failed read and a world nothing has recorded both come back empty, and one lock-wait timeout
on the shared MariaDB would otherwise have the lobby forget every row naming a world sitting intact on
a worker's disk. The same distinction separates *unrouted* from *refused*: a worker that holds the
world and has merely entered `DRAINING` **is** its recorded home, and answers refused.

Three more things the row has to survive, none of which the bus can help with:

- **The address goes stale.** `target_node` is frozen when the owner clicks; `world_placements.node_id`
  is not. A worker down for a rolling update can come back to find another worker adopted its idle
  world and opened it (`claim()` rewrites `node_id`). So before running a folder op the target
  **re-reads the home** and, if the world moved, re-addresses the row rather than deleting a folder
  somebody else has open — F-A2 again, relocated from the lobby to a stale worker.
- **A claim can be abandoned.** A node killed mid-delete leaves its row `RUNNING` forever, which the
  drain and the expiry sweep both used to skip. A claim stamps `updated_at`, so a `RUNNING` row older
  than `RECLAIM_AFTER_MILLIS` (5 min) is takeable again, and the expiry sweep covers `RUNNING` too.
- **A target that cannot act must not consume the request.** Draining, or unable to read the placement
  table, leaves the row `PENDING` for the next drain. Claiming it would end the live match, be refused
  by invariant I3's door, and burn the request into a terminal `FAILED` nothing retries — while the
  owner had already been told the request stands.

**Rolling-update note.** Upgrade workers before the lobby. An old-build worker applies a delete but has
no `experience.command.result` to answer with, so the new-build lobby reports *queued* for something
that already happened and the still-`PENDING` row re-runs the delete once that worker is upgraded.
`applyDelete` is idempotent, so nothing is harmed — but the owner is told the wrong thing.

---

## 5c. Shared maps: the row is the truth, `experience.updated` is the doorbell

A map shared with friends or made public is read by players who are **not** its owner and, on a
network, are usually not on the owner's node either. Nothing about that is cached: `ExperienceManager`
holds no state, every listing (`byOwner`, `byOwners`, `publicExperiencesExcluding`, `byWorld`) is a
query, and the world itself is *referenced*, never copied — `world/dimensions/experiences` is one
shared tree behind a symlink, and the placement lease is what stops two nodes writing it. A non-owner
who enters is routed to the node holding the folder (§2), so they play the owner's world, not a copy
of it.

What went stale was the two consumers that read a row **once** and then stopped looking:

| Consumer | Read once at | Now |
|---|---|---|
| the running world (`ExperienceGame`) | start, from the mode args | `ExperienceService.reconcileLive` compares `experiences.updated_at` against the stamp the game last applied, and re-applies challenges / keep-inventory / hardcore when it has moved. Called on every entry, so a visitor cannot walk into last week's challenge set, and on every announcement, so a player already inside sees the edit. |
| an open menu (browse / My Experiences / manage) | open | `MenuService.refreshExperienceScreens()` redraws the screen the viewer is *still* looking at (`MenuAdapter.isOpen`). |

Both are driven by `NetworkBus.Topics.EXPERIENCE_UPDATED` (declared for a long time, published by
nobody until now): every owner action publishes `field=<what>;by=<node>` keyed by the experience id,
and `SexidiumCore` subscribes it. The payload deliberately does **not** carry the new value — a
receiver that trusted it would be holding a second copy of the truth, and two changes crossing would
leave the two copies disagreeing — so every receiver re-reads the row. The change is also applied
**locally** by the publisher, because the bus never echoes to its author and the author is the lobby,
which is exactly where the other players' menus are; `by=` is what stops a node acting on its own
echo when the standalone in-process bus delivers it back.

Adding a publisher and a subscriber to an existing topic moves neither `Protocol.VERSION` nor the
wire digest — the grammar is new, but the topic is not, and a node that has never heard of the field
names simply re-reads a row it would have read anyway.

---

## 6. Operating it

`/sx admin net` is the one place to look:

| Command | Answers |
|---|---|
| `nodes` | who is alive, with heartbeat age |
| `placements [filter]` | who holds what, with **fence** — two rows for one run sharing a fence, or a `LOADED` row with `fence=0`, are visible problems |
| `locate <experienceId>` | where a specific experience lives right now — **currently broken**: `world_placements.experience_id` stores the world *base* (`death_resets_002a7816`), not the experience id, so this matches nothing |
| `transfers` | tickets in flight, with `attempts` — this is what tells a bounce apart from a slow connect |
| `stats` | transfers/min, failures, `loopBreakerTrips`, `evictions` |
| `evict <worldKey>` | clear a row stuck `LOADED`; the holder evacuates on its next refused renew — nothing is yanked out from under a player |

---

## Keeping this current

Edit this page when the placement state machine, the fence contract, the transfer ticket lifecycle or
the timing invariants change. The state diagram in §2 and the timing table in §3 are the two things
most likely to drift; both have code that enforces them (`DbWorldLeaseAuthority`, `NetworkSettings`),
so if this page and the code disagree, the code is right and this page is a bug.

Related: [architecture.md](../architecture/overview.md) · [deployment.md](deployment.md) · [experiences.md](../gameplay/experiences.md)
