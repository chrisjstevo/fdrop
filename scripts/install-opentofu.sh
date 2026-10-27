#!/bin/sh
# Installs OpenTofu at the version in fdrop-kubernetes/.opentofu-version into a folder (default ~/.local/bin),
# checking the release's SHA-256. Run by fdrop-kubernetes/check.sh when no matching tofu is installed, and by the dev
# container's postCreateCommand; safe to run again.
#
#   scripts/install-opentofu.sh [dir]
set -eu
repo=$(cd "$(dirname "$0")/.." && pwd)
version=$(cat "$repo/fdrop-kubernetes/.opentofu-version")
bin="${1:-$HOME/.local/bin}"
if [ -x "$bin/tofu" ] && "$bin/tofu" version | grep -q "v$version\$"; then
  echo "OpenTofu $version is installed"
  exit 0
fi
case "$(uname -m)" in
  x86_64) arch=amd64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) echo "no OpenTofu build for $(uname -m)" >&2; exit 1 ;;
esac
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
file="tofu_${version}_linux_${arch}.tar.gz"
base="https://github.com/opentofu/opentofu/releases/download/v${version}"
curl -fsSL -o "$tmp/$file" "$base/$file"
curl -fsSL -o "$tmp/sums" "$base/tofu_${version}_SHA256SUMS"
(cd "$tmp" && grep " $file\$" sums | sha256sum -c -)
mkdir -p "$bin"
tar -xzf "$tmp/$file" -C "$bin" tofu
echo "installed OpenTofu $version in $bin"
