# fdrop as a StatefulSet (see "Kubernetes deployment" in SPEC.md): each pod keeps its own search and audit indexes on
# its own volume, and its stable name is its audit node. Files live in the in-cluster Garage store.

locals {
  labels        = { "app.kubernetes.io/name" = "fdrop" }
  public_url    = "https://${var.hostname}${var.public_port == 443 ? "" : ":${var.public_port}"}"
  forward_cidrs = [for h in var.forward_destinations : h if can(cidrhost(h, 0))]
  s3_port       = regex(":([0-9]+)$", var.s3.endpoint)[0]

  config = {
    http = {
      port           = 8443
      publicUrl      = local.public_url
      tls            = { cert = "/etc/fdrop/tls/tls.crt", key = "/etc/fdrop/tls/tls.key" }
      trustedProxies = var.trusted_proxies
      mtls           = { enabled = true, port = 8444 }
    }
    management = { port = 8081, bind = "0.0.0.0", metricsToken = "$${FDROP_MANAGEMENT_METRICSTOKEN}" }
    sftp = {
      enabled     = true
      port        = 2222
      hostKeyPath = "/etc/fdrop/sftp/ssh_host_ed25519_key"
      spoolDir    = "/var/lib/fdrop/spool"
    }
    auth = {
      oidc = merge({
        issuer           = var.oidc_issuer
        webClientSecret  = "$${FDROP_AUTH_OIDC_WEBCLIENTSECRET}"
        redirectUri      = "${local.public_url}/auth/callback"
        sftpClientSecret = "$${FDROP_AUTH_OIDC_SFTPCLIENTSECRET}"
        # Keycloak's public URL has a certificate from the public issuer; a self-signed one (kind) is trusted here
      }, var.trust_public_ca ? { trustedCas = "/etc/fdrop/tls/ca.crt" } : {})
      twoFactor = { require = var.two_factor }
    }
    forward = {
      allowedHosts  = var.forward_destinations
      encryptionKey = "$${FDROP_FORWARD_ENCRYPTIONKEY}"
    }
    storage = {
      type = "s3"
      s3 = {
        bucket           = var.s3.bucket
        prefix           = ""
        region           = var.s3.region
        endpointOverride = var.s3.endpoint
        pathStyle        = true
        trustedCas       = "/etc/fdrop/internal-ca/ca.crt" # Garage's TLS sidecars have internal-CA certificates
      }
      encryption = merge(
        { enabled = true, keyProvider = var.storage_encryption.provider },
        var.storage_encryption.provider == "local"
        ? { local = { keys = "$${FDROP_STORAGE_ENCRYPTION_LOCAL_KEYS}" } }
        : { awsKms = { keyId = var.storage_encryption.key_id, region = var.storage_encryption.region } }
      )
    }
    db = {
      url      = "jdbc:postgresql://${var.db_host}:5432/fdrop"
      username = "fdrop"
      password = ""
      tls = {
        mode = "verify-full"
        ca   = "/etc/fdrop/db/ca.crt"
        cert = "/etc/fdrop/db/tls.crt"
        key  = "/etc/fdrop/db/tls.key"
      }
    }
    search = { indexDir = "/var/lib/fdrop/search" }
    audit  = { indexDir = "/var/lib/fdrop/audit-index" }
  }
  config_yaml = yamlencode(local.config)
  # rolls the pods when a secret changes; the hash reveals nothing of the values
  secrets_hash = nonsensitive(sha256(jsonencode(var.secrets)))
}

resource "kubernetes_service_account_v1" "fdrop" {
  metadata {
    name        = "fdrop"
    namespace   = var.namespace
    annotations = var.service_account_annotations
  }
  automount_service_account_token = false
}

resource "kubernetes_secret_v1" "env" {
  metadata {
    name      = "fdrop"
    namespace = var.namespace
  }
  data = merge({
    FDROP_AUTH_OIDC_WEBCLIENTSECRET  = var.secrets.web_client_secret
    FDROP_AUTH_OIDC_SFTPCLIENTSECRET = var.secrets.sftp_client_secret
    FDROP_FORWARD_ENCRYPTIONKEY      = var.secrets.forward_key
    FDROP_MANAGEMENT_METRICSTOKEN    = var.secrets.metrics_token
    AWS_ACCESS_KEY_ID                = var.secrets.s3_access_key_id
    AWS_SECRET_ACCESS_KEY            = var.secrets.s3_secret_access_key
    }, var.storage_encryption.provider == "local" ? {
    FDROP_STORAGE_ENCRYPTION_LOCAL_KEYS = var.secrets.storage_kek
  } : {})
}

resource "kubernetes_secret_v1" "sftp_host_key" {
  metadata {
    name      = "fdrop-sftp-host-key"
    namespace = var.namespace
  }
  data = { ssh_host_ed25519_key = var.secrets.sftp_host_key }
}

resource "kubernetes_config_map_v1" "internal_ca" {
  metadata {
    name      = "fdrop-internal-ca"
    namespace = var.namespace
  }
  data = { "ca.crt" = var.internal_ca }
}

resource "kubernetes_config_map_v1" "config" {
  metadata {
    name      = "fdrop"
    namespace = var.namespace
  }
  data = { "fdrop.yaml" = local.config_yaml }
}

# fdrop's own certificate, for its HTTPS port (behind Traefik), its mTLS port (passed straight through to clients) and
# its management port
resource "kubectl_manifest" "certificate" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
    metadata   = { name = "fdrop-public", namespace = var.namespace }
    spec = {
      secretName = "fdrop-public-tls"
      dnsNames   = distinct([var.hostname, var.mtls_hostname])
      privateKey = { algorithm = "ECDSA", size = 256, rotationPolicy = "Always" }
      issuerRef  = { name = var.public_issuer, kind = "ClusterIssuer" }
    }
  })
}

