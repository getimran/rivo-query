package com.knowimran.rivoquery.platform.persistence;

import com.knowimran.rivoquery.catalog.SchemaRefreshOperation;
import com.knowimran.rivoquery.catalog.application.SchemaRefreshOperationRepository;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcSchemaRefreshOperationRepositoryTest {

    @Test
    void schemaRefreshOperationRejectsInvalidFields() {
        assertThatThrownBy(() -> new SchemaRefreshOperation(
            null,
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            "PENDING",
            null,
            null,
            null,
            -1,
            1,
            3,
            5000,
            Instant.now(),
            Instant.now()
        )).isInstanceOf(NullPointerException.class);
    }

    @Test
    void schemaRefreshOperationRejectsNegativeFencingToken() {
        assertThatThrownBy(() -> new SchemaRefreshOperation(
            "op_1",
            new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"),
            "PENDING",
            null,
            null,
            null,
            -1,
            1,
            3,
            5000,
            Instant.now(),
            Instant.now()
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructorRejectsNullJdbcTemplate() {
        assertThatThrownBy(() -> new JdbcSchemaRefreshOperationRepository(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void insertRejectsNullOperation() {
        JdbcSchemaRefreshOperationRepository repository = new JdbcSchemaRefreshOperationRepository(new JdbcTemplate(dummyDataSource()));
        assertThatThrownBy(() -> repository.insert(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void findByIdRejectsNullOperationId() {
        JdbcSchemaRefreshOperationRepository repository = new JdbcSchemaRefreshOperationRepository(new JdbcTemplate(dummyDataSource()));
        assertThatThrownBy(() -> repository.findById(null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void updateStateRejectsNullArguments() {
        JdbcSchemaRefreshOperationRepository repository = new JdbcSchemaRefreshOperationRepository(new JdbcTemplate(dummyDataSource()));
        assertThatThrownBy(() -> repository.updateState(null, "PENDING"))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> repository.updateState("op_1", null))
            .isInstanceOf(NullPointerException.class);
    }

    private static DataSource dummyDataSource() {
        return new DataSource() {
            @Override
            public java.sql.Connection getConnection() throws SQLException {
                return null;
            }

            @Override
            public java.sql.Connection getConnection(String username, String password) throws SQLException {
                return null;
            }

            @Override
            public PrintWriter getLogWriter() throws SQLException {
                return null;
            }

            @Override
            public void setLogWriter(PrintWriter out) throws SQLException {
            }

            @Override
            public void setLoginTimeout(int seconds) throws SQLException {
            }

            @Override
            public int getLoginTimeout() throws SQLException {
                return 0;
            }

            @Override
            public Logger getParentLogger() {
                return null;
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                return null;
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }
        };
    }
}
