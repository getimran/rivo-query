-- P2-060: identity, membership, connection, secret-reference, idempotency,
-- durable-operation, and connection lifecycle audit schema.
--
-- Rollback/forward-fix policy (Flyway migrations are never edited or deleted
-- once applied, per apps/api/AGENTS.md "Migrations are forward-compatible and
-- tested from an empty control database"):
--   * All changes in this file are additive (new tables only); it introduces
--     no destructive change to V1.
--   * If any table/constraint here must be corrected, ship a new forward
--     migration (V3+) that ALTERs/backfills/recreates the affected object.
--     Never hand-edit this file after it has shipped.
--   * If a future rollback is required in production, restore by forward
--     migration (e.g. a V-next that drops/renames the offending object with
--     an explicit data-preservation plan), not by reverting this file, since
--     Flyway checksums this file's applied content.

-- ---------------------------------------------------------------------------
-- Tenants
-- ---------------------------------------------------------------------------
CREATE TABLE workspaces (
    workspace_id  VARCHAR(64) PRIMARY KEY
                  CHECK (workspace_id ~ '^wsp_[0-9A-HJKMNP-TV-Z]{26}$'),
    display_name  TEXT NOT NULL CHECK (display_name <> ''),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Identities (OIDC issuer/subject resolved to a stable opaque actor id)
-- ---------------------------------------------------------------------------
CREATE TABLE actors (
    actor_id    VARCHAR(64) PRIMARY KEY
                CHECK (actor_id ~ '^act_[0-9A-HJKMNP-TV-Z]{26}$'),
    issuer      TEXT NOT NULL CHECK (issuer <> ''),
    subject     TEXT NOT NULL CHECK (subject <> ''),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (issuer, subject)
);

-- ---------------------------------------------------------------------------
-- Memberships (workspace + actor + role/status)
-- ---------------------------------------------------------------------------
CREATE TABLE memberships (
    workspace_id   VARCHAR(64) NOT NULL REFERENCES workspaces (workspace_id),
    membership_id  VARCHAR(64) NOT NULL
                   CHECK (membership_id ~ '^mem_[0-9A-HJKMNP-TV-Z]{26}$'),
    actor_id       VARCHAR(64) NOT NULL REFERENCES actors (actor_id),
    role           TEXT NOT NULL CHECK (role IN ('ADMINISTRATOR', 'MEMBER')),
    status         TEXT NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, membership_id),
    UNIQUE (membership_id),
    UNIQUE (workspace_id, actor_id)
);

-- ---------------------------------------------------------------------------
-- Connections (tenant-scoped aggregate root; optimistic version column)
-- ---------------------------------------------------------------------------
CREATE TABLE connections (
    workspace_id            VARCHAR(64) NOT NULL REFERENCES workspaces (workspace_id),
    connection_id           VARCHAR(64) NOT NULL
                             CHECK (connection_id ~ '^con_[0-9A-HJKMNP-TV-Z]{26}$'),
    kind                    TEXT NOT NULL CHECK (kind IN ('POSTGRESQL', 'MYSQL')),
    state                   TEXT NOT NULL CHECK (state IN (
                                 'PENDING_SECRET', 'PENDING_VALIDATION', 'ACTIVE',
                                 'FAILED_VALIDATION', 'DISABLED', 'DELETING', 'DELETED')),
    version                 BIGINT NOT NULL CHECK (version >= 1),
    latest_secret_version   BIGINT NOT NULL CHECK (latest_secret_version >= 0),
    -- Points into connection_secret_references; FK added below once that
    -- table exists (avoids a forward reference within a single CREATE TABLE).
    current_secret_version  BIGINT
                             CHECK (current_secret_version IS NULL OR current_secret_version >= 1),
    secret_retire_at        TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id),
    UNIQUE (connection_id),
    -- Mirrors DatabaseConnection's constructor invariants: a bound secret
    -- version never exceeds the latest reserved version.
    CHECK (current_secret_version IS NULL OR current_secret_version <= latest_secret_version),
    -- Mirrors DatabaseConnection's per-state secret-presence invariants.
    CHECK (
        (state IN ('PENDING_SECRET', 'DELETING', 'DELETED') AND current_secret_version IS NULL)
        OR (state IN ('PENDING_VALIDATION', 'ACTIVE', 'DISABLED') AND current_secret_version IS NOT NULL)
        OR (state = 'FAILED_VALIDATION')
    ),
    -- Mirrors DatabaseConnection: secret_retire_at is set if and only if the
    -- connection is currently FAILED_VALIDATION.
    CHECK ((state = 'FAILED_VALIDATION') = (secret_retire_at IS NOT NULL))
);

