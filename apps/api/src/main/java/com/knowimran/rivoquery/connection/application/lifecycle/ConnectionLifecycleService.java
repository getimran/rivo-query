package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.InvalidConnectionTransitionException;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.ConnectionAuditAction;
import com.knowimran.rivoquery.connection.application.ConnectionAuditCategory;
import com.knowimran.rivoquery.connection.application.ConnectionAuditOutcome;
import com.knowimran.rivoquery.connection.application.ConnectionAuditPort;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.AuditRequestId;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.DiscoveryEnqueuer;
import com.knowimran.rivoquery.connection.application.DurableOperation;
import com.knowimran.rivoquery.connection.application.DurableOperationRepository;
import com.knowimran.rivoquery.connection.application.OperationAction;
import com.knowimran.rivoquery.connection.application.OperationState;
import com.knowimran.rivoquery.connection.application.destination.ConnectionPurpose;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.connection.application.destination.DestinationOperations;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.DestructibleSecretMaterial;
import com.knowimran.rivoquery.connection.application.secret.DurableSecretAuthorization.AuthorizedOperation;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretOperationKind;
import com.knowimran.rivoquery.connection.application.secret.SecretOperations;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretUsePurpose;
import com.knowimran.rivoquery.connection.application.secret.StagedSecretReference;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.connection.connector.DatabaseConnectorSelector;
import com.knowimran.rivoquery.connection.pool.PoolDrainReason;
import com.knowimran.rivoquery.connection.pool.PoolKey;
import com.knowimran.rivoquery.connection.pool.TargetPoolManager;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.AuthorizationDeniedException;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Connection lifecycle saga: create, update, disable, delete, test, and
 * rotate-secret. See {@link ConnectionQueryService} for list/get, which are
 * plain authorized reads with no audit-port writes at all ({@link
 * ConnectionAuditAction} has no LIST/GET member).
 *
 * <p><b>Audit ordering (non-negotiable, see class Javadoc on
 * {@link LifecycleAuditTrail}):</b> every method here writes a pre-action
 * audit event before touching any state, secret, DNS, or connector; if that
 * write fails, the method throws {@link PreActionAuditWriteFailedException}
 * having done nothing else. Every exit path writes a terminal audit event.
 * Once an external effect has occurred, a terminal-audit-write failure
 * surfaces as {@link IndeterminateLifecycleOutcomeException} rather than a
 * false success or a false "nothing happened" failure.
 *
 * <p><b>Platform-failure boundary (see {@link ConnectionLifecycleFailedException}):</b>
 * any failure this saga catches from a platform adapter (secret store,
 * destination resolver, connector) is translated into that stable, safe
 * exception before it can leave this class - this saga's own package, not
 * any individual platform adapter's message discipline, is what P2-130 may
 * rely on never leaking adapter detail. A small allow-list of this saga's
 * own already-safe exception types is passed through unwrapped; see the
 * private {@code safeguard} helper.
 *
 * <p><b>Deliberately out of scope, see docs/IMPLEMENTATION_TASKS.md P2-100
 * evidence for the full explanation:</b> {@code enable} (not in this task's
 * acceptance list); persisted connection metadata (name/TLS/connectorOptions/
 * objectPolicy/limits/endpoint) - {@link DatabaseConnection} has no columns
 * for any of these yet. {@code create} accepts a caller-supplied, non-persisted
 * {@link StructuredEndpoint} because it is describing a *new* connection the
 * caller is choosing to create, which is a legitimate input; {@code test} and
 * {@code rotateSecret} do NOT accept one (removed as a fix for a credential
 * exfiltration / SSRF finding in the P2-100 security review - see {@link
 * ConnectionEndpointNotYetPersistedException}) and are therefore structurally
 * blocked - they authenticate, authorize, and validate the connection/version
 * exactly as before, then fail closed with {@link
 * ConnectionEndpointNotYetPersistedException} before touching any secret,
 * destination, or connector - until a reviewed V3 migration persists a real
 * endpoint they can read from the connection's own stored state. {@code
 * update} can only advance the resource's version via {@link
 * DatabaseConnection#touch}; connection permission grant/revoke (a separate,
 * simpler concern already fully served by {@code ConnectionPermissionRepository}).
 */
public final class ConnectionLifecycleService {
    private final ConnectionRepository connections;
    private final DurableOperationRepository operations;
    private final LifecycleAuditTrail audit;
    private final SecretOperations secretOperations;
    private final SecretOperationAuthorizationRegistry secretAuthorizations;
    private final DestinationOperations destinationOperations;
    private final CurrentDestinationPolicyVersion currentPolicyVersion;
    private final DatabaseConnectorSelector connectors;
    private final TargetPoolManager pool;
    private final DiscoveryEnqueuer discoveryEnqueuer;
    private final CanonicalIdGenerator idGenerator;
    private final Clock clock;

