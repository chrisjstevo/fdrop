-- Application accounts: non-person accounts for other systems, signing in with a client certificate, a
-- generated password or an SSH key. Their SSH keys use ssh_keys like everyone else's.

ALTER TABLE users ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'USER';

CREATE TABLE app_account (
    sub             VARCHAR(64)   PRIMARY KEY REFERENCES users (sub) ON DELETE CASCADE,
    description     VARCHAR(1000),
    responsible_sub VARCHAR(64),
    allowed_methods VARCHAR(100)  NOT NULL,
    allowed_sources VARCHAR(2000),
    password_set_at BIGINT,
    created_by      VARCHAR(64),
    created_at      BIGINT        NOT NULL,
    last_login_at   BIGINT
);

CREATE TABLE app_client_cert (
    id         VARCHAR(64)   PRIMARY KEY,
    sub        VARCHAR(64)   NOT NULL REFERENCES app_account (sub) ON DELETE CASCADE,
    match_type VARCHAR(16)   NOT NULL,
    sha256     VARCHAR(64),
    subject    VARCHAR(1000),
    san        VARCHAR(1000),
    not_after  BIGINT,
    state      VARCHAR(16)   NOT NULL,
    created_at BIGINT        NOT NULL
);
CREATE INDEX app_client_cert_sha256 ON app_client_cert (sha256);
CREATE INDEX app_client_cert_subject ON app_client_cert (subject);
CREATE INDEX app_client_cert_san ON app_client_cert (san);
CREATE INDEX app_client_cert_sub ON app_client_cert (sub);
