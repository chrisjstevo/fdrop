variable "namespace" {
  type    = string
  default = "fdrop-objectstore"
}

variable "replicas" {
  description = "Garage pods, each on its own node with its own local volumes."
  type        = number
  default     = 3
}

variable "replication_factor" {
  description = "Copies of every object: 1 with a single pod, 3 with three or more."
  type        = number
  default     = 3

  validation {
    condition     = contains([1, 3], var.replication_factor)
    error_message = "replication_factor must be 1 (one pod) or 3 (three or more pods)."
  }
}

variable "storage_class" {
  description = "A storage class for node-local disks: local-path on kind; static local PVs, OpenEBS LocalPV or TopoLVM in production."
  type        = string
}

variable "data_size" {
  description = "Data volume per pod; also the capacity each node announces in Garage's layout."
  type        = string
  default     = "100Gi"
}

variable "meta_size" {
  description = "Metadata volume per pod (Garage's database), preferably on faster disk."
  type        = string
  default     = "10Gi"
}

variable "image" {
  description = "Garage, pinned by digest. Keep in step with fdrop-storage-s3's GarageContainer."
  type        = string
  default     = "docker.io/dxflrs/garage:v2.5.0@sha256:23b16d34e0348d9aca9b960a7429a7e6d494974b5288bfb8f6c3ab856895f71e"
}

variable "setup_image" {
  description = "Runs the setup Job's shell script with curl."
  type        = string
  default     = "docker.io/curlimages/curl:8.23.0@sha256:11e64e28c5f58c8d93ee95fcf22ac055f48b0bc440e0ce7d8013bbb5ad6aa8ac"
}

variable "bucket" {
  type    = string
  default = "fdrop"
}

variable "client_namespace" {
  description = "fdrop's namespace: the only one admitted to the S3 API."
  type        = string
}

variable "monitoring" {
  description = "Create a ServiceMonitor and the object store alerts."
  type        = bool
  default     = false
}

variable "monitoring_namespace" {
  type    = string
  default = "monitoring"
}

variable "rpc_secret" {
  type      = string
  sensitive = true
}

variable "admin_token" {
  type      = string
  sensitive = true
}

variable "metrics_token" {
  type      = string
  sensitive = true
}

variable "access_key_id" {
  type      = string
  sensitive = true
}

variable "secret_access_key" {
  type      = string
  sensitive = true
}
