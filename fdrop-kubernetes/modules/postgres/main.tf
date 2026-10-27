# PostgreSQL for fdrop and for Keycloak, as two CloudNativePG clusters (see "Kubernetes deployment" in SPEC.md).
# fdrop's database admits fdrop only over TLS with a client certificate (Metadata DB connection); every certificate
# is cert-manager's, from a CA it keeps in its own namespace, so no key passes through OpenTofu's state.

locals {
  clusters = {
    fdrop-db    = { instances = var.fdrop_instances, database = "fdrop", owner = "fdrop" }
    keycloak-db = { instances = var.keycloak_instances, database = "keycloak", owner = "keycloak" }
  }
  ca_issuer = "fdrop-db-ca"
  certificate = {
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
  }
}

resource "kubernetes_namespace_v1" "this" {
  metadata {
    name   = var.namespace
    labels = { "pod-security.kubernetes.io/enforce" = "restricted" }
  }
}

# The database CA: a self-signed root kept by cert-manager in its namespace, behind a ClusterIssuer
resource "kubectl_manifest" "bootstrap_issuer" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = "fdrop-db-bootstrap" }
    spec       = { selfSigned = {} }
  })
}

resource "kubectl_manifest" "ca" {
  yaml_body = yamlencode(merge(local.certificate, {
    metadata = { name = "fdrop-db-ca", namespace = "cert-manager" }
    spec = {
      isCA       = true
      commonName = "fdrop database CA"
      secretName = "fdrop-db-ca"
      duration   = "87600h"
      privateKey = { algorithm = "ECDSA", size = 256 }
      issuerRef  = { name = "fdrop-db-bootstrap", kind = "ClusterIssuer" }
    }
  }))
  depends_on = [kubectl_manifest.bootstrap_issuer]
}

resource "kubectl_manifest" "ca_issuer" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = local.ca_issuer }
    spec       = { ca = { secretName = "fdrop-db-ca" } }
  })
  depends_on = [kubectl_manifest.ca]
}

# Each cluster's server certificate, and the streaming_replica client certificate CloudNativePG needs when it is
# given a client CA
resource "kubectl_manifest" "server_cert" {
  for_each = local.clusters
  yaml_body = yamlencode(merge(local.certificate, {
    metadata = { name = "${each.key}-server", namespace = var.namespace }
    spec = {
      secretName  = "${each.key}-server-tls"
      usages      = ["server auth"]
      duration    = "2160h"
      renewBefore = "720h"
      privateKey  = { algorithm = "ECDSA", size = 256, rotationPolicy = "Always" }
      dnsNames = flatten([
        for svc in ["rw", "r", "ro"] : [
          "${each.key}-${svc}", "${each.key}-${svc}.${var.namespace}", "${each.key}-${svc}.${var.namespace}.svc",
          "${each.key}-${svc}.${var.namespace}.svc.cluster.local",
        ]
      ])
      issuerRef = { name = local.ca_issuer, kind = "ClusterIssuer" }
    }
  }))
  depends_on = [kubectl_manifest.ca_issuer, kubernetes_namespace_v1.this]
}

resource "kubectl_manifest" "replication_cert" {
  for_each = local.clusters
  yaml_body = yamlencode(merge(local.certificate, {
    metadata = { name = "${each.key}-replication", namespace = var.namespace }
    spec = {
      secretName  = "${each.key}-replication-tls"
      usages      = ["client auth"]
      commonName  = "streaming_replica"
      duration    = "2160h"
      renewBefore = "720h"
      privateKey  = { algorithm = "ECDSA", size = 256, rotationPolicy = "Always" }
      issuerRef   = { name = local.ca_issuer, kind = "ClusterIssuer" }
    }
  }))
  depends_on = [kubectl_manifest.ca_issuer, kubernetes_namespace_v1.this]
}

