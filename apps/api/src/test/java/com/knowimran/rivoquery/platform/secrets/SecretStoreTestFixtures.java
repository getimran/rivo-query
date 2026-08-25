package com.knowimran.rivoquery.platform.secrets;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization.AuthorizedOperation;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.secret.SecretOperationKind;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretStore;
import com.knowimran.rivoquery.connection.application.secret.SecretUsePurpose;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/**
 * Test-only helpers for driving a real {@link SecretCapabilityAuthority}
 * against a real {@code SecretStore} adapter under test, mirroring the
 * fixture pattern in {@code SecretCapabilityBoundaryTest}. Deliberately does
 * not (and cannot) construct a grant directly - every adapter test exercises
 * the adapter only through legitimately minted grants.
 */
final class SecretStoreTestFixtures {
    static final String ULID = "01J7QK3H0RT8N4YB2C6W9V1MFP";
    static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    static final OperationId STAGE_OPERATION = new OperationId("op_" + ULID.replace('0', '1'));
    static final OperationId REVOKE_OPERATION = new OperationId("op_" + ULID.replace('0', '2'));
    static final ActorId ACTOR = new ActorId("act_" + ULID);

    private SecretStoreTestFixtures() {
    }

    static SecretBinding binding(long version) {
        return new SecretBinding(WORKSPACE, CONNECTION, SecretPurpose.TARGET_DATABASE_CONNECTION, version);
    }

    static ActorContext actor() {
        var membership = new CurrentMembership(
                ACTOR, WORKSPACE, new MembershipId("mem_" + ULID), WorkspaceRole.ADMINISTRATOR, MembershipStatus.ACTIVE);
        return new ActorContext.Authority((issuer, subject, workspace) -> Optional.of(membership))
                .resolve("issuer", "subject", WORKSPACE);
    }

    static SecretCapabilityAuthority authority(SecretStore store, DatabaseConnection connection, Clock clock) {
        return authority(store, connection, clock, Optional.empty());
    }

    static SecretCapabilityAuthority authority(
            SecretStore store, DatabaseConnection connection, Clock clock, Optional<AuthorizedOperation> operation) {
        ConnectionRepository repository = new ConnectionRepository() {
            @Override
            public Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId) {
                return workspaceId.equals(connection.workspaceId()) && connectionId.equals(connection.id())
                        ? Optional.of(connection)
                        : Optional.empty();
            }

            @Override
            public void insert(DatabaseConnection ignored) {
            }

            @Override
            public boolean compareAndSet(DatabaseConnection ignored, ConnectionVersion version) {
                return false;
            }
        };
        DurableSecretAuthorization authorization = (workspaceId, operationId) -> operation.filter(
                op -> op.workspaceId().equals(workspaceId) && op.operationId().equals(operationId));
        return new SecretCapabilityAuthority(
                repository, authorization, store, () -> "nonce-" + ULID, clock, Duration.ofMinutes(5));
    }

    static AuthorizedOperation stageOperation(OperationId operationId, SecretBinding binding) {
        return new AuthorizedOperation(
                WORKSPACE, operationId, Optional.of(ACTOR), SecretOperationKind.STAGE,
                binding, Optional.empty(), Optional.empty(), false);
    }

    static AuthorizedOperation revokeOperation(OperationId operationId, SecretBinding binding, SecretReference reference) {
        return new AuthorizedOperation(
                WORKSPACE, operationId, Optional.empty(), SecretOperationKind.REVOKE,
                binding, Optional.of(reference), Optional.empty(), false);
    }

    static SecretUsePurpose connectionTest() {
        return SecretUsePurpose.CONNECTION_TEST;
    }

    /**
     * Shared S8 leakage guard: walks the <em>entire</em> cause chain of a
     * failure-category exception raised by this package's public surface and
     * asserts that none of the supplied sensitive values (plaintext secret
     * material, key material, or a raw absolute filesystem path) appear in
     * any {@code getMessage()} or {@code toString()} along that chain.
     */
    static void assertNoSecretLeakage(Throwable exception, String... sensitiveValues) {
        Throwable current = exception;
        while (current != null) {
            String message = current.getMessage();
            String rendered = current.toString();
            for (String sensitive : sensitiveValues) {
                if (message != null) {
                    org.assertj.core.api.Assertions.assertThat(message)
                            .describedAs("message of %s in cause chain of %s", current.getClass(), exception.getClass())
                            .doesNotContain(sensitive);
                }
                org.assertj.core.api.Assertions.assertThat(rendered)
                        .describedAs("toString() of %s in cause chain of %s", current.getClass(), exception.getClass())
                        .doesNotContain(sensitive);
            }
            current = current.getCause();
        }
    }
}
