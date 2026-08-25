package com.knowimran.rivoquery.platform.connector;

import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.connection.connector.ConnectorCapabilities;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.connection.connector.ConnectorResult;
import com.knowimran.rivoquery.connection.connector.DatabaseConnector;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.util.Objects;

/**
 * Shared {@link DatabaseConnector} SPI machinery: a single implementation parameterized by a
 * {@link ConnectorDialect} (URL/property construction, capability reporting) and a
 * {@link PhysicalJdbcConnector} (the RivoQuery-controlled, vetted-address socket hook).
 *
 * <p>This class never accepts a client-supplied URL, driver class, socket factory, or property
 * map: it only ever builds the probe target through {@link ConnectorDialect#buildProbeTarget}
 * and dials it through the injected {@link PhysicalJdbcConnector}. It never touches
 * {@code java.sql.DriverManager} directly.
 */
public final class CapabilityReportingDatabaseConnector implements DatabaseConnector {

    private final ConnectorDialect dialect;
    private final PhysicalJdbcConnector physicalConnector;
    private final Clock clock;

    public CapabilityReportingDatabaseConnector(
            ConnectorDialect dialect, PhysicalJdbcConnector physicalConnector, Clock clock) {
        this.dialect = Objects.requireNonNull(dialect, "dialect");
        this.physicalConnector = Objects.requireNonNull(physicalConnector, "physicalConnector");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ConnectorCapabilities capabilities() {
        return dialect.capabilities();
    }

    @Override
    public ConnectorResult test(ValidatedDestination destination, byte[] borrowedSecretMaterial, Deadline deadline) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(borrowedSecretMaterial, "borrowedSecretMaterial");
        Objects.requireNonNull(deadline, "deadline");
        if (deadline.isExpired(clock)) {
            return new ConnectorResult(false, ConnectorOutcomeCode.DEADLINE_EXCEEDED);
        }
        if (!isConsumableByThisDialect(destination)) {
            return new ConnectorResult(false, ConnectorOutcomeCode.DESTINATION_NOT_ALLOWED);
        }

        JdbcConnectionTarget target =
                dialect.buildProbeTarget(destination.originalTlsHostname(), destination.port());
        try {
            physicalConnector.probe(target, destination, borrowedSecretMaterial, deadline);
            return new ConnectorResult(true, ConnectorOutcomeCode.CONNECTION_TEST_SUCCEEDED);
        } catch (SQLTimeoutException timedOut) {
            return new ConnectorResult(false, ConnectorOutcomeCode.DEADLINE_EXCEEDED);
        } catch (SQLException failed) {
            return new ConnectorResult(false, ConnectorOutcomeCode.CONNECTION_TEST_FAILED);
        }
    }

    /**
     * Consumption-time re-check mirroring {@link ValidatedDestination#requireValid} for the
     * fields this connector actually has: the capability's bound connector dialect and its own
     * TTL/operation deadline. A capability minted for one dialect (or whose vetted-address window
     * has since expired) must never be dialed by a mismatched or stale connector — that is
     * exactly the DNS-rebinding window ADR-0013's TTL bounding exists to close.
     *
     * <p>This connector has neither the original {@code DestinationRequest} nor the current
     * {@code DestinationPolicy} version available, so it cannot perform the full binding check
     * that {@link ValidatedDestination#requireValid} performs. Callers that hold that context
     * (the connection orchestration layer) MUST additionally call {@code
     * destination.requireValid(...)} before ever handing a capability to a connector.
     */
    private boolean isConsumableByThisDialect(ValidatedDestination destination) {
        return destination.connectorKind() == dialect.kind()
                && clock.instant().isBefore(destination.expiresAt())
                && !destination.operationDeadline().isExpired(clock);
    }
}
