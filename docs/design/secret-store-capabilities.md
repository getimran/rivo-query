# Secret-store capability design

Status: Implementation baseline

Related decisions: ADR-0008, ADR-0009, ADR-0013

## 1. Boundary and authority

`SecretStore` stores target-database credential material and returns opaque
references. The control-plane connection row is authoritative for which
reference/version is current. Possession of an opaque reference is not authority
to resolve it.

Exactly one production application component,
`connection.application.secret.SecretCapabilityAuthority`, may mint secret
grants. It authenticates the actor or validates a durable server operation,
authorizes the tenant-scoped action, and reloads the connection through a
tenant-scoped repository before every issuance. It does not trust a transport,
worker, pool, connector, repository record, opaque reference, or caller-supplied
tenant/connection binding as proof of authority.

The authority implements the application-facing `SecretOperations` input port
and is the only production caller of the `SecretStore` output-port methods.
Use cases, `TargetPoolManager`, and cleanup workers call `SecretOperations` with
an actor or durable operation identity; they never receive or mint a grant.

The grant is bound to:

- tenant ID;
- connection ID and optimistic connection version;
- `SecretPurpose.TARGET_DATABASE_CONNECTION`;
- exact opaque secret ID and version from the loaded connection;
- use purpose: `CONNECTION_TEST`, `ROTATION_TEST`, or
  `PHYSICAL_POOL_CONNECTION`;
- actor/service operation ID, issued-at time, absolute deadline, and unguessable
  nonce.

The authority permits `ROTATION_TEST` for a staged version explicitly owned by
the same connection. Operational grants may reference only the current version.
Grants are single-use, short-lived, process-local, redacted, and invalidated by
version mismatch, disable/delete state, expiry, or cancellation. A grant cannot
be widened, copied to a new binding, or exchanged for another grant.

This is the confused-deputy boundary: it prevents a connector, worker, or caller
from using a reference belonging to another tenant/connection or from reusing
database credentials for a different purpose. Public error details never reveal
whether a foreign secret ID exists.

## 2. Port semantics

Domain-facing types are final immutable value objects except for destructible
secret containers. No secret-bearing type implements serialization, structural
`equals`/`hashCode`, or a revealing `toString`.

```java
interface SecretStore {
    StagedSecretReference stage(
        SecretBinding binding,
        DestructibleSecretMaterial material,
        SecretMutationGrant grant,
        Deadline deadline);

    SecretLease resolve(
        SecretReference reference,
        SecretAccessGrant grant,
        Deadline deadline);

    void revoke(
        SecretReference reference,
        SecretRevocationGrant grant,
        Deadline deadline);
}
```

`SecretBinding` contains tenant, connection, secret purpose, and monotonically
increasing version. `SecretReference` contains only provider namespace, opaque
secret ID, version, and a non-sensitive binding fingerprint. It contains no
plaintext, ciphertext, key ID that reveals deployment internals, credential URL,
or user-controlled provider location.

`SecretAccessGrant`, `SecretMutationGrant`, and `SecretRevocationGrant` are
sealed nested interfaces of `SecretCapabilityAuthority`. Their sole permitted
implementations are private nested final classes with private constructors in
that authority. They bind the exact operation and secret binding, are
non-serializable and short-lived, and are not interchangeable. Thus staging and
revocation cannot be abused as cross-tenant provider operations even though
neither action releases plaintext.

This is both a language and architecture boundary:

- Java private constructors and sealed permits make every grant implementation
  unconstructable outside `SecretCapabilityAuthority`.
- Architecture tests require that it is the sole production class invoking
  `SecretStore.stage`, `resolve`, or `revoke`.
- Architecture tests forbid all other application classes, platform adapters,
  connectors, pool classes, workers, REST/MCP transports, persistence records,
  serializers, and reflection factories from constructing or implementing a
  grant.
- The `platform.secrets` adapter may consume the sealed grant interface only to
  verify its binding while implementing `SecretStore`; it cannot issue one.
- Tests inspect the sealed permitted-subclass list and constructor visibility so
  adding another grant implementation fails the build.

