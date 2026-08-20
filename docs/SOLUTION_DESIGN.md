# RivoQuery solution design

Status: Reviewed; approved with required changes integrated
Date: 2026-08-20
Scope: MVP through production hardening

This document refines the accepted architecture and ADRs into an implementation design. Accepted ADRs remain authoritative. Any material conflict must be resolved through a superseding ADR rather than silently changing this design.

## 1. System shape

RivoQuery is a modular-monolith Java 21/Spring Boot API with a separate React/TypeScript SPA. REST and MCP adapters call the same application use cases. The API integrates with control-plane PostgreSQL, a `SecretStore`, a provider-neutral `SqlGenerationModel`, and PostgreSQL/MySQL target connectors.

The model proposes SQL but never authorizes it. QuerySentry is the only component that can create an `ApprovedQuery`, and target execution accepts only that capability. Credentials never reach the model. Results are response-only and are not logged or persisted by default.

A durable embedded operation worker handles schema refresh and cleanup operations from control-plane records. Query execution remains synchronous and bounded; asynchronous query execution requires a future ADR.

## 2. Backend modules

Use the following package structure below `apps/api/src/main/java/com/knowimran/rivoquery`:

```text
application/    cross-module input use-case ports and safe application errors
identity/       domain, application, and authorization/persistence ports
connection/     connection aggregate, lifecycle use cases, secret/destination ports
catalog/        normalized schema, snapshots, refresh and selection
generation/     model port, prompt construction and structured proposal
querypolicy/    QuerySentry, parser port, ApprovedQuery and policy outcomes
execution/      preview execution orchestration, limits and normalized results
audit/          execution metadata, audit events and feedback
interfaceapi/   generated REST interfaces, controllers and problem mapping
interfacemcp/   MCP transport, identity and tool DTO mapping
platform/       persistence, JDBC connectors, secrets, model adapter, security,
                telemetry and durable operation runner
```

Domain and application packages must not import Spring MVC, MCP, JDBC-driver, model-provider, cloud-secret, or persistence-record types. REST and MCP depend only on input use-case interfaces. Connectors depend on connector contracts, not generation or transport layers.

Key ports include:

- `SecretStore`: create, resolve through a bounded lease, stage rotation, and revoke.
- `DestinationPolicy`: turn structured endpoint data into a short-lived `ValidatedDestination` capability.
- `DatabaseConnector`: test, discover, execute an `ApprovedQuery`, and report capabilities.
- `SqlGenerationModel`: accept a RivoQuery-owned bounded request and return a validated structured response.
- `QuerySentry`: evaluate a proposal against an authorized catalog, dialect, and policy.
- `TargetQueryExecutor`: execute only an `ApprovedQuery` under actor, connection, and server-owned limits.

`ApprovedQuery` is immutable and constructible only by a QuerySentry-owned private factory/capability, not by arbitrary classes in `querypolicy`. Architecture tests prohibit every other production constructor call, raw SQL-string executor entry point, and forbidden module dependency. Only execution orchestration and connector execution adapters may consume an approved capability.

## 3. Control-plane persistence

Use Flyway migrations under `apps/api/src/main/resources/db/migration/`. Planned records are:

- tenants, external identities, memberships, and connection permissions;
- database connections and versioned secret references;
- immutable schema snapshots, normalized objects/relationships, and business metadata;
- durable operations and idempotency records;
- query requests and short-lived preview artifacts;
- query executions, append-only audit events, audit outbox, and feedback;
- prompt and query-policy versions.

Every tenant-owned record includes `tenant_id`. Composite tenant/resource foreign keys reinforce isolation. Repository methods require `TenantId`; unrestricted `findById` methods are forbidden outside narrowly reviewed platform administration.

Future-state state machines (not Phase 1 schema scope):

```text
Connection: PENDING_SECRET -> PENDING_VALIDATION -> ACTIVE | FAILED_VALIDATION
            ACTIVE <-> DISABLED; any live state -> DELETING -> DELETED
Operation:  PENDING -> RUNNING -> SUCCEEDED | FAILED | CANCELLED
Query:      GENERATING -> NEEDS_CLARIFICATION | UNANSWERABLE | REJECTED |
            PROVIDER_FAILED | READY -> EXECUTING | EXPIRED
Execution:  ACCEPTED -> RUNNING -> SUCCEEDED | TIMED_OUT | CANCELLED | FAILED
```

Truncation is a successful execution outcome represented by `SUCCEEDED` plus `truncated: true`, matching the public contract. Every transition uses optimistic locking and has explicit invalid-transition and concurrency tests. Deletion remains tombstoned while audit retention applies. Secret revocation and pool cleanup use durable operations. Schema snapshots are immutable; a successful refresh atomically switches the connection's current snapshot. Fingerprints use a deterministic sorted representation without volatile timestamps.

