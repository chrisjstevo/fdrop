variable "namespace" {
  description = "fdrop's namespace (created by the environment, as the database module issues into it)."
  type        = string
}

variable "image" {
  description = "fdrop's OCI image, by digest: registry/fdrop@sha256:…"
  type        = string

  validation {
    condition     = can(regex("@sha256:[0-9a-f]{64}$", var.image)) || startswith(var.image, "localhost/")
    error_message = "Refer to fdrop's image by digest (…@sha256:…), never by a tag that can move; only a localhost/ image loaded into kind may use a tag."
  }
}

variable "replicas" {
  type    = number
  default = 3
}

variable "hostname" {
  description = "fdrop's public host name for browsers and the API."
  type        = string
}

variable "mtls_hostname" {
  description = "The host name application clients use for the mTLS port (also in fdrop's certificate)."
  type        = string
}

variable "public_port" {
  description = "The HTTPS port browsers use: 443, or the host port kind maps."
  type        = number
  default     = 443
}

variable "public_issuer" {
  description = "cert-manager ClusterIssuer for fdrop's public certificate."
  type        = string
}

variable "trust_public_ca" {
  description = "Have fdrop (for Keycloak's public URL), Traefik and Prometheus trust the public issuer's CA (a self-signed one, on kind); off with a real CA."
  type        = bool
  default     = false
}

variable "internal_ca" {
  description = "The internal CA's certificate (PEM), which fdrop trusts for Garage's TLS sidecars (storage.s3.trustedCas)."
  type        = string
}

variable "oidc_issuer" {
  type = string
}

variable "two_factor" {
  description = "auth.twoFactor.require: none, admins or all."
  type        = string
  default     = "admins"

  validation {
    condition     = contains(["none", "admins", "all"], var.two_factor)
    error_message = "two_factor must be none, admins or all."
  }
}

variable "db_host" {
  description = "The database's read-write service, <cluster>-rw.<namespace>.svc."
  type        = string
}

variable "db_client_secret" {
  description = "Secret in this namespace with the database CA, fdrop's client certificate and its PKCS#8 key."
  type        = string
}

variable "s3" {
  description = "The in-cluster object store fdrop keeps its files in."
  type = object({
    endpoint  = string
    bucket    = string
    region    = string
    namespace = string
  })

  validation {
    condition     = can(regex("^https://[^/]+\\.svc:[0-9]+$", var.s3.endpoint))
    error_message = "fdrop's storage is the in-cluster Garage object store over HTTPS; give its service endpoint, https://<service>.<namespace>.svc:<port>."
  }
}

variable "storage_encryption" {
  description = "local (a generated KEK in a Secret) or aws-kms (key_id, region; credentials from workload identity)."
  type = object({
    provider = string
    key_id   = optional(string)
    region   = optional(string)
  })
  default = { provider = "local" }

  validation {
    condition     = contains(["local", "aws-kms"], var.storage_encryption.provider)
    error_message = "storage_encryption.provider must be local or aws-kms."
  }
}

variable "service_account_annotations" {
  description = "For workload identity (IRSA, GKE Workload Identity), e.g. to reach AWS KMS."
  type        = map(string)
  default     = {}
}

variable "trusted_proxies" {
  description = "CIDRs of Traefik's pods, whose X-Forwarded-For fdrop believes."
  type        = list(string)
}

variable "forward_destinations" {
  description = "Hosts and CIDRs forward folders may send to (forward.allowedHosts); CIDRs are also opened for port 22."
  type        = list(string)
  default     = []
}

variable "service_type" {
  description = "LoadBalancer for the mTLS and SFTP services, or NodePort (kind)."
  type        = string
  default     = "LoadBalancer"

  validation {
    condition     = contains(["LoadBalancer", "NodePort"], var.service_type)
    error_message = "service_type must be LoadBalancer or NodePort."
  }
}

variable "node_ports" {
  description = "With service_type NodePort: the node ports for mTLS and SFTP."
  type        = object({ mtls = number, sftp = number })
  default     = null
}

variable "host_aliases" {
  description = "Extra /etc/hosts entries, so public host names resolve inside the cluster (kind)."
  type        = list(object({ ip = string, hostnames = list(string) }))
  default     = []
}

variable "volume_size" {
  description = "Each pod's volume: its search and audit indexes and the SFTP spool, never stored files."
  type        = string
  default     = "20Gi"
}

variable "storage_class" {
  type    = string
  default = null
}

variable "resources" {
  description = "The server's heap and the forked text extractor (search.extract) share the memory limit."
  type = object({
    cpu_request    = string
    memory_request = string
    memory_limit   = string
  })
  default = { cpu_request = "500m", memory_request = "2Gi", memory_limit = "4Gi" }
}

variable "monitoring" {
  type    = bool
  default = false
}

variable "secrets" {
  description = "Generated by the secrets module."
  type = object({
    web_client_secret    = string
    sftp_client_secret   = string
    forward_key          = string
    storage_kek          = string
    metrics_token        = string
    s3_access_key_id     = string
    s3_secret_access_key = string
    sftp_host_key        = string
  })
  sensitive = true
}
