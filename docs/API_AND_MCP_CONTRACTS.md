# API and MCP contracts

This document defines behavior and resource shapes. The implementation will make OpenAPI 3.1 the machine-readable REST source of truth and generate the TypeScript client from it.

## 1. Common rules

- Base path: `/api/v1`
- Authentication: bearer JWT for REST; equivalent authenticated identity for MCP.
  The only Phase 1 exception is unauthenticated `GET /api/v1/health/live` and
  `GET /api/v1/health/ready`, which expose bounded status only for platform
  probes and never return dependency details.
- Every response includes or echoes `X-Request-Id`.
- Every workspace-scoped REST operation requires exactly one canonical
  `X-RivoQuery-Workspace-Id`. Authentication happens first; the selector is
  resolved through current server-side membership before any workspace-owned
  resource is loaded. There is no implicit single-workspace fallback.
- `GET /workspaces` is unscoped and rejects `X-RivoQuery-Workspace-Id` when the
  header is present. It derives results only from the authenticated actor's
  active server-side memberships.
- Every mutation requires `Idempotency-Key`. Repeating the same canonical
  request with the same key returns the original completed representation or
  current durable operation. Reusing a key for a different request returns
  `409 IDEMPOTENCY_KEY_REUSED` without applying another side effect.
- Versioned mutations require the exact strong `ETag` returned by the relevant
  resource or collection in `If-Match`. Missing preconditions return `428`; a
  stale precondition returns `412`.
- Secrets, CA certificates, client certificates, and client private keys are
  write-only. They never appear in a success response, problem, link, operation,
  log, trace, metric, or audit payload.
- OpenAPI Generator 7.24 does not reliably translate OpenAPI 3.1
  `dependentRequired` or `minProperties` into generated Java Bean Validation.
  The API implementation must therefore apply custom validation immediately at
  the HTTP interface boundary: client certificate and private key are both
  present or both absent, and a connection `PATCH` contains at least one field.
  Validation must finish before a service, secret store, resolver, or connector
  is called. The OpenAPI keywords remain authoritative for other consumers.
- Error responses use `application/problem+json`.
- Pagination is cursor-based.
- Timestamps are UTC ISO 8601.
- Workspace and connection responses use `Cache-Control: private, no-store`.
- Opaque IDs are immutable, case-sensitive, non-enumerable server identifiers.

## 2. Workspace and authorization boundary

### Discover the caller's workspaces

`GET /api/v1/workspaces`

The response is a bounded, cursor-paginated list containing only opaque
workspace ID, display name, and effective `ADMINISTRATOR` or `MEMBER` role for
the caller's current active memberships. An authenticated caller with no active
membership receives an empty list. The response sets `selectionRequired: true`;
the browser may preselect a sole result, but it must still send the explicit
workspace selector on every scoped request.

Supplying any `X-RivoQuery-Workspace-Id` header to this operation returns
`400 WORKSPACE_SELECTOR_NOT_ALLOWED`. Missing, empty, padded, malformed,
duplicated, comma-combined, or non-canonical selectors on scoped operations
return `400 WORKSPACE_SELECTOR_INVALID`. Unknown workspaces, non-membership,
inactive workspaces, and inactive memberships all return the same
`403 WORKSPACE_ACCESS_DENIED`.

### List selectable members for permission administration

`GET /api/v1/workspace-memberships`

This scoped, administrator-only operation lists bounded safe summaries of
active memberships in the selected workspace. It does not provision, invite,
reactivate, or change a membership. Non-administrators receive
`403 ADMINISTRATOR_REQUIRED` before any connection is loaded.

## 3. Connection resources

### Authorization and visibility

- Active administrators have effective `MANAGE` for every connection in the
  selected workspace. `MANAGE` is the sole administrative permission name.
- `MANAGE` is not grantable to members in the MVP. Administrators may grant a
  member any non-empty subset of `VIEW_SCHEMA`, `QUERY`, and `EXPORT`.
- Administrators may create, inspect full configuration, update, enable,
  disable, delete, test, rotate secrets, and administer permissions.
- Members can list only connections for which they have at least one effective
  permission. List items contain safe summary metadata and never endpoint,
  username, secret, TLS-material, or connector-option detail.
- Summary links are permission-filtered affordances, not authorization evidence.
  Admin-only `self`, test, rotation, lifecycle, and permission links are omitted
  from member summaries; because the MVP has no member detail operation, a
  member summary may omit `links` entirely. Every linked operation still
  performs its normal server-side authorization check.
- An unknown, cross-workspace, deleted, or otherwise inaccessible connection ID
  returns the same `404 CONNECTION_NOT_FOUND`. Authorization occurs before
  secret resolution, DNS resolution, destination validation, or connector use.

### Create connection

`POST /api/v1/connections`

