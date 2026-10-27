variable "state_passphrase" {
  description = "Encrypts the state and plans (at least 16 characters); never stored. Keep it to apply again."
  type        = string
  sensitive   = true

  validation {
    condition     = length(var.state_passphrase) >= 16
    error_message = "state_passphrase must be at least 16 characters."
  }
}

variable "fdrop_image" {
  description = "fdrop's image, loaded into the kind cluster (see README.md)."
  type        = string
  default     = "localhost/fdrop:0.1.0-SNAPSHOT"
}

variable "domain" {
  description = "Names under it resolve to 127.0.0.1 (localtest.me does), so the browser reaches kind's mapped ports."
  type        = string
  default     = "localtest.me"
}

variable "ports" {
  description = "Host ports kind maps to the cluster: HTTPS (fdrop and Keycloak), application mTLS and SFTP. Not 8443, 8444 and 2222, which an IDE forwarding the dev container usually holds."
  type        = object({ https = number, mtls = number, sftp = number })
  default     = { https = 18443, mtls = 18444, sftp = 12222 }
}

variable "monitoring" {
  description = "Also install kube-prometheus-stack with fdrop's alerts and dashboard (needs more memory)."
  type        = bool
  default     = false
}