The store validates exact equality among the reference, provider-side binding,
and access/revocation grant before releasing or revoking material. Local AES-GCM
uses the complete binding tuple plus format version as authenticated additional
data; moving ciphertext or changing metadata therefore fails authentication.
Production adapters must provide equivalent binding through authenticated
metadata or a protected binding record, not untrusted tags alone.

All calls take the caller's absolute `Deadline`. Provider retries are bounded,
idempotent where the operation permits, use the remaining time, and fail closed.
There is no list-all, resolve-by-tenant, bulk-decrypt, or raw-provider escape
hatch in application code.

`stage` is create-only and idempotent for the exact binding and mutation
operation ID: a replay returns the same opaque reference, while an attempt to
reuse that operation or binding for different material fails closed. It never
overwrites an existing secret version. `revoke` is idempotent and treats an
already absent exact version as success without broadening its target.

## 3. Local AES-GCM storage and atomicity

The local adapter is a development implementation of the external `SecretStore`
boundary, not another connection-metadata repository. Control PostgreSQL stores
only the opaque `SecretReference`, binding fingerprint, version, lifecycle state,
and durable operation metadata. It does not store local ciphertext, nonce,
wrapped data key, or plaintext in `database_connections` or any other ordinary
control-plane table.

The local adapter stores each ciphertext envelope in a dedicated secret-store
directory/volume configured outside the repository and outside the control
PostgreSQL data directory. Production packaging must not enable this adapter.
The directory is owner-only and each envelope is owner-readable only. It is
excluded from Git, application asset paths, generic log collection, and ordinary
control-plane exports/backups.

For every secret version, the adapter generates a new 256-bit data-encryption key
and 96-bit nonce, encrypts the material with AES-256-GCM, authenticates the full
binding, secret format version, and mutation operation ID as additional data,
and wraps the data key with an externally injected key-encryption key. The
key-encryption key and its rotation set are supplied through runtime secret
configuration and are never stored in the envelope or control database. Envelope
headers contain only format/key version, opaque reference, nonce, wrapped data
key, binding fingerprint, ciphertext, and authentication tag.

Local staging writes a new envelope to a same-directory temporary file, flushes
and synchronizes it, atomically moves it to a create-only opaque final name, and
synchronizes the directory before acknowledging success. It never overwrites a
version. Revocation first makes the exact envelope unavailable to resolution,
then deletes it idempotently. Temporary/incomplete files are never resolvable and
are removed by bounded startup/scheduled cleanup.

There is deliberately no distributed transaction between control PostgreSQL and
a secret provider. Before calling `stage`, the authority commits a durable
secret-operation row containing the mutation operation ID and binding but no
secret material. `stage` is idempotent by that operation ID. The control-plane
reference switch and creation of the old-version cleanup operation commit in one
PostgreSQL transaction. Consequently, a crash leaves either a retryable staged
operation, a current reference, or an exact cleanup target; it never requires
enumerating or decrypting every secret.

For the local adapter, the opaque ID is deterministically derived with a separate
externally supplied reference key from the mutation operation ID and binding, so
an ambiguous retry finds the same create-only envelope without exposing those
values. A production adapter must provide equivalent create-idempotency or a
protected provider-side operation index. If stage outcome remains indeterminate,
reconciliation retries the same operation and never allocates another version.

## 4. Secret material and lease lifecycle

`DestructibleSecretMaterial` copies incoming password/private-key/CA bytes into
owned mutable buffers and clears them when closed. Request-body logging is
disabled before deserialization, and the API mapper closes the input material in
a `finally` block after `stage` completes or fails.

`SecretLease` is `AutoCloseable`, thread-confined, single-consumption, and valid
until the earliest of its short platform maximum, access-grant expiry, provider
lease expiry, operation deadline, or cancellation. It exposes material only to a
bounded callback; it does not return a reusable `String`, array, stream, map, or
properties object. Closing is idempotent and zeroes owned mutable buffers.
Access after consume/close/expiry throws a safe internal exception.

JDBC APIs may force an adapter to create temporary immutable strings. That copy
is made only inside the trusted physical-connection callback, is never retained
in pool configuration, and becomes unreachable immediately after
`Driver.connect` succeeds or fails. This JVM limitation is documented and tested
through ownership/canary checks; it is never used to justify caching plaintext.

