-- A forward folder sends each file to one or more destinations: one forward_job per file and destination.

CREATE TABLE folder_destination (
    folder_id      VARCHAR(64) NOT NULL REFERENCES folder (id) ON DELETE CASCADE,
    destination_id VARCHAR(64) NOT NULL REFERENCES forward_destination (id),
    added_at       BIGINT      NOT NULL,
    added_by       VARCHAR(64),
    PRIMARY KEY (folder_id, destination_id)
);
CREATE INDEX folder_destination_destination ON folder_destination (destination_id);

INSERT INTO folder_destination (folder_id, destination_id, added_at, added_by)
    SELECT ff.folder_id, ff.destination_id, f.created_at, f.owner_sub
    FROM folder_forward ff JOIN folder f ON f.id = ff.folder_id;

ALTER TABLE folder_forward DROP COLUMN destination_id;

ALTER TABLE forward_job ADD COLUMN cancelled_by VARCHAR(64);
ALTER TABLE forward_job ADD COLUMN cancel_reason VARCHAR(500);
CREATE UNIQUE INDEX forward_job_record_destination ON forward_job (upload_record_id, destination_id);
CREATE INDEX forward_job_record ON forward_job (upload_record_id);
