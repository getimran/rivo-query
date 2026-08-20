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
