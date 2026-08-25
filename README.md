# RivoQuery

RivoQuery is a universal, security-first AI database retrieval agent. A user registers a database connection, asks a question in natural language, and receives a bounded result set plus the generated SQL and an explanation.

Phase 1 provides the runnable platform foundation: a Java 21 Spring Boot API,
a strict React/TypeScript web shell, a generated OpenAPI client, and local
PostgreSQL through Compose. Product workflows are delivered incrementally in
the later phases described in `PLANS.md`.

## Product promise

> Connect any supported database. Ask naturally. Get reliable answers.

Initial target databases:

- PostgreSQL
- MySQL

Planned interfaces:

- Web console for people
- REST API for applications
- MCP server for AI agents

## Start here

Read these files in order:

1. `docs/PRD.md`
2. `docs/ARCHITECTURE.md`
3. `docs/SECURITY_AND_THREAT_MODEL.md`
4. `docs/API_AND_MCP_CONTRACTS.md`
5. `PLANS.md`
6. `AGENTS.md`

Then use `PLANS.md` and `docs/IMPLEMENTATION_TASKS.md` to select the next
dependency-ready task.

## Run the Phase 1 foundation

Prerequisites are Java 21, Node.js 22.12 or another version allowed by
`apps/web/package.json`, npm 11.19.x, and a Docker-compatible runtime with
Compose.

```bash
cp .env.example .env
docker-compose --env-file .env -f deploy/local/compose.yaml up -d --wait

set -a
. ./.env
set +a
./mvnw -f apps/api/pom.xml spring-boot:run
```

In another terminal:

```bash
npm --prefix apps/web ci
npm --prefix apps/web run dev
```

Open `http://127.0.0.1:5173`. The web development server proxies `/api` to the
Spring API at `127.0.0.1:8080`. Public Phase 1 probes are
`GET /api/v1/health/live` and `GET /api/v1/health/ready`; no Actuator endpoint
is exposed. See `deploy/local/README.md` for stop/reset guidance.

## Verify

```bash
./mvnw -f apps/api/pom.xml verify
npm --prefix apps/web run api:check
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
docker-compose --env-file .env.example -f deploy/local/compose.yaml config --quiet
```

Backend integration tests require a Docker-compatible runtime. Colima users may
need to set `DOCKER_HOST` to its socket and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.

## Repository layout

```text
apps/
  api/                 Spring Boot application
  web/                 React + TypeScript web console
docs/
  adrs/                Architecture Decision Records
deploy/                Containers, Kubernetes and operations
prompts/               Phase-specific Codex prompts
.vscode/                Shared editor tasks and settings
```

## Working with Codex in VS Code

Open the repository folder in VS Code, open the Codex sidebar, and start with the phase prompt rather than asking Codex to build the entire product in one turn. Review and commit a checkpoint after each phase.

Codex automatically reads repository `AGENTS.md` instructions, and more-specific instructions can be placed closer to the code they govern. The workspace uses that layering model deliberately. See the official guidance for [AGENTS.md](https://learn.chatgpt.com/docs/agent-configuration/agents-md), the [Codex IDE extension](https://learn.chatgpt.com/docs/codex/ide), and [Codex best practices](https://learn.chatgpt.com/guides/best-practices).

## Working with Claude Code

Claude Code reads the same `AGENTS.md` layering through `CLAUDE.md` (`@AGENTS.md` imports at each level), plus project subagents under `.claude/agents/` and phase slash commands under `.claude/commands/` (`/orient`, `/phase1` … `/phase7-8`). See `docs/CLAUDE_WORKFLOW.md` for the full workflow, equivalent to the Codex flow above.

## Current status

- Product scope: defined
- Architecture: defined
- Security invariants: defined
- ADR baseline: defined
- Phase 1 platform bootstrap: implemented and locally verified
- Phase 2 connection control plane: not started

The authoritative progress log is `PLANS.md`.
