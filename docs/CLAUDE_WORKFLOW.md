# Claude Code workflow

This mirrors `docs/CODEX_WORKFLOW.md` for the Claude Code CLI/IDE extension. Same phases, same gates, same non-negotiables from `AGENTS.md`; different tool surface.

## 1. Prepare the machine

Install:

- Git
- Java 21 JDK
- Docker Desktop or another Testcontainers-compatible runtime
- Node.js current LTS
- Claude Code (CLI or IDE extension)

Open the repository root. Claude Code reads `CLAUDE.md` automatically and combines it with more-specific `CLAUDE.md` files closer to the working directory (`apps/api/CLAUDE.md`, `apps/web/CLAUDE.md`, `deploy/CLAUDE.md`). Each of those imports the sibling `AGENTS.md` via `@AGENTS.md` so there is one source of truth; do not duplicate rules into `CLAUDE.md` directly — add durable rules to the nearest `AGENTS.md` instead.

## 2. Verify repository guidance

Start a new session from the repository root and run `/orient`. It asks Claude to summarize the active `AGENTS.md` instructions, the product goal, security invariants, and current phase in `PLANS.md` without changing files.

## 3. Implement one phase at a time

Use the phase commands in order:

1. `/phase1` — bootstrap the platform
2. `/phase2` — connection control plane
3. `/phase3` — schema catalog
4. `/phase4` — generation and QuerySentry
5. `/phase5` — execution and audit
6. `/phase6` — web console
7. `/phase7-8` — MCP and hardening

Each command asks Claude to inspect the repository, restate the exact scope, update the plan, implement, test, and self-review. Do not combine phases until the previous acceptance criteria pass.

### Efficient context and delegation

Use the `Agent` tool with the project's subagents in `.claude/agents/` — see the routing table in `CLAUDE.md`. The same discipline as `AGENTS.md` § Token-efficient work applies:

- Search and read narrowly; reuse established context instead of repeatedly loading unchanged files or output.
- Delegate only bounded specialist or independent-review work, with minimal sufficient context and a concise return contract.
- Prefer parallel read-heavy investigation (multiple review/architecture subagents at once); serialize overlapping implementation work — never run two file-writing subagents against the same files concurrently.
- Use narrow checks during iteration and the complete required verification when the change is stable.
- Treat quality, security, test coverage, and truthful reporting as fixed requirements; optimize tokens around them, never through them.

## 4. Checkpoints

Before each phase:

- Create and switch to a focused feature branch; never implement directly on `main`.
- Commit the current working baseline.
- Ensure the tree has no unrelated uncommitted changes.
- Confirm Docker and required runtimes are available.

After each phase:

- Inspect the diff.
- Run `backend-code-reviewer` and/or `frontend-code-reviewer` against the uncommitted changes; they follow `docs/CODE_REVIEW.md`.
- Run the changed application locally when it is runnable and exercise the affected path.
- Add or update meaningful unit tests for each behavior change.
- Run the full verification commands (see `AGENTS.md` § Verification).
- After the product implementation is runnable and normal automated verification passes, run `sanity-runner` against live APIs and critical product functionality. Do not invoke it for documentation, configuration, planning, static review, or as a generic pre-push check.
- Update `PLANS.md` Outcomes.
- Resolve blocking findings, then commit and push only after review and verification pass.
- Use the applicable local suites, independent review, and QA as the development PR gate. Keep GitHub Actions manually dispatched and run its complete release-readiness workflow only after the full product is ready for deployment.
- Confirm every earlier pull request targeting `main` is merged before creating another pull request.
- Open a pull request from the feature branch to `main`; do not merge changes directly into `main`.
- Keep durable requirements, decisions, plans, verification evidence, operational impacts, and outcomes in version-controlled repository documentation.

## 5. Good follow-up prompts

- "Explain which security invariant each new test protects."
- "Review this diff for tenant-boundary bypasses and secret leakage." (or: run `frontend-code-reviewer` / `backend-code-reviewer`)
- "Compare implementation behavior with ADR-0006 and list gaps."
- "Run the PostgreSQL and MySQL connector contract tests and diagnose only; do not weaken assertions."
- "Update PLANS.md with evidence from commands actually run."

## 6. Rules for corrections

When Claude repeats a mistake, correct the implementation and add a short, concrete rule to the closest `AGENTS.md` (not `CLAUDE.md` — it only imports `AGENTS.md`). Keep it practical; add rules based on recurring friction, not speculative future cases.

## 7. Environment and secrets

- Copy `.env.example` to `.env` locally only after the implementation introduces the variables.
- Never paste real database credentials into a Claude Code session or documentation.
- Prefer synthetic local databases for development.
- Store local secrets outside Git; use external secret/KMS integration for deployment.
- If a test requires a real third-party model, use a dedicated low-privilege key and synthetic schema.