```json
{
  "name": "Reporting PostgreSQL",
  "kind": "POSTGRESQL",
  "endpoint": {
    "host": "db.example.internal",
    "port": 5432,
    "database": "reporting"
  },
  "secret": {
    "username": "rivoquery_reader",
    "password": "write-only"
  },
  "tls": {
    "mode": "VERIFY_FULL",
    "caCertificatePem": "write-only when configured"
  },
  "connectorOptions": {
    "tcpKeepAlive": true
  },
  "objectPolicy": {
    "allowedSchemas": ["sales", "reference"],
    "allowedTables": []
  },
  "limits": {
    "statementTimeoutMs": 15000,
    "maxRows": 200
  }
}
```

The request requires `Idempotency-Key`. Its structured endpoint rejects URI
syntax, user information, embedded ports, paths, query strings, alternative
numeric IP syntax, and IPv6 zone identifiers. `connectorOptions` is a closed
allowlist; Phase 2 permits only the declared typed fields. Raw JDBC URLs, driver
classes, socket factories, authentication plugins, files, and arbitrary driver
property maps are not contract fields and unknown properties are rejected.

A completed creation returns `201` with `Location`, strong `ETag`, and a
`ConnectionDetail`. A still-running durable creation returns `202` with an
operation resource. Validation may leave the connection in
`FAILED_VALIDATION`; its retained credential is usable only for an authorized
bounded retest until its fixed retirement deadline and is never returned.

Connection states are `PENDING_VALIDATION`, `ACTIVE`, `FAILED_VALIDATION`,
`DISABLED`, and `DELETING`. A detail contains safe endpoint/configuration
metadata for administrators, booleans indicating configured secret/TLS
material, optimistic version, timestamps, and links. It never contains a
password, username, certificate, private key, secret reference, provider
identifier, ciphertext, credential-bearing URL, or raw driver property.

### Read and update connections

- `GET /api/v1/connections` returns a bounded cursor page of authorized safe
  summaries. Administrators see all selected-workspace connections; members see
  only explicitly permitted connections.
- `GET /api/v1/connections/{connectionId}` is administrator-only and returns a
  full safe configuration with `ETag`.
- `PATCH /api/v1/connections/{connectionId}` is administrator-only and requires
  `Idempotency-Key` plus `If-Match`. It updates name, structured endpoint, TLS
  mode, closed connector options, object policy, or limits, but never accepts
  credential/TLS material. An empty object is rejected at the interface boundary
  even when the generated Java DTO lacks a `minProperties` Bean Validation
  constraint. Destination-affecting changes move the connection to
  `PENDING_VALIDATION` and drain the old version's pool.
- `POST /api/v1/connections/{connectionId}/enable` and `/disable` are
  administrator-only commands requiring `Idempotency-Key` and `If-Match`.
  Disable immediately stops new grants and pool borrows. Enable does not bypass
  validation or restore expired credentials.
- `DELETE /api/v1/connections/{connectionId}` requires both headers, tombstones
  the connection, stops new access, and returns a `202` durable cleanup
  operation. Foreign and already-inaccessible IDs remain non-enumerating.

### Test and secret rotation

- `POST /api/v1/connections/{connectionId}/test` requires `Idempotency-Key` and
  `If-Match`, creates a bounded non-pooled test, and returns `202` with a durable
  operation. It never returns a raw target or driver error.
- `POST /api/v1/connections/{connectionId}/rotate-secret` accepts a fresh
  write-only username/password and optional CA/client material. It requires both
  headers and returns `202`. Client certificate and private key must appear
  together; custom interface-boundary validation enforces the pair because the
  generated Java DTO does not enforce `dependentRequired`. The staged version is
  tested before an optimistic switch; failure preserves the old current version
  and schedules exact staged cleanup. A successful switch prevents old-version
  grants and drains its pool.

Connection tests and rotations surface only stable safe operation failure codes
such as `DESTINATION_NOT_ALLOWED`, `DESTINATION_RESOLUTION_FAILED`,
`SECRET_PROVIDER_UNAVAILABLE`, `CONNECTION_TEST_FAILED`, and
`CONNECTION_STATE_CONFLICT`. They never expose resolved addresses, destination
policy rules, driver messages, secret-provider identifiers, or credentials.

### Connection permissions

- `GET /api/v1/connections/{connectionId}/permissions` is administrator-only
  and accepts bounded `cursor` and `limit` parameters. It returns at most 100
  grants and an optional opaque `nextCursor`. The strong collection `ETag`
  identifies the complete permission-set version rather than an individual
  page, and collection versions advance independently of pagination.
- `PUT /api/v1/connections/{connectionId}/permissions/{membershipId}` replaces
  an active member's exact grant with a non-empty set of `VIEW_SCHEMA`, `QUERY`,
  and `EXPORT`. It requires `Idempotency-Key` and the collection `If-Match`.
- `DELETE /api/v1/connections/{connectionId}/permissions/{membershipId}`
  revokes the grant with the same preconditions. `MANAGE` is never accepted in
  either grant representation.

