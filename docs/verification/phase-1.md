# Phase 1 verification

## Verdict

**PASS** on 2026-08-20 (Asia/Dubai) for `feat/phase-1-platform-bootstrap`.
No blocking, high, medium, or low-severity functional defects remained after the
review fixes. This record contains no database password, generated request ID,
raw database row, or full runtime log.

## Candidate and environment

- Java: OpenJDK 21.0.12.1
- Maven Wrapper: 3.9.15
- Node.js: 26.7.0
- npm: 11.19.0
- Docker client/server: 29.7.2/29.5.2 through Colima
- Docker Compose: 5.5.0
- Local dependency: the digest-pinned PostgreSQL 17.6 Compose service with the
  synthetic values in `.env.example`

Testcontainers required `DOCKER_HOST` to point at the Colima socket and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`. An initial Maven
attempt without that environment could not discover Docker; the corrected final
run below passed.

## Automated verification

The final backend command was:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
  PATH=/opt/homebrew/opt/openjdk@21/bin:/opt/homebrew/bin:/usr/bin:/bin \
  DOCKER_HOST=unix:///Users/imran/.colima/default/docker.sock \
  TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock \
  ./mvnw -f apps/api/pom.xml verify
```

Result: PASS. OpenAPI validation and generated server compilation passed, as did
26 unit/contract/architecture tests and five PostgreSQL Testcontainers
integration checks. The integration checks covered migration from empty,
repeat startup, live/ready behavior, hidden actuator endpoints, safe errors,
request IDs, low-cardinality metrics, database outage, and sanitized logs.

The final frontend commands were:

```bash
npm --prefix apps/web ci
npm --prefix apps/web run install-scripts:check
npm --prefix apps/web run api:check
npm --prefix apps/web run lint
npm --prefix apps/web run test
npm --prefix apps/web run build
```

Result: PASS. Strict install-script policy allowed only the two reviewed exact
packages and reported no unreviewed lifecycle scripts. The generated client had
no drift. Three install-policy tests and 14 Vitest component/client tests passed.
Measured application coverage was 100% statements, functions, and lines and
93.18% branches. The tests include keyboard behavior and axe accessibility
checks. The strict TypeScript/Vite production build passed. A final npm registry
audit at `--audit-level=low` reported zero vulnerabilities.

Compose validation and inspection used:

```bash
docker-compose --env-file .env.example -f deploy/local/compose.yaml config --quiet
docker-compose --env-file .env.example -f deploy/local/compose.yaml up -d --wait
docker-compose --env-file .env.example -f deploy/local/compose.yaml ps
```

Result: PASS. PostgreSQL was healthy and published only on
`127.0.0.1:5432`. A read-only inspection found only
`flyway_schema_history` and `platform_heartbeat` in `public`; the singleton
heartbeat existed at schema version 1.

## Live functional evidence

The packaged Java 21 JAR was started on port 8080 with only the synthetic local
datasource environment. The Vite shell was started on `127.0.0.1:5173`.
Requests used bounded `curl --max-time` calls.

- `GET /api/v1/health/live`: 200 `{"status":"UP"}` without a database query.
- `GET /api/v1/health/ready`: 200 `{"status":"UP"}` with the migrated
  heartbeat present.
- A valid `X-Request-Id` was echoed. Missing, whitespace-invalid, and duplicate
  request-ID headers were each replaced with a value matching the documented
  pattern; duplicate caller values were not trusted.
- Unknown route, wrong method, and hidden `/actuator/health` returned safe
  RFC 9457 responses with 404, 405, and 404 respectively. Each response used
  `application/problem+json`, a stable code, and identical header/body request
  IDs.
- `Accept: application/xml` on liveness returned the reviewed safe 406 problem
  (`NOT_ACCEPTABLE`) in 0.003 seconds. It contained no framework exception,
  driver detail, stack trace, credential, or connection URL.
- With only PostgreSQL stopped and its volume preserved, readiness returned the
  safe `CONTROL_DATABASE_UNAVAILABLE` 503 in 2.034 seconds. Liveness remained
  200 in 0.006 seconds. The 503 header/body request IDs matched and the response
  exposed no SQL exception, driver detail, stack trace, credential, or JDBC URL.
- Restarting the same PostgreSQL container restored readiness to 200 in 0.010
  seconds.
- The Vite shell returned 200. Its proxy preserved typed live/ready 200
  responses, the safe database-down 503, and readiness recovery. With the API
  stopped, the proxy produced an error response; the tested health adapter maps
  network/nonconforming failures to the accessible generic error state.
- Source and production-build scans found no `localStorage`, `sessionStorage`,
  `indexedDB`, or cookie persistence and no control-database credential or JDBC
  URL. Phase 1 has no secret/result entry feature to persist.
- Observed request logs were structured and limited to request ID, status, and
  outcome for application requests. No password, access token, raw row, SQL
  exception, or stack trace was observed.

No browser automation harness is included in Phase 1, so visual state changes
were not driven in a real browser. The same ready/not-ready/error states were
covered by typed-client/component tests (including accessibility), while live
HTTP checks exercised the actual shell and proxy transitions.

## Restored state

The temporary Java and Vite processes were stopped cleanly. The Compose
PostgreSQL service was restored to `healthy` with its named volume preserved;
no volume or migrated data was deleted.

## Review and remaining external evidence

Independent backend/platform and frontend re-reviews returned SAFE after their
findings were fixed and regression-tested. QA returned PASS. Primary integration
repeated the clean backend/frontend suites, Compose validation, diff hygiene,
and targeted secret scans. Draft CP-1 is PR #5. Hosted run `32363020594` failed
before execution: backend verification, frontend checks, Compose validation, and
the full-history Gitleaks scan each have zero steps and the same GitHub annotation
that recent account payments failed or the spending limit must be increased.
These hosted checks are blocked external evidence and are not claimed as passed.
On 2026-08-20 the product owner deferred hosted Actions until the complete
product is ready for deployment and accepted the full local suites, independent
reviews, and QA recorded above as the development checkpoint gate. The workflow
is manually dispatched and all hosted release checks, including full-history
Gitleaks, remain mandatory Phase 8 deployment-readiness evidence. They are not a
CP-1 merge dependency.

## Local checkpoint re-verification after hosted-CI deferral

After changing the hosted workflow to manual release-only dispatch, the complete
development gate was rerun locally on 2026-08-20:

- Java 21 Maven `verify` passed 26 unit/contract/architecture tests and five
  digest-pinned PostgreSQL Testcontainers integration tests with no failures,
  errors, or skips.
- Clean `npm ci`, reviewed lifecycle-script policy, generated-client drift,
  lint, three policy tests, 14 UI/API tests, coverage, production build, and
  registry audit passed; the audit reported zero vulnerabilities.
- Local Compose configuration validation passed.
- Workflow parsing confirmed `workflow_dispatch` is the sole trigger and the
  backend, frontend, Compose, and secret-scan release jobs remain present.
- `git diff --check` passed. No application behavior, API contract, migration,
  dependency, or deployment topology changed in the CI-policy update.
