-- Encryption at rest: a resumable upload's multipart id also carries its wrapped data key (fdrop-enc1|…), so the
-- column holding it gets wider. Re-encryption jobs and their items are claimed through the DB like expiry rows.

ALTER TABLE upload_session ALTER COLUMN backend_upload_id SET DATA TYPE VARCHAR(4000);

CREATE TABLE reencrypt_job (
    id            VARCHAR(64)  PRIMARY KEY,
    scope         VARCHAR(16)  NOT NULL,
    from_key_id   VARCHAR(400),
    state         VARCHAR(16)  NOT NULL,
    objects_total BIGINT       NOT NULL DEFAULT 0,
    objects_done  BIGINT       NOT NULL DEFAULT 0,
    objects_failed BIGINT      NOT NULL DEFAULT 0,
    bytes_done    BIGINT       NOT NULL DEFAULT 0,
    started_by    VARCHAR(64),
    started_at    BIGINT       NOT NULL,
    finished_at   BIGINT
);

CREATE TABLE reencrypt_item (
    job_id        VARCHAR(64)   NOT NULL REFERENCES reencrypt_job (id) ON DELETE CASCADE,
    storage_key   VARCHAR(1100) NOT NULL,
    state         VARCHAR(16)   NOT NULL,
    claimed_by    VARCHAR(64),
    claimed_until BIGINT,
    error         VARCHAR(1000),
    PRIMARY KEY (job_id, storage_key)
);
CREATE INDEX reencrypt_item_state ON reencrypt_item (job_id, state);

-- Objects per key id from the last inventory, for the admin screen and fdrop_encryption_objects.
CREATE TABLE encryption_inventory (
    key_id     VARCHAR(400) PRIMARY KEY,
    objects    BIGINT       NOT NULL,
    bytes      BIGINT       NOT NULL,
    counted_at BIGINT       NOT NULL
);
