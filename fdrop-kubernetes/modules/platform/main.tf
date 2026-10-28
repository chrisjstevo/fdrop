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

# The internal CA: certificates for the hops inside the cluster (Keycloak behind Traefik, Garage's TLS sidecars), so
# no service is called over plain HTTP (see "Security" in SPEC.md). OpenTofu makes the CA, as its certificate must also
# reach fdrop's configuration; the key stays in the encrypted state and in cert-manager's namespace. A new CA is made
# by the first apply in its last year; cert-manager then reissues the certificates it signs as they renew.
resource "tls_private_key" "internal_ca" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_self_signed_cert" "internal_ca" {
  private_key_pem       = tls_private_key.internal_ca.private_key_pem
  is_ca_certificate     = true
  validity_period_hours = 87600 # 10 years
  early_renewal_hours   = 8760
  allowed_uses          = ["cert_signing", "crl_signing", "digital_signature"]
  subject {
    common_name  = "fdrop internal CA"
    organization = "fdrop"
  }
}

resource "kubernetes_secret_v1" "internal_ca" {
  metadata {
    name      = "fdrop-internal-ca"
    namespace = var.cert_manager_namespace
  }
  type = "kubernetes.io/tls"
  data = {
    "tls.crt" = tls_self_signed_cert.internal_ca.cert_pem
    "tls.key" = tls_private_key.internal_ca.private_key_pem
  }
  depends_on = [helm_release.cert_manager]
}

resource "kubectl_manifest" "internal_issuer" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = "fdrop-internal-ca" }
    spec       = { ca = { secretName = "fdrop-internal-ca" } }
  })
  depends_on = [kubernetes_secret_v1.internal_ca]
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