Unknown, inactive, administrator, or cross-workspace target membership IDs
return the same `404 MEMBERSHIP_NOT_FOUND`. Permission collection versions
advance independently of the destination/secret-bound connection version.

### Durable operations

`GET /api/v1/operations/{operationId}` returns a workspace-scoped operation in
`PENDING`, `RUNNING`, `SUCCEEDED`, `FAILED`, or `INDETERMINATE`. Operations are
bound to actor, selected workspace, action, idempotency key, request fingerprint,
and optional connection. They expose only safe failure code/detail and links.
Unknown, foreign, or unauthorized operation IDs return `404 OPERATION_NOT_FOUND`.

`INDETERMINATE` means an external effect may have occurred and reconciliation
will continue under the same durable operation. Clients must poll the operation
and must not create a new idempotency key to repeat the effect blindly.

## 4. Query endpoint

`POST /api/v1/queries`

```json
{
  "connectionId": "con_01K...",
  "question": "Show monthly completed order revenue for 2026",
  "mode": "PREVIEW",
  "requestedMaxRows": 100
}
```

### Ready preview

```json
{
  "requestId": "qry_01K...",
  "status": "READY",
  "connectionId": "con_01K...",
  "sql": "SELECT ...",
  "explanation": "Groups completed order totals by calendar month.",
  "assumptions": ["Revenue means order total after discount."],
  "referencedObjects": ["sales.orders"],
  "warnings": [],
  "policy": {
    "version": "querysentry-1",
    "maxRows": 100,
    "statementTimeoutMs": 15000
  }
}
```

### Needs clarification

```json
{
  "requestId": "qry_01K...",
  "status": "NEEDS_CLARIFICATION",
  "clarificationQuestion": "Should revenue use gross_total or net_total?",
  "choices": ["Gross revenue", "Net revenue"]
}
```

### Execute approved preview

`POST /api/v1/queries/{requestId}/execute`

The server rechecks authorization, connection version, catalog fingerprint, policy version, and deadline. If relevant state changed, it returns a conflict requiring a new preview.

### Direct execute

Authorized service callers may send `mode: EXECUTE`. The same generation, validation, and execution controls apply; connection policy may disable direct execution.

### Result shape

```json
{
  "requestId": "qry_01K...",
  "status": "SUCCEEDED",
  "sql": "SELECT ...",
  "columns": [
    {"name": "month", "type": "DATE", "nullable": false},
    {"name": "revenue", "type": "DECIMAL", "nullable": true}
  ],
  "rows": [
    ["2026-01-01", "125000.50"]
  ],
  "rowCount": 1,
  "truncated": false,
  "warnings": [],
  "timingsMs": {
    "schemaSelection": 18,
    "generation": 842,
    "validation": 7,
    "execution": 41,
    "total": 932
  }
}
```

## 5. Feedback and audit

- `POST /queries/{requestId}/feedback`
- `GET /audit-events` for authorized administrators

Feedback accepts `CORRECT`, `INCORRECT`, or `PARTIALLY_CORRECT`, optional reason categories, and a bounded comment. It must not accept pasted raw result datasets.

## 6. Problem details

```json
{
  "type": "https://rivoquery.dev/problems/query-rejected",
  "title": "Query rejected",
  "status": 422,
  "detail": "The generated query referenced an object outside the allowed catalog.",
  "instance": "/api/v1/queries/qry_01K...",
  "code": "QUERY_OBJECT_NOT_ALLOWED",
  "requestId": "req_01K..."
}
```

Stable categories include authentication, authorization, validation, destination policy, connection, schema, model, query policy, target timeout, result limit, rate limit, and internal failure. Details remain safe and do not include raw driver messages.

## 7. MCP surface

MCP is an adapter over the same application use cases.

### `list_connections`

Returns authorized connection IDs, names, kinds, states, and schema freshness. No endpoint or credential detail unless a specific permission permits safe metadata.

### `describe_schema`

Input:

```json
{"connectionId":"con_01K...","search":"orders revenue","maxObjects":10}
```

Returns a bounded normalized schema summary.

### `preview_query`

Input:

```json
{
  "connectionId":"con_01K...",
  "question":"Top five products by net revenue last month",
  "requestedMaxRows":5
}
```

Returns `READY`, `NEEDS_CLARIFICATION`, or `UNANSWERABLE`, including approved SQL only for `READY`.

### `ask_database`

Executes only when the MCP identity and connection policy allow direct execution. Returns a bounded typed table plus SQL, explanation, warnings, and truncation state.

## 8. Contract safety

- MCP and REST callers cannot submit raw SQL to the natural-language endpoints.
- A future expert raw-SQL endpoint is out of scope and requires its own ADR and permission model.
- Tool output is capped by rows, bytes, column count, and text-cell length.
- Tool descriptions state that answers originate from a connected database and may require clarification.
- Connection IDs are opaque and non-enumerable.