-- ---------------------------------------------------------------------------
-- Secret references (ciphertext-external; reference/version only, never
-- material). One immutable row per secret version ever bound to a
-- connection; secret_reference.version is a per-connection monotonic
-- counter shared across purposes, so (workspace_id, connection_id, version)
-- alone is a valid natural key.
-- ---------------------------------------------------------------------------
CREATE TABLE connection_secret_references (
    workspace_id         VARCHAR(64) NOT NULL,
    connection_id        VARCHAR(64) NOT NULL,
    version              BIGINT NOT NULL CHECK (version >= 1),
    purpose              TEXT NOT NULL CHECK (purpose IN ('TARGET_DATABASE_CONNECTION')),
    provider_namespace   TEXT NOT NULL CHECK (provider_namespace ~ '^[a-z][a-z0-9-]{0,31}$'),
    opaque_id            TEXT NOT NULL CHECK (opaque_id ~ '^[A-Za-z0-9_-]{16,128}$'),
    binding_fingerprint  TEXT NOT NULL CHECK (binding_fingerprint ~ '^[A-Za-z0-9_-]{16,128}$'),
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id, version),
    FOREIGN KEY (workspace_id, connection_id) REFERENCES connections (workspace_id, connection_id)
);

ALTER TABLE connections
    ADD CONSTRAINT fk_connections_current_secret_reference
    FOREIGN KEY (workspace_id, connection_id, current_secret_version)
    REFERENCES connection_secret_references (workspace_id, connection_id, version);

-- ---------------------------------------------------------------------------
-- Connection permissions: a shared optimistic "collection version" per
-- connection's grant set, plus the individual non-MANAGE grants.
-- ---------------------------------------------------------------------------
CREATE TABLE connection_permission_collections (
    workspace_id       VARCHAR(64) NOT NULL,
    connection_id      VARCHAR(64) NOT NULL,
    collection_version BIGINT NOT NULL CHECK (collection_version >= 1),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id),
    FOREIGN KEY (workspace_id, connection_id) REFERENCES connections (workspace_id, connection_id)
);

CREATE TABLE connection_permission_grants (
    workspace_id   VARCHAR(64) NOT NULL,
    connection_id  VARCHAR(64) NOT NULL,
    membership_id  VARCHAR(64) NOT NULL,
    permissions    TEXT[] NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id, membership_id),
    FOREIGN KEY (workspace_id, connection_id) REFERENCES connections (workspace_id, connection_id),
    FOREIGN KEY (workspace_id, membership_id) REFERENCES memberships (workspace_id, membership_id),
    -- array_length() returns NULL (not 0) for a zero-length array, which a
    -- CHECK constraint treats as satisfied; cardinality() returns 0 instead.
    CHECK (cardinality(permissions) > 0),
    CHECK (permissions <@ ARRAY['VIEW_SCHEMA', 'QUERY', 'EXPORT']::TEXT[])
);

