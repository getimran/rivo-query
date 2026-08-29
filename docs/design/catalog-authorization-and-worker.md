# Catalog authorization and worker design

Status: Approved design baseline
Date: 2026-08-29
Phase: 3
ADR: ADR-0007

## 1. Objective

Freeze the security-significant ordering and identifier rules for schema
discovery, persistence, and selection, plus the durable worker semantics for
background refresh operations. This document gates implementation in
`apps/api/src/main/java/com/knowimran/rivoquery/catalog/`,
`platform/connector/postgresql/`, `platform/connector/mysql/`,
`platform/operation/`, and `platform/persistence/`.

## 2. Authorization order

Authorization is performed before any catalog data is read from the target
database, persisted in the control plane, or returned to a caller. The exact
order is:

1. Authenticate the actor and resolve the exact `X-RivoQuery-Workspace-Id`.
2. Verify the actor has an active membership in the selected workspace.
3. Load the `DatabaseConnection` by workspace-scoped ID.
4. Verify the actor has at least one effective permission on that connection
   (`VIEW_SCHEMA` for metadata operations).
5. Verify the connection state is `ACTIVE`; `PENDING_VALIDATION`,
   `FAILED_VALIDATION`, `DISABLED`, and `DELETING` states reject schema
   operations before any connector or secret use.
6. Resolve the secret reference and obtain a just-in-time lease for the
   target database credentials.
7. Apply destination policy to the connection's vetted addresses.
8. Open the connector with read-only semantics and bounded timeouts.

No step after step 4 may reveal whether a connection exists if step 4 fails.
Cross-tenant, cross-connection, and unknown IDs are indistinguishable from
`CONNECTION_NOT_FOUND`.

## 3. Raw and canonical qualified identifiers

### 3.1 Raw identifier

A raw identifier is the exact identifier returned by JDBC metadata, preserved
as a vendor-specific string. It is used only for:
- connector-level catalog discovery
- display in administrative UI
- diagnostic audit metadata

Raw identifiers are never used for object resolution, allowlist matching,
or cross-dialect comparison.

### 3.2 Canonical identifier

A canonical identifier is a normalized, dialect-independent representation
used for all allowlist checks, selection scoring, AST resolution, and
persistence. Rules:

- Fold to lowercase using Unicode case folding, not `toLowerCase(Locale.ROOT)`.
- Trim leading and trailing whitespace.
- Replace sequences of internal whitespace with a single ASCII space.
- Reject empty identifiers after normalization.
- Store as `TEXT NOT NULL` in the control database; compare with
  `COLLATE "C"` or an explicit binary-safe equality check to avoid
  locale-dependent behavior.
- Reject identifiers containing NUL bytes, control characters, or
  non-printable Unicode before normalization.

### 3.3 Qualified-name parsing

A qualified name is a schema-object pair. Rules:

- Split on the first `.` only; additional dots are part of the object name.
- Empty schema or empty object after split is invalid.
- Quoted identifiers are preserved as-is during discovery, then normalized
  using the same canonical rules. The catalog never reconstructs quoting.
- Aliases are resolved at discovery time and stored as canonical object names.

### 3.4 Authorization checks on identifiers

- System schemas are denied by name before any allowlist check:
  - PostgreSQL: `pg_catalog`, `information_schema`, `pg_toast`
  - MySQL: `information_schema`, `mysql`, `performance_schema`, `sys`
- The allowlist is a set of canonical schema names and optional canonical
  object names. Matching is exact equality against canonical forms.
- An empty object allowlist means all non-system objects in allowed schemas
  are permitted. An empty schema allowlist is invalid and rejected at
  interface boundary.
- Discovery never reads table/column values; it reads only metadata.

## 4. Catalog domain model

### 4.1 Immutable value types

- `CatalogObjectId`: opaque connection-scoped identifier.
- `RawQualifiedName`: schema + object raw strings.
- `CanonicalQualifiedName`: normalized schema + object.
- `ObjectType`: `TABLE`, `VIEW`, `MATERIALIZED_VIEW`, `FUNCTION`, `PROCEDURE`, `SEQUENCE`.
- `ColumnClassification`: `NONE`, `PII`, `SECRET`, `DERIVED`. Classification is
  optional and disabled by default; when enabled, only explicitly marked columns
  carry classification.
- `SchemaFingerprint`: deterministic SHA-256 of the normalized snapshot.

### 4.2 Snapshot

A `SchemaSnapshot` is an immutable record of all permitted objects for a
connection at a point in time. It contains:

- `snapshotId`: opaque version identifier.
- `connectionId`: owning connection.
- `workspaceId`: tenant boundary.
- `fingerprint`: deterministic hash.
- `discoveredAt`: UTC timestamp.
- `objectCount`: total permitted objects.
- `objects`: list of `CatalogObject` entries.

A `CatalogObject` contains:

- `rawName`: raw qualified name.
- `canonicalName`: canonical qualified name.
- `type`: object type enum.
- `description`: sanitized comment text, treated as untrusted data.
- `columns`: optional list of `CatalogColumn`.
- `isSystem`: boolean, true for denied system objects.
- `isDeprecated`: boolean from vendor metadata.

A `CatalogColumn` contains:

- `name`: canonical column name.
- `type`: vendor-neutral type family (`STRING`, `NUMERIC`, `TIMESTAMP`, `BOOLEAN`, `BINARY`, `JSON`, `OTHER`).
- `nullable`: boolean.
- `description`: sanitized comment text.
- `classification`: optional `ColumnClassification`.