resource "kubectl_manifest" "statefulset" {
  wait_for_rollout = true
  yaml_body = yamlencode({
    apiVersion = "apps/v1"
    kind       = "StatefulSet"
    metadata   = { name = "fdrop", namespace = var.namespace, labels = local.labels }
    spec = {
      replicas            = var.replicas
      serviceName         = "fdrop-headless"
      podManagementPolicy = "OrderedReady" # one at a time: Flyway migrates once, on the first
      selector            = { matchLabels = local.labels }
      template = {
        metadata = {
          labels = local.labels
          annotations = {
            "checksum/config"  = sha256(local.config_yaml)
            "checksum/secrets" = local.secrets_hash
          }
        }
        spec = merge({
          serviceAccountName           = "fdrop"
          automountServiceAccountToken = false
          # no FDROP_SFTP_PORT=tcp://… service links: fdrop reads every FDROP_* variable as a configuration override
          enableServiceLinks            = false
          terminationGracePeriodSeconds = 60
          securityContext = {
            runAsNonRoot   = true
            runAsUser      = 65532
            runAsGroup     = 65532
            fsGroup        = 65532
            seccompProfile = { type = "RuntimeDefault" }
          }
          topologySpreadConstraints = [
            for key in ["kubernetes.io/hostname", "topology.kubernetes.io/zone"] : {
              maxSkew           = 1
              topologyKey       = key
              whenUnsatisfiable = key == "kubernetes.io/hostname" ? "DoNotSchedule" : "ScheduleAnyway"
              labelSelector     = { matchLabels = local.labels }
            }
          ]
          containers = [{
            name  = "fdrop"
            image = var.image
            ports = [
              { name = "https", containerPort = 8443 },
              { name = "mtls", containerPort = 8444 },
              { name = "sftp", containerPort = 2222 },
              { name = "management", containerPort = 8081 },
            ]
            envFrom = [{ secretRef = { name = "fdrop" } }]
            env = [
              { name = "FDROP_CONFIG", value = "/etc/fdrop/fdrop.yaml" },
              # the pod's stable name is its audit chain's node
              { name = "FDROP_AUDIT_NODE", valueFrom = { fieldRef = { fieldPath = "metadata.name" } } },
              # the heap leaves room in the limit for the forked text extractor
              { name = "JDK_JAVA_OPTIONS", value = "-XX:MaxRAMPercentage=50" },
            ]
            volumeMounts = [
              { name = "config", mountPath = "/etc/fdrop/fdrop.yaml", subPath = "fdrop.yaml", readOnly = true },
              { name = "tls", mountPath = "/etc/fdrop/tls", readOnly = true },
              { name = "internal-ca", mountPath = "/etc/fdrop/internal-ca", readOnly = true },
              { name = "db", mountPath = "/etc/fdrop/db", readOnly = true },
              { name = "sftp-host-key", mountPath = "/etc/fdrop/sftp", readOnly = true },
              { name = "data", mountPath = "/var/lib/fdrop" },
              { name = "tmp", mountPath = "/tmp" },
            ]
            # the management port is HTTPS too (the kubelet does not check the certificate)
            startupProbe   = { httpGet = { path = "/healthz", port = "management", scheme = "HTTPS" }, periodSeconds = 5, failureThreshold = 60 }
            livenessProbe  = { httpGet = { path = "/healthz", port = "management", scheme = "HTTPS" }, periodSeconds = 15, failureThreshold = 4 }
            readinessProbe = { httpGet = { path = "/readyz", port = "management", scheme = "HTTPS" }, periodSeconds = 10, failureThreshold = 3 }
            resources = {
              requests = { cpu = var.resources.cpu_request, memory = var.resources.memory_request }
              limits   = { memory = var.resources.memory_limit }
            }
            securityContext = {
              allowPrivilegeEscalation = false
              readOnlyRootFilesystem   = true
              capabilities             = { drop = ["ALL"] }
            }
          }]
          volumes = [
            { name = "config", configMap = { name = "fdrop" } },
            { name = "tls", secret = { secretName = "fdrop-public-tls" } },
            { name = "internal-ca", configMap = { name = "fdrop-internal-ca" } },
            { name = "db", secret = { secretName = var.db_client_secret, defaultMode = 288 } }, # 0440, readable through fsGroup
            { name = "sftp-host-key", secret = { secretName = "fdrop-sftp-host-key", defaultMode = 288 } },
            { name = "tmp", emptyDir = { sizeLimit = "2Gi" } },
          ]
          }, length(var.host_aliases) == 0 ? {} : {
          hostAliases = var.host_aliases
        })
      }
      volumeClaimTemplates = [{
        metadata = { name = "data" }
        spec = merge({
          accessModes = ["ReadWriteOnce"]
          resources   = { requests = { storage = var.volume_size } }
        }, var.storage_class == null ? {} : { storageClassName = var.storage_class })
      }]
    }
  })
  depends_on = [
    kubernetes_config_map_v1.config, kubernetes_config_map_v1.internal_ca, kubernetes_secret_v1.env, kubernetes_secret_v1.sftp_host_key,
    kubectl_manifest.certificate, kubernetes_service_account_v1.fdrop, kubernetes_service_v1.headless,
  ]
}

resource "kubernetes_pod_disruption_budget_v1" "fdrop" {
  count = var.replicas > 1 ? 1 : 0
  metadata {
    name      = "fdrop"
    namespace = var.namespace
  }
  spec {
    min_available = var.replicas - 1
    selector {
      match_labels = local.labels
    }
  }
}
