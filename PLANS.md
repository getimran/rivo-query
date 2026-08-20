# RivoQuery execution plan

This is a living implementation plan. Codex must update the Progress, Surprises, Decision log, and Outcomes sections as work proceeds. Do not mark a phase complete until its acceptance checks pass.

## Objective

Deliver a production-oriented vertical slice in which an authenticated user can securely register a PostgreSQL or MySQL connection, refresh its schema metadata, ask a natural-language question, preview validated SQL, execute it under strict read-only limits, and view tabular results with an audit record.

## Progress

- [x] Product requirements baseline
- [x] Architecture and security baseline
- [x] ADR baseline
- [x] Codex and VS Code workspace baseline
- [x] Solution design and independent design review
- [x] Execution-ready task decomposition through Phase 8
- [x] Phase 1: repository and backend bootstrap
- [ ] Phase 2: connection control plane
- [ ] Phase 3: schema discovery and relevance selection
- [ ] Phase 4: Text-to-SQL orchestration and QuerySentry
- [ ] Phase 5: query execution and audit
- [ ] Phase 6: web console
- [ ] Phase 7: MCP interface
- [ ] Phase 8: hardening and release candidate

The stable task-level plan is `docs/IMPLEMENTATION_TASKS.md`. It is the
authoritative dependency, ownership, parallel-wave, evidence, and integration
register for these phases. Keep the phase summaries and outcomes here synchronized
at serialized integration checkpoints; do not have parallel agents edit this file.

Repository gate update (2026-08-20): PR #3 is merged and
the design/planning checkpoint PR #4 is merged. Phase 1 is active on
`feat/phase-1-platform-bootstrap`; its implementation and local acceptance
checks are complete within the bootstrap prompt. CP-1 must pass hosted CI and
merge before Phase 2 begins.

## Phase 1 — Repository and backend bootstrap

Create a Maven-based Java 21 Spring Boot application in `apps/api`, a React/TypeScript application in `apps/web`, and local container dependencies under `deploy/local`.

Acceptance:

- Maven Wrapper works from the repository root.
- Backend health and readiness endpoints work.
- Control-plane PostgreSQL migrations run.
- Frontend renders a shell and calls backend health through a typed client.
- Unit checks and production builds pass.
- No application secret is committed.

## Phase 2 — Connection control plane

Implement connection CRUD using opaque connection IDs, encrypted secret storage, ownership/tenant checks, PostgreSQL and MySQL JDBC connector implementations, and a connection test operation.

Acceptance:

- Credentials are encrypted before persistence and redacted from all responses/logs.
- URLs are parsed and validated; unsafe protocols and prohibited hosts are rejected by policy.
- Connection tests have strict timeouts and stable error codes.
- Cross-tenant access tests fail closed.
- PostgreSQL and MySQL integration tests use Testcontainers.

## Phase 3 — Schema discovery

Use JDBC metadata to discover schemas, tables, columns, primary/foreign keys, and selected database capabilities. Normalize this into a vendor-neutral catalog and cache versioned snapshots.

Acceptance:

- PostgreSQL and MySQL catalogs normalize to the same domain model.
- System schemas and denied objects are excluded.
- Refresh is idempotent and records a schema fingerprint.
- Relevance selection returns a bounded subset for a question.
- Raw sampled values remain disabled by default.

## Phase 4 — Generation and QuerySentry

Implement the provider-neutral model interface, structured generation contract, prompt builder, ambiguity handling, SQL parser, statement policy, object allowlist checks, and risk scoring.

Acceptance:

- Model output is parsed from a strict schema and fails closed.
- Only one SELECT/CTE query is accepted.
- DML, DDL, stacked statements, comments used for obfuscation, dangerous functions, cross-schema access, and unrecognized objects are rejected.
- Model-supplied limits never override stricter server policy.
- Prompt-injection cases from schema metadata and user questions are covered.

## Phase 5 — Execution and audit

Execute only QuerySentry-approved SQL through read-only target credentials with query timeout, row cap, cancellation, concurrency isolation, and normalized results.

Acceptance:

- Database-level and JDBC-level read-only protections are applied where supported.
- Timeout, cancellation, row cap, and payload cap are independently tested.
- Audit contains actor, tenant, connection, request ID, policy outcome, timings, SQL fingerprint, and row count—but no credentials or raw result rows.
- Client errors use stable RFC 9457 responses.

## Phase 6 — Web console

Build onboarding, connection management, schema status, question workspace, SQL preview, confirmation, results table, error states, and feedback capture.

Acceptance:

- A non-technical user can complete the core journey.
- Secrets cannot be redisplayed after submission.
- SQL is visible before execution by default.
- Large result rendering is bounded and accessible.
- UI component, integration, and end-to-end checks pass.

## Phase 7 — MCP interface

Expose a narrow MCP surface over the same application services. Tools accept connection IDs—not credentials—and preserve identical authorization and safety enforcement.

Acceptance:

- `list_connections`, `describe_schema`, `preview_query`, and `ask_database` are available.
- MCP cannot bypass REST-layer policies because both delegate to the same use cases.
- Tool results are bounded, typed, and free of internal error details.

## Phase 8 — Hardening and release candidate

Add observability, rate limits, abuse tests, dependency and container scanning, backup/restore guidance, Kubernetes manifests or Helm, SLO dashboards, runbooks, and load tests.

Acceptance:

- Security checklist and threat mitigations are verified.
- P95 latency and concurrency targets are measured under the documented workload.
- Deployment supports external secrets and TLS.
- Restore and credential-rotation procedures are exercised.
- Release candidate has no critical/high unresolved vulnerability without a documented exception.

## Surprises and discoveries

