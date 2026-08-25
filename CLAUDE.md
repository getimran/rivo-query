# RivoQuery — Claude Code entrypoint

@AGENTS.md

## Claude Code specifics

This project was bootstrapped with the Codex IDE extension (`.codex/agents/`, `prompts/*.md`, `docs/CODEX_WORKFLOW.md`). The instructions above (`AGENTS.md`) are provider-neutral and authoritative for both agents. This section adds the Claude Code equivalents so the same workflow runs here.

### Subagents (`.claude/agents/`)

Same roles and scopes as `.codex/agents/`, invoked with the `Agent` tool instead of Codex subagents:

| Use for | Subagent | Edits files? |
|---|---|---|
| Product/PRD scope questions | `prd-researcher` | No |
| Cross-cutting architecture, ADR needs | `solution-architect` | No |
| Backend API/contract/domain design | `api-architect` | No |
| Independent design challenge before build | `solution-design-reviewer` | No |
| Turning an approved design into tasks | `task-planner` | `PLANS.md` only |
| Backend implementation (`apps/api`) | `backend-engineer` | Yes |
| Frontend implementation (`apps/web`) | `frontend-engineer` | Yes |
| CI/CD, containers, `deploy/` | `devops-engineer` | Yes |
| Backend diff review | `backend-code-reviewer` | No |
| Frontend diff review | `frontend-code-reviewer` | No |
| Risk-based test design/execution | `qa-tester` | Test code only |
| Post-implementation live sanity check | `sanity-runner` | No (never a pre-push gate) |

Same rules as `AGENTS.md` § Planning and review: the primary agent orchestrates and stays accountable; never run `backend-engineer` and `frontend-engineer` (or any two file-writing agents) on overlapping files in parallel; use `sanity-runner` only once a runnable product exists and normal automated tests already pass.

### Phase commands (`.claude/commands/`)

Equivalent to `prompts/00-orientation.md` and `prompts/01-07-*.md`; run in order, one phase at a time, per `PLANS.md`:

`/orient`, `/phase1`, `/phase2`, `/phase3`, `/phase4`, `/phase5`, `/phase6`, `/phase7-8`

## Commands

### Backend (`apps/api`)

- Full build + unit + integration tests: `./mvnw -f apps/api/pom.xml verify`
- Single test class: `./mvnw -f apps/api/pom.xml test -Dtest=ClassName`
- Single test method: `./mvnw -f apps/api/pom.xml test -Dtest=ClassName#methodName`
- Package-scoped: `./mvnw -f apps/api/pom.xml test -Dtest='com.knowimran.rivoquery.platform.secrets.**'`
- Fast compile check without tests: `./mvnw -f apps/api/pom.xml -o compile`
- Java 21 is required. If not on `PATH`, Homebrew installs it unlinked at `/opt/homebrew/opt/openjdk@21`: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21 && export PATH="$JAVA_HOME/bin:$PATH"`.
- Integration tests need Testcontainers/Docker. On Colima: `export DOCKER_HOST=unix:///path/to/colima/docker.sock` and `export TESTCONTAINERS_RYUK_DISABLED=true` (or `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock` per the README).

### Frontend (`apps/web`)

- `npm --prefix apps/web run lint`
- `npm --prefix apps/web run test` — vitest with coverage; single file: `npm --prefix apps/web exec vitest run path/to/File.test.tsx`
- `npm --prefix apps/web run test:watch`
- `npm --prefix apps/web run build`
- `npm --prefix apps/web run api:generate` — regenerates the typed OpenAPI client at `apps/web/src/api/generated/rivoquery.ts` from `apps/api/src/main/resources/openapi/rivoquery-v1.yaml`. Run after any backend contract change. `api:check` verifies it's not stale without writing.

### Local stack

```bash
cp .env.example .env
docker-compose --env-file .env -f deploy/local/compose.yaml up -d --wait
set -a; . ./.env; set +a
./mvnw -f apps/api/pom.xml spring-boot:run
```

Health probes: `GET /api/v1/health/live`, `GET /api/v1/health/ready` (no Actuator endpoint is exposed).

## Architecture

Modular monolith (Java 21 / Spring Boot) under `com.knowimran.rivoquery`, with package-level dependency direction enforced by `ArchitectureTest` (ArchUnit) — a boundary violation fails the build, not just a lint warning.

- `identity/` — actor/tenant context, permission matrix, membership repository port.
- `connection/` — the connection aggregate (`DatabaseConnection`, its state machine) and application ports: `application/secret/` (`SecretCapabilityAuthority` — the *only* class allowed to mint a secret grant; `SecretStore` port), `application/destination/` (`DestinationCapabilityAuthority` — the *only* class allowed to mint a validated destination; `DestinationPolicy` port, the SSRF/DNS-rebinding defense), `connector/` (`DatabaseConnector` SPI), `pool/` (`TargetPoolManager` contract), `application/lifecycle/` (the create/list/get/update/disable/delete/test/rotate-secret saga orchestrating all of the above).
- `catalog/`, `generation/`, `querypolicy/`, `execution/`, `audit/` — module boundaries scaffolded for Phase 3+ (schema catalog, NLQ generation, QuerySentry SQL validation, bounded execution); mostly empty until those phases land.
- `platform/` — concrete adapters: `persistence/` (JDBC repositories), `secrets/` (local AES-GCM `SecretStore` adapter + a documented production-port stub), `destination/` (DNS resolution, IP-range classification, pool revalidation), `connector/` (PostgreSQL/MySQL trusted JDBC URL/property builders).
- `interfaceapi/` — REST controllers; an architecture rule forbids depending on `platform/*` implementations directly, only on application ports.
- `interfacemcp/` — MCP tool adapters; must call the same application use cases as REST, never a separate authorization path.

The `*CapabilityAuthority` pattern recurs by design: exactly one class per capability (secrets, destinations) is allowed to construct the grant/capability object, enforced by ArchUnit rules in `ArchitectureTest` plus dedicated boundary tests (e.g. `SecretStoreEntryPointBoundaryTest`). When adding a new consumer of `SecretStore` or `DestinationPolicy`, go through the authority class — calling the adapter directly will fail the architecture tests.

The control-plane schema is Flyway-migrated (`apps/api/src/main/resources/db/migration/`) with a deliberate migration-owner/runtime-role split: the application's own datasource role has table-level privileges only (no `ALTER`/`DISABLE TRIGGER`), enforced fail-closed at startup by `MigrationOwnerSeparationValidator`, which refuses to boot if `spring.flyway.user` is unset or equals the datasource username. Local dev provisions the runtime role out-of-band via `deploy/local/postgres-initdb/`, never through Flyway itself — see `docs/OPERATIONS.md` for the staging/production equivalent.

`apps/web` is React 19 + strict TypeScript, using a generated (never hand-written) OpenAPI client — regenerate with `npm run api:generate` after any backend contract change.

### Keeping this in sync

If you change agent scope, add a phase, or add a non-negotiable rule, update both the Codex artifact (`.codex/agents/*.toml` or `prompts/*.md`) and its Claude Code counterpart (`.claude/agents/*.md` or `.claude/commands/*.md`). Durable rules belong in `AGENTS.md` (imported above), not duplicated here.
