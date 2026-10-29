#!/bin/sh
# Writes PODMAN_SOCKET to .devcontainer/.env, which Docker Compose and podman-compose read for the dev container's
# compose.yaml. The dev container mounts the Podman API socket for Testcontainers, and its path depends on how
# Podman runs: /run/podman/podman.sock for a rootful machine, /run/user/<uid>/podman/podman.sock for a rootless one
# or rootless Linux. IDEs run this first, as devcontainer.json's initializeCommand; run it yourself before starting
# the stack from the command line. A PODMAN_SOCKET already set in the environment wins.
#
#   sh scripts/podman-socket-env.sh
set -eu

env_file="$(cd "$(dirname "$0")/.." && pwd)/.devcontainer/.env"

socket="${PODMAN_SOCKET:-}"
if [ -z "$socket" ] && command -v podman >/dev/null 2>&1; then
  # the path as Podman sees it: inside the Podman machine on macOS and Windows
  socket=$(podman info --format '{{.Host.RemoteSocket.Path}}' 2>/dev/null | sed 's#^unix://##' || true)
fi
if [ -z "$socket" ]; then
  echo "podman-socket-env: could not ask Podman for its socket (is the Podman machine running?);" \
    "the dev container will use /run/podman/podman.sock" >&2
  exit 0
fi

# keep any other settings in the file, replace only PODMAN_SOCKET
tmp="$env_file.tmp"
{
  [ -f "$env_file" ] && grep -v '^PODMAN_SOCKET=' "$env_file" || true
  echo "PODMAN_SOCKET=$socket"
} > "$tmp"
mv "$tmp" "$env_file"
echo "podman-socket-env: PODMAN_SOCKET=$socket"
