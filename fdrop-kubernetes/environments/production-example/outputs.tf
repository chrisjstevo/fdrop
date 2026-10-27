output "fdrop_url" {
  value = module.fdrop.url
}

output "mtls_url" {
  value = module.fdrop.mtls_url
}

output "keycloak_admin_console" {
  value = module.keycloak.admin_console
}

output "first_admin" {
  description = "Creating the first fdrop admin is a person's job, not OpenTofu's."
  value       = <<-EOT
    1. tofu output -raw keycloak_admin_password, and sign in to ${module.keycloak.admin_console} as admin.
    2. In the fdrop realm, add a user with the realm roles fdrop-user and fdrop-admin, and the required actions
       "Configure OTP" and "Update Password".
    3. They sign in at ${module.fdrop.url} and set up their second factor at once.
    4. Then disable the bootstrap admin, or keep it only for break-glass use.
  EOT
}

output "sftp_host_key_fingerprint" {
  value = module.secrets.sftp_host_key_fingerprint
}

output "keycloak_admin_password" {
  value     = module.secrets.keycloak_admin_password
  sensitive = true
}