-- ---------------------------------------------------------------------------
-- Durable operations (saga/idempotent-mutation bookkeeping for P2-100)
-- ---------------------------------------------------------------------------
CREATE TABLE durable_operations (
    workspace_id   VARCHAR(64) NOT NULL REFERENCES workspaces (workspace_id),
    operation_id   VARCHAR(64) NOT NULL
                   CHECK (operation_id ~ '^op_[0-9A-HJKMNP-TV-Z]{26}$'),
    actor_id       VARCHAR(64) NOT NULL REFERENCES actors (actor_id),
    connection_id  VARCHAR(64),
    action         TEXT NOT NULL CHECK (action IN (
                        'CONNECTION_CREATE', 'CONNECTION_UPDATE', 'CONNECTION_DELETE',
                        'CONNECTION_TEST', 'CONNECTION_ROTATE_SECRET')),
    state          TEXT NOT NULL CHECK (state IN (
                        'PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'INDETERMINATE')),
    cancelled      BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, operation_id),
    UNIQUE (operation_id),
    FOREIGN KEY (workspace_id, connection_id) REFERENCES connections (workspace_id, connection_id)
);

-- ---------------------------------------------------------------------------
-- Idempotency keys (mutation replay/reuse detection for P2-100/P2-130)
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    workspace_id        VARCHAR(64) NOT NULL REFERENCES workspaces (workspace_id),
    actor_id            VARCHAR(64) NOT NULL REFERENCES actors (actor_id),
    idempotency_key     VARCHAR(128) NOT NULL
                        CHECK (idempotency_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$'),
    action              TEXT NOT NULL CHECK (action <> ''),
    request_fingerprint VARCHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    operation_id        VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, actor_id, idempotency_key),
    FOREIGN KEY (workspace_id, operation_id) REFERENCES durable_operations (workspace_id, operation_id)
);

CREATE INDEX idx_idempotency_keys_operation
    ON idempotency_keys (workspace_id, operation_id)
    WHERE operation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Connection lifecycle audit: metadata-only, append-only. Never a secret,
