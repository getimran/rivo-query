# RivoQuery implementation task register

Status: Approved planning baseline
Date: 2026-08-20
Scope: Design integration and implementation through Phase 8

This register turns the approved `docs/SOLUTION_DESIGN.md` and
`docs/SOLUTION_DESIGN_REVIEW.md` into stable, execution-ready work. The PRD,
accepted ADRs, security threat model, phase prompts, and `PLANS.md` remain
authoritative. If implementation needs to change an accepted decision, stop and
add a superseding ADR before changing behavior.

## 1. Status, ownership, and evidence rules

Statuses are `COMPLETE`, `PENDING`, `IN_PROGRESS`, or `BLOCKED`. Only design,
review, and planning work already performed is marked complete below. Every
product decision, ADR, contract, migration, implementation, test, review, QA,
DevOps, and sanity task remains pending.

The owner is the project-scoped agent expected to lead the task. The primary
agent owns integration, resolves conflicts, updates `PLANS.md`, and is
accountable for the final result. Review agents must not author the code they
review.

For every completed task, record command output or a concise evidence summary in
the phase outcome in `PLANS.md` and, where named below, in
`docs/verification/phase-<n>.md`. Do not record secrets, questions, SQL text,
prompts, model responses, or result rows in evidence.

Common verification commands:

```bash
./mvnw -f apps/api/pom.xml verify
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
```

Use narrower module or test selectors during iteration. Testcontainers checks
require a Docker-compatible runtime. Each phase must also run its applications
locally and exercise the affected paths before its integration checkpoint.
Unavailable checks must be named precisely; they are not passes.

Per-task command/evidence convention:

- Documentation, decisions, ADRs, and reviews: `git diff --check`, targeted
  `rg` traceability checks, and Markdown/link validation when configured.
- Backend, contracts, migrations, security, and connector work: the narrowest
  named Maven test class/package during iteration, followed by
  `./mvnw -f apps/api/pom.xml verify`. Real-database claims require the named
  Testcontainers tests, not mocks.
- Frontend work: the narrowest named test during iteration, followed by all
  three npm commands above. Browser claims require the named Playwright and
  accessibility/storage checks.
- Local/deployment work: render/validate the affected Compose, container,
  Kubernetes/Helm, dashboard, or CI asset with the task-appropriate tool, then
  start the documented stack and capture sanitized health/function evidence.
- QA/evaluation/sanity tasks: commit the reproducible test or evaluation
  command with the asset, record its dataset/policy/version and summarized
  output in the named verification file, and never store sensitive payloads.

Every `Paths` entry below is a repository-relative ownership root or explicit
file (a trailing `/**` includes its tests/resources beneath that root). External
Git/GitHub gates are marked `N/A (external)`.

## 2. Branch, PR, and parallel-work protocol

- `PR #3` is open as of 2026-08-20. The current
  `docs/solution-design-and-plan` branch must not be pushed and no PR may be
  opened from it until PR #3 is merged.
- Integration checkpoints are `CP-DESIGN`, then `CP-1` through `CP-8`. Each
  checkpoint uses one focused feature branch and one PR targeting `main`.
- A checkpoint PR may be created only after every earlier PR targeting `main`
  is merged. A later phase branch starts from the newly updated `main`.
- Tasks with the same `Parallel group` and satisfied dependencies may run
  concurrently only when their listed paths do not overlap. Contract sources,
  migrations, shared Java interfaces/domain capabilities, generated clients,
  and `PLANS.md` are serialized integration points.
- Parallel work is integrated in task-ID order, followed by reviewers, QA,
  full verification, documentation/evidence update, and one checkpoint PR.
- `sanity_runner` starts only after a runnable product implementation and its
  normal automated tests pass. It is never a documentation/configuration/static
  review or generic pre-push gate. The first eligible product sanity run is
  after the Phase 5 REST core journey is implemented.

## 3. Design and planning baseline

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| DES-001 | COMPLETE | `solution_architect` | None | D0 | `docs/SOLUTION_DESIGN.md` | End-to-end solution design reconciles the PRD, architecture, threat model, accepted ADRs, contracts, tests, operations, and prompts; evidence is the reviewed document. |
| DES-002 | COMPLETE | `solution_design_reviewer` | DES-001 | D1 | `docs/SOLUTION_DESIGN_REVIEW.md` | Independent review records severity, dispositions, phase gates, and validation gaps; every required correction is reflected in DES-001. |
| PLAN-001 | COMPLETE | `task_planner` | DES-002 | D2 | `docs/IMPLEMENTATION_TASKS.md`, `PLANS.md` | Stable tasks, exact dependencies, safe parallel groups, owners, paths, acceptance, evidence, and checkpoints exist through Phase 8; validate links and task IDs with `rg`. |
| GOV-000 | PENDING | primary agent | None | SERIAL-GOV | N/A (external) | Confirm PR #3 is merged. This is an external repository-state gate; evidence is the merged PR state and absence of an earlier open PR targeting `main`. |
| GOV-001 | PENDING | primary agent | GOV-000 | SERIAL-GOV | `PLANS.md` | Fetch/update `main`, confirm no earlier open PR, rebase or recreate the design branch from current `main`, and record the clean base; evidence: `git status --short --branch`, `git log`, and GitHub PR state. |
| GOV-002 | PENDING | primary agent | GOV-001, PLAN-001 | SERIAL-GOV | `docs/SOLUTION_DESIGN.md`, `docs/SOLUTION_DESIGN_REVIEW.md`, `docs/IMPLEMENTATION_TASKS.md`, `PLANS.md` | Review, commit, push, and open `CP-DESIGN`; accept only when the diff contains durable design/planning records, repository checks pass, and the PR targets `main`. No product sanity run applies. |
| GOV-003 | PENDING | primary agent | GOV-002 | SERIAL-GOV | `PLANS.md` | Confirm `CP-DESIGN` merged and local `main` matches origin before Phase 1 starts. |

## 4. Phase 1 — repository and backend bootstrap

