#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# run.sh — the provisioner test gate.
#
#   1. shellcheck   (-x so it follows `source scripts/lib/*.sh`)
#   2. shfmt        (format drift)
#   3. py_compile   (the extracted python helpers)
#   4. bats         (unit tests for the pure helpers)
#   5. golden       (init-paper.sh: same operations, same order)
#   6. golden-provision (docker/provision.sh: ditto, for the NETWORK profile)
#
# Every stage is skipped-with-a-warning rather than failed when its tool is
# absent, so a contributor without shellcheck still gets the golden-trace gate,
# which is the one that actually catches refactor regressions.
#
# ...but a skipped stage is NOT a passing stage. Off this workstation -- the
# stack's test container, a fresh checkout, CI -- none of the tools exist, and
# this script used to exit 0 announcing "5 stage(s) OK" having really run two.
# SX_TEST_STRICT=1 turns every skip into a failure; docker/test-entry.sh always
# exports it, so the authoritative gate can never be green by absence.
#
# Usage: scripts/test/run.sh [stage ...]     (default: all)
#        SX_TEST_STRICT=1 scripts/test/run.sh
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
SCRIPTS_DIR="$(cd -- "$SX_SCRIPT_DIR/.." && pwd -P)"

# Scripts not yet rebuilt on scripts/lib. shfmt wants a ~205-line restructure of
# init-paper.sh's line continuations, including moving `then` onto a `<<'PY'`
# heredoc line. That is a legitimate canonical form, but mixing it into the
# instrumentation change would bury it, and the heredocs are the one part of
# this script where a reindent is silently destructive (bodies and terminators
# must stay at column 0). These graduate into the shfmt scope in step 0.2, when
# each becomes a ~40-line entrypoint and the diff is reviewable.
#
# Static analysis still covers them today — only formatting is deferred.
# init-paper.sh graduated in step 0.2 (now a 38-line entrypoint); the NeoForge
# entrypoint left this list by being deleted (no NeoForge module in the build).
SHFMT_LEGACY=(install-world-tools.sh docker-paper-entry.sh)

PATH="$HOME/.local/bin:$PATH"
export PATH

failures=0
ran=0

note() { printf '\n=== %s ===\n' "$*"; }
skip() {
    printf '  SKIP: %s\n' "$*"
    if [[ "${SX_TEST_STRICT:-0}" == "1" ]]; then
        printf '  FAIL: stage skipped under SX_TEST_STRICT\n'
        failures=$((failures + 1))
    fi
}
pass() { printf '  PASS: %s\n' "$*"; }
fail() {
    printf '  FAIL: %s\n' "$*"
    failures=$((failures + 1))
}

# want <stage> "$@"  -> true when the caller asked for this stage, or asked for nothing.
# Note the explicit `if`s: a trailing `[[ ... ]] && return 0` makes the function
# return 1 when the test is false, which under `set -e` aborts the caller.
want() {
    local stage="$1"
    shift
    if [[ "$#" -eq 0 ]]; then
        return 0
    fi
    local s
    for s in "$@"; do
        if [[ "$s" == "$stage" ]]; then
            return 0
        fi
    done
    return 1
}

