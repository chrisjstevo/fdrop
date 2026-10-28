variable "cert_manager" {
  description = "Install cert-manager (turn off when the cluster already has it)."
  type        = bool
  default     = true
}

variable "cert_manager_namespace" {
  description = "cert-manager's namespace, where a ClusterIssuer's CA Secret must be (its cluster resource namespace)."
  type        = string
  default     = "cert-manager"
}

variable "cloudnative_pg" {
  description = "Install the CloudNativePG operator."
  type        = bool
  default     = true
}

variable "traefik" {
  description = "Install Traefik as the ingress controller."
  type        = bool
  default     = true
}

variable "traefik_node_ports" {
  description = "Expose Traefik on these node ports instead of a LoadBalancer (kind maps them to the host)."
  type        = object({ web = number, websecure = number })
  default     = null
}

variable "traefik_https_port" {
  description = "The port Traefik's service serves HTTPS on; on kind the same as the host port, so public URLs work inside the cluster too."
  type        = number
  default     = 443
}

variable "keycloak_operator" {
  description = "Install the Keycloak operator into the keycloak namespace."
  type        = bool
  default     = true
}

variable "monitoring" {
  description = "Create ServiceMonitors and PrometheusRules (needs the Prometheus Operator CRDs)."
  type        = bool
  default     = false
}

variable "install_prometheus" {
  description = "With monitoring on, also install kube-prometheus-stack (off when the cluster has its own)."
  type        = bool
  default     = true
}