Phase 1 creates only the minimal heartbeat/version migration required by `prompts/01-bootstrap-platform.md`. The table inventory and state machines above are future-state guidance introduced incrementally by their consuming phases.

## 4. Identity and authorization

JWT validation verifies issuer, audience, signature, allowed algorithm, expiry, and not-before. External subjects map to server-side memberships. A client-provided tenant ID is only a selector and never authority.

The API needs workspace discovery/selection for users with multiple memberships. An `X-RivoQuery-Workspace-Id` selector may be used only after membership validation produces an immutable `ActorContext`.

Permissions remain `VIEW_SCHEMA`, `QUERY`, `EXPORT`, and `MANAGE`. Documentation that says `MANAGE_CONNECTION` must be aligned to `MANAGE`. Authorization occurs before resource loading, before model invocation, again before preview execution, and inside sensitive application use cases. REST and MCP use identical permission decisions.

Product must decide whether `QUERY` implicitly permits the schema/object visibility exposed by an approved preview.

## 5. Connections, secrets, destinations, and pools

Clients submit structured kind, host, port, database, username, TLS mode, allowlists, limits, and explicitly allowed connector properties. They never submit JDBC URLs, arbitrary driver properties, plugin classes, socket factories, or file paths. Passwords, CA material, and client certificates are write-only secret material.

Connection creation is a durable saga: authorize and validate, reserve an opaque resource/idempotency record, authorize the destination, store secret material, persist only its reference/version, test with a short-lived non-pooled connection, update state, and queue initial discovery. Failure recovery revokes orphaned secret material.

Every secret operation is bound to tenant, connection, purpose, and version. Resolution occurs only after current authorization. `SecretLease` is short-lived, closeable/destructible, non-serializable, and redacted; it cannot resolve another tenant's opaque reference. Compensation tests cover orphan cleanup, provider failure, rotation races, pool draining, and secret-canary absence from telemetry.

Rotation stages and tests a new secret version, atomically switches the connection reference under optimistic versioning, drains old pools, then revokes the old version. Disable/delete also drains pools and records durable cleanup.

Destination enforcement must canonicalize host/port, resolve all IPv4/IPv6 answers, reject the destination if any answer violates operator policy, reject prohibited ranges, revalidate before every new physical connection, and preserve the hostname for TLS verification. An accepted outbound-destination ADR before Phase 2 must choose and prove a PostgreSQL/MySQL mechanism for DNS TTL/rebinding, vetted-address connection, original-host TLS verification, operator-controlled private-network access, pools, and infrastructure egress. Leaving DNS pinning versus an egress proxy undecided is not implementation-ready.

Pools are small and keyed by tenant, connection ID/version, secret version, and destination-policy version. Global, tenant, and connection budgets apply before pool creation. Pools idle-evict, drain on rotation/disable/delete, and close on shutdown without leaking credentials into names, metrics, exceptions, or string representations.

## 6. Schema catalog and selection

Connectors discover only permitted JDBC metadata: schemas, tables/views, columns, keys, comments, and capabilities. Sample rows and values remain disabled.

Security-significant processing order:

1. Extract raw metadata into a transient dialect-aware canonical representation.
2. Evaluate system-schema denial and tenant/connection allowlists against both raw and canonical qualified identifiers.
3. Apply optional column classification restrictions.
4. Persist only authorized vendor-neutral normalized objects.
5. Build the relevance index.
6. Produce bounded model-context selections.

The initial selector uses deterministic lexical and approved business-term scoring, foreign-key expansion, approved join hints, fixed object/column/hop/token budgets, and stable tie-breaking. It returns an `AuthorizedCatalogSlice` bound to a snapshot fingerprint. Metadata text is explicitly delimited as untrusted data. PostgreSQL/MySQL tests cover quoted, case-sensitive, aliased, and dialect-specific identifiers so canonicalization cannot bypass authorization.

## 7. Generation and QuerySentry

`GenerationRequest` contains only the question, dialect, bounded authorized catalog slice, server feature constraints, and prompt version. It excludes credentials, endpoint details, rows, unrelated schema, and internal authorization data.

The strict response supports `READY`, `NEEDS_CLARIFICATION`, and `UNANSWERABLE`, with bounded SQL, explanation, assumptions, referenced objects, clarification fields, and informational confidence. Provider adapters validate structure and size before returning domain data. A malformed response is a provider failure; at most one formatting-only retry may occur before execution.

QuerySentry:

