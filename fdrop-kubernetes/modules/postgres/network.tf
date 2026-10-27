# Default deny; each database is reachable only by its application, by its own instances (replication) and by the
# CloudNativePG operator.
resource "kubernetes_network_policy_v1" "default_deny" {
  metadata {
    name      = "default-deny"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  spec {
    pod_selector {}
    policy_types = ["Ingress"]
  }
}

resource "kubernetes_network_policy_v1" "cluster" {
  for_each = { fdrop-db = var.fdrop_namespace, keycloak-db = var.keycloak_namespace }
  metadata {
    name      = each.key
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  spec {
    pod_selector {
      match_labels = { "cnpg.io/cluster" = each.key }
    }
    policy_types = ["Ingress"]
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = each.value }
        }
      }
      from {
        pod_selector {
          match_labels = { "cnpg.io/cluster" = each.key }
        }
      }
      ports {
        port     = "5432"
        protocol = "TCP"
      }
    }
    # the operator's status checks and the instance manager
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "cnpg-system" }
        }
      }
      ports {
        port     = "8000"
        protocol = "TCP"
      }
    }
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "monitoring" }
        }
      }
      ports {
        port     = "9187"
        protocol = "TCP"
      }
    }
  }
}
