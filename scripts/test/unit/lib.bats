#!/usr/bin/env bats
#
# Unit tests for the pure helpers in scripts/lib. These cover the logic where a
# silent wrong answer is expensive: property round-tripping, the downgrade
# comparison that decides whether to quarantine worlds, and the version table
# that decides whether BetterHud's shader overlay matches the server.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    # set -u is deliberate: the libraries must be safe under the entrypoints'
    # `set -Eeuo pipefail`, and an unset-variable abort is a real failure mode
    # that only shows up outside the trace harness.
    set -u
    . "$SCRIPTS_DIR/lib/core.sh"
    sx::require props mcserver plugins
    TMP="$BATS_TEST_TMPDIR/work"
    mkdir -p "$TMP"
}

# --- core ---------------------------------------------------------------------

@test "core.sh resolves repo paths without relying on \$PWD" {
    cd /
    run bash -c ". '$SCRIPTS_DIR/lib/core.sh' && printf '%s' \"\$ROOT_DIR\""
    [ "$status" -eq 0 ]
    [ "$output" = "$(cd -- "$SCRIPTS_DIR/.." && pwd -P)" ]
}

@test "core.sh is safe under set -u with no harness variables set" {
    run env -u SX_TRACE -u SEXIDIUM_DRY_RUN -u SX_HTTP_FIXTURES -u SX_HTTP_RECORD \
        bash -c "set -Eeuo pipefail; . '$SCRIPTS_DIR/lib/core.sh'; sx_trace 'x'; sx_dry || true; sx_rel /tmp/a"
    [ "$status" -eq 0 ]
}

@test "sourcing a library twice is a no-op and does not abort the caller" {
    run bash -c "set -Eeuo pipefail; . '$SCRIPTS_DIR/lib/core.sh'; sx::require props; sx::require props; echo ok"
    [ "$status" -eq 0 ]
    [ "$output" = "ok" ]
}

@test "sx::on_exit handlers run in reverse registration order" {
    run bash -c "set -Eeuo pipefail; . '$SCRIPTS_DIR/lib/core.sh'; sx::on_exit 'echo first'; sx::on_exit 'echo second'; exit 0"
    [ "$status" -eq 0 ]
    [ "${lines[0]}" = "second" ]
    [ "${lines[1]}" = "first" ]
}

# --- props --------------------------------------------------------------------

@test "set_property inserts a key that is absent" {
    set_property "$TMP/p.properties" server-port 25566
    run prop_get "$TMP/p.properties" server-port 0
    [ "$output" = "25566" ]
}

@test "set_property replaces an existing key without duplicating it" {
    set_property "$TMP/p.properties" online-mode true
    set_property "$TMP/p.properties" online-mode false
    run prop_get "$TMP/p.properties" online-mode unset
    [ "$output" = "false" ]
    run grep -c '^online-mode=' "$TMP/p.properties"
    [ "$output" = "1" ]
}

@test "property values containing '=' survive a round trip" {
    set_property "$TMP/p.properties" motd "a=b=c"
    run prop_get "$TMP/p.properties" motd ""
    [ "$output" = "a=b=c" ]
}

@test "prop_get returns the fallback for a missing file and a missing key" {
    run prop_get "$TMP/absent.properties" any fallback-a
    [ "$output" = "fallback-a" ]
    set_property "$TMP/p2.properties" present 1
    run prop_get "$TMP/p2.properties" other fallback-b
    [ "$output" = "fallback-b" ]
}

# --- mcserver: the downgrade comparison --------------------------------------
# An inverted comparison here either never quarantines (worlds silently corrupted
# by an older server) or quarantines on every upgrade (worlds moved aside for no
# reason). Both are quiet, so this gets a direct test.

@test "version comparison detects a downgrade" {
    run bash -c 'printf "%s\n%s\n" "26.1.2" "26.0.1" | sort -V | head -1'
    [ "$output" = "26.0.1" ]
}

@test "version comparison treats an equal version as not-a-downgrade" {
    installed="26.1.2"
    requested="26.1.2"
    older="$(printf '%s\n%s\n' "$installed" "$requested" | sort -V | head -1)"
    [ "$older" = "$requested" ]
    [ "$installed" = "$requested" ]
}

# --- plugins: the BetterHud overlay table -------------------------------------

