# Garage: the S3 object store fdrop's files live in, on the nodes' local disks (see "Object storage" in SPEC.md).
# Garage finds its peers through Kubernetes (GarageNode objects); a Job then lays out the cluster, creates the bucket
# and imports fdrop's key, doing only what is missing.

locals {
  labels = { "app.kubernetes.io/name" = "garage", "app.kubernetes.io/part-of" = "fdrop" }
  # Garage reads sizes in bytes; capacity is announced from the data volume size
  units        = { Ki = 1024, Mi = 1048576, Gi = 1073741824, Ti = 1099511627776 }
  data_bytes   = tonumber(regex("^[0-9]+", var.data_size)) * local.units[regex("[KMGT]i$", var.data_size)]
  config       = <<-EOT
    metadata_dir = "/mnt/meta"
    data_dir = "/mnt/data"
    db_engine = "lmdb"
    replication_factor = ${var.replication_factor}
    consistency_mode = "consistent"
    rpc_bind_addr = "[::]:3901"

    [kubernetes_discovery]
    namespace = "${var.namespace}"
    service_name = "garage"
    skip_crd = false

    [s3_api]
    s3_region = "garage"
    api_bind_addr = "[::]:3900"

    [admin]
    api_bind_addr = "[::]:3903"
  EOT
  setup_inputs = sha256(join("|", [var.replicas, var.replication_factor, local.data_bytes, var.bucket, local.config]))
}

resource "terraform_data" "replication_check" {
  lifecycle {
    precondition {
      condition     = (var.replication_factor == 1 && var.replicas == 1) || (var.replication_factor == 3 && var.replicas >= 3)
      error_message = "Garage needs replication_factor 1 with one pod, or 3 with three or more pods."
    }
  }
}

resource "kubernetes_namespace_v1" "this" {
  metadata {
    name = var.namespace
    labels = {
      "pod-security.kubernetes.io/enforce" = "restricted"
    }
  }
}

