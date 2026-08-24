# shellcheck shell=bash
# -----------------------------------------------------------------------------
# lib/yaml.sh -- thin wrappers over lib/py/yamlkv.py.
# -----------------------------------------------------------------------------

if [[ -n "${_SX_LIB_YAML:-}" ]]; then return 0; fi
_SX_LIB_YAML=1

# yaml::set [--step N] <file> <dotted.key> <value> [<dotted.key> <value> ...]
#
# --step is the indentation width of the FILE, not a style preference. Sexidium's own
# configs indent with 2 (the default); a third-party file that indents with 4 -- e.g.
# SkinsRestorer's config.yml -- must say so, because at the wrong step yamlkv matches
# nothing and inserts the key at its own depth instead, gluing a shallower duplicate onto
# the end of the real block. That is unparseable YAML, and it is how `/skin` was lost.
yaml::set() {
    local step=()
    if [[ "${1:-}" == "--step" ]]; then
        step=(--step "$2")
        shift 2
    fi
    local file="$1"
    shift
    [[ -f "$file" ]] || die "yaml::set: no such file: $file"
    need_cmd python3
    # The trace is COMMITTED (scripts/test/golden/*.trace) and this function is how the
    # forwarding secret, the database password and the API token reach a config file.
    # Trace the key, never the value, whenever the key names a credential -- the point of
    # the trace is "which key was written, in what order", and the value adds nothing that
    # is worth leaking into git.
    local args=("$@") i=0 value
    while [[ "$i" -lt "${#args[@]}" ]]; do
        value="${args[i + 1]}"
        case "${args[i]}" in
            *secret* | *password* | *token*) value="«redacted»" ;;
        esac
        sx_trace "yaml_set $(sx_rel "$file") ${args[i]}=$value"
        i=$((i + 2))
    done
    python3 "$SX_LIB_DIR/py/yamlkv.py" "${step[@]}" "$file" "$@"
}

# yaml::get [--step N] <file> <dotted.key>   -> prints the value, non-zero when absent
yaml::get() {
    local step=()
    if [[ "${1:-}" == "--step" ]]; then
        step=(--step "$2")
        shift 2
    fi
    local file="$1" key="$2"
    [[ -f "$file" ]] || return 1
    python3 "$SX_LIB_DIR/py/yamlkv.py" "${step[@]}" --get "$file" "$key"
}
