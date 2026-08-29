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

Repository gate update (2026-08-20): CP-1 PR #5 passed the complete local gate
and merged to `main` as `938d67b`. Local `main` was fast-forwarded to that exact
commit, no pull request remains open, and Phase 2 is active on
`feat/phase-2-connection-control-plane`. Product, destination-enforcement, and
secret-store decisions are the only active Phase 2 work until their review gate
passes. GitHub Actions remains deferred to final deployment readiness.

Phase 2 entry-gate update (2026-08-20): the product owner explicitly approved
administrator-only connection administration, administrators' effective
`MANAGE`, no member `MANAGE` in MVP, server-derived workspace discovery, and an
exact `X-RivoQuery-Workspace-Id` on every scoped request. The technical design
gate is independently approved, so P2-040 contract work is active.

Repository gate update (2026-08-25): P2-050 through P2-090 (identity/connection
domain, control-plane schema and persistence adapters, secret AES-GCM adapter,
destination/SSRF enforcement, connector SPI) passed independent
`backend-code-reviewer` review across 2-3 rounds each, with the full local
suite green (364 unit + 37 PostgreSQL Testcontainers integration tests), and
merged to `main` as `12b16d2` (PR #6, `feat/phase-2-connection-control-plane`).
Local `main` was fast-forwarded to that exact commit and the branch deleted.
No pull request remains open. This was a partial-phase checkpoint, not the
phase-level `CP-2` closeout in the task register's P2-200 row: P2-100 (CRUD/
rotation saga), P2-110/120 (live PostgreSQL/MySQL connection tests), P2-130
(REST endpoints), P2-140 (web onboarding UI), P2-150 (QA matrix), P2-180
(devops docs), and P2-190/200 remain before Phase 2 is complete. GitHub
Actions remains deferred to final deployment readiness.

Repository gate update (2026-08-27): P2-100/P2-110/P2-120 (lifecycle CRUD/rotation
saga + live PostgreSQL/MySQL connector tests) had already merged to `main`. This
update closes P2-130 (connection REST surface). P2-130 landed as PR #13
(`feat/phase-2-connection-rest-api`) after an independent
`backend-code-reviewer` pass (no blockers; 2 MED, 3 LOW, 2 NIT all resolved in
follow-up: credential-bearing fields excluded from the idempotency fingerprint,
member-with-grant GET alignment, `tryReserve` SQLState 23505 discrimination, and
two documentation notes) and a green full suite (67 PostgreSQL Testcontainers
integration tests, 0 failures). P2-130 adds the generated-OpenAPI REST surface,
`ConnectionProfile` persistence (Flyway V3), workspace/member resolution,
idempotency-key reservation with secret-free request fingerprints, and RFC 9457
problem mapping including `ConstraintViolationException` -> 400
`VALIDATION_FAILED`. A small chore PR #12 (`.gitignore` for Claude Code
machine-local settings) was merged first. `main` is fast-forwarded to
`6659a76`; no pull request remains open. Remaining before the `CP-2` closeout:
P2-140 (web onboarding UI, `frontend-engineer` in flight), P2-150 (QA matrix),
P2-160 (backend review — effectively discharged by the P2-130 review above),
P2-170 (frontend review), P2-180 (devops docs, `devops-engineer` in flight),
P2-190/200 (final verification + closeout PR). GitHub Actions remains deferred
to final deployment readiness.

P2-180 docs update (2026-08-29): documented the four runbook items for local
deployment in `docs/OPERATIONS.md`:
1. **External encryption key injection** — expanded with explicit KEK rotation
   and recovery procedures, plus reference-key rotation guidance, for the local
   AES-GCM secret store (`RIVOQUERY_SECRETS_LOCAL_KEYS`,
   `RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID`,
   `RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY`).
2. **Outbound egress / private-target policy** — added a "Local-dev egress
   behavior" subsection spelling out what is denied by default (loopback,
   link-local, cloud-metadata, multicast, documentation ranges) vs. what is
   private/RFC 1918-denied-unless-allowlisted, with an example YAML allowlist
   rule for adding private targets in local dev.
3. **Total pool budgets** — added a "Control-database connection pool (local
   Compose)" subsection documenting the live HikariCP pool: default
   `maximum-pool-size` of 10, configured `connection-timeout`/`validation-timeout`
   /`connectTimeout`, the `10 × replica_count` multiplication against
   PostgreSQL's default `max_connections=100`, and the override mechanism.
4. **Safe local synthetic configuration** — added an explicit "What is safe in
   `.env.example` / `compose.yaml`" audit confirming both files contain no
   embedded secrets (all `.env.example` passwords are synthetic
   "synthetic-local-only-...-change-before-use" placeholders; `compose.yaml`
   references every secret via `${VAR:?VAR must be set}`; no
   `RIVOQUERY_SECRETS_*` key material is present).

Validation: `docker-compose --env-file .env.example -f deploy/local/compose.yaml
config --quiet` passes; secret-pattern scan of both files finds no embedded
credentials. Application code was not modified.

