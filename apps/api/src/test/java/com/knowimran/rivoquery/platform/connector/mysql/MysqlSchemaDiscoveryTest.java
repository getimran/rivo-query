package com.knowimran.rivoquery.platform.connector.mysql;

import com.knowimran.rivoquery.catalog.application.SchemaDiscoveryPort;
import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.identity.WorkspaceId;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MysqlSchemaDiscoveryTest {

    @Test
    void reportsMysqlKind() {
        MysqlSchemaDiscovery discovery = new MysqlSchemaDiscovery();
        assertThat(discovery.kind()).isEqualTo(DatabaseKind.MYSQL);
    }

    @Test
    void discoverRejectsNullArguments() throws SQLException {
        MysqlSchemaDiscovery discovery = new MysqlSchemaDiscovery();
        assertThatThrownBy(() -> discovery.discover(null, new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX"), new SchemaDiscoveryPort.DiscoveryPolicy(List.of("public"), List.of(), false, true, 100)))
            .isInstanceOf(NullPointerException.class);
    }
}
