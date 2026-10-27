# An example production deployment on an existing cluster (see README.md): three fdrop pods, three Garage nodes on
# local disks, replicated PostgreSQL with backups outside the cluster, Let's Encrypt certificates, AWS KMS for state
# and for fdrop's data keys, and Prometheus with fdrop's alerts. Copy it and adapt the variables.

locals {
  fdrop_host = "fdrop.${var.domain}"
  mtls_host  = "apps.${var.domain}"
  auth_host  = "auth.${var.domain}"
}

provider "kubernetes" {
  config_path    = var.kubeconfig_path
  config_context = var.kube_context
}

provider "helm" {
  kubernetes = {
    config_path    = var.kubeconfig_path
    config_context = var.kube_context
  }
}

provider "kubectl" {
  config_path      = var.kubeconfig_path
  config_context   = var.kube_context
  load_config_file = true
}

module "platform" {
  source     = "../../modules/platform"
  monitoring = true
}

module "secrets" {
  source = "../../modules/secrets"
}

resource "kubectl_manifest" "letsencrypt" {
  yaml_body = yamlencode({
    apiVersion = "cert-manager.io/v1"
    kind       = "ClusterIssuer"
    metadata   = { name = "letsencrypt" }
    spec = {
      acme = {
        email               = var.acme.email
        server              = var.acme.server
        privateKeySecretRef = { name = "letsencrypt-account" }
        solvers = [{
          dns01 = { route53 = { region = var.aws_region, hostedZoneID = var.acme.hosted_zone_id } }
        }]
      }
    }
  })
  depends_on = [module.platform]
}

resource "kubernetes_namespace_v1" "fdrop" {
  metadata {
    name   = "fdrop"
    labels = { "pod-security.kubernetes.io/enforce" = "restricted" }
  }
}

module "objectstore" {
  source             = "../../modules/objectstore"
  replicas           = var.objectstore.replicas
  replication_factor = 3
  storage_class      = var.objectstore.storage_class
  data_size          = var.objectstore.data_size
  meta_size          = var.objectstore.meta_size
  client_namespace   = kubernetes_namespace_v1.fdrop.metadata[0].name
  monitoring         = true
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
  fdrop_instances      = 3
  keycloak_instances   = 2
  storage_size         = "50Gi"
  keycloak_db_password = module.secrets.keycloak_db_password
  monitoring           = true
  backup               = var.backup
  depends_on           = [module.platform]
}

module "keycloak" {
  source             = "../../modules/keycloak"
  instances          = 2
  hostname           = local.auth_host
  fdrop_url          = "https://${local.fdrop_host}"
  public_issuer      = "letsencrypt"
  db_host            = module.postgres.keycloak_host
  db_trust_secret    = module.postgres.keycloak_trust_secret
  db_password        = module.secrets.keycloak_db_password
  admin_password     = module.secrets.keycloak_admin_password
  web_client_secret  = module.secrets.web_client_secret
  sftp_client_secret = module.secrets.sftp_client_secret
  fdrop_namespace    = kubernetes_namespace_v1.fdrop.metadata[0].name
  monitoring         = true
  depends_on         = [module.platform, module.postgres, kubectl_manifest.letsencrypt]
}

module "fdrop" {
  source           = "../../modules/fdrop"
  namespace        = kubernetes_namespace_v1.fdrop.metadata[0].name
  image            = var.fdrop_image
  replicas         = 3
  hostname         = local.fdrop_host
  mtls_hostname    = local.mtls_host
  public_issuer    = "letsencrypt"
  oidc_issuer      = module.keycloak.issuer
  two_factor       = "admins"
  db_host          = module.postgres.fdrop_host
  db_client_secret = module.postgres.fdrop_client_secret
  s3 = {
    endpoint  = module.objectstore.endpoint
    bucket    = module.objectstore.bucket
    region    = module.objectstore.region
    namespace = module.objectstore.namespace
  }
  storage_encryption          = { provider = "aws-kms", key_id = var.storage_kms.key_id, region = var.aws_region }
  service_account_annotations = { "eks.amazonaws.com/role-arn" = var.storage_kms.role_arn }
  trusted_proxies             = [var.pod_cidr]
  forward_destinations        = var.forward_destinations
  monitoring                  = true
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
