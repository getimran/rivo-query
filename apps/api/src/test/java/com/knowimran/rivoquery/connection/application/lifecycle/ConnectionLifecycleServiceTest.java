package com.knowimran.rivoquery.connection.application.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.ConnectionAuditAction;
import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import com.knowimran.rivoquery.connection.application.ConnectionAuditOutcome;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort.Event;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.OperationAction;
import com.knowimran.rivoquery.connection.application.OperationState;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Exercises the P2-100 lifecycle saga end to end through real
 * {@code SecretCapabilityAuthority}/{@code DestinationCapabilityAuthority}
 * instances (never the underlying {@code SecretStore}/{@code DestinationPolicy}
 * directly - see {@code ArchitectureTest.only_secret_capability_authority_calls_secret_store}),
 * with hand-written fakes standing in for every adapter that has no
 * production implementation yet. Every failure-injection point required by
 * P2-100 is covered: pre-action audit failure (for every operation), authorization
 * denial, each domain-operation failure (secret stage/destination/connector/
 * concurrency), compensation (secret revoke) after a partial success -
 * including when compensation itself fails - and terminal-audit failure both
 * with and without a prior external effect.
 *
 * <p>{@code test}/{@code rotateSecret} are structurally blocked pending a V3
 * schema migration that persists a connection endpoint (see the P2-100
 * security-review fix removing the caller-supplied {@code endpoint} they
 * used to accept - {@link ConnectionLifecycleService}'s class Javadoc and
 * {@link ConnectionEndpointNotYetPersistedException}); their tests here
 * assert exactly that blocked shape (authorization/not-found/stale-version
 * checks still run first; no secret, connector, or state side effect ever
 * occurs) rather than a happy path.
 */
