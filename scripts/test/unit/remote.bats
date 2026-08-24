#!/usr/bin/env bats
#
# Unit tests for scripts/remote.sh + scripts/remote/util.py -- the parts of the
# remote CLI that can be wrong silently: the credential-file parser (a `source`
# here would execute whatever a .env contains, in the shell holding the Portainer
# API key), the Docker log demultiplexer, the sync exclusion rules (uploading the
# local `run/` would overwrite the players' worlds), and the restart order.

setup() {
    SCRIPTS_DIR="$(cd -- "$BATS_TEST_DIRNAME/../.." && pwd -P)"
    TMP="$BATS_TEST_TMPDIR/work"
    mkdir -p "$TMP"
    # Sourcing is safe: remote.sh only runs main() when executed directly.
    set -u
    . "$SCRIPTS_DIR/remote.sh"
}

py() { python3 -c "import sys; sys.path.insert(0, '$SCRIPTS_DIR/remote'); $1"; }

# --- env parser ---------------------------------------------------------------

@test "remote.env parser keeps KEY=VALUE pairs and drops comments and blanks" {
    printf '# comment\n\nSX_PORTAINER_URL=http://h:9000\nSX_PORTAINER_ENDPOINT=1\n' >"$TMP/env"
    run remote::parse_env_file "$TMP/env"
    [ "$status" -eq 0 ]
    [ "${lines[0]}" = "SX_PORTAINER_URL=http://h:9000" ]
    [ "${lines[1]}" = "SX_PORTAINER_ENDPOINT=1" ]
    [ "${#lines[@]}" -eq 2 ]
}

@test "remote.env parser never executes command substitution in a value" {
    printf 'SX_PORTAINER_KEY=$(touch %s/pwned)\n' "$TMP" >"$TMP/env"
    run remote::parse_env_file "$TMP/env"
    [ "$status" -eq 0 ]
    [ "$output" = 'SX_PORTAINER_KEY=$(touch '"$TMP"'/pwned)' ]
    [ ! -e "$TMP/pwned" ]
}

@test "remote.env parser strips surrounding quotes and keeps '=' inside values" {
    printf 'A="a=b=c"\n' >"$TMP/env"
    run remote::parse_env_file "$TMP/env"
    [ "$output" = "A=a=b=c" ]
}

@test "remote.env parser ignores lines that are not assignments" {
    printf 'not an assignment\n1BAD=x\nGOOD=x\n' >"$TMP/env"
    run remote::parse_env_file "$TMP/env"
    [ "$output" = "GOOD=x" ]
}

@test "the environment wins over the file" {
    printf 'SX_TEST_ONLY=from-file\n' >"$TMP/env"
    chmod 600 "$TMP/env"
    run bash -c "set -u; SX_TEST_ONLY=from-env; export SX_TEST_ONLY; \
        . '$SCRIPTS_DIR/remote.sh'; remote::load_env '$TMP/env'; printf '%s' \"\$SX_TEST_ONLY\""
    [ "$output" = "from-env" ]
}

# --- pure python helpers ------------------------------------------------------

@test "demux decodes a multiplexed docker log stream" {
    run py "import util; print(util.demux(b'\x01\x00\x00\x00\x00\x00\x00\x03abc'))"
    [ "$output" = "abc" ]
}

@test "demux passes plain TTY output through untouched" {
    run py "import util; print(util.demux(b'hello world'))"
    [ "$output" = "hello world" ]
}

@test "sync never uploads run/, build/ or node_modules" {
    run py "import util; print([util.excluded(p) for p in \
        ('run/lobby/world/level.dat','build/libs/x.jar','bot/node_modules/a','scripts/remote.sh')])"
    [ "$output" = "[True, True, True, False]" ]
}

@test "restart order is backends-last-first then the proxy" {
    run py "import util; print(' '.join(util.restart_order(['lobby','worker-1','worker-2','worker-3'])))"
    [ "$output" = "worker-3 worker-2 worker-1 lobby proxy" ]
}

@test "shutdown order takes the proxy down first" {
    run py "import util; print(' '.join(util.shutdown_order(['lobby','worker-1'])))"
    [ "$output" = "proxy lobby worker-1" ]
}

@test "api ports follow the node index, not a hardcoded table" {
    run py "import util; n=['lobby','worker-1','worker-2']; \
        print([util.api_port(n,x,8800,10) for x in n]+[util.api_port(n,'proxy',8800,10)])"
    [ "$output" = "[8800, 8810, 8820, None]" ]
}

@test "listening ports are read from /proc/net/tcp, LISTEN only" {
    # 63DE = 25566 (game port), 2260 = 8800 (API); the 01 row is an established
    # connection, not a listener, and must not count.
    run py "import util; print(sorted(util.listening_ports('''  sl  local_address rem_address   st
 0: 00000000:63DE 00000000:0000 0A
 1: 0100007F:0BB8 0100007F:1F90 01
 2: 00000000:2260 00000000:0000 0A''')))"
    [ "$output" = "[8800, 25566]" ]
}

@test "redact removes secret values from any text that gets printed" {
    run py "import util; print(util.redact('env: FORWARDING_SECRET=abcdef0123456789', ['abcdef0123456789']))"
    [ "$output" = "env: FORWARDING_SECRET=«redacted»" ]
}

@test "a secret fingerprint identifies a value without revealing it" {
    run py "import util; f=util.fingerprint('hunter2hunter2'); print(f.startswith('sha256:'), 'hunter2' in f)"
    [ "$output" = "True False" ]
}

@test "the CLI parses its whole command surface without a network" {
    run python3 "$SCRIPTS_DIR/remote/cli.py" --help
    [ "$status" -eq 0 ]
    [[ "$output" == *"update"* ]]
    [[ "$output" == *"status"* ]]
}

@test "a command with no credentials fails with exit 5, not a traceback" {
    run env -u SX_PORTAINER_URL -u SX_PORTAINER_KEY python3 "$SCRIPTS_DIR/remote/cli.py" status
    [ "$status" -eq 5 ]
    [[ "$output" != *"Traceback"* ]]
}
