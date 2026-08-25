---
description: Phase 1 — bootstrap the platform (apps/api, apps/web, deploy/local skeletons)
---

Work from the repository root. Read all applicable `AGENTS.md`, the PRD, architecture, ADR-0001/0002/0003/0012, test strategy, and Phase 1 in `PLANS.md`.

First inspect the workspace and propose a concrete file-level plan. Ask only questions that materially block Phase 1. Then implement only Phase 1:

- Java 21 Spring Boot API under `apps/api` using Maven Wrapper from the repository root.
- Modules/packages matching the architecture, with automated boundary tests.
- Control PostgreSQL persistence and an initial migration for a minimal platform heartbeat/version table; do not prematurely create all product tables.
- Health/readiness endpoints, RFC 9457 baseline, request ID filter, sanitized structured logging, metrics baseline.
- React + strict TypeScript web shell under `apps/web` with generated or generation-ready OpenAPI client workflow.
- Local Compose dependencies under `deploy/local`, using synthetic credentials from environment/example files only.
- Unit/build/integration smoke tests and CI-ready commands.

Constraints:

- Pin reviewed versions and explain them in the final summary.
- Do not implement database connections, LLM calls, SQL generation, or query execution yet.
- Do not commit secrets or generated local data.
- Update `PLANS.md` Progress, Surprises, Decisions, and Outcome with evidence.

Before finishing, run all Phase 1 checks available in the environment and review the diff against `docs/CODE_REVIEW.md`. Report checks not run without claiming they passed.
