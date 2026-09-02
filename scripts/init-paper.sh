#!/usr/bin/env bash
set -Eeuo pipefail

# -----------------------------------------------------------------------------
# init-paper.sh -- provision and launch a single standalone Paper test server.
#
#   1. checks the JVM, drops stale jars if PAPER_VERSION moved, builds + copies the plugin jar
#   2. downloads Paper and the runtime plugin dependencies (matched to PAPER_VERSION)
#   3. configures eula / server.properties / Sexidium auth (this profile deliberately DISABLES the
#      login gate -- it is a test server you hop into with any nickname; SX_DISABLE_AUTH_GATE=0 keeps
#      the shipped `auto` instead, which is what the network profile does)
#   4. warms up once (if needed) so Geyser generates its default config, then
#      patches that config for offline LAN play
#   5. starts the server in the foreground
#
# The implementation lives in scripts/lib/*.sh, shared with docker/provision.sh
# (the network provisioner, which runs inside the `init` container). This file is
# only the standalone PROFILE: one server, SQLite,
# no proxy. Its public contract is frozen -- the same environment variables, the
# same defaults, the same `[init-paper]` log tag, the same foreground exec, and
# no new required flags. Callers that depend on it: scripts/docker-paper-entry.sh,
# docker-compose.yml, docs/operations/networking-bot-ranks.md, docs/reference/known-issues.md.
#
# Behaviour is pinned by scripts/test/golden/init-paper.trace: every mutating
# operation, in order. Run scripts/test/run.sh after touching anything here.
# -----------------------------------------------------------------------------

SX_SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# shellcheck source=lib/core.sh
. "$SX_SCRIPT_DIR/lib/core.sh"

sx::require props http modrinth spiget papermc java net mcserver console plugins sexidium paper report

main() {
    # defaults first: paper::provision's own preflight probes "$JAVA_BIN".
    paper::defaults
    paper::provision
    paper::start_foreground
}

main "$@"
