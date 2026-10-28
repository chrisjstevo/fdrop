variable "namespace" {
  description = "Where the Keycloak operator runs (the platform module installs it in keycloak)."
  type        = string
  default     = "keycloak"
}

variable "instances" {
  type    = number
  default = 2
}

variable "hostname" {
  description = "Keycloak's public host name, e.g. auth.example.com."
  type        = string
}

variable "public_port" {
  description = "The HTTPS port browsers use (443, or the host port kind maps)."
  type        = number
  default     = 443
}

variable "fdrop_url" {
  description = "fdrop's public URL, for the fdrop-web client's redirect and logout URLs."
  type        = string
}

variable "public_issuer" {
  description = "The cert-manager ClusterIssuer for public certificates."
  type        = string
}

variable "internal_issuer" {
  description = "cert-manager ClusterIssuer of the cluster's internal CA, for Keycloak's own certificate."
  type        = string
  default     = "fdrop-internal-ca"
}

variable "db_host" {
  type = string
}

variable "db_trust_secret" {
  description = "Secret in this namespace whose ca.crt checks the database server's certificate."
  type        = string
}

variable "db_password" {
  type      = string
  sensitive = true
}

variable "admin_password" {
  type      = string
  sensitive = true
}

variable "web_client_secret" {
  type      = string
  sensitive = true
}

variable "sftp_client_secret" {
  type      = string
  sensitive = true
}

variable "realm_file" {
  description = "The realm to import; the dev realm, with its users and secrets taken out unless include_dev_users."
  type        = string
  default     = null
}

variable "include_dev_users" {
  description = "Import the dev realm's users and their development passwords (kind only)."
  type        = bool
  default     = false
}

variable "fdrop_namespace" {
  type = string
}

variable "monitoring" {
  type    = bool
  default = false
}
