# Default deny, then only: fdrop to the S3 API, Garage to itself (RPC), the setup Job and Prometheus to the admin API.
resource "kubernetes_network_policy_v1" "default_deny" {
  metadata {
    name      = "default-deny"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  spec {
    pod_selector {}
    policy_types = ["Ingress", "Egress"]
  }
}

resource "kubernetes_network_policy_v1" "garage" {
  metadata {
    name      = "garage"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  spec {
    pod_selector {
      match_labels = local.labels
    }
    policy_types = ["Ingress", "Egress"]
    ingress {
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = var.client_namespace }
        }
      }
      ports {
        port     = "3900"
        protocol = "TCP"
      }
    }
    ingress {
      from {
        pod_selector {
          match_labels = local.labels
        }
      }
      ports {
        port     = "3901"
        protocol = "TCP"
      }
    }
    ingress {
      from {
        pod_selector {
          match_labels = { "app.kubernetes.io/name" = "garage-setup" }
        }
      }
      from {
        namespace_selector {
          match_labels = { "kubernetes.io/metadata.name" = var.monitoring_namespace }
        }
      }
      ports {
        port     = "3903"
        protocol = "TCP"
      }
    }
    egress {
      to {
        pod_selector {
          match_labels = local.labels
        }
      }
      ports {
        port     = "3901"
        protocol = "TCP"
      }
    }
    # the Kubernetes API, for peer discovery (GarageNode objects), and DNS
    egress {
      ports {
        port     = "443"
        protocol = "TCP"
      }
      ports {
        port     = "6443"
        protocol = "TCP"
      }
      ports {
        port     = "53"
        protocol = "UDP"
      }
      ports {
        port     = "53"
        protocol = "TCP"
      }
    }
  }
}

resource "kubernetes_network_policy_v1" "setup" {
  metadata {
    name      = "garage-setup"
    namespace = kubernetes_namespace_v1.this.metadata[0].name
  }
  spec {
    pod_selector {
      match_labels = { "app.kubernetes.io/name" = "garage-setup" }
    }
    policy_types = ["Egress"]
    egress {
      to {
        pod_selector {
          match_labels = local.labels
        }
      }
      ports {
        port     = "3903"
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
  }
}
