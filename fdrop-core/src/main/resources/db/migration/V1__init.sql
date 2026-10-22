-- fdrop metadata schema. Written to run unchanged on H2 (PostgreSQL mode) and PostgreSQL.
-- Timestamps are epoch milliseconds (BIGINT) to avoid time-zone differences between the two.

CREATE TABLE users (
    sub           VARCHAR(64)  PRIMARY KEY,
    username      VARCHAR(255) NOT NULL,
    email         VARCHAR(320),
    quota_bytes   BIGINT       NOT NULL,
    used_bytes    BIGINT       NOT NULL DEFAULT 0,
    created_at    BIGINT       NOT NULL,
    last_login_at BIGINT
);
CREATE INDEX users_username ON users (username);

CREATE TABLE ssh_keys (
    id           VARCHAR(64)  PRIMARY KEY,
    user_sub     VARCHAR(64)  NOT NULL REFERENCES users (sub) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    key_type     VARCHAR(64)  NOT NULL,
    public_key   TEXT         NOT NULL,
    fingerprint  VARCHAR(128) NOT NULL,
    created_at   BIGINT       NOT NULL,
    last_used_at BIGINT,
    CONSTRAINT ssh_keys_unique UNIQUE (user_sub, fingerprint)
);

CREATE TABLE folder (
    id         VARCHAR(64)  PRIMARY KEY,
    type       VARCHAR(16)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    name_lower VARCHAR(100) NOT NULL,
    owner_sub  VARCHAR(64)  NOT NULL,
    created_at BIGINT       NOT NULL,
    CONSTRAINT folder_name_unique UNIQUE (name_lower)
);
CREATE INDEX folder_owner ON folder (owner_sub);

CREATE TABLE folder_member (
    folder_id  VARCHAR(64) NOT NULL REFERENCES folder (id) ON DELETE CASCADE,
    user_sub   VARCHAR(64) NOT NULL,
    permission VARCHAR(8)  NOT NULL,
    PRIMARY KEY (folder_id, user_sub)
);
CREATE INDEX folder_member_user ON folder_member (user_sub);

CREATE TABLE forward_destination (
    id              VARCHAR(64)  PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    description     VARCHAR(1000),
    host            VARCHAR(255) NOT NULL,
    port            INT          NOT NULL,
    remote_dir      VARCHAR(1000) NOT NULL,
    username        VARCHAR(255) NOT NULL,
    server_auth     VARCHAR(16)  NOT NULL,
    host_key_sha256 VARCHAR(128),
    host_ca_pubkey  TEXT,
    overwrite       BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled         BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by      VARCHAR(64)  NOT NULL,
    created_at      BIGINT       NOT NULL,
    updated_at      BIGINT       NOT NULL
);

CREATE TABLE forward_client_key (
    id              VARCHAR(64) PRIMARY KEY,
    destination_id  VARCHAR(64) NOT NULL REFERENCES forward_destination (id) ON DELETE CASCADE,
    public_key      TEXT        NOT NULL,
    private_key_enc BYTEA       NOT NULL,
    user_cert       TEXT,
    state           VARCHAR(16) NOT NULL,
    created_at      BIGINT      NOT NULL
);
CREATE INDEX forward_client_key_dest ON forward_client_key (destination_id);

CREATE TABLE destination_assignment (
    destination_id VARCHAR(64) NOT NULL REFERENCES forward_destination (id) ON DELETE CASCADE,
    user_sub       VARCHAR(64) NOT NULL,
    PRIMARY KEY (destination_id, user_sub)
);
CREATE INDEX destination_assignment_user ON destination_assignment (user_sub);

CREATE TABLE folder_forward (
    folder_id      VARCHAR(64) PRIMARY KEY REFERENCES folder (id) ON DELETE CASCADE,
    destination_id VARCHAR(64) NOT NULL REFERENCES forward_destination (id),
    keep_days      INT         NOT NULL DEFAULT 0
);

CREATE TABLE upload_session (
    id                VARCHAR(64)   PRIMARY KEY,
    user_sub          VARCHAR(64)   NOT NULL,
    virtual_path      VARCHAR(2000) NOT NULL,
    storage_key       VARCHAR(1100) NOT NULL,
    folder_id         VARCHAR(64),
    went_to           VARCHAR(16)   NOT NULL,
    file_name         VARCHAR(300)  NOT NULL,
    size              BIGINT        NOT NULL,
    chunk_size        BIGINT        NOT NULL,
    chunk_count       INT           NOT NULL,
    sha256            VARCHAR(64),
    state             VARCHAR(16)   NOT NULL,
    backend_upload_id VARCHAR(1024),
    overwrite         BOOLEAN       NOT NULL DEFAULT FALSE,
    start_ip          VARCHAR(64),
    last_modified     BIGINT,
    error             VARCHAR(1000),
    created_at        BIGINT        NOT NULL,
    updated_at        BIGINT        NOT NULL,
    expires_at        BIGINT        NOT NULL
);
CREATE INDEX upload_session_user ON upload_session (user_sub);
CREATE INDEX upload_session_state ON upload_session (state, expires_at);

CREATE TABLE upload_chunk (
    session_id  VARCHAR(64)  NOT NULL REFERENCES upload_session (id) ON DELETE CASCADE,
    idx         INT          NOT NULL,
    size        BIGINT       NOT NULL,
    sha256      VARCHAR(64),
    etag        VARCHAR(256),
    received_at BIGINT       NOT NULL,
    PRIMARY KEY (session_id, idx)
);

CREATE TABLE upload_record (
    id             VARCHAR(64)   PRIMARY KEY,
    user_sub       VARCHAR(64)   NOT NULL,
    file_name      VARCHAR(300)  NOT NULL,
    size           BIGINT        NOT NULL,
    went_to        VARCHAR(16)   NOT NULL,
    folder_id      VARCHAR(64),
    path_at_upload VARCHAR(2000) NOT NULL,
    path_now       VARCHAR(2000),
    storage_key    VARCHAR(1100),
    status         VARCHAR(16)   NOT NULL,
    via            VARCHAR(8)    NOT NULL,
    client_ip      VARCHAR(64),
    start_ip       VARCHAR(64),
    completed_at   BIGINT        NOT NULL
);
CREATE INDEX upload_record_user_time ON upload_record (user_sub, completed_at);
CREATE INDEX upload_record_user_ip ON upload_record (user_sub, client_ip);
CREATE INDEX upload_record_key ON upload_record (storage_key);

CREATE TABLE forward_job (
    id               VARCHAR(64)   PRIMARY KEY,
    folder_id        VARCHAR(64)   NOT NULL,
    destination_id   VARCHAR(64)   NOT NULL,
    upload_record_id VARCHAR(64),
    storage_key      VARCHAR(1100) NOT NULL,
    file_name        VARCHAR(300)  NOT NULL,
    size             BIGINT        NOT NULL,
    state            VARCHAR(16)   NOT NULL,
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    next_attempt_at  BIGINT        NOT NULL,
    first_failed_at  BIGINT,
    created_at       BIGINT        NOT NULL,
    updated_at       BIGINT        NOT NULL,
    sent_at          BIGINT
);
CREATE INDEX forward_job_due ON forward_job (state, next_attempt_at);
CREATE INDEX forward_job_folder ON forward_job (folder_id, created_at);
