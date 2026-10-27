output "fdrop_host" {
  description = "fdrop's database host: its read-write service, named in the server certificate."
  value       = "fdrop-db-rw.${var.namespace}.svc"
}

output "keycloak_host" {
  value = "keycloak-db-rw.${var.namespace}.svc"
}

output "fdrop_client_secret" {
  description = "The Secret in fdrop's namespace with ca.crt, tls.crt and tls.key (PKCS#8) for db.tls."
  value       = "fdrop-db-client"
}

output "keycloak_trust_secret" {
  description = "The Secret in Keycloak's namespace whose ca.crt checks the database's certificate."
  value       = "keycloak-db-trust"
}

output "ready" {
  value = [for c in kubectl_manifest.cluster : c.id]
}
