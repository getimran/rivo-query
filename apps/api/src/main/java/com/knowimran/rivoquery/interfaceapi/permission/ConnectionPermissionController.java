package com.knowimran.rivoquery.interfaceapi.permission;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.application.lifecycle.ConnectionQueryService;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import com.knowimran.rivoquery.interfaceapi.generated.api.ConnectionPermissionsApi;
import com.knowimran.rivoquery.interfaceapi.generated.model.PermissionCollection;
import com.knowimran.rivoquery.interfaceapi.generated.model.ReplacePermissionRequest;
import com.knowimran.rivoquery.interfaceapi.support.ActorResolver;
import com.knowimran.rivoquery.interfaceapi.support.EtagCodec;
import com.knowimran.rivoquery.interfaceapi.support.IdempotencyGuard;
import com.knowimran.rivoquery.interfaceapi.support.RequestFingerprint;
import com.knowimran.rivoquery.interfaceapi.support.ValidationFailedException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wraps {@link ConnectionPermissionRepository} for explicit, non-{@code
 * MANAGE} member grants. Every method's first line is {@code
 * actorResolver.resolve(request())} followed by {@code
 * actor.requireAdministrator()} (auth-before-load, PD2-003, matching {@code
 * ConnectionController}): viewing or mutating who can access a connection is
 * itself an administration surface, not exposed to ordinary members.
 *
 * <p>Every operation looks up the connection via {@link
 * ConnectionQueryService#get} first (a tenant-scoped, indistinguishable
 * {@code CONNECTION_NOT_FOUND} for an unknown or foreign-tenant id) before
 * touching the permission collection at all. {@code replace}/{@code revoke}
 * additionally validate the grant target via {@link
 * CurrentMembershipRepository#findActiveMembership}, folding "unknown",
 * "inactive", "foreign workspace", and "administrator role" into the single
 * indistinguishable {@link MembershipNotEligibleException} the OpenAPI
 * contract requires.
 */
@RestController
public class ConnectionPermissionController implements ConnectionPermissionsApi {

    private static final int DEFAULT_PAGE_SIZE = 50;

    private final ActorResolver actorResolver;
    private final ConnectionQueryService connections;
    private final ConnectionPermissionRepository permissions;
    private final CurrentMembershipRepository memberships;
    private final IdempotencyGuard idempotency;
    private final ObjectProvider<HttpServletRequest> requestProvider;

    /** See {@code OperationController}'s identical Javadoc for why {@link ObjectProvider} rather than {@link HttpServletRequest}. */
    public ConnectionPermissionController(
            ActorResolver actorResolver,
            ConnectionQueryService connections,
            ConnectionPermissionRepository permissions,
            CurrentMembershipRepository memberships,
            IdempotencyGuard idempotency,
            ObjectProvider<HttpServletRequest> requestProvider) {
        this.actorResolver = Objects.requireNonNull(actorResolver, "actorResolver");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.memberships = Objects.requireNonNull(memberships, "memberships");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
        this.requestProvider = Objects.requireNonNull(requestProvider, "requestProvider");
    }

    private HttpServletRequest request() {
        return requestProvider.getObject();
    }

    @Override
    public ResponseEntity<PermissionCollection> listConnectionPermissions(
            String connectionIdValue, String xRivoQueryWorkspaceId, String xRequestId, String cursor, Integer limit) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        connections.get(actor, connectionId);

        Optional<MembershipId> after = parseCursor(cursor);
        int pageSize = limit == null ? DEFAULT_PAGE_SIZE : limit;
        List<ConnectionPermissionGrant> page = permissions.findPage(actor.workspaceId(), connectionId, after, pageSize);
        long version = permissions.currentCollectionVersion(actor.workspaceId(), connectionId);

        PermissionCollection body = ConnectionPermissionDtoMapper.toCollection(version, page);
        if (page.size() == pageSize && !page.isEmpty()) {
            body.setNextCursor(page.get(page.size() - 1).membershipId().value());
        }
        return ResponseEntity.ok().eTag(EtagCodec.format(version)).body(body);
    }

    @Override
    public ResponseEntity<PermissionCollection> replaceConnectionPermission(
            String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String connectionIdValue,
            String membershipIdValue, ReplacePermissionRequest body, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        MembershipId membershipId = toMembershipId(membershipIdValue);
        connections.get(actor, connectionId);

        requireEligibleGrantee(actor, membershipId);
        long expectedVersion = parseIfMatch(ifMatch);
        Set<com.knowimran.rivoquery.identity.GrantablePermission> requested =
                ConnectionPermissionDtoMapper.toDomain(body.getPermissions());

        String fingerprint = RequestFingerprint.of(connectionIdValue, membershipIdValue, ifMatch, body);
        var reservation = idempotency.reserve(
                actor.workspaceId(), actor.actorId(), idempotencyKey, "replaceConnectionPermission", fingerprint);
        if (reservation.isPresent()) {
            return currentCollectionResponse(actor.workspaceId(), connectionId);
        }

        try {
            var grant = new ConnectionPermissionGrant(
                    actor.workspaceId(), connectionId, membershipId, requested, expectedVersion + 1);
            if (!permissions.replace(grant, expectedVersion)) {
                throw new PermissionCollectionVersionConflictException();
            }
        } catch (RuntimeException noEffectFailure) {
            // permissions.replace is @Transactional over a single database
            // transaction: any exception here means that transaction rolled
            // back in full, so this is always safe to roll the reservation
            // back too (unlike the multi-step, cross-system lifecycle saga
            // ConnectionController guards, there is no "real effect may
            // already have occurred" outcome possible from this call).
            idempotency.rollback(actor.workspaceId(), actor.actorId(), idempotencyKey);
            throw noEffectFailure;
        }

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey, Optional.empty());
        return currentCollectionResponse(actor.workspaceId(), connectionId);
    }

    @Override
    public ResponseEntity<PermissionCollection> revokeConnectionPermission(
            String xRivoQueryWorkspaceId, String idempotencyKey, String ifMatch, String connectionIdValue,
            String membershipIdValue, String xRequestId) {
        ActorContext actor = actorResolver.resolve(request());
        actor.requireAdministrator();
        ConnectionId connectionId = toConnectionId(connectionIdValue);
        MembershipId membershipId = toMembershipId(membershipIdValue);
        connections.get(actor, connectionId);

        long expectedVersion = parseIfMatch(ifMatch);

        String fingerprint = RequestFingerprint.of(connectionIdValue, membershipIdValue, ifMatch);
        var reservation = idempotency.reserve(
                actor.workspaceId(), actor.actorId(), idempotencyKey, "revokeConnectionPermission", fingerprint);
        if (reservation.isPresent()) {
            return currentCollectionResponse(actor.workspaceId(), connectionId);
        }

        try {
            if (!permissions.revoke(actor.workspaceId(), connectionId, membershipId, expectedVersion)) {
                throw new PermissionCollectionVersionConflictException();
            }
        } catch (RuntimeException noEffectFailure) {
            // See the identical comment in replaceConnectionPermission:
            // permissions.revoke is @Transactional over a single database
            // transaction, so any exception here is always a full rollback.
            idempotency.rollback(actor.workspaceId(), actor.actorId(), idempotencyKey);
            throw noEffectFailure;
        }

        idempotency.complete(actor.workspaceId(), actor.actorId(), idempotencyKey, Optional.empty());
        return currentCollectionResponse(actor.workspaceId(), connectionId);
    }

    private ResponseEntity<PermissionCollection> currentCollectionResponse(
            com.knowimran.rivoquery.identity.WorkspaceId workspaceId, ConnectionId connectionId) {
        long version = permissions.currentCollectionVersion(workspaceId, connectionId);
        List<ConnectionPermissionGrant> page =
                permissions.findPage(workspaceId, connectionId, Optional.empty(), DEFAULT_PAGE_SIZE);
        PermissionCollection body = ConnectionPermissionDtoMapper.toCollection(version, page);
        if (page.size() == DEFAULT_PAGE_SIZE) {
            body.setNextCursor(page.get(page.size() - 1).membershipId().value());
        }
        return ResponseEntity.ok().eTag(EtagCodec.format(version)).body(body);
    }

    private void requireEligibleGrantee(ActorContext actor, MembershipId membershipId) {
        memberships.findActiveMembership(actor.workspaceId(), membershipId)
                .filter(entry -> entry.role() == WorkspaceRole.MEMBER)
                .orElseThrow(MembershipNotEligibleException::new);
    }

    private static ConnectionId toConnectionId(String value) {
        try {
            return new ConnectionId(value);
        } catch (IllegalArgumentException malformed) {
            throw new com.knowimran.rivoquery.connection.application.lifecycle.ConnectionNotFoundException();
        }
    }

    private static MembershipId toMembershipId(String value) {
        try {
            return new MembershipId(value);
        } catch (IllegalArgumentException malformed) {
            throw new MembershipNotEligibleException();
        }
    }

    private static Optional<MembershipId> parseCursor(String cursor) {
        if (cursor == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(new MembershipId(cursor));
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("cursor is not a valid membership identifier");
        }
    }

    private static long parseIfMatch(String ifMatch) {
        try {
            return EtagCodec.parseLong(ifMatch);
        } catch (IllegalArgumentException malformed) {
            throw new ValidationFailedException("If-Match is not a well-formed version token");
        }
    }
}
