package com.knowimran.rivoquery.platform.connector.mysql;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.platform.connector.JdbcConnectionTarget;
import com.knowimran.rivoquery.platform.connector.MysqlConnectorProperty;
import com.knowimran.rivoquery.platform.connector.PhysicalJdbcConnector;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Clock;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * The MySQL {@link PhysicalJdbcConnector}: dials {@code target} through MySQL Connector/J (9.7.0,
 * pinned transitively via the Spring Boot 3.5.16 parent BOM - see {@code apps/api/pom.xml}) using
 * {@link RivoQueryPinnedSocketFactory} so the physical TCP socket only ever reaches an address in
 * {@code destination.vettedAddresses()}, while the JDBC URL/TLS host stays {@code
 * destination.originalTlsHostname()} for full chain and hostname verification.
 *
 * <p><b>How this differs from {@code PostgresqlPhysicalJdbcConnector} (P2-110), and why.</b>
 * Decompiling {@code com.mysql:mysql-connector-j:9.7.0} shows Connector/J's own {@code
 * com.mysql.cj.protocol.SocketFactory} interface is architecturally different from pgjdbc's {@code
 * javax.net.SocketFactory}: its {@code connect(String, int, PropertySet, int)} method receives the
 * JDBC URL host explicitly and is fully responsible for creating <em>and</em> connecting the socket
 * itself - there is no "return an already-connected socket and the driver skips its own resolution"
 * trick to exploit, because Connector/J never attempts a fallback resolution of its own in the first
 * place. {@code com.mysql.cj.protocol.a.NativeSocketConnection#connect} stores the given hostname in
 * its own {@code host} field <em>before</em> calling {@code socketFactory.connect(...)}, so {@code
 * SocketConnection#getHost()} (which drives TLS SNI and hostname/IP verification in {@code
 * com.mysql.cj.protocol.ExportControlled#performTlsHandshake}) always reflects the original JDBC URL
 * host regardless of what address this connector's socket factory actually dials. This connector
 * therefore does not need to defeat or race the driver's own resolution step at all - {@link
 * RivoQueryPinnedSocketFactory} simply never calls a hostname-resolving API, and TLS
 * chain/hostname/IP-SAN verification runs through Connector/J's completely unmodified {@code
 * ExportControlled} code path. {@code MysqlPhysicalJdbcConnectorIT} proves this end to end against a
 * real MySQL server, and {@code RivoQueryPinnedSocketFactoryTest} covers the factory's own
 * connect/retry/deadline logic in isolation.
 *
 * <p>A second, equally material difference: Connector/J instantiates the {@code SocketFactory} via
 * its public <b>no-argument</b> constructor only (confirmed by decompiling {@code
 * com.mysql.cj.protocol.AbstractSocketConnection#createSocketFactory} / {@code
 * com.mysql.cj.util.Util#getInstance}), unlike pgjdbc's {@code Properties}-argument constructor.
 * Every value {@link RivoQueryPinnedSocketFactory#connect} needs is therefore read from the {@code
 * PropertySet} argument of {@code connect} itself, not from a constructor parameter - the same
 * internal, {@code String}-encoded {@link Properties} keys are still used to carry that state from
 * this connector into the socket factory (empirically confirmed: {@code
 * com.mysql.cj.conf.DefaultPropertySet#initializeProperties} preserves every key outside
 * Connector/J's own {@code PropertyKey} enum as an ordinary, retrievable string-valued {@code
 * CATEGORY_USER_DEFINED} runtime property, so these internal keys survive exactly the same way
 * pgjdbc's {@code Properties}-argument constructor received its own internal keys, just via a
 * different mechanism).
 *
 * <p><b>Production TLS trust needs no custom class at all (unlike pgjdbc's {@code
 * TrustAnchorSslSocketFactory}/{@code DefaultJavaSSLFactory} split).</b> Connector/J's {@code
 * ExportControlled#performTlsHandshake} already builds its trust manager from the {@code
 * trustCertificateKeyStoreUrl}/{@code trustCertificateKeyStoreType}/{@code
 * trustCertificateKeyStorePassword} properties when set, and - critically - when {@code
 * trustCertificateKeyStoreUrl} is left unset and {@code fallbackToSystemTrustStore} is {@code true}
 * (its documented, unconditional default), calls {@code TrustManagerFactory#init((KeyStore) null)},
 * which the JSSE spec defines as loading the JVM's own default trust store (cacerts) - the exact
 * production behavior this connector needs, achieved purely by leaving those three properties unset
 * rather than by wiring a different {@code sslfactory} class the way pgjdbc's production path
 * required. This was verified empirically, not merely read from the source: {@code
 * MysqlPhysicalJdbcConnectorIT#productionPathConnectsWhenTheTargetsCertificateChainsToATrustedCa}
 * and its rejection counterpart install/remove a truststore via the {@code javax.net.ssl.trustStore}
 * system property (the mechanism {@code getTrustStoreConfigurations} itself reads when {@code
 * fallbackToSystemTrustStore=true} and no explicit truststore is configured) - unlike pgjdbc's
 * {@code DefaultJavaSSLFactory}, Connector/J's own trust-building code does not consult {@code
 * SSLContext.setDefault(...)} at all, so that pgjdbc-style test mechanism would not have worked here
 * and was deliberately not reused.
 *
 * <p>For tests, a synthetic CA is trusted by materializing a throwaway PKCS12 truststore file
 * containing exactly that one CA certificate (see {@link #writeTestTrustStore}) and pointing {@code
 * trustCertificateKeyStoreUrl} at it - there is no equivalent of pgjdbc's custom {@code
 * TrustAnchorSslSocketFactory} class for MySQL because Connector/J's trust mechanism is already
 * file/property-driven, not class-name-driven.
 *
 * <p>{@code borrowedSecretMaterial} is UTF-8 {@code username + '\0' + password}, for the same reason
 * documented on {@code PostgresqlPhysicalJdbcConnector}: the connection domain has no persisted
 * username field yet (P2-100's own documented gap), so this connector defines the minimal
 * self-contained encoding it needs, narrowly scoped to this task.
 */
public final class MysqlPhysicalJdbcConnector implements PhysicalJdbcConnector {

    /** Internal-only Connector/J connection property key; never derived from client input. */
    static final String PINNED_ADDRESSES_PROPERTY = "rivoquery.internal.socketFactory.pinnedAddresses";

    static final String PINNED_PORT_PROPERTY = "rivoquery.internal.socketFactory.pinnedPort";
    static final String DEADLINE_PROPERTY = "rivoquery.internal.socketFactory.deadline";

    private static final String SOCKET_FACTORY_PROPERTY = "socketFactory";
    private static final String USER_PROPERTY = "user";
    private static final String PASSWORD_PROPERTY = "password";
    private static final String TRUST_CERTIFICATE_KEYSTORE_URL_PROPERTY = "trustCertificateKeyStoreUrl";
    private static final String TRUST_CERTIFICATE_KEYSTORE_TYPE_PROPERTY = "trustCertificateKeyStoreType";
    private static final String TRUST_CERTIFICATE_KEYSTORE_PASSWORD_PROPERTY = "trustCertificateKeyStorePassword";
    private static final char CREDENTIAL_SEPARATOR = '\0';
    private static final String SSL_MODE_VERIFY_IDENTITY = "VERIFY_IDENTITY";
    private static final String GENERIC_CONNECTION_FAILURE_SQLSTATE = "08006";
    private static final String TEST_TRUST_STORE_TYPE = "PKCS12";
    private static final String TEST_TRUST_STORE_ALIAS = "rivoquery-test-ca";

    private final Clock clock;
    private final Optional<TestTrustStore> testTrustStore;

    public MysqlPhysicalJdbcConnector(Clock clock) {
        this(clock, Optional.empty());
    }

    /**
     * @param testTrustAnchor when present, TLS chain verification trusts only this CA certificate,
     *     materialized into a throwaway PKCS12 truststore file consumed via Connector/J's own {@code
     *     trustCertificateKeyStoreUrl}/{@code trustCertificateKeyStoreType}/{@code
     *     trustCertificateKeyStorePassword} properties, instead of Connector/J's own default trust
     *     path. This is a deployment-time construction knob for test environments that must trust a
     *     synthetic CA (e.g. Testcontainers-issued certificates) - it is never derived from request,
     *     tenant, or client input. Production wiring MUST use {@link #MysqlPhysicalJdbcConnector(Clock)}
     *     (equivalent to {@code Optional.empty()}).
     */
    public MysqlPhysicalJdbcConnector(Clock clock, Optional<X509Certificate> testTrustAnchor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(testTrustAnchor, "testTrustAnchor");
        this.testTrustStore = testTrustAnchor.map(MysqlPhysicalJdbcConnector::writeTestTrustStore);
    }

    @Override
    public void probe(
            JdbcConnectionTarget target,
            ValidatedDestination destination,
            byte[] borrowedSecretMaterial,
            Deadline deadline)
            throws SQLException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(borrowedSecretMaterial, "borrowedSecretMaterial");
        Objects.requireNonNull(deadline, "deadline");
        if (target.kind() != DatabaseKind.MYSQL) {
            throw safeFailure("connector kind mismatch");
        }
        if (!SSL_MODE_VERIFY_IDENTITY.equals(target.properties().get(MysqlConnectorProperty.SSL_MODE.key()))) {
            // S5: JdbcConnectionTarget's compact constructor only enforces a property-KEY allowlist,
            // not that sslMode is actually present - so a target missing it (defaulting Connector/J
            // to sslMode=PREFERRED, which per ExportControlled performs NO certificate chain and NO
            // hostname verification at all) is constructible even though the current production
            // caller (CapabilityReportingDatabaseConnector) always supplies it. This makes the
            // security property this class's Javadoc already claims ("verifies TLS against
            // destination.originalTlsHostname()") a structurally enforced precondition of this
            // method, not an assumption about every possible caller.
            throw safeFailure("connector requires sslMode=VERIFY_IDENTITY");
        }
        if (deadline.isExpired(clock)) {
            throw new SQLTimeoutException("connection deadline already exceeded");
        }

        try {
            Properties driverProperties;
            try {
                driverProperties = buildDriverProperties(target, destination, borrowedSecretMaterial, deadline);
            } catch (IllegalArgumentException | IllegalStateException | DateTimeParseException failure) {
                // Malformed borrowed secret material or an internal encoding failure (never
                // client-controlled) must still surface only through the connector SPI's
                // SQLException contract - see CapabilityReportingDatabaseConnector, which only
                // expects SQLException/SQLTimeoutException from this method.
                throw safeFailureFrom("connection attempt failed", failure);
            }
            try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), driverProperties)) {
                connection.setReadOnly(true);
                if (!connection.isReadOnly()) {
                    throw safeFailure("connection did not enforce read-only mode");
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SELECT 1");
                }
            }
        } catch (SQLException failure) {
            throw translate(failure, deadline);
        }
    }

    /**
     * Package-private so tests can obtain the exact {@link Properties} this connector would pass to
     * {@link DriverManager#getConnection(String, Properties)} - e.g. to open their own connection
     * through the identical pinned-socket/trust-anchor configuration and attempt a write, which
     * {@link #probe} itself (a lightweight read-only reachability check) never does.
     *
     * <p><b>S1 (P2-120 adversarial review, NOT fully closed - a documented residual security risk).</b>
     * Every call to {@link DriverManager#getConnection(String, Properties)} routes through Connector/J's
     * own {@code com.mysql.cj.conf.ConnectionUrl.getConnectionUrlInstance}, which interns the resulting
     * {@code ConnectionUrl} into a static, process-lifetime {@code LRUCache<String, ConnectionUrl>}
     * (capacity 100) keyed by a string that embeds this call's {@code user=}/{@code password=}
     * properties in cleartext (confirmed by decompiling {@code ConnectionUrl}/{@code
     * buildConnectionStringCacheKey} for 9.7.0). Because {@link #DEADLINE_PROPERTY} is a fresh
     * per-call ISO-8601 timestamp - required by {@link RivoQueryPinnedSocketFactory}'s own approved
     * deadline mechanism, so it cannot be removed without touching that already-reviewed core logic -
     * every cache key is unique, so every {@link #probe} call adds a <em>new</em> retained
     * plaintext-credential cache entry rather than reusing one, both defeating the cache's purpose and
     * maximizing secret residency in this JVM's memory, undermining the deliberate byte-slice
     * credential handling in {@link #applyCredential} (which otherwise never builds an intermediate
     * combined-credential {@code String}). A lower-level bypass exists in principle - Connector/J's own
     * {@code NonRegisteringDriver#connect} calls {@code com.mysql.cj.jdbc.ConnectionImpl.getInstance(
     * com.mysql.cj.conf.HostInfo)} directly for a single-host URL, without going through {@code
     * ConnectionUrl}'s cache - but it is an internal, undocumented static factory (not part of the
     * documented {@code SocketFactory}/{@code DriverManager} surface this design otherwise relies on),
     * would require independently re-deriving host/port/database from this connector's own JDBC URL
     * construction to keep {@code HostInfo} correct, and has no existing test coverage proving TLS/
     * read-only/timeout behavior stays identical - assessed as exceeding this task's reasonable scope.
     * No Connector/J property exists to disable or bypass this cache. This is a genuine, unresolved
     * secret-handling gap requiring a product/security decision (e.g. whether the {@code
     * ConnectionImpl.getInstance(HostInfo)} bypass is worth its added maintenance risk) outside this
     * class's own engineering scope - see the P2-120 task row in {@code docs/IMPLEMENTATION_TASKS.md}.
     */
    Properties buildDriverProperties(
            JdbcConnectionTarget target,
            ValidatedDestination destination,
            byte[] borrowedSecretMaterial,
            Deadline deadline) {
        Properties properties = target.toDriverProperties();
        properties.setProperty(SOCKET_FACTORY_PROPERTY, RivoQueryPinnedSocketFactory.class.getName());
        properties.setProperty(PINNED_ADDRESSES_PROPERTY, encodePinnedAddresses(destination));
        properties.setProperty(PINNED_PORT_PROPERTY, String.valueOf(destination.port()));
        properties.setProperty(DEADLINE_PROPERTY, deadline.expiresAt().toString());
        if (testTrustStore.isPresent()) {
            TestTrustStore trustStore = testTrustStore.get();
            properties.setProperty(TRUST_CERTIFICATE_KEYSTORE_URL_PROPERTY, trustStore.fileUrl());
            properties.setProperty(TRUST_CERTIFICATE_KEYSTORE_TYPE_PROPERTY, TEST_TRUST_STORE_TYPE);
            properties.setProperty(TRUST_CERTIFICATE_KEYSTORE_PASSWORD_PROPERTY, trustStore.password());
        }
        // Else: trustCertificateKeyStoreUrl/-Type/-Password stay unset, so Connector/J's own
        // fallbackToSystemTrustStore=true default performs TrustManagerFactory#init((KeyStore) null)
        // - full PKIX chain validation against the JVM's own cacerts trust store. See this class's
        // Javadoc for the empirical verification of that production path.
        applyCredential(properties, borrowedSecretMaterial);
        boundTimeoutsToRemainingDeadline(properties, deadline, clock);
        return properties;
    }

    /**
     * Derives Connector/J's own {@code connectTimeout}/{@code socketTimeout} properties (both
     * milliseconds) from the caller's remaining absolute {@link Deadline} budget at connect-build
     * time, so the connect+TLS+auth+probe cycle is genuinely bounded by the caller's deadline - not
     * just the initial TCP connect (already bounded independently by {@link
     * RivoQueryPinnedSocketFactory}'s own per-attempt/absolute deadline logic). {@code socketTimeout}
     * is applied by {@code NativeSocketConnection#connect} as the socket's {@code SO_TIMEOUT}
     * immediately after this connector's socket factory returns, bounding every subsequent
     * TLS-handshake and authentication-phase read.
     */
    private static void boundTimeoutsToRemainingDeadline(Properties properties, Deadline deadline, Clock clock) {
        long remainingMillis = Math.max(1L, deadline.remaining(clock).toMillis());
        tightenToAtMost(properties, MysqlConnectorProperty.CONNECT_TIMEOUT.key(), remainingMillis);
        tightenToAtMost(properties, MysqlConnectorProperty.SOCKET_TIMEOUT.key(), remainingMillis);
    }

    /** Only ever narrows an already-present milliseconds-valued property, never widens it. */
    private static void tightenToAtMost(Properties properties, String key, long remainingMillis) {
        long configured = Long.parseLong(properties.getProperty(key));
        properties.setProperty(key, String.valueOf(Math.min(configured, remainingMillis)));
    }

    /**
     * {@code "FAMILY:hex,FAMILY:hex,..."}, sorted deterministically so a retry across addresses
     * never depends on {@link java.util.Set} iteration order.
     */
    private static String encodePinnedAddresses(ValidatedDestination destination) {
        HexFormat hex = HexFormat.of();
        return destination.vettedAddresses().stream()
                .sorted(Comparator.comparing(VettedAddress::family)
                        .thenComparing(address -> new BigInteger(1, address.addressBytes())))
                .map(address -> address.family().name() + ':' + hex.formatHex(address.addressBytes()))
                .collect(Collectors.joining(","));
    }

    /** Inverse of {@link #encodePinnedAddresses}; used by {@link RivoQueryPinnedSocketFactory}. */
    static List<InetAddress> decodePinnedAddresses(String encoded) {
        HexFormat hex = HexFormat.of();
        List<InetAddress> addresses = new ArrayList<>();
        for (String token : encoded.split(",")) {
            int separator = token.indexOf(':');
            if (separator < 0) {
                throw new IllegalStateException("malformed pinned address token");
            }
            AddressFamily.valueOf(token.substring(0, separator)); // validate; family itself is implied by byte length
            byte[] bytes = hex.parseHex(token.substring(separator + 1));
            try {
                // getByAddress(byte[]) performs no forward or reverse name-service lookup (JDK contract).
                addresses.add(InetAddress.getByAddress(bytes));
            } catch (UnknownHostException impossible) {
                throw new IllegalStateException("pinned address had an invalid byte length", impossible);
            }
        }
        return List.copyOf(addresses);
    }

    /**
     * Materializes a throwaway PKCS12 truststore file containing exactly {@code certificate}, with a
     * fresh random password never reused across connector instances. The file is created with
     * owner-only permissions where supported and marked {@code deleteOnExit} - it exists only for the
     * lifetime of a test JVM, never in production (see this class's Javadoc: production wiring uses
     * {@link #MysqlPhysicalJdbcConnector(Clock)}, which never calls this method).
     */
    private static TestTrustStore writeTestTrustStore(X509Certificate certificate) {
        try {
            char[] password = randomPassword();
            KeyStore trustStore = KeyStore.getInstance(TEST_TRUST_STORE_TYPE);
            trustStore.load(null, null);
            trustStore.setCertificateEntry(TEST_TRUST_STORE_ALIAS, certificate);
            Path file = Files.createTempFile("rivoquery-mysql-test-truststore", ".p12");
            file.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(file)) {
                trustStore.store(out, password);
            }
            return new TestTrustStore(file.toUri().toString(), new String(password));
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("failed to materialize the test TLS trust anchor", e);
        }
    }

    private static char[] randomPassword() {
        byte[] randomBytes = new byte[24];
        new SecureRandom().nextBytes(randomBytes);
        return Base64.getEncoder().encodeToString(randomBytes).toCharArray();
    }

    private record TestTrustStore(String fileUrl, String password) {}

    /**
     * Scans {@code borrowedSecretMaterial} directly for the {@code '\0'} separator and builds the
     * username/password {@link String}s straight from byte-array slices - never via an intermediate
     * combined-credential {@code String}.
     */
    private static void applyCredential(Properties properties, byte[] borrowedSecretMaterial) {
        int separatorIndex = indexOfNulSeparator(borrowedSecretMaterial);
        if (separatorIndex < 0) {
            throw new IllegalArgumentException("borrowed secret material must encode username and password");
        }
        String username = new String(borrowedSecretMaterial, 0, separatorIndex, StandardCharsets.UTF_8);
        String password = new String(
                borrowedSecretMaterial,
                separatorIndex + 1,
                borrowedSecretMaterial.length - separatorIndex - 1,
                StandardCharsets.UTF_8);
        properties.setProperty(USER_PROPERTY, username);
        properties.setProperty(PASSWORD_PROPERTY, password);
    }

    private static int indexOfNulSeparator(byte[] borrowedSecretMaterial) {
        for (int i = 0; i < borrowedSecretMaterial.length; i++) {
            if (borrowedSecretMaterial[i] == CREDENTIAL_SEPARATOR) {
                return i;
            }
        }
        return -1;
    }

    private SQLException translate(SQLException failure, Deadline deadline) {
        if (failure instanceof SQLTimeoutException || deadline.isExpired(clock) || hasTimeoutCause(failure)) {
            return redacted(new SQLTimeoutException("connection attempt exceeded its deadline"), failure);
        }
        return redacted(safeFailure("connection attempt failed"), failure);
    }

    private static boolean hasTimeoutCause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static SQLException safeFailure(String message) {
        return new SQLException(message, GENERIC_CONNECTION_FAILURE_SQLSTATE);
    }

    /**
     * Same redaction contract as {@link #redacted(SQLException, SQLException)}, for a {@link
     * RuntimeException} raised by this connector's own property-building code (never by the JDBC
     * driver) - preserves only the failure's type and stack trace, never its message.
     */
    private static SQLException safeFailureFrom(String message, RuntimeException cause) {
        SQLException safe = safeFailure(message);
        var redactedCause = new SQLException(cause.getClass().getSimpleName(), GENERIC_CONNECTION_FAILURE_SQLSTATE);
        redactedCause.setStackTrace(cause.getStackTrace());
        safe.initCause(redactedCause);
        return safe;
    }

    /**
     * Preserves only the failing exception's type, SQLState, and stack trace as the cause - never
     * its own message, which for Connector/J routinely embeds the hostname, IP, or server-supplied
     * text. Mirrors this codebase's established redaction pattern ({@code
     * SecretStorageUnavailableException.redacting}, {@code PostgresqlPhysicalJdbcConnector#redacted}).
     */
    private static <T extends SQLException> T redacted(T safe, SQLException original) {
        var redactedCause = new SQLException(original.getClass().getSimpleName(), original.getSQLState());
        redactedCause.setStackTrace(original.getStackTrace());
        safe.initCause(redactedCause);
        return safe;
    }
}
