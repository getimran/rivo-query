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

## External encryption key injection (local secret store)

The local `SecretStore` adapter (`platform.secrets.LocalAesGcmSecretStore`,
ADR-0008's development-only adapter - explicitly _not_ a production
recommendation) persists only ciphertext envelopes and requires every key to
arrive from runtime environment variables; it derives nothing from anything
checked into the repository and fails closed at startup when configuration is
missing or malformed. There is no default key, and no code path generates a
fallback key.

Required environment variables (all read via `System.getenv()` by
`ConnectionLifecycleWiringConfiguration` / `KeyEncryptionKeyRing` /
`SecretReferenceKey`):

| Variable | Purpose | Format / constraints |
| --- | --- | --- |
| `RIVOQUERY_SECRETS_PROVIDER` | Safety rail: enables the file-backed adapter only when exactly `local` | Must be `local`; any other value or unset fails closed at startup (`ConnectionLifecycleWiringConfiguration.java:81-94`) |
| `RIVOQUERY_SECRETS_LOCAL_KEYS` | AES-256-GCM key-encryption-key (KEK) ring | Comma-separated `keyId:base64key` entries; `keyId` matches `[A-Za-z0-9_-]{1,64}`; each key decodes to exactly 32 bytes (256 bits). Never blank. (`KeyEncryptionKeyRing.java:19`, `22-23`, `45-58`, `70-96`) |
| `RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID` | Which KEK new data-encryption keys are wrapped with | Must name a key present in `RIVOQUERY_SECRETS_LOCAL_KEYS` (`KeyEncryptionKeyRing.java:20`, `54-56`) |
| `RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY` | Separate key used only to derive create-only envelope IDs (HMAC-SHA256); never encrypts secret material | Base64; at least 32 bytes after decoding (>= 256 bits) (`SecretReferenceKey.java:29`, `40-46`, `53-63`) |
| `RIVOQUERY_SECRETS_LOCAL_STORAGE_DIR` | Directory holding the ciphertext envelopes | Any path outside the repository and outside the control-plane PostgreSQL data directory (`ConnectionLifecycleWiringConfiguration.java:90`) |

There are three distinct keys/materials: the KEK ring (protects envelope data
keys), the current-KEK identifier (which KEK wraps new data keys), and the
reference key (keyed digest for idempotent create-only IDs). None of them may
be the same as, or derived from, the control database credentials. Generate
cryptographically random keys, for example:

```bash
openssl rand -base64 32
```

Both KEK and reference keys must decode to at least 256 bits; keep KEKs at
exactly 256 bits. Example ring with two KEKs and the reference key set:

```
RIVOQUERY_SECRETS_LOCAL_KEYS=v1:<base64-256-bit>,v2:<base64-256-bit>
RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID=v2
RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY=<base64-of-at-least-32-bytes>
RIVOQUERY_SECRETS_LOCAL_STORAGE_DIR=/var/tmp/rivoquery-local-secrets
```

Key bytes are never logged or exposed: `KeyEncryptionKeyRing.toString()`,
`WrappedDataKey.toString()`, and `SecretReferenceKey.toString()` are all
redacted (`KeyEncryptionKeyRing.java:112-122`, `SecretReferenceKey.java:99-102`).

### Rotation constraints for the local KEK ring

- The KEK ring is the rotation boundary. `currentKeyId` is what any _new_
  envelope is wrapped with (`KeyEncryptionKeyRing.wrap`, line 98-102); existing
  envelopes carry their own `keyId` and are unwrapped with whichever KEK that
  ID names (`unwrap`, lines 104-110). This allows two-phase rotation: add the
  new KEK while keeping the old KEK present, flip `CURRENT_KEY_ID`, and only
  later retire the old KEK.
- Removing a KEK before every envelope wrapped with it has been re-wrapped (or
  revoked) makes those envelopes permanently undecryptable. There is no
  automatic re-wrap job and no plaintext recovery path once a KEK is gone.
