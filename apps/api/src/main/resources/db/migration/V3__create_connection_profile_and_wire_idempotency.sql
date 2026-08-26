-- P2-130: non-secret connection "profile" metadata (name/endpoint
-- view/TLS summary/connector options/object policy/limits) that the OpenAPI
-- contract's ConnectionDetail/ConnectionSummary/CreateConnectionRequest/
-- UpdateConnectionRequest schemas require but that the P2-100
-- DatabaseConnection aggregate has no columns for (a documented, deliberate
-- P2-100 gap - see its own evidence in docs/IMPLEMENTATION_TASKS.md). This
-- migration is purely additive: a new table only, no ALTER to any V1/V2
-- object, following the same forward-only policy documented at the top of
-- V2. It never stores secret material, TLS PEM content, or any other value
-- SecretStore would own - only the boolean "was a custom CA / client
-- certificate supplied" flags TlsSummary itself requires.
--
-- idempotency_keys (V2) already carried every column the *first* attempt at
-- wiring this table (checked-then-inserted-after-completion) needed, and V2
-- already granted the runtime role SELECT/INSERT/UPDATE/DELETE on it, so no
-- further grant is required for the columns added below.
--
-- Race-safety fix (post-review): the original wiring reserved a key only
-- *after* its mutation fully committed, so two concurrent requests carrying
-- the same fresh key both saw "no existing row" and both executed the full
-- mutation - for createConnection specifically this duplicated real secret
-- material, not just metadata. completed_at turns reservation into a
-- reserve-before-mutate, complete-or-rollback-after protocol: a row is
-- inserted (relying on this table's existing primary key for atomic
-- create-only semantics - a concurrent duplicate INSERT fails with a unique
-- violation) with completed_at NULL *before* the mutation runs, then either
-- updated to a non-null completed_at (success) or deleted (a definite,
-- no-side-effect failure) once the mutation's outcome is known. A NULL
-- completed_at is therefore a live signal that another request is currently
-- mid-flight for this exact key, distinct from "this key was already used to
-- a terminal outcome" (IdempotencyGuard's own Javadoc covers the full
-- protocol). expires_at is additive-only for now (no pruning job reads it
-- yet - see docs/IMPLEMENTATION_TASKS.md P2-130 evidence for this residual,
-- deliberately out-of-scope-for-this-task gap): a fixed 7-day retention
-- window gives a future cleanup job a column to key off without this table
-- growing completely unbounded in the meantime.
ALTER TABLE idempotency_keys
    ADD COLUMN completed_at TIMESTAMPTZ,
    ADD COLUMN expires_at   TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '7 days'),
    ADD CONSTRAINT idempotency_keys_completed_at_after_created
        CHECK (completed_at IS NULL OR completed_at >= created_at);

CREATE TABLE connection_profiles (
    workspace_id                    VARCHAR(64) NOT NULL,
    connection_id                   VARCHAR(64) NOT NULL,
    name                            TEXT NOT NULL CHECK (name <> '' AND length(name) <= 120),
    endpoint_host                   TEXT NOT NULL CHECK (endpoint_host <> ''),
    endpoint_port                   INTEGER NOT NULL CHECK (endpoint_port BETWEEN 1 AND 65535),
    endpoint_database               TEXT NOT NULL CHECK (endpoint_database <> ''),
    tls_mode                        TEXT NOT NULL CHECK (tls_mode IN ('VERIFY_FULL', 'DISABLED')),
    custom_ca_configured            BOOLEAN NOT NULL DEFAULT FALSE,
    client_certificate_configured   BOOLEAN NOT NULL DEFAULT FALSE,
    tcp_keep_alive                  BOOLEAN NOT NULL DEFAULT TRUE,
    allowed_schemas                 TEXT[] NOT NULL,
    allowed_tables                  TEXT[] NOT NULL DEFAULT '{}',
    statement_timeout_ms            INTEGER NOT NULL CHECK (statement_timeout_ms BETWEEN 100 AND 60000),
    max_rows                        INTEGER NOT NULL CHECK (max_rows BETWEEN 1 AND 1000),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (workspace_id, connection_id),
    FOREIGN KEY (workspace_id, connection_id) REFERENCES connections (workspace_id, connection_id),
    -- Mirrors ConnectionObjectPolicy's own invariant (schemas non-empty).
    CHECK (cardinality(allowed_schemas) > 0)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON connection_profiles TO ${runtimeAppUsername};
