#!/usr/bin/env bash
# Runs Maven inside a Java 21 container with Podman, for hosts without JDK 21 or outside the dev container.
# The host's Podman API socket is passed through so Testcontainers integration tests can start containers.
#
#   scripts/podman-mvn.sh verify
#   scripts/podman-mvn.sh -pl fdrop-http -am test
set -euo pipefail

cd "$(dirname "$0")/.."

# The socket path as the Podman engine sees it (inside the Podman machine on macOS and Windows).
SOCKET="${PODMAN_SOCKET:-$(podman info --format '{{.Host.RemoteSocket.Path}}' | sed 's#^unix://##')}"

TTY=()
[ -t 0 ] && TTY=(-it)

exec podman run --rm ${TTY[@]+"${TTY[@]}"} \
  --network host \
  --security-opt label=disable \
  -v "$SOCKET:/var/run/docker.sock" \
  -e DOCKER_HOST=unix:///var/run/docker.sock \
  -e TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE="$SOCKET" \
  -e TESTCONTAINERS_RYUK_DISABLED="${TESTCONTAINERS_RYUK_DISABLED:-true}" \
  -v "$PWD:/w:z" \
  -v fdrop-m2:/root/.m2 \
  -w /w \
  docker.io/library/maven:3.9-eclipse-temurin-21 \
  ./mvnw -B -ntp "$@"
