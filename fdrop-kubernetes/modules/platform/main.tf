# Operators and cluster add-ons fdrop's deployment builds on (see "Kubernetes deployment" in SPEC.md). Each can be
# turned off for a cluster that already runs it.

locals {
  versions = {
    cert_manager    = "v1.21.3"
    cloudnative_pg  = "0.29.1"
    traefik         = "42.1.3"
    keycloak        = "26.8.1"
    kube_prometheus = "92.14.0"
  }
  keycloak_dir = "${path.module}/keycloak-operator"
  # kubernetes.yml holds several documents; each is applied on its own
  keycloak_operator_docs = [
    for d in split("\n---\n", file("${local.keycloak_dir}/kubernetes.yml")) : d if trimspace(d) != ""
  ]
}

resource "helm_release" "cert_manager" {
  count            = var.cert_manager ? 1 : 0
  name             = "cert-manager"
  repository       = "https://charts.jetstack.io"
  chart            = "cert-manager"
  version          = local.versions.cert_manager
  namespace        = "cert-manager"
  create_namespace = true
  wait             = true
  values = [yamlencode({
    crds = { enabled = true }
  })]
}

resource "helm_release" "cloudnative_pg" {
  count            = var.cloudnative_pg ? 1 : 0
  name             = "cnpg"
  repository       = "https://cloudnative-pg.github.io/charts"
  chart            = "cloudnative-pg"
  version          = local.versions.cloudnative_pg
  namespace        = "cnpg-system"
  create_namespace = true
  wait             = true
}

resource "helm_release" "traefik" {
  count            = var.traefik ? 1 : 0
  name             = "traefik"
  repository       = "https://traefik.github.io/charts"
  chart            = "traefik"
  version          = local.versions.traefik
  namespace        = "traefik"
  create_namespace = true
  wait             = true
  values = [yamlencode({
    providers = {
      kubernetesCRD     = { enabled = true, allowCrossNamespace = false }
      kubernetesIngress = { enabled = false }
    }
    ingressRoute = { dashboard = { enabled = false } }
    ports = {
      web = merge({ http = { redirections = { entryPoint = { to = "websecure", scheme = "https", permanent = true } } } },
      var.traefik_node_ports == null ? {} : { nodePort = var.traefik_node_ports.web })
      websecure = merge({ http = { tls = { enabled = true } }, exposedPort = var.traefik_https_port },
      var.traefik_node_ports == null ? {} : { nodePort = var.traefik_node_ports.websecure })
    }
    service = {
      spec = { type = var.traefik_node_ports == null ? "LoadBalancer" : "NodePort", externalTrafficPolicy = "Local" }
    }
    metrics = { prometheus = { serviceMonitor = { enabled = var.monitoring } } }
  })]
  depends_on = [helm_release.kube_prometheus]
}

# The Keycloak operator has no Helm chart; its release manifests (every CRD it watches, then the operator) are
# vendored in keycloak-operator/ and applied
# server-side (the CRDs are too large for a last-applied annotation).
resource "kubectl_manifest" "keycloak_namespace" {
  count = var.keycloak_operator ? 1 : 0
  yaml_body = yamlencode({
    apiVersion = "v1"
    kind       = "Namespace"
    metadata = {
      name = "keycloak"
      # baseline, not restricted: the Keycloak operator's own Deployment, and the pods it creates for Keycloak and the
      # realm import, set no restricted security context; restricted violations are still reported
      labels = {
        "pod-security.kubernetes.io/enforce" = "baseline"
        "pod-security.kubernetes.io/warn"    = "restricted"
        "pod-security.kubernetes.io/audit"   = "restricted"
      }
    }
  })
}

resource "kubectl_manifest" "keycloak_crds" {
  for_each          = var.keycloak_operator ? toset(["keycloaks", "keycloakrealmimports", "keycloakoidcclients", "keycloaksamlclients"]) : toset([])
  yaml_body         = file("${local.keycloak_dir}/${each.key}.k8s.keycloak.org-v1.yml")
  server_side_apply = true
  wait              = true
}

resource "kubectl_manifest" "keycloak_operator" {
  count              = var.keycloak_operator ? length(local.keycloak_operator_docs) : 0
  yaml_body          = local.keycloak_operator_docs[count.index]
  override_namespace = "keycloak"
  server_side_apply  = true
  depends_on         = [kubectl_manifest.keycloak_namespace, kubectl_manifest.keycloak_crds]
}

resource "helm_release" "kube_prometheus" {
  count            = var.monitoring && var.install_prometheus ? 1 : 0
  name             = "monitoring"
  repository       = "https://prometheus-community.github.io/helm-charts"
  chart            = "kube-prometheus-stack"
  version          = local.versions.kube_prometheus
  namespace        = "monitoring"
  create_namespace = true
  wait             = true
  values = [yamlencode({
    # pick up every ServiceMonitor and PrometheusRule, not only the chart's own
    prometheus = {
      prometheusSpec = {
        serviceMonitorSelectorNilUsesHelmValues = false
        ruleSelectorNilUsesHelmValues           = false
      }
    }
    grafana = { sidecar = { dashboards = { enabled = true, searchNamespace = "ALL" } } }
  })]
}
