#!/usr/bin/env bash
# End-to-end test of the Kubernetes deployment on a fresh kind cluster (see "Kubernetes deployment" in SPEC.md, Tests).
# Run on a machine with Docker, kind, kubectl, tofu, sftp, curl and
# python3, from the repository: ./mvnw -pl fdrop-kubernetes verify -Pkind, or bash fdrop-kubernetes/e2e/kind-test.sh.
#  1. builds fdrop's image and creates the cluster, loads the image, applies environments/kind
#  2. checks Keycloak's discovery and fdrop through Traefik
#  3. uploads 20 MiB as app-demo with the chunked upload protocol, then downloads it and compares
#  4. uploads over SFTP on the mapped port
#  5. checks the files are in Garage's bucket, as multipart objects
#  6. checks the encrypted state holds no generated secret in clear
#  7. destroys everything (KEEP_CLUSTER=1 keeps it for a look)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../.." && pwd)
env_dir="$repo/fdrop-kubernetes/environments/kind"
work="$here/work"
tofu=${TOFU:-tofu}
image=localhost/fdrop:0.1.0-SNAPSHOT
https=18443
mtls=18444
sftp_port=12222

rm -rf "$work"
mkdir -p "$work"
export TF_VAR_state_passphrase=${TF_VAR_state_passphrase:-$(head -c 24 /dev/urandom | base64)}
export TF_VAR_fdrop_image=$image
export TF_IN_AUTOMATION=1
step() { echo; echo "=== $*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }

cleanup() {
  if [ "${KEEP_CLUSTER:-0}" = 1 ]; then
    echo "keeping the cluster: cd $env_dir && tofu destroy (with the same TF_VAR_state_passphrase) when done"
    return
  fi
  step "destroying"
  (cd "$env_dir" && "$tofu" destroy -auto-approve -input=false -no-color >"$work/destroy.log" 2>&1) \
    || kind delete cluster --name fdrop || true
  rm -f "$env_dir"/terraform.tfstate*
}
trap cleanup EXIT

image_tar="$repo/fdrop-server/target/jib-image.tar"
if [ "${SKIP_IMAGE_BUILD:-0}" != 1 ]; then
  step "building fdrop's image"
  (cd "$repo" && ./mvnw -B -ntp -q -pl fdrop-server -am -DskipTests -Ddotnet.skip -Dtofu.skip package jib:buildTar \
    -Dimage.name=localhost/fdrop)
fi
[ -f "$image_tar" ] || fail "no image archive at $image_tar"

step "creating the cluster and loading the image"
cd "$env_dir"
"$tofu" init -input=false -no-color >"$work/init.log"
"$tofu" apply -auto-approve -input=false -no-color -target=kind_cluster.this >"$work/apply-cluster.log"
kind load image-archive "$image_tar" --name fdrop
"$tofu" output -raw kubeconfig >"$work/kubeconfig"
export KUBECONFIG="$work/kubeconfig"

step "applying environments/kind"
"$tofu" apply -auto-approve -input=false -no-color >"$work/apply.log" || { tail -40 "$work/apply.log"; fail "tofu apply"; }
"$tofu" output -raw ca_certificate >"$work/ca.pem"
kubectl get pods -A -o wide >"$work/pods.txt"
kubectl -n fdrop wait --for=condition=Ready pod/fdrop-0 --timeout=300s
# applying the Keycloak resources does not wait for Keycloak itself, nor for the realm import
kubectl -n keycloak wait --for=condition=Ready keycloak/keycloak --timeout=600s
kubectl -n keycloak wait --for=condition=Done keycloakrealmimport/fdrop --timeout=600s

step "Keycloak and fdrop through Traefik"
curl -fsS --cacert "$work/ca.pem" "https://auth.localtest.me:$https/realms/fdrop/.well-known/openid-configuration" \
  | grep -q "\"issuer\":\"https://auth.localtest.me:$https/realms/fdrop\"" || fail "Keycloak discovery"
code=$(curl -sS -o /dev/null -w '%{http_code}' --cacert "$work/ca.pem" "https://fdrop.localtest.me:$https/api/v1/me")
[ "$code" = 401 ] || fail "fdrop answered $code to an anonymous request"
curl -fsS --cacert "$work/ca.pem" -u app-demo:app-demo "https://fdrop.localtest.me:$https/api/v1/me" \
  | grep -q '"username":"app-demo"' || fail "app-demo's password login"

step "no plain HTTP, outside or inside"
code=$(curl -sS -o /dev/null -w '%{http_code}' "http://fdrop.localtest.me:$mtls/api/v1/me" 2>/dev/null || true)
[ "$code" = 000 ] || fail "fdrop's mTLS port answered plain HTTP with $code"
ports=$(kubectl -n fdrop-objectstore get service garage -o jsonpath='{.spec.ports[*].port}')
[ "$ports" = "3443 3943" ] || fail "Garage's Service offers $ports, not just its TLS ports"
[ "$(kubectl -n keycloak get keycloak keycloak -o jsonpath='{.spec.http.httpEnabled}')" = false ] \
  || fail "Keycloak still serves plain HTTP"
[ "$(kubectl -n fdrop get pod fdrop-0 -o jsonpath='{.spec.containers[0].readinessProbe.httpGet.scheme}')" = HTTPS ] \
  || fail "fdrop's management port is probed over plain HTTP"

step "uploading 20 MiB in chunks as app-demo"
head -c $((20 * 1024 * 1024)) /dev/urandom >"$work/big.bin"
python3 - "$work" "$https" <<'PY' || fail "chunked upload"
import base64, hashlib, json, os, ssl, sys, urllib.request
work, port = sys.argv[1], sys.argv[2]
ctx = ssl.create_default_context(cafile=os.path.join(work, "ca.pem"))
auth = "Basic " + base64.b64encode(b"app-demo:app-demo").decode()
base = f"https://fdrop.localtest.me:{port}"
data = open(os.path.join(work, "big.bin"), "rb").read()
def call(method, path, body=None, headers=None):
    h = {"Authorization": auth, **(headers or {})}
    if isinstance(body, dict):
        body = json.dumps(body).encode(); h["Content-Type"] = "application/json"
    req = urllib.request.Request(base + path, data=body, method=method, headers=h)
    with urllib.request.urlopen(req, context=ctx) as r:
        text = r.read()
        return r.status, (json.loads(text) if text else None)
_, s = call("POST", "/api/v1/uploads", {"path": "/home/e2e-big.bin", "size": len(data),
                                       "sha256": hashlib.sha256(data).hexdigest()})
size = s["chunkSize"]
for i in range(s["chunkCount"]):
    part = data[i * size:(i + 1) * size]
    call("PUT", f"/api/v1/uploads/{s['id']}/chunks/{i}", part,
         {"Content-Type": "application/octet-stream", "X-Chunk-SHA256": hashlib.sha256(part).hexdigest()})
status, done = call("POST", f"/api/v1/uploads/{s['id']}/complete", {"sha256": hashlib.sha256(data).hexdigest()})
print("uploaded in", s["chunkCount"], "chunks:", done["state"])
assert s["chunkCount"] >= 3 and done["state"] in ("COMPLETED", "ASSEMBLING")
PY
sleep 2
curl -fsS --cacert "$work/ca.pem" -u app-demo:app-demo -o "$work/back.bin" \
  "https://fdrop.localtest.me:$https/api/v1/files/content?path=/home/e2e-big.bin"
cmp "$work/big.bin" "$work/back.bin" || fail "the download differs from the upload"

step "uploading over SFTP"
echo "sent over SFTP" >"$work/sftp.txt"
printf '#!/bin/sh\necho app-demo\n' >"$work/askpass" && chmod 700 "$work/askpass"
echo "put $work/sftp.txt /home/e2e-sftp.txt" >"$work/batch"
# -b turns BatchMode on, which disables passwords; an -o given first wins, so this keeps -b's error handling
SSH_ASKPASS="$work/askpass" SSH_ASKPASS_REQUIRE=force sftp -o BatchMode=no -P "$sftp_port" -b "$work/batch" \
  -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o PreferredAuthentications=password,keyboard-interactive \
  app-demo@fdrop.localtest.me </dev/null || fail "SFTP upload"
curl -fsS --cacert "$work/ca.pem" -u app-demo:app-demo "https://fdrop.localtest.me:$https/api/v1/files/content?path=/home/e2e-sftp.txt" \
  | grep -q "sent over SFTP" || fail "the SFTP upload is not there"

step "the files are in Garage"
kubectl -n fdrop-objectstore exec garage-0 -- /garage bucket info fdrop >"$work/bucket.txt"
cat "$work/bucket.txt"
objects=$(sed -n 's/.*Objects: *\([0-9]*\).*/\1/p' "$work/bucket.txt" | head -n 1)
[ "${objects:-0}" -ge 2 ] || fail "Garage's bucket holds ${objects:-no} objects"

step "the state is encrypted"
state="$env_dir/terraform.tfstate"
grep -q '"encrypted_data"' "$state" || fail "the state file is not encrypted"
for out in keycloak_admin_password; do
  value=$("$tofu" output -raw "$out")
  ! grep -qF "$value" "$state" || fail "$out is in the state in clear"
done

echo
echo "kind end-to-end test passed"
