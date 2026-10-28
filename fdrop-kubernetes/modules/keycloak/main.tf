# Keycloak for fdrop, run by the Keycloak operator, with the fdrop realm imported from the dev realm's definition
# (see "Kubernetes deployment" in SPEC.md). The public TLS ends at Traefik, which calls Keycloak over HTTPS again,
# checking its internal-CA certificate: Keycloak serves no plain HTTP. A network policy admits only Traefik and the
# monitoring namespace.

locals {
  realm_source = jsondecode(file(coalesce(var.realm_file, "${path.module}/../../../.devcontainer/keycloak/fdrop-realm.json")))
  public_url   = "https://${var.hostname}${var.public_port == 443 ? "" : ":${var.public_port}"}"
  # client secrets are placeholders the import fills in from Secrets, so they are never in the rendered realm. The
  # changes go through jsondecode so each client keeps its own types (a conditional would unify them, booleans into
  # strings, which the CRD refuses).
  client_overrides = {
    "fdrop-web" = jsonencode({
      secret       = "$${FDROP_WEB_SECRET}"
      redirectUris = ["${var.fdrop_url}/auth/callback"]
      webOrigins   = [var.fdrop_url]
      attributes = {
        "post.logout.redirect.uris" = "${var.fdrop_url}/"
        "backchannel.logout.url"    = "${var.fdrop_url}/auth/backchannel-logout"
      }
    })
    "fdrop-sftp" = jsonencode({ secret = "$${FDROP_SFTP_SECRET}" })
  }
  clients = [
    for c in local.realm_source.clients : merge(c, jsondecode(lookup(local.client_overrides, c.clientId, "{}")),
      contains(keys(local.client_overrides), c.clientId) && can(c.attributes) ? {
        attributes = merge(c.attributes, try(jsondecode(local.client_overrides[c.clientId]).attributes, {}))
    } : {})
  ]
  # the fdrop-sftp service account is part of the realm's setup; people only on kind
  users = [
    for u in local.realm_source.users : u if var.include_dev_users || startswith(u.username, "service-account-")
  ]
  # the operator's Service, which Traefik calls
  service_names = ["keycloak-service.${var.namespace}.svc", "keycloak-service.${var.namespace}.svc.cluster.local"]
  realm         = merge(local.realm_source, { clients = local.clients, users = local.users, sslRequired = "external" })
}

resource "kubernetes_secret_v1" "bootstrap_admin" {
  metadata {
    name      = "keycloak-bootstrap-admin"
    namespace = var.namespace
  }
  type = "kubernetes.io/basic-auth"
  data = {
    username = "admin"
    password = var.admin_password
  }
}

resource "kubernetes_secret_v1" "db" {
  metadata {
    name      = "keycloak-db"
    namespace = var.namespace
  }
  data = {
    username = "keycloak"
    password = var.db_password
  }
}

resource "kubernetes_secret_v1" "clients" {
  metadata {
    name      = "fdrop-clients"
    namespace = var.namespace
  }
  data = {
    web  = var.web_client_secret
    sftp = var.sftp_client_secret
  }
}

resource "kubectl_manifest" "certificate" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
    metadata   = { name = "keycloak-public", namespace = var.namespace }
    spec = {
      secretName = "keycloak-public-tls"
      dnsNames   = [var.hostname]
      issuerRef  = { name = var.public_issuer, kind = "ClusterIssuer" }
    }
  })
}

# Keycloak's own certificate, for Traefik's HTTPS calls to it inside the cluster
resource "kubectl_manifest" "internal_certificate" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
    metadata   = { name = "keycloak-internal", namespace = var.namespace }
    spec = {
      secretName = "keycloak-internal-tls"
      dnsNames   = local.service_names
      privateKey = { algorithm = "ECDSA", size = 256, rotationPolicy = "Always" }
      issuerRef  = { name = var.internal_issuer, kind = "ClusterIssuer" }
    }
  })
}