class ConnectionLifecycleServiceTest {
    private static final String ADMIN_ULID = "01J9QK3H0RT8N4YB2C6W9V1MFP";
    private static final String MEMBER_ULID = "01J9QK3H0RT8N4YB2C6W9V1MFQ";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ADMIN_ULID);
    private static final ActorId ADMIN_ACTOR = new ActorId("act_" + ADMIN_ULID);
    private static final ActorId MEMBER_ACTOR = new ActorId("act_" + MEMBER_ULID);
    private static final MembershipId MEMBER_MEMBERSHIP = new MembershipId("mem_" + MEMBER_ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final StructuredEndpoint ENDPOINT = new StructuredEndpoint("db.example.com", 5432, "app_db");
    private static final String CANARY_SECRET = "unique-canary-secret-value-9f31";

    @Test
    void createHappyPathActivatesConnectionStagesSecretAndQueuesDiscovery() {
        Harness h = new Harness();
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var result = h.service.create(command, freshSecret());

        assertThat(result.connection().state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(result.operation()).isPresent();
        assertThat(result.operation().orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(result.operation().orElseThrow().action()).isEqualTo(OperationAction.CONNECTION_CREATE);
        assertThat(h.secretStore.staged()).hasSize(1);
        assertThat(h.secretStore.revoked()).isEmpty();
        assertThat(h.connector.calls).isEqualTo(1);
        assertThat(h.discovery.queued()).hasSize(1);
        assertThat(h.discovery.queued().get(0).connectionId()).isEqualTo(result.connection().id());

        List<Event> events = h.auditPort.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).outcome()).isEqualTo(ConnectionAuditOutcome.INDETERMINATE);
        assertThat(events.get(0).category()).isEqualTo(ConnectionAuditCategory.AUTHORIZATION);
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.SUCCEEDED);
        assertThat(events.get(1).action()).isEqualTo(ConnectionAuditAction.CREATE);
        assertNoSecretLeakage(events, result);
    }

    @Test
    void createPreActionAuditFailureCausesNoStateSecretOrConnectorSideEffect() {
        Harness h = new Harness();
        h.auditPort.failOnCall(1);
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        assertThat(h.connections.findPage(WORKSPACE, java.util.Optional.empty(), 100)).isEmpty();
        assertThat(h.secretStore.staged()).isEmpty();
        assertThat(h.connector.calls).isZero();
        assertThat(h.destinationPolicy.calls).isZero();
        assertThat(h.discovery.queued()).isEmpty();
        assertThat(h.auditPort.events()).isEmpty();
    }

    @Test
    void createDeniedForNonAdministratorWithNoSideEffectsBeyondAudit() {
        Harness h = new Harness();
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                member(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(AuthorizationDeniedException.class);

        assertThat(h.secretStore.staged()).isEmpty();
        assertThat(h.connector.calls).isZero();
        List<Event> events = h.auditPort.events();
        assertThat(events).hasSize(2);
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.DENIED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.AUTHORIZATION);
    }

    @Test
    void createSecretStageFailureFailsClosedWithNothingToCompensate() {
        Harness h = new Harness();
        h.secretStore.failStage = true;
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var thrown = assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(ConnectionLifecycleFailedException.class)
                .satisfies(ex -> assertThat(((ConnectionLifecycleFailedException) ex).category())
                        .isEqualTo(ConnectionAuditCategory.SECRET_PROVIDER))
                .actual();

        assertThat(h.secretStore.staged()).isEmpty();
        assertThat(h.secretStore.revoked()).isEmpty();
        assertThat(h.connector.calls).isZero();
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.SECRET_PROVIDER);
        assertNoSecretLeakage(events, thrown);
    }

    @Test
    void createDestinationFailureAfterStagingCompensatesByRevokingTheStagedSecret() {
        Harness h = new Harness();
        h.destinationPolicy.nextFailure = new RuntimeException("simulated dns outage");
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var thrown = assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(ConnectionLifecycleFailedException.class)
                .satisfies(ex -> assertThat(((ConnectionLifecycleFailedException) ex).category())
                        .isEqualTo(ConnectionAuditCategory.DESTINATION_POLICY))
                .actual();

        assertThat(h.secretStore.staged()).hasSize(1);
        assertThat(h.secretStore.revoked()).hasSize(1);
        assertThat(h.secretStore.revoked().get(0)).isEqualTo(h.secretStore.staged().get(0).reference());
        assertThat(h.connector.calls).isZero();
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.DESTINATION_POLICY);
        assertNoSecretLeakage(events, thrown);
    }

    @Test
    void createConnectorRejectionCompensatesByRevokingTheStagedSecret() {
        Harness h = new Harness();
        h.connector.nextResult = new ConnectorResult(false, ConnectorOutcomeCode.CONNECTION_TEST_FAILED);
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(ConnectorTestFailedException.class)
                .satisfies(ex -> assertThat(((ConnectorTestFailedException) ex).outcomeCode())
                        .isEqualTo(ConnectorOutcomeCode.CONNECTION_TEST_FAILED));

        assertThat(h.secretStore.revoked()).hasSize(1);
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.CONNECTOR);
    }

    @Test
    void createConnectorCrashCompensatesByRevokingTheStagedSecret() {
        Harness h = new Harness();
        h.connector.nextFailure = new RuntimeException("simulated connector crash");
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var thrown = assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(ConnectionLifecycleFailedException.class)
                .satisfies(ex -> assertThat(((ConnectionLifecycleFailedException) ex).category())
                        .isEqualTo(ConnectionAuditCategory.CONNECTOR))
                .actual();

        assertThat(h.secretStore.revoked()).hasSize(1);
        assertNoSecretLeakage(thrown);
    }

    @Test
    void createTerminalAuditFailureAfterSuccessIsIndeterminateNotFalseSuccessOrFalseFailure() {
        Harness h = new Harness();
        h.auditPort.failOnCall(2);
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var thrown = assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        // The external effect (secret staged, connection promoted to ACTIVE)
        // already happened and must not be undone or repeated.
        assertThat(h.secretStore.staged()).hasSize(1);
        assertThat(h.secretStore.revoked()).isEmpty();
        var stored = h.connections.findPage(WORKSPACE, java.util.Optional.empty(), 100);
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(h.operations.findByWorkspaceAndId(WORKSPACE, indeterminate.operationId().orElseThrow()))
                .isPresent()
                .get()
                .extracting(op -> op.state())
                .isEqualTo(OperationState.SUCCEEDED);
    }

    @Test
    void createReserveVersionRaceFailsClosedWithNoCompensationNeeded() {
        Harness h = new Harness();
        h.connections.throwStaleOnCompareAndSetCall(1); // races the very first CAS (version reservation)
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(StaleConnectionVersionException.class);

        // Nothing was ever staged, so there is nothing to compensate.
        assertThat(h.secretStore.staged()).isEmpty();
        assertThat(h.secretStore.revoked()).isEmpty();
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.CONCURRENCY);
    }

    @Test
    void createPromoteVersionRaceCompensatesByRevokingTheStagedSecret() {
        Harness h = new Harness();
        h.connections.throwStaleOnCompareAndSetCall(2); // races the second CAS (promotion to ACTIVE)
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(StaleConnectionVersionException.class);

        assertThat(h.secretStore.staged()).hasSize(1);
        assertThat(h.secretStore.revoked()).hasSize(1);
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.CONCURRENCY);
    }

    @Test
    void createPromoteVersionRaceWithFailedCompensationIsIndeterminateNotFailed() {
        Harness h = new Harness();
        h.connections.throwStaleOnCompareAndSetCall(2);
        h.secretStore.failRevoke = true;
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());

        var thrown = assertThatThrownBy(() -> h.service.create(command, freshSecret()))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        assertThat(h.secretStore.staged()).hasSize(1);
        // Compensation was attempted but failed: the staged secret is now
        // orphaned, not cleanly revoked - INDETERMINATE (not FAILED) is the
        // only outcome that leaves this discoverable for a reconciler.
        assertThat(h.secretStore.revoked()).isEmpty();
        assertThat(h.operations.findByWorkspaceAndId(WORKSPACE, indeterminate.operationId().orElseThrow()))
                .isPresent()
                .get()
                .extracting(op -> op.state())
                .isEqualTo(OperationState.INDETERMINATE);
        List<Event> events = h.auditPort.events();
        assertThat(events.get(1).outcome()).isEqualTo(ConnectionAuditOutcome.INDETERMINATE);
        assertThat(events.get(1).category()).isEqualTo(ConnectionAuditCategory.CONCURRENCY);
        assertTerminalEventsNeverReuseThePreActionIndeterminateAuthorizationSignature(events);
    }

    @Test
    void rotateSecretIsStructurallyBlockedWithSecretMaterialLeftUntouched() {
        Harness h = new Harness();
        var created = create(h);
        int stagedBefore = h.secretStore.staged().size();
        var secret = freshSecret();

        Throwable thrown;
        try {
            thrown = assertThatThrownBy(() -> h.service.rotateSecret(
                            new ConnectionLifecycleService.RotateSecretCommand(
                                    admin(), created.connection().id(), created.connection().version(),
                                    requestId(), deadline()),
                            secret))
                    .isInstanceOf(ConnectionEndpointNotYetPersistedException.class)
                    .actual();
        } finally {
            secret.close(); // never touched by rotateSecret today; still the caller's responsibility.
        }

        assertThat(h.secretStore.staged()).hasSize(stagedBefore);
        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.currentSecret().orElseThrow().binding().version()).isEqualTo(1);
        List<Event> events = h.auditPort.events();
        assertThat(events).hasSize(4);
        assertThat(events.get(3).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(3).category()).isEqualTo(ConnectionAuditCategory.INTERNAL);
        assertNoSecretLeakage(thrown);
    }

    @Test
    void rotateSecretFailsClosedOnStaleVersionBeforeReachingTheStructuralBlock() {
        Harness h = new Harness();
        var created = create(h);
        var stale = new ConnectionVersion(created.connection().version().value() + 5);

        assertThatThrownBy(() -> h.service.rotateSecret(
                        new ConnectionLifecycleService.RotateSecretCommand(
                                admin(), created.connection().id(), stale, requestId(), deadline()),
                        freshSecret()))
                .isInstanceOf(StaleConnectionVersionException.class);
    }

    @Test
    void rotateSecretUnknownConnectionIsNotFound() {
        Harness h = new Harness();
        assertThatThrownBy(() -> h.service.rotateSecret(
                        new ConnectionLifecycleService.RotateSecretCommand(
                                admin(), new ConnectionId("con_01J9QK3H0RT8N4YB2C6W9V1MFR"), new ConnectionVersion(1),
                                requestId(), deadline()),
                        freshSecret()))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void rotateSecretPreActionAuditFailureCausesNoSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        int stagedBefore = h.secretStore.staged().size();
        h.auditPort.failOnCall(h.auditPort.events().size() + 1);

        assertThatThrownBy(() -> h.service.rotateSecret(
                        new ConnectionLifecycleService.RotateSecretCommand(
                                admin(), created.connection().id(), created.connection().version(),
                                requestId(), deadline()),
                        freshSecret()))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        assertThat(h.secretStore.staged()).hasSize(stagedBefore);
        assertThat(h.auditPort.events()).hasSize(2);
    }

    @Test
    void testIsStructurallyBlockedWithNoConnectorOrSecretSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        int connectorCallsBefore = h.connector.calls;
        int stagedBefore = h.secretStore.staged().size();

        var thrown = assertThatThrownBy(() -> h.service.test(new ConnectionLifecycleService.TestConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(ConnectionEndpointNotYetPersistedException.class)
                .actual();

        assertThat(h.connector.calls).isEqualTo(connectorCallsBefore);
        assertThat(h.secretStore.staged()).hasSize(stagedBefore);
        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(created.connection().version());
        assertThat(unchanged.state()).isEqualTo(ConnectionState.ACTIVE);
        List<Event> events = h.auditPort.events();
        assertThat(events).hasSize(4);
        assertThat(events.get(3).outcome()).isEqualTo(ConnectionAuditOutcome.FAILED);
        assertThat(events.get(3).category()).isEqualTo(ConnectionAuditCategory.INTERNAL);
        assertNoSecretLeakage(thrown);
    }

    @Test
    void testFailsClosedOnStaleVersionBeforeReachingTheStructuralBlock() {
        Harness h = new Harness();
        var created = create(h);
        var stale = new ConnectionVersion(created.connection().version().value() + 5);

        assertThatThrownBy(() -> h.service.test(new ConnectionLifecycleService.TestConnectionCommand(
                        admin(), created.connection().id(), stale, requestId(), deadline())))
                .isInstanceOf(StaleConnectionVersionException.class);
    }

    @Test
    void testUnknownConnectionIsNotFound() {
        Harness h = new Harness();
        assertThatThrownBy(() -> h.service.test(new ConnectionLifecycleService.TestConnectionCommand(
                        admin(), new ConnectionId("con_01J9QK3H0RT8N4YB2C6W9V1MFR"), new ConnectionVersion(1),
                        requestId(), deadline())))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void testPreActionAuditFailureCausesNoSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        int connectorCallsBefore = h.connector.calls;
        h.auditPort.failOnCall(h.auditPort.events().size() + 1);

        assertThatThrownBy(() -> h.service.test(new ConnectionLifecycleService.TestConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        assertThat(h.connector.calls).isEqualTo(connectorCallsBefore);
        assertThat(h.auditPort.events()).hasSize(2);
    }

    @Test
    void disableHappyPathTransitionsAndDrainsThePool() {
        Harness h = new Harness();
        var created = create(h);

        var result = h.service.disable(new ConnectionLifecycleService.DisableConnectionCommand(
                admin(), created.connection().id(), created.connection().version(), requestId(), deadline()));

        assertThat(result.connection().state()).isEqualTo(ConnectionState.DISABLED);
        assertThat(result.operation()).isEmpty();
        assertThat(h.pool.drains).hasSize(1);
        assertThat(h.pool.drains.get(0).reason()).isEqualTo(PoolDrainReason.DISABLED);
    }

    @Test
    void disableFailsClosedOnStaleVersionWithNoSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        var stale = new ConnectionVersion(created.connection().version().value() + 5);

        assertThatThrownBy(() -> h.service.disable(new ConnectionLifecycleService.DisableConnectionCommand(
                        admin(), created.connection().id(), stale, requestId(), deadline())))
                .isInstanceOf(StaleConnectionVersionException.class);

        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(h.pool.drains).isEmpty();
    }

    @Test
    void disableUnknownConnectionIsNotFound() {
        Harness h = new Harness();
        assertThatThrownBy(() -> h.service.disable(new ConnectionLifecycleService.DisableConnectionCommand(
                        admin(), new ConnectionId("con_01J9QK3H0RT8N4YB2C6W9V1MFR"), new ConnectionVersion(1),
                        requestId(), deadline())))
                .isInstanceOf(ConnectionNotFoundException.class);
    }

    @Test
    void disablePreActionAuditFailureCausesNoStateOrPoolSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        h.auditPort.failOnCall(h.auditPort.events().size() + 1);

        assertThatThrownBy(() -> h.service.disable(new ConnectionLifecycleService.DisableConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(unchanged.version()).isEqualTo(created.connection().version());
        assertThat(h.pool.drains).isEmpty();
        assertThat(h.auditPort.events()).hasSize(2);
    }

    /**
     * S5 (round-2 review gap): {@code disable}'s committed state change (the
     * {@code compareAndSet} to DISABLED) is a real external effect that
     * happens before {@link ConnectionLifecycleService#recordSuccessOrIndeterminate};
     * if the *terminal* audit write fails after that effect - unlike the
     * pre-action-audit-failure test above, which fails before any effect -
     * the caller must see {@link IndeterminateLifecycleOutcomeException}
     * carrying no operation id (disable never persists a {@code
     * DurableOperation}), never a false success or a false "nothing
     * happened" failure, and the already-committed DISABLED state and pool
     * drain must survive untouched.
     */
    @Test
    void disableTerminalAuditFailureAfterStateChangeIsIndeterminateWithoutReconcilableOperation() {
        Harness h = new Harness();
        var created = create(h);
        // create(h) leaves 2 audit events; disable's pre-action write is call
        // #3 (must succeed so the state change is reached) and its terminal
        // write is call #4 (must fail).
        h.auditPort.failOnCall(h.auditPort.events().size() + 2);

        var thrown = assertThatThrownBy(() -> h.service.disable(new ConnectionLifecycleService.DisableConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        assertThat(indeterminate.operationId()).isEmpty();
        var disabled = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(disabled.state()).isEqualTo(ConnectionState.DISABLED);
        assertThat(h.pool.drains).hasSize(1);
    }

    @Test
    void deleteWithACurrentSecretRevokesItThenReachesDeleted() {
        Harness h = new Harness();
        var created = create(h);

        var result = h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                admin(), created.connection().id(), created.connection().version(), requestId(), deadline()));

        assertThat(result.connection().state()).isEqualTo(ConnectionState.DELETED);
        assertThat(result.operation().orElseThrow().state()).isEqualTo(OperationState.SUCCEEDED);
        assertThat(h.secretStore.revoked()).hasSize(1);
    }

    @Test
    void deleteRevokeFailureLeavesConnectionTombstonedAndIndeterminateNotBlindlyRepeated() {
        Harness h = new Harness();
        var created = create(h);
        h.secretStore.failRevoke = true;

        var thrown = assertThatThrownBy(() -> h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        var tombstoned = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(tombstoned.state()).isEqualTo(ConnectionState.DELETING);
        assertThat(h.operations.findByWorkspaceAndId(WORKSPACE, indeterminate.operationId().orElseThrow()))
                .isPresent()
                .get()
                .extracting(op -> op.state())
                .isEqualTo(OperationState.INDETERMINATE);
        assertThat(h.secretStore.revoked()).isEmpty();
        assertTerminalEventsNeverReuseThePreActionIndeterminateAuthorizationSignature(h.auditPort.events());
    }

    @Test
    void deleteFinalTombstoneRaceIsIndeterminateAfterRevokeAlreadySucceeded() {
        Harness h = new Harness();
        var created = create(h); // consumes CAS calls #1 (reserve) and #2 (promote)
        h.connections.throwStaleOnCompareAndSetCall(4); // delete: #3 = tombstone, #4 = final DELETED transition

        var thrown = assertThatThrownBy(() -> h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        var tombstoned = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(tombstoned.state()).isEqualTo(ConnectionState.DELETING);
        // The revoke that happens between the tombstone and the final
        // transition already completed before the race hit; it must not be
        // undone or repeated.
        assertThat(h.secretStore.revoked()).hasSize(1);
        assertThat(h.operations.findByWorkspaceAndId(WORKSPACE, indeterminate.operationId().orElseThrow()))
                .isPresent()
                .get()
                .extracting(op -> op.state())
                .isEqualTo(OperationState.INDETERMINATE);
        assertTerminalEventsNeverReuseThePreActionIndeterminateAuthorizationSignature(h.auditPort.events());
    }

    @Test
    void deleteWithoutACurrentSecretReachesDeletedDirectly() {
        Harness h = new Harness();
        var created = create(h);
        // Disable first: still carries the secret, so instead exercise the
        // no-secret path via a fresh reservation that never attached one.
        var reservedOnly = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(reservedOnly.currentSecret()).isPresent(); // sanity: the happy path always has one by now

        // A connection can only lack a current secret while PENDING_SECRET,
        // which delete() must also tombstone cleanly with no revoke call.
        var pendingOnly = com.knowimran.rivoquery.connection.DatabaseConnection.reserve(
                WORKSPACE, new ConnectionId("con_01J9QK3H0RT8N4YB2C6W9V1MFS"), DatabaseKind.POSTGRESQL);
        h.connections.insert(pendingOnly);

        var result = h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                admin(), pendingOnly.id(), pendingOnly.version(), requestId(), deadline()));

        assertThat(result.connection().state()).isEqualTo(ConnectionState.DELETED);
        assertThat(h.secretStore.revoked()).isEmpty();
    }

    @Test
    void deletePreActionAuditFailureCausesNoStateOrSecretSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        h.auditPort.failOnCall(h.auditPort.events().size() + 1);

        assertThatThrownBy(() -> h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(h.secretStore.revoked()).isEmpty();
        assertThat(h.auditPort.events()).hasSize(2);
    }

    @Test
    void deleteTerminalAuditFailureAfterTombstoneAndRevokeIsIndeterminateNotFalseSuccessOrFalseFailure() {
        Harness h = new Harness();
        var created = create(h);
        // create() = 2 events; delete's own pre-action = 3rd append; delete's terminal = 4th append.
        h.auditPort.failOnCall(4);

        var thrown = assertThatThrownBy(() -> h.service.delete(new ConnectionLifecycleService.DeleteConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(IndeterminateLifecycleOutcomeException.class)
                .actual();
        var indeterminate = (IndeterminateLifecycleOutcomeException) thrown;

        var deleted = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(deleted.state()).isEqualTo(ConnectionState.DELETED);
        assertThat(h.secretStore.revoked()).hasSize(1);
        assertThat(h.operations.findByWorkspaceAndId(WORKSPACE, indeterminate.operationId().orElseThrow()))
                .isPresent()
                .get()
                .extracting(op -> op.state())
                .isEqualTo(OperationState.SUCCEEDED);
    }

    @Test
    void updateAdvancesVersionOnlyAndFailsClosedOnStaleVersion() {
        Harness h = new Harness();
        var created = create(h);

        var result = h.service.update(new ConnectionLifecycleService.UpdateConnectionCommand(
                admin(), created.connection().id(), created.connection().version(), requestId(), deadline()));
        assertThat(result.connection().state()).isEqualTo(ConnectionState.ACTIVE);
        assertThat(result.connection().version().value()).isEqualTo(created.connection().version().value() + 1);
        assertThat(result.operation()).isEmpty();

        assertThatThrownBy(() -> h.service.update(new ConnectionLifecycleService.UpdateConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(StaleConnectionVersionException.class);
    }

    @Test
    void updatePreActionAuditFailureCausesNoVersionSideEffect() {
        Harness h = new Harness();
        var created = create(h);
        h.auditPort.failOnCall(h.auditPort.events().size() + 1);

        assertThatThrownBy(() -> h.service.update(new ConnectionLifecycleService.UpdateConnectionCommand(
                        admin(), created.connection().id(), created.connection().version(), requestId(), deadline())))
                .isInstanceOf(PreActionAuditWriteFailedException.class);

        var unchanged = h.connections.findByWorkspaceAndId(WORKSPACE, created.connection().id()).orElseThrow();
        assertThat(unchanged.version()).isEqualTo(created.connection().version());
        assertThat(h.auditPort.events()).hasSize(2);
    }

    // -------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------

    private static ConnectionLifecycleResult create(Harness h) {
        var command = new ConnectionLifecycleService.CreateConnectionCommand(
                admin(), DatabaseKind.POSTGRESQL, ENDPOINT, requestId(), deadline());
        return h.service.create(command, freshSecret());
    }

    private static DestructibleSecretMaterial freshSecret() {
        return new DestructibleSecretMaterial(CANARY_SECRET.getBytes());
    }

    private static ActorContext admin() {
        return LifecycleTestFixtures.administrator(WORKSPACE, ADMIN_ACTOR);
    }

    private static ActorContext member() {
        return LifecycleTestFixtures.member(WORKSPACE, MEMBER_ACTOR, MEMBER_MEMBERSHIP);
    }

    private static AuditRequestId requestId() {
        return new AuditRequestId("req-" + java.util.UUID.randomUUID());
    }

    private static Deadline deadline() {
        return new Deadline(NOW.plusSeconds(60));
    }

    /**
     * Checks every item for the literal canary secret value. For a {@link
     * Throwable}, walks the full {@code getCause()} chain (and each cause's
     * message) - not just {@code toString()} of the outermost exception -
     * because {@link ConnectionLifecycleFailedException} deliberately
     * retains the underlying platform failure as its cause for internal
     * diagnostics; this assertion is what actually proves that chain still
     * never carries secret material, unlike checking {@link Event} objects
     * alone (which have no field that could ever hold secret-shaped data in
     * the first place).
     */
    private static void assertNoSecretLeakage(Object... telemetry) {
        for (Object item : telemetry) {
            Object current = item;
            while (current != null) {
                assertThat(String.valueOf(current)).doesNotContain(CANARY_SECRET);
                if (current instanceof Throwable throwable) {
                    assertThat(String.valueOf(throwable.getMessage())).doesNotContain(CANARY_SECRET);
                    current = throwable.getCause();
                } else {
                    current = null;
                }
            }
        }
    }

    /**
     * {@link LifecycleAuditTrail} deliberately reuses (INDETERMINATE,
     * AUTHORIZATION) for the pre-action row - the closed {@code
     * ConnectionAuditOutcome}/{@code ConnectionAuditCategory} enums have no
     * "attempted"/"started" value (see that class's Javadoc). If a terminal
     * write ever reused the exact same pair, a future reconciler could not
     * tell "this attempt just started" apart from "this attempt concluded"
     * by outcome+category alone. Every service call appends exactly two
     * events in order (pre-action, then terminal) or zero (if the pre-action
     * write itself failed), so in this flat, ordered log every odd index is
     * a terminal row and must never be (INDETERMINATE, AUTHORIZATION).
     */
    private static void assertTerminalEventsNeverReuseThePreActionIndeterminateAuthorizationSignature(
            List<Event> events) {
        for (int i = 1; i < events.size(); i += 2) {
            Event terminal = events.get(i);
            boolean reusesPreActionSignature = terminal.outcome() == ConnectionAuditOutcome.INDETERMINATE
                    && terminal.category() == ConnectionAuditCategory.AUTHORIZATION;
            assertThat(reusesPreActionSignature)
                    .as("terminal event at index %d must not reuse the pre-action (INDETERMINATE, AUTHORIZATION) signature", i)
                    .isFalse();
        }
    }

    private static final class Harness {
        final LifecycleTestFixtures.FakeConnectionRepository connections =
                new LifecycleTestFixtures.FakeConnectionRepository();
        final LifecycleTestFixtures.FakeDurableOperationRepository operations =
                new LifecycleTestFixtures.FakeDurableOperationRepository();
        final LifecycleTestFixtures.FakeConnectionAuditPort auditPort =
                new LifecycleTestFixtures.FakeConnectionAuditPort();
        final LifecycleTestFixtures.FakeSecretStore secretStore = new LifecycleTestFixtures.FakeSecretStore();
        final LifecycleTestFixtures.FakeDestinationPolicy destinationPolicy =
                new LifecycleTestFixtures.FakeDestinationPolicy();
        final LifecycleTestFixtures.FakeDatabaseConnector connector = new LifecycleTestFixtures.FakeDatabaseConnector();
        final LifecycleTestFixtures.FakeTargetPoolManager pool = new LifecycleTestFixtures.FakeTargetPoolManager();
        final InMemoryDiscoveryEnqueuer discovery = new InMemoryDiscoveryEnqueuer();
        final ConnectionLifecycleService service;

        Harness() {
            Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
            var secretAuthorizations = new SecretOperationAuthorizationRegistry(clock, Duration.ofMinutes(5));
            var secretOperations = LifecycleTestFixtures.secretOperations(
                    connections, secretAuthorizations, secretStore, clock);
            var destinationOperations = LifecycleTestFixtures.destinationOperations(destinationPolicy, clock);
            destinationPolicy.nextAssessment = new DestinationAssessment(
                    "db.example.com", "db.example.com", 5432,
                    Set.of(new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1})),
                    "fingerprint-v1", "policy-v1", NOW, NOW.plusSeconds(300));
            service = new ConnectionLifecycleService(
                    connections, operations, auditPort, secretOperations, secretAuthorizations,
                    destinationOperations, () -> "policy-v1",
                    new LifecycleTestFixtures.SingleConnectorSelector(connector), pool, discovery,
                    new SecureRandomCanonicalIdGenerator(), clock);
        }
    }
}
