# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/store.sh -- the versioned build store and per-node jar pinning.
#
# The problem it solves: with one Sexidium jar in the shared install, "which build
# is worker-2 running" was unanswerable (there was one file, so the answer was
# always "the last one anybody installed") and UNCHOOSABLE (there was nowhere else
# for it to point). A rolling update needs both: it has to move one node at a time
# and it has to be able to move a node BACK.
#
# The whole design is two directories and a rename:
#
#   $SX_BUILD_STORE/<build-id>/     the artifact, immutable once finalised
#   <node>/pluginjars/              symlinks only -- the dir Paper is pointed at
#   <node>/sexidium-build.pin       which build that node resolves, and which it
#                                   resolved before
#
# A roll is `ln -s` + `mv -T` (rename(2), atomic). A rollback is the identical call
# with the pin file's `previous=`. There is no restore-from-backup path and no state
# to reconstruct, which is what makes an interrupted run harmless.
#
# NOT the download cache. lib/http.sh's $SX_ARTIFACT_CACHE is keyed by sha1(url) and
# answers "did we already download these bytes"; this holds artifacts we BUILT, keyed
# by their own content. Different questions, deliberately different directories.
#
# Sourced via lib/core.sh's sx::require. Libraries never set shell options and never
# install traps.
# -----------------------------------------------------------------------------

if [[ -n "${_SX_LIB_STORE:-}" ]]; then return 0; fi
_SX_LIB_STORE=1

# shellcheck disable=SC2034  # cross-library globals, same rationale as paper::defaults
store::defaults() {
    # Beside the shared install, on the same volume every container mounts -- a node
    # has to be able to RESOLVE the symlink its pluginjars/ holds, so the target
    # cannot live anywhere only `init` can see.
    SX_BUILD_STORE="${SX_BUILD_STORE:-${SX_SHARED_INSTALL:-$SHARED_DIR/install}/builds}"
    # How many builds survive a GC by counter alone. Anything a node still references
    # (build= or previous=) is kept regardless, so this number bounds disk, never
    # rollback reach.
    SX_BUILD_RETENTION="${SX_BUILD_RETENTION:-10}"
    # link | copy. `link` is the design; `copy` is the escape hatch for the one
    # unvalidated assumption underneath it -- that Paper loads a plugin through a
    # symlink inside --add-extra-plugin-dir. One variable, read in one function, so
    # the fallback is a redeploy rather than a rewrite. See scripts/test/smoke-pin.sh.
    SX_PIN_MODE="${SX_PIN_MODE:-link}"
    # Whether `init` moves every node's pin onto the build it just staged.
    #
    # 1 (default) preserves the historical contract of `remote.sh update` exactly:
    # provision, restart, and every node is running the code you just pushed. The
    # ROLLING pipeline sets 0, because its whole job is to move pins one node at a
    # time between a drain and a restart -- staging and adoption are separate events
    # there, and that separation is what makes an interrupted run harmless.
    SX_ADOPT_BUILD="${SX_ADOPT_BUILD:-1}"

    SX_PAPER_JAR_NAME="${SX_PAPER_JAR_NAME:-Sexidium-Paper-1.0.0.jar}"
    SX_VELOCITY_JAR_NAME="${SX_VELOCITY_JAR_NAME:-Sexidium-Velocity-1.0.0.jar}"
}

store::sha256() {
    sha256sum "$1" | awk '{ print $1 }'
}

store::path() {
    printf '%s/%s' "$SX_BUILD_STORE" "$1"
}

# store::manifest_get <build-id> <key>  -> the value, empty when absent
store::manifest_get() {
    local file
    file="$(store::path "$1")/manifest.txt"
    [[ -f "$file" ]] || return 0
    sed -n "s/^$2=//p" "$file" | head -1
}

# store::pin_get <node-dir> <key>  -> the value from sexidium-build.pin, empty when absent
store::pin_get() {
    local file="$1/sexidium-build.pin"
    [[ -f "$file" ]] || return 0
    sed -n "s/^$2=//p" "$file" | head -1
}