resource "kubernetes_secret_v1" "garage" {
  metadata {
    name      = "garage"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  data = {
    rpc-secret    = var.rpc_secret
    admin-token   = var.admin_token
    metrics-token = var.metrics_token
  }
}

resource "kubernetes_secret_v1" "fdrop_key" {
  metadata {
    name      = "garage-fdrop-key"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  data = {
    access-key-id     = var.access_key_id
    secret-access-key = var.secret_access_key
  }
}

resource "kubernetes_config_map_v1" "config" {
  metadata {
    name      = "garage-config"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  data = { "garage.toml" = local.config }
}

# Garage publishes each node as a GarageNode object and creates that CRD itself
resource "kubernetes_service_account_v1" "garage" {
  metadata {
    name      = "garage"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
}

resource "kubernetes_cluster_role_v1" "garage" {
  metadata {
    name = "fdrop-garage-${var.namespace}"
  }
  rule {
    api_groups = ["apiextensions.k8s.io"]
    resources  = ["customresourcedefinitions"]
    verbs      = ["get", "list", "watch", "create", "patch"]
  }
  rule {
    api_groups = ["deuxfleurs.fr"]
    resources  = ["garagenodes"]
    verbs      = ["get", "list", "watch", "create", "update", "patch", "delete"]
  }
}

resource "kubernetes_cluster_role_binding_v1" "garage" {
  metadata {
    name = "fdrop-garage-${var.namespace}"
  }
  role_ref {
    api_group = "rbac.authorization.k8s.io"
    kind      = "ClusterRole"
    name      = kubernetes_cluster_role_v1.garage.metadata[0].name
  }
  subject {
    kind      = "ServiceAccount"
    name      = kubernetes_service_account_v1.garage.metadata[0].name
    namespace = var.namespace
  }
}

resource "kubernetes_service_v1" "garage" {
  metadata {
    name      = "garage"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
    labels    = local.labels
  }
  spec {
    selector = local.labels
    port {
      name        = "s3"
      port        = 3900
      target_port = 3900
    }
    port {
      name        = "admin"
      port        = 3903
      target_port = 3903
    }
  }
}

resource "kubernetes_service_v1" "headless" {
  metadata {
    name      = "garage-headless"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
    labels    = local.labels
  }
  spec {
    cluster_ip                  = "None"
    publish_not_ready_addresses = true
    selector                    = local.labels
    port {
      name = "rpc"
      port = 3901
    }
  }
}

resource "kubectl_manifest" "statefulset" {
  wait_for_rollout = true
  yaml_body = yamlencode({
    apiVersion = "apps/v1"
    kind       = "StatefulSet"
    metadata   = { name = "garage", namespace = var.namespace, labels = local.labels }
    spec = {
      replicas    = var.replicas
      serviceName = "garage-headless"
      # Garage is ready only once the layout exists, which needs every pod: start them together
      podManagementPolicy = "Parallel"
      selector            = { matchLabels = local.labels }
      template = {
        metadata = {
          labels      = local.labels
          annotations = { "checksum/config" = sha256(local.config) }
        }
        spec = {
          serviceAccountName = "garage"
          securityContext = {
            runAsNonRoot   = true
            runAsUser      = 1000
            runAsGroup     = 1000
            fsGroup        = 1000
            seccompProfile = { type = "RuntimeDefault" }
          }
          # one Garage pod per node: a node's loss costs one copy, not two
          affinity = var.replicas > 1 ? {
            podAntiAffinity = {
              requiredDuringSchedulingIgnoredDuringExecution = [{
                labelSelector = { matchLabels = local.labels }
                topologyKey   = "kubernetes.io/hostname"
              }]
            }
          } : null
          containers = [{
            name  = "garage"
            image = var.image
            ports = [
              { name = "s3", containerPort = 3900 },
              { name = "rpc", containerPort = 3901 },
              { name = "admin", containerPort = 3903 },
            ]
            env = [
              { name = "GARAGE_RPC_SECRET", valueFrom = { secretKeyRef = { name = "garage", key = "rpc-secret" } } },
              { name = "GARAGE_ADMIN_TOKEN", valueFrom = { secretKeyRef = { name = "garage", key = "admin-token" } } },
              { name = "GARAGE_METRICS_TOKEN", valueFrom = { secretKeyRef = { name = "garage", key = "metrics-token" } } },
            ]
            volumeMounts = [
              { name = "config", mountPath = "/etc/garage.toml", subPath = "garage.toml" },
              { name = "meta", mountPath = "/mnt/meta" },
              { name = "data", mountPath = "/mnt/data" },
            ]
            readinessProbe = { tcpSocket = { port = 3900 }, periodSeconds = 5 }
            livenessProbe  = { tcpSocket = { port = 3903 }, periodSeconds = 20, failureThreshold = 6 }
            resources = {
              requests = { cpu = "100m", memory = "256Mi" }
              limits   = { memory = "1Gi" }
            }
            securityContext = {
              allowPrivilegeEscalation = false
              readOnlyRootFilesystem   = true
              capabilities             = { drop = ["ALL"] }
            }
          }]
          volumes = [{ name = "config", configMap = { name = "garage-config" } }]
        }
      }
      volumeClaimTemplates = [
        for v in [{ name = "meta", size = var.meta_size }, { name = "data", size = var.data_size }] : {
          metadata = { name = v.name }
          spec = {
            accessModes      = ["ReadWriteOnce"]
            storageClassName = var.storage_class
            resources        = { requests = { storage = v.size } }
          }
        }
      ]
    }
  })
  depends_on = [
    kubernetes_config_map_v1.config, kubernetes_secret_v1.garage, kubernetes_cluster_role_binding_v1.garage,
    terraform_data.replication_check,
  ]
}

# Lays out the cluster, creates the bucket and imports fdrop's key, through Garage's admin API. Only what is missing
# is done; the Job's name changes with its inputs, so a new layout (more pods, bigger disks) runs it again.
resource "kubernetes_job_v1" "setup" {
  metadata {
    name      = "garage-setup-${substr(local.setup_inputs, 0, 10)}"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
    labels    = { "app.kubernetes.io/name" = "garage-setup" }
  }
  spec {
    backoff_limit              = 10
    ttl_seconds_after_finished = 86400
    template {
      metadata {
        labels = { "app.kubernetes.io/name" = "garage-setup" }
      }
      spec {
        restart_policy = "OnFailure"
        security_context {
          run_as_non_root = true
          run_as_user     = 100
          seccomp_profile {
            type = "RuntimeDefault"
          }
        }
        container {
          name    = "setup"
          image   = var.setup_image
          command = ["/bin/sh", "-c", file("${path.module}/setup.sh")]
          env {
            name  = "GARAGE"
            value = "http://garage.${var.namespace}.svc:3903"
          }
          env {
            name  = "NODES"
            value = tostring(var.replicas)
          }
          env {
            name  = "CAPACITY"
            value = tostring(local.data_bytes)
          }
          env {
            name  = "BUCKET"
            value = var.bucket
          }
          env {
            name = "TOKEN"
            value_from {
              secret_key_ref {
                name = "garage"
                key  = "admin-token"
              }
            }
          }
          env {
            name = "KEY_ID"
            value_from {
              secret_key_ref {
                name = "garage-fdrop-key"
                key  = "access-key-id"
              }
            }
          }
          env {
            name = "KEY_SECRET"
            value_from {
              secret_key_ref {
                name = "garage-fdrop-key"
                key  = "secret-access-key"
              }
            }
          }
          volume_mount {
            name       = "tmp"
            mount_path = "/tmp"
          }
          security_context {
            # the provider writes runAsNonRoot: false into a container that does not say, which restricted refuses
            run_as_non_root            = true
            allow_privilege_escalation = false
            read_only_root_filesystem  = true
            capabilities {
              drop = ["ALL"]
            }
          }
        }
        volume {
          name = "tmp"
          empty_dir {}
        }
      }
    }
  }
  wait_for_completion = true
  timeouts {
    create = "10m"
    update = "10m"
  }
  depends_on = [kubectl_manifest.statefulset, kubernetes_secret_v1.fdrop_key, kubernetes_network_policy_v1.garage]
}
