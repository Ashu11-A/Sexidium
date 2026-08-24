#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# record-golden.sh
#
# Records the canonical "what does the provisioner actually DO, and in what
# order" trace for each PROFILE, into scripts/test/golden/.
#
# This is the gate for the scripts/lib refactor: the trace is recorded against
# the CURRENT script, and every refactored version must reproduce it byte for
# byte (scripts/test/run.sh). It is the only mechanism that catches an ORDERING
# regression, which is the failure mode this script has already had once — see
# the "AFTER the warm-up, not inside ensure_plugins" comment in init-paper.sh
# main(), which documents a bug where a config patch silently no-opped because
# it ran before the file it patches existed.
#
# TWO PROFILES, because there are two entrypoints and only one of them used to be
# covered:
#
#   init-paper   scripts/init-paper.sh   -- ONE standalone server on SQLite
#   provision    docker/provision.sh     -- the NETWORK: proxy + lobby + 3 workers
#
# The second one is the profile the update pipeline, the build store and the
# per-node jar pinning all modify, and it had no gate at all. Recording it BEFORE
# changing it is the whole point; without that there is nothing to diff a silent
# behaviour drift against.
#
# Usage:
#   scripts/test/record-golden.sh                   # replay fixtures, write both traces
#   scripts/test/record-golden.sh provision         # just one profile
#   scripts/test/record-golden.sh --refresh         # hit the network, re-record fixtures
#
# --refresh re-records the HTTP fixtures for EVERY profile (it clears the fixture
# directory first), because the fixtures are one shared, url-keyed store: refreshing
# for a single profile would delete the other's.
#
# The run is hermetic: SEXIDIUM_DRY_RUN=1 means no jar is downloaded, no Gradle
# build runs, no JVM is booted and nothing is written outside the scratch dir.
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
ROOT_DIR="$(cd -- "$SX_SCRIPT_DIR/../.." && pwd -P)"
GOLDEN_DIR="$SX_SCRIPT_DIR/golden"
FIXTURE_DIR="$SX_SCRIPT_DIR/fixtures/http"

PROFILES=(init-paper provision)

refresh=0
wanted=()
for arg in "$@"; do
    case "$arg" in
        --refresh) refresh=1 ;;
        -*) echo "record-golden.sh: unknown flag $arg" >&2 && exit 2 ;;
        *) wanted+=("$arg") ;;
    esac
done
# ENTRYPOINT is the pre-profiles interface; honour it so an existing invocation
# (scripts/test/run.sh before this change, a shell alias, a note in a doc) keeps working.
if [[ "${#wanted[@]}" -eq 0 && -n "${ENTRYPOINT:-}" ]]; then
    wanted=("$(basename "${ENTRYPOINT%.sh}")")
fi
[[ "${#wanted[@]}" -gt 0 ]] || wanted=("${PROFILES[@]}")

mkdir -p "$GOLDEN_DIR" "$FIXTURE_DIR"

scratch="$(mktemp -d "${TMPDIR:-/tmp}/sexidium-golden-XXXXXX")"
trap 'rm -rf "$scratch"' EXIT

