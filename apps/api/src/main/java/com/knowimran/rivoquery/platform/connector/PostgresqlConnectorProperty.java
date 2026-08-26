package com.knowimran.rivoquery.platform.connector;

/**
 * Closed allowlist of pgjdbc connection-property keys that {@link PostgresqlConnectorDialect} and
 * {@link PostgresqlPhysicalJdbcConnector} are permitted to emit. This is the entire set; there is
 * no open/arbitrary property path. {@code SSL_RESPONSE_TIMEOUT} and {@code LOGIN_TIMEOUT} are
 * never set by the dialect's base properties - only {@link PostgresqlPhysicalJdbcConnector} sets
 * them, derived from the caller's remaining {@code Deadline} at connect time (never from client
 * input).
 */
public enum PostgresqlConnectorProperty implements ConnectorProperty {
    SSL_MODE("sslmode"),
    READ_ONLY("readOnly"),
    READ_ONLY_MODE("readOnlyMode"),
    TCP_KEEP_ALIVE("tcpKeepAlive"),
    CONNECT_TIMEOUT("connectTimeout"),
    SOCKET_TIMEOUT("socketTimeout"),
    SSL_RESPONSE_TIMEOUT("sslResponseTimeout"),
    LOGIN_TIMEOUT("loginTimeout"),
    APPLICATION_NAME("ApplicationName");

    private final String key;

    PostgresqlConnectorProperty(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
