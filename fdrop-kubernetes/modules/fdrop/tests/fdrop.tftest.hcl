# fdrop's workload as planned, with mocked providers: no cluster needed.

mock_provider "kubernetes" {}
mock_provider "kubectl" {}
mock_provider "random" {}

variables {
  namespace        = "fdrop"
  image            = "registry.example.com/fdrop@sha256:0000000000000000000000000000000000000000000000000000000000000000"
  hostname         = "fdrop.example.com"
  mtls_hostname    = "apps.example.com"
  public_issuer    = "letsencrypt"
  oidc_issuer      = "https://auth.example.com/realms/fdrop"
  db_host          = "fdrop-db-rw.fdrop-db.svc"
  db_client_secret = "fdrop-db-client"
  s3 = {
    endpoint  = "http://garage.fdrop-objectstore.svc:3900"
    bucket    = "fdrop"
    region    = "garage"
    namespace = "fdrop-objectstore"
  }
  trusted_proxies      = ["10.0.0.0/16"]
  forward_destinations = ["sftp.partner.example", "203.0.113.0/24"]
  monitoring           = true
  secrets = {
    web_client_secret    = "test-web-client-secret"
    sftp_client_secret   = "test-sftp-client-secret"
    forward_key          = "dGVzdC1mb3J3YXJkLWtleQ=="
    storage_kek          = "k8s1:dGVzdC1rZWs="
    metrics_token        = "test-metrics-token"
    s3_access_key_id     = "GK000000000000000000000000"
    s3_secret_access_key = "test-s3-secret"
    sftp_host_key        = "test-host-key"
  }
}

run "the_pod_is_locked_down" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.securityContext.runAsNonRoot
    error_message = "fdrop must run as non-root"
  }
  assert {
    condition = alltrue([
      for c in yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.containers :
      c.securityContext.readOnlyRootFilesystem && !c.securityContext.allowPrivilegeEscalation
      && c.securityContext.capabilities.drop == ["ALL"]
    ])
    error_message = "fdrop's container must have a read-only root file system and no capabilities"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.automountServiceAccountToken == false
    error_message = "fdrop does not need the Kubernetes API"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.enableServiceLinks == false
    error_message = "service links such as FDROP_SFTP_PORT=tcp://… would be read as fdrop configuration"
  }
}

run "each_pod_has_its_own_volume_and_is_its_audit_node" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).kind == "StatefulSet"
    error_message = "fdrop runs as a StatefulSet, for its per-node indexes"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).spec.volumeClaimTemplates[0].spec.accessModes == ["ReadWriteOnce"]
    error_message = "each pod gets its own volume"
  }
  assert {
    condition = contains([
      for e in yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.containers[0].env :
      try(e.valueFrom.fieldRef.fieldPath, "") if e.name == "FDROP_AUDIT_NODE"
    ], "metadata.name")
    error_message = "the pod name is the audit node"
  }
}

run "files_go_to_garage_and_the_db_needs_a_certificate" {
  command = plan

  assert {
    condition     = yamldecode(kubernetes_config_map_v1.config.data["fdrop.yaml"]).storage.type == "s3"
    error_message = "fdrop stores files in S3"
  }
  assert {
    condition     = yamldecode(kubernetes_config_map_v1.config.data["fdrop.yaml"]).storage.s3.endpointOverride == "http://garage.fdrop-objectstore.svc:3900"
    error_message = "the S3 endpoint is the in-cluster Garage store"
  }
  assert {
    condition     = yamldecode(kubernetes_config_map_v1.config.data["fdrop.yaml"]).db.tls.mode == "verify-full"
    error_message = "the database connection checks the server's certificate"
  }
  assert {
    condition     = yamldecode(kubernetes_config_map_v1.config.data["fdrop.yaml"]).storage.encryption.enabled
    error_message = "files are encrypted before they reach Garage"
  }
}

run "no_secret_is_in_the_config_map" {
  command = plan

  assert {
    condition = alltrue([
      for v in ["test-web-client-secret", "test-sftp-client-secret", "test-metrics-token", "test-s3-secret", "dGVzdC1rZWs="] :
      !strcontains(kubernetes_config_map_v1.config.data["fdrop.yaml"], v)
    ])
    error_message = "secrets come from the environment, never the config map"
  }
}

run "browsers_are_pinned_to_a_pod_over_https" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.route.yaml_body).spec.routes[0].services[0].scheme == "https"
    error_message = "Traefik talks HTTPS to fdrop"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.route.yaml_body).spec.routes[0].services[0].sticky.cookie.secure
    error_message = "a secure cookie keeps each browser on one pod"
  }
  assert {
    condition     = kubernetes_service_v1.mtls.spec[0].port[0].target_port == "mtls" && kubernetes_service_v1.mtls.spec[0].type == "LoadBalancer"
    error_message = "the mTLS port goes straight to fdrop through its own load balancer"
  }
}

run "network_policies_deny_by_default" {
  command = plan

  assert {
    condition     = toset(kubernetes_network_policy_v1.default_deny.spec[0].policy_types) == toset(["Ingress", "Egress"])
    error_message = "the namespace denies everything not allowed"
  }
  assert {
    condition = anytrue([
      for e in kubernetes_network_policy_v1.fdrop.spec[0].egress :
      anytrue([for t in e.to : try(t.ip_block[0].cidr, "") == "203.0.113.0/24"]) && e.ports[0].port == "22"
    ])
    error_message = "forward destinations given as CIDRs are open on port 22"
  }
}

run "alerts_are_the_ones_in_deploy_prometheus" {
  command = plan

  assert {
    condition = length(flatten([for g in yamldecode(kubectl_manifest.rules[0].yaml_body).spec.groups : g.rules])) == length(flatten([
      for g in yamldecode(file("../../../deploy/prometheus/alerts.yml")).groups : g.rules
    ]))
    error_message = "the PrometheusRule holds every alert in deploy/prometheus/alerts.yml"
  }
}

run "a_moving_image_tag_is_refused" {
  command = plan

  variables {
    image = "registry.example.com/fdrop:latest"
  }
  expect_failures = [var.image]
}

run "storage_must_be_the_in_cluster_store" {
  command = plan

  variables {
    s3 = { endpoint = "https://s3.amazonaws.com", bucket = "x", region = "eu-west-1", namespace = "x" }
  }
  expect_failures = [var.s3]
}

run "a_self_signed_ca_is_trusted_only_when_asked" {
  command = plan

  variables {
    trust_public_ca = true
  }

  assert {
    condition = length([
      for e in yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.containers[0].env : e if e.name == "JAVA_TOOL_OPTIONS"
    ]) == 1
    error_message = "with trust_public_ca, fdrop's JVM trusts the issuing CA"
  }
}
