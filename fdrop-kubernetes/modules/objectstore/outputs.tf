output "endpoint" {
  description = "fdrop's storage.s3.endpointOverride: HTTPS, with a certificate from the internal CA."
  value       = "https://garage.${var.namespace}.svc:3443"
}

output "bucket" {
  value = var.bucket
}

output "region" {
  value = "garage"
}

output "namespace" {
  value = var.namespace
}

output "setup_job" {
  description = "The Job that set Garage up; fdrop waits for it."
  value       = kubernetes_job_v1.setup.metadata[0].name
}
