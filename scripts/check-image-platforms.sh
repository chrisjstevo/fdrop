#!/usr/bin/env bash
# Checks that every container image the repo uses runs natively on both x86-64 and ARM64 hosts (see "CPU architecture"
# in spec/build-and-dev-container.md): each must publish linux/amd64 and linux/arm64, and an image pinned by digest must
# be pinned to its multi-platform index, not to one platform's image. Needs registry access; uses podman, else docker.
#
#   scripts/check-image-platforms.sh                     # every image the repo uses
#   scripts/check-image-platforms.sh docker.io/x/y:1.0   # just these
set -euo pipefail

cd "$(dirname "$0")/.."

CLI="${CONTAINER_CLI:-$(command -v podman || command -v docker || true)}"
[ -n "$CLI" ] || { echo "check-image-platforms: needs podman or docker" >&2; exit 2; }

# Fully qualified references in tracked files, with Java's "…" + "…" joined so a digest split over two lines is whole.
# Dev container features (OCI artifacts, not images) and example placeholders are left out.
images="$*"
[ -n "$images" ] || images=$(git ls-files -z -- '*.yaml' '*.yml' '*.tf' '*.java' '*.xml' '*.json' '*.sh' '*.container' ':!*node_modules*' \
  | xargs -0 perl -0777 -ne 's/"\s*\+\s*"//g; print "$1\n" while m{((?:docker\.io|quay\.io|ghcr\.io|registry\.k8s\.io|mcr\.microsoft\.com|gcr\.io|public\.ecr\.aws)/[A-Za-z0-9._/-]+(?::[A-Za-z0-9._-]+)?(?:\@sha256:[0-9a-f]{64})?)}g' \
  | grep -v -e '^ghcr\.io/devcontainers/features/' -e '^ghcr\.io/example/' \
  | sort -u)

failed=0
for image in $images; do
  ref=$image
  [[ "$image" == *@sha256:* ]] && ref=$(echo "$image" | sed 's/:[^/@]*@/@/')   # the CLIs refuse a tag and a digest together
  if ! manifest=$("$CLI" manifest inspect "$ref" 2>&1); then
    echo "FAIL $image: cannot inspect: $(echo "$manifest" | tail -1)"
    failed=1
    continue
  fi
  arches=$(echo "$manifest" | grep -o '"architecture": *"[^"]*"' | sed 's/.*"\([^"]*\)"$/\1/' | sort -u | xargs || true)
  problems=()
  if ! echo "$manifest" | grep -q -e 'manifest\.list' -e 'image\.index'; then
    if [[ "$image" == *@sha256:* ]]; then
      problems+=("pinned to one platform's image, not the multi-platform index")
    else
      problems+=("one platform's image only, not a multi-platform image")
    fi
  else
    for want in amd64 arm64; do
      [[ " $arches " == *" $want "* ]] || problems+=("no linux/$want")
    done
  fi
  if [ ${#problems[@]} -eq 0 ]; then
    echo "ok   $image ($arches)"
  else
    echo "FAIL $image: $(IFS=';'; echo "${problems[*]}" | sed 's/;/; /g')"
    failed=1
  fi
done

exit $failed
