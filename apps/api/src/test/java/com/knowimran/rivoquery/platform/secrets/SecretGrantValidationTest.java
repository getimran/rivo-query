package com.knowimran.rivoquery.platform.secrets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization.AuthorizedOperation;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretOperationKind;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretUsePurpose;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.SingleMembershipRepository;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Direct coverage of {@link SecretGrantValidation}, previously exercised
 * only indirectly (or not at all - see the P2-070 review finding this test
 * closes). Proves every {@link LocalAesGcmSecretStore} entry point
 * re-verifies a caller-supplied grant against the binding/reference it is
 * presented with, even when that grant was legitimately minted by a real
 * {@code SecretCapabilityAuthority} - for a <em>different</em> tenant,
 * connection, or secret version - and is then replayed directly against the
 * adapter via {@link CapturingSecretStore}, bypassing the authority
 * entirely. This is exactly the guarantee the P2-070 acceptance row claims:
 * "SecretStore entry points cannot be reached with a transport-,
 * persistence-, connector-, or worker-forged grant."
 *
 * <p>Not covered here: a "wrong use-purpose" replay at this layer.
 * {@link SecretGrantValidation} does not (and structurally cannot usefully)
 * check {@code SecretUsePurpose} - it is not part of {@link SecretBinding}
 * or {@link com.knowimran.rivoquery.connection.application.secret.SecretReference},
 * so there is nothing for the adapter's binding/reference equality checks to
 * compare it against. Use-purpose gating instead happens one layer up, in
 * {@code SecretCapabilityAuthority.requireCurrentAccess}/{@code
 * consumeForOperation}, and is already covered by
 * {@code SecretCapabilityBoundaryTest}.
 */
