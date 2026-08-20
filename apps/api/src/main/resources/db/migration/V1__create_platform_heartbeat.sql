CREATE TABLE platform_heartbeat (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    schema_version VARCHAR(32) NOT NULL,
    migrated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO platform_heartbeat (singleton_id, schema_version)
VALUES (1, '1');
