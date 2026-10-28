#!/bin/sh
# Creates development certificates for fdrop: a CA, a server certificate for localhost (fdrop's HTTPS and mTLS
# listeners), a client certificate for the app-demo application account, and for the dev container's PostgreSQL a
# server certificate (postgres) and fdrop's client certificate (fdrop-db, CN=fdrop, the DB user), and when ssh-keygen
# is installed, fixed SSH host keys for the two dev SFTP destinations. Run by the `certs`
# service of .devcontainer/compose.yaml and deploy/compose/compose.yaml. The CA is kept while it is valid, and each
# certificate is kept until a week before it expires, so adding a certificate here never replaces the others.
#
#   scripts/dev-certs.sh <dir>
#
# Development only: the keys are world-readable so containers running as other users can read them (PostgreSQL
# copies its key with stricter permissions before starting). Keys are PKCS#8, which fdrop's DB connection requires.
set -eu

dir=${1:?usage: dev-certs.sh <dir>}
mkdir -p "$dir"
cd "$dir"

days=825
week=604800

# current <name>: the certificate exists and is valid for at least another week
current() {
  [ -f "$1.crt" ] && [ -f "$1.key" ] && openssl x509 -in "$1.crt" -noout -checkend "$week" >/dev/null 2>&1
}

made=""
if ! current ca; then
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout ca.key -out ca.crt \
    -days 3650 -subj "/CN=fdrop dev CA/O=fdrop development" \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  # a new CA invalidates everything it signed before
  rm -f server.crt app-demo.crt postgres.crt fdrop-db.crt
  made="$made ca"
fi

# issue <name> <subject> <subjectAltName> <extendedKeyUsage>
issue() {
  if current "$1"; then
    return
  fi
  openssl req -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -keyout "$1.key" -out "$1.csr" \
    -subj "$2" 2>/dev/null
  printf 'subjectAltName=%s\nextendedKeyUsage=%s\nbasicConstraints=CA:FALSE\nkeyUsage=critical,digitalSignature\n' \
    "$3" "$4" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -out "$1.crt" -days "$days" \
    -extfile "$1.ext" 2>/dev/null
  rm -f "$1.csr" "$1.ext"
  made="$made $1"
}

issue server "/CN=localhost/O=fdrop development" "DNS:localhost,IP:127.0.0.1,IP:::1" serverAuth
issue app-demo "/CN=app-demo/O=fdrop development" "DNS:app-demo.fdrop.local,URI:fdrop:app:app-demo" clientAuth
issue postgres "/CN=localhost/O=fdrop development" "DNS:localhost,IP:127.0.0.1,IP:::1" serverAuth
issue fdrop-db "/CN=fdrop/O=fdrop development" "DNS:fdrop.db-client.local" clientAuth
rm -f ca.srl
chmod 644 ./*.crt ./*.key

# hostkey <name>: a fixed Ed25519 host key for a dev SFTP destination (sftp-target, sftp-target2), so a host key pinned
# in fdrop's admin screens still matches after the container is recreated to install fdrop's client key. No .key
# suffix: sshd refuses a host key others can read, so these stay owner-only.
hostkey() {
  if [ -f "$1_ssh_host_ed25519_key" ] || ! command -v ssh-keygen >/dev/null 2>&1; then
    return
  fi
  ssh-keygen -q -t ed25519 -N "" -C "$1 (fdrop development)" -f "$1_ssh_host_ed25519_key"
  chmod 600 "$1_ssh_host_ed25519_key"
  made="$made $1-hostkey"
}
hostkey sftp-target
hostkey sftp-target2

if [ -z "$made" ]; then
  echo "dev certificates in $dir are current"
else
  echo "created in $dir:$made (ca.crt; server: localhost HTTPS; app-demo; postgres: DB server; fdrop-db: DB client;"
  echo "  sftp-target and sftp-target2 host keys)"
fi
