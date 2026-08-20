# RivoQuery agent instructions

## Mission

Build RivoQuery as a production-oriented, security-first AI database retrieval agent. Preserve the product and architecture decisions in `docs/` unless an ADR explicitly supersedes them.

## Read before changing code

1. Read `docs/PRD.md` for expected behavior and scope.
2. Read `docs/ARCHITECTURE.md` for boundaries and dependency direction.
3. Read `docs/SECURITY_AND_THREAT_MODEL.md` before touching connections, credentials, prompts, SQL validation, query execution, logging, or result handling.
4. Read the relevant ADRs in `docs/adrs/`.
5. For multi-step work, update and follow `PLANS.md`.

## Non-negotiable invariants

- Never send database credentials to an LLM.
- Never log credentials, access tokens, connection URLs with secrets, or raw result rows.
- Target database access is read-only and fail-closed.
- Only one validated read query may execute per request.
- Enforce server-side statement timeout and row limits independently of model output.
- Treat the question, schema comments, table names, column names, sampled values, and model output as untrusted input.
- Do not add write-query support without a new ADR and explicit product approval.
- Do not silently weaken authentication, authorization, tenant isolation, TLS, audit, or SQL guardrails for convenience.

## Engineering rules

- Backend: Java 21, Spring Boot, Maven Wrapper, package root `com.knowimran.rivoquery`.
- Frontend: React, TypeScript, strict mode; generated OpenAPI client rather than handwritten duplicated DTOs.
- Prefer a modular monolith with explicit package boundaries over premature microservices.
- Keep LLM, database connector, credential-store, and schema-index implementations behind interfaces.
- Use immutable records/value objects where practical and constructor injection only.
- Return RFC 9457 problem details; never expose driver or stack-trace details to clients.
- Add or update tests with every behavior change.
- Keep public APIs backward compatible or record the breaking decision in an ADR.

## Verification

Run the narrowest relevant checks during iteration, then the full checks before declaring completion:

```bash
./mvnw -f apps/api/pom.xml verify
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
```

Integration tests require a Docker-compatible runtime and Testcontainers. If a required tool is unavailable, report exactly which checks were not run; do not claim success.

## Definition of done

- Acceptance criteria in the active plan are satisfied.
- Security invariants remain covered by tests.
- Relevant unit, integration, contract, and UI checks pass.
- API/schema changes update contracts and documentation.
- Operational impacts and migrations are documented.
- The final diff is reviewed for secret leakage, unsafe SQL paths, tenant-boundary mistakes, and accidental scope expansion.

## Planning and review

- Never make project changes directly on `main`; create a focused feature branch first.
- Before pushing, run the changed application locally when it is runnable and execute the narrowest relevant checks plus the full verification suite.
- Add or update meaningful unit tests for every behavior change; do not treat build-only or superficial tests as sufficient coverage.
- Before pushing, delegate a final independent verification to a `sanity-runner` sub-agent. Give it the changed scope, acceptance criteria, and commands to run; resolve its blocking findings or document any unavailable check precisely.
- Push only after local verification and the `sanity-runner` review succeed, then open a pull request targeting `main`. Do not merge directly into `main`.
- Keep changes small enough to review.
- Record material architectural choices as ADRs.
- Update `PLANS.md` progress, surprises, decisions, and outcomes while working.
- Follow `docs/CODE_REVIEW.md` for self-review and PR review.
