package com.knowimran.rivoquery.platform.connector;

/**
 * Closed allowlist of MySQL Connector/J connection-property keys that
 * {@link MysqlConnectorDialect} is permitted to emit. This is the entire set; there is no
 * open/arbitrary property path.
 */
public enum MysqlConnectorProperty implements ConnectorProperty {
    SSL_MODE("sslMode"),
    TCP_KEEP_ALIVE("tcpKeepAlive"),
    CONNECT_TIMEOUT("connectTimeout"),
    SOCKET_TIMEOUT("socketTimeout"),
    READ_ONLY_PROPAGATES_TO_SERVER("readOnlyPropagatesToServer"),
    ALLOW_LOAD_LOCAL_INFILE("allowLoadLocalInfile"),
    ALLOW_URL_IN_LOCAL_INFILE("allowUrlInLocalInfile"),
    AUTO_DESERIALIZE("autoDeserialize"),
    ALLOW_MULTI_QUERIES("allowMultiQueries");

    private final String key;

    MysqlConnectorProperty(String key) {
        this.key = key;
    }

    @Override
    public String key() {
        return key;
    }
}