@test "betterhud_overlay_matches agrees with the pinned Paper version" {
    PAPER_VERSION="26.1.2"
    run betterhud_overlay_matches
    [ "$status" -eq 0 ]
}

@test "betterhud_overlay_matches rejects the version whose shaders it breaks" {
    PAPER_VERSION="26.2"
    run betterhud_overlay_matches
    [ "$status" -ne 0 ]
}

# --- velocity: the proxy's auth block ----------------------------------------
# The proxy IS the login gate and holds no bot config, so `auto` resolves through
# bot.enabled/bot.token and silently means OFF there. The seeded block is the fix,
# and it is only a fix while it keeps being written -- an existing proxy config is
# never regenerated, so the refresh below is the half that reaches a live network.

@test "the seeded proxy config sets the login gate explicitly, not to auto" {
    sx::require yaml
    config="$TMP/proxy-config.yml"
    printf 'network:\n  enabled: true\n' >"$config"
    yaml::set "$config" \
        auth.enabled true \
        auth.require-for-login "${SX_AUTH_GATE:-true}"
    [ "$(yaml::get "$config" auth.enabled)" = "true" ]
    [ "$(yaml::get "$config" auth.require-for-login)" = "true" ]
}

@test "SX_AUTH_ overrides land on the proxy auth block" {
    sx::require yaml
    config="$TMP/proxy-overrides.yml"
    printf 'network:\n  enabled: true\n' >"$config"
    # Unset: every layer stays off, which is the shipped answer for all four.
    yaml::set "$config" \
        auth.session.enabled "${SX_AUTH_SESSIONS:-false}" \
        auth.approval.enabled "${SX_AUTH_APPROVAL:-false}" \
        auth.premium.enabled "${SX_AUTH_PREMIUM:-false}" \
        auth.hold.enabled "${SX_AUTH_HOLD:-false}"
    [ "$(yaml::get "$config" auth.session.enabled)" = "false" ]
    [ "$(yaml::get "$config" auth.hold.enabled)" = "false" ]
    # Set: the override reaches the file, which is the half a re-provision depends on.
    SX_AUTH_SESSIONS=true
    SX_AUTH_APPROVAL=true
    yaml::set "$config" \
        auth.session.enabled "${SX_AUTH_SESSIONS:-false}" \
        auth.approval.enabled "${SX_AUTH_APPROVAL:-false}"
    [ "$(yaml::get "$config" auth.session.enabled)" = "true" ]
    [ "$(yaml::get "$config" auth.approval.enabled)" = "true" ]
}

@test "the auth-gate opt-out addresses require-for-login by path, not by regex" {
    sx::require yaml
    config="$TMP/backend-config.yml"
    printf 'auth:\n  require-for-login: auto\n  session:\n    enabled: false\n' >"$config"
    yaml::set "$config" auth.require-for-login false
    [ "$(yaml::get "$config" auth.require-for-login)" = "false" ]
    # The nested block must be untouched: the awk this replaced matched the FIRST
    # `require-for-login:` under `auth`, which a same-named nested key would have won.
    [ "$(yaml::get "$config" auth.session.enabled)" = "false" ]
}


# --- plugins: SkinsRestorer's config.yml --------------------------------------
# The regression these cover took `/skin` down on the proxy AND all three backends at
# once: an older provisioner appended the MySQL keys at 2-space indentation into a block
# SkinsRestorer indents with 4, YAML read the shallower line as a new mapping, and the
# plugin threw out of loadConfig() BEFORE registering a single command.
#
# Three separate ways to reproduce that outage, so there are three groups here:
#   * wrong DEPTH          -> the orphan block; the healer removes it.
#   * unquoted VALUE       -> an operator password containing `&`, `:` or ` #` writes
#                             YAML that will not parse. Same dead plugin, and the healer
#                             cannot repair it because it is not an indentation fault.
#   * disagreeing SIDES    -> proxy on MYSQL, backends on FILE. Parses fine, shares no
#                             skins at all.

