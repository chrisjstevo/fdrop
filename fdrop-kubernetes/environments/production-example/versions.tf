terraform {
  required_version = ">= 1.10"

  required_providers {
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 3.4"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.3"
    }
    kubectl = {
      source  = "alekc/kubectl"
      version = "~> 2.4"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.9"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.4"
    }
  }

  # State in a private, versioned S3 bucket with S3's own lock file (no DynamoDB table). Fill these in, or pass
  # them with tofu init -backend-config=…
  backend "s3" {
    bucket       = "example-fdrop-tofu-state"
    key          = "fdrop/production.tfstate"
    region       = "eu-west-1"
    use_lockfile = true
  }

  # State and plans hold the generated secrets: both are encrypted with a KMS key the deployer's role may use, and an
  # unencrypted one is refused. Even read access to the bucket reveals no secret without the key. To rotate the key,
  # add the old one as a fallback for one apply, then remove it.
  encryption {
    key_provider "aws_kms" "state" {
      kms_key_id = var.state_kms_key_id
      region     = var.aws_region
      key_spec   = "AES_256"
    }
    method "aes_gcm" "state" {
      keys = key_provider.aws_kms.state
    }
    state {
      method   = method.aes_gcm.state
      enforced = true
    }
    plan {
      method   = method.aes_gcm.state
      enforced = true
    }
  }
}
