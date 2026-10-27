# The whole kind environment planned against mocked providers: every module wired together, no cluster needed.

mock_provider "kind" {}
mock_provider "kubernetes" {
  mock_data "kubernetes_service_v1" {
    defaults = {
      spec = [{ cluster_ip = "10.96.0.10" }]
    }
  }
}
mock_provider "helm" {}
mock_provider "kubectl" {}
mock_provider "random" {}
mock_provider "tls" {}

variables {
  state_passphrase = "a test passphrase, long enough"
}

run "everything_plans_together" {
  command = plan

  assert {
    condition     = output.fdrop_url == "https://fdrop.localtest.me:18443"
    error_message = "fdrop is at kind's mapped HTTPS port"
  }
  assert {
    condition     = module.fdrop.url == output.fdrop_url
    error_message = "the module and the environment agree on fdrop's URL"
  }
}

run "a_short_passphrase_is_refused" {
  command = plan

  variables {
    state_passphrase = "short"
  }
  expect_failures = [var.state_passphrase]
}
