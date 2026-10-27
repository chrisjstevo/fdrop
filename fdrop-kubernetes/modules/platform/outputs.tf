output "ready" {
  description = "Depend on this to wait for every operator and its CRDs."
  value = {
    cert_manager      = length(helm_release.cert_manager) > 0 ? helm_release.cert_manager[0].status : "external"
    cloudnative_pg    = length(helm_release.cloudnative_pg) > 0 ? helm_release.cloudnative_pg[0].status : "external"
    traefik           = length(helm_release.traefik) > 0 ? helm_release.traefik[0].status : "external"
    keycloak_operator = length(kubectl_manifest.keycloak_operator)
    monitoring        = length(helm_release.kube_prometheus) > 0 ? helm_release.kube_prometheus[0].status : "external"
  }
}

output "versions" {
  description = "Pinned chart and operator versions."
  value       = local.versions
}
