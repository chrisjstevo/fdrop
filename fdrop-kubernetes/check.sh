#!/bin/sh
# The static checks and mocked tests of fdrop-kubernetes, run by the Maven build (see pom.xml):
#   ./check.sh validate   tofu fmt -check, and tofu validate for both environments
#   ./check.sh test       tofu test for every module and the kind environment (mocked providers, no cluster)
# TOFU names the tofu executable. Without it: tofu on PATH if it is the version in .opentofu-version, otherwise that
# version, downloaded once (checksum verified) into ~/.cache/opentofu/<version>/, as fdrop-web fetches its own Node.
set -eu
cd "$(dirname "$0")"

find_tofu() {
  version=$(cat .opentofu-version)
  if command -v tofu >/dev/null 2>&1 && tofu version | head -n 1 | grep -q "v$version\$"; then
    echo tofu
    return
  fi
  dir="${XDG_CACHE_HOME:-$HOME/.cache}/opentofu/$version"
  if [ ! -x "$dir/tofu" ]; then
    echo "OpenTofu $version is not installed; fetching it into $dir" >&2
    sh ../scripts/install-opentofu.sh "$dir" >&2
  fi
  echo "$dir/tofu"
}

tofu=${TOFU:-}
[ -n "$tofu" ] || tofu=$(find_tofu)
# providers are downloaded once and shared between the folders
export TF_PLUGIN_CACHE_DIR="${TF_PLUGIN_CACHE_DIR:-$HOME/.cache/opentofu/plugins}"
export TF_IN_AUTOMATION=1
mkdir -p "$TF_PLUGIN_CACHE_DIR"

init() {
  (cd "$1" && "$tofu" init -backend=false -input=false -no-color >/dev/null) || {
    echo "tofu init failed in $1" >&2
    exit 1
  }
}

case "${1:-}" in
validate)
  "$tofu" fmt -check -recursive -no-color
  for env in environments/kind environments/production-example; do
    init "$env"
    (cd "$env" && "$tofu" validate -no-color)
  done
  ;;
test)
  for dir in modules/fdrop modules/objectstore modules/postgres modules/keycloak environments/kind; do
    init "$dir"
    echo "== tofu test in $dir"
    (cd "$dir" && "$tofu" test -no-color)
  done
  ;;
*)
  echo "usage: check.sh validate|test" >&2
  exit 2
  ;;
esac
