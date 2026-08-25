package com.knowimran.rivoquery.platform.connector;

import static org.assertj.core.api.Assertions.assertThat;

import com.knowimran.rivoquery.connection.ConnectionId;
import com.knowimran.rivoquery.connection.ConnectionVersion;
import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.ConnectionPurpose;
import com.knowimran.rivoquery.connection.application.destination.DestinationAssessment;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.connection.application.destination.DestinationRequest;
import com.knowimran.rivoquery.connection.application.destination.StructuredEndpoint;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.connection.connector.ConnectorOutcomeCode;
import com.knowimran.rivoquery.identity.WorkspaceId;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class CapabilityReportingDatabaseConnectorTest {

    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final PostgresqlConnectorDialect dialect = new PostgresqlConnectorDialect();

    @Test
    void capabilitiesDelegateToTheDialect() {
        var connector = new CapabilityReportingDatabaseConnector(dialect, (t, d, s, dl) -> {}, CLOCK);

        assertThat(connector.capabilities()).isEqualTo(dialect.capabilities());
    }

    @Test
    void deadlineAlreadyExceededShortCircuitsWithoutDialingThePhysicalConnector() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        var connector = new CapabilityReportingDatabaseConnector(
                dialect, (t, d, s, dl) -> called.set(true), CLOCK);

        var result = connector.test(validatedDestination(), secret(), new Deadline(NOW.minusSeconds(1)));

        assertThat(result.successful()).isFalse();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.DEADLINE_EXCEEDED);
        assertThat(called.get()).isFalse();
    }

    @Test
    void successfulProbeReportsConnectionTestSucceeded() {
        var connector = new CapabilityReportingDatabaseConnector(dialect, (t, d, s, dl) -> {}, CLOCK);

        var result = connector.test(validatedDestination(), secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(result.successful()).isTrue();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.CONNECTION_TEST_SUCCEEDED);
    }

    @Test
    void sqlTimeoutMapsToDeadlineExceeded() {
        var connector = new CapabilityReportingDatabaseConnector(
                dialect,
                (t, d, s, dl) -> {
                    throw new SQLTimeoutException("timed out");
                },
                CLOCK);

        var result = connector.test(validatedDestination(), secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(result.successful()).isFalse();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.DEADLINE_EXCEEDED);
    }

    @Test
    void otherSqlExceptionMapsToConnectionTestFailedWithoutLeakingDriverDetail() {
        var connector = new CapabilityReportingDatabaseConnector(
                dialect,
                (t, d, s, dl) -> {
                    throw new SQLException("FATAL: password authentication failed for user \"root\"");
                },
                CLOCK);

        var result = connector.test(validatedDestination(), secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(result.successful()).isFalse();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.CONNECTION_TEST_FAILED);
    }

    @Test
    void probeTargetUsesTheOriginalTlsHostnameNotTheCanonicalHostnameAndAlwaysEnforcesReadOnly() {
        AtomicReference<JdbcConnectionTarget> captured = new AtomicReference<>();
        var connector = new CapabilityReportingDatabaseConnector(
                dialect, (t, d, s, dl) -> captured.set(t), CLOCK);

        connector.test(validatedDestination(), secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(captured.get().jdbcUrl()).isEqualTo("jdbc:postgresql://DB.Example.com:5432/");
        assertThat(captured.get().enforceReadOnlyOnConnect()).isTrue();
    }

    @Test
    void destinationMintedForADifferentDialectIsRejectedWithoutDialing() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        var connector = new CapabilityReportingDatabaseConnector(
                dialect, (t, d, s, dl) -> called.set(true), CLOCK);
        var mysqlDestination = validatedDestination(DatabaseKind.MYSQL, NOW.plusSeconds(60));

        var result = connector.test(mysqlDestination, secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(result.successful()).isFalse();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.DESTINATION_NOT_ALLOWED);
        assertThat(called.get()).isFalse();
    }

    /**
     * Reproduces the reviewer's DNS-rebinding-window scenario: a capability's own vetted-address
     * window (destination.expiresAt()) has elapsed by the time the connector dials it, even
     * though the {@code deadline} argument passed into {@code test()} is fresh and would, on its
     * own, pass the connector's initial deadline check. The connector must not silently dial the
     * stale capability.
     */
    @Test
    void destinationWhoseOwnExpiryHasElapsedIsRejectedEvenWhenTheOperationDeadlineHasNot() {
        AtomicReference<Boolean> called = new AtomicReference<>(false);
        Clock laterClock = Clock.fixed(NOW.plusSeconds(15), ZoneOffset.UTC);
        var connector = new CapabilityReportingDatabaseConnector(
                dialect, (t, d, s, dl) -> called.set(true), laterClock);
        // Capability minted at NOW with a vetted-address window that expires at NOW+10s (already
        // elapsed relative to laterClock=NOW+15s), but bound to an operation deadline of
        // NOW+30s that is still fresh relative to laterClock.
        var destination = validatedDestination(DatabaseKind.POSTGRESQL, NOW.plusSeconds(10));

        var result = connector.test(destination, secret(), new Deadline(NOW.plusSeconds(30)));

        assertThat(result.successful()).isFalse();
        assertThat(result.safeCode()).isEqualTo(ConnectorOutcomeCode.DESTINATION_NOT_ALLOWED);
        assertThat(called.get()).isFalse();
    }

    private static byte[] secret() {
        return "borrowed-secret-material".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ValidatedDestination validatedDestination() {
        return validatedDestination(DatabaseKind.POSTGRESQL, NOW.plusSeconds(60));
    }

    private static ValidatedDestination validatedDestination(DatabaseKind connectorKind, Instant expiresAt) {
        var assessment = new DestinationAssessment(
                "db.example.com",
                "DB.Example.com",
                5432,
                Set.of(new VettedAddress(AddressFamily.IPV4, new byte[] {8, 8, 8, 8})),
                "fingerprint-v1",
                "policy-v4",
                NOW,
                expiresAt);
        var authority = new DestinationCapabilityAuthority(request -> assessment, () -> "nonce", CLOCK);
        var request = new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                connectorKind,
                ConnectionPurpose.CONNECTION_TEST,
                new StructuredEndpoint("DB.Example.com", 5432, "analytics"),
                new Deadline(NOW.plusSeconds(30)));
        return authority.validate(request);
    }
}