# store::find_by_sha <sha256>  -> an existing build id holding exactly these bytes
#
# This is what makes a re-run over unchanged source a no-op instead of a new store
# entry with a new number: the id carries the content hash, so the lookup is a glob.
# The manifest is re-checked rather than trusting the directory name, because the name
# is 12 hex characters and the manifest holds all 64.
store::find_by_sha() {
    local want="$1" dir id
    [[ -d "$SX_BUILD_STORE" ]] || return 0
    for dir in "$SX_BUILD_STORE"/b*-"${want:0:12}"; do
        [[ -d "$dir" ]] || continue
        id="$(basename "$dir")"
        [[ "$(store::manifest_get "$id" sha256)" == "$want" ]] || continue
        printf '%s' "$id"
        return 0
    done
}

# The monotonic half of the build id. Under flock, for the same reason download_to
# takes one: two `init` runs (or an init and a pipeline) racing here would hand out
# the same number and the second finalise would land in the first's directory.
store::next_counter() {
    local counter="$SX_BUILD_STORE/COUNTER" lock_fd="" value=0
    mkdir -p "$SX_BUILD_STORE"
    if command -v flock >/dev/null 2>&1; then
        exec {lock_fd}>"$counter.lock"
        flock "$lock_fd"
    fi
    [[ ! -s "$counter" ]] || value="$(<"$counter")"
    [[ "$value" =~ ^[0-9]+$ ]] || value=0
    value=$((value + 1))
    printf '%s\n' "$value" >"$counter.tmp"
    mv -f "$counter.tmp" "$counter"
    [[ -z "$lock_fd" ]] || exec {lock_fd}>&-
    printf '%s' "$value"
}

# store::stage <paper-jar> <velocity-jar>  -> prints the build id
#
# Finalises builds/<id>/ out of two freshly built artifacts. Everything that decides
# WHICH build a node runs happens elsewhere: staging never touches a node directory,
# never touches $SX_SHARED_PLUGINS and never writes the provisioning stamp. A compile
# failure upstream therefore changes nothing on the network.
store::stage() {
    local paper="$1" velocity="${2:-}" sha id dir counter

    [[ -s "$paper" ]] || die "store::stage: no Paper jar at $paper"
    sha="$(store::sha256 "$paper")"

    id="$(store::find_by_sha "$sha")"
    if [[ -n "$id" ]]; then
        log "Build $id already in the store (same bytes); staging is a no-op"
        printf '%s' "$id"
        return 0
    fi

    counter="$(store::next_counter)"
    id="$(printf 'b%04d-%.12s' "$counter" "$sha")"
    dir="$(store::path "$id")"

    # Assembled under .staging and RENAMED into place, so a build id either exists
    # complete or does not exist. A half-written store entry is indistinguishable
    # from a good one to every reader here, and the reader that matters resolves it
    # into a running JVM's classpath.
    local staging="$SX_BUILD_STORE/.staging.$id"
    rm -rf "$staging"
    mkdir -p "$staging"
    sexidium::install_jar "$paper" "$staging"
    [[ -z "$velocity" || ! -s "$velocity" ]] || sexidium::install_jar "$velocity" "$staging"

    {
        printf 'build=%s\n' "$id"
        printf 'sha256=%s\n' "$sha"
        if [[ -n "$velocity" && -s "$velocity" ]]; then
            printf 'velocity-sha256=%s\n' "$(store::sha256 "$velocity")"
        fi
        printf 'built-at=%s\n' "$(date -Is)"
        printf 'paper-version=%s\n' "${PAPER_VERSION:-unknown}"
        printf 'velocity-version=%s\n' "${VELOCITY_VERSION:-unknown}"
        # Best-effort: the `init` container has git, but a tarball sync has no .git.
        # A build with no commit recorded is still a valid build; a build that FAILED
        # to stage because git was missing would not be.
        if command -v git >/dev/null 2>&1 && git -C "$ROOT_DIR" rev-parse HEAD >/dev/null 2>&1; then
            printf 'git-commit=%s\n' "$(git -C "$ROOT_DIR" rev-parse HEAD)"
            printf 'git-dirty=%s\n' "$(git -C "$ROOT_DIR" diff --quiet 2>/dev/null && echo 0 || echo 1)"
        fi
    } >"$staging/manifest.txt"

    mv -T "$staging" "$dir"
    # The last build STAGED, which is not the same question as "what is any node
    # running": docker/node-entry.sh copies it into the provisioning stamp so the stamp
    # names the deploy TARGET by id. During a rolling update the two differ on purpose.
    printf '%s\n' "$id" >"$SX_BUILD_STORE/LATEST.tmp"
    mv -f "$SX_BUILD_STORE/LATEST.tmp" "$SX_BUILD_STORE/LATEST"
    sx_trace "store stage $id"
    log "Staged build $id into $dir"
    printf '%s' "$id"
}