# Everything that is a versioned shell entrypoint, wherever it lives. docker/*.sh
# (node-entry, provision, test-entry) is production shell that runs inside the
# stack -- it belongs under the same static analysis as scripts/, and it is the
# half nobody can casually re-run by hand to discover a typo.
entrypoints() {
    local f
    for f in "$SCRIPTS_DIR"/init-*.sh "$SCRIPTS_DIR"/net.sh "$SCRIPTS_DIR"/remote.sh \
        "$SCRIPTS_DIR"/test/*.sh "$SCRIPTS_DIR"/../docker/*.sh; do
        [[ -f "$f" ]] && printf '%s\n' "$f"
    done
}

libs() {
    [[ -d "$SCRIPTS_DIR/lib" ]] || return 0
    find "$SCRIPTS_DIR/lib" -name '*.sh' -type f
}

# --- 1. shellcheck ------------------------------------------------------------
if want shellcheck "$@"; then
    note "shellcheck"
    ran=$((ran + 1))
    if command -v shellcheck >/dev/null 2>&1; then
        # shellcheck disable=SC2046  # deliberate word-splitting over the file list
        if shellcheck -x -S warning $(entrypoints) $(libs); then
            pass "no warnings"
        else
            fail "shellcheck reported findings"
        fi
    else
        skip "shellcheck not installed"
    fi
fi

# --- 2. shfmt -----------------------------------------------------------------
if want shfmt "$@"; then
    note "shfmt"
    ran=$((ran + 1))
    if command -v shfmt >/dev/null 2>&1; then
        checked=()
        while IFS= read -r f; do
            legacy=0
            for l in "${SHFMT_LEGACY[@]}"; do
                [[ "$(basename "$f")" == "$l" ]] && legacy=1
            done
            [[ "$legacy" -eq 0 ]] && checked+=("$f")
        done < <(
            entrypoints
            libs
        )
        if [[ "${#checked[@]}" -eq 0 ]]; then
            skip "nothing in scope yet"
        elif shfmt -d -i 4 -ci "${checked[@]}" >/dev/null; then
            pass "${#checked[@]} file(s) formatted (${#SHFMT_LEGACY[@]} legacy deferred)"
        else
            fail "formatting drift (run: shfmt -w -i 4 -ci <file>)"
        fi
    else
        skip "shfmt not installed"
    fi
fi

# --- 3. py_compile ------------------------------------------------------------
if want py "$@"; then
    note "python helpers"
    ran=$((ran + 1))
    # Every .py under scripts/ -- the provisioner helpers (lib/py) *and* the remote
    # CLI (scripts/remote/). A syntax error in the CLI is otherwise only found by
    # the person mid-deploy.
    pyfiles=()
    while IFS= read -r f; do pyfiles+=("$f"); done < <(
        find "$SCRIPTS_DIR" -name '*.py' -type f -not -path '*/__pycache__/*' | sort
    )
    if ! command -v python3 >/dev/null 2>&1; then
        skip "python3 not installed"
    elif [[ "${#pyfiles[@]}" -eq 0 ]]; then
        skip "no python helpers yet"
    elif python3 -m py_compile "${pyfiles[@]}"; then
        pass "py_compile clean (${#pyfiles[@]} file(s))"
    else
        fail "py_compile failed"
    fi
fi

# --- 4. bats ------------------------------------------------------------------
if want bats "$@"; then
    note "bats unit tests"
    ran=$((ran + 1))
    if ! command -v bats >/dev/null 2>&1; then
        skip "bats not installed"
    elif ! compgen -G "$SX_SCRIPT_DIR/unit/*.bats" >/dev/null; then
        skip "no unit tests yet"
    elif bats "$SX_SCRIPT_DIR"/unit/*.bats; then
        pass "unit tests green"
    else
        fail "unit tests failed"
    fi
fi

# --- 5. golden traces ---------------------------------------------------------
# One stage per PROFILE, because they gate different scripts and a contributor
# touching only docker/provision.sh wants to run only that one:
#   golden            scripts/init-paper.sh  (the standalone server)
#   golden-provision  docker/provision.sh    (the network: proxy + lobby + workers)
#
# The mechanism is identical for both -- record over the committed file, diff, and
# RESTORE the committed copy on divergence so a failing run never silently rewrites
# the thing it was supposed to be compared against.
check_golden() {
    local profile="$1" golden="$SX_SCRIPT_DIR/golden/$1.trace" saved
    if [[ ! -f "$golden" ]]; then
        skip "no recorded trace for $profile (run scripts/test/record-golden.sh --refresh)"
        return 0
    fi
    saved="$(mktemp)"
    cp "$golden" "$saved"
    if ! "$SX_SCRIPT_DIR/record-golden.sh" "$profile" >/dev/null 2>&1; then
        fail "record-golden.sh $profile errored"
    elif diff -u "$saved" "$golden"; then
        pass "$profile: $(wc -l <"$golden") operations, identical order"
    else
        fail "TRACE DIVERGED ($profile) — the refactor changed what the provisioner does"
        cp "$saved" "$golden" # keep the committed golden authoritative
    fi
    rm -f "$saved"
}

if want golden "$@"; then
    note "golden trace (init-paper)"
    ran=$((ran + 1))
    check_golden init-paper
fi

if want golden-provision "$@"; then
    note "golden trace (network provision)"
    ran=$((ran + 1))
    check_golden provision
fi

printf '\n----------------------------------------\n'
if [[ "$failures" -eq 0 ]]; then
    printf 'scripts/test/run.sh: %d stage(s) OK\n' "$ran"
else
    printf 'scripts/test/run.sh: %d FAILURE(S) across %d stage(s)\n' "$failures" "$ran"
fi
exit "$((failures > 0 ? 1 : 0))"
