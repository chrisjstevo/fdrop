output "url" {
  value = local.public_url
}

output "mtls_url" {
  value = "https://${var.mtls_hostname}:8444"
}

output "config" {
  description = "fdrop.yaml as deployed (no secrets: they come from the environment)."
  value       = local.config_yaml
}
