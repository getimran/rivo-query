package com.knowimran.rivoquery.platform.connector.mysql;

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
import com.knowimran.rivoquery.platform.connector.JdbcConnectionTarget;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorDialect;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorProperty;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage of {@link MysqlPhysicalJdbcConnector}'s own logic (encode/decode round-trip,
 * deterministic address ordering, and the SPI rejection branches reachable without a real driver
 * connect) - complementing {@code MysqlPhysicalJdbcConnectorIT}'s real-MySQL, real-TLS coverage.
 * Mirrors {@code platform.connector.postgresql.PostgresqlPhysicalJdbcConnectorTest}'s structure.
 */
class MysqlPhysicalJdbcConnectorTest {

    private static final String ULID = "01J6MZ4H9ST5N7YQ2C8W3V0KFX";
    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_" + ULID);
    private static final ConnectionId CONNECTION = new ConnectionId("con_" + ULID);
    private static final Instant NOW = Instant.parse("2026-08-20T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String PROBE_URL = "jdbc:mysql://db.example.com:3306/";

    private final MysqlConnectorDialect dialect = new MysqlConnectorDialect();
    private final MysqlPhysicalJdbcConnector connector = new MysqlPhysicalJdbcConnector(CLOCK);

    // --- encode/decode round trip, using the real encoder and the real decoder together -------

    @Test
    void encodeThenDecodeRoundTripsTheExactVettedAddressSet() {
        var addresses = Set.of(
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1}),
                new VettedAddress(AddressFamily.IPV6, ipv6Bytes((byte) 0x20, (byte) 0x01)));
        var destination = validatedDestination(addresses, NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(30)));
        String encoded = properties.getProperty(mysqlPinnedAddressesPropertyKey());
        List<InetAddress> decoded = MysqlPhysicalJdbcConnector.decodePinnedAddresses(encoded);

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
        var addresses = Set.of(
                new VettedAddress(AddressFamily.IPV6, ipv6Bytes((byte) 0xFF, (byte) 0xFF)),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 3}),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 1}),
                new VettedAddress(AddressFamily.IPV4, new byte[] {10, 0, 0, 2}));
        var destination = validatedDestination(addresses, NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(30)));
        String encoded = properties.getProperty(mysqlPinnedAddressesPropertyKey());
        List<InetAddress> decoded = MysqlPhysicalJdbcConnector.decodePinnedAddresses(encoded);

        assertThat(decoded)
                .extracting(InetAddress::getHostAddress)
                .containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3", "ffff:0:0:0:0:0:0:0");
    }

    @Test
    void decodeRejectsAMalformedTokenMissingItsFamilySeparator() {
        assertThatThrownBy(() -> MysqlPhysicalJdbcConnector.decodePinnedAddresses("NOSEPARATORHERE"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decodeRejectsATokenWhoseByteLengthDoesNotMatchAnyAddressFamily() {
        assertThatThrownBy(() -> MysqlPhysicalJdbcConnector.decodePinnedAddresses("IPV4:AA"))
                .isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(UnknownHostException.class);
    }

    // --- byte-slice-based username/password split, not a discarded combined String -------------

    @Test
    void buildDriverPropertiesSplitsBorrowedSecretMaterialIntoUserAndPasswordAtTheFirstNulSeparator() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);
        byte[] credential = ("alice" + '\0' + "s3cr3t" + '\0' + "withEmbeddedNul").getBytes(StandardCharsets.UTF_8);

        Properties properties =
                connector.buildDriverProperties(target, destination, credential, new Deadline(NOW.plusSeconds(30)));

        assertThat(properties.getProperty("user")).isEqualTo("alice");
        assertThat(properties.getProperty("password")).isEqualTo("s3cr3t\0withEmbeddedNul");
    }

    // --- socket factory pinning (cheap direct-assertion insurance alongside the IT's end-to-end proof) ---

    @Test
    void buildDriverPropertiesPinsTheSocketFactoryToRivoQueryPinnedSocketFactory() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(30)));

        assertThat(properties.getProperty("socketFactory")).isEqualTo(RivoQueryPinnedSocketFactory.class.getName());
    }

    // --- connect/socket timeouts derived from the remaining deadline (milliseconds) -------------

    @Test
    void buildDriverPropertiesTightensConnectAndSocketTimeoutsToTheRemainingDeadline() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusMillis(2500)));

        assertThat(properties.getProperty("connectTimeout")).isEqualTo("2500");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("2500");
    }

    @Test
    void buildDriverPropertiesNeverWidensTimeoutsBeyondTheDialectsOwnFixedDefaults() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(600));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW.plusSeconds(300)));

        // MysqlConnectorDialect's own fixed defaults are connectTimeout=10000ms, socketTimeout=30000ms
        // - a generous remaining deadline (300s) must never widen them beyond those ceilings.
        assertThat(properties.getProperty("connectTimeout")).isEqualTo("10000");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("30000");
    }

    @Test
    void buildDriverPropertiesNeverSetsAZeroConnectOrSocketTimeoutEvenWithASubMillisecondRemainingDeadline() {
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(NOW));

        assertThat(properties.getProperty("connectTimeout")).isEqualTo("1");
        assertThat(properties.getProperty("socketTimeout")).isEqualTo("1");
    }

    // --- probe() rejection branches reachable without dialing a real driver --------------------

    @Test
    void connectorKindMismatchIsRejectedWithoutEverBuildingDriverProperties() {
        var postgresTarget = new PostgresqlConnectorDialect().buildProbeTarget("db.example.com", 5432);
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> connector.probe(
                        postgresTarget, destination, credential(), new Deadline(NOW.plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void aTargetMissingSslModeVerifyIdentityIsRejectedWithoutEverBuildingDriverProperties() {
        // S5: JdbcConnectionTarget's compact constructor only enforces a property-KEY allowlist, not
        // that sslMode is actually present, so a target missing it is constructible even though the
        // current production caller always supplies it. Without probe()'s own structural guard, this
        // would silently fall back to Connector/J's sslMode=PREFERRED default - no certificate chain
        // and no hostname verification at all.
        var baseTarget = dialect.buildProbeTarget("db.example.com", 3306);
        var propertiesWithoutSslMode = new LinkedHashMap<>(baseTarget.properties());
        propertiesWithoutSslMode.remove(MysqlConnectorProperty.SSL_MODE.key());
        var targetMissingSslMode = new JdbcConnectionTarget(
                baseTarget.kind(),
                baseTarget.driverClassName(),
                baseTarget.jdbcUrl(),
                propertiesWithoutSslMode,
                baseTarget.enforceReadOnlyOnConnect());
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> connector.probe(
                        targetMissingSslMode, destination, credential(), new Deadline(NOW.plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void aTargetWithAWeakerSslModeIsRejectedWithoutEverBuildingDriverProperties() {
        var baseTarget = dialect.buildProbeTarget("db.example.com", 3306);
        var weakerProperties = new LinkedHashMap<>(baseTarget.properties());
        weakerProperties.put(MysqlConnectorProperty.SSL_MODE.key(), "PREFERRED");
        var targetWithWeakerSslMode = new JdbcConnectionTarget(
                baseTarget.kind(),
                baseTarget.driverClassName(),
                baseTarget.jdbcUrl(),
                weakerProperties,
                baseTarget.enforceReadOnlyOnConnect());
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));

        assertThatThrownBy(() -> connector.probe(
                        targetWithWeakerSslMode, destination, credential(), new Deadline(NOW.plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void malformedBorrowedSecretMaterialSurfacesAsASqlExceptionNeverAsARawRuntimeException() {
        byte[] malformedCredential = "no-separator-here".getBytes(StandardCharsets.UTF_8);
        var destination = validatedDestination(defaultAddresses(), NOW.plusSeconds(60));
        var target = dialect.buildProbeTarget("db.example.com", 3306);

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
                                dialect.buildProbeTarget("db.example.com", 3306),
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
                                dialect.buildProbeTarget("db.example.com", 3306),
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
                                dialect.buildProbeTarget("db.example.com", 3306),
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
                                dialect.buildProbeTarget("db.example.com", 3306),
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
                "db.example.com", "db.example.com", 3306, addresses, "fingerprint-v1", "policy-v4", NOW, expiresAt);
        var authority = new DestinationCapabilityAuthority(request -> assessment, () -> "nonce", CLOCK);
        var request = new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.MYSQL,
                ConnectionPurpose.CONNECTION_TEST,
                new StructuredEndpoint("db.example.com", 3306, "analytics"),
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

    private static String mysqlPinnedAddressesPropertyKey() {
        return "rivoquery.internal.socketFactory.pinnedAddresses";
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

    private static void withFakeJdbcDriver(FakeConnectionFactory factory, ThrowingRunnable body) throws Exception {
        Driver realMysqlDriver = DriverManager.getDriver(PROBE_URL);
        DriverManager.deregisterDriver(realMysqlDriver);
        var fakeDriver = new FakeDriver(factory);
        DriverManager.registerDriver(fakeDriver);
        try {
            body.run();
        } finally {
            DriverManager.deregisterDriver(fakeDriver);
            DriverManager.registerDriver(realMysqlDriver);
        }
    }

    private static Connection neverActuallyReadOnlyConnection() {
        return (Connection) Proxy.newProxyInstance(
                MysqlPhysicalJdbcConnectorTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if (name.equals("isReadOnly") || name.equals("isClosed")) {
                        return false;
                    }
                    if (name.equals("setReadOnly") || name.equals("close")) {
                        return null;
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
            return url != null && url.toLowerCase(Locale.ROOT).startsWith("jdbc:mysql:");
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
