package com.knowimran.rivoquery.connection.application.lifecycle;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.identity.ActorContext;
import com.knowimran.rivoquery.identity.PermissionMatrix;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Connection list/get read paths. Deliberately separate from
 * {@link ConnectionLifecycleService}: these are plain, tenant-scoped,
 * authorization-filtered reads with no audit-port write at all -
 * {@link com.knowimran.rivoquery.connection.application.ConnectionAuditAction} has no LIST/GET
 * member, matching the fact that this table records lifecycle *mutations*,
 * not every read.
 *
 * <p>An administrator sees every connection in their workspace. A member
 * sees only a connection for which they hold at least one explicit
 * {@code ConnectionPermissionGrant} with non-empty effective permissions
 * (checked per candidate row via the existing single-lookup {@link
 * ConnectionPermissionRepository#find} port; no bulk "list connections a
 * member can see" port exists, and adding one is out of this task's scope -
 * {@link #list} itself enforces the OpenAPI contract's ≤100 page size, so
 * this is a bounded number of extra lookups, not an unbounded scan).
 */
public final class ConnectionQueryService {
    /** Matches the OpenAPI contract's page-size ceiling; enforced here, not left to a future REST layer. */
    public static final int MAX_PAGE_SIZE = 100;

    private final ConnectionRepository connections;
    private final ConnectionPermissionRepository permissions;

    public ConnectionQueryService(ConnectionRepository connections, ConnectionPermissionRepository permissions) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
    }

    /**
     * Returns a connection only if it exists in the actor's workspace and
     * the actor may view it (administrator, or a member with any explicit
     * grant). Unknown, foreign-tenant, and unauthorized-member cases are all
     * indistinguishable {@link ConnectionNotFoundException}s.
     */
    public DatabaseConnection get(ActorContext actor, ConnectionId connectionId) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(connectionId, "connectionId");
        DatabaseConnection connection = connections.findByWorkspaceAndId(actor.workspaceId(), connectionId)
                .filter(candidate -> candidate.state().isPublicState())
                .orElseThrow(ConnectionNotFoundException::new);
        if (!canView(actor, connection)) {
            throw new ConnectionNotFoundException();
        }
        return connection;
    }

    /**
     * Returns up to {@code limit} connections in the actor's workspace,
     * filtered to what the actor may view, ordered ascending by connection
     * id (the same order the cursor is defined over).
     *
     * <p>Two known cursor edge cases, both accepted rather than "fixed":
     * <ul>
     *   <li>The raw page is taken first and <em>then</em> filtered for
     *       members, so a member can receive fewer than {@code limit} rows
     *       even when more viewable rows exist beyond the raw page - clients
     *       keep paging with the last returned cursor until an empty or
     *       under-full page stops them.</li>
     *   <li>When the result size is an exact multiple of {@code limit}, the
     *       next cursor returns one empty page before terminating - clients
     *       detect "empty page" (or "page shorter than limit") to stop, not
     *       a non-null cursor.</li>
     * </ul></p>
     */
    public List<DatabaseConnection> list(ActorContext actor, Optional<ConnectionId> afterConnectionId, int limit) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(afterConnectionId, "afterConnectionId");
        if (limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must not exceed " + MAX_PAGE_SIZE);
        }
        List<DatabaseConnection> page = connections.findPage(actor.workspaceId(), afterConnectionId, limit);
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return page;
        }
        return page.stream().filter(candidate -> canView(actor, candidate)).toList();
    }

    private boolean canView(ActorContext actor, DatabaseConnection connection) {
        if (actor.role() == WorkspaceRole.ADMINISTRATOR) {
            return true;
        }
        return permissions.find(actor.workspaceId(), connection.id(), actor.membershipId())
                .map(grant -> !PermissionMatrix.effectivePermissions(
                        actor, actor.workspaceId(), actor.membershipId(), grant.permissions())
                        .isEmpty())
                .orElse(false);
    }
}
