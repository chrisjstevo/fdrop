# Default deny both ways, then only what fdrop needs (see "Kubernetes deployment" in SPEC.md).
resource "kubernetes_network_policy_v1" "default_deny" {
  metadata {
    name      = "default-deny"
    namespace = var.namespace
  }
  spec {
    pod_selector {}
    policy_types = ["Ingress", "Egress"]
  }
}

resource "kubernetes_network_policy_v1" "fdrop" {
  metadata {
    name      = "fdrop"
    namespace = var.namespace
  }
  spec {
    pod_selector {
      match_labels = local.labels
    }
    policy_types = ["Ingress", "Egress"]

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
    # application clients and SFTP come from anywhere, through their load balancers
    ingress {
      from {
        ip_block {
          cidr = "0.0.0.0/0"
        }
      }
      ports {
        port     = "8444"
        protocol = "TCP"
      }
      ports {
        port     = "2222"
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
        port     = "8081"
        protocol = "TCP"
      }
    }

    egress {
      ports {
        port     = "53"
        protocol = "UDP"
      }
      ports {
        port     = "53"
        protocol = "TCP"
      }
    }
    egress {
      to {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = split(".", var.db_host)[1] }
        }
      }
      ports {
        port     = "5432"
        protocol = "TCP"
      }
    }
    egress {
      to {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = var.s3.namespace }
        }
      }
      ports {
        port     = "3900"
        protocol = "TCP"
      }
    }
    # Keycloak at its public URL: through Traefik inside the cluster (kind), or a load balancer outside it
    egress {
      to {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = "traefik" }
        }
      }
      ports {
        port     = "8443"
        protocol = "TCP"
      }
    }
    egress {
      to {
        ip_block {
          cidr = "0.0.0.0/0"
        }
      }
      ports {
        port     = "443"
        protocol = "TCP"
      }
    }
    dynamic "egress" {
      for_each = length(local.forward_cidrs) > 0 ? [1] : []
      content {
        dynamic "to" {
          for_each = local.forward_cidrs
          content {
            ip_block {
              cidr = to.value
            }
          }
        }
        ports {
          port     = "22"
          protocol = "TCP"
        }
      }
    }
  }
}