- The reference key is not part of the KEK ring and must be rotated separately
  and with care: changing it changes the derived opaque envelope IDs, so all
  persisted references must be migrated together or resolved before the change
  (`SecretReferenceKey.java` class Javadoc).
- `RIVOQUERY_SECRETS_PROVIDER!=local` currently means "no usable secret
  provider exists". There is no managed KMS/Vault branch yet; in a
  production-shaped deployment this fails closed rather than silently falling
  back to local disk. Production key injection (KMS/external broker) is future
  work (ADR-0008, `UnimplementedManagedSecretProvider`).

Operational note: the local adapter's crash-recovery helpers
`cleanupOrphanedTemporaryFiles()` and `cleanupRevokedTombstones()`
(`LocalAesGcmSecretStore.java:83-96`) are not yet scheduled by any framework
component; without a scheduled cleanup they only run on explicit invocation.
The adapter also enforces owner-only (0700/0600) POSIX permissions on its
storage directory and rejects a pre-existing directory that is not
owner-only (`LocalSecretStorage.java:42-64`, `273-300`).

## Outbound egress and private-target policy

Destination enforcement is ADR-0013, implemented by
`platform.destination.DestinationEnforcementPolicy`. Operators must understand
three layers: in-process resolution/classification, connector socket pinning,
and independent infrastructure egress - a target passes all of them or the
connection is rejected.

### In-process resolution and classification

Every validation performs a fresh, uncached full CNAME-plus-A/AAAA resolution
and then classifies _every_ returned address (`DestinationEnforcementPolicy.
assess`, `DnsjavaDestinationResolver`). Fail-closed outcomes include empty,
malformed, truncated, timed-out, indeterminate, or deeper-than-bound CNAME
chains (`DnsjavaDestinationResolver.java:91-107`, `70-74`, `137-139`). Any
prohibited address rejects the whole destination - it never silently picks an
allowed answer from a mixed set (`DestinationEnforcementPolicy.java:82-97`).

The non-overridable deny set (`AddressRangeClassifier`) always includes
unspecified, loopback, link-local, multicast, broadcast, documentation,
benchmarking, reserved/special-use, and cloud-metadata ranges
(`AddressRangeClassifier.java:20-50`). RFC 1918, carrier-grade NAT, and IPv6
unique-local are classified private, and are allowed only through an
operator-authored allowlist (below). IPv4-mapped and other IPv6 transition
forms are classified by their embedded IPv4 address (`AddressRangeClassifier.
java:79-118`).

Operator configuration defaults (under `rivoquery.destination.*`; see
`DestinationPolicyProperties.java`):

