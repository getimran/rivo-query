package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionPermissionGrant;
import com.knowimran.rivoquery.connection.application.ConnectionPermissionRepository;
import com.knowimran.rivoquery.identity.GrantablePermission;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.Array;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists non-{@code MANAGE} connection permission grants behind a single
 * shared optimistic "collection version" per (workspace, connection), as
 * required by the contract that the permission collection's strong
 * {@code ETag} advances independently of any individual grant. Every query
 * is scoped by {@code workspace_id} at the SQL level.
 *
 * <p>Contract inferred from {@link ConnectionPermissionRepository}: the
 * caller supplies the grant's intended new version as
 * {@link ConnectionPermissionGrant#version()} and the collection version it
 * expects to still be current as {@code expectedCollectionVersion}. A
 * collection with no grants yet has an implicit version of {@code 0}; the
 * first grant for a connection must therefore be written with
 * {@code expectedCollectionVersion == 0} and {@code grant.version() == 1}.
 */
// Not final: Spring's default proxy-target-class=true AOP configuration
// requires a CGLIB subclass to support @Transactional on this bean.
@Repository
public class JdbcConnectionPermissionRepository implements ConnectionPermissionRepository {

    private static final String FIND_QUERY = """
            SELECT g.permissions, coll.collection_version
            FROM connection_permission_grants g
            JOIN connection_permission_collections coll
                 ON coll.workspace_id = g.workspace_id AND coll.connection_id = g.connection_id
            WHERE g.workspace_id = ? AND g.connection_id = ? AND g.membership_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcConnectionPermissionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<ConnectionPermissionGrant> find(
            WorkspaceId workspaceId, ConnectionId connectionId, MembershipId membershipId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(membershipId, "membershipId");

        List<ConnectionPermissionGrant> rows = jdbcTemplate.query(
                FIND_QUERY,
                (resultSet, rowNumber) -> new ConnectionPermissionGrant(
                        workspaceId,
                        connectionId,
                        membershipId,
                        toPermissionSet(resultSet.getArray("permissions")),
                        resultSet.getLong("collection_version")),
                workspaceId.value(), connectionId.value(), membershipId.value());
        return rows.stream().findFirst();
    }

    @Override
    @Transactional
    public boolean replace(ConnectionPermissionGrant grant, long expectedCollectionVersion) {
        Objects.requireNonNull(grant, "grant");
        if (grant.version() != expectedCollectionVersion + 1) {
            throw new IllegalArgumentException(
                    "grant version must be exactly expectedCollectionVersion + 1");
        }
        if (!casCollectionVersion(
                grant.workspaceId(), grant.connectionId(), expectedCollectionVersion, grant.version())) {
            return false;
        }
        jdbcTemplate.update(
                connection -> {
                    var statement = connection.prepareStatement(
                            """
                            INSERT INTO connection_permission_grants
                                (workspace_id, connection_id, membership_id, permissions)
                            VALUES (?, ?, ?, ?)
                            ON CONFLICT (workspace_id, connection_id, membership_id)
                            DO UPDATE SET permissions = EXCLUDED.permissions, updated_at = now()
                            """);
                    statement.setString(1, grant.workspaceId().value());
                    statement.setString(2, grant.connectionId().value());
                    statement.setString(3, grant.membershipId().value());
                    statement.setArray(4, connection.createArrayOf("text", toPermissionNames(grant.permissions())));
                    return statement;
                });
        return true;
    }

    /**
     * Fixed post-review (S2): the original implementation had two defects.
     * (1) It had no {@code expectedCollectionVersion == 0} branch (unlike
     * {@link #casCollectionVersion}'s replace path): a connection with no
     * permission-collection row yet has an implicit version of {@code 0}
     * (see this class's own Javadoc), so the documented flow (GET returns
     * ETag {@code "0"} for an empty collection -> DELETE with {@code
     * If-Match: "0"}) always 412'd against the server's own just-issued
     * ETag, because the bare {@code UPDATE} below matches zero rows when the
     * collection doesn't exist. (2) It bumped the collection version (and
     * therefore invalidated every other client's ETag) even when the target
     * membership had no grant to revoke, with no existence check.
     *
     * <p>Fixed by treating "does the current collection version match, and
     * does {@code membershipId} actually have a grant" as two independent
     * questions: a version mismatch is always a precondition failure
     * (whether or not a collection row physically exists yet); a version
     * match with no existing grant is a no-op success (matches, returns
     * {@code true}, but never bumps the version or deletes anything - a
     * revoke of something already absent should not invalidate other
     * clients' ETags); only a version match *and* an existing grant performs
     * the real CAS bump + delete.
     */
    @Override
    @Transactional
    public boolean revoke(
            WorkspaceId workspaceId,
            ConnectionId connectionId,
            MembershipId membershipId,
            long expectedCollectionVersion) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(membershipId, "membershipId");

        if (currentCollectionVersion(workspaceId, connectionId) != expectedCollectionVersion) {
            return false;
        }
        if (find(workspaceId, connectionId, membershipId).isEmpty()) {
            // Nothing to revoke: a no-op success that leaves the collection
            // version (and every other client's ETag) untouched.
            return true;
        }

        int updated = jdbcTemplate.update(
                """
                UPDATE connection_permission_collections
                   SET collection_version = collection_version + 1, updated_at = now()
                 WHERE workspace_id = ? AND connection_id = ? AND collection_version = ?
                """,
                workspaceId.value(), connectionId.value(), expectedCollectionVersion);
        if (updated != 1) {
            // Lost a race against a concurrent writer between the checks
            // above and this CAS update.
            return false;
        }
        jdbcTemplate.update(
                "DELETE FROM connection_permission_grants "
                        + "WHERE workspace_id = ? AND connection_id = ? AND membership_id = ?",
                workspaceId.value(), connectionId.value(), membershipId.value());
        return true;
    }

    private boolean casCollectionVersion(
            WorkspaceId workspaceId, ConnectionId connectionId, long expected, long next) {
        if (expected == 0) {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO connection_permission_collections (workspace_id, connection_id, collection_version)
                    VALUES (?, ?, ?)
                    ON CONFLICT (workspace_id, connection_id) DO NOTHING
                    """,
                    workspaceId.value(), connectionId.value(), next);
            return inserted == 1;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE connection_permission_collections
                   SET collection_version = ?, updated_at = now()
                 WHERE workspace_id = ? AND connection_id = ? AND collection_version = ?
                """,
                next, workspaceId.value(), connectionId.value(), expected);
        return updated == 1;
    }

    private static final String FIND_PAGE_QUERY = """
            SELECT g.membership_id, g.permissions, coll.collection_version
            FROM connection_permission_grants g
            JOIN connection_permission_collections coll
                 ON coll.workspace_id = g.workspace_id AND coll.connection_id = g.connection_id
            WHERE g.workspace_id = ? AND g.connection_id = ? AND g.membership_id > ?
            ORDER BY g.membership_id
            LIMIT ?
            """;

    @Override
    public List<ConnectionPermissionGrant> findPage(
            WorkspaceId workspaceId, ConnectionId connectionId,
            Optional<MembershipId> afterMembershipId, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(afterMembershipId, "afterMembershipId");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        String after = afterMembershipId.map(MembershipId::value).orElse("");
        return jdbcTemplate.query(
                FIND_PAGE_QUERY,
                (resultSet, rowNumber) -> new ConnectionPermissionGrant(
                        workspaceId,
                        connectionId,
                        new MembershipId(resultSet.getString("membership_id")),
                        toPermissionSet(resultSet.getArray("permissions")),
                        resultSet.getLong("collection_version")),
                workspaceId.value(), connectionId.value(), after, limit);
    }

    @Override
    public long currentCollectionVersion(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        List<Long> rows = jdbcTemplate.query(
                "SELECT collection_version FROM connection_permission_collections "
                        + "WHERE workspace_id = ? AND connection_id = ?",
                (resultSet, rowNumber) -> resultSet.getLong("collection_version"),
                workspaceId.value(), connectionId.value());
        return rows.stream().findFirst().orElse(0L);
    }

    private static String[] toPermissionNames(Set<GrantablePermission> permissions) {
        return permissions.stream().map(Enum::name).toArray(String[]::new);
    }

    private static Set<GrantablePermission> toPermissionSet(Array sqlArray) throws java.sql.SQLException {
        String[] values = (String[]) sqlArray.getArray();
        Set<GrantablePermission> permissions = EnumSet.noneOf(GrantablePermission.class);
        Arrays.stream(values).map(GrantablePermission::valueOf).forEach(permissions::add);
        return Set.copyOf(permissions);
    }
}