sr_fixture() {
    cat >"$1" <<'YAML'
messages:
    locale: en_US
# Set database.type to FILE, MYSQL or POSTGRESQL.
database:
    # Database backend selection. Valid values: FILE, MYSQL, POSTGRESQL.
    type: FILE
    host: localhost
    port: 3306
    database: skinsrestorer
    username: root
    password: ''
    maxPoolSize: 10
    tablePrefix: sr_
    connectionOptions: sslMode=trust&serverTimezone=UTC

############
# Commands #
############

commands:
    perSkinPermissions: false
advanced:
    disableOnJoinSkins: true
YAML
}

sr_env() {
    SEXIDIUM_DB_TYPE=mysql
    SEXIDIUM_DB_HOST=sexidiumdb
    SEXIDIUM_DB_PORT=3306
    SEXIDIUM_DB_NAME=sexidium
    SEXIDIUM_DB_USER=sexidium
    SEXIDIUM_DB_PASSWORD=pw
}

# The value SkinsRestorer's own YAML parser would see, as a python repr so that `''` and
# `None` are distinguishable -- the difference between an empty password and no password.
sr_value() {
    python3 - "$1" "$2" <<'PY'
import sys, yaml
node = yaml.safe_load(open(sys.argv[1], encoding="utf-8"))
for part in sys.argv[2].split("."):
    node = node[part]
print(repr(node))
PY
}

@test "the SkinsRestorer database block is rewritten in place, never appended" {
    sr_env
    config="$TMP/sr.yml"
    sr_fixture "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    # One key each, at the file's OWN indentation -- not a second run at ours.
    [ "$(grep -c '^    type:' "$config")" = "1" ]
    [ "$(grep -c '[[:space:]]host:' "$config")" = "1" ]
    [ "$(sr_value "$config" database.type)" = "'MYSQL'" ]
    [ "$(sr_value "$config" database.host)" = "'sexidiumdb'" ]
    [ "$(sr_value "$config" database.port)" = "3306" ]
    # The keys we have no opinion about survive.
    [ "$(sr_value "$config" database.tablePrefix)" = "'sr_'" ]
    [ "$(sr_value "$config" advanced.disableOnJoinSkins)" = "False" ]
}

@test "an orphan database block left by an older provisioner is removed" {
    sr_env
    config="$TMP/sr-corrupt.yml"
    sr_fixture "$config"
    # Exactly what yamlkv.py at the wrong step produced: the same keys again, glued on
    # at ITS indentation, inside the block.
    printf "  type: 'MYSQL'\n  host: old\n  port: 3306\n  database: sexidium\n" >"$TMP/orphan"
    awk 'FNR==NR { orphan[FNR] = $0; n = FNR; next }
         { print }
         /^    connectionOptions:/ { for (i = 1; i <= n; i++) print orphan[i] }' \
        "$TMP/orphan" "$config" >"$config.x" && mv "$config.x" "$config"
    grep -q "^  type: 'MYSQL'$" "$config" # the fixture really is broken
    ! python3 -c "import sys,yaml; yaml.safe_load(open('$config'))" 2>/dev/null
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(grep -c '^  [a-z]' "$config")" = "0" ]
    [ "$(sr_value "$config" database.host)" = "'sexidiumdb'" ]
    [ "$(sr_value "$config" database.tablePrefix)" = "'sr_'" ]
}

@test "configuring an already-configured SkinsRestorer config changes nothing" {
    sr_env
    config="$TMP/sr-idem.yml"
    sr_fixture "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    cp "$config" "$TMP/first-pass"
    configure_skinsrestorer_config "$config" backend >/dev/null
    diff "$TMP/first-pass" "$config"
}

@test "a nested host: under the database block is neither dropped nor overwritten" {
    # The healer keeps deeper nesting on purpose. That is only worth anything if the
    # injection that runs immediately after leaves it alone too -- an un-anchored
    # `^[[:space:]]+host:` rewrote a read-replica host with the main database's.
    sr_env
    config="$TMP/sr-nested.yml"
    printf 'database:\n    type: FILE\n    connectionOptions:\n        host: readreplica.internal\n        port: 9999\n    tablePrefix: sr_\n' >"$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(sr_value "$config" database.connectionOptions.host)" = "'readreplica.internal'" ]
    [ "$(sr_value "$config" database.connectionOptions.port)" = "9999" ]
    [ "$(sr_value "$config" database.host)" = "'sexidiumdb'" ]
}