- `dns-timeout`: 2s
- `max-cname-chain-depth`: 8
- `max-capability-ttl-seconds`: 60 (a validated destination's upper bound)
- `fallback-ttl-seconds`: 5 (used when the resolver cannot vouch for a
  trustworthy TTL)
- `resolver-address`: optional; when unset the JVM/OS-configured resolver is
  used. Restrict to operator-approved resolvers in production.
- `policy-version`: `destination-policy-v1` (change when the operator policy
  meaningfully changes; it binds into pool keys and validation)
- `private-allowlist`: see below

There is also a hard per-query ceiling (`MAX_PER_QUERY_TIMEOUT`, 5s) inside
the resolver independent of the configured deadline
(`DnsjavaDestinationResolver.java:46`, `165-167`).

### Private-target allowlist

Private (RFC 1918 / CGNAT / ULA) destinations are denied by default and are
permitted only by an operator-authored `rivoquery.destination.private-allowlist`
entry - never from tenant or request input. Each rule binds connector kind,
canonical hostname (exact lower-case name or a suffix beginning with `.`),
port range, and destination CIDR list; _every_ resolved address and the
name/port must match the _same_ rule for the destination to pass
(`OperatorPrivateAllowlist.java`, `PrivateDestinationRule.java`,
`DestinationPolicyProperties.PrivateRule.java:52-66`). A rule can never widen
the non-overridable deny set - link-local and metadata ranges stay prohibited
regardless of any allowlist entry.

### Connector socket pinning and TLS

PostgreSQL and MySQL connectors do not pass a hostname to the driver to
resolve; they pin the socket to the vetted address set only (decoded from the
vetted addresses) and keep the logical JDBC and TLS server name as the
original hostname, so TLS verifies the certificate against the original host -
never the selected IP - while the dial cannot be redirected by a later DNS
answer (ADR-0013; `platform.connector.postgresql`, `platform.connector.mysql`).
When the configured host is an IP literal, verification requires a matching IP
subject alternative name.

### Infrastructure egress operators must allow

Application validation is not the only barrier (ADR-0013 "Independent
infrastructure enforcement"). Operators must:

- Deny workload egress by default; explicitly allow only what is needed.
- Allow DNS/53 outbound only to the approved resolver addresses, and pin
  `rivoquery.destination.resolver-address` to those same resolvers.
- Independently deny special-use and cloud-metadata ranges at the firewall or
  egress gateway, mirroring `AddressRangeClassifier`'s prohibited set.
- Allow target-database egress only to operator-approved public/private CIDRs
  and database ports, and keep model/identity/secret-provider/control-plane
  egress rules separate from target rules so a target rule cannot reach an
  internal service plane.
- Reconcile private allowlist entries with the infrastructure rules: both the
  application (`rivoquery.destination.private-allowlist`) and the firewall must
  permit a private target; disagreement fails closed.
- In a multi-replica deployment, apply the same egress rules to every replica;
  target connections are process-local and scale with replica count (see pool
  budgets below).

## Target pool budgets

As of Phase 2 there is no concrete target-connection-pool implementation yet:
`TargetPoolManager` is an interface
(`connection/pool/TargetPoolManager.java`) and the wired bean is
`NoOpTargetPoolManager` (`ConnectionLifecycleWiringConfiguration.java:122-125`),
so there is currently nothing to configure and `stopNewBorrowsAndDrain` is
intentionally a no-op. Pool creation, capacity budgets, just-in-time lease, and
idle eviction/drain/shutdown are owned by P5-100
(`NoOpTargetPoolManager.java:8-18`).

What already exists and what operators must plan for:

- The drain contract and its reasons are defined: `PoolKey` (workspace,
  connection ID/version, secret version, destination-policy version) and
  `PoolDrainReason` (`CONFIGURATION_CHANGED`, `SECRET_ROTATED`,
  `POLICY_CHANGED`, `DISABLED`, `DELETING`, `SHUTDOWN`)
  (`connection/pool/PoolKey.java`, `connection/pool/PoolDrainReason.java`).
- The lifecycle saga already issues drain calls at the correct
  lifecycle points (disable, delete - and rotation once it is unblocked), so
  the no-op can be replaced without call-site changes
  (`ConnectionLifecycleService.java:451-474`, `537-540`, `784-795`).
- Pool revalidation contract already exists
  (`platform/destination/pool/PoolDestinationRevalidator.java`,
  `DestinationPolicyPoolRevalidator.java`): a pool's destination binding is
  re-resolved before reuse after its validation expires; a changed or
  prohibited answer quarantines and drains the pool and requires
  re-registration under a new pool key.

Required properties once P5-100 lands (recorded now so budgets are not
invented later): small bounded per-target pools, a global connection budget,
idle eviction, and per-tenant concurrency/queue/rate limits
(`docs/SECURITY_AND_THREAT_MODEL.md:110-118`). Because target pools are
process-local, _total_ target-database connections multiply across replicas;
deployment must bound global and per-connection totals against replica count
(`docs/ARCHITECTURE.md:247`, `docs/OPERATIONS.md` "Deployment safety" above).
Until P5-100, treat "no pool implementation" as "no unbounded pooling risk
yet", not as evidence that budgets are in place.

## Rotation and deletion operations

Rotation (`rotate-secret`) and deletion (`delete`) are orchestrated by
`ConnectionLifecycleService`. Their exact Phase 2 availability differs:

- **Delete** is fully implemented as a tombstoned two-phase operation:
  transition to `DELETING` (tombstone), revoke the current secret, drain the
  pool with `PoolDrainReason.DELETING`, then transition to `DELETED`
  (`ConnectionLifecycleService.java:479-563`). A revoke failure after the
  tombstone leaves the connection inert in `DELETING` and reports an
  `INDETERMINATE` durable operation - deliberately not auto-retried at the
  caller; a future reconciliation pass retries the revoke without repeating
  the tombstone transition (`524-535`). A concurrency race after the
  tombstone is likewise `INDETERMINATE` (`552-557`).
- **Rotate-secret is structurally blocked in this phase**: because the open
  endpoint of a connection is not yet persisted (persisting it is a future V3
  migration), `rotateSecret` (and `test`) authenticate, authorize, and
  version-check, then fail closed before touching any secret or connector
  (`ConnectionLifecycleService.java:76-91`, `371-395`). The intended rotation
  path (already documented in the ADR and the existing "Rotation" section
  above) is: stage a new secret version, test it, atomically switch the
  reference under optimistic versioning, drain the old pool with
  `PoolDrainReason.SECRET_ROTATED`, confirm an audited query works, then revoke
  the old secret; secret-material handling for that path is the same
  external-KEK discipline described under "External encryption key injection".

Failure semantics common to both operations: every mutation writes a pre-action
audit event before any side effect; a terminal-audit failure after an external
effect surfaces as `IndeterminateLifecycleOutcomeException` rather than a false
success or a false "nothing happened"
(`ConnectionLifecycleService.java:56-63`). Secret-store access, staging, and
revocation are gated by an exactly-matching pre-registered authorization that
only the lifecycle saga can register
(`SecretOperationAuthorizationRegistry`, `SecretOperations`). Reconcile
`INDETERMINATE` outcomes via `durable_operations`, never by blindly repeating
the mutation.

## Safe local synthetic configuration

The Phase 2 application needs three groups of environment variables, none of
which may contain real credentials or key material in `.env`/source control.
`deploy/local/compose.yaml` wires every value through `${...:?...}` references
and embeds no secret itself; `postgres-initdb` provisions the restricted
runtime role from those variables (never through Flyway).

1. **Control database** (already in `.env.example`): `SPRING_FLYWAY_USER` /
   `SPRING_FLYWAY_PASSWORD` (migration-owner bootstrap role) and
   `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` (restricted
   runtime role). `compose.yaml` maps the runtime pair to
   `RIVOQUERY_RUNTIME_DB_USER`/`RIVOQUERY_RUNTIME_DB_PASSWORD`, and
   `postgres-initdb/01-create-runtime-role.sh` creates/alters that role on
   first empty-volume initialization before V2 runs.
2. **Secret store** (this phase's addition, external and never committed):
   `RIVOQUERY_SECRETS_PROVIDER=local`, `RIVOQUERY_SECRETS_LOCAL_KEYS`,
   `RIVOQUERY_SECRETS_LOCAL_CURRENT_KEY_ID`,
   `RIVOQUERY_SECRETS_LOCAL_REFERENCE_KEY`,
   `RIVOQUERY_SECRETS_LOCAL_STORAGE_DIR` (each described above; generate the
   key material with `openssl rand -base64 32` and keep it out of this
   directory). `RIVOQUERY_SECRETS_LOCAL_STORAGE_DIR` must point outside the
   repository and outside the Postgres data volume.
3. **Local auth** (pre-existing, out of this task's scope but needed to boot):
   the JWT authenticator requires `rivoquery.auth.jwt.issuer`,
   `rivoquery.auth.jwt.audience`, and
   `rivoquery.auth.jwt.hmac-secret-base64`
   (`JwtAuthenticationProperties.java`,
   `JwtAuthenticationConfiguration.java`).

Start order for a clean local stack:

```bash
cp .env.example .env         # add the secret-store + local-auth vars above
docker-compose --env-file .env -f deploy/local/compose.yaml up -d --wait
set -a; . ./.env; set +a
./mvnw -f apps/api/pom.xml spring-boot:run
```

Reset with `down --volumes` only for a deliberately destroyed local control
database (it deletes the named volume and runs migrations again from empty);
see `deploy/local/README.md`.
