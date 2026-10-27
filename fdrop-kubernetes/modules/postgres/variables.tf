variable "namespace" {
  type    = string
  default = "fdrop-db"
}

variable "fdrop_namespace" {
  description = "Where fdrop runs: its client certificate is issued there."
  type        = string
}

variable "keycloak_namespace" {
  type    = string
  default = "keycloak"
}

variable "fdrop_instances" {
  description = "PostgreSQL instances for fdrop's database (1 on kind, 3 in production)."
  type        = number
  default     = 3
}

variable "keycloak_instances" {
  type    = number
  default = 2
}

variable "storage_class" {
  description = "Storage class for the database volumes; null for the cluster default."
  type        = string
  default     = null
}

variable "storage_size" {
  type    = string
  default = "20Gi"
}

variable "keycloak_db_password" {
  type      = string
  sensitive = true
}

variable "monitoring" {
  type    = bool
  default = false
}

variable "backup" {
  description = "Continuous WAL archiving and daily base backups to an S3 bucket outside the cluster; null for none."
  type = object({
    destination_path   = string # s3://bucket/path
    endpoint_url       = optional(string)
    credentials_secret = string # a Secret in this namespace with ACCESS_KEY_ID and ACCESS_SECRET_KEY
    retention          = optional(string, "30d")
  })
  default = null

  validation {
    condition     = var.backup == null ? true : (var.backup.endpoint_url == null ? true : !strcontains(var.backup.endpoint_url, ".svc"))
    error_message = "Backups must go outside the cluster: an in-cluster endpoint (such as the Garage object store) sits on the same nodes as the database it backs up."
  }
}
