# fdrop on a local kind cluster, for trying it (see README.md): one fdrop pod, one Garage node, one PostgreSQL
# instance each, a self-signed CA, and the dev realm's users.

locals {
  node_ports = { https = 30443, mtls = 30444, sftp = 30222 }
  fdrop_host = "fdrop.${var.domain}"
  auth_host  = "auth.${var.domain}"
  fdrop_url  = "https://${local.fdrop_host}:${var.ports.https}"
}

resource "kind_cluster" "this" {
  name           = "fdrop"
  wait_for_ready = true

  kind_config {
    kind        = "Cluster"
    api_version = "kind.x-k8s.io/v1alpha4"

    node {
      role = "control-plane"
      extra_port_mappings {
        container_port = local.node_ports.https
        host_port      = var.ports.https
        listen_address = "127.0.0.1"
      }
      extra_port_mappings {
        container_port = local.node_ports.mtls
        host_port      = var.ports.mtls
        listen_address = "127.0.0.1"
      }
      extra_port_mappings {
        container_port = local.node_ports.sftp
        host_port      = var.ports.sftp
        listen_address = "127.0.0.1"
      }
    }
  }
}

provider "kubernetes" {
  host                   = kind_cluster.this.endpoint
  client_certificate     = kind_cluster.this.client_certificate
  client_key             = kind_cluster.this.client_key
  cluster_ca_certificate = kind_cluster.this.cluster_ca_certificate
}

provider "helm" {
  kubernetes = {
    host                   = kind_cluster.this.endpoint
    client_certificate     = kind_cluster.this.client_certificate
    client_key             = kind_cluster.this.client_key
    cluster_ca_certificate = kind_cluster.this.cluster_ca_certificate
  }
}

provider "kubectl" {
  host                   = kind_cluster.this.endpoint
  client_certificate     = kind_cluster.this.client_certificate
  client_key             = kind_cluster.this.client_key
  cluster_ca_certificate = kind_cluster.this.cluster_ca_certificate
  load_config_file       = false
}

module "platform" {
  source             = "../../modules/platform"
  traefik_node_ports = { web = 30080, websecure = local.node_ports.https }
  traefik_https_port = var.ports.https
  monitoring         = var.monitoring
  depends_on         = [kind_cluster.this]
}

module "secrets" {
  source = "../../modules/secrets"
}

# A local CA for every public certificate; its certificate is an output, to trust in the browser and the CLI
resource "kubectl_manifest" "local_ca_bootstrap" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = "fdrop-local-bootstrap" }
    spec       = { selfSigned = {} }
  })
  depends_on = [module.platform]
}

resource "kubectl_manifest" "local_ca" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "Certificate"
    metadata   = { name = "fdrop-local-ca", namespace = "cert-manager" }
    spec = {
      isCA       = true
      commonName = "fdrop kind CA"
      secretName = "fdrop-local-ca"
      duration   = "87600h"
      privateKey = { algorithm = "ECDSA", size = 256 }
      issuerRef  = { name = "fdrop-local-bootstrap", kind = "ClusterIssuer" }
    }
  })
  wait_for {
    field {
      key   = "status.conditions.[0].status"
      value = "True"
    }
  }
  depends_on = [kubectl_manifest.local_ca_bootstrap]
}

resource "kubectl_manifest" "local_issuer" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = "fdrop-local-ca" }
    spec       = { ca = { secretName = "fdrop-local-ca" } }
  })
  depends_on = [kubectl_manifest.local_ca]
}

resource "kubernetes_namespace_v1" "fdrop" {
  metadata {
    name   = "fdrop"
    labels = { "pod-security.kubernetes.io/enforce" = "restricted" }
  }
  depends_on = [kind_cluster.this]
}

module "objectstore" {
  source             = "../../modules/objectstore"
  replicas           = 1
  replication_factor = 1
  storage_class      = "standard" # kind's local-path provisioner: a folder on the node's disk
  data_size          = "10Gi"
  meta_size          = "1Gi"
  client_namespace   = kubernetes_namespace_v1.fdrop.metadata[0].name
  monitoring         = var.monitoring
  rpc_secret         = module.secrets.garage_rpc_secret
  admin_token        = module.secrets.garage_admin_token
  metrics_token      = module.secrets.garage_metrics_token
  access_key_id      = module.secrets.s3_access_key_id
  secret_access_key  = module.secrets.s3_secret_access_key
  depends_on         = [module.platform]
}

