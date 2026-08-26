package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.identity.application.WorkspaceDirectory;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcWorkspaceDirectory implements WorkspaceDirectory {

    private final JdbcTemplate jdbcTemplate;

    public JdbcWorkspaceDirectory(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    @Override
    public Optional<String> displayNameOf(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        List<String> rows = jdbcTemplate.query(
                "SELECT display_name FROM workspaces WHERE workspace_id = ?",
                (resultSet, rowNumber) -> resultSet.getString("display_name"),
                workspaceId.value());
        return rows.stream().findFirst();
    }
}
