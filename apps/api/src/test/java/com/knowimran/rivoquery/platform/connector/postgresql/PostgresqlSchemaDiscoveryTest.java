package com.knowimran.rivoquery.platform.connector.postgresql;

import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresqlSchemaDiscoveryTest {

    @Test
    void reportsPostgresqlKind() {
        PostgresqlSchemaDiscovery discovery = new PostgresqlSchemaDiscovery();
        assertThat(discovery.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
    }

    @Test
    void discoverRejectsNullArguments() throws SQLException {
        PostgresqlSchemaDiscovery discovery = new PostgresqlSchemaDiscovery();
        assertThatThrownBy(() -> discovery.discover(null, new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)))
            .isInstanceOf(NullPointerException.class);
    }
}
