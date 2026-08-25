package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;
import com.knowimran.rivoquery.connection.connector.ConnectorOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Trusted JDBC URL/property builder and capability report for PostgreSQL (pgjdbc).
 *
 * <p>Read-only access is enforced unconditionally via the pgjdbc {@code readOnly}/
 * {@code readOnlyMode=always} properties (session-level, applied immediately on connect, not
 * only per-transaction); TLS is required with full chain and hostname verification
 * ({@code sslmode=verify-full}). None of these are client-selectable.
 */
public final class PostgresqlConnectorDialect implements ConnectorDialect {

    public static final String DRIVER_CLASS_NAME = "org.postgresql.Driver";

    private static final String APPLICATION_NAME = "rivoquery";
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int SOCKET_TIMEOUT_SECONDS = 30;

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.POSTGRESQL;
    }

    @Override
    public String driverClassName() {
        return DRIVER_CLASS_NAME;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(DatabaseKind.POSTGRESQL, Set.of(ConnectorOption.TCP_KEEP_ALIVE));
    }

    @Override
    public JdbcConnectionTarget buildConnectionTarget(StructuredEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String host = TrustedEndpointValidation.requireTrustedHost(endpoint.host());
        int port = TrustedEndpointValidation.requireTrustedPort(endpoint.port());
        String database = TrustedEndpointValidation.requireTrustedDatabaseName(endpoint.databaseName());
        String url = "jdbc:postgresql://" + TrustedEndpointValidation.toUrlHostComponent(host) + ":" + port
                + "/" + database;
        return new JdbcConnectionTarget(DatabaseKind.POSTGRESQL, DRIVER_CLASS_NAME, url, baseProperties(), true);
    }

    @Override
    public JdbcConnectionTarget buildProbeTarget(String host, int port) {
        String trustedHost = TrustedEndpointValidation.requireTrustedHost(host);
        int trustedPort = TrustedEndpointValidation.requireTrustedPort(port);
        String url = "jdbc:postgresql://" + TrustedEndpointValidation.toUrlHostComponent(trustedHost) + ":"
                + trustedPort + "/";
        return new JdbcConnectionTarget(DatabaseKind.POSTGRESQL, DRIVER_CLASS_NAME, url, baseProperties(), true);
    }

    private static Map<String, String> baseProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(PostgresqlConnectorProperty.SSL_MODE.key(), "verify-full");
        properties.put(PostgresqlConnectorProperty.READ_ONLY.key(), "true");
        properties.put(PostgresqlConnectorProperty.READ_ONLY_MODE.key(), "always");
        properties.put(PostgresqlConnectorProperty.TCP_KEEP_ALIVE.key(), "true");
        properties.put(PostgresqlConnectorProperty.CONNECT_TIMEOUT.key(), String.valueOf(CONNECT_TIMEOUT_SECONDS));
        properties.put(PostgresqlConnectorProperty.SOCKET_TIMEOUT.key(), String.valueOf(SOCKET_TIMEOUT_SECONDS));
        properties.put(PostgresqlConnectorProperty.APPLICATION_NAME.key(), APPLICATION_NAME);
        return properties;
    }
}
