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
 * Trusted JDBC URL/property builder and capability report for MySQL (Connector/J 8.x).
 *
 * <p>TLS is required with full chain and hostname verification ({@code sslMode=VERIFY_IDENTITY}).
 * Connector/J has no connect-time URL property that forces a session read-only; instead
 * {@code readOnlyPropagatesToServer=true} is set unconditionally so that once the physical
 * connector (outside this task's scope) calls {@code Connection.setReadOnly(true)} immediately
 * after opening — which {@link JdbcConnectionTarget#enforceReadOnlyOnConnect()} always requires
 * — the server session itself is also placed into {@code SET SESSION TRANSACTION READ ONLY}.
 * None of this is client-selectable.
 *
 * <p>The target MySQL server is tenant-supplied and untrusted per the threat model. Several
 * server-initiated-risk Connector/J properties are explicitly pinned to safe values rather than
 * relying on driver defaults: {@code allowLoadLocalInfile} and {@code allowUrlInLocalInfile} are
 * disabled because {@code LOCAL INFILE} is a server-initiated request — a malicious server can
 * ask the client to read an arbitrary local file even without the client ever requesting it;
 * {@code autoDeserialize} is disabled to avoid deserializing server-controlled bytes; {@code
 * allowMultiQueries} is disabled to keep the "exactly one statement per connection" invariant
 * enforced at the transport layer, not just by query-construction discipline elsewhere.
 */
public final class MysqlConnectorDialect implements ConnectorDialect {

    public static final String DRIVER_CLASS_NAME = "com.mysql.cj.jdbc.Driver";

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;

    @Override
    public DatabaseKind kind() {
        return DatabaseKind.MYSQL;
    }

    @Override
    public String driverClassName() {
        return DRIVER_CLASS_NAME;
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return new ConnectorCapabilities(DatabaseKind.MYSQL, Set.of(ConnectorOption.TCP_KEEP_ALIVE));
    }

    @Override
    public JdbcConnectionTarget buildConnectionTarget(StructuredEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        String host = TrustedEndpointValidation.requireTrustedHost(endpoint.host());
        int port = TrustedEndpointValidation.requireTrustedPort(endpoint.port());
        String database = TrustedEndpointValidation.requireTrustedDatabaseName(endpoint.databaseName());
        String url = "jdbc:mysql://" + TrustedEndpointValidation.toUrlHostComponent(host) + ":" + port
                + "/" + database;
        return new JdbcConnectionTarget(DatabaseKind.MYSQL, DRIVER_CLASS_NAME, url, baseProperties(), true);
    }

    @Override
    public JdbcConnectionTarget buildProbeTarget(String host, int port) {
        String trustedHost = TrustedEndpointValidation.requireTrustedHost(host);
        int trustedPort = TrustedEndpointValidation.requireTrustedPort(port);
        String url = "jdbc:mysql://" + TrustedEndpointValidation.toUrlHostComponent(trustedHost) + ":"
                + trustedPort + "/";
        return new JdbcConnectionTarget(DatabaseKind.MYSQL, DRIVER_CLASS_NAME, url, baseProperties(), true);
    }

    private static Map<String, String> baseProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put(MysqlConnectorProperty.SSL_MODE.key(), "VERIFY_IDENTITY");
        properties.put(MysqlConnectorProperty.TCP_KEEP_ALIVE.key(), "true");
        properties.put(MysqlConnectorProperty.CONNECT_TIMEOUT.key(), String.valueOf(CONNECT_TIMEOUT_MILLIS));
        properties.put(MysqlConnectorProperty.SOCKET_TIMEOUT.key(), String.valueOf(SOCKET_TIMEOUT_MILLIS));
        properties.put(MysqlConnectorProperty.READ_ONLY_PROPAGATES_TO_SERVER.key(), "true");
        properties.put(MysqlConnectorProperty.ALLOW_LOAD_LOCAL_INFILE.key(), "false");
        properties.put(MysqlConnectorProperty.ALLOW_URL_IN_LOCAL_INFILE.key(), "false");
        properties.put(MysqlConnectorProperty.AUTO_DESERIALIZE.key(), "false");
        properties.put(MysqlConnectorProperty.ALLOW_MULTI_QUERIES.key(), "false");
        return properties;
    }
}
