resource "kubernetes_service_v1" "fdrop" {
  metadata {
    name      = "fdrop"
    namespace = var.namespace
    labels    = local.labels
  }
  spec {
    selector = local.labels
    port {
      name        = "https"
      port        = 8443
      target_port = "https"
    }
    port {
      name        = "management"
      port        = 8081
      target_port = "management"
    }
  }
}

resource "kubernetes_service_v1" "headless" {
  metadata {
    name      = "fdrop-headless"
    namespace = var.namespace
    labels    = local.labels
  }
  spec {
    cluster_ip = "None"
    selector   = local.labels
    port {
      name = "https"
      port = 8443
    }
  }
}

# Application clients' certificates must reach fdrop itself: TCP straight through, no TLS termination on the way
resource "kubernetes_service_v1" "mtls" {
  metadata {
    name      = "fdrop-mtls"
    namespace = var.namespace
    labels    = local.labels
  }
  spec {
    type                    = var.service_type
    selector                = local.labels
    external_traffic_policy = "Local"
    port {
      name        = "mtls"
      port        = 8444
      target_port = "mtls"
      node_port   = var.node_ports == null ? null : var.node_ports.mtls
    }
  }
}

resource "kubernetes_service_v1" "sftp" {
  metadata {
    name      = "fdrop-sftp"
    namespace = var.namespace
    labels    = local.labels
  }
  spec {
    type                    = var.service_type
    selector                = local.labels
    external_traffic_policy = "Local" # clients' real addresses, for the audit log
    session_affinity        = "ClientIP"
    port {
      name        = "sftp"
      port        = 22
      target_port = "sftp"
      node_port   = var.node_ports == null ? null : var.node_ports.sftp
    }
  }
}

# Browsers: Traefik ends the public TLS, then talks HTTPS to fdrop, checking its certificate; sessions live in each
# pod's memory, so a cookie keeps a browser on one pod
resource "kubectl_manifest" "servers_transport" {
  yaml_body = yamlencode({
    apiVersion = "traefik.io/v1alpha1"
    kind       = "ServersTransport"
    metadata   = { name = "fdrop", namespace = var.namespace }
    spec = merge({ serverName = var.hostname },
    var.trust_public_ca ? { rootCAs = [{ secret = "fdrop-public-tls" }] } : {})
  })
}

resource "kubectl_manifest" "route" {
  yaml_body = yamlencode({
    apiVersion = "traefik.io/v1alpha1"
    kind       = "IngressRoute"
    metadata   = { name = "fdrop", namespace = var.namespace }
    spec = {
      entryPoints = ["websecure"]
      routes = [{
        match = "Host(`${var.hostname}`)"
        kind  = "Rule"
        services = [{
          name             = "fdrop"
          port             = 8443
          scheme           = "https"
          serversTransport = "fdrop"
          sticky = {
            cookie = { name = "fdrop_node", secure = true, httpOnly = true, sameSite = "lax" }
          }
        }]
      }]
      tls = { secretName = "fdrop-public-tls" }
    }
  })
  depends_on = [kubectl_manifest.servers_transport, kubectl_manifest.certificate]
}