Repository gate update (2026-08-29): P2-140 (web onboarding UI) is complete on
`feat/phase-2-web-onboarding`. The frontend adds workspace selection, connection
create/edit/detail/list, transient write-only secret handling, and disabled-action
affordances under `apps/web/src/app/connections/` and `apps/web/src/app/auth/`.
Independent frontend review (P2-170) found one medium accessibility gap in
confirmation dialogs: missing `aria-modal="true"` and keyboard focus trapping.
This was fixed in `ConnectionDetailPanel.tsx` and covered by a new focus-trap
test; frontend lint, 161 tests, and production build all pass. P2-180 (devops
docs) is also complete: `docs/OPERATIONS.md` now covers local external encryption
key injection/rotation, outbound egress policy, control-database pool budgets,
and safe synthetic-configuration audit. Remaining before `CP-2` closeout: P2-150
(QA matrix), P2-190/200 (final verification + closeout PR). GitHub Actions
remains deferred to final deployment readiness.

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
- 2026-08-20: Phase 2 entry review initially rejected unapproved product-policy
  claims plus incomplete audit, secret-grant, DNS, pool, and driver/TLS task
  traceability. The technical fixes now pass independent re-review. P2-040 is
  blocked solely until the product owner explicitly approves the proposed
  administrator-only and workspace-selection semantics in
  `docs/product-decisions/phase-2.md`.
- 2026-08-20: Phase 2 contract review initially found stale health-only tests,
  administrator links required in member summaries, unenforced cross-field
  schema constraints, unbounded permission listing, and unintended health cache
  headers. The final contract makes links permission-filtered, paginates grants,
  marks mandatory runtime validators, preserves Phase 1 health exactly, and has
  eight operation-wide contract tests. Independent re-review is APPROVED.
- 2026-08-23: On this machine, Testcontainers' default Docker discovery cannot
  find Colima's socket (`~/.colima/default/docker.sock` is not
  `/var/run/docker.sock`), and once `DOCKER_HOST` points at it, Testcontainers'
  Ryuk reaper still fails to start because Colima's VZ/virtiofs backend cannot
  bind-mount the socket path into a container. `./mvnw -f apps/api/pom.xml
  verify` therefore needs `DOCKER_HOST=unix:///Users/<user>/.colima/default/docker.sock`
  and `TESTCONTAINERS_RYUK_DISABLED=true` exported first; this is an
  environment quirk, not a project configuration gap, and every prior
  Testcontainers IT (PlatformMigrationIT, HealthEndpointIT) needs the same two
  variables, not just the P2-060 tests added in this pass.
- 2026-08-23: P2-060's `connections` table has a composite FK to
  `connection_secret_references` (for the current secret pointer) and that
  table has the reverse composite FK back to `connections`. Combined with a
  CHECK constraint mirroring `DatabaseConnection`'s per-state secret-presence
  invariants, this circular relationship means `ConnectionRepository.insert()`
  can only ever accept a freshly reserved connection (`PENDING_SECRET`, no
  secret) — any other state would require a `connection_secret_references` row
  that cannot exist before the `connections` row does. Every later transition,
  including attaching the first secret, goes through `compareAndSet`. Also:
  Postgres `array_length()` returns `NULL` (not `0`) for an empty array, which
  a `CHECK` treats as satisfied — the non-empty-permissions constraint on
  `connection_permission_grants` must use `cardinality()` instead.
- 2026-08-24: Independent backend review of the uncommitted P2-050/P2-060 diff
  found it not safe to push: the audit table's append-only guarantee was not
  airtight because Flyway and the running application shared one Postgres
  role, so that role — as table owner — could `ALTER TABLE ... DISABLE
  TRIGGER` and delete around the trigger regardless of grants; the audit
  table's FK to `connections` made pre-action/DENIED audit events for a
  nonexistent or cross-tenant connection unrecordable, contradicting
  ADR-0011; a secret-reference insert conflict was silently discarded via
  `ON CONFLICT ... DO NOTHING`; a missing connection row plus an attached
  secret let a raw FK-violation `DataAccessException` escape
  `compareAndSet` instead of a clean `false`; `DatabaseConnection.transition`
  could throw `IllegalArgumentException` (a 500) for transitions its own
  state machine advertised as legal; `JdbcCurrentMembershipRepository`
  omitted `status = 'ACTIVE'` from its SQL; and none of the three
  Testcontainers ITs exercised the real `@Transactional` proxy, since each
  constructed its adapters with `new` instead of pulling them from the
  Spring context. All findings were fixed in place in the still-unshipped
  V2 migration and the P2-050/P2-060 code (see the P2-060 evidence row);
  fixing the audit-trigger bypass required splitting the Flyway
  migration-owner role from the application's runtime role
  (`spring.flyway.user`/`password` vs `spring.datasource.username/password`),
  which was a legitimate, reviewer-authorized scope addition, not a
  pre-planned task.