class SecretGrantValidationTest {
    private static final Instant NOW = Instant.parse("2026-08-24T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String MEMBERSHIP = "mem_01J7QK3H0RT8N4YB2C6W9V1MFP";

    private static final WorkspaceId WORKSPACE_A = new WorkspaceId("wsp_01J7QK3H0RT8N4YB2C6W9V1MFP");
    private static final ConnectionId CONNECTION_A = new ConnectionId("con_01J7QK3H0RT8N4YB2C6W9V1MFP");
    private static final ActorId ACTOR_A = new ActorId("act_01J7QK3H0RT8N4YB2C6W9V1MFP");

    // A different tenant entirely.
    private static final WorkspaceId WORKSPACE_B = new WorkspaceId("wsp_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ConnectionId CONNECTION_B = new ConnectionId("con_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    private static final ActorId ACTOR_B = new ActorId("act_01ARZ3NDEKTSV4RRFFQ69G5FAV");

    // A second connection inside WORKSPACE_A (same tenant, different connection).
    private static final ConnectionId CONNECTION_C = new ConnectionId("con_01J7QK3H0RT8N4YB2C6W9V1MFQ");

    @TempDir
    Path directory;

    @Test
    void resolveRejectsAnAccessGrantMintedForADifferentTenantsSecret() {
        var store = newCapturingStore(directory);
        var secretA = stageAndActivate(store, WORKSPACE_A, CONNECTION_A, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFR", "a-canary-password");
        var secretB = stageAndActivate(store, WORKSPACE_B, CONNECTION_B, ACTOR_B, "op_01ARZ3NDEKTSV4RRFFQ69G5FAW", "b-canary-password");

        var accessGrant = captureAccessGrant(store, secretA, ACTOR_A);

        assertThatThrownBy(() -> store.resolve(secretB.value().reference(), accessGrant, freshDeadline()))
                .isInstanceOf(SecretGrantMismatchException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "a-canary-password", "b-canary-password", directory.toAbsolutePath().toString()));
    }

    @Test
    void resolveRejectsAnAccessGrantMintedForADifferentConnectionInTheSameTenant() {
        var store = newCapturingStore(directory);
        var secretA = stageAndActivate(store, WORKSPACE_A, CONNECTION_A, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFR", "a-canary-password");
        var secretC = stageAndActivate(store, WORKSPACE_A, CONNECTION_C, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFS", "c-canary-password");

        var accessGrant = captureAccessGrant(store, secretA, ACTOR_A);

        assertThatThrownBy(() -> store.resolve(secretC.value().reference(), accessGrant, freshDeadline()))
                .isInstanceOf(SecretGrantMismatchException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "a-canary-password", "c-canary-password", directory.toAbsolutePath().toString()));
    }

    @Test
    void resolveRejectsAnAccessGrantMintedForAStaleVersionOfTheSameConnection() {
        var store = newCapturingStore(directory);
        var secretV1 = stageAndActivate(store, WORKSPACE_A, CONNECTION_A, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFR", "v1-canary-password");
        var connectionAtV1 = activeConnection(WORKSPACE_A, CONNECTION_A, secretV1);
        var secretV2 = rotate(store, connectionAtV1, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFT", "v2-canary-password");

        // Legitimately-minted grant is for the current (v2) secret.
        var accessGrant = captureAccessGrant(store, secretV2, ACTOR_A);

        // Replayed directly against the stale (v1) reference.
        assertThatThrownBy(() -> store.resolve(secretV1.value().reference(), accessGrant, freshDeadline()))
                .isInstanceOf(SecretGrantMismatchException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "v1-canary-password", "v2-canary-password", directory.toAbsolutePath().toString()));
    }

    @Test
    void stageRejectsAMutationGrantMintedForADifferentConnectionsBinding() {
        var store = newCapturingStore(directory);
        stageAndActivate(store, WORKSPACE_A, CONNECTION_A, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFR", "a-canary-password");
        var mutationGrant = store.lastMutationGrant();

        var foreignBinding = new SecretBinding(WORKSPACE_A, CONNECTION_C, SecretPurpose.TARGET_DATABASE_CONNECTION, 1);
        try (var material = new DestructibleSecretMaterial("forged-password".getBytes(StandardCharsets.UTF_8))) {
            assertThatThrownBy(() -> store.stage(foreignBinding, material, mutationGrant, freshDeadline()))
                    .isInstanceOf(SecretGrantMismatchException.class)
                    .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                            ex, "a-canary-password", "forged-password", directory.toAbsolutePath().toString()));
        }
    }

    @Test
    void revokeRejectsARevocationGrantMintedForADifferentConnectionsReference() {
        var store = newCapturingStore(directory);
        var secretA = stageAndActivate(store, WORKSPACE_A, CONNECTION_A, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFR", "a-canary-password");
        var secretC = stageAndActivate(store, WORKSPACE_A, CONNECTION_C, ACTOR_A, "op_01J7QK3H0RT8N4YB2C6W9V1MFS", "c-canary-password");

        var revocationGrant = captureRevocationGrant(store, WORKSPACE_A, CONNECTION_A, secretA, "op_01J7QK3H0RT8N4YB2C6W9V1MFX");

        assertThatThrownBy(() -> store.revoke(secretC.value().reference(), revocationGrant, freshDeadline()))
                .isInstanceOf(SecretGrantMismatchException.class)
                .satisfies(ex -> SecretStoreTestFixtures.assertNoSecretLeakage(
                        ex, "a-canary-password", "c-canary-password", directory.toAbsolutePath().toString()));
    }

    private static CapturingSecretStore newCapturingStore(Path directory) {
        return new CapturingSecretStore(new LocalAesGcmSecretStore(
                directory,
                KeyEncryptionKeyRing.of(Map.of("v1", AesGcmCipher.randomBytes(32)), "v1"),
                SecretReferenceKey.of(AesGcmCipher.randomBytes(32)),
                CLOCK));
    }

    private static StagedSecretReference stageAndActivate(
            CapturingSecretStore store,
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            ActorId actorId,
            String operationId,
            String password) {
        return stage(store, workspaceId, connectionId, actorId, operationId, 1, password);
    }

    private static StagedSecretReference rotate(
            CapturingSecretStore store,
            DatabaseConnection currentConnection,
            ActorId actorId,
            String operationId,
            String password) {
        var reserved = currentConnection.reserveNextSecretVersion(currentConnection.version());
        var operation = new AuthorizedOperation(
                reserved.workspaceId(), new OperationId(operationId), Optional.of(actorId), SecretOperationKind.STAGE,
                new SecretBinding(reserved.workspaceId(), reserved.id(), SecretPurpose.TARGET_DATABASE_CONNECTION, 2),
                Optional.empty(), Optional.empty(), false);
        var authority = SecretStoreTestFixtures.authority(store, reserved, CLOCK, Optional.of(operation));
        try (var material = new DestructibleSecretMaterial(password.getBytes(StandardCharsets.UTF_8))) {
            return authority.stage(
                    actorFor(reserved.workspaceId(), actorId),
                    reserved.id(),
                    operation.binding(),
                    material,
                    new OperationId(operationId),
                    freshDeadline());
        }
    }

    private static StagedSecretReference stage(
            CapturingSecretStore store,
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            ActorId actorId,
            String operationId,
            long version,
            String password) {
        var binding = new SecretBinding(workspaceId, connectionId, SecretPurpose.TARGET_DATABASE_CONNECTION, version);
        var connection = DatabaseConnection.reserve(workspaceId, connectionId, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        var operation = new AuthorizedOperation(
                workspaceId, new OperationId(operationId), Optional.of(actorId), SecretOperationKind.STAGE,
                binding, Optional.empty(), Optional.empty(), false);
        var authority = SecretStoreTestFixtures.authority(store, connection, CLOCK, Optional.of(operation));
        try (var material = new DestructibleSecretMaterial(password.getBytes(StandardCharsets.UTF_8))) {
            return authority.stage(
                    actorFor(workspaceId, actorId), connectionId, binding, material, new OperationId(operationId), freshDeadline());
        }
    }

    private static DatabaseConnection activeConnection(
            WorkspaceId workspaceId, ConnectionId connectionId, StagedSecretReference staged) {
        var connection = DatabaseConnection.reserve(workspaceId, connectionId, DatabaseKind.POSTGRESQL)
                .reserveNextSecretVersion(new ConnectionVersion(1));
        long targetVersion = staged.value().binding().version();
        for (long v = 2; v <= targetVersion; v++) {
            connection = connection.reserveNextSecretVersion(connection.version());
        }
        var withSecret = connection.attachCurrentSecret(connection.version(), staged.value());
        return withSecret.transition(withSecret.version(), ConnectionState.ACTIVE);
    }

    private static DatabaseConnection deletingConnectionAt(
            WorkspaceId workspaceId, ConnectionId connectionId, StagedSecretReference staged) {
        var active = activeConnection(workspaceId, connectionId, staged);
        return active.transition(active.version(), ConnectionState.DELETING);
    }

    private static com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretAccessGrant
            captureAccessGrant(CapturingSecretStore store, StagedSecretReference staged, ActorId actorId) {
        var active = activeConnection(staged.value().binding().workspaceId(), staged.value().binding().connectionId(), staged);
        var authority = SecretStoreTestFixtures.authority(store, active, CLOCK);
        authority.consume(
                actorFor(active.workspaceId(), actorId),
                active.id(),
                SecretUsePurpose.CONNECTION_TEST,
                freshDeadline(),
                bytes -> { });
        return store.lastAccessGrant();
    }

    private static com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority.SecretRevocationGrant
            captureRevocationGrant(
                    CapturingSecretStore store,
                    WorkspaceId workspaceId,
                    ConnectionId connectionId,
                    StagedSecretReference staged,
                    String operationId) {
        var deleting = deletingConnectionAt(workspaceId, connectionId, staged);
        var operation = new AuthorizedOperation(
                workspaceId, new OperationId(operationId), Optional.empty(), SecretOperationKind.REVOKE,
                staged.value().binding(), Optional.of(staged.value().reference()), Optional.empty(), false);
        var authority = SecretStoreTestFixtures.authority(store, deleting, CLOCK, Optional.of(operation));
        authority.revokeForOperation(workspaceId, new OperationId(operationId), connectionId, staged.value(), freshDeadline());
        return store.lastRevocationGrant();
    }

    private static ActorContext actorFor(WorkspaceId workspaceId, ActorId actorId) {
        var membership = new CurrentMembership(
                actorId, workspaceId, new MembershipId(MEMBERSHIP), WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        return new ActorContext.Authority(new SingleMembershipRepository(membership))
                .resolve("issuer", "subject", workspaceId);
    }

    private static Deadline freshDeadline() {
        return new Deadline(NOW.plusSeconds(30));
    }
}
