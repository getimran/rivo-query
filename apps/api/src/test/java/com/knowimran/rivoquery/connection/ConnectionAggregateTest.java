package com.knowimran.rivoquery.connection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretAuthorizationException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ConnectionAggregateTest {
    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);

    @Test
    void connectionAndOperationIdsAreCanonicalAndCaseSensitive() {
        assertThat(CONNECTION.value()).isEqualTo("con_" + ULID);
        assertThat(new OperationId("op_" + ULID).value()).isEqualTo("op_" + ULID);
        assertThatThrownBy(() -> new ConnectionId("con_" + ULID.toLowerCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperationId("op_" + ULID + " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stateMachineDefinesEveryValidAndInvalidTransition() {
        var expected = Set.of(
                "PENDING_SECRET->DELETING",
                "PENDING_VALIDATION->ACTIVE",
                "PENDING_VALIDATION->FAILED_VALIDATION",
                "PENDING_VALIDATION->DISABLED",
                "PENDING_VALIDATION->DELETING",
                "ACTIVE->PENDING_VALIDATION",
                "ACTIVE->DISABLED",
                "ACTIVE->DELETING",
                "FAILED_VALIDATION->PENDING_VALIDATION",
                "FAILED_VALIDATION->DISABLED",
                "FAILED_VALIDATION->DELETING",
                "DISABLED->PENDING_VALIDATION",
                "DISABLED->DELETING",
                "DELETING->DELETED");

        for (var source : ConnectionState.values()) {
            for (var target : ConnectionState.values()) {
                assertThat(source.canTransitionTo(target))
                        .as("%s -> %s", source, target)
                        .isEqualTo(expected.contains(source + "->" + target));
            }
        }
        assertThat(Arrays.stream(ConnectionState.values()).filter(ConnectionState::isPublicState))
                .containsExactly(
                        ConnectionState.PENDING_VALIDATION,
                        ConnectionState.ACTIVE,
                        ConnectionState.FAILED_VALIDATION,
                        ConnectionState.DISABLED,
                        ConnectionState.DELETING);
    }

    /**
     * For every (source, target) pair {@link ConnectionState#canTransitionTo}
     * advertises as legal, proves the aggregate actually reaches that target
     * without throwing when driven from a representative, normally
     * constructed instance of the source state. FAILED_VALIDATION as a
     * *target* is exercised separately below via {@link
     * DatabaseConnection#validationFailed}, since {@link
     * DatabaseConnection#transition} deliberately always rejects it (that
     * transition requires a credential-retirement instant the generic method
     * has no way to accept).
     */
    @Test
    void everyGraphLegalTransitionSucceedsFromARepresentativeInstanceOfItsSourceState() {
        var pendingSecret = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL);
        var pendingValidation = withInitialSecret();
        var active = pendingValidation.transition(pendingValidation.version(), ConnectionState.ACTIVE);
        var failedValidation = pendingValidation.validationFailed(
                pendingValidation.version(), Instant.parse("2026-08-23T00:00:00Z"));
        var disabled = active.transition(active.version(), ConnectionState.DISABLED);

        var representativeInstanceByState = java.util.Map.of(
                ConnectionState.PENDING_SECRET, pendingSecret,
                ConnectionState.PENDING_VALIDATION, pendingValidation,
                ConnectionState.ACTIVE, active,
                ConnectionState.FAILED_VALIDATION, failedValidation,
                ConnectionState.DISABLED, disabled);

        for (var source : representativeInstanceByState.keySet()) {
            var instance = representativeInstanceByState.get(source);
            for (var target : ConnectionState.values()) {
                if (!source.canTransitionTo(target) || target == ConnectionState.FAILED_VALIDATION) {
                    continue;
                }
                assertThatCode(() -> instance.transition(instance.version(), target))
                        .as("%s -> %s from a representative secret-bearing instance", source, target)
                        .doesNotThrowAnyException();
            }
        }
    }

    /**
     * {@link ConnectionState#canTransitionTo} advertises FAILED_VALIDATION ->
     * {DISABLED, PENDING_VALIDATION} as legal because that is true whenever
     * the failing secret is still attached (see the test above). Both
     * targets unconditionally require a current secret, so once
     * {@link DatabaseConnection#detachFailedSecret} has removed it, both
     * moves are genuinely invalid for this instance and must fail closed
     * with {@link InvalidConnectionTransitionException}, never surface the
     * aggregate constructor's {@link IllegalArgumentException}.
     */
    @Test
    void transitionRejectsGraphLegalTargetsThatRequireASecretOnceItHasBeenDetached() {
        var pendingValidation = withInitialSecret();
        var failedValidation = pendingValidation.validationFailed(
                pendingValidation.version(), Instant.parse("2026-08-23T00:00:00Z"));
        var detached = failedValidation.detachFailedSecret(
                failedValidation.version(), secret(WORKSPACE, CONNECTION, 1));

        assertThat(detached.currentSecret()).isEmpty();
        assertThatThrownBy(() -> detached.transition(detached.version(), ConnectionState.DISABLED))
                .isInstanceOf(InvalidConnectionTransitionException.class);
        assertThatThrownBy(() -> detached.transition(detached.version(), ConnectionState.PENDING_VALIDATION))
                .isInstanceOf(InvalidConnectionTransitionException.class);
    }

    @Test
    void aggregateTransitionsImmutablyAndAdvancesOptimisticVersion() {
        var reserved = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL);
        var versionReserved = reserved.reserveNextSecretVersion(new ConnectionVersion(1));
        var pendingValidation = versionReserved.attachCurrentSecret(new ConnectionVersion(2), secret(WORKSPACE, CONNECTION, 1));
        var active = pendingValidation.transition(new ConnectionVersion(3), ConnectionState.ACTIVE);

        assertThat(reserved.state()).isEqualTo(ConnectionState.PENDING_SECRET);
        assertThat(reserved.version()).isEqualTo(new ConnectionVersion(1));
        assertThat(active.state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(active.version()).isEqualTo(new ConnectionVersion(4));
    }

    @Test
    void staleVersionsAndInvalidTransitionsFailClosed() {
        var reserved = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.MYSQL);
        assertThatThrownBy(() -> reserved.transition(new ConnectionVersion(2), ConnectionState.PENDING_VALIDATION))
                .isInstanceOf(StaleConnectionVersionException.class);
        assertThatThrownBy(() -> reserved.transition(new ConnectionVersion(1), ConnectionState.PENDING_SECRET))
                .isInstanceOf(InvalidConnectionTransitionException.class);
        assertThatThrownBy(() -> new ConnectionVersion(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingIsTerminal() {
        var deleted = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL)
                .transition(new ConnectionVersion(1), ConnectionState.DELETING);
        deleted = deleted.transition(new ConnectionVersion(2), ConnectionState.DELETED);
        for (var target : ConnectionState.values()) {
            var terminal = deleted;
            assertThatThrownBy(() -> terminal.transition(new ConnectionVersion(3), target))
                    .isInstanceOf(InvalidConnectionTransitionException.class);
        }
    }

    @Test
    void aggregateRejectsCrossWorkspaceActorBinding() {
        var connection = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL);
        var foreignWorkspace = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
        var membership = new CurrentMembership(
                new ActorId("act_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                foreignWorkspace,
                new MembershipId("mem_01J6MZ4H9ST5N7YQ2C8W3V0KFY"),
                WorkspaceRole.ADMINISTRATOR,
                MembershipStatus.ACTIVE);
        var foreign = new ActorContext.Authority((issuer, subject, workspace) -> Optional.of(membership))
                .resolve("issuer", "subject", foreignWorkspace);

        assertThatThrownBy(() -> connection.authorize(foreign))
                .isInstanceOf(AuthorizationDeniedException.class);
    }

    @Test
    void rehydrationRejectsCrossTenantAndInvalidStateSecretBindings() {
        var foreignWorkspace = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFY");
        assertThatThrownBy(() -> DatabaseConnection.rehydrate(
                        WORKSPACE,
                        CONNECTION,
                        DatabaseKind.POSTGRESQL,
                        ConnectionState.ACTIVE,
                        new ConnectionVersion(3),
                        1,
                        secret(foreignWorkspace, CONNECTION, 1),
                        null))
                .isInstanceOf(SecretAuthorizationException.class);
        assertThatThrownBy(() -> DatabaseConnection.rehydrate(
                        WORKSPACE,
                        CONNECTION,
                        DatabaseKind.POSTGRESQL,
                        ConnectionState.ACTIVE,
                        new ConnectionVersion(3),
                        1,
                        null,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseConnection.rehydrate(
                        WORKSPACE,
                        CONNECTION,
                        DatabaseKind.POSTGRESQL,
                        ConnectionState.DELETING,
                        new ConnectionVersion(3),
                        1,
                        secret(WORKSPACE, CONNECTION, 1),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DatabaseConnection.rehydrate(
                        WORKSPACE,
                        CONNECTION,
                        DatabaseKind.POSTGRESQL,
                        ConnectionState.FAILED_VALIDATION,
                        new ConnectionVersion(3),
                        1,
                        secret(WORKSPACE, CONNECTION, 1),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void failedRetirementIsFixedAndDetachmentNeverReusesASecretVersion() {
        var pending = withInitialSecret();
        var retireAt = Instant.parse("2026-08-21T00:00:00Z");
        var failed = pending.validationFailed(pending.version(), retireAt);

        assertThat(failed.secretRetireAt()).contains(retireAt);
        assertThatThrownBy(() -> failed.validationFailed(failed.version(), retireAt.plusSeconds(60)))
                .isInstanceOf(InvalidConnectionTransitionException.class);
        var detached = failed.detachFailedSecret(failed.version(), secret(WORKSPACE, CONNECTION, 1));
        assertThat(detached.currentSecret()).isEmpty();
        assertThat(detached.latestSecretVersion()).isEqualTo(1);
        var next = detached.reserveNextSecretVersion(detached.version());
        assertThat(next.latestSecretVersion()).isEqualTo(2);
        assertThatThrownBy(() -> next.attachCurrentSecret(next.version(), secret(WORKSPACE, CONNECTION, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletionDetachesCurrentSecretAndForbidsFurtherAttachment() {
        var active = withInitialSecret().transition(new ConnectionVersion(3), ConnectionState.ACTIVE);
        var deleting = active.transition(active.version(), ConnectionState.DELETING);

        assertThat(deleting.currentSecret()).isEmpty();
        assertThat(deleting.latestSecretVersion()).isEqualTo(1);
        assertThatThrownBy(() -> deleting.attachCurrentSecret(deleting.version(), secret(WORKSPACE, CONNECTION, 1)))
                .isInstanceOf(InvalidConnectionTransitionException.class);
        assertThatThrownBy(() -> deleting.reserveNextSecretVersion(deleting.version()))
                .isInstanceOf(InvalidConnectionTransitionException.class);
    }

    @Test
    void touchAdvancesVersionWithoutChangingStateSecretOrRetirement() {
        var active = withInitialSecret().transition(new ConnectionVersion(3), ConnectionState.ACTIVE);
        var touched = active.touch(active.version());

        assertThat(touched.state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(touched.version()).isEqualTo(new ConnectionVersion(active.version().value() + 1));
        assertThat(touched.currentSecret()).isEqualTo(active.currentSecret());
        assertThat(touched.latestSecretVersion()).isEqualTo(active.latestSecretVersion());

        assertThatThrownBy(() -> active.touch(new ConnectionVersion(active.version().value() + 5)))
                .isInstanceOf(StaleConnectionVersionException.class);
    }

    @Test
    void touchRejectsDeletingAndDeletedConnections() {
        var deleting = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL)
                .transition(new ConnectionVersion(1), ConnectionState.DELETING);
        var deleted = deleting.transition(deleting.version(), ConnectionState.DELETED);

        assertThatThrownBy(() -> deleting.touch(deleting.version()))
                .isInstanceOf(InvalidConnectionTransitionException.class);
        assertThatThrownBy(() -> deleted.touch(deleted.version()))
                .isInstanceOf(InvalidConnectionTransitionException.class);
    }

    @Test
    void publicStateExclusionListMatchesTheSqlLevelExclusionInFindPage() {
        // JdbcConnectionRepository.FIND_PAGE_QUERY hard-codes
        // `state NOT IN ('PENDING_SECRET', 'DELETED')` at the SQL level (it
        // cannot reference this Java enum directly); this test is the tie
        // that keeps the two in sync - if a future ConnectionState value
        // changes isPublicState() without the SQL literal also being
        // updated, this fails instead of silently leaking (or
        // over-hiding) a state from the list page.
        var nonPublicStates = Arrays.stream(ConnectionState.values())
                .filter(state -> !state.isPublicState())
                .toList();
        assertThat(nonPublicStates).containsExactlyInAnyOrder(ConnectionState.PENDING_SECRET, ConnectionState.DELETED);
    }

    private static DatabaseConnection withInitialSecret() {
        var reserved = DatabaseConnection.reserve(WORKSPACE, CONNECTION, DatabaseKind.POSTGRESQL);
        var versionReserved = reserved.reserveNextSecretVersion(reserved.version());
        return versionReserved.attachCurrentSecret(versionReserved.version(), secret(WORKSPACE, CONNECTION, 1));
    }

    private static BoundSecretReference secret(
            WorkspaceId workspaceId, ConnectionId connectionId, long version) {
        var binding = new SecretBinding(
                workspaceId, connectionId, SecretPurpose.TARGET_DATABASE_CONNECTION, version);
        var reference = new SecretReference(
                "local", "opaqueSecretRef0" + version, version, "bindingFingerpt0" + version);
        return new BoundSecretReference(binding, reference);
    }
}
