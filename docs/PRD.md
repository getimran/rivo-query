# Product Requirements Document: RivoQuery

Status: Baseline for implementation  
Owner: Mohammad Imran  
Date: 2026-08-20

## 1. Product summary

RivoQuery is a generic AI database retrieval agent. An authorized user connects a supported relational database, asks a business question in natural language, and receives data retrieved by a generated, validated, bounded, read-only SQL query.

RivoQuery is both a user-facing tool and an agent capability. It will be usable through a web console, REST API, and MCP tools without duplicating business logic or safety controls.

## 2. Problem

People frequently need answers from operational databases but cannot write SQL, do not understand complex schemas, or must wait for engineering/analytics teams. Existing Text-to-SQL demos commonly fail in production because they expose credentials, generate incorrect joins, execute unsafe SQL, return unbounded data, or lack tenant-aware authorization and auditability.

RivoQuery must reduce the effort to obtain answers without pretending that model-generated SQL is inherently correct or safe.

## 3. Product principles

1. **Safe by construction:** generated SQL is untrusted and cannot execute until deterministic policy approves it.
2. **Transparent:** users can inspect generated SQL, selected data source, limits, warnings, and execution outcome.
3. **Provider neutral:** no LLM provider is part of the domain model.
4. **Database neutral:** target-specific behavior is isolated behind connector capabilities.
5. **Fail closed:** ambiguity, unsupported syntax, missing authorization, and validation uncertainty stop execution.
6. **Data minimization:** credentials never reach the model; raw results do not reach the model unless an explicit future policy permits a bounded summary workflow.
7. **Evidence over fluency:** correctness is measured by execution and expected result semantics, not by whether SQL looks plausible.

## 4. Users and jobs

### 4.1 Workspace administrator

- Create a workspace and invite users.
- Register and test database connections.
- Configure allowed schemas/tables, query limits, and model policy.
- Assign connection-level permissions.
- Review audit events and rotate credentials.

### 4.2 Business user or analyst

- Select an authorized connection.
- Ask a question without writing SQL.
- Clarify ambiguous terms.
- Preview and run safe SQL.
- View, sort, and export bounded results when permitted.
- Submit correctness feedback.

### 4.3 Developer or external AI agent

- Call a stable REST or MCP contract.
- Discover authorized connections and schema summaries.
- Preview a query before execution.
- Receive typed, bounded results and stable error codes.

### 4.4 Security or platform operator

- Enforce identity, tenant isolation, outbound-network policy, secrets management, logging, and retention.
- Observe health, latency, failure categories, and suspicious activity.
- Investigate an event without exposing raw customer result data.

## 5. Core user journey

1. Administrator creates a connection by providing database type, host, port, database, username, secret, TLS settings, and allowlist policy.
2. RivoQuery validates the destination and stores credentials through the configured secret provider.
3. RivoQuery tests connectivity with strict timeouts and read-only expectations.
4. RivoQuery discovers and normalizes permitted schema metadata.
5. User selects the connection and asks a question.
6. RivoQuery identifies relevant schema, resolves known business terms, and asks for clarification if intent is materially ambiguous.
7. The LLM returns a structured proposal containing SQL, explanation, assumptions, referenced objects, and confidence—not executable authority.
8. QuerySentry parses and validates the proposal against deterministic policy.
9. The user previews the approved SQL and executes it, or an authorized API/MCP caller requests execution under its assigned policy.
10. RivoQuery applies read-only mode, timeout, row/payload caps, cancellation, and concurrency limits.
11. Results, SQL, warnings, timings, and truncation state are returned. A metadata-only audit event is recorded.

## 6. Functional requirements

### Connection management

- **FR-CON-001:** Support PostgreSQL and MySQL at launch.
- **FR-CON-002:** Store connections under an opaque identifier scoped to one tenant/workspace.
- **FR-CON-003:** Accept hostname, port, database, username, secret, TLS mode, optional CA material, and connector-specific safe options.
- **FR-CON-004:** Never return a stored secret after creation.
- **FR-CON-005:** Encrypt secrets using a pluggable secret provider; production supports managed KMS/Vault integration.
- **FR-CON-006:** Test connections using strict connect/read timeouts and normalized errors.
- **FR-CON-007:** Restrict connection creation, configuration, testing, secret rotation, disable/re-enable, deletion, and permission administration to an active administrator of the selected workspace.
- **FR-CON-008:** Enforce allowed schema/table patterns and deny system schemas by default.
- **FR-CON-009:** Apply an outbound destination policy to mitigate SSRF and access to prohibited networks.

