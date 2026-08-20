# RivoQuery

RivoQuery is a universal, security-first AI database retrieval agent. A user registers a database connection, asks a question in natural language, and receives a bounded result set plus the generated SQL and an explanation.

This repository is currently an implementation-ready planning workspace for Codex in VS Code. The source code will be created in the phases described in `PLANS.md`.

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

Then open `prompts/01-bootstrap-platform.md` and submit it to Codex from the repository root.

## Planned repository layout

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

## Current status

- Product scope: defined
- Architecture: defined
- Security invariants: defined
- ADR baseline: defined
- Implementation: not started

The authoritative progress log is `PLANS.md`.

