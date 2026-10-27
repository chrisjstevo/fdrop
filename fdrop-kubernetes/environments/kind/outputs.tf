output "fdrop_url" {
  value = module.fdrop.url
}

output "mtls_url" {
  value = "https://${local.fdrop_host}:${var.ports.mtls}"
}

output "sftp" {
  value = "sftp -P ${var.ports.sftp} <user>@${local.fdrop_host}"
}

output "keycloak_admin_console" {
  value = module.keycloak.admin_console
}

output "ca_certificate" {
  description = "The local CA: trust it in the browser, and give it to the CLI with --ca (tofu output -raw ca_certificate > ca.pem)."
  value       = nonsensitive(data.kubernetes_secret_v1.local_ca.data["ca.crt"]) # a CA certificate is public
}

output "sftp_host_key_fingerprint" {
  value = module.secrets.sftp_host_key_fingerprint
}

output "keycloak_admin_password" {
  description = "Keycloak's bootstrap admin (user admin): tofu output -raw keycloak_admin_password"
  value       = module.secrets.keycloak_admin_password
  sensitive   = true
}

output "kubeconfig" {
  description = "For kubectl: tofu output -raw kubeconfig > kubeconfig && export KUBECONFIG=$PWD/kubeconfig"
  value       = kind_cluster.this.kubeconfig
  sensitive   = true
}
