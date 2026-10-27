# fdrop in production: an example

A starting point, not a turnkey install. It assumes an existing cluster (written for EKS, adaptable), a Route 53 zone
for `fdrop.<domain>`, `apps.<domain>` and `auth.<domain>`, a storage class for node-local disks for Garage (static local
PersistentVolumes, OpenEBS LocalPV or TopoLVM) on at least three nodes, and an S3 bucket outside the cluster for
PostgreSQL backups.

## Before the first apply

1. A private, versioned S3 bucket for the state, and a KMS key the deployer's role may use; set them in `versions.tf`
   (or `tofu init -backend-config=…`) and `state_kms_key_id`.
2. fdrop's image pushed to a registry, referred to by digest (`fdrop_image = "…/fdrop@sha256:…"`).
3. An IAM role for fdrop's service account (IRSA) allowed `kms:Encrypt`, `kms:Decrypt` and `kms:GenerateDataKey` on the
   storage KMS key (`storage_kms`), and cert-manager allowed to change the Route 53 zone for DNS-01.
4. A Secret `backup-credentials` in namespace `fdrop-db` with `ACCESS_KEY_ID` and `ACCESS_SECRET_KEY` for the backup
   bucket.

```bash
tofu init
tofu apply -var-file=production.tfvars
tofu output first_admin                         # creating the first admin is a person's job
```

## Restoring the database

CloudNativePG restores into a new cluster from the backup bucket: create a `Cluster` named `fdrop-db-restore` with
`bootstrap.recovery.source` pointing at an `externalClusters` entry for the same `barmanObjectStore` (and, for a point
in time, `recoveryTarget.targetTime`), check it, then point fdrop at it (`db_host` in `module.fdrop`) and apply. fdrop's
client certificate works unchanged, as the new cluster uses the same certificates and `pg_hba`. Files are in Garage,
not the database, and are not affected; Garage itself keeps three copies but is not backed up off the cluster by this
example.