### 4.3 Persistence rules

- Snapshots are append-only. Updates create a new snapshot and atomically
  advance the connection's current snapshot pointer.
- The current snapshot pointer is a single row in `connection_profiles` or a
  dedicated `current_schema_snapshot` table with a unique constraint on
  `(workspace_id, connection_id)`.
- Old snapshots remain queryable for audit and historical selection but are
  not used for live authorization.
- Snapshot creation is idempotent: repeating the same discovery with the same
  source state produces a snapshot with the same fingerprint.

## 5. Worker semantics

### 5.1 Worker claim

A background refresh is executed by a worker claim. Claims are stored in a
`schema_refresh_operations` table with:

- `operation_id`: opaque durable operation ID.
- `workspace_id`, `connection_id`: tenant and resource boundary.
- `state`: `PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`.
- `claimed_by`: worker instance identifier.
- `claimed_at`: timestamp.
- `lease_expires_at`: timestamp.
- `fencing_token`: monotonically increasing version.
- `attempt`: integer, starts at 1.
- `max_attempts`: integer, default 3.
- `retry_backoff_ms`: integer, default 5000.
- `created_at`, `updated_at`: timestamps.

### 5.2 Lease renewal and fencing

- A worker must renew its lease before `lease_expires_at`. The default lease
  is 30 seconds. Renewal extends the lease by another 30 seconds.
- A worker that cannot renew its lease must stop work and release the claim.
- The fencing token is assigned at claim time and never changes during the
  operation. Any subsequent claim for the same operation must have a strictly
  greater fencing token.
- A stale worker (fencing token mismatch) must stop immediately and release
  resources. Its partial work is discarded by the next claim.

### 5.3 Retry and backoff

- On transient failure (connector timeout, destination denial, secret provider
  unavailable), the worker releases the claim and the operation state returns
  to `PENDING`.
- A new claim may be made after `retry_backoff_ms`. Backoff doubles on each
  retry, capped at 60 seconds.
- After `max_attempts` failures, the operation enters `FAILED` permanently.
  An administrator may manually trigger a new operation.
- Pre-action audit is written before each attempt. Terminal audit is written
  after each terminal state transition.

### 5.4 Idempotency

- A refresh operation is identified by `(workspace_id, connection_id)`.
- Only one pending/running operation per connection exists at a time.
- A duplicate request with the same idempotency key returns the existing
  operation resource.
- A request with a different idempotency key while an operation is pending
  returns `409 OPERATION_IN_PROGRESS`.

### 5.5 Crash recovery

- On application startup, any operation in `RUNNING` state whose
  `lease_expires_at` is in the past is returned to `PENDING`.
- A background reaper runs every 60 seconds and reclaims expired leases.
- If a worker crashes mid-snapshot, the partial snapshot is never installed
  because snapshot installation is atomic: the new snapshot is written first,
  then the pointer is advanced in a single transaction.

### 5.6 Cleanup and shutdown

- On graceful shutdown, the worker releases its claim and sets the operation
  state to `PENDING` so another worker may resume.
- On forced termination, the lease reaper eventually reclaims the claim.
- Old snapshots are retained indefinitely by default. A background cleanup
  job may delete snapshots older than a configurable retention period
  (default 90 days), but only after confirming they are not the current
  snapshot and are not referenced by any active operation.

## 6. Selection rules

### 6.1 Bounds

- Maximum tables per question: 20.
- Maximum columns per table: 50.
- Maximum total columns: 200.
- Maximum relationship hops: 2.
- Maximum token budget for selected context: 4000 tokens.

### 6.2 Scoring

Lexical scoring is deterministic and uses only canonical names and
descriptions. The scorer produces a stable ranking for the same input.

1. Exact match on canonical qualified name: score 100.
2. Token overlap between question tokens and canonical name tokens: score
   proportional to overlap ratio, max 80.
3. Token overlap between question tokens and description tokens: score
   proportional to overlap ratio, max 60.
4. Approved join hints and business concepts: fixed additive bonuses defined
   in configuration, max 40.
5. Ties are broken by lexicographic order of canonical qualified name.

### 6.3 Expansion

- After scoring, the top-N tables are selected.
- Foreign-key relationships expand the selection by one hop in both directions.
- Expanded objects that exceed bounds are dropped in score order.
- System objects are never selected, even if referenced by a foreign key.

## 7. Security considerations

- Schema metadata is tenant-confidential. Snapshots are scoped by
  `workspace_id` and `connection_id`. Cross-tenant access is impossible
  because repository methods require tenant context and database constraints
  enforce composite foreign keys.
- Descriptions and comments are untrusted data. They are sanitized before
  being passed to any model or rendered in UI.
- Raw identifiers are never used for authorization. Only canonical identifiers
  participate in allowlist and selection logic.
- Worker operations are authenticated through the same actor/tenant resolution
  as REST operations. No worker bypasses authorization.
- Audit records include actor, tenant, connection, request ID, operation ID,
  fencing token, timings, outcome, and object count, but never raw result rows
  or credentials.

## 8. Open questions

- Should column classification be enabled by default in Phase 3 or deferred?
- What is the maximum acceptable refresh interval for local development?
- Should the selector support approved business-concept synonyms in Phase 3
  or only in a later phase?

These questions must be resolved before P3-100 implementation.
