package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.identity.ActorId;
import com.knowimran.rivoquery.identity.CurrentMembership;
import com.knowimran.rivoquery.identity.MembershipId;
import com.knowimran.rivoquery.identity.MembershipStatus;
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.WorkspaceRole;
import com.knowimran.rivoquery.identity.application.CurrentMembershipRepository;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Resolves the caller's membership for a selected workspace by joining the
 * global identity registry ({@code actors}, keyed by OIDC issuer/subject) to
 * the workspace-scoped {@code memberships} row. Every query is scoped by the
 * caller-selected {@code workspaceId} at the SQL level, and only an
 * {@link MembershipStatus#ACTIVE} row is ever returned; {@code
 * ActorContext.Authority} still keeps its own defense-in-depth status check
 * rather than trusting this adapter alone.
 */
@Component
public final class JdbcCurrentMembershipRepository implements CurrentMembershipRepository {

    private static final String FIND_QUERY = """
            SELECT m.membership_id, m.workspace_id, a.actor_id, m.role, m.status
            FROM actors a
            JOIN memberships m ON m.actor_id = a.actor_id
            WHERE a.issuer = ? AND a.subject = ? AND m.workspace_id = ? AND m.status = 'ACTIVE'
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcCurrentMembershipRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<CurrentMembership> findCurrentActiveMembership(
            String issuer, String subject, WorkspaceId workspaceId) {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(workspaceId, "workspaceId");

        List<CurrentMembership> rows = jdbcTemplate.query(
                FIND_QUERY,
                (resultSet, rowNumber) -> new CurrentMembership(
                        new ActorId(resultSet.getString("actor_id")),
                        new WorkspaceId(resultSet.getString("workspace_id")),
                        new MembershipId(resultSet.getString("membership_id")),
                        WorkspaceRole.valueOf(resultSet.getString("role")),
                        MembershipStatus.valueOf(resultSet.getString("status"))),
                issuer, subject, workspaceId.value());
        return rows.stream().findFirst();
    }
}