Phase 1 is strictly limited to `prompts/01-bootstrap-platform.md`. It must not
create future product tables or implement connection management, schema
discovery, LLM calls, SQL generation, QuerySentry, or target query execution.

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P1-001 | PENDING | primary agent | GOV-003 | SERIAL-P1 | `PLANS.md` | Create Phase 1 branch, verify clean tree/tool versions/Docker availability, set only P1-001 in progress, and record scope. Evidence: Git status plus `java -version`, `node --version`, `npm --version`, `docker version`. |
| P1-010 | PENDING | `backend_engineer` | P1-001 | P1-A | `pom.xml`, `.mvn/`, `mvnw*`, `apps/api/pom.xml`, `apps/api/src/main/**` | Pin Java 21/Spring Boot 3.5.x approved patch and create bootable API with the package root and constructor injection; `./mvnw -f apps/api/pom.xml test` passes. |
| P1-020 | PENDING | `backend_engineer` | P1-010 | SERIAL-P1-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/**`, `apps/api/src/test/**` | Create all architecture package shells and ArchUnit rules for dependency direction/cycles without product behavior; architecture tests pass. |
| P1-030 | PENDING | `api_architect` | P1-010 | SERIAL-P1-CONTRACT | `apps/api/src/main/resources/openapi/rivoquery-v1.yaml`, `apps/api/pom.xml` | Define OpenAPI 3.1 baseline for health/problem details/request ID and server/client generation; validate the spec and generated Spring interfaces without future endpoints. |
| P1-040 | PENDING | `backend_engineer` | P1-010 | SERIAL-P1-MIGRATION | `apps/api/src/main/resources/db/migration/**`, `apps/api/src/test/**` | Add only a minimal platform heartbeat/version migration; prove migrate-from-empty and repeat startup. No tenants/connections/product tables exist. Evidence: Flyway/Testcontainers test output. |
| P1-050 | PENDING | `backend_engineer` | P1-020, P1-030, P1-040 | P1-B | `apps/api/src/main/java/com/knowimran/rivoquery/{interfaceapi,platform}/**`, `apps/api/src/test/**` | Implement liveness, control-DB-sensitive readiness, safe RFC 9457 mapping, request ID, sanitized structured-log and metrics baseline; unit/integration tests prove readiness semantics and no sensitive request-body logging. |
| P1-060 | PENDING | `frontend_engineer` | P1-001 | P1-A | `apps/web/package.json`, `apps/web/tsconfig*.json`, `apps/web/src/app/**`, `apps/web/src/test/**` | Pin React/TypeScript tooling, enable strict mode, render an accessible shell, and add unit setup; `npm --prefix apps/web run lint`, `test`, and `build` pass without handwritten backend DTOs. |
| P1-070 | PENDING | `frontend_engineer` | P1-030, P1-060 | SERIAL-P1-GENERATED | `apps/web/src/api/generated/**`, `apps/web/src/api/**`, `apps/web/package.json` | Generate the client from OpenAPI and call backend health with typed states; regeneration is deterministic and generated output/check mode passes. |
| P1-080 | PENDING | `devops_engineer` | P1-001 | P1-A | `deploy/local/**`, `.env.example`, `.gitignore` | Add local control PostgreSQL Compose, synthetic example values, health checks, and no committed secret/local volume data; `docker compose config` and local dependency startup succeed. |
| P1-090 | PENDING | `devops_engineer` | P1-010, P1-060, P1-080 | P1-B | `.github/workflows/**` | Add CI-ready backend/frontend/build/integration/secret-scan stages with caches and pinned actions; workflow lint or equivalent validation passes. |
| P1-100 | PENDING | `qa_tester` | P1-050, P1-070, P1-080 | P1-C | `apps/api/src/test/**`, `apps/web/src/test/**`, `docs/verification/phase-1.md` | Start dependencies/API/web locally, verify liveness/readiness, typed web health, migration-from-empty, safe problem/request ID, and failed-control-DB readiness; record sanitized evidence. |
| P1-110 | PENDING | `backend_code_reviewer` | P1-050, P1-090 | P1-REVIEW | `apps/api/**`, `deploy/local/**`, `.github/workflows/**` | Independently review scope, architecture, migration minimalism, redaction, dependency pins, and tests against `docs/CODE_REVIEW.md`; no blocking finding remains. |
| P1-120 | PENDING | `frontend_code_reviewer` | P1-070 | P1-REVIEW | `apps/web/**` | Independently review strict typing, generated-client use, accessibility, secret/browser persistence baseline, and tests; no blocking finding remains. |
| P1-130 | PENDING | primary agent | P1-090, P1-100, P1-110, P1-120 | SERIAL-P1 | `README.md`, `docs/OPERATIONS.md`, `PLANS.md`, `docs/verification/phase-1.md` | Run all four common commands, scan diff/secrets, record versions, local-run evidence, unavailable checks, rollback (remove app deployment; migration is additive), and Phase 1 outcome. |
| P1-140 | PENDING | primary agent | P1-130 | SERIAL-P1 | `PLANS.md`, `docs/verification/phase-1.md` | Commit/push/open `CP-1` only after all earlier PRs are merged; merge before Phase 2. Sanity runner is explicitly not invoked. |

## 5. Phase 2 — identity and connection control plane

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P2-001 | PENDING | primary agent | P1-140 | SERIAL-P2 | `PLANS.md` | Create Phase 2 branch from updated `main`; verify clean baseline and mark entry decisions as the only active planning work. |
| P2-010 | PENDING | `prd_researcher` | P2-001 | P2-DECIDE | `docs/product-decisions/phase-2.md`, `docs/PRD.md` | Resolve administrator-only connection creation and multi-workspace discovery/selection with product owner; define `X-RivoQuery-Workspace-Id` selector semantics. If requirements change materially, update PRD before design/contracts. |
| P2-020 | PENDING | `solution_architect` | P2-001 | P2-DECIDE | `docs/adrs/0013-outbound-destination-enforcement.md`, `docs/adrs/README.md` | Decide enforceable outbound destination handling for PostgreSQL/MySQL: all-address IPv4/IPv6 checks, DNS TTL/rebinding, vetted address connection, original-host TLS verification, private allowlist, pool behavior, and infrastructure egress. ADR acceptance is a Phase 2 gate. |
| P2-030 | PENDING | `solution_architect` | P2-001 | P2-DECIDE | `docs/design/secret-store-capabilities.md` | Specify tenant/connection/purpose/version-bound `SecretStore`, destructible `SecretLease`, stage/switch/revoke compensation, confused-deputy prevention, and pool ownership. |
| P2-040 | PENDING | `api_architect` | P2-010, P2-020, P2-030 | SERIAL-P2-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml` | Resolve `MANAGE_CONNECTION` to accepted `MANAGE`; define workspace discovery/selection, permission management, connection lifecycle, idempotency/replay, safe options, states, test/rotation/delete, errors, and operation links. Validate OpenAPI and compatibility. |
| P2-050 | PENDING | `backend_engineer` | P2-040 | SERIAL-P2-INTERFACE | `apps/api/src/main/java/com/knowimran/rivoquery/{identity,connection}/**`, `apps/api/src/test/**` | Implement immutable IDs, `ActorContext`, roles/permission matrix, connection aggregate/state transitions, destination/secret/connector ports, and constructor-only dependency boundaries. Unit/ArchUnit tests cover invalid transitions and forbidden dependencies. |
| P2-060 | PENDING | `backend_engineer` | P2-040, P2-050 | SERIAL-P2-MIGRATION | `apps/api/src/main/resources/db/migration/**`, `apps/api/src/main/java/com/knowimran/rivoquery/platform/persistence/**`, `apps/api/src/test/**` | Add tenants, identities, memberships, permissions, connections, secret references, idempotency, and required durable-operation records with `tenant_id`, composite tenant/resource FKs, optimistic versions, indexes, additive rollback/forward-fix notes, and migrate-from-empty tests. |
| P2-070 | PENDING | `backend_engineer` | P2-030, P2-050 | P2-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/secrets/**`, `apps/api/src/test/**` | Implement external-key AES-GCM local adapter and production port/stub; ciphertext-only persistence, redacted objects, bounded lease lifetime, provider-failure and canary telemetry tests pass. |
| P2-080 | PENDING | `backend_engineer` | P2-020, P2-050 | P2-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/destination/**`, `apps/api/src/test/**` | Implement structured endpoint/property validation, all-answer IP classification, connect-time revalidation capability, TLS hostname preservation, prohibited ranges, operator private allowlist, deadlines, and rebinding tests for IPv4/IPv6. |
| P2-090 | PENDING | `backend_engineer` | P2-050 | P2-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/**`, `apps/api/src/test/**` | Implement capability-reporting connector SPI and trusted JDBC URL/property builders; users cannot supply URLs/classes/socket factories/files. Shared contract and dialect configuration tests pass. |
| P2-100 | PENDING | `backend_engineer` | P2-060, P2-070, P2-080, P2-090 | SERIAL-P2-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/{connection,platform}/**`, `apps/api/src/test/**` | Implement create/list/get/update/disable/delete/test/rotation saga, tenant-scoped repositories, JIT secret resolution, non-pooled tests, pool key/budgets/drain, compensation, optimistic concurrency, stable errors, tombstoned deletion/durable cleanup, and queueing (but not executing) initial discovery. Unit tests cover every transition/failure. |
| P2-110 | PENDING | `backend_engineer` | P2-090, P2-100 | P2-B | `apps/api/src/test/**/postgresql/**` | Real PostgreSQL Testcontainers connection/TLS-option/read-only-capability/timeout/error/redaction tests pass with synthetic credentials. |
| P2-120 | PENDING | `backend_engineer` | P2-090, P2-100 | P2-B | `apps/api/src/test/**/mysql/**` | Real MySQL Testcontainers connection/TLS-option/read-only-capability/timeout/error/redaction tests pass with synthetic credentials. |
| P2-130 | PENDING | `backend_engineer` | P2-040, P2-100 | P2-B | `apps/api/src/main/java/com/knowimran/rivoquery/interfaceapi/**`, `apps/api/src/test/**` | Implement connection/workspace/permission endpoints through use cases, RFC 9457 codes, auth-before-load, idempotency and cross-tenant negative matrix; no response can contain secret material or raw driver errors. |
| P2-140 | PENDING | `frontend_engineer` | P2-040, P2-130 | P2-C | `apps/web/src/features/{auth,connections}/**`, `apps/web/src/test/**` | Add workspace selector and connection onboarding/manage/test/rotate/disable/delete UI using generated types; secrets are transient/write-only and cleared on success, error, navigation, and unmount. |
| P2-150 | PENDING | `qa_tester` | P2-110, P2-120, P2-130, P2-140 | P2-QA | `apps/api/src/test/**`, `apps/web/src/test/**`, `docs/verification/phase-2.md` | Exercise permission/cross-tenant matrix, idempotency, SSRF IPv4/IPv6/rebinding, secret compensation/canary, rotation race/pool drain, provider failure, stable errors, and both engines. Record synthetic evidence. |
| P2-160 | PENDING | `backend_code_reviewer` | P2-130 | P2-REVIEW | `apps/api/**` | Independently review tenant queries/FKs, authorization order, secret ownership/redaction, destination enforcement, pool bounds, compensation, migration safety, and tests; blockers resolved. |
| P2-170 | PENDING | `frontend_code_reviewer` | P2-140 | P2-REVIEW | `apps/web/**` | Independently review generated-client use, transient secret lifecycle, auth/workspace behavior, accessibility, errors, and tests; blockers resolved. |
| P2-180 | PENDING | `devops_engineer` | P2-070, P2-080, P2-100 | P2-D | `deploy/local/**`, `docs/OPERATIONS.md` | Document external encryption key injection, outbound egress/private-target policy, total pool budgets, rotation/deletion operations, and safe local synthetic configuration; validate Compose/config without embedded keys. |
| P2-190 | PENDING | primary agent | P2-150, P2-160, P2-170, P2-180 | SERIAL-P2 | `docs/**`, `PLANS.md` | Run local core connection journey and full suite; record decisions, migrations/forward rollback, checks, risks, and Phase 2 outcome; secret scan/diff review clean. |
| P2-200 | PENDING | primary agent | P2-190 | SERIAL-P2 | `PLANS.md`, `docs/verification/phase-2.md` | Commit/push/open/merge `CP-2` under serial-PR rule before Phase 3. No sanity runner: complete query product is not implemented. |

## 6. Phase 3 — schema discovery and relevance selection

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P3-001 | PENDING | primary agent | P2-200 | SERIAL-P3 | `PLANS.md` | Create clean Phase 3 branch and verify Phase 2 gates/evidence. |
| P3-010 | PENDING | `solution_architect` | P3-001 | P3-DESIGN | `docs/design/catalog-authorization-and-worker.md` | Freeze catalog authorization order and raw/canonical qualified-identifier rules plus durable worker claim, lease renewal, fencing token, retry/backoff, idempotency, crash recovery, cleanup, and shutdown semantics. |
| P3-020 | PENDING | `api_architect` | P3-010 | SERIAL-P3-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml` | Define durable schema-refresh operation/status, schema browsing/freshness, business metadata CRUD, idempotency, cursor expiry, and safe errors; regenerate interfaces before consumers. |
| P3-030 | PENDING | `backend_engineer` | P3-010 | SERIAL-P3-INTERFACE | `apps/api/src/main/java/com/knowimran/rivoquery/catalog/**`, `apps/api/src/test/**` | Implement vendor-neutral immutable catalog, raw/transient dialect canonical form, raw-and-canonical allowlist/system denial, optional column-classification restriction, authorized catalog, capabilities, snapshot fingerprint, selector ports, and security-significant processing order. Tests prove deterministic sorting and no unauthorized persisted object. |
| P3-040 | PENDING | `backend_engineer` | P3-020, P3-030 | SERIAL-P3-MIGRATION | `apps/api/src/main/resources/db/migration/**`, `apps/api/src/main/java/com/knowimran/rivoquery/platform/persistence/**`, `apps/api/src/test/**` | Add immutable snapshot/object/relationship/business metadata and worker lease/fencing columns with tenant composite FKs; atomic current-snapshot switch, additive rollback notes, migrate-from-empty and constraint tests pass. |
| P3-050 | PENDING | `backend_engineer` | P3-030 | P3-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/postgresql/**`, `apps/api/src/test/**/postgresql/**` | Discover allowed PostgreSQL schemas/tables/views/columns/keys/comments/capabilities; raw and canonical authorization covers quoted/case/alias/system objects with real-engine tests. No values/rows are read. |
| P3-060 | PENDING | `backend_engineer` | P3-030 | P3-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/mysql/**`, `apps/api/src/test/**/mysql/**` | Discover allowed MySQL metadata and prove dialect case/catalog behavior, aliases, system exclusions, and no values/rows with real-engine tests. |
| P3-070 | PENDING | `backend_engineer` | P3-040 | P3-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/operation/**`, `apps/api/src/test/**` | Implement transactional claims, leases, fencing, bounded retries, idempotency, crash recovery, stale-worker denial, cleanup and graceful shutdown. Worker-fencing/concurrency tests pass. |
| P3-080 | PENDING | `backend_engineer` | P3-040, P3-050, P3-060, P3-070 | SERIAL-P3-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/{catalog,platform}/**`, `apps/api/src/test/**` | Implement manual/background refresh, authorization before persistence/indexing, deterministic fingerprint/idempotency, immutable snapshot storage, and atomic pointer switch; concurrency/schema-change tests pass. |
| P3-090 | PENDING | `backend_engineer` | P3-030 | P3-A | `apps/api/src/main/java/com/knowimran/rivoquery/catalog/**`, `apps/api/src/test/**`, `evals/schema-selection/**` | Implement bounded deterministic lexical scoring over names, descriptions, synonyms and metric definitions; approved join hints/FK expansion; object/column/hop/token budgets; stable ties; and versioned synthetic recall fixtures. Report baseline recall/limitations. |
| P3-100 | PENDING | `backend_engineer` | P3-020, P3-080, P3-090 | P3-B | `apps/api/src/main/java/com/knowimran/rivoquery/interfaceapi/**`, `apps/api/src/test/**` | Implement contract through shared use cases with `VIEW_SCHEMA`, workspace/tenant checks, bounded cursors and safe operation states/errors. |
| P3-110 | PENDING | `frontend_engineer` | P3-020, P3-100 | P3-C | `apps/web/src/features/schema/**`, `apps/web/src/test/**` | Build bounded accessible schema freshness, refresh/status and schema browser UI with generated types; render untrusted metadata safely and never sample values. |
| P3-120 | PENDING | `qa_tester` | P3-080, P3-090, P3-100, P3-110 | P3-QA | `apps/api/src/test/**`, `apps/web/src/test/**`, `evals/schema-selection/**`, `docs/verification/phase-3.md` | Verify both engines normalize compatibly; raw/canonical identifier attacks fail; refresh idempotency/fingerprint/worker fencing/crash recovery pass; selection budgets/recall recorded; no sample-value access. |
| P3-130 | PENDING | `backend_code_reviewer` | P3-100 | P3-REVIEW | `apps/api/**`, `evals/schema-selection/**` | Review authorization ordering, dialect identifiers, tenant persistence, immutability, worker fencing, selector bounds, no row sampling, and tests; blockers resolved. |
| P3-140 | PENDING | `frontend_code_reviewer` | P3-110 | P3-REVIEW | `apps/web/**` | Review generated types, bounded rendering, unsafe metadata display, accessibility, refresh states, and tests; blockers resolved. |
| P3-150 | PENDING | `devops_engineer` | P3-070, P3-080 | P3-D | `docs/OPERATIONS.md`, `deploy/local/**` | Document worker replicas, leases, graceful shutdown, refresh schedules/cooldowns, capacity and recovery; validate local multi-worker failure scenario where practical. |
| P3-160 | PENDING | primary agent | P3-120, P3-130, P3-140, P3-150 | SERIAL-P3 | `docs/**`, `PLANS.md` | Run local discovery/refresh/browser journey and full suite; record migration rollback/forward-fix, selector limits, evidence, and outcome. |
| P3-170 | PENDING | primary agent | P3-160 | SERIAL-P3 | `PLANS.md`, `docs/verification/phase-3.md` | Commit/push/open/merge `CP-3` before Phase 4. Product sanity remains deferred until execution exists. |

## 7. Phase 4 — Text-to-SQL generation and QuerySentry

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P4-001 | PENDING | primary agent | P3-170 | SERIAL-P4 | `PLANS.md` | Create clean Phase 4 branch and verify catalog/worker gates. |
| P4-010 | PENDING | `prd_researcher` | P4-001 | P4-DECIDE | `docs/product-decisions/phase-4.md`, `docs/PRD.md` | Select reference OpenAI-compatible adapter configuration for examples, provider retention defaults, and whether `QUERY` implicitly permits the schema/object visibility in an approved preview; document owner approval. |
| P4-020 | PENDING | `solution_architect` | P4-001 | P4-DECIDE | `docs/adrs/0014-querysentry-parser-canonicalization.md`, `docs/adrs/README.md` | Select dialect-capable parser/canonicalizer and containment mechanism; specify deadlines, AST transformations, serialization/reparse/revalidation, semantic preservation, unsupported syntax, limits, CTEs/set operations/quoted identifiers. ADR acceptance gates implementation. |
| P4-030 | PENDING | `api_architect` | P4-010, P4-020 | SERIAL-P4-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml` | Define versioned `READY`/`NEEDS_CLARIFICATION`/`UNANSWERABLE` preview, bounded fields, warnings, referenced objects, stable provider/policy errors, query visibility, and no raw-SQL input. Regenerate interfaces/client. |
| P4-040 | PENDING | `backend_engineer` | P4-020, P4-030 | SERIAL-P4-INTERFACE | `apps/api/src/main/java/com/knowimran/rivoquery/{generation,querypolicy}/**`, `apps/api/src/test/**` | Implement RivoQuery-owned request/response types, per-tenant model-policy input, model and parser ports, policy outcomes, limits, fingerprint types, and `ApprovedQuery` private QuerySentry-only construction capability. ArchUnit forbids other construction and raw-string executor entry points. |
| P4-050 | PENDING | `backend_engineer` | P4-040 | P4-A | `apps/api/src/test/**/generation/**` | Implement deterministic fake for all statuses/errors and strict response schema/size validation; malformed data is provider failure and formatting-only retry is at most one. |
| P4-060 | PENDING | `backend_engineer` | P4-010, P4-040 | P4-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/model/**`, `apps/api/src/test/**`, `docs/OPERATIONS.md` | Implement configurable provider adapter with bounded timeout/output/retry, provider DTO isolation, retention configuration and sanitized telemetry; HTTP stub covers timeout/rate/server/malformed/oversize. No live-provider gate. |
| P4-070 | PENDING | `backend_engineer` | P4-040, P3-090 | P4-A | `apps/api/src/main/java/com/knowimran/rivoquery/generation/**`, `apps/api/src/main/resources/prompts/**`, `apps/api/src/test/**` | Build strict question/schema/object/column/token budgets and untrusted-data delimiters; snapshot tests prove no credentials, endpoints, rows, unauthorized schema or internal auth data. |
| P4-080 | PENDING | `backend_engineer` | P4-020, P4-040 | P4-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/sqlparser/**`, `apps/api/src/test/**`, `evals/security/**` | Implement bounded PostgreSQL/MySQL parsing and canonical AST transformation; prove deadline/nontermination containment and semantic preservation on accepted corpus before QuerySentry integration. |
| P4-090 | PENDING | `backend_engineer` | P4-070, P4-080 | SERIAL-P4-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/querypolicy/**`, `apps/api/src/test/**` | Enforce one SELECT/non-modifying CTE; deny modifying/admin/session/locking/INTO/dynamic/external/file/network/sleep/system behavior; resolve every object against bound authorized snapshot; apply complexity/output/strictest limits; transform/reparse/revalidate; construct `ApprovedQuery`. |
| P4-100 | PENDING | `qa_tester` | P4-050, P4-060, P4-070, P4-090 | P4-QA | `evals/text-to-sql/**`, `evals/security/**`, `evals/README.md` | Create versioned synthetic semantic/adversarial datasets with database fixture/schema version, question, optional clarification, allowed objects, expected result invariant/policy outcome and tags. Cover prompt injection, credential extraction, obfuscation, nested writes, CTEs, comments, Unicode/NUL, functions, cross-schema, parser timeouts, limits and both dialects. Zero write/admin escapes; record false positives and selector recall. |
| P4-110 | PENDING | `backend_engineer` | P4-030, P4-050, P4-060, P4-090 | P4-B | `apps/api/src/main/java/com/knowimran/rivoquery/{generation,interfaceapi}/**`, `apps/api/src/test/**` | Implement authorize-before-catalog/model preview orchestration and contract statuses with request metadata; no target execution and no raw client SQL. Provider/policy failures fail closed with safe problems. |
| P4-120 | PENDING | `frontend_engineer` | P4-030, P4-110 | P4-C | `apps/web/src/features/query/**`, `apps/web/src/test/**` | Add generated-client preview states needed for API verification, showing selected connection, SQL, assumptions, warnings and enforced limits; do not add execution or browser persistence. |
| P4-130 | PENDING | `backend_code_reviewer` | P4-110, P4-100 | P4-REVIEW | `apps/api/**`, `evals/**` | Review provider isolation/data minimization, parser containment, AST policy completeness, object resolution, private capability, strict limits, fail-closed errors and corpus strength; blockers resolved. |
| P4-140 | PENDING | `frontend_code_reviewer` | P4-120 | P4-REVIEW | `apps/web/**` | Review typed states, SQL/metadata display, no policy duplication/persistence, accessibility and tests; blockers resolved. |
| P4-150 | PENDING | `qa_tester` | P4-110, P4-120, P4-130, P4-140 | P4-QA2 | `apps/api/src/test/**`, `apps/web/src/test/**`, `evals/**`, `docs/verification/phase-4.md` | Run deterministic preview/clarification/unanswerable/provider failure/prompt injection/policy rejection flows and complete corpus for both dialects; record metrics and safe artifacts only. |
| P4-160 | PENDING | primary agent | P4-150 | SERIAL-P4 | `apps/api/src/main/resources/prompts/**`, `docs/**`, `PLANS.md` | Run local preview journey and full suite; record prompt/policy versions, corpus metrics, checks, known parser limitations, no-execution guarantee, rollback, and outcome. |
| P4-170 | PENDING | primary agent | P4-160 | SERIAL-P4 | `PLANS.md`, `docs/verification/phase-4.md` | Commit/push/open/merge `CP-4` before Phase 5. Sanity deferred because core execution is absent. |

## 8. Phase 5 — bounded execution, preview capabilities, and audit

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P5-001 | PENDING | primary agent | P4-170 | SERIAL-P5 | `PLANS.md` | Create clean Phase 5 branch and verify QuerySentry/corpus gates. |
| P5-010 | PENDING | `prd_researcher` | P5-001 | P5-DECIDE | `docs/product-decisions/phase-5.md`, `docs/PRD.md` | Resolve question/SQL/prompt/model response retention profiles and confirm preview schema visibility under `QUERY`; define defaults, configurability, deletion and audit implications. |
| P5-020 | PENDING | `solution_architect` | P5-001 | P5-DECIDE | `docs/adrs/0015-preview-capability-persistence.md`, `docs/adrs/README.md` | Decide encrypted multi-replica preview storage, external keys, actor/tenant/connection/schema/SQL/prompt/policy/limit/expiry binding, CAS claim, idempotent replay/in-progress/conflict, direct execute, tamper/key rotation/cleanup, and no ambiguous auto-retry. |
| P5-030 | PENDING | `solution_architect` | P5-001 | P5-DECIDE | `docs/adrs/0016-execution-audit-atomicity.md`, `docs/adrs/README.md` | Freeze accepted-before-execute and terminal metadata/outbox transaction semantics, row withholding on terminal failure, reconciliation/alerts, and complete Query/Execution transitions aligned to `SUCCEEDED` plus `truncated`. |
| P5-040 | PENDING | `api_architect` | P5-010, P5-020, P5-030 | SERIAL-P5-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml` | Define opaque execute input, expiry/version conflicts, double-submit replay/in-progress response, direct execute, typed bounded results, cursor/retention, feedback/audit filters and safe RFC 9457 errors. Regenerate interfaces/client. |
| P5-050 | PENDING | `backend_engineer` | P5-040 | SERIAL-P5-INTERFACE | `apps/api/src/main/java/com/knowimran/rivoquery/{execution,audit}/**`, `apps/api/src/test/**` | Implement immutable execution limits, normalized column/value/result types, lifecycle, preview store/audit/outbox/executor ports; only executor consumes `ApprovedQuery`; unit/ArchUnit transition and forbidden-overload tests pass. |
| P5-060 | PENDING | `backend_engineer` | P5-020, P5-030, P5-040, P5-050 | SERIAL-P5-MIGRATION | `apps/api/src/main/resources/db/migration/**`, `apps/api/src/main/java/com/knowimran/rivoquery/platform/persistence/**`, `apps/api/src/test/**` | Add tenant-composite-FK records, encrypted short-lived SQL artifact metadata, CAS fields, idempotency, append-only audit/outbox and retention indexes; migrate-from-empty, tamper, constraints, forward rollback and cleanup tests pass. |
| P5-070 | PENDING | `backend_engineer` | P5-020, P5-060 | P5-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/preview/**`, `apps/api/src/test/**` | Implement external-key authenticated encryption, aggressive expiry, atomic `READY -> EXECUTING`, attempt identity, replay/conflict behavior and direct internal claim; tests cover expiry, tamper, key rotation and concurrent double submit. |
| P5-080 | PENDING | `backend_engineer` | P5-030, P5-060 | P5-A | `apps/api/src/main/java/com/knowimran/rivoquery/{audit,platform}/**`, `apps/api/src/test/**` | Commit `EXECUTION_ACCEPTED` before target access; terminal metadata/outbox before rows release; withhold rows on terminal failure; reconcile/alert missing terminal events. Tests inject pre/terminal/outbox failures without persisting rows. |
| P5-090 | PENDING | `backend_engineer` | P5-050 | P5-A | `apps/api/src/main/java/com/knowimran/rivoquery/execution/**`, `apps/api/src/test/**` | Implement deterministic JSON-safe types plus row, byte, column, cell, deadline, disconnect and cancellation caps while streaming at most `maxRows + 1`; default timeout 15 seconds, default 200 rows, platform interactive maximum 1,000 rows and default 2 MiB payload apply unless a stricter policy wins. Resources close on every path. |
| P5-100 | PENDING | `backend_engineer` | P5-050, P2-100 | P5-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/{connector,secrets,destination}/**`, `apps/api/src/test/**` | Enforce global/tenant/connection capacities before pool creation; pool keys include tenant, connection/version, secret version and destination-policy version; JIT lease, connect-time destination validation, idle eviction/drain/shutdown and no telemetry leakage. |
| P5-110 | PENDING | `backend_engineer` | P5-090, P5-100 | P5-B | `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/postgresql/**`, `apps/api/src/test/**/postgresql/**` | Apply PostgreSQL read-only transaction/session, JDBC timeout/max rows/cancel and typed streaming only from `ApprovedQuery`; real-engine tests deny writes/admin and verify timeout/cancel/caps/pool cleanup. |
| P5-120 | PENDING | `backend_engineer` | P5-090, P5-100 | P5-B | `apps/api/src/main/java/com/knowimran/rivoquery/platform/connector/mysql/**`, `apps/api/src/test/**/mysql/**` | Apply MySQL read-only session/driver controls and identical bounds; real-engine tests deny writes/admin and verify timeout/cancel/caps/types/pool cleanup. |
| P5-130 | PENDING | `backend_engineer` | P5-070, P5-080, P5-110, P5-120 | SERIAL-P5-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/execution/**`, `apps/api/src/test/**` | Reauthorize, reload bindings, rerun QuerySentry for fresh capability, commit accepted audit, execute once, persist terminal metadata, and return rows only afterward. Changed state conflicts; ambiguous failures never retry. Complete authorization/concurrency/failure tests pass. |
| P5-140 | PENDING | `backend_engineer` | P5-040, P5-130 | P5-C | `apps/api/src/main/java/com/knowimran/rivoquery/interfaceapi/**`, `apps/api/src/test/**` | Implement generated interfaces, bounded result responses, stable safe driver mapping, metadata-only feedback/audit, filters and permission matrix; cross-tenant and client-SQL attempts fail closed. |
| P5-150 | PENDING | `qa_tester` | P5-110, P5-120, P5-130, P5-140 | P5-QA | `apps/api/src/test/**`, `evals/**`, `docs/verification/phase-5.md` | Verify both engines, read-only defense, timeout/cancel/disconnect, every cap, types, cross-tenant denial, expiry/tamper/version conflict/double-submit, secret/pool limits, audit failures/reconciliation, no rows in persistence/telemetry, and core semantic fixtures. |
| P5-160 | PENDING | `backend_code_reviewer` | P5-140, P5-150 | P5-REVIEW | `apps/api/**`, `evals/**` | Independently review capability boundary, reauthorization, CAS/idempotency, transaction/audit ordering, JIT secrets, pool budgets, dialect controls, resource closure, safe errors/migrations and tests; blockers resolved. |
| P5-170 | PENDING | `devops_engineer` | P5-070, P5-080, P5-100 | P5-D | `docs/OPERATIONS.md`, `deploy/local/**` | Document preview key injection/rotation, cleanup/reconciliation, audit alerts, pool/concurrency math across replicas, cancellation/shutdown, retention and recovery; validate local configuration. |
| P5-180 | PENDING | primary agent | P5-150, P5-160, P5-170 | SERIAL-P5 | `docs/**`, `PLANS.md` | Run local authenticated REST connection-to-answer journey and all common/full/security/eval checks; record evidence, migration/rollback, risks, and outcome. Normal automated verification must pass before P5-190. |
| P5-190 | PENDING | `sanity_runner` | P5-180 | SANITY-P5 | `docs/verification/phase-5.md` | First eligible sanity run: exercise live connection CRUD/test, schema refresh/status, preview/clarification/rejection/execute, bounded/truncated results, safe failures and audit using synthetic databases. Record API/functionality evidence; do not inspect or review source. |
| P5-200 | PENDING | primary agent | P5-190 | SERIAL-P5 | `PLANS.md`, `docs/verification/phase-5.md` | Resolve sanity defects, rerun affected/full checks, commit/push/open/merge `CP-5` before Phase 6. |

## 9. Phase 6 — web console

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P6-001 | PENDING | primary agent | P5-200 | SERIAL-P6 | `PLANS.md` | Create clean Phase 6 branch and verify runnable REST core journey. |
| P6-010 | PENDING | `prd_researcher` | P6-001 | P6-DECIDE | `docs/product-decisions/phase-6.md`, `docs/PRD.md` | Decide whether export is MVP and approve browser identity/token policy and reference OIDC user journey. If export is deferred, remove it from Phase 6 acceptance and record future scope; if approved, define separate caps/audit. |
| P6-020 | PENDING | `api_architect` | P6-010 | SERIAL-P6-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml` | Finalize auth/workspace discovery and, only if approved, export contract/permissions/caps/idempotency/audit; regenerate client before UI feature work. |
| P6-025 | PENDING | `backend_engineer` | P6-020 | SERIAL-P6-SHARED | `apps/api/src/main/java/com/knowimran/rivoquery/{identity,execution,audit,interfaceapi}/**`, `apps/api/src/test/**` | Implement and test the finalized auth/workspace contract and, only when approved, bounded `EXPORT`-authorized execution/export with separate audit and caps. If export is deferred, prove no export route exists and record the task as completed by contract enforcement. |
| P6-030 | PENDING | `frontend_engineer` | P6-020, P6-025 | SERIAL-P6-SHARED | `apps/web/src/app/**`, `apps/web/src/features/auth/**`, `apps/web/src/security/**`, `apps/web/src/test/**` | Implement OIDC authorization-code + PKCE shell, in-memory tokens, validated workspace selection, auth/error/loading routes, and no browser persistence/service-worker/analytics leakage. |
| P6-040 | PENDING | `frontend_engineer` | P6-030 | P6-A | `apps/web/src/features/connections/**`, `apps/web/src/test/**` | Complete accessible connection create/test/manage/rotate/disable/delete UI; write-only secrets clear on every completion/navigation/unmount path and cannot redisplay. |
| P6-050 | PENDING | `frontend_engineer` | P6-030 | P6-A | `apps/web/src/features/schema/**`, `apps/web/src/test/**` | Complete bounded schema freshness/refresh/status/browser/business metadata UI with safe untrusted text rendering. |
| P6-060 | PENDING | `frontend_engineer` | P6-030 | P6-A | `apps/web/src/features/query/**`, `apps/web/src/test/**` | Complete question connection selector, clarification, SQL preview default, assumptions/warnings/limits, explicit execute, replay/conflict/expiry and error states with request ID. |
| P6-070 | PENDING | `frontend_engineer` | P6-030 | P6-A | `apps/web/src/{features/query,features/audit,components}/**`, `apps/web/src/test/**` | Build typed bounded accessible result grid with type-preserving cells, keyboard support, loading/empty/error/warning/truncation, feedback and optional approved export; never persist SQL/rows or render unbounded content. |
| P6-080 | PENDING | `qa_tester` | P6-040, P6-050, P6-060, P6-070 | P6-QA | `apps/web/src/test/**`, `apps/web/e2e/**`, `docs/verification/phase-6.md` | Test complete non-technical core journey plus auth, secret clearing, clarification, rejection, expiry/conflict, truncation, feedback, cross-tenant/permission and browser storage/cache/session-replay absence; lint/unit/build/a11y/E2E pass. |
| P6-090 | PENDING | `frontend_code_reviewer` | P6-080 | P6-REVIEW | `apps/web/**` | Independently review generated contract, no backend-policy duplication, OIDC/token/secret/result handling, bounds, accessibility/responsiveness and test quality; blockers resolved. |
| P6-100 | PENDING | `backend_code_reviewer` | P6-025 | P6-REVIEW | `apps/api/**` | Review contract/auth/export backend changes for permission, caps, audit, compatibility and safe errors; blockers resolved. |
| P6-110 | PENDING | primary agent | P6-080, P6-090, P6-100 | SERIAL-P6 | `docs/**`, `PLANS.md` | Run local browser core journey and full suite, record identity/export decision, screenshots or sanitized state evidence, known limits, rollback and outcome. |
| P6-120 | PENDING | `sanity_runner` | P6-110 | SANITY-P6 | `docs/verification/phase-6.md` | After automated tests pass, exercise live user-critical UI-backed API functionality end to end, including failures/truncation/audit; record sanitized functional evidence only. |
| P6-130 | PENDING | primary agent | P6-120 | SERIAL-P6 | `PLANS.md`, `docs/verification/phase-6.md` | Resolve defects/reverify, commit/push/open/merge `CP-6` before Phase 7. |

## 10. Phase 7 — MCP interface

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P7-001 | PENDING | primary agent | P6-130 | SERIAL-P7 | `PLANS.md` | Create clean Phase 7 branch; keep Phase 8 out of this checkpoint. |
| P7-010 | PENDING | `prd_researcher` | P7-001 | P7-DECIDE | `docs/product-decisions/phase-7.md`, `docs/PRD.md` | Resolve supported MCP transport, interactive and service identity use cases, tenancy/workspace selection, token lifecycle and reference client expectations. |
| P7-020 | PENDING | `solution_architect` | P7-010 | SERIAL-P7-DESIGN | `docs/adrs/0017-mcp-transport-service-identity.md`, `docs/adrs/README.md` | Accept MCP deployment/authentication/service-identity decision, threat controls, output caps and shared-use-case boundary; direct connector access is structurally prohibited. |
| P7-030 | PENDING | `api_architect` | P7-020 | SERIAL-P7-CONTRACT | `docs/API_AND_MCP_CONTRACTS.md`, `apps/api/src/main/resources/openapi/rivoquery-v1.yaml`, `apps/api/src/main/resources/mcp/**` | Finalize typed bounded schemas and stable parity outcomes for `list_connections`, `describe_schema`, `preview_query`, `ask_database`; descriptions state read-only limits and clarification behavior. |
| P7-040 | PENDING | `backend_engineer` | P7-030 | SERIAL-P7-INTERFACE | `apps/api/src/main/java/com/knowimran/rivoquery/interfacemcp/**`, `apps/api/src/test/**` | Implement transport and authenticated actor/service mapping to server-side tenant/membership/permissions; independent DTO mapping delegates only to existing use cases. ArchUnit forbids connector/persistence/model direct dependencies. |
| P7-050 | PENDING | `backend_engineer` | P7-040 | P7-A | `apps/api/src/main/java/com/knowimran/rivoquery/interfacemcp/**`, `apps/api/src/test/**` | Implement all four tools with connection IDs only, bounded typed output, safe errors and optional stricter caps; no credentials, internal errors, unauthorized schema or raw-SQL inputs. |
| P7-060 | PENDING | `qa_tester` | P7-050 | P7-QA | `apps/api/src/test/**`, `docs/verification/phase-7.md` | Prove identical authorization, tenant isolation, clarification, QuerySentry decisions, execution/audit and limits across transports; service identity negatives and context-flood caps pass. |
| P7-070 | PENDING | `backend_code_reviewer` | P7-060 | P7-REVIEW | `apps/api/**` | Review identity mapping, DTO independence, shared use cases, no bypass/dependency, bounds, errors, telemetry and tests; blockers resolved. |
| P7-080 | PENDING | `devops_engineer` | P7-020, P7-050 | P7-D | `deploy/**`, `docs/OPERATIONS.md` | Document MCP endpoint exposure, TLS/auth secrets, rate/egress controls, scaling and reference client configuration without tokens; validate deployment config. |
| P7-090 | PENDING | primary agent | P7-060, P7-070, P7-080 | SERIAL-P7 | `docs/examples/mcp/**`, `docs/**`, `PLANS.md` | Add token-free reference client examples, run local authenticated MCP and REST parity journey plus full suite, and record transport/identity decision, evidence, risks, rollback and outcome. |
| P7-100 | PENDING | `sanity_runner` | P7-090 | SANITY-P7 | `docs/verification/phase-7.md` | After normal checks, call all live MCP tools and matching REST flows with permitted and denied identities; verify bounded typed results, clarification, safe failure, execution and audit. |
| P7-110 | PENDING | primary agent | P7-100 | SERIAL-P7 | `PLANS.md`, `docs/verification/phase-7.md` | Resolve/reverify, commit/push/open/merge `CP-7`; confirm Phase 7 complete before any Phase 8 implementation. |

## 11. Phase 8 — hardening and release candidate

| ID | Status | Owner | Dependencies | Parallel group | Paths | Deliverable, acceptance, and evidence |
| --- | --- | --- | --- | --- | --- | --- |
| P8-001 | PENDING | primary agent | P7-110 | SERIAL-P8 | `PLANS.md` | Create clean Phase 8 branch and inventory every PRD release gate, threat mitigation and unresolved risk. |
| P8-010 | PENDING | `prd_researcher` | P8-001 | P8-DECIDE | `docs/product-decisions/phase-8.md`, `docs/PRD.md`, `docs/OPERATIONS.md` | Resolve self-hosted/SaaS profile, single-region production topology, reference IdP, first-customer data residency and retention profiles, and release acceptance authority. |
| P8-020 | PENDING | `solution_architect` | P8-010 | SERIAL-P8-DESIGN | `docs/adrs/0018-hosting-deployment-profile.md`, `docs/adrs/README.md` | Accept hosting/data-boundary/topology, external secret/KMS, control-plane/audit/telemetry residency, scaling, backup and responsibility decision before production manifests. |
| P8-030 | PENDING | `backend_engineer` | P8-020 | P8-A | `apps/api/src/main/java/com/knowimran/rivoquery/platform/**`, `apps/api/src/test/**` | Implement global/tenant/connection rate and concurrency controls, abuse cooldowns, mandatory secure configuration validation, retention/delete jobs and managed secret-store reference integration appropriate to chosen profile. Tests prove fail-closed limits. |
| P8-040 | PENDING | `backend_engineer` | P8-020 | P8-A | `apps/api/src/main/java/com/knowimran/rivoquery/{audit,platform}/**`, `apps/api/src/test/**` | Complete privacy-safe OTel logs/metrics/traces, low-cardinality labels, SLO measurements, audit export/reconciliation alerts and canary snapshots proving no questions/SQL/secrets/rows. |
| P8-050 | PENDING | `devops_engineer` | P8-020 | P8-A | `apps/api/Dockerfile`, `deploy/kubernetes/**`, `deploy/helm/**`, `deploy/test/**` | Build production image and reference IdP/deployment profile: non-root/read-only FS, probes/resources/disruption/graceful shutdown, controlled migration job, external secrets, TLS, deny-default egress with explicit destinations and replica-aware pool budgets. Validate/render and locally smoke image. |
| P8-060 | PENDING | `devops_engineer` | P8-020, P8-040 | P8-A | `deploy/observability/**`, `docs/OPERATIONS.md` | Implement/document 99.9% monthly API availability and stage-latency SLOs plus dashboards/alerts for auth, provider, QuerySentry, execution, caps, pools, control DB, secrets and audit without high-cardinality tenant labels. Validate configuration. |
| P8-070 | PENDING | `devops_engineer` | P8-050 | P8-B | `.github/workflows/**`, `.github/dependabot.yml` | Add secret, dependency, SAST where appropriate, container and IaC scans, SBOM/provenance and critical/high release policy with documented exception process; CI config passes validation and no unresolved critical/high finding remains. |
| P8-080 | PENDING | `qa_tester` | P8-030, P8-040, P8-050 | P8-B | `tests/performance/**`, `tests/abuse/**`, `docs/verification/phase-8.md` | Run documented synthetic workload across tenants/connections, hot/cold catalog, provider/target failures, pool saturation, schema refresh concurrency, large cells, cancellation and rate abuse; record P50/P95/P99, throughput, resources, target connections and limit effectiveness, including <250 ms P95 API overhead and <100 ms P95 cached schema lookup targets. |
| P8-090 | PENDING | `qa_tester` | P8-030, P8-070 | P8-B | `evals/**`, `docs/verification/phase-8.md` | Run full semantic/adversarial suites: >=80% semantic execution accuracy, >=95% unsafe rejection, zero write/admin escape, <5% valid policy false positives where dataset supports the metric; document dataset/version and deviations. |
| P8-100 | PENDING | `devops_engineer` | P8-050, P8-060 | P8-B | `docs/runbooks/**`, `docs/verification/phase-8.md` | Complete and exercise backup/restore, credential exposure/rotation, unsafe-query kill switch, control-plane recovery, preview-key rotation, audit reconciliation, deployment/rollback and graceful shutdown; verify tenant data and secret references after restore without exposing secrets. |
| P8-110 | PENDING | `qa_tester` | P8-080, P8-090, P8-100 | P8-QA | `docs/verification/phase-8.md` | Trace every PRD P0/P1 requirement, release gate, review finding and threat checklist to implementation/test evidence; run PostgreSQL/MySQL, tenant, rotation, restore, load, browser and MCP suites; measure connection-to-first-answer against the five-minute target and identify any exception explicitly. |
| P8-120 | PENDING | `backend_code_reviewer` | P8-030, P8-040, P8-070 | P8-REVIEW | `apps/api/**`, `.github/workflows/**`, `evals/**` | Review limits, retention, privacy telemetry, secure defaults, scans, failure modes and tests; no blocking security/backend finding remains. |
| P8-130 | PENDING | `frontend_code_reviewer` | P8-110 | P8-REVIEW | `apps/web/**` | Review production web configuration/CSP/identity/storage/accessibility regressions; mark not applicable only if no web diff. |
| P8-140 | PENDING | `devops_engineer` | P8-050, P8-060, P8-070, P8-100 | P8-REVIEW | `deploy/**`, `.github/workflows/**`, `docs/runbooks/**` | Self-review manifests/images/migrations/external secrets/TLS/egress/resources/rollback and scan evidence against deploy instructions; unresolved risks documented. |
| P8-150 | PENDING | primary agent | P8-110, P8-120, P8-130, P8-140 | SERIAL-P8 | `docs/**`, `PLANS.md` | Run all common/full/release checks, inspect final diff for secrets/unsafe SQL/tenant mistakes/scope, record SLO/eval/security evidence, operational/migration/rollback impacts, exceptions and release retrospective. Product owner acceptance remains required. |
| P8-160 | PENDING | `sanity_runner` | P8-150 | SANITY-P8 | `docs/verification/phase-8.md` | After all automated release checks pass, exercise live REST, web and MCP critical journeys plus auth denial, clarification/rejection, caps/timeouts/truncation, rotation, audit and health. Record functional evidence and defects; no static review. |
| P8-165 | PENDING | `prd_researcher` | P8-160 | SERIAL-P8 | `docs/verification/phase-8.md`, `PLANS.md` | Present traceability, measurements, exceptions and sanity evidence for product-owner acceptance of the core journey and release candidate; record the explicit decision without inventing approval. |
| P8-170 | PENDING | primary agent | P8-165 | SERIAL-P8 | `PLANS.md`, `docs/verification/phase-8.md` | Resolve defects and rerun affected/full checks; open `CP-8` only after earlier PRs are merged. Merge only with product approval and no critical/high unresolved vulnerability absent a documented accepted exception. |

## 12. Critical path and explicitly parallel work

Critical path:

```text
PR #3 merge -> CP-DESIGN -> CP-1
-> Phase 2 product/secret/destination decisions + contract -> Phase 2 shared domain/migration
-> CP-2 -> Phase 3 worker/catalog design + contract -> catalog integration -> CP-3
-> Phase 4 provider/parser decisions + contract -> QuerySentry -> corpus -> CP-4
-> Phase 5 retention/preview/audit decisions + contract -> migrations -> execution orchestration
-> REST core sanity -> CP-5 -> web core sanity -> CP-6
-> MCP decision/contract/parity/sanity -> CP-7
-> hosting decision -> production delivery + evidence -> release sanity -> CP-8
```

Dependency-independent parallel groups are explicitly the tasks sharing these
labels: `P1-A`, `P1-REVIEW`; `P2-DECIDE`, `P2-A`, `P2-B`, `P2-REVIEW`;
`P3-A`, `P3-REVIEW`; `P4-DECIDE`, `P4-A`, `P4-REVIEW`; `P5-DECIDE`,
`P5-A`, `P5-B`; `P6-A`, `P6-REVIEW`; and `P8-A`, `P8-B`, `P8-REVIEW`.
Tasks in different groups are not automatically parallel: their exact
dependencies still control readiness. Phase integration, shared contracts,
migrations, interfaces, generated clients, `PLANS.md`, verification, and PR
creation always remain serialized.