Secret-bearing values and provider errors are excluded from logs, metrics,
traces, exception messages, audit payloads, heap/debug dumps in normal operation,
and client responses. Redaction tests use unique canaries across success,
provider failure, timeout, cancellation, and compensation paths.

## 5. Connection and rotation protocol

### Initial creation

1. Reserve the tenant-scoped connection and version under an idempotency key.
2. Stage version 1 using material bound to that connection.
3. Persist only the staged reference/version using optimistic concurrency.
4. Ask `SecretCapabilityAuthority` to test; it internally mints a
   `CONNECTION_TEST` grant, resolves one lease, and invokes a short-lived
   non-pooled physical connection through an ADR-0013 destination capability.
5. Mark the connection `ACTIVE` or `FAILED_VALIDATION`. On first failed
   validation, persist an immutable `secretRetireAt`: 24 hours by default and
   never later than the platform hard maximum of seven days after failure.
   Retries do not extend it. Before that instant only an authorized
   `CONNECTION_TEST` retry may resolve the version; pool/operational grants remain
   forbidden.
6. If reference persistence cannot complete, revoke the orphan using the durable
   compensation operation. Until cleanup succeeds, it is unreachable because no
   current control-plane reference can mint an operational grant.

At `secretRetireAt`, issuance stops even if cleanup is delayed. Reconciliation
atomically detaches the reference from the failed connection, records an exact
revocation operation, and revokes the version. The connection remains
`FAILED_VALIDATION` with expired credential state and requires new secret
material. Rotation-test failures are revoked immediately rather than retained.

### Rotation

1. Reserve one rotation operation and the next monotonically increasing version
   in control PostgreSQL using optimistic concurrency, then stage it without
   changing the current reference.
2. Ask `SecretCapabilityAuthority` to test the staged version; it internally
   uses a `ROTATION_TEST` grant and a non-pooled connection. The staged version
   cannot receive a pool grant.
3. On failed test or cancellation, revoke the staged version; the old current
   version remains unchanged.
4. Atomically compare-and-switch the connection reference/version in control
   PostgreSQL. A race or stale connection version loses and revokes its staged
   version.
5. After a successful switch, prohibit new grants for the old version, prevent
   old-pool borrows, and drain it for a bounded interval.
6. Revoke the old version idempotently. Failure is recorded as a durable cleanup
   operation and retried with backoff; the connection continues using the new
   version and is never rolled back to the old credential automatically.

The same durable compensation model covers crashes between steps. Reconciliation
finds staged references with no matching current connection version and old
references awaiting revocation without needing to enumerate or decrypt secrets.

### Disable and deletion

Disable immediately stops new operational grants and pool borrows, then drains
pools. Delete tombstones the connection, stops all grants, drains pools, and
revokes every known version through durable idempotent cleanup. Metadata and
audit retention do not retain plaintext or make a revoked reference resolvable.

## 6. Reconciliation and bounded cleanup

The secret reconciler calls `SecretOperations`; it cannot access `SecretStore` or
construct grants. It processes durable operation IDs at least once. Before every
external action, `SecretCapabilityAuthority` reloads tenant, connection, current
reference/version, lifecycle state, and operation state. It issues an exact
revocation grant only when the target is staged/orphaned, expired, superseded, or
deleted. A stale cleanup operation can therefore never revoke the currently
active version.

State transitions use optimistic compare-and-set. Duplicate stage/revoke calls
are safe by their exact operation IDs and bindings. Provider timeouts are
ambiguous outcomes, not evidence of failure: reconciliation repeats the same
idempotent operation after bounded exponential backoff with jitter. It never
creates a replacement version, rolls a successful switch back, or releases
plaintext from an unconfirmed reference.

High-frequency retries have configurable attempt and elapsed-time bounds. After
that bound, the operation moves to low-frequency reconciliation and raises an
operator alert; it is not discarded. Access denial begins at supersession,
disable/delete, or `secretRetireAt`, independent of physical deletion success.
Completed operation records are retained only under metadata/audit retention and
contain no ciphertext or plaintext. Local incomplete temporary envelopes and
unreferenced envelopes past the maximum staging/failed-validation window are
quarantined and deleted only after exact operation/binding reconciliation.

## 7. Pool ownership

