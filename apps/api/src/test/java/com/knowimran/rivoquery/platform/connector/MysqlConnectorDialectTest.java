package com.knowimran.rivoquery.platform.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.connector.ConnectorOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MysqlConnectorDialectTest extends ConnectorDialectContractTest {

    private final MysqlConnectorDialect dialect = new MysqlConnectorDialect();

    @Override
    ConnectorDialect dialect() {
        return dialect;
    }

    @Override
    Set<String> allowedPropertyKeys() {
        return Arrays.stream(MysqlConnectorProperty.values())
                .map(MysqlConnectorProperty::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    Set<ConnectorOption> expectedSafeOptions() {
        return Set.of(ConnectorOption.TCP_KEEP_ALIVE);
    }

    @Override
    Map<String, String> expectedReadOnlyProperties() {
        return Map.of("readOnlyPropagatesToServer", "true");
    }

    @Override
    Map<String, String> expectedTlsVerificationProperty() {
        return Map.of("sslMode", "VERIFY_IDENTITY");
    }

    @Test
    void kindAndDriverClassNameAreExact() {
        assertThat(dialect.kind()).isEqualTo(DatabaseKind.MYSQL);
        assertThat(dialect.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void connectionTargetHasTheExactConnectorJUrlAndPropertyShape() {
        var target = dialect.buildConnectionTarget(new StructuredEndpoint("db.example.com", 3306, "analytics"));

        assertThat(target.kind()).isEqualTo(DatabaseKind.MYSQL);
        assertThat(target.jdbcUrl()).isEqualTo("jdbc:mysql://db.example.com:3306/analytics");
        assertThat(target.properties()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "sslMode", "VERIFY_IDENTITY",
                "tcpKeepAlive", "true",
                "connectTimeout", "10000",
                "socketTimeout", "30000",
                "readOnlyPropagatesToServer", "true",
                "allowLoadLocalInfile", "false",
                "allowUrlInLocalInfile", "false",
                "autoDeserialize", "false",
                "allowMultiQueries", "false"));
    }

    @Test
    void connectionTargetPinsServerInitiatedRiskPropertiesToSafeValuesForAnUntrustedServer() {
        var target = dialect.buildConnectionTarget(new StructuredEndpoint("db.example.com", 3306, "analytics"));

        assertThat(target.properties()).containsAllEntriesOf(java.util.Map.of(
                "allowLoadLocalInfile", "false",
                "allowUrlInLocalInfile", "false",
                "autoDeserialize", "false",
                "allowMultiQueries", "false"));
    }

    @Test
    void probeTargetOmitsDatabaseSegmentButKeepsTheSameProperties() {
        var connectionTarget =
                dialect.buildConnectionTarget(new StructuredEndpoint("db.example.com", 3306, "analytics"));
        var probeTarget = dialect.buildProbeTarget("db.example.com", 3306);

        assertThat(probeTarget.jdbcUrl()).isEqualTo("jdbc:mysql://db.example.com:3306/");
        assertThat(probeTarget.properties()).isEqualTo(connectionTarget.properties());
    }
}