Record unexpected constraints, failed assumptions, library incompatibilities, database-dialect differences, or security findings here with dates and evidence.

- 2026-08-20: The approved design exposes phase-gated decisions and security
  prerequisites that are not implementation-ready yet: outbound destination
  enforcement before Phase 2; durable worker fencing before Phase 3; parser and
  canonicalization before Phase 4; preview replay and terminal audit semantics
  before Phase 5; and identity/export, MCP, and hosting decisions at their
  consuming phases. These are now explicit dependencies in the task register.
- 2026-08-20: Phase 1 initially lacked Java 21, Node/npm, and a Docker runtime.
  The local toolchain was then installed and exercised with OpenJDK 21.0.12.1,
  Maven Wrapper 3.9.15, Node 26.7.0/npm 11.19.0, Docker Engine 29.5.2 under
  Colima, and standalone Compose 5.5.0. Backend/Testcontainers, frontend, and
  live Compose checks now run locally rather than remaining environment gaps.
- 2026-08-20: Colima did not establish a published loopback port when the
  PostgreSQL service was attached only to a Compose `internal` network, despite
  recording the requested host binding. Using the project-scoped default bridge
  while retaining `127.0.0.1` binding restored host-run API access without
  exposing PostgreSQL on non-loopback interfaces.
- 2026-08-20: OpenAPI Generator 7.24.0 validates and generates the required
  OpenAPI 3.1 contract, but still labels its 3.1 code path beta and emits a
  generic default-localhost warning even with the contract's relative `/`
  server. Generated Phase 1 interfaces contain no base URL, and the frontend
  client uses the same-origin relative contract; keep this generator behavior
  under dependency review.
- 2026-08-20: Independent review caught three release-blocking gaps before the
  checkpoint: unsupported response media types mapped to 500 instead of a safe
  406, the REST controller depended on a concrete platform probe, and npm's
  lifecycle-script approvals were not fail-closed. The fixes added a contracted
  406, an application input port plus ArchUnit guard, and strict exact-version
  lifecycle-script enforcement with regressions. Both re-reviews are SAFE.

## Decision log

Record implementation-level decisions that do not yet justify an ADR.

- 2026-08-20: Project-scoped Codex agents cover product research, solution/API design, design review, planning, backend/frontend implementation and review, QA, DevOps, and post-implementation API/functional sanity testing; the primary agent coordinates handoffs and resolves conflicts.
- 2026-08-20: All changes must use a focused feature branch, pass applicable local execution and relevant/full checks with meaningful unit coverage and independent review/QA, and reach `main` only through a pull request. `sanity_runner` is not a pre-push gate; it runs only against a runnable implemented product.
- 2026-08-20: Keep pull requests to `main` serial: merge every earlier pull request before creating another. Store durable project context and verification evidence in version-controlled repository files rather than relying on chat or PR-only context.
- 2026-08-20: Agents must use tokens efficiently through targeted reads, bounded delegation, concise handoffs, reuse of established evidence, and narrow iterative checks, without reducing implementation quality, security analysis, test coverage, full verification, or truthful reporting.
- 2026-08-20: Product name fixed as RivoQuery; internal SQL policy component named QuerySentry.
- 2026-08-20: Initial target databases are PostgreSQL and MySQL.
- 2026-08-20: LLM provider is deliberately abstracted; no provider is selected as a product dependency.
- 2026-08-20: Phase work follows the stable IDs and exact dependencies in
  `docs/IMPLEMENTATION_TASKS.md`. Dependency-independent, non-overlapping tasks
  may run in named parallel groups, but contracts, migrations, shared interfaces,
  generated clients, `PLANS.md`, reviews/QA, and one PR per checkpoint are
  serialized integration points.
- 2026-08-20: Phase 1 is limited to `prompts/01-bootstrap-platform.md`, including
  only the minimal heartbeat/version migration. Future-state product tables and
  connection, catalog, model, policy, and execution behavior begin in their
  consuming phases.
- 2026-08-20: The two Phase 1 health probes are the sole unauthenticated REST
  exception. They expose bounded status only; liveness has no dependency call,
  readiness uses an application input port and the control heartbeat, and all
  other product REST operations remain subject to the planned JWT baseline.

## Outcomes and retrospective

Complete after each phase: what shipped, checks run, known limitations, and the safest next step.

### Phase 1 — platform bootstrap (2026-08-20)

Shipped the Java 21/Spring Boot modular foundation, OpenAPI-generated health
contract, minimal Flyway heartbeat migration, bounded PostgreSQL readiness,
safe RFC 9457/request-ID/logging/metrics baseline, strict React/TypeScript shell
with generated health client, loopback-only digest-pinned local PostgreSQL, and
least-privilege SHA-pinned CI.

Final local evidence is recorded in `docs/verification/phase-1.md`. The primary
clean run passed OpenAPI validation/generation, 26 backend unit/contract/
architecture tests, five digest-pinned PostgreSQL integrations, strict npm
installation and lifecycle-policy checks, three policy plus 14 frontend tests,
contract drift, lint, production build, vulnerability audit with zero findings,
Compose validation, diff/secret hygiene, and live API/web/database outage and
recovery QA. Independent backend and frontend re-reviews are SAFE; QA is PASS.

Known limitations are the OpenAPI Generator 3.1 beta warning, lack of a Phase 1
real-browser automation harness, and hosted GitHub Actions/Gitleaks evidence
remaining external until CP-1 is opened. Rollback is to stop/remove the API and
web deployment and stop Compose while preserving its volume. Flyway V1 is a
small additive migration; do not attempt an ad-hoc destructive down migration.
The safest next step is to open CP-1, require hosted checks, merge it, synchronize
`main`, and only then start Phase 2.