# store::snapshot_deps <build-id>
#
# Hard-links the third-party jars of $SX_SHARED_PLUGINS into builds/<id>/deps/ and
# records their digests in the manifest. Free (same filesystem, one inode) and it is
# what makes a --refresh-plugins run rollback-able: those jars are SHARED, so
# restoring them is a fleet-wide operation and needs a fleet-wide snapshot to restore
# FROM. Recorded at INSTALL, not at BUILD -- the dependency set is a property of the
# install, not of the compile.
store::snapshot_deps() {
    local id="$1" dir jar name
    dir="$(store::path "$id")"
    [[ -d "$dir" ]] || return 0
    mkdir -p "$dir/deps"
    for jar in "$SX_SHARED_PLUGINS"/*.jar; do
        [[ -f "$jar" ]] || continue
        name="$(basename "$jar")"
        [[ -e "$dir/deps/$name" ]] || ln "$jar" "$dir/deps/$name" 2>/dev/null || cp "$jar" "$dir/deps/$name"
        grep -q "^dep:$name=" "$dir/manifest.txt" 2>/dev/null ||
            printf 'dep:%s=%s\n' "$name" "$(store::sha256 "$jar")" >>"$dir/manifest.txt"
    done
}

# store::place <src> <dest>   -- one symlink (or one copy under SX_PIN_MODE=copy)
#
# The `.tmp.$$` + `mv -T` is the atomic swap and the ONLY way a pin ever changes.
# `mv -T` over an existing symlink is rename(2): a node restarting at that instant
# resolves the old target or the new one, never a missing file.
store::place() {
    local src="$1" dest="$2" tmp="$2.pin.tmp.$$"
    rm -f "$tmp"
    if [[ "${SX_PIN_MODE:-link}" == "copy" ]]; then
        cp "$src" "$tmp"
    else
        ln -s "$src" "$tmp"
    fi
    mv -Tf "$tmp" "$dest"
}

# store::link_node_plugin_jars <node-dir>
#
# Builds/refreshes <node>/pluginjars/ -- one entry per THIRD-PARTY jar in the shared
# install, plus the pinned Sexidium jar (added separately by store::pin_node).
#
# A SIBLING of plugins/, never inside it, and that placement is what keeps
# paper::link_shared_install's `find "$PLUGINS_DIR" -maxdepth 1 -name '*.jar' -delete`
# correct without a special case: a jar is visible through exactly one of --plugins
# and --add-extra-plugin-dir, so there is still no double-load path.
#
# Stale entries are PRUNED, which is how a paper::drop_jar_unless removal in the
# shared tree actually reaches the nodes. Without it, "the stack says
# INSTALL_WORLDEDIT=0" would keep meaning "and yet every node loads FAWE" -- the exact
# failure the shared install was introduced to end, reintroduced one level down.
store::link_node_plugin_jars() {
    local dir="$1" jars="$1/pluginjars" jar name
    mkdir -p "$jars"

    for jar in "$SX_SHARED_PLUGINS"/*.jar; do
        [[ -f "$jar" ]] || continue
        name="$(basename "$jar")"
        # The Sexidium jar is never linked from here: it is PINNED, per node, and
        # the shared tree is no longer where it lives.
        [[ "$name" != "$SX_PAPER_JAR_NAME" ]] || continue
        store::place "$jar" "$jars/$name"
    done

    for jar in "$jars"/*.jar; do
        [[ -e "$jar" || -L "$jar" ]] || continue
        name="$(basename "$jar")"
        [[ "$name" != "$SX_PAPER_JAR_NAME" ]] || continue
        # Two removals, one rule: an entry whose shared original is gone (a dropped
        # plugin), and a dangling link (a shared tree rebuilt underneath us). Both
        # mean "this node would load something nobody intends".
        if [[ ! -e "$SX_SHARED_PLUGINS/$name" ]] || [[ ! -e "$jar" ]]; then
            log "Pruning $name from $(basename "$dir")/pluginjars (no longer in the shared install)"
            rm -f "$jar"
        fi
    done
    sx_trace "pluginjars $(basename "$dir") mode=${SX_PIN_MODE:-link}"
}

# store::pin_node <node-dir> <build-id> [--record-previous]
#
# The atomic swap, and therefore also the rollback. Refuses before it touches
# anything: a pin that resolves to a jar whose bytes are not the ones the manifest
# describes is worse than no pin at all, because every later comparison would agree
# with it.
store::pin_node() {
    local dir="$1" id="$2" record="${3:-}"
    local build_dir jar previous now

    build_dir="$(store::path "$id")"
    jar="$build_dir/$SX_PAPER_JAR_NAME"
    [[ -s "$jar" ]] || die "store::pin_node: build $id has no $SX_PAPER_JAR_NAME"
    local want have
    want="$(store::manifest_get "$id" sha256)"
    have="$(store::sha256 "$jar")"
    [[ -z "$want" || "$want" == "$have" ]] ||
        die "store::pin_node: $jar ($have) does not match manifest $id ($want); refusing to pin"

    mkdir -p "$dir/pluginjars"
    store::place "$jar" "$dir/pluginjars/$SX_PAPER_JAR_NAME"

    previous=""
    if [[ "$record" == "--record-previous" ]]; then
        previous="$(store::pin_get "$dir" build)"
        # Repinning to what is already pinned must not erase the rollback target.
        [[ "$previous" != "$id" ]] || previous="$(store::pin_get "$dir" previous)"
    else
        previous="$(store::pin_get "$dir" previous)"
    fi

    now="$dir/sexidium-build.pin"
    {
        printf 'build=%s\n' "$id"
        printf 'sha256=%s\n' "$have"
        printf 'pinned-at=%s\n' "$(date -Is)"
        printf 'pinned-by=%s\n' "${SX_PIN_ACTOR:-provision}"
        [[ -z "$previous" ]] || printf 'previous=%s\n' "$previous"
    } >"$now.tmp"
    mv -f "$now.tmp" "$now"
    store::sync_node_args "$dir" "$id"
    sx_trace "pin $(basename "$dir") build=$id${previous:+ previous=$previous}"
    log "Pinned $(basename "$dir") to build $id${previous:+ (was $previous)}"
}

# store::sync_node_args <node-dir> <build-id>
#
# Rewrites the single `-Dsexidium.build.id=` line in sexidium-node.args.
#
# Called FROM store::pin_node rather than from the provisioner, and that is the point:
# the pipeline flips pins through a helper container without running provision.sh at
# all, so anything that lived only in the provisioner would go stale the moment a roll
# or a rollback happened. One function moves both halves or neither.
#
# The JVM reads the value as configuration().getString("build.id") and writes it to
# network_nodes.plugin_version, which is what lets the pipeline ask the DATABASE
# "did this node actually take the build I pinned?" instead of inferring it from a log.
store::sync_node_args() {
    local dir="$1" id="$2" args="$1/sexidium-node.args"
    [[ -f "$args" ]] || return 0
    [[ -n "$id" ]] || return 0
    grep -v '^-Dsexidium\.build\.id=' "$args" >"$args.tmp"
    printf -- '-Dsexidium.build.id=%s\n' "$id" >>"$args.tmp"
    mv -f "$args.tmp" "$args"
}

# store::pin_proxy <proxy-dir> <build-id>
#
# Velocity has no --add-extra-plugin-dir: it scans plugins/ relative to its working
# directory. So the proxy's pin is the symlink AT that path, written by the same
# primitive, and its pin file lives beside the backends' for the same reader.
store::pin_proxy() {
    local dir="$1" id="$2" jar
    jar="$(store::path "$id")/$SX_VELOCITY_JAR_NAME"
    if [[ ! -s "$jar" ]]; then
        log "Build $id carries no $SX_VELOCITY_JAR_NAME; leaving the proxy plugin as it is"
        return 0
    fi
    mkdir -p "$dir/plugins"
    store::place "$jar" "$dir/plugins/$SX_VELOCITY_JAR_NAME"
    {
        printf 'build=%s\n' "$id"
        printf 'sha256=%s\n' "$(store::sha256 "$jar")"
        printf 'pinned-at=%s\n' "$(date -Is)"
        printf 'pinned-by=%s\n' "${SX_PIN_ACTOR:-provision}"
    } >"$dir/sexidium-build.pin.tmp"
    mv -f "$dir/sexidium-build.pin.tmp" "$dir/sexidium-build.pin"
    sx_trace "pin proxy build=$id"
    log "Pinned proxy to build $id"
}

# store::referenced_builds  -> every build id any node still points at, one per line
#
# BOTH `build=` and `previous=`, because `previous=` is the rollback target and a GC
# that collected it would turn a one-rename recovery into a rebuild under pressure.
store::referenced_builds() {
    local pin
    [[ -n "${NETWORK_DIR:-}" ]] || return 0
    for pin in "$NETWORK_DIR"/*/sexidium-build.pin; do
        [[ -f "$pin" ]] || continue
        sed -n -e 's/^build=//p' -e 's/^previous=//p' "$pin"
    done
}

