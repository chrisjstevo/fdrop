#!/bin/sh
# Creates development certificates for fdrop, once: a CA, a server certificate for localhost (fdrop's HTTPS and
# mTLS listeners) and a client certificate for the app-demo application account. Run by the `certs` service of
# .devcontainer/compose.yaml and deploy/compose/compose.yaml; existing files are kept until a week before expiry.
#
#   scripts/dev-certs.sh <dir>
#
# Development only: the keys are world-readable so containers running as other users can read them.
set -eu

dir=${1:?usage: dev-certs.sh <dir>}
mkdir -p "$dir"
cd "$dir"

if [ -f ca.crt ] && [ -f server.crt ] && [ -f app-demo.crt ] \
    && openssl x509 -in server.crt -noout -checkend 604800 >/dev/null 2>&1; then
  echo "dev certificates in $dir are current"
  exit 0
fi

days=825
openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout ca.key -out ca.crt \
  -days 3650 -subj "/CN=fdrop dev CA/O=fdrop development" \
  -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

# issue <name> <subject> <subjectAltName> <extendedKeyUsage>
issue() {
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout "$1.key" -out "$1.csr" \
    -subj "$2" 2>/dev/null
  printf 'subjectAltName=%s\nextendedKeyUsage=%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\n' \
    "$3" "$4" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -out "$1.crt" -days "$days" \
    -extfile "$1.ext" 2>/dev/null
  rm -f "$1.csr" "$1.ext"
}

issue server "/CN=localhost/O=fdrop development" "DNS:localhost,IP:127.0.0.1,IP:::1" serverAuth
issue app-demo "/CN=app-demo/O=fdrop development" "DNS:app-demo.fdrop.local,URI:fdrop:app:app-demo" clientAuth
rm -f ca.srl
chmod 644 ./*.crt ./*.key
echo "created dev certificates in $dir: ca.crt, server.crt/.key (localhost), app-demo.crt/.key"