- 2026-08-24: A second independent review of the same still-unshipped V2
  migration/runtime-role split found it incomplete on two fronts. First, the
  split was opt-in and silently void by default: `application.yaml` never
  set `spring.flyway.user`/`password`, so an operator who only configured
  `spring.datasource.*` (the reviewer's reproduced default) got Flyway
  silently falling back to that same role, reintroducing the exact
  audit-table bypass the prior round fixed, with nothing failing loudly.
  Second, the runtime role's password was embedded as a Flyway-substituted
  SQL literal (`EXECUTE format('CREATE ROLE %I LOGIN PASSWORD %L', ...)`
  inside a `DO` block), which put the plaintext password into SQL text
  Postgres receives/logs and let a password containing `'` execute
  arbitrary SQL as the migration owner. Fixed: (1)
  `MigrationOwnerSeparationValidator` (a `FlywayConfigurationCustomizer`)
  now fails application startup before any migration runs whenever
  `spring.flyway.user` is unset, blank, or equal to
  `spring.datasource.username`; `.env.example`/`deploy/local/compose.yaml`
  now wire real non-empty local-dev defaults for the split, and every
  full-context IT (`ConnectionRepositoryPersistenceIT`,
  `IdentityAndConnectionPersistenceIT`, `PlatformMigrationIT`,
  `HealthEndpointIT`, in addition to `ControlPlaneSchemaConstraintsIT`) now
  configures it correctly. (2) `V2` no longer creates the runtime role or
  sets its password; it only grants privileges to a role that must already
  exist, failing the migration loudly otherwise. Role creation moved
  out-of-band: `deploy/local/postgres-initdb/01-create-runtime-role.sh`
  (Postgres's own `docker-entrypoint-initdb.d` mechanism) for local dev, and
  a new `docs/OPERATIONS.md` section requiring external secrets/IaC for
  staging/production. Actually exercising the split for the first time
  (rather than it being masked by a shared role) surfaced one genuine
  pre-existing gap fixed in the same pass: `platform_heartbeat` (V1,
  already shipped) had never been granted to the runtime role, so the
  readiness probe would have failed under a correctly-configured split; `V2`
  now grants it `SELECT`. See the P2-060 evidence row for full detail and
  the `MigrationOwnerSeparationValidator` Javadoc for the residual,
  configuration-shaped (not privilege-shaped) limit of the fail-closed
  check.

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
- 2026-08-20: ADR-0013 selects complete all-answer destination validation,
  short-lived vetted-address capabilities, driver socket hooks preserving the
  original TLS hostname, pool revalidation/drain, operator-only private rules,
  and independent egress. Secret access uses sole grant issuer
  `SecretCapabilityAuthority`, binding-aware destructible leases, external local
  ciphertext storage, bounded failed-validation retention, and durable
  stage/switch/revoke compensation.
- 2026-08-20: The product owner approved Phase 2's administrator-only connection
  model and mandatory explicit workspace selector. The accepted requirements are
  recorded as FR-CON-007 and FR-IAM-006/007; bootstrap provisioning, reference
  IdP, delegated administration, and cross-workspace operators remain deferred.
- 2026-08-24: Reconciled `ConnectionState.canTransitionTo` with
  `DatabaseConnection`'s constructor invariants without weakening either: the
  per-state secret-presence invariants (`PENDING_VALIDATION`/`ACTIVE`/
  `DISABLED` always require a current secret) are unchanged, and
  `ConnectionState`'s abstract reachability graph is unchanged except for
  removing `PENDING_SECRET -> PENDING_VALIDATION`, which was unconditionally
  impossible (`PENDING_SECRET` can never hold a secret). For the remaining
  graph-legal-but-instance-conditional edges (e.g. `FAILED_VALIDATION ->
  DISABLED`/`PENDING_VALIDATION` once `detachFailedSecret` has run),
  `DatabaseConnection.transition` now fails closed with
  `InvalidConnectionTransitionException` before construction, mirroring the
  existing `FAILED_VALIDATION`-target guard, instead of letting the
  constructor's `IllegalArgumentException` escape as an unhandled 500.

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
being intentionally deferred to the final deployment-readiness gate. Draft PR
#5's first two hosted runs created all four jobs with zero executed steps because
GitHub reported an account payment/spending-limit restriction. That restriction
does not invalidate the completed local verification. After making the workflow
manual, the complete local gate was rerun successfully: 26 backend tests, five
PostgreSQL integrations, clean frontend install/policy/contract/lint, three
policy plus 14 UI/API tests, production build, zero-vulnerability audit, Compose
validation, and workflow/diff checks. The hosted workflow must be dispatched
during Phase 8 before deployment. Rollback is to stop or remove the API and web
deployment and stop Compose while preserving its volume. Flyway V1 is a small
additive migration; do not attempt an ad-hoc destructive down migration. The
next step is product-owner-approved CP-1 merge, `main` synchronization, and only
then Phase 2.