# store::gc
#
# Keeps the newest $SX_BUILD_RETENTION by counter, PLUS everything any node references,
# PLUS anything marked PROMOTED in the last 30 days. Called only at the END of a fully
# green run and never from `init` -- a provision that could delete a rollback target
# would make "the deploy failed" and "you cannot go back" the same event.
store::gc() {
    local keep_n="${SX_BUILD_RETENTION:-10}" dir id
    [[ -d "$SX_BUILD_STORE" ]] || return 0

    local -A keep=()
    while IFS= read -r id; do
        [[ -z "$id" ]] || keep["$id"]=1
    done < <(store::referenced_builds)

    local all=()
    for dir in "$SX_BUILD_STORE"/b*; do
        [[ -d "$dir" ]] || continue
        all+=("$(basename "$dir")")
    done
    # An EMPTY store must return here, not fall through. `printf '%s\n' "${all[@]}"` on an
    # empty array still emits one blank line, which would become an empty id -- and the
    # removal below would then expand to the store directory itself.
    [[ "${#all[@]}" -gt 0 ]] || return 0
    # The counter is zero-padded to four digits, so a lexical sort IS the numeric one
    # until b9999 -- and the id is designed that way precisely so this stays true.
    local sorted=()
    while IFS= read -r id; do
        [[ -n "$id" ]] && sorted+=("$id")
    done < <(printf '%s\n' "${all[@]}" | sort -r)

    local i=0
    for id in "${sorted[@]}"; do
        if [[ "$i" -lt "$keep_n" ]]; then
            keep["$id"]=1
        elif [[ -f "$SX_BUILD_STORE/$id/PROMOTED" ]] &&
            [[ -n "$(find "$SX_BUILD_STORE/$id/PROMOTED" -mtime -30 2>/dev/null)" ]]; then
            keep["$id"]=1
        fi
        i=$((i + 1))
    done

    for id in "${sorted[@]}"; do
        [[ -z "${keep[$id]:-}" ]] || continue
        # Belt and braces on the shape of the id. `rm -rf` with an empty or unexpected
        # value here would take the store with it, and the store is where every rollback
        # target lives.
        [[ "$id" =~ ^b[0-9]{4}-[0-9a-f]{12}$ ]] || continue
        log "GC: removing build $id (beyond retention, referenced by nothing)"
        rm -rf "${SX_BUILD_STORE:?}/$id"
    done
}
