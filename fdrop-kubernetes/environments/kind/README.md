# fdrop on kind

Everything on one local kind node: fdrop, a one-node Garage store, PostgreSQL for fdrop and Keycloak, Keycloak with the
dev realm's users, cert-manager with a local CA, and Traefik. It needs about 6 GB of memory.

## Run it

On the host (not in the dev container), with Docker, [kind](https://kind.sigs.k8s.io/), kubectl and OpenTofu 1.10 or
later. The kind provider creates the cluster with Docker; the host ports are 18443 (HTTPS), 18444 (mTLS) and 12222
(SFTP), clear of the 8443, 8444 and 2222 an IDE forwards for the dev container.

```bash
# fdrop's image, built without a container daemon and loaded into kind after the cluster exists
./mvnw -pl fdrop-server -am -DskipTests -Ddotnet.skip -Dtofu.skip package jib:buildTar -Dimage.name=localhost/fdrop

cd fdrop-kubernetes/environments/kind
export TF_VAR_state_passphrase='…'              # any long passphrase; you need it again for every tofu command
tofu init
tofu apply -target=kind_cluster.this            # the cluster first
kind load image-archive ../../../fdrop-server/target/jib-image.tar --name fdrop
tofu apply                                      # then everything else (a few minutes the first time)
```

Keycloak keeps starting for a minute or two after `tofu apply` returns; with the kubeconfig below,
`kubectl -n keycloak wait --for=condition=Ready keycloak/keycloak --timeout=10m` waits for it.

Then:

```bash
tofu output -raw ca_certificate > ca.pem        # trust it in your browser, or pass it to curl and the CLI
```

- **Browser:** <https://fdrop.localtest.me:18443>, as `admin`/`admin`, `jugash`/`jugash` or `chris`/`chris`
  (`localtest.me` names resolve to 127.0.0.1). Keycloak's console is at <https://auth.localtest.me:18443/admin/>,
  as `admin` with `tofu output -raw keycloak_admin_password`.
- **Application password login:** `curl --cacert ca.pem -u app-demo:app-demo https://fdrop.localtest.me:18443/api/v1/me`
- **SFTP:** `sftp -P 12222 jugash@fdrop.localtest.me`; the host key's fingerprint is `tofu output sftp_host_key_fingerprint`.
- **Application certificates:** the mTLS port is `https://fdrop.localtest.me:18444`, for `fdrop upload --ca ca.pem …`
  once a certificate is registered on `app-demo` (Admin: Applications).
- **kubectl:** `tofu output -raw kubeconfig > kubeconfig && export KUBECONFIG=$PWD/kubeconfig`

Two-factor sign-in is off here (`two_factor = "none"`), since the dev users have no second factor.

## Remove it

```bash
tofu destroy                                    # or: kind delete cluster --name fdrop, then rm terraform.tfstate*
```

## What differs from production

One replica of everything, so no high availability; Garage keeps a single copy; certificates come from a local CA that
fdrop and Traefik are told to trust; the public names reach Traefik inside the cluster through `hostAliases`; services
are NodePorts on host ports 18443, 18444 and 12222 instead of load balancers. Keycloak's back-channel logout calls to fdrop
fail here (Keycloak does not trust the local CA), so signing out in Keycloak does not end fdrop sessions on kind.