`TargetPoolManager` owns pool lifecycle; connectors and use cases cannot create
unbounded independent pools. A pool key includes tenant, connection ID/version,
secret version, and destination-policy version. It contains only the opaque
reference/binding and never a `SecretLease`, password, credential-bearing JDBC
URL, or password-bearing `HikariConfig`.

The trusted physical-connection factory requests the following operation from
`SecretOperations` for every new socket; only `SecretCapabilityAuthority`
performs steps 3 and 4:

1. confirm the pool key is still current and borrow capacity is authorized;
2. obtain a fresh ADR-0013 `ValidatedDestination`;
3. internally mint a `PHYSICAL_POOL_CONNECTION` grant for the exact current
   secret version;
4. resolve and consume a short lease inside try-with-resources without returning
   either grant or lease to the pool;
5. create the physical JDBC connection using the vetted-address socket hook;
6. close/zero the lease whether connection creation succeeds or fails.

An open authenticated physical connection may live only within configured pool
idle/max-lifetime and a successfully renewed destination binding. It is never
moved between pool keys. Rotation, disable, deletion, destination/policy change,
secret-provider revocation, or shutdown prevents new borrows and drains/closes
it. In-flight work may complete only within its existing operation deadline and
bounded drain grace.

Connection tests and staged rotation tests never enter a pool. Global, tenant,
and connection pool/physical-connection budgets are acquired before any lease is
resolved, so resource exhaustion cannot cause unnecessary plaintext resolution.

## 8. Failure and compensation matrix

| Failure point | Required outcome |
| --- | --- |
| Stage fails | Close input material; persist no reference; return safe provider failure. |
| Reference persistence fails | Durably schedule idempotent staged-version revoke. |
| Initial test fails | Record `FAILED_VALIDATION` and fixed `secretRetireAt`; close lease/socket; allow test-only retry until expiry. |
| Failed-validation retention expires | Deny all access, detach exact reference, and durably revoke; retry cleanup without extending retention. |
| Rotation test fails | Keep old current version; revoke staged version. |
| Compare-and-switch loses | Keep winner; revoke losing staged version. |
| Crash after switch | Current reference is new; reconciliation drains/revokes old version. |
| Old revoke fails | Keep new current version; retry durable cleanup and alert after threshold. |
| Resolve/binding mismatch | Release no plaintext; emit safe security outcome and alert. |
| Provider unavailable/timeout | Open no connection; never use a stale plaintext cache. |
| Pool creation fails | Close lease and partial socket; release budgets; retain no plaintext config. |
| Delete cleanup fails | Keep tombstone and deny access; retry revocation durably. |

## 9. Required verification

- Cross-tenant, cross-connection, wrong-purpose, stale-version, disabled/deleted,
  expired, replayed, and forged-grant tests all fail before plaintext release.
- Architecture tests prove `SecretCapabilityAuthority` is the sole grant issuer
  and `SecretStore` caller; sealed permits/private constructors reject every
  production, adapter, worker, transport, persistence, and reflection bypass.
- AES-GCM tests prove random nonces, ciphertext-only persistence, binding AAD,
  tamper failure, atomic create-only files, crash/temp-file cleanup, external key
  configuration, separation from control metadata, and key/plaintext absence
  from Git.
- Lease tests prove single consumption, thread confinement, expiry, cancellation,
  idempotent close, zeroization, and cleanup on every exception path.
- Creation/rotation tests inject a failure or crash at each protocol edge and
  prove current-reference safety, orphan compensation, idempotent revoke, and no
  automatic rollback after switch.
- Retention/reconciliation tests prove fixed failed-validation expiry, no retry
  extension, immediate post-expiry denial, stale cleanup protection, ambiguous
  provider retry under the same operation, alerts, and eventual exact deletion.
- Pool tests prove one fresh lease per physical connection, no lease/plaintext in
  pool state, pre-resolution budgets, versioned drain, and non-pooled tests.
- Canary snapshots cover API responses, RFC 9457 details, logs, metrics, traces,
  audits, provider exceptions, connector exceptions, and object rendering.
- Production-adapter contract tests prove binding equivalence and fail-closed
  behavior without pretending a documentation stub is a secure implementation.
