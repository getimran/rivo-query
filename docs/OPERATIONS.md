# Operations and runbook outline

## Health model

- Liveness: process event loop/JVM is alive; no downstream calls.
- Readiness: control database and mandatory internal components are usable.
- Target databases: reported per connection and do not determine global readiness.
- LLM provider: dependency status metric; temporary failure does not make connection management unavailable.

Phase 1 exposes only `GET /api/v1/health/live` and
`GET /api/v1/health/ready`. Liveness performs no downstream call. Readiness
performs a bounded lookup of the migrated control-database heartbeat and returns
a safe RFC 9457 `CONTROL_DATABASE_UNAVAILABLE` response when that dependency is
unusable. Both probes return or echo a validated `X-Request-Id`; they expose no
database, schema, version, credential, or driver detail. Actuator web endpoints
remain unexposed.

For the local runbook, start PostgreSQL with the commands in
`deploy/local/README.md`, load the synthetic `.env` values, and run:

```bash
./mvnw -f apps/api/pom.xml spring-boot:run
npm --prefix apps/web run dev
```

Stop the API and web processes normally, then preserve local database data with:

```bash
docker-compose --env-file .env -f deploy/local/compose.yaml down
```

## Required dashboards

- Request rate/errors/latency by operation
- Generation latency and provider error category
- QuerySentry rejection category and rate
- Execution latency, timeout, cancellation, and truncation
- Per-tenant concurrency/rate-limit pressure without high-cardinality labels in general metrics
- Target pool count/usage/eviction
- Control database pool and migration state
- Secret-provider latency/errors
- Audit write failures

## Alerts

- Elevated authentication/authorization failures
- Any audit persistence failure on execute path
- Sustained target pool saturation
- Generation/validation/execute P95 above SLO
- Query timeout spike or cancellation failure
- Secret-provider failures
- Unexpected increase in policy bypass regression failures
- Control database storage/replication/backup problems

## Incident: suspected credential exposure

1. Disable affected connection and revoke/rotate target credentials.
2. Invalidate RivoQuery secret versions and close pools.
3. Restrict egress if active misuse is possible.
4. Preserve sanitized audit/telemetry and determine exposure scope.
5. Rotate model/provider and platform credentials if implicated.
6. Notify owners under the incident policy.
7. Add regression controls and document the root cause.

## Incident: unsafe query accepted

1. Disable execution globally or for affected dialect/policy.
2. Preserve SQL fingerprint, policy/model versions, catalog fingerprint, and audit timeline.
3. Verify the database role prevented modification.
4. Patch QuerySentry and add a permanent adversarial regression case.
5. Re-run the complete safety corpus before re-enabling.

## Rotation

- Use the connection rotation operation.
- Test the new secret before switching.
- Atomically update secret reference/version.
- Drain and evict old pools.
- Confirm an audited query works with the new version.
- Revoke old credential at the target.

## Backup and restore

- Back up control PostgreSQL with encryption and point-in-time recovery.
- Secret material follows the secret provider's backup/replication strategy.
- Restore tests must confirm tenant metadata, policies, snapshots, and audit references while keeping secrets resolvable.
- Target databases are customer-owned and not backed up by RivoQuery.

## Deployment safety

- Run migrations as a controlled release step.
- Use rolling deployment with readiness and graceful shutdown.
- Account for multiplied target pool limits across replicas.
- Use external secrets and network policy.
- Roll back application independently; never roll back destructive migrations without an explicit tested plan.

## Control database: migration-owner and runtime-role separation

The control database's append-only audit table
(`connection_lifecycle_audit_events`, see `V2__create_identity_and_connection_control_plane.sql`)
is only append-only for a runtime role that is never that table's *owner* -
an owning role always retains `ALTER TABLE` rights (including
`ALTER TABLE ... DISABLE TRIGGER`) that no `REVOKE` can strip. Every table
Flyway creates is owned by whichever role Flyway itself connects as. This
requires two distinct Postgres roles in every environment, not just local
development:

- **Migration owner** (`spring.flyway.user`/`password`): runs Flyway and
  therefore owns every table it creates. Never used by the running
  application.
- **Runtime application role** (`spring.datasource.username`/`password`):
  what the running application's own connection pool authenticates as.
  Granted only the specific table-level privileges `V2` lists (and denied
  `UPDATE`/`DELETE`/`TRUNCATE` on the audit table); never a table owner.

`com.knowimran.rivoquery.platform.MigrationOwnerSeparationValidator` refuses
to start the application if `spring.flyway.user` is unset, blank, or equal
to `spring.datasource.username`, so this split can never silently degrade
to "same role" through a missing environment variable. That check is
configuration-shaped, not privilege-shaped: it cannot detect every way two
differently-named roles could still end up with equivalent ownership
rights granted independently of this migration.

**The runtime role's password must never appear as a Flyway-substituted SQL
literal.** `V2` deliberately never creates or sets the password for the
runtime role - only grants privileges to it - and fails the migration
loudly if that role does not already exist by the time it runs. Flyway
performs plain-text placeholder substitution before sending SQL to
Postgres; embedding a password there would put the plaintext value into SQL
text Postgres receives and may log (`log_statement=all`, pgaudit,
`pg_stat_statements`), and a password containing a single quote could break
out of the intended string literal and execute arbitrary SQL as the
migration-owner role.

**Staging and production must therefore provision the runtime role
out-of-band, before the application's migration ever runs**, consistent
with `deploy/AGENTS.md`'s "use external secrets and restrictive workload
identities" and "never embed credentials in manifests/examples":

- Provision the role and its password via the same external
  secrets/IaC pipeline that manages the control database itself (for
  example, a Terraform/Ansible/cloud-managed-Postgres role resource, or a
  one-time release-job step that reads the password from the secret
  store and issues `CREATE ROLE ... LOGIN PASSWORD ...` directly against
  Postgres) - never by having the application or its own Flyway
  connection create it.
- Rotate the runtime role's password through that same external path
  (`ALTER ROLE ... PASSWORD ...`), then update the secret the application
  reads for `spring.datasource.password`; do not route rotation through a
  Flyway migration.
- Run this provisioning step strictly before the migration release step
  above, and treat "role does not exist" as an expected, actionable
  migration failure (`V2` raises a clear exception) rather than a
  surprise.

`deploy/local/postgres-initdb/01-create-runtime-role.sh` is the local-only
equivalent, using PostgreSQL's own `docker-entrypoint-initdb.d` mechanism
instead of external IaC; see `deploy/local/README.md`.