module "postgres" {
  source               = "../../modules/postgres"
  fdrop_namespace      = kubernetes_namespace_v1.fdrop.metadata[0].name
  fdrop_instances      = 1
  keycloak_instances   = 1
  storage_size         = "2Gi"
  keycloak_db_password = module.secrets.keycloak_db_password
  monitoring           = var.monitoring
  depends_on           = [module.platform]
}

module "keycloak" {
  source             = "../../modules/keycloak"
  instances          = 1
  hostname           = local.auth_host
  public_port        = var.ports.https
  fdrop_url          = local.fdrop_url
  public_issuer      = "fdrop-local-ca"
  db_host            = module.postgres.keycloak_host
  db_trust_secret    = module.postgres.keycloak_trust_secret
  db_password        = module.secrets.keycloak_db_password
  admin_password     = module.secrets.keycloak_admin_password
  web_client_secret  = module.secrets.web_client_secret
  sftp_client_secret = module.secrets.sftp_client_secret
  include_dev_users  = true
  fdrop_namespace    = kubernetes_namespace_v1.fdrop.metadata[0].name
  monitoring         = var.monitoring
  depends_on         = [module.platform, module.postgres, kubectl_manifest.local_issuer]
}

# The public names resolve to 127.0.0.1 everywhere, so inside the cluster fdrop is pointed at Traefik for them
data "kubernetes_service_v1" "traefik" {
  metadata {
    name      = "traefik"
    namespace = "traefik"
  }
  depends_on = [module.platform]
}

module "fdrop" {
  source           = "../../modules/fdrop"
  namespace        = kubernetes_namespace_v1.fdrop.metadata[0].name
  image            = var.fdrop_image
  replicas         = 1
  hostname         = local.fdrop_host
  mtls_hostname    = local.fdrop_host
  public_port      = var.ports.https
  public_issuer    = "fdrop-local-ca"
  trust_public_ca  = true
  internal_ca      = module.platform.internal_ca.certificate
  oidc_issuer      = module.keycloak.issuer
  two_factor       = "none" # the dev users have no second factor; set admins to try the production behaviour
  db_host          = module.postgres.fdrop_host
  db_client_secret = module.postgres.fdrop_client_secret
  s3 = {
    endpoint  = module.objectstore.endpoint
    bucket    = module.objectstore.bucket
    region    = module.objectstore.region
    namespace = module.objectstore.namespace
  }
  trusted_proxies = ["10.244.0.0/16"] # kind's pod network, where Traefik runs
  service_type    = "NodePort"
  node_ports      = { mtls = local.node_ports.mtls, sftp = local.node_ports.sftp }
  host_aliases = [{
    ip        = data.kubernetes_service_v1.traefik.spec[0].cluster_ip
    hostnames = [local.fdrop_host, local.auth_host]
  }]
  volume_size = "2Gi"
  resources   = { cpu_request = "250m", memory_request = "1Gi", memory_limit = "3Gi" }
  monitoring  = var.monitoring
  secrets = {
    web_client_secret    = module.secrets.web_client_secret
    sftp_client_secret   = module.secrets.sftp_client_secret
    forward_key          = module.secrets.forward_key
    storage_kek          = module.secrets.storage_kek
    metrics_token        = module.secrets.metrics_token
    s3_access_key_id     = module.secrets.s3_access_key_id
    s3_secret_access_key = module.secrets.s3_secret_access_key
    sftp_host_key        = module.secrets.sftp_host_key
  }
  depends_on = [module.objectstore, module.postgres, module.keycloak]
}

# Read once fdrop is up: its certificate comes from this CA, so by then cert-manager has written the CA's Secret
data "kubernetes_secret_v1" "local_ca" {
  metadata {
    name      = "fdrop-local-ca"
    namespace = "cert-manager"
  }
  depends_on = [module.fdrop]
}
