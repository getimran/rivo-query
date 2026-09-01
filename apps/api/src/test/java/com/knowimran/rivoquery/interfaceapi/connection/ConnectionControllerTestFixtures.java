package com.knowimran.rivoquery.interfaceapi.connection;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.DurableOperation;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.destination.DestinationOperations;
import com.knowimran.rivoquery.connection.application.destination.DestinationPolicy;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.lifecycle.CanonicalIdGenerator;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionLifecycleService;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionQueryService;
import com.knowimran.rivoquery.connection.application.lifecycle.SecretOperationAuthorizationRegistry;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.secret.SecretLease;
import com.knowimran.rivoquery.connection.application.secret.SecretMaterialConsumer;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;
import com.knowimran.rivoquery.connection.connector.ConnectorOption;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.connection.connector.DatabaseConnector;
import com.knowimran.rivoquery.connection.connector.DatabaseConnectorSelector;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hand-written (no Mockito) fakes for {@code ConnectionLifecycleService}'s
 * full dependency graph, needed to exercise {@link ConnectionController}
 * end to end. This deliberately duplicates (rather than reuses) the
 * structurally similar fakes in {@code
 * connection.application.lifecycle.LifecycleTestFixtures}: that class is
 * package-private and lives under {@code connection/application/lifecycle/},
 * which this task's own instructions forbid modifying (even to widen a
 * test fixture's visibility) - see docs/IMPLEMENTATION_TASKS.md P2-130
 * evidence.
 */
final class ConnectionControllerTestFixtures {
    private ConnectionControllerTestFixtures() {}

    static ConnectionLifecycleService lifecycleService(
            FakeConnectionRepository connections, FakeDurableOperationRepository operations,
            FakeConnectionAuditPort audit, FakeSecretStore secretStore, FakeDestinationPolicy destinationPolicy,
            FakeDatabaseConnector connector, FakeTargetPoolManager pool, Clock clock) {
        var authorizations = new SecretOperationAuthorizationRegistry(clock, Duration.ofMinutes(5));
        SecretOperations secretOperations = new SecretCapabilityAuthority(
                connections, authorizations, secretStore, () -> "nonce-fixture", clock, Duration.ofMinutes(5));
        DestinationOperations destinationOperations =
                new DestinationCapabilityAuthority(destinationPolicy, () -> "destination-nonce-fixture", clock);
        DatabaseConnectorSelector connectorSelector = kind -> connector;
        return new ConnectionLifecycleService(
                connections, operations, audit, secretOperations, authorizations, destinationOperations,
                () -> "policy-v1", connectorSelector, pool, (workspaceId, connectionId, connectionVersion, operationId) -> {},
                new SequentialCanonicalIdGenerator(), clock);
    }

    static ConnectionQueryService queryService(FakeConnectionRepository connections, FakeConnectionPermissionRepository permissions) {
        return new ConnectionQueryService(connections, permissions);
    }

    /**
     * Deterministic, distinct ids so a test can assert on the exact
     * connection/operation id a create call produces - {@code
     * AtomicInteger} (not a bare {@code int ++}) so {@link
     * ConnectionControllerTest}'s concurrent-create race test can call this
     * from two real threads without the two racers ever colliding on the
     * same generated id, which would defeat that test's purpose (each racer
     * must get its own distinct connectionId, exactly like production's
     * {@code SecureRandomCanonicalIdGenerator}).
     */
    static final class SequentialCanonicalIdGenerator implements CanonicalIdGenerator {
        private final java.util.concurrent.atomic.AtomicInteger connectionSeed = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger operationSeed = new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger snapshotSeed = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public ConnectionId newConnectionId() {
            return new ConnectionId("con_" + String.format("%026d", connectionSeed.incrementAndGet()).replace('0', 'A'));
        }

        @Override
        public com.knowimran.rivoquery.connection.OperationId newOperationId() {
            return new com.knowimran.rivoquery.connection.OperationId(
                    "op_" + String.format("%026d", operationSeed.incrementAndGet()).replace('0', 'B'));
        }

        @Override
        public String newSnapshotId() {
            return "snap_" + String.format("%026d", snapshotSeed.incrementAndGet()).replace('0', 'S');
        }

        @Override
        public com.knowimran.rivoquery.connection.QueryRequestId newQueryRequestId() {
            return new com.knowimran.rivoquery.connection.QueryRequestId(
                    "qry_" + String.format("%026d", connectionSeed.incrementAndGet()).replace('0', 'Q'));
        }
    }

