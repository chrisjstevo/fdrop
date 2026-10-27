output "issuer" {
  description = "The fdrop realm's issuer URL, as browsers and fdrop see it."
  value       = "${local.public_url}/realms/fdrop"
}

output "admin_console" {
  value = "${local.public_url}/admin/"
}

output "realm_users" {
  description = "Users the realm import brings (service accounts only, outside kind)."
  value       = [for u in local.users : u.username]
}

output "ready" {
  value = kubectl_manifest.realm.id
}