### Schema catalog

- **FR-SCH-001:** Discover schemas, tables/views, columns, data types, nullability, primary keys, foreign keys, and comments where permitted.
- **FR-SCH-002:** Normalize metadata into a vendor-neutral catalog while retaining dialect capabilities.
- **FR-SCH-003:** Cache versioned schema snapshots with a deterministic fingerprint.
- **FR-SCH-004:** Support manual refresh and a configurable background refresh.
- **FR-SCH-005:** Exclude non-allowlisted and system objects before model context is built.
- **FR-SCH-006:** Select a bounded relevant schema subset for each question.
- **FR-SCH-007:** Permit administrators to add business descriptions, synonyms, approved join hints, and metric definitions.
- **FR-SCH-008:** Keep sample row/value retrieval disabled by default and controlled by a separate explicit policy if added later.

### Natural-language query

- **FR-NLQ-001:** Accept a question, connection ID, execution mode, and optional maximum row request.
- **FR-NLQ-002:** Support `PREVIEW` and `EXECUTE`; the web console defaults to preview before execute.
- **FR-NLQ-003:** Produce a structured proposal: SQL, explanation, assumptions, referenced objects, ambiguity status, and model confidence.
- **FR-NLQ-004:** Ask a clarification question rather than guessing when ambiguity changes query semantics.
- **FR-NLQ-005:** Expose generated SQL and safety warnings to authorized users.
- **FR-NLQ-006:** Version prompts and record the version in audit metadata.
- **FR-NLQ-007:** Allow a provider-neutral LLM adapter and per-tenant model policy without exposing provider details in public contracts.

### QuerySentry and execution

- **FR-QRY-001:** Parse SQL into an AST using a dialect-capable parser.
- **FR-QRY-002:** Accept only one read-only SELECT/CTE statement.
- **FR-QRY-003:** Reject DML, DDL, administrative commands, stacked statements, unsupported syntax, cross-connection references, and policy-denied functions.
- **FR-QRY-004:** Verify referenced schemas/tables against the connection allowlist and discovered catalog.
- **FR-QRY-005:** Enforce server-owned row, payload, statement-time, and concurrency limits.
- **FR-QRY-006:** Execute with a read-only database identity and read-only transaction/session settings where the driver supports them.
- **FR-QRY-007:** Support cancellation and close abandoned resources.
- **FR-QRY-008:** Normalize tabular results into typed columns and JSON-safe values.
- **FR-QRY-009:** Mark results as truncated when any limit is reached.
- **FR-QRY-010:** Never retry a query automatically when execution may already have consumed significant resources; generation repair is bounded and occurs before execution.

### Identity and authorization

- **FR-IAM-001:** Authenticate web/API callers through OIDC/OAuth2 JWT.
- **FR-IAM-002:** Scope every resource and query to a tenant/workspace.
- **FR-IAM-003:** Support administrator and member roles plus connection-level `VIEW_SCHEMA`, `QUERY`, `EXPORT`, and `MANAGE` permissions.
- **FR-IAM-004:** Apply the same authorization to REST, web, and MCP paths.
- **FR-IAM-005:** Record the actor and authorization context in audit metadata.
- **FR-IAM-006:** Discover a caller's active workspace memberships from server-side state and require explicit workspace selection for every workspace-scoped request; never accept a workspace identifier from a token, path, query, cookie, or body as authority.
- **FR-IAM-007:** Treat `X-RivoQuery-Workspace-Id` as a request-local selector only after exact validation and server-side membership lookup; never fall back to another membership when the selector is missing or unauthorized.

### Results, history, and feedback

- **FR-RES-001:** Return request ID, status, SQL, explanation, assumptions, warnings, columns, rows, row count, truncation state, and timings.
- **FR-RES-002:** Do not persist raw rows in the control plane by default.
- **FR-RES-003:** Persist query metadata and SQL fingerprints according to retention policy.
- **FR-RES-004:** Capture positive/negative feedback and an optional corrected interpretation without storing sensitive result rows.
- **FR-RES-005:** Allow exports only when the caller has `EXPORT`; exports obey separate row/payload caps and auditing.

### Agent interfaces

- **FR-AGT-001:** REST and MCP must call the same application services and policy engine.
- **FR-AGT-002:** MCP tools accept connection IDs, never credentials.
- **FR-AGT-003:** Tool descriptions must state read-only behavior, limits, and possible clarification responses.
- **FR-AGT-004:** Agent results must be bounded and typed to prevent context flooding.

