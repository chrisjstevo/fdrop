# Keycloak and its realm as planned, with mocked providers.

mock_provider "kubernetes" {}
mock_provider "kubectl" {}

variables {
  hostname           = "auth.example.com"
  fdrop_url          = "https://fdrop.example.com"
  public_issuer      = "letsencrypt"
  db_host            = "keycloak-db-rw.fdrop-db.svc"
  db_trust_secret    = "keycloak-db-trust"
  db_password        = "test-db-password"
  admin_password     = "test-admin-password"
  web_client_secret  = "test-web-secret"
  sftp_client_secret = "test-sftp-secret"
  fdrop_namespace    = "fdrop"
}

run "no_people_and_no_secrets_in_the_realm" {
  command = plan

  assert {
    condition     = alltrue([for u in yamldecode(kubectl_manifest.realm.yaml_body).spec.realm.users : startswith(u.username, "service-account-")])
    error_message = "outside kind, the realm brings service accounts only"
  }
  assert {
    condition = alltrue([
      for v in ["test-web-secret", "test-sftp-secret", "fdrop-web-dev-secret", "fdrop-sftp-dev-secret"] :
      !strcontains(kubectl_manifest.realm.yaml_body, v)
    ])
    error_message = "client secrets are placeholders filled from Secrets, never in the realm"
  }
}

run "the_web_client_points_at_fdrop" {
  command = plan

  assert {
    condition = one([
      for c in yamldecode(kubectl_manifest.realm.yaml_body).spec.realm.clients : c.redirectUris if c.clientId == "fdrop-web"
    ]) == ["https://fdrop.example.com/auth/callback"]
    error_message = "fdrop-web redirects to fdrop's public URL"
  }
  assert {
    condition     = output.issuer == "https://auth.example.com/realms/fdrop"
    error_message = "the issuer is Keycloak's public URL"
  }
}

run "the_database_is_checked_against_its_ca" {
  command = plan

  assert {
    condition     = strcontains(yamldecode(kubectl_manifest.keycloak.yaml_body).spec.db.url, "sslmode=verify-full")
    error_message = "Keycloak checks the database's certificate"
  }
}

run "dev_users_only_when_asked" {
  command = plan

  variables {
    include_dev_users = true
  }

  assert {
    condition     = contains([for u in yamldecode(kubectl_manifest.realm.yaml_body).spec.realm.users : u.username], "jugash")
    error_message = "kind imports the dev users"
  }
}

run "every_client_keeps_its_own_types" {
  command = plan

  assert {
    condition = alltrue([
      for c in yamldecode(kubectl_manifest.realm.yaml_body).spec.realm.clients : c.enabled == true && c.publicClient == false
    ])
    error_message = "booleans stay booleans (the CRD refuses \"true\" as a string)"
  }
  assert {
    condition = one([
      for c in yamldecode(kubectl_manifest.realm.yaml_body).spec.realm.clients : c.attributes["backchannel.logout.session.required"] if c.clientId == "fdrop-web"
    ]) == "true"
    error_message = "fdrop-web keeps the realm's other attributes"
  }
}

run "traefik_re_encrypts_to_keycloak" {
  command = plan

  assert {
    condition     = yamldecode(kubectl_manifest.keycloak.yaml_body).spec.http == { httpEnabled = false, httpsPort = 8443, tlsSecret = "keycloak-internal-tls" }
    error_message = "Keycloak serves HTTPS only, with its internal certificate"
  }
  assert {
    condition = (yamldecode(kubectl_manifest.route.yaml_body).spec.routes[0].services[0].scheme == "https"
    && yamldecode(kubectl_manifest.route.yaml_body).spec.routes[0].services[0].serversTransport == "keycloak")
    error_message = "Traefik calls Keycloak over HTTPS"
  }
  assert {
    condition     = yamldecode(kubectl_manifest.servers_transport.yaml_body).spec.rootCAs == [{ secret = "keycloak-internal-tls" }]
    error_message = "and checks its certificate against the internal CA"
  }
  assert {
    condition     = kubernetes_network_policy_v1.keycloak.spec[0].ingress[0].ports[0].port == "8443"
    error_message = "Traefik reaches the HTTPS port"
  }
}
