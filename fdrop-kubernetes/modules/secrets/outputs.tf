output "keycloak_admin_password" {
  value     = random_password.keycloak_admin.result
  sensitive = true
}

output "keycloak_db_password" {
  value     = random_password.keycloak_db.result
  sensitive = true
}

output "web_client_secret" {
  value     = random_password.web_client.result
  sensitive = true
}

output "sftp_client_secret" {
  value     = random_password.sftp_client.result
  sensitive = true
}

output "metrics_token" {
  value     = random_password.metrics_token.result
  sensitive = true
}

output "forward_key" {
  value     = random_bytes.forward_key.base64
  sensitive = true
}

output "storage_kek" {
  description = "The local key provider's key list: id:base64key."
  value       = "k8s1:${random_bytes.storage_kek.base64}"
  sensitive   = true
}

output "garage_rpc_secret" {
  value     = random_bytes.garage_rpc.hex
  sensitive = true
}

output "garage_admin_token" {
  value     = random_password.garage_admin.result
  sensitive = true
}

output "garage_metrics_token" {
  value     = random_password.garage_metrics.result
  sensitive = true
}

output "s3_access_key_id" {
  value     = "GK${random_bytes.s3_key_id.hex}"
  sensitive = true
}

output "s3_secret_access_key" {
  value     = random_bytes.s3_secret.hex
  sensitive = true
}

output "sftp_host_key" {
  description = "OpenSSH private key, as fdrop's sftp.hostKeyPath reads it."
  value       = tls_private_key.sftp_host.private_key_openssh
  sensitive   = true
}

output "sftp_host_key_fingerprint" {
  description = "To check against what SFTP clients show; not secret."
  value       = tls_private_key.sftp_host.public_key_fingerprint_sha256
}