# fdrop's client certificate, in fdrop's namespace: CN=fdrop (the DB user), a PKCS#8 key as fdrop requires,
# renewed 30 days ahead; fdrop reads it again for every new connection
resource "kubectl_manifest" "fdrop_client_cert" {
  yaml_body = yamlencode(merge(local.certificate, {
    metadata = { name = "fdrop-db-client", namespace = var.fdrop_namespace }
    spec = {
      secretName  = "fdrop-db-client"
      usages      = ["client auth"]
      commonName  = "fdrop"
      duration    = "2160h"
      renewBefore = "720h"
      privateKey  = { algorithm = "ECDSA", size = 256, encoding = "PKCS8", rotationPolicy = "Always" }
      issuerRef   = { name = local.ca_issuer, kind = "ClusterIssuer" }
    }
  }))
  depends_on = [kubectl_manifest.ca_issuer]
}

# Keycloak signs in with a password, but checks the server against the same CA: this certificate's ca.crt
resource "kubectl_manifest" "keycloak_trust" {
  yaml_body = yamlencode(merge(local.certificate, {
    metadata = { name = "keycloak-db-trust", namespace = var.keycloak_namespace }
    spec = {
      secretName = "keycloak-db-trust"
      usages     = ["client auth"]
      commonName = "keycloak"
      duration   = "2160h"
      privateKey = { algorithm = "ECDSA", size = 256 }
      issuerRef  = { name = local.ca_issuer, kind = "ClusterIssuer" }
    }
  }))
  depends_on = [kubectl_manifest.ca_issuer]
}

resource "kubernetes_secret_v1" "keycloak_owner" {
  metadata {
    name      = "keycloak-db-owner"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  type = "kubernetes.io/basic-auth"
  data = {
    username = "keycloak"
    password = var.keycloak_db_password
  }
}

resource "kubectl_manifest" "cluster" {
  for_each = local.clusters
  yaml_body = yamlencode({
    apiVersion = "postgresql.cnpg.io/v1"
    kind       = "Cluster"
    metadata   = { name = each.key, namespace = var.namespace }
    spec = merge({
      instances = each.value.instances
      storage   = merge({ size = var.storage_size }, var.storage_class == null ? {} : { storageClass = var.storage_class })
      bootstrap = {
        initdb = merge({ database = each.value.database, owner = each.value.owner },
        each.key == "keycloak-db" ? { secret = { name = "keycloak-db-owner" } } : {})
      }
      certificates = {
        serverCASecret       = "${each.key}-server-tls"
        serverTLSSecret      = "${each.key}-server-tls"
        clientCASecret       = "${each.key}-replication-tls"
        replicationTLSSecret = "${each.key}-replication-tls"
      }
      postgresql = {
        parameters = { ssl_min_protocol_version = "TLSv1.2", log_connections = "on" }
        # first match wins: fdrop only with its certificate, nobody without TLS; CloudNativePG keeps its own lines
        pg_hba = each.key == "fdrop-db" ? [
          "hostssl fdrop fdrop all cert",
          "host all fdrop all reject",
          "hostnossl all all all reject",
          ] : [
          "hostnossl all all all reject",
        ]
      }
      monitoring = { enablePodMonitor = var.monitoring }
      affinity   = { enablePodAntiAffinity = true, topologyKey = "kubernetes.io/hostname" }
      },
      var.backup == null || each.key != "fdrop-db" ? {} : {
        backup = {
          retentionPolicy = var.backup.retention
          barmanObjectStore = merge({
            destinationPath = var.backup.destination_path
            s3Credentials = {
              accessKeyId     = { name = var.backup.credentials_secret, key = "ACCESS_KEY_ID" }
              secretAccessKey = { name = var.backup.credentials_secret, key = "ACCESS_SECRET_KEY" }
            }
          }, var.backup.endpoint_url == null ? {} : { endpointURL = var.backup.endpoint_url })
        }
    })
  })
  depends_on = [kubectl_manifest.server_cert, kubectl_manifest.replication_cert, kubernetes_secret_v1.keycloak_owner]
}

resource "kubectl_manifest" "daily_backup" {
  count = var.backup == null ? 0 : 1
  yaml_body = yamlencode({
    apiVersion = "postgresql.cnpg.io/v1"
    kind       = "ScheduledBackup"
    metadata   = { name = "fdrop-db-daily", namespace = var.namespace }
    spec       = { schedule = "0 0 2 * * *", cluster = { name = "fdrop-db" }, backupOwnerReference = "self" }
  })
  depends_on = [kubectl_manifest.cluster]
}
