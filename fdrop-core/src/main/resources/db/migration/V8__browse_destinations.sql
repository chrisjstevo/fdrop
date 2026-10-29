-- Destination tabs in forward folders: listing a destination's remote directory and retrieving its files into fdrop.

ALTER TABLE forward_destination ADD COLUMN browsable BOOLEAN DEFAULT FALSE NOT NULL;

-- One copy of a remote file in fdrop. Rows outlive their folder only until the next sweep, which deletes the copy.
CREATE TABLE remote_retrieval (
    id               VARCHAR(64)   PRIMARY KEY,
    folder_id        VARCHAR(64)   NOT NULL,
    destination_id   VARCHAR(64)   NOT NULL,
    remote_path      VARCHAR(4000) NOT NULL,
    remote_size      BIGINT        NOT NULL,
    remote_mtime     BIGINT        NOT NULL,
    state            VARCHAR(16)   NOT NULL,
    bytes_done       BIGINT        DEFAULT 0 NOT NULL,
    storage_key      VARCHAR(1024),
    own_copy         BOOLEAN       DEFAULT FALSE NOT NULL,
    charged_sub      VARCHAR(64),
    error            VARCHAR(1000),
    requested_by     VARCHAR(64),
    created_at       BIGINT        NOT NULL,
    updated_at       BIGINT        NOT NULL,
    ready_at         BIGINT,
    last_download_at BIGINT,
    expires_at       BIGINT
);
CREATE INDEX remote_retrieval_file ON remote_retrieval (folder_id, destination_id, remote_path);
CREATE INDEX remote_retrieval_expires ON remote_retrieval (expires_at);
