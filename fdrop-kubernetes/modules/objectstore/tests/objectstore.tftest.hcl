# Garage as planned, with mocked providers.

mock_provider "kubernetes" {}
mock_provider "kubectl" {}

variables {
  storage_class     = "local-path"
  client_namespace  = "fdrop"
  rpc_secret        = "00"
  admin_token       = "test-admin-token"
  metrics_token     = "test-metrics-token"
  access_key_id     = "GK000000000000000000000000"
  secret_access_key = "test-secret"
}

run "three_nodes_on_three_hosts_with_three_copies" {
  command = plan

  assert {
    condition     = strcontains(kubernetes_config_map_v1.config.data["garage.toml"], "replication_factor = 3")
    error_message = "production keeps three copies"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.affinity.podAntiAffinity.requiredDuringSchedulingIgnoredDuringExecution[0].topologyKey == "kubernetes.io/hostname"
    error_message = "Garage pods require separate nodes"
  }
  assert {
    condition = alltrue([
      for t in yamldecode(kubectl_manifest.statefulset.yaml_body).spec.volumeClaimTemplates : t.spec.storageClassName == "local-path"
    ])
    error_message = "data and metadata are on the local storage class"
  }
}

run "one_node_keeps_one_copy" {
  command = plan

  variables {
    replicas           = 1
    replication_factor = 1
  }

  assert {
    condition     = strcontains(kubernetes_config_map_v1.config.data["garage.toml"], "replication_factor = 1")
    error_message = "a single node keeps one copy"
  }
}

run "more_copies_than_nodes_is_refused" {
  command = plan

  variables {
    replicas           = 2
    replication_factor = 3
  }
  expect_failures = [terraform_data.replication_check]
}

run "two_copies_is_refused" {
  command = plan

  variables {
    replication_factor = 2
  }
  expect_failures = [var.replication_factor]
}

run "only_fdrop_reaches_the_s3_api" {
  command = plan

  assert {
    condition = anytrue([
      for i in kubernetes_network_policy_v1.garage.spec[0].ingress :
      i.ports[0].port == "3443" && i.from[0].namespace_selector[0].match_labels["kubernetes.io/metadata.name"] == "fdrop"
    ])
    error_message = "the S3 port admits fdrop's namespace"
  }
  assert {
    condition     = toset(kubernetes_network_policy_v1.default_deny.spec[0].policy_types) == toset(["Ingress", "Egress"])
    error_message = "everything else is denied"
  }
}

run "the_key_is_the_generated_one" {
  command = plan

  assert {
    condition     = kubernetes_secret_v1.fdrop_key.data["access-key-id"] == "GK000000000000000000000000"
    error_message = "the setup Job imports the key OpenTofu generated"
  }
  assert {
    condition     = contains([for e in kubernetes_job_v1.setup.spec[0].template[0].spec[0].container[0].env : e.name], "KEY_SECRET")
    error_message = "the Job reads the key's secret from the Secret, not from its own spec"
  }
}

run "garage_is_reached_only_over_https" {
  command = plan

  assert {
    condition = (strcontains(kubernetes_config_map_v1.config.data["garage.toml"], "api_bind_addr = \"127.0.0.1:3900\"")
    && strcontains(kubernetes_config_map_v1.config.data["garage.toml"], "api_bind_addr = \"127.0.0.1:3903\""))
    error_message = "Garage's plain-HTTP APIs listen inside the pod only"
  }
  assert {
    condition = toset([
      for c in yamldecode(kubectl_manifest.statefulset.yaml_body).spec.template.spec.containers : c.name
    ]) == toset(["garage", "s3-tls", "admin-tls"])
    error_message = "a TLS sidecar fronts each API"
  }
  assert {
    condition     = toset([for p in kubernetes_service_v1.garage.spec[0].port : p.port]) == toset([3443, 3943])
    error_message = "the Service offers only the TLS ports"
  }
  assert {
    condition     = output.endpoint == "https://garage.fdrop-objectstore.svc:3443"
    error_message = "fdrop is given the HTTPS endpoint"
  }
  assert {
    condition = (one([for e in kubernetes_job_v1.setup.spec[0].template[0].spec[0].container[0].env : e.value if e.name == "GARAGE"]) == "https://garage.fdrop-objectstore.svc:3943"
    && strcontains(kubernetes_job_v1.setup.spec[0].template[0].spec[0].container[0].command[2], "--cacert"))
    error_message = "the setup Job calls the admin API over HTTPS and checks its certificate"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.certificate.yaml_body).spec.issuerRef.name == "fdrop-internal-ca"
    error_message = "the sidecars' certificate comes from the internal CA"
  }
}
