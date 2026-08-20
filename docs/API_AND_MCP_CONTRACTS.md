# API and MCP contracts

This document defines behavior and resource shapes. The implementation will make OpenAPI 3.1 the machine-readable REST source of truth and generate the TypeScript client from it.

## 1. Common rules

- Base path: `/api/v1`
- Authentication: bearer JWT for REST; equivalent authenticated identity for MCP.
  The only Phase 1 exception is unauthenticated `GET /api/v1/health/live` and
  `GET /api/v1/health/ready`, which expose bounded status only for platform
  probes and never return dependency details.
- Every response includes or echoes `X-Request-Id`.
- Commands accept `Idempotency-Key` where specified.
- Secrets are write-only.
- Error responses use `application/problem+json`.
- Pagination is cursor-based.
- Timestamps are UTC ISO 8601.

## 2. Connection resources

### Create connection

`POST /api/v1/connections`

```json
{
  "name": "Reporting PostgreSQL",
  "kind": "POSTGRESQL",
  "host": "db.example.internal",
  "port": 5432,
  "database": "reporting",
  "username": "rivoquery_reader",
  "password": "write-only",
  "tlsMode": "VERIFY_FULL",
  "allowedSchemas": ["sales", "reference"],
  "allowedTables": [],
  "limits": {
    "statementTimeoutMs": 15000,
    "maxRows": 200
  }
}
```

Response contains metadata, state, redacted username if policy requires, timestamps, and links. It never contains password, secret material, or a reconstructable credential-bearing URL.

### Other endpoints

- `GET /connections`
- `GET /connections/{connectionId}`
- `PATCH /connections/{connectionId}`
- `DELETE /connections/{connectionId}`
- `POST /connections/{connectionId}/test`
- `POST /connections/{connectionId}/rotate-secret`
- `POST /connections/{connectionId}/schema-refresh`
- `GET /connections/{connectionId}/schema`

Secret rotation accepts only new secret material and invalidates/evicts old pools after in-flight work drains.

## 3. Query endpoint

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

## 4. Feedback and audit

- `POST /queries/{requestId}/feedback`
- `GET /audit-events` for authorized administrators

Feedback accepts `CORRECT`, `INCORRECT`, or `PARTIALLY_CORRECT`, optional reason categories, and a bounded comment. It must not accept pasted raw result datasets.

## 5. Problem details

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

## 6. MCP surface

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

## 7. Contract safety

- MCP and REST callers cannot submit raw SQL to the natural-language endpoints.
- A future expert raw-SQL endpoint is out of scope and requires its own ADR and permission model.
- Tool output is capped by rows, bytes, column count, and text-cell length.
- Tool descriptions state that answers originate from a connected database and may require clarification.
- Connection IDs are opaque and non-enumerable.
