resource "kubectl_manifest" "service_monitor" {
  count = var.monitoring ? 1 : 0
  yaml_body = yamlencode({
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "ServiceMonitor"
    metadata   = { name = "garage", namespace = var.namespace, labels = local.labels }
    spec = {
      selector = { matchLabels = local.labels }
      endpoints = [{
        port          = "admin"
        path          = "/metrics"
        interval      = "30s"
        scheme        = "https"
        tlsConfig     = { ca = { secret = { name = "garage-tls", key = "ca.crt" } }, serverName = local.service_names[0] }
        authorization = { credentials = { name = "garage", key = "metrics-token" } }
      }]
    }
  })
}

resource "kubectl_manifest" "rules" {
  count = var.monitoring ? 1 : 0
  yaml_body = yamlencode({
    apiVersion = "monitoring.coreos.com/v1"
    kind       = "PrometheusRule"
    metadata   = { name = "fdrop-objectstore", namespace = var.namespace, labels = local.labels }
    spec = {
      groups = [{
        name = "fdrop-objectstore"
        rules = [
          {
            alert       = "FdropObjectStoreDiskFull"
            expr        = "max by (persistentvolumeclaim) (kubelet_volume_stats_used_bytes{namespace=\"${var.namespace}\"} / kubelet_volume_stats_capacity_bytes{namespace=\"${var.namespace}\"}) > 0.8"
            for         = "10m"
            labels      = { severity = "warning" }
            annotations = { summary = "A Garage volume is over 80% full; add capacity before writes fail" }
          },
          {
            alert       = "FdropObjectStoreDiskFull"
            expr        = "max by (persistentvolumeclaim) (kubelet_volume_stats_used_bytes{namespace=\"${var.namespace}\"} / kubelet_volume_stats_capacity_bytes{namespace=\"${var.namespace}\"}) > 0.9"
            for         = "5m"
            labels      = { severity = "critical" }
            annotations = { summary = "A Garage volume is over 90% full" }
          },
          {
            alert       = "FdropObjectStoreNodeDown"
            expr        = "min(cluster_connected_nodes{namespace=\"${var.namespace}\"}) < min(cluster_known_nodes{namespace=\"${var.namespace}\"})"
            for         = "10m"
            labels      = { severity = "critical" }
            annotations = { summary = "A Garage node has been missing for 10 minutes; with replication 3, one more loss stops writes" }
          },
        ]
      }]
    }
  })
}
