package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import java.sql.SQLException;

/**
 * RivoQuery-controlled hook that dials the physical socket, opens the JDBC connection to one of
 * {@code destination.vettedAddresses()}, verifies TLS against {@code destination
 * .originalTlsHostname()}, and enforces {@code target.enforceReadOnlyOnConnect()} before
 * returning.
 *
 * <p>This interface intentionally has no production implementation in this task: pinning the
 * physical socket to a vetted address while preserving the logical JDBC/TLS hostname (and
 * proving no ordinary driver/OS DNS fallback occurs) is the scope of the PostgreSQL and MySQL
 * connector tasks. {@link CapabilityReportingDatabaseConnector} depends only on this
 * abstraction, never on {@code DriverManager} or an unpinned socket factory directly, so a
 * caller can never reach a raw driver connection without going through vetted-address dialing.
 */
public interface PhysicalJdbcConnector {

    /**
     * Opens a connection to {@code target}, applies mandatory read-only enforcement, exercises
     * it enough to prove reachability/authentication, and closes it.
     *
     * @throws java.sql.SQLTimeoutException if the deadline is exceeded while connecting
     * @throws SQLException for any other connection failure
     */
    void probe(
            JdbcConnectionTarget target,
            ValidatedDestination destination,
            byte[] borrowedSecretMaterial,
            Deadline deadline)
            throws SQLException;
}