1. Enforces input size and encoding constraints.
2. Parses exactly one dialect-specific statement under a deadline.
3. Accepts only SELECT or non-modifying CTEs ending in SELECT.
4. Rejects modifying, administrative, transaction, session, locking, `SELECT INTO`, dynamic, external, file, network, sleep, and system-inspection behavior.
5. Resolves every AST object against the authorized snapshot and rejects cross-catalog/database access.
6. Applies complexity and estimated-output limits.
7. Applies the strictest platform, tenant, connection, and request limits.
8. Adds/reduces the row limit through AST transformation, then serializes, reparses, and revalidates under a proven containment deadline.
9. Creates an immutable `ApprovedQuery` containing dialect, canonical SQL/fingerprint, referenced objects, risks, enforced limits, and policy version.

## 8. Preview and execution

A preview artifact binds actor, tenant, connection ID/version, schema fingerprint, canonical SQL/fingerprint, prompt/policy versions, enforced limits, creation/expiry, and execution state. Multi-replica deployments store this state in the control plane. Because SQL literals may be sensitive, canonical SQL is a short-lived encrypted artifact with externally supplied key material and aggressive expiry cleanup.

Execution atomically claims a preview with a transactional compare-and-set from `READY` to `EXECUTING`, recording an execution-attempt ID and idempotency identity. Concurrent callers receive the same completed replay response when safe, an in-progress response, or a stable conflict; they never execute the target query twice. Expired artifacts cannot be claimed. Ambiguous target failures are not automatically retried. Direct execution creates and atomically claims the same internal bound capability and follows the same audit lifecycle.

Execution accepts only an opaque preview/request ID. It reauthorizes the caller, reloads current connection/catalog/policy state, verifies all bindings, and reruns QuerySentry to create a fresh `ApprovedQuery`. Changed state returns a conflict and requires a new preview; clients never resubmit SQL.

Execution sequence:

1. Reauthorize and validate preview state.
2. Compute effective server-owned limits.
3. Commit an execution record and `EXECUTION_ACCEPTED` audit event.
4. Resolve the secret just in time and revalidate destination when opening a physical connection.
5. Acquire bounded global/tenant/connection capacity.
6. Apply JDBC and dialect-specific read-only transaction/session controls.
7. Apply database/JDBC timeout and maximum rows.
8. Stream at most `maxRows + 1`, enforcing payload, column, and cell caps.
9. Cancel on deadline or disconnect and close all resources deterministically.
10. Persist the terminal metadata-only outcome transactionally with its audit-outbox record before releasing rows to the caller.

Rows never enter logs, traces, audits, or control persistence. Type normalization is deterministic and JSON-safe. Driver failures map to stable RFC 9457 codes without raw messages. An audit outbox and reconciliation detect executions lacking a terminal event. Failure of the committed pre-execution audit prevents database execution; failure of terminal audit persistence withholds rows and returns a safe failure while reconciliation and alerts preserve investigation evidence.

## 9. REST, MCP, and web

The OpenAPI 3.1 source lives at `apps/api/src/main/resources/openapi/rivoquery-v1.yaml`. It generates Spring server interfaces and the TypeScript client. Transport DTOs are mapped at the application boundary and never become domain types.

Contracts must clarify workspace selection, connection-permission management, durable schema-operation status, business metadata, cursor expiry, idempotency replay/conflict behavior, preview expiry/conflicts, audit filters, and export if product approves it.

MCP tools map directly to the same use cases as REST:

```text
list_connections -> ListConnectionsUseCase
describe_schema  -> DescribeSchemaUseCase
preview_query    -> PreviewQueryUseCase
ask_database     -> DirectExecuteUseCase
```

MCP accepts connection IDs only, has independent transport DTOs, preserves identical authorization and policy outcomes, and may apply stricter output caps. It cannot access connectors directly.

Frontend structure under `apps/web/src`:

```text
app/  api/generated/  api/  features/auth/  features/connections/
features/schema/  features/query/  features/audit/  components/
security/  test/
```

Use OIDC authorization-code flow with PKCE. Keep tokens in memory absent an approved identity decision. Secrets exist only in transient form state and are cleared on every completion/navigation/unmount path. SQL and result rows are not stored in browser persistence, service-worker caches, analytics, or session replay. The bounded result grid preserves types and provides accessible loading, empty, error, warning, and truncation states.

## 10. Deployment and observability

Initial topology uses stateless API/operation-worker replicas, separately served web assets, managed control PostgreSQL, external secret/KMS, controlled LLM/target egress, OpenTelemetry collection, and an external audit sink.

Readiness depends on control PostgreSQL and mandatory internal configuration, not target databases or temporary LLM availability. Shutdown stops new work, cancels/drains requests, releases operation leases, and closes pools.

