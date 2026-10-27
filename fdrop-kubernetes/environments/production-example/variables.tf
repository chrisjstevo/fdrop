variable "kubeconfig_path" {
  description = "The deployer's kubeconfig for the existing cluster."
  type        = string
  default     = "~/.kube/config"
}

variable "kube_context" {
  type    = string
  default = null
}

variable "aws_region" {
  type    = string
  default = "eu-west-1"
}

variable "state_kms_key_id" {
  description = "KMS key (ARN or alias) that encrypts OpenTofu's state and plans."
  type        = string
}

variable "fdrop_image" {
  description = "fdrop's image by digest, e.g. ghcr.io/example/fdrop@sha256:…"
  type        = string
}

variable "domain" {
  description = "fdrop is at fdrop.<domain>, application clients at apps.<domain>, Keycloak at auth.<domain>."
  type        = string
}

variable "acme" {
  description = "Let's Encrypt account and the Route 53 zone for DNS-01 (cert-manager uses workload identity for it)."
  type = object({
    email          = string
    hosted_zone_id = string
    server         = optional(string, "https://acme-v02.api.letsencrypt.org/directory")
  })
}

variable "objectstore" {
  description = "Garage on node-local disks: a local-volume storage class, and the size of each node's volumes."
  type = object({
    storage_class = string
    data_size     = optional(string, "500Gi")
    meta_size     = optional(string, "20Gi")
    replicas      = optional(number, 3)
  })
}

variable "storage_kms" {
  description = "AWS KMS key wrapping fdrop's data keys (storage.encryption), and the role fdrop's service account assumes."
  type = object({
    key_id   = string
    role_arn = string
  })
}

variable "pod_cidr" {
  description = "The cluster's pod network, where Traefik runs (fdrop's http.trustedProxies)."
  type        = string
}

variable "forward_destinations" {
  description = "SFTP hosts and CIDRs forward folders may send to."
  type        = list(string)
  default     = []
}

variable "backup" {
  description = "Where PostgreSQL backups go: a bucket outside the cluster, and a Secret in fdrop-db with its credentials."
  type = object({
    destination_path   = string
    endpoint_url       = optional(string)
    credentials_secret = string
  })
}
