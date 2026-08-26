package com.knowimran.rivoquery.platform.connector.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.knowimran.rivoquery.identity.WorkspaceId;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorDialect;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage of {@link PostgresqlPhysicalJdbcConnector}'s own logic (encode/decode
 * round-trip, deterministic address ordering, and the SPI rejection branches reachable without a
 * real driver connect) - complementing {@code PostgresqlPhysicalJdbcConnectorIT}'s real-Postgres,
 * real-TLS coverage. The "did not enforce read-only mode" and {@code hasTimeoutCause} branches are
 * exercised by temporarily substituting pgjdbc's real driver for a fake {@link Driver}/{@link
 * Connection} registered with {@link DriverManager} - a standard JDBC test technique - rather than
 * requiring Docker for logic that lives entirely in this connector, not in a real server.
 */
class PostgresqlPhysicalJdbcConnectorTest {

    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROBE_URL = "jdbc:postgresql://db.example.com:5432/";

    private final PostgresqlConnectorDialect dialect = new PostgresqlConnectorDialect();
    private final PostgresqlPhysicalJdbcConnector connector = new PostgresqlPhysicalJdbcConnector(CLOCK);

    // --- encode/decode round trip, using the real encoder and the real decoder together -------

    @Test
    void encodeThenDecodeRoundTripsTheExactVettedAddressSet() {
        var addresses = Set.of(
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1}),
                new VettedAddress(AddressFamily.IPV6, ipv6Bytes((byte) 0x20, (byte) 0x01)));
        var destination = validatedDestination(addresses, NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(30)));
        String encoded = properties.getProperty(PostgresqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY);
        List<InetAddress> decoded = PostgresqlPhysicalJdbcConnector.decodePinnedAddresses(encoded);

        assertThat(decoded).hasSize(2);
        Set<List<Byte>> decodedByteLists = decoded.stream()
                .map(address -> toBoxedList(address.getAddress()))
                .collect(java.util.stream.Collectors.toSet());
        Set<List<Byte>> expectedByteLists = addresses.stream()
                .map(address -> toBoxedList(address.addressBytes()))
                .collect(java.util.stream.Collectors.toSet());
        assertThat(decodedByteLists).isEqualTo(expectedByteLists);
    }

    @Test
    void encodedAddressesAreSortedByFamilyThenNumericValueRegardlessOfInputOrder() {
        // Deliberately out-of-order input: IPv6 before IPv4, and descending within IPv4.
        var addresses = Set.of(
                new VettedAddress(AddressFamily.IPV6, ipv6Bytes((byte) 0xFF, (byte) 0xFF)),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 3}),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1}),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 2}));
        var destination = validatedDestination(addresses, NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(30)));
        String encoded = properties.getProperty(PostgresqlPhysicalJdbcConnector.PINNED_ADDRESSES_PROPERTY);
        List<InetAddress> decoded = PostgresqlPhysicalJdbcConnector.decodePinnedAddresses(encoded);

        // Every retry (RivoQueryPinnedSocketFactory's createSocket loop) walks this exact decoded
        // order - IPv4 candidates first (ascending numeric value), IPv6 candidates last.
        assertThat(decoded).extracting(InetAddress::getHostAddress)
                .containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3", "ffff:0:0:0:0:0:0:0");
    }

    @Test
    void decodeRejectsAMalformedTokenMissingItsFamilySeparator() {
        assertThatThrownBy(() -> PostgresqlPhysicalJdbcConnector.decodePinnedAddresses("NOSEPARATORHERE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decodeRejectsATokenWhoseByteLengthDoesNotMatchAnyAddressFamily() throws UnknownHostException {
        // A single hex byte (1 byte) is neither a valid IPv4 (4) nor IPv6 (16) address length -
        // InetAddress.getByAddress(byte[]) itself rejects it with UnknownHostException, which
        // decodePinnedAddresses wraps as IllegalStateException rather than letting it escape raw.
        assertThatThrownBy(() -> PostgresqlPhysicalJdbcConnector.decodePinnedAddresses("IPV4:AA"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(UnknownHostException.class);
    }

    // --- S6: byte-slice-based username/password split, not a discarded combined String ---------

    @Test
    void buildDriverPropertiesSplitsBorrowedSecretMaterialIntoUserAndPasswordAtTheFirstNulSeparator() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);
        // Only the first '\0' is the username/password separator; a second one, if present, is
        // just part of the password bytes.
        byte[] credential = ("alice" + '\0' + "s3cr3t" + '\0' + "withEmbeddedNul")
                .getBytes(StandardCharsets.UTF_8);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential, new Deadline(NOW.plusSeconds(30)));

        assertThat(properties.getProperty("user")).isEqualTo("alice");
        assertThat(properties.getProperty("password")).isEqualTo("s3cr3t\0withEmbeddedNul");
    }

    // --- S3: connect/TLS/auth-phase timeouts derived from the remaining deadline ---------------

    @Test
    void buildDriverPropertiesTightensConnectAndSocketTimeoutsToTheRemainingDeadline() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(3)));

        assertThat(properties.getProperty("connectTimeout")).isEqualTo("3");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("3");
        assertThat(properties.getProperty("loginTimeout")).isEqualTo("3");
        assertThat(Long.parseLong(properties.getProperty("sslResponseTimeout"))).isLessThanOrEqualTo(3000L);
    }

    @Test
    void buildDriverPropertiesNeverWidensTimeoutsBeyondTheDialectsOwnFixedDefaults() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(600));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(300)));

        // PostgresqlConnectorDialect's own fixed defaults are connectTimeout=10, socketTimeout=30 -
        // a generous remaining deadline (300s) must never widen them beyond those ceilings.
        assertThat(properties.getProperty("connectTimeout")).isEqualTo("10");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("30");
    }

    @Test
    void buildDriverPropertiesNeverSetsAZeroConnectOrSocketTimeoutEvenWithASubSecondRemainingDeadline() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        // A zero connectTimeout/socketTimeout/loginTimeout means "disabled" (infinite) to pgjdbc -
        // the opposite of what an almost-exhausted deadline requires.
        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusMillis(400)));

        assertThat(properties.getProperty("connectTimeout")).isEqualTo("1");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("1");
        assertThat(properties.getProperty("loginTimeout")).isEqualTo("1");
    }

    // --- probe() rejection branches reachable without dialing a real driver --------------------

    @Test
    void connectorKindMismatchIsRejectedWithoutEverBuildingDriverProperties() {
        var mysqlTarget = new MysqlConnectorDialect().buildProbeTarget("db.example.com", 3306);
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));

        assertThatThrownBy(() ->
                        connector.probe(mysqlTarget, destination, credential(), new Deadline(NOW.plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void malformedBorrowedSecretMaterialSurfacesAsASqlExceptionNeverAsARawRuntimeException() {
        // No '\0' separator: applyCredential's IllegalArgumentException must never escape probe()
        // as a raw RuntimeException - the PhysicalJdbcConnector SPI contract is SQLException-only.
        byte[] malformedCredential = "no-separator-here".getBytes(StandardCharsets.UTF_8);
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 5432);

        assertThatThrownBy(() -> connector.probe(
                        target, destination, malformedCredential, new Deadline(NOW.plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void connectionThatClaimsSetReadOnlySucceededButIsNotActuallyReadOnlyIsRejected() throws Exception {
        withFakeJdbcDriver(
                url -> neverActuallyReadOnlyConnection(),
                () -> assertThatThrownBy(() -> connector.probe(
                                dialect.buildProbeTarget("db.example.com", 5432),
                                validatedDestination(defaultAddresses(), NOW.plusSeconds(60)),
                                credential(),
                                new Deadline(NOW.plusSeconds(30))))
                        .isInstanceOf(SQLException.class)
                        .isNotInstanceOf(SQLTimeoutException.class));
    }

    // --- hasTimeoutCause's cause-chain-walking logic, exercised through translate()/probe() ----

    @Test
    void aDirectSocketTimeoutExceptionCauseIsClassifiedAsADeadlineTimeout() throws Exception {
        withFakeJdbcDriver(
                url -> {
                    throw new SQLException("wrapped", "08006", new SocketTimeoutException("timed out"));
                },
                () -> assertThatThrownBy(() -> connector.probe(
                                dialect.buildProbeTarget("db.example.com", 5432),
                                validatedDestination(defaultAddresses(), NOW.plusSeconds(60)),
                                credential(),
                                new Deadline(NOW.plusSeconds(30))))
                        .isInstanceOf(SQLTimeoutException.class));
    }

    @Test
    void aSocketTimeoutExceptionNestedTwoLevelsDeepIsStillClassifiedAsADeadlineTimeout() throws Exception {
        withFakeJdbcDriver(
                url -> {
                    throw new SQLException(
                            "wrapped", "08006", new RuntimeException("mid", new SocketTimeoutException("deep")));
                },
                () -> assertThatThrownBy(() -> connector.probe(
                                dialect.buildProbeTarget("db.example.com", 5432),
                                validatedDestination(defaultAddresses(), NOW.plusSeconds(60)),
                                credential(),
                                new Deadline(NOW.plusSeconds(30))))
                        .isInstanceOf(SQLTimeoutException.class));
    }

    @Test
    void aFailureWithNoSocketTimeoutExceptionAnywhereInItsCauseChainIsNotClassifiedAsATimeout() throws Exception {
        withFakeJdbcDriver(
                url -> {
                    throw new SQLException(
                            "wrapped", "08006", new RuntimeException("authentication failed, not a timeout"));
                },
                () -> assertThatThrownBy(() -> connector.probe(
                                dialect.buildProbeTarget("db.example.com", 5432),
                                validatedDestination(defaultAddresses(), NOW.plusSeconds(60)),
                                credential(),
                                new Deadline(NOW.plusSeconds(30))))
                        .isInstanceOf(SQLException.class)
                        .isNotInstanceOf(SQLTimeoutException.class));
    }

    // --- fixtures --------------------------------------------------------------------------

    private static byte[] credential() {
        return ("rivoquery_it" + '\0' + "rivoquery-it-synthetic-pw").getBytes(StandardCharsets.UTF_8);
    }

    private static Set<VettedAddress> defaultAddresses() {
        return Set.of(new VettedAddress(AddressFamily.IPV4, new byte[] {8, 8, 8, 8}));
    }

    private static ValidatedDestination validatedDestination(Set<VettedAddress> addresses, Instant expiresAt) {
        var assessment = new DestinationAssessment(
                "db.example.com",
                "db.example.com",
                5432,
                addresses,
                "fingerprint-v1",
                "policy-v4",
                NOW,
                expiresAt);
        var authority = new DestinationCapabilityAuthority(request -> assessment, () -> "nonce", CLOCK);
        var request = new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.CONNECTION_TEST,
                new StructuredEndpoint("db.example.com", 5432, "analytics"),
                new Deadline(NOW.plusSeconds(30)));
        return authority.validate(request);
    }

    private static byte[] ipv6Bytes(byte first, byte second) {
        var bytes = new byte[16];
        bytes[0] = first;
        bytes[1] = second;
        return bytes;
    }

    private static List<Byte> toBoxedList(byte[] bytes) {
        List<Byte> boxed = new java.util.ArrayList<>(bytes.length);
        for (byte b : bytes) {
            boxed.add(b);
        }
        return boxed;
    }

    // --- fake-driver plumbing for branches that require a live java.sql.Connection ------------

    @FunctionalInterface
    private interface FakeConnectionFactory {
        Connection connect(String url) throws SQLException;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * Temporarily deregisters the real pgjdbc {@link Driver} and registers a fake one accepting
     * the same {@code jdbc:postgresql:} URL prefix for the duration of {@code body}, always
     * restoring the original registration afterward regardless of outcome.
     */
    private static void withFakeJdbcDriver(FakeConnectionFactory factory, ThrowingRunnable body) throws Exception {
        Driver realPgDriver = DriverManager.getDriver(PROBE_URL);
        DriverManager.deregisterDriver(realPgDriver);
        var fakeDriver = new FakeDriver(factory);
        DriverManager.registerDriver(fakeDriver);
        try {
            body.run();
        } finally {
            DriverManager.deregisterDriver(fakeDriver);
            DriverManager.registerDriver(realPgDriver);
        }
    }

    private static Connection neverActuallyReadOnlyConnection() {
        return (Connection) Proxy.newProxyInstance(
                PostgresqlPhysicalJdbcConnectorTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("isReadOnly") || name.equals("isClosed")) {
                        return false;
                    }
                    if (name.equals("setReadOnly") || name.equals("close")) {
                        return null; // claims success but never actually becomes read-only
                    }
                    if (name.equals("toString")) {
                        return "FakeNeverReadOnlyConnection";
                    }
                    if (name.equals("hashCode")) {
                        return System.identityHashCode(proxy);
                    }
                    if (name.equals("equals")) {
                        return proxy == args[0];
                    }
                    return defaultReturnValue(method.getReturnType());
                });
    }

    private static Object defaultReturnValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return Boolean.FALSE;
        }
        return 0;
    }

    private static final class FakeDriver implements Driver {
        private final FakeConnectionFactory factory;

        FakeDriver(FakeConnectionFactory factory) {
            this.factory = factory;
        }

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            return factory.connect(url);
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }
}