    public ConnectionLifecycleService(
            ConnectionRepository connections,
            DurableOperationRepository operations,
            ConnectionAuditPort auditPort,
            SecretOperations secretOperations,
            SecretOperationAuthorizationRegistry secretAuthorizations,
            DestinationOperations destinationOperations,
            CurrentDestinationPolicyVersion currentPolicyVersion,
            DatabaseConnectorSelector connectors,
            TargetPoolManager pool,
            DiscoveryEnqueuer discoveryEnqueuer,
            CanonicalIdGenerator idGenerator,
            Clock clock) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.audit = new LifecycleAuditTrail(Objects.requireNonNull(auditPort, "auditPort"), clock);
        this.secretOperations = Objects.requireNonNull(secretOperations, "secretOperations");
        this.secretAuthorizations = Objects.requireNonNull(secretAuthorizations, "secretAuthorizations");
        this.destinationOperations = Objects.requireNonNull(destinationOperations, "destinationOperations");
        this.currentPolicyVersion = Objects.requireNonNull(currentPolicyVersion, "currentPolicyVersion");
        this.connectors = Objects.requireNonNull(connectors, "connectors");
        this.pool = Objects.requireNonNull(pool, "pool");
        this.discoveryEnqueuer = Objects.requireNonNull(discoveryEnqueuer, "discoveryEnqueuer");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // -------------------------------------------------------------------
    // Commands
    // -------------------------------------------------------------------