-- endpoint credential, or raw driver error column.
--
-- connection_id is deliberately NOT a foreign key into connections (ADR-0011:
-- "Audit every execution attempt", including pre-action authorization-denial
-- and probing attempts against a connection_id that was never created, or
-- that belongs to a different tenant than the actor probing it). It is still
-- format-validated with the same opaque-ID CHECK pattern connections itself
-- uses, so only a well-formed id can ever be recorded.
-- ---------------------------------------------------------------------------
CREATE TABLE connection_lifecycle_audit_events (
    id             BIGSERIAL PRIMARY KEY,
    workspace_id   VARCHAR(64) NOT NULL,
    actor_id       VARCHAR(64) NOT NULL REFERENCES actors (actor_id),
    connection_id  VARCHAR(64) NOT NULL
                   CHECK (connection_id ~ '^con_[0-9A-HJKMNP-TV-Z]{26}$'),
    request_id     VARCHAR(64) NOT NULL
                   CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    action         TEXT NOT NULL CHECK (action IN (
                        'CREATE', 'UPDATE', 'TEST', 'ROTATE_SECRET', 'ENABLE',
                        'DISABLE', 'DELETE', 'GRANT_PERMISSION', 'REVOKE_PERMISSION')),
    outcome        TEXT NOT NULL CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED', 'INDETERMINATE')),
    category       TEXT NOT NULL CHECK (category IN (
                        'AUTHORIZATION', 'VALIDATION', 'DESTINATION_POLICY', 'SECRET_PROVIDER',
                        'CONNECTOR', 'CONCURRENCY', 'INTERNAL')),
    occurred_at    TIMESTAMPTZ NOT NULL,
    recorded_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_connection_audit_events_by_connection
    ON connection_lifecycle_audit_events (workspace_id, connection_id, occurred_at DESC);

CREATE INDEX idx_connection_audit_events_by_workspace
    ON connection_lifecycle_audit_events (workspace_id, occurred_at DESC);

CREATE FUNCTION reject_connection_lifecycle_audit_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'connection_lifecycle_audit_events is append-only; % is not permitted', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER connection_lifecycle_audit_events_no_update
    BEFORE UPDATE ON connection_lifecycle_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_connection_lifecycle_audit_event_mutation();

CREATE TRIGGER connection_lifecycle_audit_events_no_delete
    BEFORE DELETE ON connection_lifecycle_audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_connection_lifecycle_audit_event_mutation();

-- BEFORE UPDATE/DELETE triggers above are row-level and never fire for
-- TRUNCATE; a statement-level trigger is required to reject that path too.
CREATE TRIGGER connection_lifecycle_audit_events_no_truncate
    BEFORE TRUNCATE ON connection_lifecycle_audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION reject_connection_lifecycle_audit_event_mutation();

-- ---------------------------------------------------------------------------
-- Runtime application role: distinct from the role this migration itself
-- runs as (the schema/migration owner). Every table above is owned by
-- whichever role Flyway connects as; an owning role always has ALTER TABLE
-- rights (including ALTER TABLE ... DISABLE TRIGGER) that cannot be revoked,
-- so the append-only guarantee on connection_lifecycle_audit_events is only
-- real for a role that is never that owner. The placeholder substituted
-- below (see spring.flyway.placeholders in application.yaml, named for the
-- application's own runtime datasource username) resolves to the same
-- username the application's own JDBC pool (spring.datasource) connects
-- with, so the role granted privileges here is exactly the role the running
-- application authenticates as.
--
-- This migration deliberately never creates the role or sets its password.
-- Flyway performs plain-text placeholder substitution before sending SQL to
-- Postgres, so embedding a password placeholder in a CREATE/ALTER ROLE
-- statement here would (a) put the plaintext password into the SQL text
-- Postgres receives and may log (log_statement=all, pgaudit,
-- pg_stat_statements), and (b) let a password containing a single quote
-- break out of the intended string literal and execute arbitrary SQL as the
-- migration-owner role, since Flyway's substitution happens before
-- Postgres's own parser/escaping ever sees the value. Role creation with a
-- credential therefore happens out-of-band, in a path that is never
-- Flyway's or the application's own logged connection: see
-- deploy/local/postgres-initdb/ for local development, and
-- docs/OPERATIONS.md for staging/production. If that role does not already
-- exist by the time this migration runs, fail loudly rather than silently
-- creating it here (which would reintroduce the same leak).
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = '${runtimeAppUsername}') THEN
        RAISE EXCEPTION 'Runtime application role "%" does not exist. It must be provisioned '
            'out-of-band before this migration runs (see deploy/local/postgres-initdb/ for local '
            'development and docs/OPERATIONS.md for staging/production); this migration only grants '
            'privileges to a pre-existing role and never creates it or sets its password.',
            '${runtimeAppUsername}';
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO ${runtimeAppUsername};

-- platform_heartbeat is owned by V1 (already shipped; V1 must never be
-- hand-edited, per this file's own header). Prior to this runtime-role
-- split, the application queried it as the same role that owned it, so no
-- explicit grant was ever needed; ControlDatabaseReadinessProbe still
-- depends on SELECT access to it, so this forward migration grants it here.
GRANT SELECT ON platform_heartbeat TO ${runtimeAppUsername};

GRANT SELECT, INSERT, UPDATE, DELETE ON
    workspaces, actors, memberships, connections, connection_secret_references,
    connection_permission_collections, connection_permission_grants,
    durable_operations, idempotency_keys
    TO ${runtimeAppUsername};

GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO ${runtimeAppUsername};

-- Read-only visibility into Flyway's own bookkeeping table (migration
-- history metadata only; no schema/DDL right), useful for the application's
-- own diagnostics and required for tests that connect as this role.
GRANT SELECT ON flyway_schema_history TO ${runtimeAppUsername};

-- The audit table is append-only for the runtime role: SELECT/INSERT only.
-- No UPDATE/DELETE/TRUNCATE grant is ever issued to it or to PUBLIC, and
-- (per the comment above) the runtime role is never this table's owner, so
-- it has no ALTER TABLE path to disable the triggers and delete around this
-- restriction either.
REVOKE ALL ON connection_lifecycle_audit_events FROM PUBLIC;
GRANT SELECT, INSERT ON connection_lifecycle_audit_events TO ${runtimeAppUsername};
REVOKE UPDATE, DELETE, TRUNCATE ON connection_lifecycle_audit_events FROM ${runtimeAppUsername};
