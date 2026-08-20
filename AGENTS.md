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

## Token-efficient work

- Optimize token use without reducing correctness, security, test depth, or acceptance-criteria coverage. Token savings never justify skipping required analysis, implementation, review, or verification.
- Start with targeted `rg` searches and the smallest relevant files or line ranges. Do not repeatedly reread unchanged documents, diffs, logs, or tool output already established in the current task.
- Load only the ADRs, references, and skills relevant to the changed boundary. Summarize durable findings once and reuse them in later handoffs.
- Delegate only concrete, bounded work that benefits from specialist expertise or independent verification. Avoid spawning agents for trivial tasks or duplicating the same exploration across agents.
- Parallelize independent read-heavy work when it reduces elapsed time, but avoid concurrent edits to overlapping files. Give sub-agents the minimum sufficient context, exact scope, expected output, and stop conditions.
- Require concise agent returns: decisions or findings, evidence with file references, commands and results, blockers, and recommended next action. Keep raw logs out of the primary context unless needed to diagnose a failure.
- During iteration, run the narrowest relevant checks and reuse valid results; run the complete required verification once the change is stable. Never claim or omit a check based on token cost.
- Keep plans, commentary, and final reports concise and non-duplicative while preserving material risks, assumptions, skipped checks, and outcomes.

## Planning and review

- Delegate substantial work to the project-scoped custom agents in `.codex/agents/`:
  - Use `prd_researcher` when scope may change product requirements.
  - Use `solution_architect`, `api_architect`, and `solution_design_reviewer` before implementation when architecture or contracts materially change.
  - Use `task_planner` to turn approved designs into ordered tasks and acceptance checks.
  - Use `backend_engineer` and `frontend_engineer` for their respective implementation areas; avoid parallel edits to overlapping files.
  - Use `backend_code_reviewer` and `frontend_code_reviewer` for independent diff review.
  - Use `qa_tester` for risk-based functional and regression testing and `devops_engineer` for delivery and operational changes.
  - Use `sanity_runner` only after a runnable product implementation exists and normal automated tests pass, to exercise APIs and critical functionality end to end. Never use it for documentation, configuration, planning, static review, or as a generic pre-push gate.
- The primary agent owns orchestration, resolves conflicting recommendations, and remains accountable for the final result.
- Never make project changes directly on `main`; create a focused feature branch first.
- Before pushing, run the changed application locally when it is runnable and execute the narrowest relevant checks plus the full verification suite.
- Add or update meaningful unit tests for every behavior change; do not treat build-only or superficial tests as sufficient coverage.
- Before pushing, complete the required local verification, independent code review, and QA appropriate to the change; resolve blocking findings or document any unavailable check precisely.
- Treat the applicable local suites, independent review, and QA as the development pull-request verification gate. GitHub Actions is manually dispatched only when the complete product is ready for final deployment verification; do not require hosted Actions for intermediate phase PRs.
- Push only after the applicable review and verification succeed, then open a pull request targeting `main`. Do not merge directly into `main`.
- Before creating a new pull request, verify that every earlier pull request targeting `main` has been merged. Keep at most one open change pull request at a time unless the user explicitly approves an exception.
- Record durable requirements, decisions, plans, architecture, verification evidence, operational impacts, and outcomes in version-controlled repository files; do not leave essential project context only in chat, agent output, or pull-request descriptions.
- Keep changes small enough to review.
- Record material architectural choices as ADRs.
- Update `PLANS.md` progress, surprises, decisions, and outcomes while working.
- Follow `docs/CODE_REVIEW.md` for self-review and PR review.