@test "a hostile database password still produces YAML the plugin can load" {
    # Operator-supplied (docker/stack.sexidium.yml), so the charset is arbitrary. Each of
    # these breaks a naive unquoted write in a different way: `&` is awk's "the matched
    # text" inside sub(), `: ` opens a nested mapping, ` #` opens a comment, a backslash
    # is eaten by `awk -v`, and a quote closes the scalar early.
    sr_env
    SEXIDIUM_DB_PASSWORD='a&b: c #d \e '"'"'f&'
    config="$TMP/sr-hostile.yml"
    sr_fixture "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(sr_value "$config" database.password)" = "$(printf '%s' "\"a&b: c #d \\\\e 'f&\"")" ]
    # And the rest of the document still parses -- i.e. it did not swallow the next key.
    [ "$(sr_value "$config" database.tablePrefix)" = "'sr_'" ]
    [ "$(sr_value "$config" commands.perSkinPermissions)" = "False" ]
}

@test "an empty database password is written as an empty string, not as null" {
    sr_env
    SEXIDIUM_DB_PASSWORD=''
    config="$TMP/sr-empty.yml"
    sr_fixture "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(sr_value "$config" database.password)" = "''" ]
}

@test "the healer measures indentation rather than counting characters" {
    # A tab is one character and four columns. Comparing LENGTHS scores a 2-space orphan
    # as DEEPER than a tab-indented block and keeps it; comparing the indent STRING does
    # not. YAML forbids tabs for indentation, so such a file is broken either way -- but
    # silently leaving the corruption in place is the failure this repair exists to end.
    config="$TMP/sr-tabs.yml"
    printf 'database:\n\ttype: FILE\n\ttablePrefix: sr_\n  type: MYSQL\n  host: old\n' >"$config"
    skinsrestorer::heal_orphan_database_keys "$config" backend >/dev/null
    [ "$(grep -c '^  ' "$config")" = "0" ]
    [ "$(grep -c '^	' "$config")" = "2" ]
}

@test "the database block is found even when its header carries a comment" {
    config="$TMP/sr-header.yml"
    printf 'database:   # storage\n    type: FILE\n  type: MYSQL\n' >"$config"
    skinsrestorer::heal_orphan_database_keys "$config" backend >/dev/null
    [ "$(grep -c '^  type: MYSQL$' "$config")" = "0" ]
}

@test "the repair keeps the permissions the config had" {
    sr_env
    config="$TMP/sr-perm.yml"
    sr_fixture "$config"
    chmod 600 "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(stat -c '%a' "$config")" = "600" ]
}

@test "a missing config is only seeded when the caller asks for it" {
    sr_env
    # No third argument: the historical behaviour, which creates nothing.
    configure_skinsrestorer_config "$TMP/absent/skinsrestorer/config.yml" proxy >/dev/null
    [ ! -e "$TMP/absent" ]
    configure_skinsrestorer_config "$TMP/seeded/skinsrestorer/config.yml" proxy seed >/dev/null
    [ "$(sr_value "$TMP/seeded/skinsrestorer/config.yml" database.type)" = "'MYSQL'" ]
    [ "$(sr_value "$TMP/seeded/skinsrestorer/config.yml" database.host)" = "'sexidiumdb'" ]
}

@test "proxy and backend seed the same bytes, which is what proxy mode requires" {
    # There is no template to copy from on a fresh network. Identity comes from both
    # sides running the same seed and the same six values -- so assert exactly that,
    # rather than a copy that would never happen in production.
    sr_env
    PLUGINS_DIR="$TMP/backend-node/plugins"
    configure_skinsrestorer_config "$TMP/p/skinsrestorer/config.yml" proxy seed >/dev/null
    configure_skinsrestorer_offline_skins seed >/dev/null
    diff "$TMP/p/skinsrestorer/config.yml" "$PLUGINS_DIR/SkinsRestorer/config.yml"
}

@test "without a shared database nothing is seeded and FILE storage is left alone" {
    SEXIDIUM_DB_TYPE=sqlite
    configure_skinsrestorer_config "$TMP/lan/skinsrestorer/config.yml" proxy seed >/dev/null
    [ ! -e "$TMP/lan" ]
    config="$TMP/sr-lan.yml"
    sr_fixture "$config"
    configure_skinsrestorer_config "$config" backend >/dev/null
    [ "$(sr_value "$config" database.type)" = "'FILE'" ]
}
