# Schematics

Drop `.schem` (or legacy `.schematic`) files here and `scripts/init-paper.sh` copies them into
`test/paper/plugins/FastAsyncWorldEdit/schematics/` on the next run, where `//schem list` and
`//schem load <name>` find them.

Why this folder exists at all: the test server lives under `test/paper/`, which is **gitignored**, so a
schematic that only exists there is one `rm -rf test/paper` away from gone. This is the tracked copy.

## Workflow

```bash
# 1. put the build here (downloaded, or exported from another world)
cp ~/Downloads/medieval-tower.schem assets/schematics/

# 2. provision + boot the test server; the sync happens during provisioning
./scripts/init-paper.sh

# 3. in game (op yourself in the console first: op <yourname>)
//schem load medieval-tower
//paste            # -a to skip air, -o to paste at the copy origin
```

Saving in the other direction is manual and deliberate: `//schem save <name>` writes into the gitignored
server folder, so copy anything worth keeping back here by hand.

```bash
cp test/paper/plugins/FastAsyncWorldEdit/schematics/<name>.schem assets/schematics/
```

The sync only overwrites a server-side file when the copy here is **newer**, so a schematic re-saved
in-game under a name this folder also uses is not silently clobbered on the next provision.

Finished *maps* (a whole world, not a build) do not belong here — those are zipped world folders under
`assets/worlds/**`, bundled into the jar and extracted at runtime. See
[`docs/Prompt.worlds.md`](../../docs/Prompt.worlds.md).
