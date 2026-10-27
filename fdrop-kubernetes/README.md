# fdrop on Kubernetes

A reference deployment of fdrop with everything it needs, written in the Terraform language and applied with
[OpenTofu](https://opentofu.org/) (see "Kubernetes deployment" in `SPEC.md`):

| Module | What it runs |
| --- | --- |
| `modules/platform` | cert-manager, the CloudNativePG operator, the Keycloak operator, Traefik, optionally kube-prometheus-stack |
| `modules/secrets` | Every generated secret (`random_*`), and the SFTP host key |
| `modules/objectstore` | Garage, the S3 store fdrop's files live in, on the nodes' local disks; its setup Job |
| `modules/postgres` | CloudNativePG clusters for fdrop and Keycloak; fdrop signs in with a client certificate |
| `modules/keycloak` | Keycloak with the fdrop realm, imported from `.devcontainer/keycloak/fdrop-realm.json` |
| `modules/fdrop` | fdrop as a StatefulSet, its services, Traefik route, network policies and monitoring |

`environments/kind` runs it on a local [kind](https://kind.sigs.k8s.io/) cluster; `environments/production-example`
is a starting point for a real cluster.

## Secrets

OpenTofu generates every secret and writes it only to Kubernetes Secrets. Because the values are in OpenTofu's state,
both environments encrypt state and plans (`encryption` block, `enforced = true`): with a passphrase on kind
(`TF_VAR_state_passphrase`, never stored), with an AWS KMS key in production. This is why the environments are
applied with `tofu`; Terraform does not understand the `encryption` block. TLS keys are cert-manager's and never pass
through the state.

### Rotating secrets

- **Most secrets:** `tofu apply -replace='module.secrets.random_password.metrics_token'` (or `random_bytes.…`) writes
  a new value; the pods that use it roll, because a checksum of their Secrets is on the pod template.
- **Keycloak's client secrets** (`web_client`, `sftp_client`): the realm import only creates the realm, it never
  changes one that exists. Replace the value with OpenTofu as above, then set the same value on the client in
  Keycloak's admin console (Clients, fdrop-web or fdrop-sftp, Credentials) or with `kcadm.sh`; fdrop's pods have
  already rolled onto it.
- **The storage KEK:** add a new key first (fdrop reads a list, the first is primary), re-encrypt with Admin:
  Encryption, then drop the old one; see "Encryption at rest" in `SPEC.md`. Replacing it outright makes stored files
  unreadable.
- **The SFTP host key:** replacing it changes the fingerprint every SFTP client has stored; announce it first.

## Trying it on kind

See [environments/kind/README.md](environments/kind/README.md).

## Checks and tests

`./mvnw -pl fdrop-kubernetes verify` runs `check.sh`: `tofu fmt -check`, `tofu validate` for both environments, and
`tofu test` with mocked providers for every module and the kind environment (no cluster needed). When no `tofu` of the
version in `.opentofu-version` is on PATH, `check.sh` downloads that release once (checksum verified) into
`~/.cache/opentofu/<version>/`; `-Dtofu.executable=…` points at another one, and `-Dtofu.skip` skips the module.

`./mvnw -pl fdrop-kubernetes verify -Pkind` also runs `e2e/kind-test.sh` on a real kind cluster; it needs Docker, kind,
kubectl, tofu, sftp, curl and python3 on the machine, so it runs on
the host or in CI, not in the dev container.
