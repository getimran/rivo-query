package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.connection.ConflictingSecretReferenceException;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseConnection;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.ConnectionState;
import com.knowimran.rivoquery.connection.StaleConnectionVersionException;
import com.knowimran.rivoquery.connection.application.ConnectionRepository;
import com.knowimran.rivoquery.connection.application.secret.BoundSecretReference;
import com.knowimran.rivoquery.connection.application.secret.SecretBinding;
import com.knowimran.rivoquery.connection.application.secret.SecretPurpose;
import com.knowimran.rivoquery.connection.application.secret.SecretReference;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tenant-scoped, optimistic-concurrency-checked persistence for
 * {@link DatabaseConnection}. Every query is scoped by {@code workspace_id}
 * at the SQL level (never trusting only an in-memory filter), and every
 * write path that changes {@code current_secret_version} first durably
 * records the ({@code workspace_id}, {@code connection_id}, {@code version})
 * secret reference row so the composite foreign key from {@code connections}
 * to {@code connection_secret_references} is always satisfied.
 *
 * <p>{@link #compareAndSet} returns {@code false} only when the connection
 * row does not exist for the given tenant/id, regardless of whether the
 * written connection carries a secret; if the row exists but its stored
 * version does not match {@code expectedVersion}, it throws the existing
 * {@link StaleConnectionVersionException} rather than returning
 * {@code false}, matching the fail-closed optimistic-concurrency behavior
 * the domain aggregate itself enforces on every transition. A write that
 * tries to record a secret reference at a version that already durably
 * holds a *different* reference throws {@link ConflictingSecretReferenceException}
 * rather than silently discarding the new reference while the caller's
 * in-memory aggregate believes it was recorded.
 */
// Not final: Spring's default proxy-target-class=true AOP configuration
// requires a CGLIB subclass to support @Transactional on this bean.
@Repository
public class JdbcConnectionRepository implements ConnectionRepository {

    private static final String FIND_QUERY = """
            SELECT c.workspace_id, c.connection_id, c.kind, c.state, c.version,
                   c.latest_secret_version, c.secret_retire_at,
                   r.version AS secret_version, r.purpose, r.provider_namespace,
                   r.opaque_id, r.binding_fingerprint
            FROM connections c
            LEFT JOIN connection_secret_references r
                   ON r.workspace_id = c.workspace_id
                  AND r.connection_id = c.connection_id
                  AND r.version = c.current_secret_version
            WHERE c.workspace_id = ? AND c.connection_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<DatabaseConnection> findByWorkspaceAndId(WorkspaceId workspaceId, ConnectionId connectionId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(connectionId, "connectionId");
        List<DatabaseConnection> rows = jdbcTemplate.query(
                FIND_QUERY, this::mapRow, workspaceId.value(), connectionId.value());
        return rows.stream().findFirst();
    }

    @Override
    public void insert(DatabaseConnection connection) {
        Objects.requireNonNull(connection, "connection");
        // insert() is for brand-new connections only. DatabaseConnection.reserve()
        // is the only factory that yields this exact combination (PENDING_SECRET,
        // no current secret), which is also the only combination the connections
        // table's own CHECK constraints allow to be written in a single INSERT:
        // every other state either requires a current_secret_version that must
        // already exist as a row in connection_secret_references (impossible
        // before this connection row itself exists, since that table's FK points
        // back here) or requires secret_retire_at. Every later transition -
        // including attaching the first secret - goes through compareAndSet.
        if (connection.state() != ConnectionState.PENDING_SECRET || connection.currentSecret().isPresent()) {
            throw new IllegalArgumentException(
                    "insert() only accepts a freshly reserved connection; use compareAndSet for later transitions");
        }
        jdbcTemplate.update(
                """
                INSERT INTO connections
                    (workspace_id, connection_id, kind, state, version,
                     latest_secret_version, current_secret_version, secret_retire_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL)
                """,
                connection.workspaceId().value(),
                connection.id().value(),
                connection.kind().name(),
                connection.state().name(),
                connection.version().value(),
                connection.latestSecretVersion());
    }

    @Override
    @Transactional
    public boolean compareAndSet(DatabaseConnection connection, ConnectionVersion expectedVersion) {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        WorkspaceId workspaceId = connection.workspaceId();
        ConnectionId connectionId = connection.id();

        // A secret reference insert has its own FK into connections, so if
        // the row has already been deleted it would otherwise fail with a
        // raw FK-violation DataAccessException before we ever reach the
        // version check below. Checking existence first keeps the "missing
        // row returns false" contract true regardless of whether this write
        // carries a secret.
        if (!connectionExists(workspaceId, connectionId)) {
            return false;
        }

        // Record the secret reference before the connection row can point to
        // it, satisfying the composite FK.
        connection.currentSecret()
                .ifPresent(secret -> insertOrVerifySecretReference(workspaceId, connectionId, secret));

        int updated = jdbcTemplate.update(
                """
                UPDATE connections
                   SET kind = ?, state = ?, version = ?, latest_secret_version = ?,
                       current_secret_version = ?, secret_retire_at = ?, updated_at = now()
                 WHERE workspace_id = ? AND connection_id = ? AND version = ?
                """,
                connection.kind().name(),
                connection.state().name(),
                connection.version().value(),
                connection.latestSecretVersion(),
                connection.currentSecret().map(secret -> secret.binding().version()).orElse(null),
                toTimestamp(connection.secretRetireAt()),
                workspaceId.value(),
                connectionId.value(),
                expectedVersion.value());

        if (updated == 1) {
            return true;
        }

        if (!connectionExists(workspaceId, connectionId)) {
            return false;
        }
        throw new StaleConnectionVersionException();
    }

    // Added for P2-100's list use case. PENDING_SECRET/DELETED are excluded
    // in SQL (not just filtered afterward) so a page never leaks an
    // internal-only connection, and the LIMIT is applied server-side so a
    // caller-supplied limit can never force an unbounded scan.
    //
    // This literal `state NOT IN (...)` list must stay exactly the
    // complement of ConnectionState.isPublicState() - SQL cannot reference
    // that Java method directly, so ConnectionAggregateTest
    // .publicStateExclusionListMatchesTheSqlLevelExclusionInFindPage is the
    // regression guard that keeps the two in sync if a future state is
    // added.
    //
    // The keyset-pagination cursor (`c.connection_id > ?`) and `ORDER BY
    // c.connection_id ASC` must sort using the same collation/comparison
    // rule, or a row could be skipped or repeated across pages. connection_id
    // is a fixed-format, fixed-length canonical ULID-suffixed opaque id
    // (see ConnectionId), so plain byte/codepoint ordering is stable and
    // collation-independent here - this would need revisiting if the id
    // format ever became variable-length or case-insensitive.
    private static final String FIND_PAGE_QUERY = """
            SELECT c.workspace_id, c.connection_id, c.kind, c.state, c.version,
                   c.latest_secret_version, c.secret_retire_at,
                   r.version AS secret_version, r.purpose, r.provider_namespace,
                   r.opaque_id, r.binding_fingerprint
            FROM connections c
            LEFT JOIN connection_secret_references r
                   ON r.workspace_id = c.workspace_id
                  AND r.connection_id = c.connection_id
                  AND r.version = c.current_secret_version
            WHERE c.workspace_id = ?
              AND c.state NOT IN ('PENDING_SECRET', 'DELETED')
              AND c.connection_id > ?
            ORDER BY c.connection_id ASC
            LIMIT ?
            """;

    @Override
    public List<DatabaseConnection> findPage(
            WorkspaceId workspaceId, Optional<ConnectionId> afterConnectionId, int limit) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(afterConnectionId, "afterConnectionId");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        // connection_id is a fixed-format canonical opaque id (see
        // ConnectionId), so an empty-string lower bound is always strictly
        // less than every real id and never matches a real row itself.
        String after = afterConnectionId.map(ConnectionId::value).orElse("");
        return jdbcTemplate.query(FIND_PAGE_QUERY, this::mapRow, workspaceId.value(), after, limit);
    }

    private boolean connectionExists(WorkspaceId workspaceId, ConnectionId connectionId) {
        List<Long> existing = jdbcTemplate.query(
                "SELECT version FROM connections WHERE workspace_id = ? AND connection_id = ?",
                (resultSet, rowNumber) -> resultSet.getLong("version"),
                workspaceId.value(), connectionId.value());
        return !existing.isEmpty();
    }

    /**
     * Inserts the secret reference row for this (workspace, connection,
     * version) unless one already exists. A pre-existing row is only a
     * legitimate idempotent retry (e.g. a saga re-staging the same secret
     * object after a partial failure) if its content is identical to
     * {@code secret}; a pre-existing row with *different* content means a
     * second, different secret object is trying to reuse an already-used
     * version number, which would otherwise be silently discarded by
     * {@code ON CONFLICT ... DO NOTHING} while the caller's in-memory
     * aggregate goes on believing it holds the new (discarded) reference.
     */
    private void insertOrVerifySecretReference(
            WorkspaceId workspaceId, ConnectionId connectionId, BoundSecretReference secret) {
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO connection_secret_references
                    (workspace_id, connection_id, version, purpose, provider_namespace,
                     opaque_id, binding_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (workspace_id, connection_id, version) DO NOTHING
                """,
                workspaceId.value(),
                connectionId.value(),
                secret.binding().version(),
                secret.binding().purpose().name(),
                secret.reference().providerNamespace(),
                secret.reference().opaqueId(),
                secret.reference().bindingFingerprint());
        if (inserted == 1) {
            return;
        }

        List<BoundSecretReference> stored = jdbcTemplate.query(
                """
                SELECT purpose, provider_namespace, opaque_id, binding_fingerprint
                  FROM connection_secret_references
                 WHERE workspace_id = ? AND connection_id = ? AND version = ?
                """,
                (resultSet, rowNumber) -> new BoundSecretReference(
                        new SecretBinding(
                                workspaceId,
                                connectionId,
                                SecretPurpose.valueOf(resultSet.getString("purpose")),
                                secret.binding().version()),
                        new SecretReference(
                                resultSet.getString("provider_namespace"),
                                resultSet.getString("opaque_id"),
                                secret.binding().version(),
                                resultSet.getString("binding_fingerprint"))),
                workspaceId.value(), connectionId.value(), secret.binding().version());
        BoundSecretReference existingReference = stored.stream().findFirst().orElseThrow(
                () -> new IllegalStateException(
                        "secret reference insert reported a conflict but no row was found"));
        if (!existingReference.equals(secret)) {
            throw new ConflictingSecretReferenceException();
        }
    }

    private DatabaseConnection mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        WorkspaceId workspaceId = new WorkspaceId(resultSet.getString("workspace_id"));
        ConnectionId connectionId = new ConnectionId(resultSet.getString("connection_id"));
        DatabaseKind kind = DatabaseKind.valueOf(resultSet.getString("kind"));
        ConnectionState state = ConnectionState.valueOf(resultSet.getString("state"));
        ConnectionVersion version = new ConnectionVersion(resultSet.getLong("version"));
        long latestSecretVersion = resultSet.getLong("latest_secret_version");
        Timestamp retireAtTimestamp = resultSet.getTimestamp("secret_retire_at");
        Instant retireAt = retireAtTimestamp == null ? null : retireAtTimestamp.toInstant();

        BoundSecretReference currentSecret = null;
        long secretVersion = resultSet.getLong("secret_version");
        if (!resultSet.wasNull()) {
            SecretBinding binding = new SecretBinding(
                    workspaceId, connectionId, SecretPurpose.valueOf(resultSet.getString("purpose")), secretVersion);
            SecretReference reference = new SecretReference(
                    resultSet.getString("provider_namespace"),
                    resultSet.getString("opaque_id"),
                    secretVersion,
                    resultSet.getString("binding_fingerprint"));
            currentSecret = new BoundSecretReference(binding, reference);
        }

        return DatabaseConnection.rehydrate(
                workspaceId, connectionId, kind, state, version, latestSecretVersion, currentSecret, retireAt);
    }

    private static Timestamp toTimestamp(Optional<Instant> instant) {
        return instant.map(Timestamp::from).orElse(null);
    }
}
