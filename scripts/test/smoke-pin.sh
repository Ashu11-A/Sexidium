#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# smoke-pin.sh -- the ONE empirical question the whole per-node pin design rests on:
#
#   Does Paper load a plugin reached through a SYMLINK inside --add-extra-plugin-dir?
#
# Everything else in the build store follows from a yes. Each node's pluginjars/ holds
# symlinks -- to the shared third-party jars (one inode, N links) and to the build that
# node is pinned to -- and Paper is pointed at that directory. If the loader stats with
# a no-follow flag, or refuses a non-regular file, every node comes up with NO Sexidium
# plugin: the container is Running, Paper prints `Done (`, and the server serves
# nothing. That failure is silent, which is exactly why it gets its own boot test
# instead of a paragraph of confidence.
#
# The probe is deliberately a plugin that CANNOT enable: a plugin.yml naming a main
# class that does not exist. That makes the signal unambiguous in both directions --
#
#   discovered through the symlink -> Paper names it while failing to load it
#   not discovered                  -> Paper never mentions it at all
#
# -- and it means the test needs no compiler, no Bukkit API jar and no working plugin.
# A jar that loaded successfully would prove the same thing but would also be able to
# fail for a dozen unrelated reasons.
#
# The control arm matters as much as the probe: a second, REGULAR-FILE plugin jar goes
# into the same directory. If Paper mentions neither, the run proves nothing about
# symlinks (the flag was wrong, the directory was wrong, the boot died early) and the
# script says so rather than reporting a false FAIL.
#
# Usage:
#   scripts/test/smoke-pin.sh                       # uses test/paper/paper.jar
#   PAPER_JAR=/path/to/paper.jar scripts/test/smoke-pin.sh
#
# This is NOT part of scripts/test/run.sh: it boots a real JVM for ~60 s and needs a
# provisioned paper.jar, neither of which belongs in a gate that has to run in a
# container with no network. Run it by hand when the loader assumption is in question --
# a Paper major bump being the obvious occasion.
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd -- "$SX_SCRIPT_DIR/../.." && pwd -P)"

PAPER_JAR="${PAPER_JAR:-$ROOT_DIR/test/paper/paper.jar}"
BOOT_TIMEOUT="${BOOT_TIMEOUT:-180}"

say() { printf '[smoke-pin] %s\n' "$*"; }
die() {
    printf '[smoke-pin] FATAL: %s\n' "$*" >&2
    exit 1
}

command -v java >/dev/null 2>&1 || die "no java on PATH"
command -v zip >/dev/null 2>&1 || command -v python3 >/dev/null 2>&1 ||
    die "need zip or python3 to build the probe jars"
[[ -s "$PAPER_JAR" ]] || die "no paper.jar at $PAPER_JAR (set PAPER_JAR=…)"

work="$(mktemp -d "${TMPDIR:-/tmp}/sexidium-smoke-pin-XXXXXX")"
trap 'rm -rf "$work"' EXIT

server="$work/server"
store="$work/store"
mkdir -p "$server/pluginjars" "$server/plugins" "$store"

# --- the two probe jars -------------------------------------------------------
make_jar() {
    local dest="$1" name="$2"
    python3 - "$dest" "$name" <<'PY'
import sys, zipfile

dest, name = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(dest, "w") as jar:
    jar.writestr(
        "plugin.yml",
        # A main class that does not exist. Paper resolves plugin.yml at DISCOVERY and
        # the class at LOAD, so a jar reached through the symlink is named in the log
        # even though it can never enable -- which is the whole signal.
        f"name: {name}\nversion: 1.0.0\nmain: com.sexidium.smoke.{name}\napi-version: '1.21'\n",
    )
PY
}

make_jar "$store/SxPinLinked.jar" SxPinLinked # reached ONLY through a symlink
make_jar "$work/SxPinDirect.jar" SxPinDirect  # the control: a regular file

ln -s "$store/SxPinLinked.jar" "$server/pluginjars/SxPinLinked.jar"
cp "$work/SxPinDirect.jar" "$server/pluginjars/SxPinDirect.jar"

say "pluginjars/ contains one symlink and one regular file:"
ls -l "$server/pluginjars"

# --- a server that boots as fast as it can ------------------------------------
printf 'eula=true\n' >"$server/eula.txt"
cat >"$server/server.properties" <<'EOF'
online-mode=false
server-port=25599
level-type=minecraft\:flat
generate-structures=false
spawn-protection=0
max-world-size=1024
view-distance=2
simulation-distance=2
EOF

say "booting Paper (up to ${BOOT_TIMEOUT}s)…"
log="$work/boot.log"
(
    cd "$server" || exit 1
    # `stop` on stdin so the server shuts down on its own the moment it is up; the
    # timeout is the backstop for a boot that never reaches the console.
    printf 'stop\n' | timeout "$BOOT_TIMEOUT" java -Xms512M -Xmx1G \
        -jar "$PAPER_JAR" \
        --plugins "$server/plugins" \
        --add-extra-plugin-dir "$server/pluginjars" \
        nogui
) >"$log" 2>&1 || true

# --- verdict ------------------------------------------------------------------
linked=0
direct=0
grep -q 'SxPinLinked' "$log" && linked=1
grep -q 'SxPinDirect' "$log" && direct=1

say "log evidence: SxPinDirect(control)=$direct  SxPinLinked(symlink)=$linked"
grep -i 'SxPin' "$log" | head -10 | sed 's/^/    /' || true

if [[ "$direct" -eq 0 ]]; then
    say "INCONCLUSIVE: Paper did not mention the CONTROL jar either, so this run says"
    say "  nothing about symlinks -- the extra-plugin-dir flag, the paths or the boot"
    say "  itself is what failed. Boot log tail:"
    tail -25 "$log" | sed 's/^/    /'
    exit 2
fi

if [[ "$linked" -eq 1 ]]; then
    say "PASS -- Paper discovers and loads a plugin through a symlink in"
    say "  --add-extra-plugin-dir. The per-node pluginjars/ layout is sound; keep"
    say "  SX_PIN_MODE=link (the default)."
    exit 0
fi

say "FAIL -- Paper saw the regular file and NOT the symlink."
say "  Set SX_PIN_MODE=copy in the stack environment and re-provision: pluginjars/ then"
say "  holds real copies instead of links. Cost is one jar per node of disk; nothing"
say "  else in the store, the pin file, the rollback or the pipeline changes."
exit 1
