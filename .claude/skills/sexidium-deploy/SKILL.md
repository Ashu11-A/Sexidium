---
name: sexidium-deploy
description: Deploy, update, scale, roll back and validate the Sexidium Minecraft network running on Portainer (Velocity proxy + Paper lobby/workers + MariaDB). Use whenever the task involves shipping code to the live network, restarting or scaling nodes, a rolling update, a build rollback, reading node logs, or diagnosing a deploy that went wrong. Covers `scripts/remote.sh` and the `pipeline` subcommands.
allowed-tools: Bash, Read, Grep, Glob
---

# Deploying Sexidium on Portainer

The full reference is [`docs/operations/deployment.md`](../../../docs/operations/deployment.md) (13 sections). This skill is
the operational layer: which command to reach for, what will bite you, and what must never be done
without asking. **When the two disagree, the code wins — check it.**

## Read this before your first command

Deployment here does **not** mean pushing an image. The containers all run one stock
`eclipse-temurin:25-jdk` image; a single `SX_NODE` variable decides whether a container is the proxy,
the lobby, a worker, the DB or the one-shot `init` provisioner. Code ships as **sources rsync'd into
a build volume**, compiled on the host by the `init` container, and loaded by each node from a jar in
a build store. So "deploy" = sync sources → provision (build) → restart nodes, and **never** a
`docker pull`.

## Entry point

Everything goes through one command. There is no `docker` and no `kubectl` in this workflow.

```bash
scripts/remote.sh <command>          # thin env loader; logic is Python stdlib in scripts/remote/
scripts/remote.sh --help             # the full list
```

Credentials live in `scripts/remote.env` (gitignored, `chmod 600`), or in the environment, which
**wins over the file**:

```bash
SX_PORTAINER_KEY=ptr_… scripts/remote.sh status
```

Preflight before anything else — it answers "is the key good and can I reach the daemon":

```bash
scripts/remote.sh version
```

### The two Portainer endpoints

| Where | URL | Use |
|---|---|---|
| Local (same LAN) | `http://192.168.1.72:9000` | default; fast |
| Via FRP/NAT | `http://150.230.84.206:26152` | from outside the LAN |

Same Portainer, same stack, same API key — only `SX_PORTAINER_URL` changes. Prefer local when
reachable; the FRP path adds latency that matters on the long-running provision call (`SX_TIMEOUT`
defaults to 900s for exactly that reason).

### The game port is NOT 25565 from outside

| Where | Address |
|---|---|
| On the host / LAN | `:25565` (what the proxy publishes) |
| Public, via FRP | **`150.230.84.206:26001`** |

`scripts/remote.env` sets `SX_MC_PORT=25565` and `SX_MC_HOST` is unset, so it derives the host from
`SX_PORTAINER_URL`. Run `status` from outside the LAN and it pings `150.230.84.206:25565`, which
nothing listens on — you get a **`mc.ping FAIL … Connection refused` that is an artifact of where you
are standing, not an outage**. Every other check will pass, which is the tell. From off-LAN, override:

```bash
SX_PORTAINER_URL=http://150.230.84.206:26152 SX_MC_PORT=26001 scripts/remote.sh status
```

Then `mc.ping` reports the real version and player count. Note the FRP hop also masks player IPs
(logins arrive as `172.18.0.1`), which is why session-per-IP and login rate-limiting are hollow.

## Which command

| You want to | Run | Disconnects players? |
|---|---|---|
| Stand the network up from nothing | `up` | n/a |
| Ship code, nobody online / don't care | `update` | **YES, all of them** |
| Ship code with players online | `pipeline deploy` | No — that is its whole purpose |
| See what each node is running | `pipeline status` | No |
| Undo a bad build | `pipeline pin <node> <build>` | That node only |
| Check the network is healthy | `status` | No |
| Read a node's log | `logs <node> -f` | No |
| Add/remove a worker | `scale up` / `scale down` | No (scale-down parks an idle tail node) |

`update` is the everyday route and it is **not** graceful: it restarts nodes underneath whoever is
online. `pipeline deploy` is the one that drains each node, verifies the new build, and can reverse
itself. Reach for `pipeline deploy` whenever players might be connected.

## The rolling update

```bash
scripts/remote.sh pipeline deploy                 # canary = last worker, then the rest, then proxy
scripts/remote.sh pipeline status                 # lock, current run, per-node pins
scripts/remote.sh pipeline resume                 # continue an interrupted run
scripts/remote.sh pipeline abort                  # release the lock, reverting what was repinned
scripts/remote.sh pipeline builds                 # what the build store holds
```

Per node the sequence is: announce (T-60/-30/-10s in-game warnings) → drain → repin the jar →
restart → verify (boot banner + battery of assertions, **while still drained**, so a failure never
exposes a player to the bad build) → reclaim. A failed verify rolls that node back to its previous
build and converges the ones already rolled.

Deliberate refusals — these are correct behaviour, not bugs to work around:

- **It refuses to roll the only lobby.** A single lobby cannot be updated without dropping its
  players. Scale to 2 lobbies, or pass `--allow-lobby-disconnect` and own the outage.
- **It refuses to restart the only proxy** unless the Velocity jar actually changed, and then only
  with `--maintenance-window`. One proxy = the whole network drops.
- **`needs-human`** in the lock means a rollback itself failed and the network was left N-1 and
  serving. Read `pipeline status`, fix it by hand, then `pipeline abort --force-unlock --yes`.
  Do not force past this state.
- **`resume` refuses rather than forces.** It declines a `needs-human` lock, a lock still being
  heartbeaten by a live run, and a run whose target build no longer matches `LATEST`. All three are
  deliberate; read the message instead of working around it.

`converge` and `abort` unwind nodes that are already back in service, so they drain and warn before
stopping anything. `--skip-drain` exists only for a node whose API no longer answers — using it on a
reachable node disconnects its players.

## Hard rules

1. **Never run a mutating command against the live network without explicit human approval** —
   `up`, `update`, `down`, `restart`, `stack`, `provision`, `pipeline deploy`, `scale`. Reading
   (`status`, `logs`, `pipeline status`, `builds`, `version`, `secrets`) is always fine. When in
   doubt, propose the command and wait.
2. **Never rotate `forwarding_secret`.** It is what authenticates backends to the proxy. Rotating it
   without re-provisioning every node makes every join fail with *"This server requires you to
   connect with Velocity."*
3. **`SX_NODES` is append-only.** Ports are positional (`base + index`), so removing a node from the
   middle renumbers every node after it onto its neighbour's port. `scale down` therefore **parks**
   (stops) the tail node and leaves the compose intact. There is **no implemented command that
   removes a node** — a comment in `scale.py` refers to a `pipeline decommission` that does not
   exist. Real removal is a manual, careful edit of `SX_NODES` plus the node's volume, and only ever
   from the tail.
4. **Never print secret values.** `secrets` shows fingerprints; `--show` additionally demands
   `--yes`. The API key travels only in the `X-API-Key` header — never in argv (visible in `ps`),
   never in a query string (it lands in Portainer's access log).
5. **A leaked Portainer key is a host compromise.** It creates, stops and deletes any container on
   the host. Revoke at Portainer → My account → Access tokens and issue a new one.

## Gotchas that have actually cost time here

- **Syncing sources by tar cannot delete files.** A file removed locally survives on the host and
  will fail the build. Clear the target (e.g. `/srv/build/repo/packages`) before re-uploading.
- **Memory is the real scaling ceiling.** ~20 GiB of limits are committed on a 31 GiB host, so the
  autoscaler will correctly **refuse** to add a worker on the current configuration. If a scale-up
  is declined, check memory before assuming the autoscaler is broken.
- **Mixed builds during a roll are expected.** `status`'s `build.pins` check reporting *"builds
  MISTOS"* mid-`pipeline deploy` is the system working, not a fault.
- **`update` sits behind the same lock as the pipeline.** Running it by hand mid-roll would
  reprovision and restart nodes the pipeline just drained and pinned; the lock refuses. Don't defeat
  it.
- **The proxy is a single point of failure by construction.** There is one Velocity instance. Any
  work that restarts it drops everyone.

## Diagnosing

```bash
scripts/remote.sh status                     # the network validation
scripts/remote.sh logs worker-1 -n 200       # -n/--lines, -f/--follow
scripts/remote.sh logs init                  # why a build/provision failed
scripts/remote.sh pipeline status            # who is on which build right now
scripts/remote.sh exec worker-1 -- <cmd>     # escape hatch
```

`status` distinguishes `SKIP` (couldn't check) from `FAIL` (checked, it's wrong) — do not read a
`SKIP` as healthy. Nodes also expose a token-gated HTTP API (`X-Sexidium-Token`, **not** Bearer) on
the `sexidium` bridge network: `/health` unauthenticated, plus `/node`, `/network`, `/node/drain`,
`/node/selftest`.

## Status of this tooling — read before trusting it

The build store, per-node jar pinning, the `pipeline` subcommands and the autoscaler are **recent and
have not yet been exercised against the live network**. They are covered by unit tests, golden traces
and a real Paper boot smoke test, and a review found (and fixed) defects in the resume and rollback
paths. Treat the first live `pipeline deploy` as a supervised operation: run `pipeline status` between
stages, keep `pipeline abort` in reach, and prefer a window when few players are online — the
no-disconnect guarantee is designed for, but not yet field-proven.

## Where to look next

| Question | Section of `docs/operations/deployment.md` |
|---|---|
| What runs where, adding a worker | §1 Topology |
| Volume layout, what can't be shared | §2 |
| Credentials | §3 |
| Standing it up from zero | §4 |
| Shipping code, which build a node runs, rolling update, scaling | §5 |
| Restarting, remote tests, validation, logs | §7–§10 |
| Rollback | §11 |
| When it breaks | §12 |
| Every flag | §13 CLI reference |
