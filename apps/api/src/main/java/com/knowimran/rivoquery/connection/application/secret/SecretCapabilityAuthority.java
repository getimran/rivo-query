package com.knowimran.rivoquery.connection.application.secret;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.OperationId;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

public final class SecretCapabilityAuthority implements SecretOperations {
    private final ConnectionRepository connections;
    private final DurableSecretAuthorization durableAuthorization;
    private final SecretStore secretStore;
    private final GrantNonceSource nonceSource;
    private final Clock clock;
    private final Duration maximumGrantLifetime;

    public SecretCapabilityAuthority(
            ConnectionRepository connections,
            DurableSecretAuthorization durableAuthorization,
            SecretStore secretStore,
            GrantNonceSource nonceSource,
            Clock clock,
            Duration maximumGrantLifetime) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.durableAuthorization = Objects.requireNonNull(durableAuthorization, "durableAuthorization");
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.nonceSource = Objects.requireNonNull(nonceSource, "nonceSource");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.maximumGrantLifetime = Objects.requireNonNull(maximumGrantLifetime, "maximumGrantLifetime");
        if (maximumGrantLifetime.isZero() || maximumGrantLifetime.isNegative()) {
            throw new IllegalArgumentException("maximum grant lifetime must be positive");
        }
    }

    @Override
    public StagedSecretReference stage(
            ActorContext actor,
            ConnectionId connectionId,
            SecretBinding binding,
            DestructibleSecretMaterial material,
            OperationId operationId,
            Deadline deadline) {
        Objects.requireNonNull(actor, "actor").requireAdministrator();
        var connection = load(actor.workspaceId(), connectionId);
        connection.authorize(actor);
        Objects.requireNonNull(binding, "binding").requireConnection(connection.workspaceId(), connection.id());
        var operation = loadOperation(actor.workspaceId(), operationId);
        if (binding.version() != connection.latestSecretVersion()
                || connection.currentSecret()
                        .filter(current -> current.binding().version() >= binding.version())
                        .isPresent()
                || operation.kind() != SecretOperationKind.STAGE
                || operation.cancelled()
                || !operation.actorId().equals(java.util.Optional.of(actor.actorId()))
                || !operation.binding().equals(binding)
                || operation.reference().isPresent()
                || operation.usePurpose().isPresent()) {
            throw new SecretAuthorizationException();
        }
        var bounded = bounded(deadline);
        var grant = new MutationGrant(
                binding, connection.version(), operationId.value(), clock.instant(), bounded, requireNonce());
        return secretStore.stage(binding, Objects.requireNonNull(material, "material"), grant, bounded);
    }

    @Override
    public void consume(
            ActorContext actor,
            ConnectionId connectionId,
            SecretUsePurpose usePurpose,
            Deadline deadline,
            SecretMaterialConsumer consumer) {
        Objects.requireNonNull(actor, "actor").requireAdministrator();
        if (usePurpose == SecretUsePurpose.ROTATION_TEST
                || usePurpose == SecretUsePurpose.PHYSICAL_POOL_CONNECTION) {
            throw new SecretAuthorizationException();
        }
        var connection = load(actor.workspaceId(), connectionId);
        connection.authorize(actor);
        var secret = requireCurrentAccess(connection, usePurpose);
        consumeResolved(
                secret,
                connection.version(),
                usePurpose,
                actor.actorId().value(),
                bounded(deadline),
                consumer);
    }

    @Override
    public void consumeForOperation(
            WorkspaceId workspaceId,
            OperationId operationId,
            ConnectionId connectionId,
            BoundSecretReference secret,
            SecretUsePurpose usePurpose,
            Deadline deadline,
            SecretMaterialConsumer consumer) {
        var connection = load(workspaceId, connectionId);
        Objects.requireNonNull(secret, "secret").binding().requireConnection(workspaceId, connectionId);
        var operation = loadOperation(workspaceId, operationId);
        if (operation.kind() != SecretOperationKind.ACCESS
                || operation.cancelled()
                || !operation.binding().equals(secret.binding())
                || !operation.reference().equals(java.util.Optional.of(secret.reference()))
                || !operation.usePurpose().equals(java.util.Optional.of(usePurpose))) {
            throw new SecretAuthorizationException();
        }
        if (usePurpose != SecretUsePurpose.ROTATION_TEST) {
            var current = requireCurrentAccess(connection, usePurpose);
            if (!current.equals(secret)) {
                throw new SecretAuthorizationException();
            }
        } else if (secret.binding().version() != connection.latestSecretVersion()
                || connection.currentSecret().filter(secret::equals).isPresent()) {
            throw new SecretAuthorizationException();
        }
        consumeResolved(
                secret,
                connection.version(),
                usePurpose,
                operationId.value(),
                bounded(deadline),
                consumer);
    }

    @Override
    public void revokeForOperation(
            WorkspaceId workspaceId,
            OperationId operationId,
            ConnectionId connectionId,
            BoundSecretReference secret,
            Deadline deadline) {
        var connection = load(workspaceId, connectionId);
        Objects.requireNonNull(secret, "secret").binding().requireConnection(workspaceId, connectionId);
        var operation = loadOperation(workspaceId, operationId);
        if (operation.kind() != SecretOperationKind.REVOKE
                || operation.cancelled()
                || !operation.binding().equals(secret.binding())
                || !operation.reference().equals(java.util.Optional.of(secret.reference()))
                || operation.usePurpose().isPresent()
                || secret.binding().version() > connection.latestSecretVersion()
                || connection.currentSecret().filter(secret::equals).isPresent()) {
            throw new SecretAuthorizationException();
        }
        var bounded = bounded(deadline);
        var grant = new RevocationGrant(
                secret.binding(), connection.version(), secret.reference(),
                operationId.value(), clock.instant(), bounded, requireNonce());
        secretStore.revoke(secret.reference(), grant, bounded);
    }

    private void consumeResolved(
            BoundSecretReference secret,
            ConnectionVersion connectionVersion,
            SecretUsePurpose usePurpose,
            String authorityId,
            Deadline deadline,
            SecretMaterialConsumer consumer) {
        var grant = new AccessGrant(
                secret.binding(),
                connectionVersion,
                secret.reference(),
                usePurpose,
                authorityId,
                clock.instant(),
                deadline,
                requireNonce());
        try (var lease = secretStore.resolve(secret.reference(), grant, deadline)) {
            lease.consume(Objects.requireNonNull(consumer, "consumer"));
        }
    }

    private BoundSecretReference requireCurrentAccess(
            DatabaseConnection connection, SecretUsePurpose usePurpose) {
        boolean allowed = switch (usePurpose) {
            case CONNECTION_TEST -> connection.state() == ConnectionState.PENDING_VALIDATION
                    || connection.state() == ConnectionState.ACTIVE
                    || (connection.state() == ConnectionState.FAILED_VALIDATION
                            && connection.secretRetireAt().isPresent()
                            && clock.instant().isBefore(connection.secretRetireAt().orElseThrow()));
            case PHYSICAL_POOL_CONNECTION -> connection.state() == ConnectionState.ACTIVE;
            case ROTATION_TEST -> false;
        };
        if (!allowed) {
            throw new SecretAuthorizationException();
        }
        return connection.currentSecret().orElseThrow(SecretAuthorizationException::new);
    }

    private DurableSecretAuthorization.AuthorizedOperation loadOperation(
            WorkspaceId workspaceId, OperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        var operation = durableAuthorization.findByWorkspaceAndId(workspaceId, operationId)
                .orElseThrow(SecretAuthorizationException::new);
        if (!operation.workspaceId().equals(workspaceId) || !operation.operationId().equals(operationId)) {
            throw new SecretAuthorizationException();
        }
        return operation;
    }

    private DatabaseConnection load(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        return connections.findByWorkspaceAndId(workspaceId, connectionId)
                .orElseThrow(SecretAuthorizationException::new);
    }

    private Deadline bounded(Deadline callerDeadline) {
        Objects.requireNonNull(callerDeadline, "deadline").requireUsable(clock);
        Instant maximum = clock.instant().plus(maximumGrantLifetime);
        return new Deadline(callerDeadline.expiresAt().isBefore(maximum) ? callerDeadline.expiresAt() : maximum);
    }

    private String requireNonce() {
        String nonce = Objects.requireNonNull(nonceSource.newNonce(), "nonce");
        if (nonce.isBlank()) {
            throw new IllegalStateException("grant nonce is invalid");
        }
        return nonce;
    }

    public sealed interface SecretAccessGrant permits AccessGrant {
        SecretBinding binding();
        ConnectionVersion connectionVersion();
        SecretReference reference();
        SecretUsePurpose usePurpose();
        String authorityId();
        Instant issuedAt();
        Deadline deadline();
        String nonce();
    }

    public sealed interface SecretMutationGrant permits MutationGrant {
        SecretBinding binding();
        ConnectionVersion connectionVersion();
        String authorityId();
        Instant issuedAt();
        Deadline deadline();
        String nonce();
    }

    public sealed interface SecretRevocationGrant permits RevocationGrant {
        SecretBinding binding();
        ConnectionVersion connectionVersion();
        SecretReference reference();
        String authorityId();
        Instant issuedAt();
        Deadline deadline();
        String nonce();
    }

    private record AccessGrant(
            SecretBinding binding,
            ConnectionVersion connectionVersion,
            SecretReference reference,
            SecretUsePurpose usePurpose,
            String authorityId,
            Instant issuedAt,
            Deadline deadline,
            String nonce) implements SecretAccessGrant {
        private AccessGrant {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(connectionVersion, "connectionVersion");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(usePurpose, "usePurpose");
            Objects.requireNonNull(authorityId, "authorityId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(nonce, "nonce");
        }

        @Override public String toString() { return "SecretAccessGrant[REDACTED]"; }
    }

    private record MutationGrant(
            SecretBinding binding,
            ConnectionVersion connectionVersion,
            String authorityId,
            Instant issuedAt,
            Deadline deadline,
            String nonce) implements SecretMutationGrant {
        private MutationGrant {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(connectionVersion, "connectionVersion");
            Objects.requireNonNull(authorityId, "authorityId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(nonce, "nonce");
        }

        @Override public String toString() { return "SecretMutationGrant[REDACTED]"; }
    }

    private record RevocationGrant(
            SecretBinding binding,
            ConnectionVersion connectionVersion,
            SecretReference reference,
            String authorityId,
            Instant issuedAt,
            Deadline deadline,
            String nonce) implements SecretRevocationGrant {
        private RevocationGrant {
            Objects.requireNonNull(binding, "binding");
            Objects.requireNonNull(connectionVersion, "connectionVersion");
            Objects.requireNonNull(reference, "reference");
            Objects.requireNonNull(authorityId, "authorityId");
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(deadline, "deadline");
            Objects.requireNonNull(nonce, "nonce");
        }

        @Override public String toString() { return "SecretRevocationGrant[REDACTED]"; }
    }
}
