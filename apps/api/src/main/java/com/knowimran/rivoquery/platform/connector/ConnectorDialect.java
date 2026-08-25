package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;

/**
 * Dialect-specific configuration and trusted JDBC URL/property construction.
 *
 * <p>Implementations are the only place a JDBC URL or driver property map is assembled. They
 * accept only structured, already-validated input (host/port/database, never a raw client URL)
 * and a fixed, dialect-controlled set of options; they never accept an arbitrary driver class
 * name, socket factory, file path, or open property map from a caller.
 */
public interface ConnectorDialect {

    DatabaseKind kind();

    String driverClassName();

    /** Accurate, dialect-specific capability report for this connector. */
    ConnectorCapabilities capabilities();

    /**
     * Builds the full JDBC connection target (including database) for physical pooled use.
     *
     * @throws InvalidConnectorEndpointException if any endpoint field fails the trusted
     *     character allowlist, regardless of what upstream validation already did
     */
    JdbcConnectionTarget buildConnectionTarget(StructuredEndpoint endpoint);

    /**
     * Builds a database-less JDBC connection target used only to probe reachability/authn of the
     * server named by a validated destination (which carries no database name).
     *
     * @throws InvalidConnectorEndpointException if the host or port fails the trusted allowlist
     */
    JdbcConnectionTarget buildProbeTarget(String host, int port);
}