## 7. Non-functional requirements

### Security and privacy

- **NFR-SEC-001:** Zero plaintext credential persistence and zero credentials in logs, traces, metrics, model requests, or client responses.
- **NFR-SEC-002:** TLS for all remote database and service connections in production.
- **NFR-SEC-003:** Defense in depth: application policy plus read-only DB role plus database/session restrictions.
- **NFR-SEC-004:** Dependency, secret, container, and IaC scans in CI.
- **NFR-SEC-005:** Configurable metadata/audit retention and delete workflows.

### Reliability

- **NFR-REL-001:** 99.9% monthly availability target for the API excluding configured model-provider outages during preview.
- **NFR-REL-002:** Readiness fails when the control plane is unusable; target connection failures do not make the entire service unready.
- **NFR-REL-003:** Idempotency for connection creation where a client supplies an idempotency key and for schema refresh requests.
- **NFR-REL-004:** Graceful shutdown cancels work, drains requests, and closes connector pools.

### Performance and limits

- **NFR-PERF-001:** P95 API overhead excluding LLM and target database time: under 250 ms for normal requests.
- **NFR-PERF-002:** P95 schema lookup from cache: under 100 ms.
- **NFR-PERF-003:** Default query timeout: 15 seconds; administrator-configurable within a platform maximum.
- **NFR-PERF-004:** Default result limit: 200 rows; platform maximum: 1,000 rows for interactive execution.
- **NFR-PERF-005:** Default response payload cap: 2 MiB before transport compression.

### Observability

- **NFR-OBS-001:** Structured logs with request ID, tenant ID, actor ID, connection ID, outcome, and stable error code.
- **NFR-OBS-002:** Metrics for request count, generation latency, validation rejection category, execution latency, timeout/cancellation, result size, pool usage, and provider errors.
- **NFR-OBS-003:** Distributed traces redact questions, SQL literals, secrets, and result rows by default.
- **NFR-OBS-004:** Audit events are append-only to the application and exportable to an external sink.

### Maintainability

- **NFR-MNT-001:** Target connectors, model providers, secret stores, and schema selectors use explicit interfaces and compatibility tests.
- **NFR-MNT-002:** Public contracts are OpenAPI-first and versioned.
- **NFR-MNT-003:** PostgreSQL and MySQL behavior is covered by real-engine integration tests.
- **NFR-MNT-004:** Architecture boundaries are enforced with automated tests.

## 8. MVP scope

Included:

- Single-region deployment
- Multi-tenant control plane
- PostgreSQL and MySQL targets
- Web console and REST API
- OIDC/JWT security
- Encrypted credential persistence through a local adapter and a production secret-provider interface
- Schema discovery and manual refresh
- Provider-neutral LLM adapter with one OpenAI-compatible implementation
- Preview, deterministic validation, read-only execution, audit, and feedback

Not included:

- INSERT/UPDATE/DELETE/DDL or administrative database actions
- Autonomous recurring queries
- Cross-database joins
- Natural-language result summarization using raw rows by default
- Dashboard builder or long-term result warehouse
- Voice interface
- Oracle, SQL Server, Snowflake, BigQuery, SQLite, or NoSQL connectors
- Fine-tuning a proprietary Text-to-SQL model

## 9. Success measures

- At least 80% execution accuracy on the project evaluation set, measured by correct result semantics rather than exact SQL text.
- At least 95% rejection of the maintained malicious/unsafe query corpus, with zero accepted write statements.
- Fewer than 5% of valid questions fail because of parser/policy false positives after launch tuning.
- Median connection-to-first-answer time under five minutes for a small, documented database.
- Zero confirmed credential leakage events.
- Every executed query has a corresponding audit event.

## 10. Release gates

- All P0/P1 requirements implemented.
- PostgreSQL and MySQL integration suites pass.
- Threat-model mitigations verified.
- QuerySentry corpus passes with no write-query escape.
- Tenant isolation tests pass.
- Load test demonstrates limits without pool starvation.
- Credential rotation and control-plane restore are exercised.
- Product owner accepts the core user journey.

## 11. Open product decisions

Resolve before public beta:

- Hosting model: self-hosted only, managed SaaS, or both.
- How the first workspace and administrator are provisioned for each deployment.
- Whether result export is enabled in MVP.
- Supported identity provider for the reference deployment.
- Default LLM adapter for local development and production examples.
- Data residency and retention profiles required for first customers.

