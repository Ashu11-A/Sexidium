# Deployment — running the Sexidium network on Portainer

How the multi-server network is deployed, updated, restarted, tested, and diagnosed on a
Docker host managed by [Portainer](https://www.portainer.io/). One CLI drives all of it:

```bash
scripts/remote.sh up        # zero → network live
scripts/remote.sh update    # the day-to-day command: sync + provision + rolling restart
scripts/remote.sh status    # the network validation (13 checks)
```

`scripts/remote.sh` is the remote twin of [`scripts/net.sh`](../../scripts/net.sh) (which runs the
same network as local processes): same verbs, different execution substrate. Everything it does
is also visible and doable in the Portainer dashboard — the CLI exists so the sequence is
correct and repeatable, not to hide the dashboard.

---

## 1. Topology

One image (`eclipse-temurin:25-jdk`), one data volume, and one variable — `SX_NODE` — deciding
what each container is ([`docker/node-entry.sh`](../../docker/node-entry.sh)).

```
              players :26001  ← public; the internet-facing proxy forwards to
                     │          the host's 25565, the stack's only published port
              ┌──────▼───────┐
              │    proxy     │  Velocity · HTTP API 8787 · pack 8788 · RPC 8789
              └──┬───┬───┬───┘
    docker network `sexidium` (no host ports, container DNS)
       ┌─────────┼───┼───┴─────────┬──────────────┐
   ┌───▼───┐ ┌───▼──────┐ ┌────────▼─┐ ┌──────────▼┐
   │ lobby │ │ worker-1 │ │ worker-2 │ │ worker-3  │   Paper backends
   │ :25566│ │  :25567  │ │  :25568  │ │  :25569   │   API 8800/8810/8820/8830
   └───┬───┘ └───┬──────┘ └────────┬─┘ └──────────┬┘
       └─────────┴────────┬────────┴──────────────┘
                    ┌─────▼─────┐
                    │ mariadb   │  one database shared by every node
                    └───────────┘
      init  ── one-shot: builds the plugin once, writes the shared install,
               provisions every node, exits
```

- **`init` ending in `Exited (0)` is the correct state**, not a failure. It runs
  [`docker/provision.sh`](../../docker/provision.sh), writes `server/.provisioned`, and stops. Nodes
  wait for that stamp before starting a JVM.
- **Each container has its own network namespace.** Backends bind `0.0.0.0` inside a network
  that publishes nothing, and the proxy dials them by compose DNS name (`lobby:25566`, …). The
  gate is the Velocity modern-forwarding HMAC, not the socket. Until the stack is redeployed
  after this change, an older deployment may still show a `netns` container owning the
  namespace and publishing the port — `status` accepts both shapes.
- **`SX_NODES` is append-only.** Data identity follows the node *name* (the `sexidium-<node>` volume), but the
  port follows the *index* in the list: removing a node from the middle renumbers every node
  after it onto its neighbour's port. Adding one: §1.1.
- **One installation, four working directories.** The backends do not each own a Paper
  installation. `init` writes a single `/srv/sexidium/server/` (one `paper.jar`, one set of plugin
  jars) and every node runs it from its own thin working directory (§2). The jar a node loads is
  therefore a *file*, not a copy of a file — plugin/build drift between nodes is not "unlikely",
  it has nowhere to come from. The bundled **map templates** are shared the same way and for the same
  reason (§2.3); live worlds never are (§2.2).

Source of truth: [`docker/stack.sexidium.yml`](../../docker/stack.sexidium.yml).

### 1.1 Adding a worker

Adding `worker-4` is four edits in [`docker/stack.sexidium.yml`](../../docker/stack.sexidium.yml) and one
deploy. The node's identity is entirely its name and its `SX_NODE`; nothing is copied from a sibling.

1. **Append** it to `SX_NODES` (`"lobby worker-1 worker-2 worker-3 worker-4"`). Append-only: inserting
   or removing a name renumbers the ports of everything after it.
2. **Add the service**, cloning `worker-3`: `<<: *node`, `container_name: sexidium-worker-4`,
   `SX_NODE: worker-4`, its own `mem_limit`, and a `volumes:` list holding **both**
   `sexidium-data:/srv/sexidium`, `sexidium-build:/srv/build` and `sexidium-worker-4:/srv/nodes/worker-4` (create the new volume first).
3. **Declare** `sexidium-worker-4` under top-level `volumes:` and `docker volume create` it on the
   host (every volume is `external: true` so no redeploy can recreate one under you).
4. **Add it to the proxy's `depends_on`**, so the proxy still stops before the backends do.

A new node needs no map data of its own: `provision` points its `worlds/<bundle>` at the shared
template tree (§2.3), so it can host a match on every bundled map the moment it boots.

Then `scripts/remote.sh stack && scripts/remote.sh provision && scripts/remote.sh restart`. Because the
jars come from the shared install, a node added months later runs *the same files* as the others —
the old failure mode, where a new node resolved a fresher "latest" from Modrinth and stayed silently
ahead forever, no longer has a destination to differ in. `provision` creates the `worker-4` node volume (config,
port, symlinks, empty data folders) and Velocity learns the node from the regenerated `velocity.toml`.

### 1.2 Which node a world lives on

The proxy sends every join to the **lobby** — that is the design, and it is why the lobby alone needs a
real lobby world. What changed is the *exit*: a player opening a persistent Experience is now placed on
a **worker** and transferred there, instead of the world being created wherever the question happened to
be asked (which was always the lobby, because the lobby is where the player was).

- **Placement is decided once and recorded** in the `world_placements` table. Order: an existing owner
  always wins (the chunks are on that disk), then a folder already present on the local disk, then a
  planner that filters nodes by capability and `UP` state and picks the least loaded — **with the lobby
  as the last resort**, never excluded. Generating terrain on the lobby is felt by everyone on the
  network; on a worker it is felt only by the players in that world.
- **Transfer, not teleport.** The lobby writes a handoff row plus a route request, the proxy connects the
  player to the owning node, and the worker re-runs the normal permission checks on arrival. A route is a
  delivery mechanism, not an authorization.
- **A dead owner is reported, never re-created.** If the node owning a materialized world is down, the
  player is told *"the server holding this world is offline; nothing was lost"* — the world is not
  regenerated somewhere else. Only a *plan* nobody ever acted on (no folder exists anywhere) is
  re-pointed at another live node.
- **On boot, each node reconciles its own disk** against the table: a world folder with no row is
  **adopted** by the node holding it; a row pointing at another node, or a row whose folder is missing
  while an Experience still references it, is logged (`SEVERE`) and left alone for a human. Only a row
  that references neither a folder nor an Experience is deleted. **No path deletes a world folder.**
- **Leaving a worker routes back to the lobby.** `/leave`, death and match end on a node without the
  `LOBBY` capability send the player back through the proxy rather than dropping them in that node's
  default world.

**Current limits, deliberately.** Minigame matches are *not* placed: they still run on the node that
started them (`NodeCapability.MINIGAMES` is declared but not consulted), because launching a match
elsewhere means reserving a roster remotely, not routing a player. Nothing rebalances the Experience
worlds that already live on the lobby — moving one means copying the folder and calling `rehome()`
deliberately. And a worker has no real lobby world yet: a transferred player briefly lands in its default
world before the resume teleports them.

---

## 2. Volume layout

Three volumes, three jobs. The split exists so that **the folder you update is not the folder that
accumulates**, and so that the source is exactly what the servers execute — nothing else.

| Volume | Mount | What it is |
|--------|-------|-----------|
| `sexidium-data` | `/srv/sexidium` | **the source**: `server/`, and nothing else |
| `sexidium-build` | `/srv/build` | what only provisioning needs: `repo/`, `gradle/`, `artifacts/`, `toolbox/`, test reports |
| `sexidium-<node>` | `/srv/nodes/<node>` | **the state** of that one process, logs included |

Inside the source volume:

| Path | What it is | Replaceable? |
|------|-----------|--------------|
| `server/paper.jar` | the server binary — **one file** for all four backends | yes — rewritten by `init` |
| `server/plugins/*.jar` | the 5 jars every node loads | yes — rebuilt by `init` |
| `server/libraries/` `versions/` `cache/` | populated once by paperclip | yes |
| `server/maps/<bundle>/<id>/` | **the one map-template tree**, seeded from the jar by `init` (§2.3) | yes — re-seeded by `init`, *unless* a map was edited in-game and never re-exported |
| `server/.provisioned` | provisioning stamp (timestamp, node list, plugin sha256) | written by `init` |
| `server/.provision-failed` | tombstone: the last `init` failed and the previous tree was kept | diagnostic |

Inside each node volume:

| Path | What it is | Replaceable? |
|------|-----------|--------------|
| `world/` | the live world of that node | **never delete** |
| `worlds/` | real directory, one symlink per shared bundle, plus `temp/` and `experience/` (§2.3) | **never delete** |
| `plugins/<Plugin>/` | data folders — `sexidium.db`, `worlds.yml`, `npcs.yml` — and **zero jars** | **never delete** |
| `server.properties` `config/` `*.yml` | what Paper rewrites at every boot, including this node's port | regenerated by `init` |
| `paper.jar` `libraries` `versions` `cache` | symlinks into `/srv/sexidium/server` | yes |
| `proxy/` only: `velocity.toml`, `forwarding.secret`, `velocity.jar` | | **never delete the secret** |

**Why the state is not in the single folder**, and it is not a design preference: Minecraft's
`session.lock` admits exactly one owner per `world/`, and Paper rewrites `server.properties` — with
that node's port — on every boot, *before* it even checks the lock. Shared state would mean two JVMs
writing the same chunks. What can be unified is the **source**; what is left per node is **state**.

The `init` container is the only one that mounts all five node volumes, at the *same paths* the nodes
themselves use (`/srv/nodes/<node>`). That matters: the provisioner writes absolute paths into
symlinks and config, and a different prefix on each side would break them silently.

> A service's `volumes:` list **replaces** the `x-node` anchor's list instead of extending it — which
> is how the log mounts silently disappeared once. Every service therefore repeats `sexidium-data`
> and `sexidium-build` next to its own two. Check with
> `exec lobby -- df -h /srv/nodes/lobby/logs`: a different filesystem from `/srv/sexidium` means the
> mount is live.

`sync` never writes outside `/srv/build/repo`. That is the whole reason updating code cannot cost a
world — it does not even mount the volume the worlds live on.

### 2.1 The source folder and the node working directory

```
/srv/sexidium/server/        THE SINGLE FOLDER — written by `init`, read by every backend
├── paper.jar                one file for all four nodes
├── plugins/                 5 jars, and only jars: Sexidium, Multiverse-Core,
│                            FancyNpcs, FancyHolograms, SkinsRestorer
├── libraries/  versions/  cache/     populated once by paperclip
├── maps/tntwar/<id>/        map TEMPLATES — never opened as a live world
└── .provisioned             the stamp every node waits for before booting

/srv/nodes/<node>/           the node's working directory (its CWD), its own volume
├── paper.jar  libraries  versions  cache   → symlinks into /srv/sexidium/server
├── plugins/                 data folders ONLY — Sexidium/, Multiverse-Core/, … and zero jars
├── world/                   the live world (this node's, always)
├── worlds/                  a REAL directory holding one symlink per bundle:
│                            worlds/tntwar → /srv/sexidium/server/maps/tntwar. Plus
│                            temp/ and experience/ — live worlds, node-local (§2.3)
├── logs/                    pruned by age at every boot (SX_LOG_RETENTION_DAYS)
└── server.properties  config/  bukkit.yml  spigot.yml  …  ops.json  usercache.json
```

Each node boots with (`docker/node-entry.sh`, after `cd /srv/nodes/<node>`):

```bash
java $heap -jar $SX_SHARED_INSTALL/paper.jar \
    --plugins <cwd>/plugins --add-extra-plugin-dir $SX_SHARED_PLUGINS nogui
```

- `--plugins <cwd>/plugins` decides where each plugin's **data folder** goes
  (`Bukkit.getPluginsFolder()`), so hot state — `sexidium.db`, `Multiverse-Core/worlds.yml`,
  `FancyNpcs/npcs.yml` — stays per node.
- `--add-extra-plugin-dir` decides where the **jars** load from.
- A jar left in a node's own `plugins/` would be seen by both flags; Paper refuses a duplicate plugin
  name and the node comes up with neither copy. Provisioning deletes node-local jars for exactly this
  reason, which is also what migrates a directory off the old layout.
- No `--port`: Paper writes the flag's value back into `server.properties` *before* it checks the
  world lock, so even a node that fails to boot would rewrite its port. The port stays in each node's
  `server.properties`.
- `libraries/`, `versions/` and `cache/` are resolved by paperclip **relative to the CWD** with no
  flag to move them; the symlinks are the only way to aim them at the shared tree.

Measured on the live network after the migration: the Sexidium jar exists at **one** path (it was four
independent copies), per-node disk fell from **~1.72 GB to ~1.08 GB**, and the lobby reaches
`Done (` in **9.8 s** where it used to take **24 s**.

**Do not `chmod a-w` the shared tree.** It is read-mostly, not immutable: Paper's plugin library
loader resolves the `libraries:` declared in a `plugin.yml` (Sexidium's JDBC drivers) into
`<cwd>/libraries/`, i.e. into the shared tree, at plugin load. Measured here: the artifacts land once
and later boots only rewrite maven-resolver's small metadata beside them; two JVMs did it
concurrently without error. Making the tree read-only would break plugin load, not harden it.

### 2.2 Why the rest cannot be shared

Sharing an *installation* is safe. Sharing a *server folder* is not, and each row is a different
reason:

| Per node | Why it cannot be one copy |
|----------|---------------------------|
| `world/` | `session.lock` admits exactly one server per world folder. It is the only thing standing between a second JVM and two servers writing the same chunks — reproduced live. |
| `plugins/<Plugin>/` | Plugins keep their own registries and rewrite them wholesale: Multiverse's `worlds.yml`, FancyNpcs' `npcs.yml`, FancyHolograms' `holograms.yml`, Sexidium's SQLite file. Last writer wins, and the loser's worlds/NPCs simply vanish. |
| `server.properties`, `config/`, `bukkit.yml`, `spigot.yml`, `commands.yml`, `help.yml`, `permissions.yml` | Paper **rewrites** these on every boot, without an atomic rename — four nodes would take turns overwriting each other's config, and this is where the port lives. |
| `ops.json`, `usercache.json`, `banned-*.json`, `whitelist.json`, `version_history.json` | Fully rewritten at shutdown, no flag to relocate. |
| `logs/` | No flag, and they are wanted separate anyway. |
| `worlds/` **itself** | The world *root*. `temp/` (disposable match worlds) and `experience/` live **inside** it by the core's contract, and a node sweeps stale temp worlds out of it at startup. Share the root and one node's cleanup deletes the other three's **live matches**. Only the bundle subfolders under it are shared — §2.3. |

### 2.3 Shared map templates

Minigame maps ship **inside the plugin jar** (`assets/worlds/`). Each node used to extract its own copy,
so four nodes held four independently-extracted trees with nothing comparing them: 92 MB of maps × 3
redundant copies = **276 MB**, and any map edited in-game diverged on exactly one node, silently, for
as long as it took someone to notice a match starting on last month's layout.

There is now **one** tree, `/srv/sexidium/server/maps/<bundle>/<id>`, and each node reaches it through a
symlink at `/srv/nodes/<node>/worlds/<bundle>`.

**Why this is safe to share when `world/` is not.** A template is never opened as a world. Starting a
match **copies** `region/entities/poi/data` out of the template into a fresh world first, so there is no
`session.lock` to contend for and no second JVM writing the same chunks. Within one world root, readers
(match clones) are serialized against the writer (map extraction) — but not against each other — by a
`FileLock` on `<worldsRoot>/.map-bundle.lock`, refcounted per JVM. Measured on the real 46 MB `tnt-wars`
template: a clone costs **27.1 ms** under the lock versus 27.3 ms without it, and acquiring/releasing
the lock uncontended costs **28.6 µs**, about 0.1 % of the copy. The copy itself was additionally moved
**off the world thread**, so the tick that starts a match now pays ~0 ms instead of 27 ms plus up to
500 ms of lock wait.

That lock lives in the world root each JVM opened, so it does **not** span containers: `init` writing
`/srv/sexidium/server/maps` and a node cloning through `/srv/nodes/<node>/worlds` hold two different lock files. This is
why re-seeding **with the nodes down is mandatory whenever a map's bytes change** (below) and merely
recommended otherwise. Every write is still published by `rename`, which narrows the window in which a
reader could see a half-written template from the 1–2 s of an inflate to the microseconds of a
`rename(2)` — a belt, not the buckle.

**Never symlink `worlds/` itself — only `worlds/<bundle>`.** This is the one expensive thing to get
wrong here, and it is enforced twice: `provision` refuses to run against a symlinked world root, and
`docker/node-entry.sh` refuses to **boot** a node whose `worlds/` is a symlink. The asymmetry with the
jar-hash check (a warning, never fatal) is deliberate: there, refusing to boot turns a half-finished
deploy into an outage and no data is at stake; here, *booting* is the destructive act, because the stale
temp-world sweep would delete other nodes' live matches. Losing a boot is recoverable; that is not.

**Who may write it.** Writing a map template requires the `MAP_AUTHORITY` capability, held by the
**lobby** (and by a standalone server, which is the only node that exists). It is checked at the door,
not at the save: `/sx admin map edit` refuses to *enter* the editor on a worker, and `/sx admin map
tntwar` / `combat` refuse anything past `list`. The refusal names the node you are on and the node that
does hold the authority. Read-only subcommands (`list`, `worlds`, `status`, `exit`) work everywhere.
There is exactly one writer on this network by construction — the lobby is the only backend role of
which exactly one exists.

**Publishing a new or changed map.**

1. *The canonical path* — export the map into `assets/worlds/tntwars/<id>.zip` (the source directory
   feeding the `tntwar` bundle), then `scripts/remote.sh update`. `init` re-seeds the shared tree from
   the jar — a digest stamp per map, so unchanged maps are a no-op; 92 MB / 3 maps takes ~0.5 s — and
   every node sees the result through its symlink. Run it with the nodes **down** if a map's contents
   changed: no lock spans the container boundary, so a node mid-clone of a template being replaced is a
   real race, and `remote.sh down` is a minute against a match starting on a half-copied map.
2. *In-game* — build with `/sx admin map edit` **on the lobby** and confirm. The write lands in the
   shared tree, so it is live for the whole network on the next match. This is the point of no return
   for a rollback: reverting the symlink is ten minutes, but a map edited in-game exists in no jar.
   Re-export it to `assets/worlds/` afterwards or the next `MapBundle` refresh has nothing to preserve.
   Confirm it while **no match is running on that map** — for the same cross-container reason as above,
   the workers reading the template are not holding the lobby's lock.

Nodes do **not** extract maps themselves: `provision` writes `worlds.map-bundle.extract-if-missing:
false` and `refresh-when-changed: false` into every network node's `config.yml`. With those left on, a
node booting with a new digest would move a template aside and re-extract it *inside the shared tree* —
the concurrent writer this whole layout exists to remove. On a standalone server both stay `true` and
the plugin remains its own map installer.

**Migrating an existing node.** `provision` hands the node's local copy to the shared tree with
`cp -an` (no-clobber, so the shared tree can never be downgraded) and then compares **byte by byte** —
not by mtime, which is what made four identical extractions look like they had drifted in the first
place. A copy proven identical is deleted; a copy that differs is **moved aside** to
`worlds/<bundle>.local-<timestamp>` with a `.divergent.txt` listing the differing paths, and nothing is
ever `rm -rf`'d. If a `KEPT worlds/<bundle> from <node>` line appears in the `init` log, someone had
local work: decide what wins before the nodes come back up.

---

## 3. Credentials

Two files, both gitignored, both `chmod 600`, neither ever committed:

| File | Holds | Created by |
|------|-------|-----------|
| `scripts/remote.env` | `SX_PORTAINER_URL`, `SX_PORTAINER_KEY`, `SX_PORTAINER_ENDPOINT` | you, from [`scripts/remote.env.example`](../../scripts/remote.env.example) |
| `scripts/remote.secrets.json` | `db_password`, `api_token`, `forwarding_secret`, `db_root_password` | `remote.sh` on first use |

```bash
cp scripts/remote.env.example scripts/remote.env
chmod 600 scripts/remote.env
# Portainer → My account → Access tokens → Add access token   (the value starts with ptr_)
scripts/remote.sh version      # preflight: is the key good?
```

- Every variable can come from the environment instead, and the **environment wins**:
  `SX_PORTAINER_KEY=… scripts/remote.sh status` works with no file at all.
- `remote.env` is *parsed*, never `source`d — a `$(...)` in a credentials file would otherwise
  execute in the shell holding the key.
- The key travels only in the `X-API-Key` header: never in `argv` (visible in `ps`) and never
  in a query string (it would land in Portainer's access log). `--verbose` never prints headers,
  and every error body is redacted before printing.
- `remote.sh secrets` lists **fingerprints**, not values (`--show` additionally requires `--yes`).
- **The forwarding secret never changes.** It is what authenticates backends; rotating it
  without re-provisioning makes every join fail with *"This server requires you to connect with
  Velocity."* `secrets --rotate forwarding-secret` says so and demands confirmation.
- **If the key leaks** (a paste, a screenshot, a commit): revoke it in Portainer → My account →
  Access tokens, create a new one, update `remote.env`. Nothing else needs to change — the key
  is not stored anywhere else.

---

## 4. Deploy from zero

Prerequisites on the host: Docker + Portainer, an external network `sexidium`, and the external
volumes `sexidium-data`, `sexidium-build`, `sexidium-<node>` (one per node **and** the proxy) — all `external`, so no redeploy can recreate them by accident.

```bash
scripts/remote.sh up
```

| Phase | What happens | How long |
|-------|--------------|---------:|
| preflight | Docker version + API key | < 1 s |
| `db-init` | database, user, grants (idempotent) | ~2 s |
| `sync` | repo → build volume (~60 MB tar, minus `build/`, `run/`, `node_modules`) | 10–20 s |
| `stack` | create/update the Portainer stack from `docker/stack.sexidium.yml` | ~5 s |
| `provision` | `init`: build the plugin once, write the shared install, seed the shared map templates, then provision proxy + 4 backends in parallel | **2–15 min** first run |
| boot | backends first, proxy last | 1–3 min |

The shared install **and the map templates** are written **serially, before** the per-node fan-out: the
jars either all land or the run aborts, so a partial failure can no longer mean "three nodes on the new
build, one on the old". It also avoids four subshells racing for the same destination path. A
half-seeded map tree aborts the run outright — a node must never boot against one. Populating
`libraries/`/`versions/`/`cache/` costs one extra paperclip run (~160 MB) the first time only.

Then `scripts/remote.sh status` and connect a client to `<host>:26001` (the internet-facing proxy
maps that to the host's published 25565).

`up` is idempotent — re-running it is safe and is the way to recover a half-finished deploy.

---

## 5. Updating code

```bash
scripts/remote.sh pipeline deploy       # rolling, no disconnect, reversible  (§5.2)
scripts/remote.sh update                # sync + provision + restart-everything
scripts/remote.sh sync scripts docker   # only these directories
```

**`update` disconnects every player** — it restarts all five nodes without draining them, and if one
does not come back the network is left half-rolled with no record of it. It is the right tool for a
network with nobody on it. `pipeline deploy` is the right tool for every other time.

What each step preserves:

| Step | Touches | Preserves |
|------|---------|-----------|
| `sync` | `/srv/build/repo` | the source folder and every node volume — it does not even mount them |
| `provision` | `/srv/sexidium/server/` (jars, maps) + node dirs (generated config, symlinks) | worlds, plugin data folders, hand-edits the provisioner does not own |
| `restart` | the JVMs | everything on disk |

A change that only touches `/srv/nodes/<node>/config.yml` needs a `restart`, not an `update`. A change
to Java or bot code needs the full `update` (the plugin jar is rebuilt by `provision`).

### 5.1 Which build each node runs

Until recently there was **one** Sexidium jar on disk, in `/srv/sexidium/server/plugins/`, loaded by
every backend through `--add-extra-plugin-dir`. That made "worker-2 is running an older build"
impossible — and it also made it impossible to *choose*, which is what a rolling update needs.

The jar now lives in a **versioned store** and each node reaches its own build through a symlink:

```
/srv/sexidium/server/builds/
  COUNTER  LATEST
  b0042-9f3c1a77b0de/
    Sexidium-Paper-1.0.0.jar  Sexidium-Velocity-1.0.0.jar
    manifest.txt              deps/          PROMOTED

/srv/nodes/worker-2/
  pluginjars/                        <- the dir passed to --add-extra-plugin-dir
    Multiverse-Core-5.7.3.jar  -> /srv/sexidium/server/plugins/…   (shared, one inode)
    Sexidium-Paper-1.0.0.jar   -> /srv/sexidium/server/builds/b0042-…/…
  sexidium-build.pin                 <- build=, previous=, sha256=
```

- **The build id is content-addressed**: `b<counter>-<sha256[0:12]>`. Re-running a build over
  unchanged source produces the *same* directory, so a resumed pipeline's BUILD stage is a no-op.
- **A roll is one rename.** `ln -s` into a temp name, then `mv -T` — which is `rename(2)`, atomic. A
  **rollback is the identical call** with the pin file's `previous=`. There is no restore-from-backup
  path and no state to reconstruct, which is what makes an interrupted run harmless.
- **`pluginjars/` is a sibling of `plugins/`**, never inside it, so the provisioner's
  `find plugins/ -name '*.jar' -delete` still works with no special case and a jar is visible through
  exactly one of the two flags.
- **Paper does follow the symlink.** That was the one unvalidated assumption under this layout and it
  is checked by a real boot: `scripts/test/smoke-pin.sh` starts Paper with one symlinked and one
  regular-file probe plugin and asserts both are discovered. Re-run it on a Paper major bump. If it
  ever fails, `SX_PIN_MODE=copy` puts real copies in `pluginjars/` instead — one variable, and
  nothing else in the store, the pin, the rollback or the pipeline changes.
- **The boot check names the build.** `verify_pinned_jar` logs
  `build b0042-… (9f3c1a77b0de) pinado; o carimbo descreve b0043-…` on every backend start. A hash
  mismatch is a warning; a **dangling pin is fatal**, because a node that boots without its plugin
  looks completely healthy — container Running, `Done (` in the log — and serves nothing.
- **`server/.provisioned` changed meaning.** It now describes the build `init` most recently
  **staged** (the deploy *target*, with a new `plugin-build=` line), not what each node runs. During a
  rolling update those differ on purpose. The question "who is running what" is answered by
  `status` → `build.pins` and by `pipeline status`.
- **Third-party jars are frozen once resolved.** `ensure_modrinth_plugin` short-circuits on "the file
  is already there", so a plugin resolved once never changes again — good for reproducibility, useless
  when you *want* the update. The one lever is `SX_REFRESH_PLUGINS=1` on the `init` environment, which
  drops Multiverse/FancyNpcs/FancyHolograms/SkinsRestorer from the shared install so Modrinth is
  re-resolved. Opt-in, because the default must stay "nothing changes under you". Multiverse-Core is
  additionally pinned to the **`release`** channel: without that, a fresh resolve picks up a
  pre-release (5.8.0-pre was current while production ran 5.7.3) and silently swaps the plugin
  Sexidium hard-depends on. Every such run snapshots the resulting jar set into `builds/<id>/deps/`,
  so a fleet-wide dependency rollback has something to restore *from*.
- **`INSTALL_*=0` now actually removes.** Skipping a *download* never deleted anything, which is how
  FAWE and Axiom kept running on all four nodes for months while the stack declared them off. The
  shared install prunes jars this run does not want, and `pluginjars/` prunes links whose target is
  gone, so the declared intent reaches the nodes. Consequence to know: **FAWE and Axiom are no longer
  on the network nodes** — in-world editing with `//wand` or Axiom is not available in production.
  BetterHud is not installed on the network either (`SX_SKIP_BETTERHUD=1`), which costs players
  nothing: `hud.betterhud.enabled` is already forced `false` on every network node, so those readouts
  render on the scoreboard sidebar (see F62/F67 in [known-issues.md](../reference/known-issues.md)).

Retention keeps the last `SX_BUILD_RETENTION` (default 10) builds, **plus** anything any node still
references through `build=` or `previous=`, **plus** anything promoted in the last 30 days. GC runs
only at the end of a fully green pipeline run — never during one, and never from `init`, because a
provision that could delete a rollback target would make "the deploy failed" and "you cannot go back"
the same event.

---

### 5.2 The rolling update (`pipeline deploy`)

`update` restarts all five nodes without draining them: **everybody online is disconnected**, and if a
node does not come back the network is left half-rolled with no record and no way back. Use it for a
network with nobody on it. For anything else:

```bash
scripts/remote.sh pipeline deploy            # the whole thing
scripts/remote.sh pipeline status            # lock, current run, and each node's pin
scripts/remote.sh pipeline resume            # continue an interrupted run
scripts/remote.sh pipeline abort --yes       # unwind it instead
scripts/remote.sh pipeline builds            # what is in the store
scripts/remote.sh pipeline pin worker-2 b0041-4c1dd7e29a80 --yes
```

Stages: **PREFLIGHT** (refuses on an already-unhealthy network, and refuses if the compose's `db`
service ever lost its `profiles` guard) → **BUILD** (stages into the store; touches no node, writes no
stamp, so a compile failure leaves the network byte-identical) → **INSTALL** (provisions everything
with `SX_ADOPT_BUILD=0`, so every node still runs its previous build) → **CANARY** (the last worker) →
**the rest** → **proxy** → **FINALISE** (all-PASS `status`, mark `PROMOTED`, GC, release the lock).

Each node: announce at T-60/-30/-10s → drain → **wait for all four conditions** (`state=DRAINING`,
`players=0`, `worlds=0`, and zero `world_placements` rows `LOADED` — `players=0` alone is not enough,
an open lease means a shared world still has an owner) → flip the pin → restart → verify → reclaim.

**No player is disconnected**, because the pin only moves after the node is empty. The two exceptions
are refusals, not warnings:

| | what happens | how to override |
|---|---|---|
| only one lobby | **REFUSED.** Velocity's `try = ["lobby"]` has nowhere to fall back to, so rolling it *is* a disconnect | run a second lobby first, or `--allow-lobby-disconnect` |
| the proxy | **skipped entirely** when the Velocity jar sha is unchanged (the common case) | when it *has* changed: `--maintenance-window` |

**Resuming.** The journal lives on `sexidium-data`, not on your machine, so anyone can resume. It is
append-only JSON Lines: a torn last line is not valid JSON, so an interrupted run is *detectable*
rather than ambiguous. `resume` replays to the first sub-stage with a `begin` and no `ok` and re-runs
it from the top — safe because every sub-stage is idempotent, and unambiguous because PIN journals
both build ids.

`resume` adopts a run; it does not force its way into one. It **refuses** a lock marked `needs-human`
(a failed rollback needs a person, not a retry), and **refuses** a lock whose heartbeat is younger
than the staleness window — that lock belongs to a run that is still alive, and two pipelines rolling
the same nodes would each see the other's half-applied pins. Only a genuinely stale lock is taken
over. It also **refuses when `LATEST` has moved underneath it**: the build the journal names is the
build it installs, so a `pipeline deploy` landing between the interruption and the resume cannot
silently retarget the run and split the fleet across two builds.

**Unwinding drains first.** `converge` (after a failed verify) and `abort` act on nodes that were
already *reclaimed* — undrained, with players on them. Both therefore drain and broadcast before they
stop or restart anything; the no-disconnect rule holds on the unwind path too, which is where it
matters most. The one exception is a node that no longer answers its API: `--skip-drain` (on `deploy`,
`resume` and `abort`) stops waiting out the full drain timeout for a node that can never drain.
Unlike the rolling drain, a drain timeout *here* warns and proceeds — at that point the fleet is
already split across two builds, and stopping halfway would leave it that way.

**Rollback** fires only for the node just repinned, only inside its own verify window, and only on
R1-R7 (boot timeout · crash loop · plugin threw during enable · node did not take the build · a
battery assertion FAILED · OOM · heartbeat lost). `SKIP` is never `FAIL`, so an assertion whose
JVM-side half has not shipped can never roll anything back, and every known-benign boot line is
asserted *not* to be a trigger. The node's container is **stopped first** — an explicitly stopped
container is the only thing that halts an uncapped `unless-stopped` restart loop — then repinned to
`previous=`, started, and verified again.

If the *rollback* fails, the pipeline stops touching things: that node stays stopped and drained, the
lock is marked `needs-human` (which refuses every new run, autoscaler included), a `critical` alert
fires, and **the other nodes keep serving**. N-1 is a degraded network; a half-rolled network with a
crash-looping member is a broken one.

---

### 5.3 Scaling

```bash
scripts/remote.sh watch                 # 15s loop: status + autoscaler evaluation
scripts/remote.sh watch --observe-only  # evaluate, never act
scripts/remote.sh scale up              # one action, by hand
```

Scale-up needs utilisation > 0.70 for 4 consecutive polls; scale-down needs < 0.35 for **40**. The
asymmetry is the anti-flap mechanism: a node too many costs memory, a node too few costs queueing
during a spike.

**Scale-in parks, it does not delete.** The container stops; `SX_NODES` and the compose are untouched
and the name goes into `pipeline/retired.json` for reuse. Ports follow the *index* in `SX_NODES` (in
three shell functions, plus each node's persisted `server.properties` and `sexidium-node.args`), so
removing from the middle would renumber every later node onto its neighbour's port. Parking keeps the
index, the ports and the volume. Real deletion is a separate, manual, tail-only operation.

**The ceiling is physical, it is printed, and today it is zero.** Measured live: 24 CPU, **31.07 GiB**
RAM, **20 GiB of `mem_limit` already committed** (proxy 1 + lobby 4 + 3×5), and `init` reserves a
further 6 GiB while it provisions — which a scale-out has to run. Autoscaled workers get
`mem_limit: 3g` (measured working set is 2.0-2.4 GiB), so the uniform-size formula
`(31.07×0.85 − 1 − 4 − 6) / 3` says 5 workers.

**That formula is not what governs**, because the three deployed workers are 5g, not 3g. The check
the autoscaler actually applies is `committed + new worker + init reserve ≤ MemTotal × 0.85`, i.e.
`20 + 3 + 6 = 29 GiB > 26.4 GiB` — so on this host, as configured today, **it refuses to add a
worker**, and says so with the whole sum. To make room, lower an existing worker's `mem_limit`
(5g → 3g on all three frees 6 GiB), raise `SX_MAX_WORKERS` if you accept the over-commit, or add a
host. It never over-commits on its own: a cgroup OOM kill is `SIGKILL` — no shutdown hook, no world
save, exit 137 — and that is the worst outcome available on this machine.

CPU is not the constraint and is not checked: ~24% of *one* core-equivalent across six containers,
on 24 vCPU.

A scale-out never touches `proxy.depends_on`. It only orders startup and the proxy discovers a new
backend from the DB registry within one 5s refresh — but editing it changes the proxy's config hash,
and a stack update runs with `prune: true`, so the proxy would be **recreated and every player
disconnected**. The compose edit is guarded hunk-by-hunk against the *deployed* stack file and refuses
anything outside the four allowed changes.

> **Do not add `SX_PIN_MODE`, `SX_BUILD_RETENTION` or any other new key to the compose's `x-env`
> block for a one-off.** `x-env` is merged into every service, so adding a key changes all six config
> hashes and the next `prune: true` deploy recreates all six containers. Pass run-scoped variables
> through the pipeline's `pipeline/init-env.sh` mechanism, which `init` sources and deletes.

---

## 6. Updating volume content

- **Worlds and hand-edited config** live in the node volumes and are never touched by `sync`. Edit them
  in place: `scripts/remote.sh exec lobby -- cat /srv/nodes/lobby/plugins/Sexidium/config.yml`.
- **Bundled maps** (`assets/worlds/`) ship inside the jar; on the network they are extracted **once**,
  by `init`, into `/srv/sexidium/server/maps/` — not by each node on boot. Changing one is a normal code change
  (`update`), but see §2.3 for who may write a map and what happens to an in-game edit.
- **A single file** can go up with `sync <dir>` (directory granularity) or be edited in place
  via `exec`.
- **`sync --prune-repo`** deletes `/srv/build/repo` before uploading, making the update a
  replacement instead of a merge. Opt-in and confirmed, because it is only needed when files
  were *deleted* from the repo. It still never touches the node volumes.
- **Paper/Velocity version bumps**: set `PAPER_VERSION` / `VELOCITY_VERSION` in the stack env
  and re-provision; the provisioner refreshes the shared install's jars on a version change.
  Downgrades quarantine worlds rather than corrupt them. Swapping the proxy's major version also
  means deleting `/srv/nodes/proxy/velocity.jar` so it is re-fetched.
- **Never hand-drop a jar into `/srv/nodes/<node>/plugins/`.** It would be loaded twice (once per plugin
  directory flag) and Paper rejects the duplicate name, so the node comes up without the plugin at
  all. Put it in `/srv/sexidium/server/plugins/` — or better, let `provision` own it.

---

## 7. Restarting

```bash
scripts/remote.sh restart                # worker-3 → worker-2 → worker-1 → lobby → proxy
scripts/remote.sh restart --only worker-2
scripts/remote.sh down                   # proxy → lobby → workers
```

**Why that order.** The proxy restarts *last* because, while it is alive, a player whose backend
goes down is bounced back to the lobby instead of being disconnected; backends go last-first so
the lobby (the fallback destination) is unavailable for the shortest time. Shutdown reverses it:
the proxy goes first so nobody is stranded on a server that is mid-save.

The roll **fails fast** — a node that does not come back stops the sequence rather than leaving
the network half-updated. `--boot-timeout 0` skips waiting for each node's `Done (` line.

What a player sees: a brief *"Unable to connect you to lobby"* only if they join in the window
where the lobby is restarting; already-connected players on a worker are moved rather than kicked.

---

## 8. Running tests remotely

```bash
scripts/remote.sh test                 # scripts + gradle + bot
scripts/remote.sh test gradle --no-sync
```

An ephemeral `sexidium-test` container runs [`docker/test-entry.sh`](../../docker/test-entry.sh)
on the same volume: `scripts/test/run.sh`, `./gradlew build` (the JUnit suites run as part of
`build`), and `bun install && bun run check` in `bot/`. Reports land in
`/srv/build/test-run/test-reports/<run-id>/`. The container is deleted afterwards, always.

> **Why the host and not your workstation.** `scripts/test/run.sh` **skips** a stage whose tool
> is missing and still exits 0 — on a fresh checkout it announces "5 stage(s) OK" having really
> run two. That makes local green non-authoritative. The remote run exports `SX_TEST_STRICT=1`,
> which turns every skip into a failure, and its toolbox is version-pinned + sha256-checked so
> `shfmt` cannot report formatting drift that does not exist.

Timings: scripts ~10 s · gradle ~90 s warm (150 s with an empty `GRADLE_USER_HOME`) · bot ~30 s.
The first ever run adds 2–3 minutes to install the toolbox into the volume.

---

## 9. Validating the network

`scripts/remote.sh status` (`--json` for machines, `--wait S` to poll until it converges) runs
13 named checks and exits non-zero on any `FAIL`.

| Check | Green means | Red usually means |
|-------|-------------|-------------------|
| `containers.running` | every node in `SX_NODES` + proxy is up | a service crash-looped; read its `logs` |
| `provision.stamp` | `server/.provisioned` matches `SX_NODES`, one plugin build across all nodes | a node was not restarted after provisioning (still on the old jar), or the tombstone is present |
| `boot.done` | every node logged `Done (` this boot | still starting, or died during startup |
| `boot.clean` | no `ERROR`/`Exception` this boot outside the versioned allowlist | a real fault — read the node's log |
| `ports.listening` | each node listens on its game port + API port, read from its own `/proc/net/tcp` | a port collision or a node that never bound |
| `ports.published` | someone publishes 25565 | the stack lost its port mapping; nobody can join |
| `db.schema` | the `sexidium` schema has tables | `SchemaMigrator` never ran — wrong credentials or wrong DB |
| `db.nodes` | one `UP` row per node, heartbeat < 30 s | a node lost the database or its clock/thread is stuck |
| `api.health` | the lobby's HTTP API answers `/rank` | the JVM is up but the plugin did not finish loading |
| `mc.ping` | an external server-list ping succeeds | the proxy is not reachable from outside |
| `memory.headroom` | every container below 85 % of its `mem_limit` | raise `mem_limit`/heap before the cgroup SIGKILLs it (no save, no shutdown hook) |
| `host.thp` | the host kernel's transparent hugepages are `madvise` (or `never`) | `always` is back — see §9.1; expect JVM SIGSEGVs inside the GC |

Checks that cannot apply to the current topology report `SKIP`, never `FAIL` — nothing in
`status` hardcodes a container name, a port base, or the presence of a `netns` container.

### 9.1 Host tuning (transparent hugepages)

Every node had crashed with `SIGSEGV` **inside the garbage collector** — `ZMark::mark_and_follow`
(worker-2), `ZRelocateWork::do_forwarding` (worker-1), `ZMark::follow_object` (lobby) — and before the
G1→ZGC switch in `node-entry.sh`, the same crashes appeared in `G1CMOopClosure` /
`G1ParScanThreadState` with the same corrupted-pointer shape (`0xa0000008`, `0xb9000008`, …). A fault
that reproduces identically under two independent collectors is not a collector bug, and the leading
collector-independent cause on this host is `transparent_hugepage=always`.

Two mechanisms hold it at `madvise`, because neither alone covers both failure modes:

| | Where | Survives reboot | Survives an umbrelOS update |
|---|---|---|---|
| `docker/host/sexidium-thp.service` | host `/etc/systemd/system` | yes | **no** |
| `host-tuning` service | the `sexidium` stack | yes | yes |

The host is umbrelOS: Rugix (`init=/usr/bin/rugix-ctrl`), root on an overlay, A/B system slots. The
overlay does survive a reboot, so a unit in `/etc` is fine day to day — but an OS update writes the
*other* slot, and everything outside `/data`, `/home`, `/var/log` and `/var/lib/docker` goes with it.
Docker's state is a real persist bind mount, so a container with a restart policy is the only boot hook
on this machine that an OS update cannot erase. The unit is still worth having: it runs `Before=
docker.service`, and the setting only affects mappings made *after* it, so getting in before the JVMs
is what makes it count.

After an umbrelOS update, reinstall the unit:

```bash
scp docker/host/sexidium-thp.service umbrel@<host>:/tmp/
ssh umbrel@<host> 'sudo install -m644 /tmp/sexidium-thp.service /etc/systemd/system/ \
  && sudo systemctl daemon-reload && sudo systemctl enable --now sexidium-thp.service'
```

`status`'s `host.thp` row is the check that this is still true. It asserts the **kernel value**, not the
container — swap the mechanism freely, just never let the value go back to `always` unnoticed.

---

## 10. Logs

```bash
scripts/remote.sh logs lobby -n 300
scripts/remote.sh logs proxy -f          # follow
scripts/remote.sh logs init              # what the last provisioning did
scripts/remote.sh logs db
```

Two log surfaces, solving different problems, and both are kept:

- **`docker logs`** — what the CLI streams, capped by the stack's `json-file 20m×5` (the Docker
  default is unlimited).
- **The canonical file** `/srv/nodes/<node>/logs/latest.log`, inside that node's **own** volume
  (`sexidium-<node>`, alongside its worlds), reachable with
  `exec <node> -- tail -n 200 /srv/nodes/<node>/logs/latest.log`. That is the surface you back
  up, quota, and apply retention to.

The log had a volume of its own until 2026-08-11 (`sexidium-logs-<node>`). Merging it back into the
node's volume cost nothing that was actually being used: what the separation was *for* — retention —
is a policy the entrypoint applies at every boot, not a mount point, and the price was ten volumes
for five processes.

Paper's log4j2 ships **no retention at all**, so rolled `*.log.gz` accumulate forever. The entrypoint
prunes them by age before each boot: `SX_LOG_RETENTION_DAYS` (default `30`, `0` disables). A bad value
is ignored rather than fatal — misconfigured retention must never be why a node fails to start.

---

## 11. Rollback

- **Code, automatic**: a `pipeline deploy` rolls a node back by itself the moment its verification
  fails (§5.2), and unwinds the nodes it had already repinned so the network converges on one build.
- **Code, by hand**: `scripts/remote.sh pipeline pin <node> <build-id> --yes` against any retained
  build. `pipeline builds` lists them. This is the deliberate escape hatch for going back *more than
  one step* — the automatic path goes back exactly one, because every node started the run on the same
  build and one step is therefore unambiguous, while picking an arbitrary build is a human decision.
- **Code, the old way**: `git checkout <ref> && scripts/remote.sh update` still works and still
  disconnects everyone. Downloaded artifacts are cached under `/srv/build/artifacts` (keyed by URL),
  so a rollback re-installs rather than re-downloads.
- **Config and worlds**: untouched by any deploy — each node volume survives every update, and
  `/srv/nodes/proxy/forwarding.secret` is never regenerated (regenerating it orphans all four backends at
  once).
- **Third-party dependencies** are shared by every node, so restoring them is fleet-wide by nature.
  That is why a `--refresh-plugins` run is all-or-nothing: the resulting jar set is snapshotted into
  `builds/<id>/deps/` with digests in the manifest, and a rollback restores that set for everyone. A
  partial roll with mixed dependency sets is refused up front.
- **The shared install** is disposable: deleting `/srv/sexidium/server/` and re-running `provision`
  rebuilds it from scratch. Do that with the nodes **stopped** — they hold the jar open and their
  `paper.jar`/`libraries`/`versions`/`cache` symlinks point into it. Note this also destroys the build
  store, and with it every rollback target.
- **The shared map tree** is disposable *only* for maps that still exist in a jar. Reverting the layout
  is `rm run/<node>/worlds/<bundle>` plus a rolling restart with `worlds.map-bundle.*` back to `true`;
  `MapBundle` re-extracts from the jar on its own. Anything built in-game and never re-exported to
  `assets/worlds/` is not in any jar and does not come back (§2.3).
- **Stack shape**: edit `docker/stack.sexidium.yml`, run `scripts/remote.sh stack`.
- **Paper and Velocity are NOT rolled back automatically.** `refresh_jars_on_version_change`
  *quarantines a node's worlds* on a downgrade — that is the codebase saying a server-jar downgrade is
  not a safe automatic action, and worlds genuinely do not downgrade. A run whose manifest shows a
  Paper or Velocity change stops at "halt, drain, leave stopped, alert", never at an automatic
  downgrade.
- **A database migration that already ran is not undone by deploying older code.** Check
  `docs/operations/networking-bot-ranks.md` for the schema history before rolling back across one.

---

## 12. When it breaks

| Symptom | Likely cause | Action |
|---------|--------------|--------|
| *"Unable to connect you to lobby"* | lobby not booted yet, or the forwarding secret differs between proxy and backends | `status`; if `provision.stamp` is red, `update` |
| A node sits forever without starting | no `server/.provisioned` stamp — `init` failed | `logs init`; look for `.provision-failed` |
| `already locked` / `DirectoryLock$LockException` on boot | a **live** JVM owns the world; the entrypoint only removes an orphan lock (proved by scanning `/proc/*/comm`, and an unreadable `/proc` counts as "owned") | do **not** delete it by hand until you have checked no server is running on that world — the lock is the only thing preventing two JVMs writing the same chunks |
| `instalação compartilhada ausente/incompleta` | `/srv/sexidium/server/` missing or half-written — `init` never finished | `logs init`, then `provision` |
| A node dies at boot with `worlds é um symlink` | the node's **world root** was linked at a shared tree; booting would let this node's stale-temp sweep delete other nodes' live matches (§2.3) | replace `run/<node>/worlds` with a real directory (its bundle subfolders are the only things that may be links), then `provision` |
| `cópia local de mapa em worlds/` warning | that node still holds its own extracted copy instead of a link into `/srv/sexidium/server/mapsared/maps` — usually not reprovisioned since the migration | `provision`, then `restart --only <node>`; check `logs init` for a `KEPT worlds/<bundle>` line first |
| A player is told *the server holding this world is offline* | the node owning that Experience world is down; the world is deliberately **not** re-created elsewhere (§1.2) | bring that node back — `status` → `containers.running`, then its `logs` |
| `SEVERE` at boot naming a world placement | reconciliation found a folder claimed by another node, or a row whose folder is gone while an Experience still points at it | a human decides: restore the folder from backup, or `rehome()` the row after copying the bytes. Nothing is deleted meanwhile |
| A node logs `diverge do pin` | the jar it resolves does not hash to what its pin file says — something wrote the pin without moving the link | `pipeline status` to see every node's pin, then `pipeline pin <node> <build> --yes` |
| A node dies with `pin quebrado` or `pluginjars ausente` | its symlink points at a build the GC removed, or it was never pinned. **Fatal on purpose**: booting anyway means booting with no Sexidium plugin at all, which looks perfectly healthy and serves nothing | `pipeline builds`, then `pipeline pin <node> <build> --yes`, or `provision` for a node that has never been pinned |
| `status` shows `build.pins: builds MISTOS` | nodes are on different builds — the **normal** state during a rolling update | `pipeline status`; if no run is active, `pipeline deploy` or pin them by hand |
| `outro run está em curso` / `lock ESTANCADO` | another deploy or the autoscaler holds the lock, or one died holding it. **`stack` and `provision` take this lock too** — by hand mid-roll they would PUT the compose with `prune:true` and recreate the very containers the pipeline just drained and pinned | `pipeline status` shows who and how stale; `pipeline resume` adopts it, `pipeline abort --yes` unwinds it |
| `resume` refuses instead of resuming | the lock is `needs-human`, the run it names is still alive (fresh heartbeat), or `LATEST` moved under it | all three are deliberate — read the message: investigate the node, wait for the live run, or name the intended build. `resume` never forces |
| The lock says `needs-human` | a rollback itself failed; that node is stopped and drained and the rest of the network is still serving | investigate that node, then `pipeline abort --force-unlock --yes` |
| A plugin is missing on one node only | a stray jar in `/srv/nodes/<node>/plugins/` duplicates a shared one and Paper drops both | `exec <node> -- find /srv/sexidium/run/<node>/plugins -maxdepth 1 -name '*.jar'` — there should be none; `provision` removes them |
| Proxy up, every join refused | backends unreachable by DNS name, or `SX_BACKEND_ADVERTISE` wrong | `status` → `ports.listening`, then `logs proxy` |
| Container OOM-killed (no shutdown log) | RSS above `mem_limit`: heap + metaspace + region-file mmap | `status` → `memory.headroom`; raise the limit in the stack file |
| Exit code 3 from any command | API key revoked or expired | create a new token, update `remote.env` |
| `name already in use` for `sexidium-sync` / `sexidium-test` | a previous run was interrupted | the CLI removes it before creating; if not, delete it in Portainer |
| Disk full | test reports in `/srv/build`, old worlds, Docker log files | prune reports; `exec <node> -- du -sh /srv/sexidium/*` |

---

## 13. CLI reference

```
scripts/remote.sh <command> [options]

  up                          zero → live: preflight, db-init, sync, stack, provision, boot
  update                      sync + provision + restart          (the day-to-day command)
  sync [dir…] [--prune-repo]  local repo → sexidium-data (never touches run/)
  stack                       create/update the stack from docker/stack.sexidium.yml
  provision                   run the init container and follow it to the stamp
  restart [--only NODE]       rolling worker-N → … → lobby → proxy
  down                        proxy first, then lobby, then workers
  status [--json] [--wait S]  the network validation (§9) — 13 checks
  test [suite…] [--no-sync]   scripts | gradle | bot, inside the host (§8)
  logs <node> [-n N] [-f]     any container: proxy | lobby | worker-N | init | db
  exec <node> -- <cmd…>       escape hatch
  db-init                     database + user + grants (idempotent)
  secrets [--show] [--rotate NAME]
  version                     preflight: Docker version + does the key work?

  pipeline deploy             rolling update: no disconnect, resumable, reversible (§5.2)
      --canary NODE           which node goes first (default: the last worker)
      --drain-timeout S       how long to wait for a node to empty (default 300)
      --on-drain-timeout      abort (default: repins nothing) | force (costs <=30s of
                              per-player experience state; --yes gated)
      --soak S                how long to watch a node before moving on (default 120)
      --allow-unhealthy NAMES named checks that already fail and may be assumed
      --allow-lobby-disconnect  accept the disconnect when there is only one lobby
      --maintenance-window    accept a network-wide disconnect to restart the only proxy
      --skip-tests            emergency only
      --skip-drain            do NOT drain before an unwind — only for a node whose API
                              no longer answers (otherwise it disconnects players)
  pipeline resume [--run-id]  continue an interrupted run from where it stopped
                              (refuses needs-human, a live lock, or a moved LATEST)
      --skip-drain            as above
  pipeline status             the lock, the current run, and every node's pin
  pipeline builds             what is in the build store
  pipeline abort [--force-unlock] unwind a run, or just release the lock
      --skip-drain            as above
  pipeline pin <node> <build> put one node on any retained build and restart it

  watch [--interval S]        status + autoscaler loop (--once, --observe-only)
  scale up|down               one scale action by hand, with the same rails

Global: -n/--dry-run  -y/--yes  -v/--verbose  --timeout S  --boot-timeout S
Exit:   0 ok · 1 operational failure · 2 usage · 3 auth (401/403)
        4 Portainer unreachable · 5 missing precondition
```

`--dry-run` keeps every `GET` (so the report is real) and only prints the mutations.
`down`, `sync --prune-repo` and `secrets --rotate` ask for confirmation; with no terminal they
fail rather than assume yes.

---

*Keeping this current: this page tracks `scripts/remote.sh`, `scripts/remote/*.py`,
`docker/stack.sexidium.yml`, `docker/node-entry.sh`, `docker/provision.sh`,
`docker/test-entry.sh`, `scripts/lib/{velocity,paper,sexidium,plugins,store}.sh` and
`scripts/test/run.sh`. Update it in the same change that adds a command or flag, adds/renames a
service, port or volume, changes the restart order or a node environment variable, changes what
lives in the shared install versus a node's working directory (`paper::provision_shared_install`,
`paper::link_shared_install`, `paper::seed_shared_maps`, `paper::link_shared_maps`), changes which
capability may write a map template (`NodeCapability.MAP_AUTHORITY`) or how a world is placed on a node
(`NodePlacementPlanner`, `PlacementDecider`, `PlacementReconciler`), changes how secrets are stored, or changes the build store / pin layout, the pipeline's stage
order, a rollback trigger or the autoscaler's thresholds and ceiling.*
