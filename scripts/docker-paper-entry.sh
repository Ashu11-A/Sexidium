#!/bin/sh
# Entrypoint for the docker-compose `paper` service. POSIX sh (bash may not exist yet). The image build
# has no network, so the toolchain + Bun are installed here at runtime (the container's bridge network
# resolves DNS + reaches the internet). Works on either an Alpine base (apk) or a Debian/Ubuntu base
# (apt-get), so a stale image doesn't break it. Then it builds the plugin and launches the server via
# scripts/init-paper.sh, seeded (from SEXIDIUM_* env) for the networked Postgres backend + the Discord
# bot. Server data is written under /workspace/test/paper (host ./test/paper via the repo bind mount).
set -eu

install_toolchain() {
  if command -v apk >/dev/null 2>&1; then
    apk add --no-cache bash curl git unzip python3 coreutils procps ca-certificates libstdc++ gcompat
  elif command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update
    apt-get install -y --no-install-recommends bash curl git unzip python3 python3-pip ca-certificates procps
    rm -rf /var/lib/apt/lists/*
  else
    echo "[docker-paper] neither apk nor apt-get found; cannot install the toolchain" >&2
    exit 1
  fi
}

# 1. Toolchain (idempotent).
if ! command -v bash >/dev/null 2>&1 || ! command -v git >/dev/null 2>&1 \
    || ! command -v python3 >/dev/null 2>&1 || ! command -v unzip >/dev/null 2>&1; then
  echo "[docker-paper] installing toolchain…"
  install_toolchain
fi

# 2. Bun (BotManager runs the Discord bot subprocess with it).
if ! command -v bun >/dev/null 2>&1; then
  echo "[docker-paper] installing bun…"
  curl -fsSL https://bun.sh/install | bash
fi
export PATH="/root/.bun/bin:$PATH"

cd /workspace
# The repo is bind-mounted (owned by the host user), so keep Gradle caches container-local.
export GRADLE_PROJECT_CACHE_DIR="${GRADLE_PROJECT_CACHE_DIR:-/tmp/sexidium-gradle-cache}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export JAVA_ARGS="${JAVA_ARGS:--Xms512M -Xmx1536M}"

echo "[docker-paper] building + launching Sexidium (DB=${SEXIDIUM_DB_TYPE:-sqlite}, bot=$([ -n "${SEXIDIUM_BOT_TOKEN:-}" ] && echo on || echo off), data=/workspace/test/paper)"
exec scripts/init-paper.sh