    public record CreateConnectionCommand(
            ActorContext actor, DatabaseKind kind, StructuredEndpoint endpoint,
            AuditRequestId requestId, Deadline deadline) {
        public CreateConnectionCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(endpoint, "endpoint");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    /**
     * No {@code endpoint} field: see class Javadoc and {@link
     * ConnectionEndpointNotYetPersistedException}. Matches the OpenAPI
     * {@code RotateSecretRequest} contract, which likewise has no endpoint
     * field - only write-only secret-material fields.
     */
    public record RotateSecretCommand(
            ActorContext actor, ConnectionId connectionId, ConnectionVersion expectedVersion,
            AuditRequestId requestId, Deadline deadline) {
        public RotateSecretCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    /**
     * No {@code endpoint} field: see class Javadoc and {@link
     * ConnectionEndpointNotYetPersistedException}. Matches the OpenAPI
     * {@code POST /connections/{id}/test} contract, which has no request
     * body at all.
     */
    public record TestConnectionCommand(
            ActorContext actor, ConnectionId connectionId, ConnectionVersion expectedVersion,
            AuditRequestId requestId, Deadline deadline) {
        public TestConnectionCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record DisableConnectionCommand(
            ActorContext actor, ConnectionId connectionId, ConnectionVersion expectedVersion,
            AuditRequestId requestId, Deadline deadline) {
        public DisableConnectionCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record DeleteConnectionCommand(
            ActorContext actor, ConnectionId connectionId, ConnectionVersion expectedVersion,
            AuditRequestId requestId, Deadline deadline) {
        public DeleteConnectionCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    public record UpdateConnectionCommand(
            ActorContext actor, ConnectionId connectionId, ConnectionVersion expectedVersion,
            AuditRequestId requestId, Deadline deadline) {
        public UpdateConnectionCommand {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(connectionId, "connectionId");
            Objects.requireNonNull(expectedVersion, "expectedVersion");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    // -------------------------------------------------------------------
    // create
    // -------------------------------------------------------------------

    /**
     * The caller owns {@code secretMaterial} and must close it (e.g. via
     * try-with-resources), matching {@code SecretCapabilityAuthority}'s own
     * convention: this method consumes it exactly once on the success path
     * (after which it is already closed/zeroed as a side effect of {@link
     * DestructibleSecretMaterial#consume}), but on every failure path that
     * returns before staging is attempted (pre-action audit failure,
     * authorization denial, a concurrency race on the initial reservation),
     * it is never touched here and remains the caller's responsibility to
     * close.
     */
    public ConnectionLifecycleResult create(CreateConnectionCommand command, DestructibleSecretMaterial secretMaterial) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(secretMaterial, "secretMaterial");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = idGenerator.newConnectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.CREATE);

        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.CREATE);

        // connections.insert() must happen before the first operations.save()
        // below: durable_operations has a non-deferrable composite FK
        // (workspace_id, connection_id) -> connections (workspace_id,
        // connection_id), and this is the very first DurableOperation for
        // this connectionId, so the connection row must already exist for
        // that insert to satisfy the FK. connection_lifecycle_audit_events
        // deliberately has no such FK (ADR-0011 - audit rows may reference a
        // connection_id that was denied/never created), so audit.recordAttempt
        // above is unaffected by this ordering constraint.
        DatabaseConnection reserved = DatabaseConnection.reserve(workspaceId, connectionId, command.kind());
        connections.insert(reserved);

        OperationId operationId = idGenerator.newOperationId();
        operations.save(new DurableOperation(
                operationId, workspaceId, actor.actorId(), Optional.of(connectionId),
                OperationAction.CONNECTION_CREATE, OperationState.RUNNING, false));

        DatabaseConnection versionReserved = reserved.reserveNextSecretVersion(reserved.version());
        try {
            if (!connections.compareAndSet(versionReserved, reserved.version())) {
                operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                        OperationAction.CONNECTION_CREATE, OperationState.FAILED, false));
                throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                        ConnectionAuditAction.CREATE, ConnectionAuditCategory.INTERNAL,
                        new IllegalStateException("connection row disappeared immediately after insert"));
            }
        } catch (StaleConnectionVersionException raceAfterInsert) {
            // Nothing has been staged yet at this point, so there is
            // nothing to compensate - same "no effect" shape as the
            // missing-row branch above, just a different, genuinely
            // possible cause (a concurrent writer raced this brand-new row).
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    OperationAction.CONNECTION_CREATE, OperationState.FAILED, false));
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.CREATE, ConnectionAuditCategory.CONCURRENCY, raceAfterInsert);
        }

        DestinationRequest destinationRequest = new DestinationRequest(
                workspaceId, connectionId, versionReserved.version(), command.kind(),
                ConnectionPurpose.ROTATION_TEST, command.endpoint(), command.deadline());

        SecretBinding binding = new SecretBinding(
                workspaceId, connectionId, SecretPurpose.TARGET_DATABASE_CONNECTION,
                versionReserved.latestSecretVersion());

        StagedSecretReference staged;
        try {
            staged = stageSecret(actor, connectionId, binding, secretMaterial, command.deadline());
        } catch (RuntimeException stageFailure) {
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    OperationAction.CONNECTION_CREATE, OperationState.FAILED, false));
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.CREATE, ConnectionAuditCategory.SECRET_PROVIDER, stageFailure);
        }

        ValidatedDestination destination;
        try {
            destination = destinationOperations.validate(destinationRequest);
        } catch (RuntimeException destinationFailure) {
            throw compensatedFailure(workspaceId, actor, connectionId, requestId, operationId,
                    OperationAction.CONNECTION_CREATE, ConnectionAuditAction.CREATE,
                    ConnectionAuditCategory.DESTINATION_POLICY, destinationFailure, staged.value(), command.deadline());
        }

        ConnectorResult testResult;
        try {
            testResult = testCandidate(
                    workspaceId, connectionId, staged.value(), destination, destinationRequest, command.kind(),
                    command.deadline());
        } catch (RuntimeException candidateTestFailure) {
            throw compensatedFailure(workspaceId, actor, connectionId, requestId, operationId,
                    OperationAction.CONNECTION_CREATE, ConnectionAuditAction.CREATE,
                    categoryOf(candidateTestFailure), causeOf(candidateTestFailure), staged.value(), command.deadline());
        }
        if (!testResult.successful()) {
            throw compensatedFailure(workspaceId, actor, connectionId, requestId, operationId,
                    OperationAction.CONNECTION_CREATE, ConnectionAuditAction.CREATE,
                    ConnectionAuditCategory.CONNECTOR, new ConnectorTestFailedException(testResult.safeCode()),
                    staged.value(), command.deadline());
        }

        DatabaseConnection pendingValidation = versionReserved.attachCurrentSecret(versionReserved.version(), staged.value());
        DatabaseConnection active = pendingValidation.transition(pendingValidation.version(), ConnectionState.ACTIVE);
        try {
            if (!connections.compareAndSet(active, versionReserved.version())) {
                throw new StaleConnectionVersionException();
            }
        } catch (StaleConnectionVersionException raceBeforePromotion) {
            // The verified, staged secret never became durably current: safe
            // to compensate (revoke) even though this is a "post effect #1"
            // path, because effect #1 (the version reservation) carries no
            // secret and no externally observable behavior change.
            throw compensatedFailure(workspaceId, actor, connectionId, requestId, operationId,
                    OperationAction.CONNECTION_CREATE, ConnectionAuditAction.CREATE,
                    ConnectionAuditCategory.CONCURRENCY, raceBeforePromotion, staged.value(), command.deadline());
        }

        bestEffortEnqueueDiscovery(workspaceId, connectionId, active.version());

        operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                OperationAction.CONNECTION_CREATE, OperationState.SUCCEEDED, false));
        recordSuccessOrIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.CREATE, ConnectionAuditCategory.CONNECTOR, Optional.of(operationId));

        return new ConnectionLifecycleResult(active, Optional.of(operations.findByWorkspaceAndId(workspaceId, operationId).orElseThrow()));
    }

    // -------------------------------------------------------------------
    // rotate-secret
    // -------------------------------------------------------------------

    /**
     * Structurally blocked: see class Javadoc and {@link
     * ConnectionEndpointNotYetPersistedException}. This still performs every
     * check that is independent of the missing endpoint (authorization,
     * connection existence/tenancy, optimistic-concurrency version check) so
     * a caller gets the most specific applicable failure, then fails closed
     * before ever staging {@code secretMaterial}. {@code secretMaterial}
     * ownership/closing contract: see {@link #create} - it is never touched
     * by this method today, so it always remains the caller's responsibility
     * to close.
     */
    public ConnectionLifecycleResult rotateSecret(RotateSecretCommand command, DestructibleSecretMaterial secretMaterial) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(secretMaterial, "secretMaterial");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = command.connectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.ROTATE_SECRET);
        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.ROTATE_SECRET);

        DatabaseConnection connection = requireConnectionOrFail(
                workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.ROTATE_SECRET);
        connection.authorize(actor);
        try {
            connection.requireVersion(command.expectedVersion());
        } catch (StaleConnectionVersionException staleVersion) {
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.ROTATE_SECRET, ConnectionAuditCategory.CONCURRENCY, staleVersion);
        }

        throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.ROTATE_SECRET, ConnectionAuditCategory.INTERNAL,
                new ConnectionEndpointNotYetPersistedException());
    }

    // -------------------------------------------------------------------
    // test
    // -------------------------------------------------------------------

    /**
     * Structurally blocked: see class Javadoc and {@link
     * ConnectionEndpointNotYetPersistedException}. This still performs every
     * check that is independent of the missing endpoint (authorization,
     * connection existence/tenancy, optimistic-concurrency version check) so
     * a caller gets the most specific applicable failure, then fails closed
     * before ever touching the connection's current secret or a connector.
     */
    public ConnectionLifecycleResult test(TestConnectionCommand command) {
        Objects.requireNonNull(command, "command");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = command.connectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.TEST);
        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.TEST);

        DatabaseConnection connection = requireConnectionOrFail(
                workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.TEST);
        connection.authorize(actor);
        try {
            connection.requireVersion(command.expectedVersion());
        } catch (StaleConnectionVersionException staleVersion) {
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.TEST, ConnectionAuditCategory.CONCURRENCY, staleVersion);
        }

        throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.TEST, ConnectionAuditCategory.INTERNAL,
                new ConnectionEndpointNotYetPersistedException());
    }

    // -------------------------------------------------------------------
    // disable
    // -------------------------------------------------------------------

    public ConnectionLifecycleResult disable(DisableConnectionCommand command) {
        Objects.requireNonNull(command, "command");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = command.connectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.DISABLE);
        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.DISABLE);

        DatabaseConnection connection = requireConnectionOrFail(
                workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.DISABLE);
        connection.authorize(actor);

        DatabaseConnection disabled;
        try {
            disabled = connection.transition(command.expectedVersion(), ConnectionState.DISABLED);
            if (!connections.compareAndSet(disabled, command.expectedVersion())) {
                throw new StaleConnectionVersionException();
            }
        } catch (StaleConnectionVersionException | InvalidConnectionTransitionException domainFailure) {
            ConnectionAuditCategory category = domainFailure instanceof StaleConnectionVersionException
                    ? ConnectionAuditCategory.CONCURRENCY : ConnectionAuditCategory.VALIDATION;
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.DISABLE, category, domainFailure);
        }

        connection.currentSecret().ifPresent(secret -> bestEffortDrain(
                workspaceId, connectionId, connection.version(), secret.binding().version(),
                PoolDrainReason.DISABLED, command.deadline()));

        recordSuccessOrIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.DISABLE, ConnectionAuditCategory.INTERNAL, Optional.empty());

        return new ConnectionLifecycleResult(disabled, Optional.empty());
    }

    // -------------------------------------------------------------------
    // delete (tombstoned)
    // -------------------------------------------------------------------

    public ConnectionLifecycleResult delete(DeleteConnectionCommand command) {
        Objects.requireNonNull(command, "command");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = command.connectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.DELETE);
        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.DELETE);

        DatabaseConnection connection = requireConnectionOrFail(
                workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.DELETE);
        connection.authorize(actor);

        OperationId operationId = idGenerator.newOperationId();
        operations.save(new DurableOperation(
                operationId, workspaceId, actor.actorId(), Optional.of(connectionId),
                OperationAction.CONNECTION_DELETE, OperationState.RUNNING, false));

        DatabaseConnection tombstoned;
        try {
            tombstoned = connection.transition(command.expectedVersion(), ConnectionState.DELETING);
            if (!connections.compareAndSet(tombstoned, command.expectedVersion())) {
                throw new StaleConnectionVersionException();
            }
        } catch (StaleConnectionVersionException | InvalidConnectionTransitionException domainFailure) {
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    OperationAction.CONNECTION_DELETE, OperationState.FAILED, false));
            ConnectionAuditCategory category = domainFailure instanceof StaleConnectionVersionException
                    ? ConnectionAuditCategory.CONCURRENCY : ConnectionAuditCategory.VALIDATION;
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.DELETE, category, domainFailure);
        }

        Optional<BoundSecretReference> oldSecret = connection.currentSecret();
        if (oldSecret.isEmpty()) {
            return finishDelete(workspaceId, actor, connectionId, requestId, operationId, tombstoned, command.deadline());
        }

        try {
            revokeSecret(workspaceId, connectionId, oldSecret.orElseThrow(), command.deadline());
        } catch (RuntimeException revokeFailure) {
            // The tombstone (effect #1) already happened and cannot be
            // safely undone; cleanup did not complete. This is exactly the
            // "safe indeterminate failure, reconcilable, do not blindly
            // repeat" case: the connection stays DELETING (already inert -
            // ConnectionState.isPublicState() excludes neither DELETING nor
            // ACTIVE from visibility, but no further transition/attach is
            // reachable from DELETING except DELETED, so it cannot be
            // reused), and the DurableOperation stays available for a future
            // reconciliation pass to retry the revoke without repeating the
            // tombstone transition.
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    OperationAction.CONNECTION_DELETE, OperationState.INDETERMINATE, false));
            throw terminalIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.DELETE, ConnectionAuditCategory.SECRET_PROVIDER, operationId);
        }

        connection.currentSecret().ifPresent(secret -> bestEffortDrain(
                workspaceId, connectionId, connection.version(), secret.binding().version(),
                PoolDrainReason.DELETING, command.deadline()));

        return finishDelete(workspaceId, actor, connectionId, requestId, operationId, tombstoned, command.deadline());
    }

    private ConnectionLifecycleResult finishDelete(
            WorkspaceId workspaceId, ActorContext actor, ConnectionId connectionId, AuditRequestId requestId,
            OperationId operationId, DatabaseConnection tombstoned, Deadline deadline) {
        DatabaseConnection deleted = tombstoned.transition(tombstoned.version(), ConnectionState.DELETED);
        try {
            if (!connections.compareAndSet(deleted, tombstoned.version())) {
                throw new StaleConnectionVersionException();
            }
        } catch (StaleConnectionVersionException raceAfterTombstone) {
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    OperationAction.CONNECTION_DELETE, OperationState.INDETERMINATE, false));
            throw terminalIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.DELETE, ConnectionAuditCategory.CONCURRENCY, operationId);
        }
        operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                OperationAction.CONNECTION_DELETE, OperationState.SUCCEEDED, false));
        recordSuccessOrIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.DELETE, ConnectionAuditCategory.INTERNAL, Optional.of(operationId));
        return new ConnectionLifecycleResult(deleted, Optional.of(operations.findByWorkspaceAndId(workspaceId, operationId).orElseThrow()));
    }

    // -------------------------------------------------------------------
    // update (metadata-only; see class Javadoc for why this is a version
    // bump today, not a real field mutation)
    // -------------------------------------------------------------------

    public ConnectionLifecycleResult update(UpdateConnectionCommand command) {
        Objects.requireNonNull(command, "command");
        ActorContext actor = command.actor();
        WorkspaceId workspaceId = actor.workspaceId();
        ConnectionId connectionId = command.connectionId();
        AuditRequestId requestId = command.requestId();

        audit.recordAttempt(workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.UPDATE);
        requireAdministratorOrDeny(actor, workspaceId, connectionId, requestId, ConnectionAuditAction.UPDATE);

        DatabaseConnection connection = requireConnectionOrFail(
                workspaceId, actor.actorId(), connectionId, requestId, ConnectionAuditAction.UPDATE);
        connection.authorize(actor);

        DatabaseConnection touched;
        try {
            touched = connection.touch(command.expectedVersion());
            if (!connections.compareAndSet(touched, command.expectedVersion())) {
                throw new StaleConnectionVersionException();
            }
        } catch (StaleConnectionVersionException | InvalidConnectionTransitionException domainFailure) {
            ConnectionAuditCategory category = domainFailure instanceof StaleConnectionVersionException
                    ? ConnectionAuditCategory.CONCURRENCY : ConnectionAuditCategory.VALIDATION;
            throw terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId,
                    ConnectionAuditAction.UPDATE, category, domainFailure);
        }

        recordSuccessOrIndeterminate(workspaceId, actor.actorId(), connectionId, requestId,
                ConnectionAuditAction.UPDATE, ConnectionAuditCategory.VALIDATION, Optional.empty());

        return new ConnectionLifecycleResult(touched, Optional.empty());
    }

    // -------------------------------------------------------------------
    // shared helpers
    // -------------------------------------------------------------------

    private void requireAdministratorOrDeny(
            ActorContext actor, WorkspaceId workspaceId, ConnectionId connectionId, AuditRequestId requestId,
            ConnectionAuditAction action) {
        try {
            actor.requireAdministrator();
        } catch (AuthorizationDeniedException denied) {
            try {
                audit.recordTerminal(workspaceId, actor.actorId(), connectionId, requestId, action,
                        ConnectionAuditOutcome.DENIED, ConnectionAuditCategory.AUTHORIZATION);
            } catch (RuntimeException ignoredAuditFailure) {
                // No side effect has occurred; the caller still sees the
                // original, safe AuthorizationDeniedException either way.
            }
            throw denied;
        }
    }

    private DatabaseConnection requireConnectionOrFail(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId, AuditRequestId requestId,
            ConnectionAuditAction action) {
        return connections.findByWorkspaceAndId(workspaceId, connectionId)
                .orElseThrow(() -> terminalFailureNoEffect(
                        workspaceId, actorId, connectionId, requestId, action,
                        ConnectionAuditCategory.VALIDATION, new ConnectionNotFoundException()));
    }

    private StagedSecretReference stageSecret(
            ActorContext actor, ConnectionId connectionId, SecretBinding binding,
            DestructibleSecretMaterial material, Deadline deadline) {
        OperationId stageOperationId = idGenerator.newOperationId();
        secretAuthorizations.register(new AuthorizedOperation(
                actor.workspaceId(), stageOperationId, Optional.of(actor.actorId()), SecretOperationKind.STAGE,
                binding, Optional.empty(), Optional.empty(), false));
        try {
            return secretOperations.stage(actor, connectionId, binding, material, stageOperationId, deadline);
        } finally {
            secretAuthorizations.release(actor.workspaceId(), stageOperationId);
        }
    }

    /**
     * Runs the candidate secret through a real connector test against the
     * already-validated destination, freshly re-checked at consumption time
     * ({@code ValidatedDestination.requireValid}'s own contract). Any
     * failure raised specifically by the destination re-check or the
     * connector call is tagged with its real category via {@link
     * CategorizedFailure} (see {@link #categoryOf}/{@link #causeOf}) rather
     * than letting the caller assume every failure in this method is a
     * secret-provider failure just because the outer call is {@code
     * consumeForOperation}.
     */
    private ConnectorResult testCandidate(
            WorkspaceId workspaceId, ConnectionId connectionId, BoundSecretReference candidate,
            ValidatedDestination destination, DestinationRequest destinationRequest, DatabaseKind kind,
            Deadline deadline) {
        OperationId accessOperationId = idGenerator.newOperationId();
        secretAuthorizations.register(new AuthorizedOperation(
                workspaceId, accessOperationId, Optional.empty(), SecretOperationKind.ACCESS,
                candidate.binding(), Optional.of(candidate.reference()), Optional.of(SecretUsePurpose.ROTATION_TEST), false));
        ConnectorResult[] captured = new ConnectorResult[1];
        try {
            secretOperations.consumeForOperation(
                    workspaceId, accessOperationId, connectionId, candidate, SecretUsePurpose.ROTATION_TEST, deadline,
                    material -> {
                        try {
                            destination.requireValid(destinationRequest, currentPolicyVersion.value(), clock);
                        } catch (RuntimeException destinationRecheckFailure) {
                            throw new CategorizedFailure(ConnectionAuditCategory.DESTINATION_POLICY, destinationRecheckFailure);
                        }
                        try {
                            captured[0] = connectors.select(kind).test(destination, material, deadline);
                        } catch (RuntimeException connectorFailure) {
                            throw new CategorizedFailure(ConnectionAuditCategory.CONNECTOR, connectorFailure);
                        }
                    });
        } finally {
            secretAuthorizations.release(workspaceId, accessOperationId);
        }
        return captured[0];
    }

    /**
     * Failure raised while resolving/consuming the candidate secret itself
     * (outside {@link #testCandidate}'s callback) defaults to {@link
     * ConnectionAuditCategory#SECRET_PROVIDER}; a failure raised inside the
     * callback carries its own real category via {@link CategorizedFailure}.
     */
    private static ConnectionAuditCategory categoryOf(RuntimeException failure) {
        return failure instanceof CategorizedFailure categorized
                ? categorized.category
                : ConnectionAuditCategory.SECRET_PROVIDER;
    }

    /** Unwraps {@link CategorizedFailure} back to the real underlying cause; see {@link #categoryOf}. */
    private static RuntimeException causeOf(RuntimeException failure) {
        if (failure instanceof CategorizedFailure categorized) {
            Throwable cause = categorized.getCause();
            return cause instanceof RuntimeException runtimeCause ? runtimeCause : categorized;
        }
        return failure;
    }

    /**
     * Internal-only signal from {@link #testCandidate}'s callback to its
     * caller, carrying the failure's real {@link ConnectionAuditCategory}.
     * Always unwrapped via {@link #causeOf} before reaching {@link
     * #terminalFailureNoEffect}; never itself exposed to a caller of this
     * service.
     */
    private static final class CategorizedFailure extends RuntimeException {
        private final ConnectionAuditCategory category;

        CategorizedFailure(ConnectionAuditCategory category, RuntimeException cause) {
            super(cause);
            this.category = category;
        }
    }

    private void revokeSecret(
            WorkspaceId workspaceId, ConnectionId connectionId, BoundSecretReference secret, Deadline deadline) {
        OperationId revokeOperationId = idGenerator.newOperationId();
        secretAuthorizations.register(new AuthorizedOperation(
                workspaceId, revokeOperationId, Optional.empty(), SecretOperationKind.REVOKE,
                secret.binding(), Optional.of(secret.reference()), Optional.empty(), false));
        try {
            secretOperations.revokeForOperation(workspaceId, revokeOperationId, connectionId, secret, deadline);
        } finally {
            secretAuthorizations.release(workspaceId, revokeOperationId);
        }
    }

    /**
     * Best-effort compensation: returns {@code true} if the candidate secret
     * was durably revoked, {@code false} if the revoke attempt itself
     * failed. A {@code false} result means a staged-but-unrevoked secret now
     * exists at a specific (workspace, connection, version) with no marker
     * other than the caller recording the outcome as {@code INDETERMINATE}
     * (not {@code FAILED}) - see {@link #compensatedFailure}. It can never
     * become reachable/usable (never attached as current), but is not
     * cleanly gone either; a future reconciliation pass (not built by this
     * task) can retry revocation using the same binding recorded in {@code
     * connection_secret_references}.
     */
    private boolean revokeQuietly(
            WorkspaceId workspaceId, ConnectionId connectionId, BoundSecretReference secret, Deadline deadline) {
        try {
            revokeSecret(workspaceId, connectionId, secret, deadline);
            return true;
        } catch (RuntimeException compensationFailure) {
            return false;
        }
    }

    /**
     * Shared tail for every {@code create} failure path that must first
     * compensate (revoke) a previously staged candidate secret. Reports the
     * outcome as {@code FAILED} (safe, nothing reachable remains) only if
     * compensation itself succeeded; if compensation failed, reports {@code
     * INDETERMINATE} instead so the orphaned staged secret is discoverable
     * by a future reconciler rather than silently lost behind a {@code
     * FAILED} row (see {@link #revokeQuietly}).
     */
    private RuntimeException compensatedFailure(
            WorkspaceId workspaceId, ActorContext actor, ConnectionId connectionId, AuditRequestId requestId,
            OperationId operationId, OperationAction operationAction, ConnectionAuditAction action,
            ConnectionAuditCategory category, RuntimeException cause, BoundSecretReference staged, Deadline deadline) {
        boolean compensated = revokeQuietly(workspaceId, connectionId, staged, deadline);
        if (compensated) {
            operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                    operationAction, OperationState.FAILED, false));
            return terminalFailureNoEffect(workspaceId, actor.actorId(), connectionId, requestId, action, category, cause);
        }
        operations.save(terminalOperation(operationId, workspaceId, actor.actorId(), connectionId,
                operationAction, OperationState.INDETERMINATE, false));
        return terminalIndeterminate(workspaceId, actor.actorId(), connectionId, requestId, action, category, operationId);
    }

    private void bestEffortDrain(
            WorkspaceId workspaceId, ConnectionId connectionId, ConnectionVersion connectionVersion,
            long secretVersion, PoolDrainReason reason, Deadline deadline) {
        try {
            PoolKey key = new PoolKey(workspaceId, connectionId, connectionVersion, secretVersion, currentPolicyVersion.value());
            pool.stopNewBorrowsAndDrain(key, reason, deadline);
        } catch (RuntimeException drainFailure) {
            // Best-effort: no pool implementation exists yet (P5-100), and a
            // drain failure must never block or reverse an already-committed
            // connection-state change.
        }
    }

    private void bestEffortEnqueueDiscovery(WorkspaceId workspaceId, ConnectionId connectionId, ConnectionVersion connectionVersion) {
        try {
            discoveryEnqueuer.enqueue(workspaceId, connectionId, connectionVersion, idGenerator.newOperationId());
        } catch (RuntimeException enqueueFailure) {
            // Best-effort: discovery enqueue is a placeholder (see
            // InMemoryDiscoveryEnqueuer) and must never fail an otherwise
            // successful connection creation.
        }
    }

    private DurableOperation terminalOperation(
            OperationId operationId, WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId,
            OperationAction action, OperationState state, boolean cancelled) {
        return new DurableOperation(operationId, workspaceId, actorId, Optional.of(connectionId), action, state, cancelled);
    }

    private RuntimeException terminalFailureNoEffect(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId, AuditRequestId requestId,
            ConnectionAuditAction action, ConnectionAuditCategory category, RuntimeException cause) {
        RuntimeException safeFailure = safeguard(category, cause);
        try {
            audit.recordTerminal(workspaceId, actorId, connectionId, requestId, action,
                    ConnectionAuditOutcome.FAILED, category);
        } catch (RuntimeException auditFailure) {
            // No external effect happened on this path, so the caller still
            // safely sees the same original failure either way.
        }
        return safeFailure;
    }

    /**
     * Wraps {@code cause} in {@link ConnectionLifecycleFailedException}
     * unless it is already one of this saga's own stable, safe exception
     * types - see that exception's Javadoc for why this boundary exists and
     * exactly what crosses it unwrapped.
     */
    private static RuntimeException safeguard(ConnectionAuditCategory category, RuntimeException cause) {
        if (cause instanceof ConnectorTestFailedException
                || cause instanceof ConnectionNotFoundException
                || cause instanceof StaleConnectionVersionException
                || cause instanceof InvalidConnectionTransitionException
                || cause instanceof ConnectionEndpointNotYetPersistedException
                || cause instanceof ConnectionLifecycleFailedException) {
            return cause;
        }
        return new ConnectionLifecycleFailedException(category, cause);
    }

    private IndeterminateLifecycleOutcomeException terminalIndeterminate(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId, AuditRequestId requestId,
            ConnectionAuditAction action, ConnectionAuditCategory category, OperationId operationId) {
        try {
            audit.recordTerminal(workspaceId, actorId, connectionId, requestId, action,
                    ConnectionAuditOutcome.INDETERMINATE, category);
        } catch (RuntimeException auditFailure) {
            // Even the INDETERMINATE terminal write failed; the already
            // persisted DurableOperation row remains the reconciliation
            // source of truth regardless.
        }
        return new IndeterminateLifecycleOutcomeException(operationId);
    }

    private void recordSuccessOrIndeterminate(
            WorkspaceId workspaceId, ActorId actorId, ConnectionId connectionId, AuditRequestId requestId,
            ConnectionAuditAction action, ConnectionAuditCategory category, Optional<OperationId> operationId) {
        try {
            audit.recordTerminal(workspaceId, actorId, connectionId, requestId, action,
                    ConnectionAuditOutcome.SUCCEEDED, category);
        } catch (RuntimeException auditFailure) {
            throw operationId.map(IndeterminateLifecycleOutcomeException::new)
                    .orElseGet(IndeterminateLifecycleOutcomeException::withoutReconcilableOperation);
        }
    }
}