# Each invocation gets its OWN server dir. Sharing one would let the first run's
# dry-run stub jars satisfy the second run's "already downloaded" short-circuits,
# silently dropping every download from the trace.
#
# profile_env <profile> <scratch-root>  -> the KEY=VALUE lines that profile needs.
#
# The network profile needs considerably more than the standalone one, and every value
# here mirrors what docker/stack.sexidium.yml sets in production. Two of them are not
# cosmetic:
#   SEXIDIUM_FORWARDING_SECRET  pinned so the run is reproducible. The secret VALUE never
#     reaches the trace (yaml::set redacts credential-shaped keys), only the fact that one
#     came from the environment rather than being generated.
#   SEXIDIUM_DB_TYPE            without it the nodes resolve their own SQLite and the whole
#     networked-backend configuration branch -- the one a network actually takes -- is
#     never exercised.
profile_env() {
    local profile="$1" root="$2"
    case "$profile" in
        init-paper)
            printf '%s\n' \
                "SERVER_DIR=$root/server" \
                "GRADLE_PROJECT_CACHE_DIR=$root/gradle" \
                "MENU_PACK_ZIP=$root/menu-pack.zip"
            ;;
        provision)
            printf '%s\n' \
                "NETWORK_DIR=$root/nodes" \
                "SHARED_DIR=$root/shared" \
                "SX_SHARED_INSTALL=$root/shared/install" \
                "SX_SHARED_PLUGINS=$root/shared/install/plugins" \
                "SX_SHARED_MAPS=$root/shared/install/maps" \
                "SX_SHARED_WORLDS=$root/shared/install/worlds" \
                "SX_ARTIFACT_CACHE=$root/artifacts" \
                "SX_STATE_DIR=$root/shared/install" \
                "GRADLE_PROJECT_CACHE_DIR=$root/gradle" \
                "MENU_PACK_ZIP=$root/menu-pack.zip" \
                "SX_NODES=lobby worker-1 worker-2 worker-3" \
                "SX_PORT_BASE=25566" \
                "SX_API_PORT_BASE=8800" \
                "SX_API_PORT_STRIDE=10" \
                "SX_PACK_PORT_BASE=26011" \
                "SX_BACKEND_BIND=0.0.0.0" \
                "SX_BACKEND_ADVERTISE=%s" \
                "SX_BOT_NODE=lobby" \
                "SX_DISABLE_AUTH_GATE=0" \
                "INSTALL_WORLDEDIT=0" \
                "INSTALL_AXIOM=0" \
                "SEXIDIUM_FORWARDING_SECRET=golden-trace-forwarding-secret" \
                "SEXIDIUM_API_TOKEN=golden-trace-api-token" \
                "SEXIDIUM_DB_TYPE=mysql" \
                "SEXIDIUM_DB_HOST=sexidiumdb" \
                "SEXIDIUM_DB_PORT=3306" \
                "SEXIDIUM_DB_NAME=sexidium" \
                "SEXIDIUM_DB_USER=sexidium" \
                "SEXIDIUM_DB_PASSWORD=golden-trace-db-password"
            ;;
        *)
            echo "record-golden.sh: unknown profile '$profile' (have: ${PROFILES[*]})" >&2
            return 2
            ;;
    esac
}

profile_script() {
    case "$1" in
        init-paper) printf '%s' "$ROOT_DIR/scripts/init-paper.sh" ;;
        provision) printf '%s' "$ROOT_DIR/docker/provision.sh" ;;
    esac
}

# run_profile <profile> <tag> <trace-file> [extra KEY=VALUE ...]
run_profile() {
    local profile="$1" tag="$2" trace="$3"
    shift 3
    local root="$scratch/$profile-$tag"
    rm -rf "${root:?}"
    mkdir -p "$root"
    local env_lines=()
    while IFS= read -r line; do env_lines+=("$line"); done < <(profile_env "$profile" "$root")
    env \
        SEXIDIUM_DRY_RUN=1 \
        SX_TRACE="$trace" \
        "${env_lines[@]}" \
        "$@" \
        bash "$(profile_script "$profile")" \
        >"$root.stdout.log" 2>"$root.stderr.log"
}

if [[ "$refresh" -eq 1 ]]; then
    echo "[golden] refreshing HTTP fixtures from the network (all profiles)"
    rm -f "$FIXTURE_DIR"/*.json "$FIXTURE_DIR/INDEX.txt"
    for profile in "${PROFILES[@]}"; do
        if ! run_profile "$profile" record "$scratch/discard-$profile.trace" \
            SX_HTTP_RECORD="$FIXTURE_DIR"; then
            echo "[golden] FAILED while recording fixtures for $profile; stderr tail:" >&2
            tail -30 "$scratch/$profile-record.stderr.log" >&2
            exit 1
        fi
    done
    echo "[golden] recorded $(find "$FIXTURE_DIR" -name '*.json' | wc -l) fixtures"
fi

for profile in "${wanted[@]}"; do
    golden_file="$GOLDEN_DIR/$profile.trace"
    if ! run_profile "$profile" replay "$scratch/$profile.trace" SX_HTTP_FIXTURES="$FIXTURE_DIR"; then
        echo "[golden] FAILED during the traced run of $profile; stderr tail:" >&2
        tail -30 "$scratch/$profile-replay.stderr.log" >&2
        exit 1
    fi
    cp "$scratch/$profile.trace" "$golden_file"
    echo "[golden] wrote $golden_file ($(wc -l <"$golden_file") operations)"
done
