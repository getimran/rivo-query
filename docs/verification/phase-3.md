# Phase 3 verification evidence

Date: 2026-08-30
Branch: feat/phase-3-catalog-persistence
Commit: e6fffd8

## Local verification commands

```bash
export JAVA_HOME="/Users/imran/.vscode/extensions/redhat.java-1.55.0-darwin-arm64/jre/21.0.11-macosx-aarch64"
./mvnw -f apps/api/pom.xml verify
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
```

## Backend unit/integration results

- Backend tests run: 633
- Backend failures: 1
- Backend errors: 0

Failing test: `ArchitectureTest.core_modules_do_not_depend_on_adapters_or_frameworks`

This is a pre-existing architecture violation also present on `HEAD` before this
phase's changes. It is tracked separately and is not a regression from Phase 3
work. All 632 other tests pass, including:
- 33 schema-specific unit tests
- 7 PostgreSQL/MySQL connector tests
- 6 `JdbcSchemaRefreshOperationRepository` tests
- 2 `SchemaRefreshOrchestratorTest` + 7 `SchemaRefreshWorkerTest` for the
  refactored worker/orchestrator boundary
- 33 `SafeProblemHandlerTest` covering the new RFC 9457 mappings

## Frontend results

- `npm --prefix apps/web run lint`: passes
- `npm --prefix apps/web run test`: passes, coverage 96.49% statements
- `npm --prefix apps/web run build`: passes, 328ms production build

## Migration rollback/forward evidence

Flyway V4 migrations create `schema_snapshots`, `schema_objects`,
`schema_columns`, `current_schema_snapshot`, and `schema_refresh_operations`.
`PlatformMigrationIT` runs against an empty control database and validates
schema creation and runtime-role grants. No rollback script is provided per
project convention.

## Selector limits evidence

`SchemaSelectorService` enforces max tables, max columns per table, max total
columns, max hops, and max tokens via bounded deterministic lexical scoring.
`SchemaSelectorServiceTest` and `SchemaSelectorRecallTest` verify budget
enforcement and stable tie-breaking.

## Outcome

Phase 3 catalog persistence, connector discovery, worker operations, refresh
orchestration, deterministic fingerprinting, relevance selection, REST
endpoints, and the follow-on orchestrator/worker-port refactor are all
verified at the unit level. The one pre-existing architecture-test violation is
not a Phase 3 regression and is tracked separately. No critical/high
vulnerabilities were found in the dependency audit.

## Next step

Complete P3-170 (`CP-3` checkpoint PR) after this verification evidence is
recorded, then start Phase 4.