Logs contain identifiers, outcome, and stable codes without questions, SQL, secrets, or rows. General metrics use low-cardinality operation/dialect/outcome labels and exclude tenant/connection identifiers. Traces exclude questions, SQL text/literals, schema comments, secrets, and rows.

## 11. Verification architecture

Required suites include unit, ArchUnit, OpenAPI/REST-MCP parity, tenant/SSRF/redaction security, QuerySentry dialect/adversarial corpus, shared connector contracts, PostgreSQL/MySQL Testcontainers integration, React component/accessibility, Playwright, and versioned semantic/adversarial evaluations.

Critical evidence includes empty-database migration; tenant composite-FK enforcement; complete permission matrices; secret canary absence and compensation failures; IPv4/IPv6 and DNS-rebinding behavior; catalog identifier-normalization attacks; real-engine metadata/types/read-only/timeouts; zero write/admin corpus escapes and parser nontermination containment; encrypted-preview tampering/key rotation; preview expiry/replay/double-submit/version conflicts; worker fencing; pre/terminal-audit failures; row/byte/column/cell/cancellation/pool limits; REST/MCP parity; and absence of secret/result browser persistence.

`sanity_runner` is reserved for the runnable implemented product after normal automated verification passes. It is not part of design, documentation, bootstrap, or generic pre-push review.

## 12. Decisions and design gaps

Product decisions required before the named phase:

- Phase 2: administrator-only connection creation; multi-workspace selection.
- Phase 4: reference model adapter and provider retention policy.
- Phase 5: question/SQL retention and preview schema visibility under `QUERY`.
- Phase 6: whether export is in MVP.
- Phase 7: MCP transport, authentication, and service identities.
- Phase 8: self-hosted/SaaS profile, reference IdP, and data residency.

Documentation/design gaps:

- `MANAGE_CONNECTION` conflicts with accepted permission `MANAGE`.
- Preview persistence, encryption, expiry, replay, and revalidation are not defined in current contracts.
- Audit completion after a target read needs durable outbox/reconciliation semantics.
- Multi-workspace selection is absent from REST contracts.
- DNS revalidation lacks a chosen vetted-address enforcement mechanism.
- Connection/rotation compensation and orphan-secret cleanup need explicit behavior.
- Export is both a requirement and an unresolved MVP decision.
- MCP transport/authentication is unspecified.
- Parser/canonicalization selection and acceptance evidence are unspecified.
- Semantic evaluation and malicious corpus fixtures do not yet exist.

Candidate new ADRs:

1. Preview capability persistence and execution binding.
2. Outbound destination enforcement and DNS/TLS behavior.
3. QuerySentry parser/canonicalization implementation.
4. MCP transport and service identity before Phase 7.
5. Hosting/deployment profile before Phase 8.

Control schema details, the durable schema-operation runner, and audit outbox remain implementation decisions under existing ADRs unless review identifies a material cross-system decision.

The outbound-destination ADR is a Phase 2 entry gate. The parser/canonicalization ADR is a Phase 4 entry gate. Preview capability persistence/binding and terminal audit semantics must be accepted before Phase 5. MCP and hosting ADRs remain gates for Phases 7 and 8 respectively.

Durable workers must define transactional claim, lease renewal, fencing token, retry/backoff, idempotency, crash recovery, and graceful-shutdown behavior before Phase 3 implementation.

## 13. Sequencing and parallel work

Top-level dependency chain:

```text
product decisions/design review -> Phase 1 -> Phase 2 -> Phase 3 -> Phase 4
-> Phase 5 -> Phase 6 -> Phase 7 -> Phase 8
```

Parallel work is allowed only for dependency-independent tasks with non-overlapping file ownership:

- Phase 1: backend bootstrap, frontend shell, local deployment/CI, and OpenAPI baseline.
- Phase 2: identity/tenant persistence, SecretStore, destination-policy tests, connector contracts, then connection UI after contract stabilization.
- Phase 3: catalog model/persistence, PostgreSQL discovery, MySQL discovery, selector/evaluation fixtures, then UI after API stabilization.
- Phase 4: model port/adapter, prompt builder, parser evaluation, security corpus, then preview API after domain contracts.
- Phase 5: dialect execution adapters, preview store, audit/outbox, result normalization, and integration tests after shared interfaces stabilize.
- Phase 6: onboarding, schema, query workspace, and results UI as separate generated-client feature slices.
- Phase 7: MCP transport/authentication and parity/security suites after the application use cases are stable.
- Phase 8: deployment, observability, scanning, load/abuse tests, and runbooks.

Cross-cutting contracts, migrations, shared interfaces, and `PLANS.md` are serialized integration points. Parallel tasks converge into one branch and one reviewed PR under the repository's serial-PR policy.
