package com.knowimran.rivoquery.connection.application.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization.AuthorizedOperation;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretMutationGrant;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.SingleMembershipRepository;
import java.io.Serializable;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SecretCapabilityBoundaryTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final OperationId OPERATION = new OperationId("op_" + ULID);
    private static final ActorId ACTOR = new ActorId("act_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final BoundSecretReference SECRET_1 = secret(1);
    private static final BoundSecretReference SECRET_2 = secret(2);

    @Test
    void grantsAreSealedToExactlyPrivateFinalAuthorityImplementations() {
        for (Class<?> grant : new Class<?>[] {
            SecretAccessGrant.class, SecretMutationGrant.class, SecretRevocationGrant.class
        }) {
            assertThat(grant.isSealed()).isTrue();
            assertThat(grant.getPermittedSubclasses()).hasSize(1);
            Class<?> implementation = grant.getPermittedSubclasses()[0];
            assertThat(implementation.getEnclosingClass()).isEqualTo(SecretCapabilityAuthority.class);
            assertThat(Modifier.isPrivate(implementation.getModifiers())).isTrue();
            assertThat(Modifier.isFinal(implementation.getModifiers())).isTrue();
            assertThat(Arrays.stream(implementation.getDeclaredConstructors())
                            .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers())))
                    .isTrue();
            assertThat(Serializable.class.isAssignableFrom(grant)).isFalse();
            assertThatThrownBy(() -> new ObjectMapper().readValue("{}", grant)).isInstanceOf(Exception.class);
        }
    }

    @Test
    void stageAndAccessGrantsAreExactBoundedRedactedAndLeaseNeverEscapes() {
        var store = new CapturingSecretStore();
        var operation = stageOperation();
        var authority = authority(store, activeWithReservedRotation(), operation);
        var deadline = new Deadline(NOW.plusSeconds(300));

        try (var material = new DestructibleSecretMaterial("canary-password".getBytes())) {
            authority.stage(actor(), CONNECTION, SECRET_2.binding(), material, OPERATION, deadline);
        }
        var consumedLength = new int[1];
        authority.consume(
                actor(),
                CONNECTION,
                SecretUsePurpose.CONNECTION_TEST,
                deadline,
                bytes -> consumedLength[0] = bytes.length);

        assertThat(consumedLength[0]).isEqualTo(12);
        assertThat(store.leaseClosed).isTrue();
        assertThat(store.mutationGrant.toString()).isEqualTo("SecretMutationGrant[REDACTED]");
        assertThat(store.accessGrant.toString()).isEqualTo("SecretAccessGrant[REDACTED]");
        assertThat(store.mutationGrant.binding()).isEqualTo(SECRET_2.binding());
        assertThat(store.accessGrant.binding()).isEqualTo(SECRET_1.binding());
        assertThat(store.accessGrant.connectionVersion()).isEqualTo(new ConnectionVersion(5));
        assertThat(store.accessGrant.deadline().expiresAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(Arrays.stream(SecretOperations.class.getMethods())
                        .noneMatch(method -> method.getReturnType().equals(SecretLease.class)))
                .isTrue();
    }

    @Test
    void callbackFailureStillClosesLease() {
        var store = new CapturingSecretStore();
        var authority = authority(store, active(), accessOperation(SECRET_1, SecretUsePurpose.CONNECTION_TEST));

        assertThatThrownBy(() -> authority.consume(
                        actor(),
                        CONNECTION,
                        SecretUsePurpose.CONNECTION_TEST,
                        new Deadline(NOW.plusSeconds(10)),
                        bytes -> { throw new IllegalStateException("callback failed"); }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("callback failed");
        assertThat(store.leaseClosed).isTrue();
    }

    @Test
    void rotationRequiresExactStagedOperationAndBinding() {
        var store = new CapturingSecretStore();
        var connection = activeWithReservedRotation();
        var wrongOperation = accessOperation(SECRET_1, SecretUsePurpose.ROTATION_TEST);
        var wrong = authority(store, connection, wrongOperation);

        assertThatThrownBy(() -> wrong.consumeForOperation(
                        WORKSPACE,
                        OPERATION,
                        CONNECTION,
                        SECRET_2,
                        SecretUsePurpose.ROTATION_TEST,
                        new Deadline(NOW.plusSeconds(10)),
                        bytes -> {}))
                .isInstanceOf(SecretAuthorizationException.class);
        assertThat(store.calls).isZero();

        var exact = authority(store, connection, accessOperation(SECRET_2, SecretUsePurpose.ROTATION_TEST));
        var consumed = new boolean[1];
        exact.consumeForOperation(
                WORKSPACE,
                OPERATION,
                CONNECTION,
                SECRET_2,
                SecretUsePurpose.ROTATION_TEST,
                new Deadline(NOW.plusSeconds(10)),
                bytes -> consumed[0] = true);
        assertThat(consumed[0]).isTrue();
    }

    @Test
    void revokeRejectsCurrentActiveReferenceEvenWhenOperationClaimsItIsAuthorized() {
        var store = new CapturingSecretStore();
        var authority = authority(store, active(), revokeOperation(SECRET_1));

        assertThatThrownBy(() -> authority.revokeForOperation(
                        WORKSPACE,
                        OPERATION,
                        CONNECTION,
                        SECRET_1,
                        new Deadline(NOW.plusSeconds(10))))
                .isInstanceOf(SecretAuthorizationException.class);
        assertThat(store.calls).isZero();
    }

    @Test
    void detachedDeletingReferenceCanBeRevokedWithExactOperation() {
        var store = new CapturingSecretStore();
        var authority = authority(store, deleting(), revokeOperation(SECRET_1));

        authority.revokeForOperation(
                WORKSPACE, OPERATION, CONNECTION, SECRET_1, new Deadline(NOW.plusSeconds(10)));

        assertThat(store.revocationGrant.reference()).isEqualTo(SECRET_1.reference());
        assertThat(store.revocationGrant.toString()).isEqualTo("SecretRevocationGrant[REDACTED]");
    }

    @Test
    void connectionTestsFailClosedAcrossInternalDisabledDeletedAndExpiredStates() {
        for (DatabaseConnection connection : new DatabaseConnection[] {
            DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL),
            active().transition(active().version(), ConnectionState.DISABLED),
            deleting(),
            deleted(),
            failed(NOW)
        }) {
            var store = new CapturingSecretStore();
            var authority = authority(store, connection, accessOperation(SECRET_1, SecretUsePurpose.CONNECTION_TEST));
            assertThatThrownBy(() -> authority.consume(
                            actor(),
                            CONNECTION,
                            SecretUsePurpose.CONNECTION_TEST,
                            new Deadline(NOW.plusSeconds(10)),
                            bytes -> {}))
                    .isInstanceOf(SecretAuthorizationException.class);
            assertThat(store.calls).isZero();
        }

        var beforeRetirement = authority(
                new CapturingSecretStore(),
                failed(NOW.plusSeconds(1)),
                accessOperation(SECRET_1, SecretUsePurpose.CONNECTION_TEST));
        var consumed = new boolean[1];
        beforeRetirement.consume(
                actor(),
                CONNECTION,
                SecretUsePurpose.CONNECTION_TEST,
                new Deadline(NOW.plusSeconds(10)),
                bytes -> consumed[0] = true);
        assertThat(consumed[0]).isTrue();
    }

    @Test
    void secretValuesAndDeadlinesAreRedactedNonSerializableAndBounded() {
        var material = new DestructibleSecretMaterial("unique-canary".getBytes());
        assertThat(material).isNotInstanceOf(Serializable.class);
        assertThat(material.toString()).doesNotContain("unique-canary").contains("REDACTED");
        assertThat(SECRET_1.reference()).isNotInstanceOf(Serializable.class);
        assertThat(SECRET_1.toString()).doesNotContain("opaqueSecretRef01").contains("REDACTED");
        material.close();
        assertThatThrownBy(() -> material.consume(bytes -> bytes.length))
                .isInstanceOf(IllegalStateException.class);
        var clock = Clock.fixed(NOW, ZoneOffset.UTC);
        assertThat(new Deadline(NOW.plusSeconds(4)).remaining(clock)).isEqualTo(Duration.ofSeconds(4));
        assertThat(new Deadline(NOW.minusSeconds(1)).remaining(clock)).isEqualTo(Duration.ZERO);
    }

    private static SecretCapabilityAuthority authority(
            CapturingSecretStore store, DatabaseConnection connection, AuthorizedOperation operation) {
        ConnectionRepository repository = new ConnectionRepository() {
            @Override
            public Optional<DatabaseConnection> findByWorkspaceAndId(
                    WorkspaceId workspaceId, ConnectionId connectionId) {
                return workspaceId.equals(connection.workspaceId()) && connectionId.equals(connection.id())
                        ? Optional.of(connection)
                        : Optional.empty();
            }
            @Override public void insert(DatabaseConnection ignored) {}
            @Override public boolean compareAndSet(DatabaseConnection ignored, ConnectionVersion version) {
                return false;
            }
            @Override public java.util.List<DatabaseConnection> findPage(
                    WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit) {
                return java.util.List.of();
            }
        };
        DurableSecretAuthorization authorization = (workspaceId, operationId) ->
                operation.workspaceId().equals(workspaceId) && operation.operationId().equals(operationId)
                        ? Optional.of(operation)
                        : Optional.empty();
        return new SecretCapabilityAuthority(
                repository,
                authorization,
                store,
                () -> "nonce-01J6MZ4H9ST5N7YQ2C8W3V0KFX",
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static AuthorizedOperation stageOperation() {
        return new AuthorizedOperation(
                WORKSPACE, OPERATION, Optional.of(ACTOR), SecretOperationKind.STAGE,
                SECRET_2.binding(), Optional.empty(), Optional.empty(), false);
    }

    private static AuthorizedOperation accessOperation(
            BoundSecretReference secret, SecretUsePurpose purpose) {
        return new AuthorizedOperation(
                WORKSPACE, OPERATION, Optional.empty(), SecretOperationKind.ACCESS,
                secret.binding(), Optional.of(secret.reference()), Optional.of(purpose), false);
    }

    private static AuthorizedOperation revokeOperation(BoundSecretReference secret) {
        return new AuthorizedOperation(
                WORKSPACE, OPERATION, Optional.empty(), SecretOperationKind.REVOKE,
                secret.binding(), Optional.of(secret.reference()), Optional.empty(), false);
    }

    private static DatabaseConnection pendingValidation() {
        var reserved = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL);
        var secretReserved = reserved.reserveNextSecretVersion(reserved.version());
        return secretReserved.attachCurrentSecret(secretReserved.version(), SECRET_1);
    }

    private static DatabaseConnection active() {
        var pending = pendingValidation();
        return pending.transition(pending.version(), ConnectionState.ACTIVE);
    }

    private static DatabaseConnection activeWithReservedRotation() {
        var active = active();
        return active.reserveNextSecretVersion(active.version());
    }

    private static DatabaseConnection failed(Instant retireAt) {
        var pending = pendingValidation();
        return pending.validationFailed(pending.version(), retireAt);
    }

    private static DatabaseConnection deleting() {
        var active = active();
        return active.transition(active.version(), ConnectionState.DELETING);
    }

    private static DatabaseConnection deleted() {
        var deleting = deleting();
        return deleting.transition(deleting.version(), ConnectionState.DELETED);
    }

    private static ActorContext actor() {
        var membership = new CurrentMembership(
                ACTOR, WORKSPACE, new MembershipId("mem_" + ULID),
                WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        return new ActorContext.Authority(new SingleMembershipRepository(membership))
                .resolve("issuer", "subject", WORKSPACE);
    }

    private static BoundSecretReference secret(long version) {
        var binding = new SecretBinding(
                WORKSPACE, CONNECTION, SecretPurpose.TARGET_DATABASE_CONNECTION, version);
        var reference = new SecretReference(
                "local", "opaqueSecretRef0" + version, version, "bindingFingerpt0" + version);
        return new BoundSecretReference(binding, reference);
    }

    private static final class CapturingSecretStore implements SecretStore {
        private int calls;
        private boolean leaseClosed;
        private SecretAccessGrant accessGrant;
        private SecretMutationGrant mutationGrant;
        private SecretRevocationGrant revocationGrant;

        @Override
        public StagedSecretReference stage(
                SecretBinding binding,
                DestructibleSecretMaterial material,
                SecretMutationGrant grant,
                Deadline deadline) {
            calls++;
            mutationGrant = grant;
            return new StagedSecretReference(SECRET_2);
        }

        @Override
        public SecretLease resolve(SecretReference reference, SecretAccessGrant grant, Deadline deadline) {
            calls++;
            accessGrant = grant;
            return new SecretLease() {
                private final byte[] bytes = "secret-bytes".getBytes();
                @Override public void consume(SecretMaterialConsumer consumer) {
                    consumer.use(bytes);
                }
                @Override public void close() {
                    Arrays.fill(bytes, (byte) 0);
                    leaseClosed = true;
                }
                @Override public String toString() { return "SecretLease[REDACTED]"; }
            };
        }

        @Override
        public void revoke(SecretReference reference, SecretRevocationGrant grant, Deadline deadline) {
            calls++;
            revocationGrant = grant;
        }
    }
}