    static final class FakeConnectionRepository implements ConnectionRepository {
        // ConcurrentHashMap (not HashMap) so ConnectionControllerTest's
        // concurrent-create race test - two real threads racing under the
        // same Idempotency-Key, each minting its own distinct connectionId
        // via SequentialCanonicalIdGenerator above - never corrupts this
        // map; the two racers never collide on the same key, so plain
        // thread-safe put/get is sufficient without needing this fake to
        // model true atomic compare-and-swap.
        private final Map<String, DatabaseConnection> byKey = new java.util.concurrent.ConcurrentHashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId) {
            return workspaceId.value() + "/" + connectionId.value();
        }

        DatabaseConnection require(WorkspaceId workspaceId, ConnectionId connectionId) {
            DatabaseConnection connection = byKey.get(key(workspaceId, connectionId));
            if (connection == null) {
                throw new IllegalStateException("test setup: connection not seeded");
            }
            return connection;
        }

        @Override
        public Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId) {
            return Optional.ofNullable(byKey.get(key(workspaceId, connectionId)));
        }

        @Override
        public void insert(DatabaseConnection connection) {
            byKey.put(key(connection.workspaceId(), connection.id()), connection);
        }

        @Override
        public boolean compareAndSet(DatabaseConnection connection, ConnectionVersion expectedVersion) {
            String mapKey = key(connection.workspaceId(), connection.id());
            DatabaseConnection stored = byKey.get(mapKey);
            if (stored == null) {
                return false;
            }
            if (!stored.version().equals(expectedVersion)) {
                throw new StaleConnectionVersionException();
            }
            byKey.put(mapKey, connection);
            return true;
        }

        @Override
        public List<DatabaseConnection> findPage(WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit) {
            return byKey.values().stream()
                    .filter(c -> c.workspaceId().equals(workspaceId))
                    .filter(c -> c.state().isPublicState())
                    .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
                    .filter(c -> afterConnectionId.map(after -> c.id().value().compareTo(after.value()) > 0).orElse(true))
                    .limit(limit)
                    .toList();
        }

        /**
         * Seeds an ACTIVE connection directly (bypassing {@code
         * ConnectionLifecycleService#create}, which a REST-layer test has
         * no need to drive for scenarios that only need a pre-existing
         * connection - update/disable/delete/get/permissions).
         */
        DatabaseConnection seedActive(WorkspaceId workspaceId, ConnectionId connectionId) {
            var reserved = DatabaseConnection.reserve(workspaceId, connectionId, DatabaseKind.POSTGRESQL);
            insert(reserved);
            var versionReserved = reserved.reserveNextSecretVersion(reserved.version());
            compareAndSet(versionReserved, reserved.version());
            var binding = new SecretBinding(
                    workspaceId, connectionId, com.knowimran.rivoquery.connection.application.secret.SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
            var reference = new SecretReference("local-fake", "opaqueaaaaaaaaaaaaaaaaa", 1, "fingerprintaaaaaaaaaaaaa");
            var secret = new BoundSecretReference(binding, reference);
            var pendingValidation = versionReserved.attachCurrentSecret(versionReserved.version(), secret);
            var active = pendingValidation.transition(pendingValidation.version(), com.knowimran.rivoquery.connection.ConnectionState.ACTIVE);
            compareAndSet(active, versionReserved.version());
            return require(workspaceId, connectionId);
        }
    }

    static final class FakeDurableOperationRepository implements DurableOperationRepository {
        // ConcurrentHashMap: see FakeConnectionRepository's identical comment.
        private final Map<String, DurableOperation> byKey = new java.util.concurrent.ConcurrentHashMap<>();

        private static String key(WorkspaceId workspaceId, com.knowimran.rivoquery.connection.OperationId operationId) {
            return workspaceId.value() + "/" + operationId.value();
        }

        @Override
        public Optional<DurableOperation> findByWorkspaceAndId(WorkspaceId workspaceId, com.knowimran.rivoquery.connection.OperationId operationId) {
            return Optional.ofNullable(byKey.get(key(workspaceId, operationId)));
        }

        @Override
        public void save(DurableOperation operation) {
            byKey.put(key(operation.workspaceId(), operation.id()), operation);
        }
    }

    static final class FakeConnectionAuditPort implements ConnectionAuditPort {
        private final List<Event> events = new CopyOnWriteArrayList<>();

        @Override
        public void append(Event event) {
            events.add(event);
        }
    }

    static final class FakeSecretStore implements SecretStore {
        // AtomicLong so ConnectionControllerTest's concurrent-create race
        // test (two real threads) never corrupts this counter.
        private final java.util.concurrent.atomic.AtomicLong nextVersionSeed = new java.util.concurrent.atomic.AtomicLong(1);

        @Override
        public StagedSecretReference stage(
                SecretBinding binding, DestructibleSecretMaterial material,
                SecretCapabilityAuthority.SecretMutationGrant grant, Deadline deadline) {
            var reference = new SecretReference(
                    "local-fake", "opaque" + nextVersionSeed.getAndIncrement() + "aaaaaaaaaaaaaaaa", binding.version(),
                    "fingerprint" + "aaaaaaaaaaaaaaaaaaaaaaaa");
            var bound = new BoundSecretReference(binding, reference);
            material.consume(bytes -> null);
            return new StagedSecretReference(bound);
        }

        @Override
        public SecretLease resolve(SecretReference reference, SecretCapabilityAuthority.SecretAccessGrant grant, Deadline deadline) {
            return new SecretLease() {
                @Override
                public void consume(SecretMaterialConsumer consumer) {
                    consumer.use("fallback-secret".getBytes());
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void revoke(SecretReference reference, SecretCapabilityAuthority.SecretRevocationGrant grant, Deadline deadline) {
            // No-op: nothing this fixture's tests assert on.
        }
    }

    /**
     * Always assesses every destination as allowed, timestamped against the
     * same {@link Clock} the rest of the fixture graph uses - {@code
     * DestinationCapabilityAuthority.validate}/{@code requireValid} both
     * reject an assessment whose {@code resolvedAt} is after the caller's
     * own clock (a real, security-relevant staleness check this fake must
     * not accidentally trip by using the wall clock instead).
     */
    static final class FakeDestinationPolicy implements DestinationPolicy {
        private final Clock clock;

        FakeDestinationPolicy(Clock clock) {
            this.clock = clock;
        }

        @Override
        public DestinationAssessment assess(DestinationRequest request) {
            var now = clock.instant();
            return new DestinationAssessment(
                    request.endpoint().host().toLowerCase(java.util.Locale.ROOT),
                    request.endpoint().host(),
                    request.endpoint().port(),
                    Set.of(new com.knowimran.rivoquery.connection.application.destination.VettedAddress(
                            com.knowimran.rivoquery.connection.application.destination.AddressFamily.IPV4,
                            new byte[] {10, 0, 0, 1})),
                    "fingerprint-v1", "policy-v1", now, now.plusSeconds(300));
        }
    }

    static final class FakeDatabaseConnector implements DatabaseConnector {
        boolean fail;

        @Override
        public ConnectorCapabilities capabilities() {
            return new ConnectorCapabilities(DatabaseKind.POSTGRESQL, Set.of(ConnectorOption.TCP_KEEP_ALIVE));
        }

        @Override
        public ConnectorResult test(
                DestinationCapabilityAuthority.ValidatedDestination destination, byte[] borrowedSecretMaterial, Deadline deadline) {
            if (fail) {
                return new ConnectorResult(false, ConnectorOutcomeCode.CONNECTION_TEST_FAILED);
            }
            return new ConnectorResult(true, ConnectorOutcomeCode.CONNECTION_TEST_SUCCEEDED);
        }
    }

    static final class FakeTargetPoolManager implements TargetPoolManager {
        @Override
        public void stopNewBorrowsAndDrain(PoolKey key, PoolDrainReason reason, Deadline deadline) {
            // No-op: nothing this fixture's tests assert on.
        }
    }

    static final class FakeConnectionPermissionRepository implements ConnectionPermissionRepository {
        private final Map<String, ConnectionPermissionGrant> grants = new HashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return workspaceId.value() + "/" + connectionId.value() + "/" + membershipId.value();
        }

        void put(ConnectionPermissionGrant grant) {
            grants.put(key(grant.workspaceId(), grant.connectionId(), grant.membershipId()), grant);
        }

        @Override
        public Optional<ConnectionPermissionGrant> find(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return Optional.ofNullable(grants.get(key(workspaceId, connectionId, membershipId)));
        }

        @Override
        public boolean replace(ConnectionPermissionGrant grant, long expectedCollectionVersion) {
            put(grant);
            return true;
        }

        @Override
        public boolean revoke(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId, long expectedCollectionVersion) {
            return grants.remove(key(workspaceId, connectionId, membershipId)) != null;
        }

        @Override
        public List<ConnectionPermissionGrant> findPage(
                WorkspaceId workspaceId, ConnectionId connectionId, Optional<MembershipId> afterMembershipId, int limit) {
            return List.of();
        }

        @Override
        public long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId) {
            return 0L;
        }
    }
}
