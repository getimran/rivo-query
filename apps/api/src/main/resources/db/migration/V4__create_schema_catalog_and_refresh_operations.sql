-- P3-040: immutable schema catalog and refresh operation tables.
--
-- Additive-only migration following the same forward-only policy as V2/V3.
-- These tables store only metadata; no credentials, result rows, or raw
-- driver details are persisted.

CREATE TABLE schema_snapshots (
    snapshot_id      VARCHAR(64) NOT NULL,
    workspace_id     VARCHAR(64) NOT NULL,
    connection_id    VARCHAR(64) NOT NULL,
    fingerprint      TEXT NOT NULL CHECK (length(fingerprint) = 64),
    discovered_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    object_count     INTEGER NOT NULL CHECK (object_count >= 0),
    PRIMARY KEY (snapshot_id),
    FOREIGN KEY (workspace_id, connection_id)
        REFERENCES connections (workspace_id, connection_id)
);

CREATE TABLE schema_objects (
    snapshot_id      VARCHAR(64) NOT NULL,
    object_index     INTEGER NOT NULL,
    raw_schema       TEXT NOT NULL CHECK (raw_schema <> ''),
    raw_object       TEXT NOT NULL CHECK (raw_object <> ''),
    canonical_schema TEXT NOT NULL CHECK (canonical_schema <> ''),
    canonical_object TEXT NOT NULL CHECK (canonical_object <> ''),
    object_type      TEXT NOT NULL CHECK (object_type IN (
                         'TABLE', 'VIEW', 'MATERIALIZED_VIEW',
                         'FUNCTION', 'PROCEDURE', 'SEQUENCE')),
    description      TEXT,
    system_object    BOOLEAN NOT NULL DEFAULT FALSE,
    deprecated       BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (snapshot_id, object_index),
    FOREIGN KEY (snapshot_id) REFERENCES schema_snapshots (snapshot_id)
);

CREATE TABLE schema_columns (
    snapshot_id      VARCHAR(64) NOT NULL,
    object_index     INTEGER NOT NULL,
    column_index     INTEGER NOT NULL,
    canonical_schema TEXT NOT NULL CHECK (canonical_schema <> ''),
    canonical_object TEXT NOT NULL CHECK (canonical_object <> ''),
    type_family      TEXT NOT NULL CHECK (type_family IN (
                         'STRING', 'NUMERIC', 'TIMESTAMP',
                         'BOOLEAN', 'BINARY', 'JSON', 'OTHER')),
    nullable         BOOLEAN NOT NULL DEFAULT FALSE,
    description      TEXT,
    classification   TEXT CHECK (classification IN ('NONE', 'PII', 'SECRET', 'DERIVED')),
    PRIMARY KEY (snapshot_id, object_index, column_index),
    FOREIGN KEY (snapshot_id, object_index)
        REFERENCES schema_objects (snapshot_id, object_index)
);

CREATE TABLE current_schema_snapshot (
    workspace_id     VARCHAR(64) NOT NULL,
    connection_id    VARCHAR(64) NOT NULL,
    snapshot_id      VARCHAR(64) NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id),
    FOREIGN KEY (workspace_id, connection_id)
        REFERENCES connections (workspace_id, connection_id),
    FOREIGN KEY (snapshot_id) REFERENCES schema_snapshots (snapshot_id)
);

CREATE TABLE schema_refresh_operations (
    operation_id     VARCHAR(64) NOT NULL,
    workspace_id     VARCHAR(64) NOT NULL,
    connection_id    VARCHAR(64) NOT NULL,
    state            TEXT NOT NULL CHECK (state IN (
                         'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    claimed_by       TEXT,
    claimed_at       TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ,
    fencing_token    BIGINT NOT NULL DEFAULT 0,
    attempt          INTEGER NOT NULL DEFAULT 1,
    max_attempts     INTEGER NOT NULL DEFAULT 3,
    retry_backoff_ms INTEGER NOT NULL DEFAULT 5000,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (operation_id),
    FOREIGN KEY (workspace_id, connection_id)
        REFERENCES connections (workspace_id, connection_id)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON schema_snapshots TO ${runtimeAppUsername};
GRANT SELECT, INSERT, UPDATE, DELETE ON schema_objects TO ${runtimeAppUsername};
GRANT SELECT, INSERT, UPDATE, DELETE ON schema_columns TO ${runtimeAppUsername};
GRANT SELECT, INSERT, UPDATE, DELETE ON current_schema_snapshot TO ${runtimeAppUsername};
GRANT SELECT, INSERT, UPDATE, DELETE ON schema_refresh_operations TO ${runtimeAppUsername};
