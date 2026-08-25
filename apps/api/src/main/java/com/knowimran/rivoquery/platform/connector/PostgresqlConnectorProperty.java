package com.knowimran.rivoquery.platform.connector;

/**
 * Closed allowlist of pgjdbc connection-property keys that {@link PostgresqlConnectorDialect} is
 * permitted to emit. This is the entire set; there is no open/arbitrary property path.
 */
public enum PostgresqlConnectorProperty implements ConnectorProperty {
    SSL_MODE("sslmode"),
    READ_ONLY("readOnly"),
    READ_ONLY_MODE("readOnlyMode"),
    TCP_KEEP_ALIVE("tcpKeepAlive"),
    CONNECT_TIMEOUT("connectTimeout"),
    SOCKET_TIMEOUT("socketTimeout"),
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
