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

class PostgresqlConnectorDialectTest extends ConnectorDialectContractTest {

    private final PostgresqlConnectorDialect dialect = new PostgresqlConnectorDialect();

    @Override
    ConnectorDialect dialect() {
        return dialect;
    }

    @Override
    Set<String> allowedPropertyKeys() {
        return Arrays.stream(PostgresqlConnectorProperty.values())
                .map(PostgresqlConnectorProperty::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    Set<ConnectorOption> expectedSafeOptions() {
        return Set.of(ConnectorOption.TCP_KEEP_ALIVE);
    }

    @Override
    Map<String, String> expectedReadOnlyProperties() {
        return Map.of("readOnly", "true", "readOnlyMode", "always");
    }

    @Override
    Map<String, String> expectedTlsVerificationProperty() {
        return Map.of("sslmode", "verify-full");
    }

    @Test
    void kindAndDriverClassNameAreExact() {
        assertThat(dialect.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(dialect.driverClassName()).isEqualTo("org.postgresql.Driver");
    }

    @Test
    void connectionTargetHasTheExactPgjdbcUrlAndPropertyShape() {
        var target = dialect.buildConnectionTarget(new StructuredEndpoint("db.example.com", 5432, "analytics"));

        assertThat(target.kind()).isEqualTo(DatabaseKind.POSTGRESQL);
        assertThat(target.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/analytics");
        assertThat(target.properties()).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "sslmode", "verify-full",
                "readOnly", "true",
                "readOnlyMode", "always",
                "tcpKeepAlive", "true",
                "connectTimeout", "10",
                "socketTimeout", "30",
                "ApplicationName", "rivoquery"));
    }

    @Test
    void probeTargetOmitsDatabaseSegmentButKeepsTheSameProperties() {
        var connectionTarget =
                dialect.buildConnectionTarget(new StructuredEndpoint("db.example.com", 5432, "analytics"));
        var probeTarget = dialect.buildProbeTarget("db.example.com", 5432);

        assertThat(probeTarget.jdbcUrl()).isEqualTo("jdbc:postgresql://db.example.com:5432/");
        assertThat(probeTarget.properties()).isEqualTo(connectionTarget.properties());
    }

    @Test
    void unbracketedIpv6HostIsBracketedInTheUrl() {
        var target = dialect.buildConnectionTarget(new StructuredEndpoint("2001:db8::1", 5432, "analytics"));

        assertThat(target.jdbcUrl()).isEqualTo("jdbc:postgresql://[2001:db8::1]:5432/analytics");
    }
}
