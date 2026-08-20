# Codex workflow in VS Code

## 1. Prepare the machine

Install:

- Git
- Java 21 JDK
- Docker Desktop or another Testcontainers-compatible runtime
- Node.js current LTS
- VS Code
- Codex IDE extension

Open `RivoQuery.code-workspace`. In VS Code, open the Codex sidebar from its icon or run **Codex: Open Codex Sidebar** from the Command Palette.

## 2. Verify repository guidance

Start a new Codex chat from the repository root and ask:

> Summarize the active AGENTS.md instructions, the product goal, security invariants, and current phase in PLANS.md. Do not change files.

Codex reads `AGENTS.md` automatically and combines repository instructions with more-specific files closer to the working directory. Keep the root file concise and route details into `docs/`, matching the official [AGENTS.md guidance](https://learn.chatgpt.com/docs/agent-configuration/agents-md).

## 3. Implement one phase at a time

Use prompts in order:

1. `prompts/01-bootstrap-platform.md`
2. `prompts/02-connection-control-plane.md`
3. `prompts/03-schema-catalog.md`
4. `prompts/04-generation-and-querysentry.md`
5. `prompts/05-execution-and-audit.md`
6. `prompts/06-web-console.md`
7. `prompts/07-mcp-and-hardening.md`

Each prompt asks Codex to inspect the repository, restate the exact scope, update the plan, implement, test, and self-review. Do not combine phases until the previous acceptance criteria pass.

## 4. Checkpoints

Before each phase:

- Create and switch to a focused feature branch; never implement directly on `main`.
- Commit the current working baseline.
- Ensure the tree has no unrelated uncommitted changes.
- Confirm Docker and required runtimes are available.

After each phase:

- Inspect the diff in VS Code.
- Ask Codex to review uncommitted changes against `docs/CODE_REVIEW.md`.
- Run the changed application locally when it is runnable and exercise the affected path.
- Add or update meaningful unit tests for each behavior change.
- Run the full verification commands.
- Ask Codex to create a `sanity-runner` sub-agent for an independent final verification of the diff, acceptance criteria, local run, unit coverage, and relevant checks.
- Update `PLANS.md` Outcomes.
- Resolve blocking findings, then commit and push only after review and verification pass.
- Confirm every earlier pull request targeting `main` is merged before creating another pull request.
- Open a pull request from the feature branch to `main`; do not merge changes directly into `main`.
- Keep durable requirements, decisions, plans, verification evidence, operational impacts, and outcomes in version-controlled repository documentation.

The official Codex IDE guidance recommends using editor context and Git checkpoints so changes remain reviewable and reversible: [Codex IDE extension](https://learn.chatgpt.com/docs/codex/ide).

## 5. Good follow-up prompts

- “Explain which security invariant each new test protects.”
- “Review this diff for tenant-boundary bypasses and secret leakage.”
- “Compare implementation behavior with ADR-0006 and list gaps.”
- “Run the PostgreSQL and MySQL connector contract tests and diagnose only; do not weaken assertions.”
- “Update PLANS.md with evidence from commands actually run.”

## 6. Rules for corrections

When Codex repeats a mistake, correct the implementation and add a short, concrete rule to the closest `AGENTS.md`. Do not turn `AGENTS.md` into a duplicate architecture document. Official best practices recommend keeping it practical and adding rules based on recurring friction: [Codex best practices](https://learn.chatgpt.com/guides/best-practices).

## 7. Environment and secrets

- Copy `.env.example` to `.env` locally only after the implementation introduces the variables.
- Never paste real database credentials into Codex chat or documentation.
- Prefer synthetic local databases for development.
- Store local secrets outside Git; use external secret/KMS integration for deployment.
- If a test requires a real third-party model, use a dedicated low-privilege key and synthetic schema.
