# The databases as planned, with mocked providers.

mock_provider "kubernetes" {}
mock_provider "kubectl" {}

variables {
  fdrop_namespace      = "fdrop"
  keycloak_db_password = "test-password"
}

run "fdrop_signs_in_only_with_its_certificate" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.cluster["fdrop-db"].yaml_body).spec.postgresql.pg_hba[0] == "hostssl fdrop fdrop all cert"
    error_message = "fdrop's role is admitted only over TLS with a certificate"
  }
  assert {
    condition     = contains(yamldecode(kubectl_manifest.cluster["fdrop-db"].yaml_body).spec.postgresql.pg_hba, "host all fdrop all reject")
    error_message = "fdrop is refused any other way"
  }
  assert {
    condition     = contains(yamldecode(kubectl_manifest.cluster["keycloak-db"].yaml_body).spec.postgresql.pg_hba, "hostnossl all all all reject")
    error_message = "nothing connects without TLS"
  }
}

run "fdrops_certificate_is_cn_fdrop_with_a_pkcs8_key" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.fdrop_client_cert.yaml_body).spec.commonName == "fdrop"
    error_message = "the CN is the database user"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.fdrop_client_cert.yaml_body).spec.privateKey.encoding == "PKCS8"
    error_message = "fdrop reads PKCS#8 keys"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.fdrop_client_cert.yaml_body).metadata.namespace == "fdrop"
    error_message = "the certificate is issued into fdrop's namespace"
  }
}

run "backups_inside_the_cluster_are_refused" {
  command = plan

  variables {
    backup = {
      destination_path   = "s3://backups/fdrop"
      endpoint_url       = "http://garage.fdrop-objectstore.svc:3900"
      credentials_secret = "backup-credentials"
    }
  }
  expect_failures = [var.backup]
}

run "backups_go_outside" {
  command = plan

  variables {
    backup = {
      destination_path   = "s3://backups/fdrop"
      credentials_secret = "backup-credentials"
    }
  }

  assert {
    condition     = yamldecode(kubectl_manifest.cluster["fdrop-db"].yaml_body).spec.backup.barmanObjectStore.destinationPath == "s3://backups/fdrop"
    error_message = "fdrop's database archives to the given bucket"
  }
  assert {
    condition     = length(kubectl_manifest.daily_backup) == 1
    error_message = "and takes a daily base backup"
  }
}
