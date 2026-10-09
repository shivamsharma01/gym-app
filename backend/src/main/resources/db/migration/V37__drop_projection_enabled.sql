-- Every reader is written from desired state. The column only selected an older member writer.

ALTER TABLE device
    DROP COLUMN projection_enabled;
