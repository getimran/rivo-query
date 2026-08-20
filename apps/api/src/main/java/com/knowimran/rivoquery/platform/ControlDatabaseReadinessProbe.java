package com.knowimran.rivoquery.platform;

import com.knowimran.rivoquery.application.health.ControlDatabaseUnavailableException;
import com.knowimran.rivoquery.application.health.ReadinessCheck;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ControlDatabaseReadinessProbe implements ReadinessCheck {

    static final int QUERY_TIMEOUT_SECONDS = 2;

    private static final String HEARTBEAT_QUERY =
            "SELECT 1 FROM platform_heartbeat WHERE singleton_id = 1 LIMIT 1";

    private final JdbcTemplate jdbcTemplate;

    public ControlDatabaseReadinessProbe(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void verifyReady() {
        try {
            Boolean heartbeatPresent = jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
                try (PreparedStatement statement = connection.prepareStatement(HEARTBEAT_QUERY)) {
                    statement.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                    statement.setMaxRows(1);
                    try (ResultSet resultSet = statement.executeQuery()) {
                        return resultSet.next();
                    }
                }
            });

            if (!Boolean.TRUE.equals(heartbeatPresent)) {
                throw new ControlDatabaseUnavailableException();
            }
        } catch (DataAccessException exception) {
            throw new ControlDatabaseUnavailableException(exception);
        }
    }
}