resource "kubectl_manifest" "keycloak" {
  yaml_body = yamlencode({
    apiVersion = "k8s.keycloak.org/v2alpha1"
    kind       = "Keycloak"
    metadata   = { name = "keycloak", namespace = var.namespace }
    spec = {
      instances = var.instances
      db = {
        vendor         = "postgres"
        url            = "jdbc:postgresql://${var.db_host}:5432/keycloak?sslmode=verify-full&sslrootcert=/opt/keycloak/db-ca/ca.crt"
        usernameSecret = { name = "keycloak-db", key = "username" }
        passwordSecret = { name = "keycloak-db", key = "password" }
      }
      http           = { httpEnabled = false, httpsPort = 8443, tlsSecret = "keycloak-internal-tls" }
      hostname       = { hostname = local.public_url, strict = true }
      proxy          = { headers = "xforwarded" }
      ingress        = { enabled = false }
      bootstrapAdmin = { user = { secret = "keycloak-bootstrap-admin" } }
      additionalOptions = [
        { name = "health-enabled", value = "true" },
        { name = "metrics-enabled", value = "true" },
      ]
      resources = {
        requests = { cpu = "250m", memory = "1Gi" }
        limits   = { memory = "2Gi" }
      }
      # only the database CA's ca.crt, not the key that comes with it in the Secret
      unsupported = {
        podTemplate = {
          spec = {
            containers = [{
              volumeMounts = [{ name = "db-ca", mountPath = "/opt/keycloak/db-ca", readOnly = true }]
            }]
            volumes = [{
              name   = "db-ca"
              secret = { secretName = var.db_trust_secret, items = [{ key = "ca.crt", path = "ca.crt" }] }
            }]
          }
        }
      }
    }
  })
  depends_on = [kubernetes_secret_v1.db, kubernetes_secret_v1.bootstrap_admin, kubectl_manifest.internal_certificate]
}

# Creates the realm once: an import never changes a realm that exists (see "Rotating secrets" in the README)
resource "kubectl_manifest" "realm" {
  yaml_body = yamlencode({
    apiVersion = "k8s.keycloak.org/v2alpha1"
    kind       = "KeycloakRealmImport"
    metadata   = { name = "fdrop", namespace = var.namespace }
    spec = {
      keycloakCRName = "keycloak"
      realm          = local.realm
      placeholders = {
        FDROP_WEB_SECRET  = { secret = { name = "fdrop-clients", key = "web" } }
        FDROP_SFTP_SECRET = { secret = { name = "fdrop-clients", key = "sftp" } }
      }
    }
  })
  depends_on = [kubectl_manifest.keycloak, kubernetes_secret_v1.clients]
}

# Traefik re-encrypts to Keycloak and checks its certificate against the internal CA (the Secret's ca.crt)
resource "kubectl_manifest" "servers_transport" {
  yaml_body = yamlencode({
    apiVersion = "traefik.io/v1alpha1"
    kind       = "ServersTransport"
    metadata   = { name = "keycloak", namespace = var.namespace }
    spec = {
      serverName = local.service_names[0]
      rootCAs    = [{ secret = "keycloak-internal-tls" }]
    }
  })
}

resource "kubectl_manifest" "route" {
  yaml_body = yamlencode({
    apiVersion = "traefik.io/v1alpha1"
    kind       = "IngressRoute"
    metadata   = { name = "keycloak", namespace = var.namespace }
    spec = {
      entryPoints = ["websecure"]
      routes = [{
        match    = "Host(`${var.hostname}`)"
        kind     = "Rule"
        services = [{ name = "keycloak-service", port = 8443, scheme = "https", serversTransport = "keycloak" }]
      }]
      tls = { secretName = "keycloak-public-tls" }
    }
  })
  depends_on = [kubectl_manifest.keycloak, kubectl_manifest.certificate, kubectl_manifest.servers_transport]
}

resource "kubernetes_network_policy_v1" "keycloak" {
  metadata {
    name      = "keycloak"
    namespace = var.namespace
  }
  spec {
    pod_selector {
      match_labels = { app = "keycloak" }
    }
    policy_types = ["Ingress"]
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "traefik" }
        }
      }
      ports {
        port     = "8443"
        protocol = "TCP"
      }
    }
    # the instances' own cache traffic, the operator's checks and Prometheus
    ingress {
      from {
        pod_selector {}
      }
    }
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "monitoring" }
        }
      }
      ports {
        port     = "9000"
        protocol = "TCP"
      }
    }
  }
}
