package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.OperationId;
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
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretMaterialConsumer;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;
import com.knowimran.rivoquery.connection.connector.ConnectorOption;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.connection.connector.DatabaseConnector;
import com.knowimran.rivoquery.connection.connector.DatabaseConnectorSelector;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.SingleMembershipRepository;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared, hand-written (no Mockito) fakes for the P2-100 lifecycle saga tests. */
final class LifecycleTestFixtures {
    private LifecycleTestFixtures() {}

    static ActorContext administrator(WorkspaceId workspaceId, ActorId actorId) {
        var membership = new CurrentMembership(
                actorId, workspaceId, new MembershipId("mem_" + actorId.value().substring(4)),
                WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        return new ActorContext.Authority(new SingleMembershipRepository(membership))
                .resolve("issuer", "subject", workspaceId);
    }

    static ActorContext member(WorkspaceId workspaceId, ActorId actorId, MembershipId membershipId) {
        var membership = new CurrentMembership(
                actorId, workspaceId, membershipId, WorkspaceRole.MEMBER, MembershipStatus.ACTIVE);
        return new ActorContext.Authority(new SingleMembershipRepository(membership))
                .resolve("issuer", "subject", workspaceId);
    }

    /** In-memory {@link ConnectionRepository} backed by a map; mirrors the real adapter's contract shape. */
    static final class FakeConnectionRepository implements ConnectionRepository {
        private final Map<String, DatabaseConnection> byKey = new HashMap<>();
        private int compareAndSetCallCount;
        private int throwStaleOnCompareAndSetCallNumber = -1;

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId) {
            return workspaceId.value() + "/" + connectionId.value();
        }

        /**
         * Simulates a concurrent version-changing writer racing the saga:
         * the Nth (1-indexed) {@link #compareAndSet} call throws {@link
         * StaleConnectionVersionException} regardless of whether the
         * in-memory version would actually have matched, mirroring {@code
         * JdbcConnectionRepository.compareAndSet}'s real "throws on an
         * actual version mismatch" contract for a race the fake's own
         * single-threaded state can otherwise never reproduce.
         */
        void throwStaleOnCompareAndSetCall(int oneIndexedCallNumber) {
            this.throwStaleOnCompareAndSetCallNumber = oneIndexedCallNumber;
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
            compareAndSetCallCount++;
            if (compareAndSetCallCount == throwStaleOnCompareAndSetCallNumber) {
                throw new StaleConnectionVersionException();
            }
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
        public List<DatabaseConnection> findPage(
                WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit) {
            return byKey.values().stream()
                    .filter(c -> c.workspaceId().equals(workspaceId))
                    .filter(c -> c.state().isPublicState())
                    .sorted((a, b) -> a.id().value().compareTo(b.id().value()))
                    .filter(c -> afterConnectionId.map(after -> c.id().value().compareTo(after.value()) > 0).orElse(true))
                    .limit(limit)
                    .toList();
        }
    }

    /** In-memory {@link DurableOperationRepository}, loosely mirroring the real adapter's terminal-write guard. */
    static final class FakeDurableOperationRepository implements DurableOperationRepository {
        private final Map<String, DurableOperation> byKey = new HashMap<>();

        private static String key(WorkspaceId workspaceId, OperationId operationId) {
            return workspaceId.value() + "/" + operationId.value();
        }

        @Override
        public Optional<DurableOperation> findByWorkspaceAndId(WorkspaceId workspaceId, OperationId operationId) {
            return Optional.ofNullable(byKey.get(key(workspaceId, operationId)));
        }

        @Override
        public void save(DurableOperation operation) {
            byKey.put(key(operation.workspaceId(), operation.id()), operation);
        }
    }

    /** Captures every audit event; can be told to fail the Nth {@link #append} call. */
    static final class FakeConnectionAuditPort implements ConnectionAuditPort {
        private final List<Event> events = new ArrayList<>();
        private int callCount;
        private int failOnCallNumber = -1;

        void failOnCall(int oneIndexedCallNumber) {
            this.failOnCallNumber = oneIndexedCallNumber;
        }

        List<Event> events() {
            return List.copyOf(events);
        }

        @Override
        public void append(Event event) {
            callCount++;
            if (callCount == failOnCallNumber) {
                throw new RuntimeException("simulated audit sink outage");
            }
            events.add(event);
        }
    }

    /** Captures stage/revoke calls and lets tests inject a failure for either. */
    static final class FakeSecretStore implements SecretStore {
        private final List<BoundSecretReference> staged = new ArrayList<>();
        private final List<SecretReference> revoked = new ArrayList<>();
        private final Map<String, byte[]> materialByOpaqueId = new HashMap<>();
        boolean failStage;
        boolean failResolve;
        boolean failRevoke;
        private long nextVersionSeed = 1;

        List<BoundSecretReference> staged() {
            return List.copyOf(staged);
        }

        List<SecretReference> revoked() {
            return List.copyOf(revoked);
        }

        @Override
        public StagedSecretReference stage(
                SecretBinding binding, DestructibleSecretMaterial material,
                SecretCapabilityAuthority.SecretMutationGrant grant, Deadline deadline) {
            if (failStage) {
                throw new RuntimeException("simulated secret storage outage");
            }
            var reference = new SecretReference(
                    "local-fake", "opaque" + (nextVersionSeed++) + "aaaaaaaaaaaaaaaa", binding.version(),
                    "fingerprint" + "aaaaaaaaaaaaaaaaaaaaaaaa");
            var bound = new BoundSecretReference(binding, reference);
            material.consume(bytes -> {
                materialByOpaqueId.put(reference.opaqueId(), bytes.clone());
                return null;
            });
            staged.add(bound);
            return new StagedSecretReference(bound);
        }

        @Override
        public com.knowimran.rivoquery.connection.application.secret.SecretLease resolve(
                SecretReference reference, SecretCapabilityAuthority.SecretAccessGrant grant, Deadline deadline) {
            if (failResolve) {
                throw new RuntimeException("simulated secret storage outage");
            }
            byte[] material = materialByOpaqueId.getOrDefault(reference.opaqueId(), "fallback-secret".getBytes());
            return new com.knowimran.rivoquery.connection.application.secret.SecretLease() {
                @Override
                public void consume(SecretMaterialConsumer consumer) {
                    consumer.use(material);
                }

                @Override
                public void close() {}
            };
        }

        @Override
        public void revoke(
                SecretReference reference, SecretCapabilityAuthority.SecretRevocationGrant grant, Deadline deadline) {
            if (failRevoke) {
                throw new RuntimeException("simulated secret storage outage");
            }
            revoked.add(reference);
        }
    }

    static SecretOperations secretOperations(
            ConnectionRepository connections, DurableSecretAuthorization authorization, SecretStore store, Clock clock) {
        return new SecretCapabilityAuthority(
                connections, authorization, store, () -> "nonce-fixture", clock, java.time.Duration.ofMinutes(5));
    }

    /** Wraps a mutable {@link DestinationPolicy} so a test can flip between success/failure between calls. */
    static final class FakeDestinationPolicy implements DestinationPolicy {
        DestinationAssessment nextAssessment;
        RuntimeException nextFailure;
        int calls;

        @Override
        public DestinationAssessment assess(com.knowimran.rivoquery.connection.application.destination.DestinationRequest request) {
            calls++;
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextAssessment;
        }
    }

    static DestinationOperations destinationOperations(FakeDestinationPolicy policy, Clock clock) {
        return new DestinationCapabilityAuthority(policy, () -> "destination-nonce-fixture", clock);
    }

    static final class FakeDatabaseConnector implements DatabaseConnector {
        ConnectorResult nextResult = new ConnectorResult(true, ConnectorOutcomeCode.CONNECTION_TEST_SUCCEEDED);
        RuntimeException nextFailure;
        int calls;
        byte[] lastMaterialSeen;

        @Override
        public ConnectorCapabilities capabilities() {
            return new ConnectorCapabilities(DatabaseKind.POSTGRESQL, Set.of(ConnectorOption.TCP_KEEP_ALIVE));
        }

        @Override
        public ConnectorResult test(
                DestinationCapabilityAuthority.ValidatedDestination destination, byte[] borrowedSecretMaterial,
                Deadline deadline) {
            calls++;
            lastMaterialSeen = borrowedSecretMaterial.clone();
            if (nextFailure != null) {
                throw nextFailure;
            }
            return nextResult;
        }
    }

    static final class SingleConnectorSelector implements DatabaseConnectorSelector {
        final FakeDatabaseConnector connector;

        SingleConnectorSelector(FakeDatabaseConnector connector) {
            this.connector = connector;
        }

        @Override
        public DatabaseConnector select(DatabaseKind kind) {
            return connector;
        }
    }

    static final class FakeTargetPoolManager implements TargetPoolManager {
        final List<Drain> drains = new ArrayList<>();
        boolean fail;

        @Override
        public void stopNewBorrowsAndDrain(PoolKey key, PoolDrainReason reason, Deadline deadline) {
            if (fail) {
                throw new RuntimeException("simulated pool outage");
            }
            drains.add(new Drain(key, reason));
        }

        record Drain(PoolKey key, PoolDrainReason reason) {}
    }

    static final class FakeConnectionPermissionRepository implements ConnectionPermissionRepository {
        private final Map<String, com.knowimran.rivoquery.connection.ConnectionPermissionGrant> grants = new HashMap<>();

        private static String key(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return workspaceId.value() + "/" + connectionId.value() + "/" + membershipId.value();
        }

        void put(com.knowimran.rivoquery.connection.ConnectionPermissionGrant grant) {
            grants.put(key(grant.workspaceId(), grant.connectionId(), grant.membershipId()), grant);
        }

        @Override
        public Optional<com.knowimran.rivoquery.connection.ConnectionPermissionGrant> find(
                WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
            return Optional.ofNullable(grants.get(key(workspaceId, connectionId, membershipId)));
        }

        @Override
        public boolean replace(com.knowimran.rivoquery.connection.ConnectionPermissionGrant grant, long expectedCollectionVersion) {
            put(grant);
            return true;
        }

        @Override
        public boolean revoke(WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId, long expectedCollectionVersion) {
            return grants.remove(key(workspaceId, connectionId, membershipId)) != null;
        }

        @Override
        public java.util.List<com.knowimran.rivoquery.connection.ConnectionPermissionGrant> findPage(
                WorkspaceId workspaceId, ConnectionId connectionId,
                Optional<MembershipId> afterMembershipId, int limit) {
            String after = afterMembershipId.map(MembershipId::value).orElse("");
            return grants.values().stream()
                    .filter(grant -> grant.workspaceId().equals(workspaceId) && grant.connectionId().equals(connectionId))
                    .filter(grant -> grant.membershipId().value().compareTo(after) > 0)
                    .sorted(java.util.Comparator.comparing(grant -> grant.membershipId().value()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId) {
            return grants.values().stream()
                    .filter(grant -> grant.workspaceId().equals(workspaceId) && grant.connectionId().equals(connectionId))
                    .mapToLong(com.knowimran.rivoquery.connection.ConnectionPermissionGrant::version)
                    .max()
                    .orElse(0L);
        }
    }
}
