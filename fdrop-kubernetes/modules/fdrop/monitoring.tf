# Prometheus scrapes fdrop's management port over HTTPS with the generated token, and alerts on the same rules as the Podman
# deployments: deploy/prometheus/alerts.yml itself becomes the PrometheusRule.
resource "kubectl_manifest" "service_monitor" {
  count = var.monitoring ? 1 : 0
  yaml_body = yamlencode({
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "ServiceMonitor"
    metadata   = { name = "fdrop", namespace = var.namespace, labels = local.labels }
    spec = {
      selector = { matchLabels = local.labels }
      endpoints = [{
        port     = "management"
        path     = "/metrics"
        interval = "30s"
        scheme   = "https"
        # pods are scraped by address, so the name to check is given; a self-signed public CA is trusted from the Secret
        tlsConfig = merge({ serverName = var.hostname },
        var.trust_public_ca ? { ca = { secret = { name = "fdrop-public-tls", key = "ca.crt" } } } : {})
        authorization = { credentials = { name = "fdrop", key = "FDROP_MANAGEMENT_METRICSTOKEN" } }
      }]
    }
  })
}

resource "kubectl_manifest" "rules" {
  count = var.monitoring ? 1 : 0
  yaml_body = yamlencode({
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "PrometheusRule"
    metadata   = { name = "fdrop", namespace = var.namespace, labels = local.labels }
    spec       = { groups = yamldecode(file("${path.module}/../../../deploy/prometheus/alerts.yml")).groups }
  })
}

resource "kubernetes_config_map_v1" "dashboard" {
  count = var.monitoring ? 1 : 0
  metadata {
    name      = "fdrop-dashboard"
    namespace = var.namespace
    labels    = { grafana_dashboard = "1" }
  }
  data = { "fdrop.json" = file("${path.module}/../../../deploy/grafana/fdrop-dashboard.json") }
}
