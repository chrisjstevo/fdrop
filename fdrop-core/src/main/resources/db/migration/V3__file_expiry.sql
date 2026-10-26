-- File expiry: a file with an expiry has one file_expiry row, keyed by its storage key and moved with it. Files
-- without an expiry have none. Expiry texts are stored in canonical form ('6 days').

CREATE TABLE file_expiry (
    id               VARCHAR(64)   PRIMARY KEY,
    storage_key      VARCHAR(1100) NOT NULL,
    parent_key       VARCHAR(1100) NOT NULL,
    user_sub         VARCHAR(64),
    folder_id        VARCHAR(64),
    expires_at       BIGINT        NOT NULL,
    expires_in       VARCHAR(40),
    source           VARCHAR(16)   NOT NULL,
    set_by_sub       VARCHAR(64),
    set_via          VARCHAR(8),
    set_at           BIGINT        NOT NULL,
    upload_record_id VARCHAR(64),
    claimed_by       VARCHAR(64),
    claimed_until    BIGINT,
    attempts         INT           NOT NULL DEFAULT 0,
    last_error       VARCHAR(1000),
    next_attempt_at  BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT file_expiry_key UNIQUE (storage_key)
);
CREATE INDEX file_expiry_due ON file_expiry (expires_at);
CREATE INDEX file_expiry_parent ON file_expiry (parent_key);
CREATE INDEX file_expiry_folder ON file_expiry (folder_id);

ALTER TABLE folder ADD COLUMN default_expiry VARCHAR(40);
ALTER TABLE folder ADD COLUMN max_expiry VARCHAR(40);
ALTER TABLE users ADD COLUMN home_default_expiry VARCHAR(40);
ALTER TABLE upload_session ADD COLUMN expires_in VARCHAR(40);
ALTER TABLE upload_record ADD COLUMN expires_at BIGINT;

-- An SFTP control file written before its file: applied when that user next completes the file.
CREATE TABLE sftp_pending_expiry (
    user_sub    VARCHAR(64)   NOT NULL,
    storage_key VARCHAR(1100) NOT NULL,
    expires_in  VARCHAR(40)   NOT NULL,
    created_at  BIGINT        NOT NULL,
    PRIMARY KEY (user_sub, storage_key)
);
