package com.knowimran.rivoquery.platform.connector.postgresql;

import com.knowimran.rivoquery.connection.DatabaseKind;
import com.knowimran.rivoquery.connection.application.Deadline;
import com.knowimran.rivoquery.connection.application.destination.AddressFamily;
import com.knowimran.rivoquery.connection.application.destination.DestinationCapabilityAuthority.ValidatedDestination;
import com.knowimran.rivoquery.connection.application.destination.VettedAddress;
import com.knowimran.rivoquery.platform.connector.JdbcConnectionTarget;
import com.knowimran.rivoquery.platform.connector.PhysicalJdbcConnector;
import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorProperty;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
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
 * The PostgreSQL {@link PhysicalJdbcConnector}: dials {@code target} through pgjdbc (42.7.11,
 * pinned transitively via the Spring Boot 3.5.16 parent BOM - see {@code apps/api/pom.xml}) using
 * {@link RivoQueryPinnedSocketFactory} so the physical TCP socket only ever reaches an address in
 * {@code destination.vettedAddresses()}, while the JDBC URL/TLS host stays {@code
 * destination.originalTlsHostname()} for full chain and hostname verification.
 *
 * <p><b>Why the pinned-socket-factory approach is correct for this driver version.</b> Disassembly
 * of {@code org.postgresql:postgresql:42.7.11}'s {@code PGStream.createSocket(int)} shows it calls
 * {@code socketFactory.createSocket()} (the zero-argument {@link javax.net.SocketFactory} method)
 * and only resolves/connects the JDBC URL host itself when the returned socket is <em>not already
 * connected</em>. {@code MakeSSL.convert}/{@code verifyPeerName} then wrap that socket in TLS and
 * verify the certificate against {@code hostSpec.getHost()} (the JDBC URL host) via {@code
 * PGjdbcHostnameVerifier} - a completely separate step that never looks at the socket's actual
 * remote address. Returning an already-connected socket, dialed only to a vetted IP, from {@link
 * RivoQueryPinnedSocketFactory#createSocket()} therefore (a) makes pgjdbc skip its own hostname
 * resolution entirely for this connection and (b) leaves full chain/hostname verification against
 * the original hostname completely intact and unmodified. This is a property of pgjdbc's own
 * {@code PGStream}/{@code MakeSSL} implementation, not an assumption; {@code
 * PostgresqlPhysicalJdbcConnectorIT} proves it end to end against a real PostgreSQL server, and
 * {@code RivoQueryPinnedSocketFactoryTest} pins the fail-closed behavior (every other {@code
 * SocketFactory} overload refuses to run) that would catch a future driver upgrade silently
 * changing which overload {@code PGStream} calls.
 *
 * <p>{@code borrowedSecretMaterial} is UTF-8 bytes of {@code username + '\0' + password}: the
 * connection domain (as of this task) has no persisted username field anywhere (see P2-100's own
 * documented deferral), so this connector - the first and only production code that actually opens
 * a socket to a target database - defines the minimal self-contained encoding it needs. This is a
 * narrowly scoped, explicit decision for this task, not a completed credential design; a future
 * task that persists a real endpoint/credential shape should revisit it.
 *
 * <p><b>Every internal {@code Properties} value staged here is a {@code String}.</b> An earlier
 * revision of this class stored the pinned address list, deadline, and trust anchor as raw Java
 * objects directly in the {@link Properties} instance (legal, since {@link Properties} is a plain
 * {@code Hashtable<Object,Object>}). That broke empirically: pgjdbc's own connect pipeline
 * (observed against 42.7.11) rebuilds/copies the properties it was given before {@code
 * SocketFactoryFactory} ever sees them, and that rebuild silently drops any entry whose value is
 * not a {@code String} (consistent with {@link Properties#stringPropertyNames()}'s documented
 * String-only enumeration). Every value below is therefore string-encoded and decoded back by
 * {@link RivoQueryPinnedSocketFactory}/{@link TrustAnchorSslSocketFactory}.
 *
 * <p><b>Production TLS trust.</b> When constructed with {@link #PostgresqlPhysicalJdbcConnector(Clock)}
 * (no test trust anchor), {@code sslfactory} is set to pgjdbc's own {@code
 * org.postgresql.ssl.DefaultJavaSSLFactory}, which performs full PKIX chain validation against the
 * JVM's own default trust store (cacerts) - never pgjdbc's {@code LibPQFactory} default (which reads
 * {@code sslrootcert}, defaulting to {@code ${user.home}/.postgresql/root.crt}, a file that does not
 * exist in any real deployment and would make every production connection fail closed). Hostname
 * verification ({@code PGjdbcHostnameVerifier}, driven by {@code sslmode=verify-full}) is a
 * completely separate step that runs after the TLS handshake regardless of which {@code
 * sslfactory} produced the socket, confirmed by reading {@code org.postgresql.ssl.MakeSSL} in the
 * pinned 42.7.11 sources. There is currently no operator-configurable custom/private CA trust
 * mechanism for self-hosted target databases whose certificate does not chain to a JVM-trusted
 * public root; nothing in the PRD, ADR-0013, or {@code docs/OPERATIONS.md} documents that as a
 * requirement today, so it is a known, deliberate residual gap rather than an oversight - a future
 * task should add a real operator-configured trust-anchor property (not a client-reachable one) if
 * that need materializes.
 *
 * <p><b>Residual credential-copy limitation.</b> {@link #applyCredential} scans {@code
 * borrowedSecretMaterial} directly for the {@code '\0'} separator and builds the username/password
 * {@link String}s straight from byte-array slices, without ever materializing the full combined
 * credential as one {@code String}. Two final {@code String} copies (username, password) are still
 * unavoidable: pgjdbc's {@code PGProperty.USER}/{@code PGProperty.PASSWORD} only accept {@code
 * String}, and Java {@code String}s cannot be zeroed after use, unlike {@code
 * DestructibleSecretMaterial}'s byte array. This is a known, bounded limitation of bridging into a
 * String-only third-party API, not an oversight.
 */
public final class PostgresqlPhysicalJdbcConnector implements PhysicalJdbcConnector {

    /** Internal-only pgjdbc {@code Properties} key; never derived from client input. */
    static final String PINNED_ADDRESSES_PROPERTY = "rivoquery.internal.socketFactory.pinnedAddresses";

    static final String PINNED_PORT_PROPERTY = "rivoquery.internal.socketFactory.pinnedPort";
    static final String DEADLINE_PROPERTY = "rivoquery.internal.socketFactory.deadline";
    static final String TRUST_ANCHOR_PROPERTY = "rivoquery.internal.sslFactory.trustAnchor";

    private static final String SOCKET_FACTORY_PROPERTY = "socketFactory";
    private static final String SSL_FACTORY_PROPERTY = "sslfactory";
    private static final String USER_PROPERTY = "user";
    private static final String PASSWORD_PROPERTY = "password";
    private static final char CREDENTIAL_SEPARATOR = '\0';
    private static final String GENERIC_CONNECTION_FAILURE_SQLSTATE = "08006";

    /**
     * pgjdbc's own JVM-cacerts-backed {@code SSLSocketFactory} (see {@code
     * org.postgresql.ssl.DefaultJavaSSLFactory}, which simply calls {@code
     * SSLSocketFactory.getDefault()}) - the production {@code sslfactory}. Never pgjdbc's {@code
     * sslfactory} default (unset), which resolves to {@code LibPQFactory} and requires a {@code
     * sslrootcert} file that does not exist in production.
     */
    private static final String PRODUCTION_SSL_FACTORY_CLASS_NAME = "org.postgresql.ssl.DefaultJavaSSLFactory";

    /**
     * pgjdbc's own default for {@code sslResponseTimeout} ({@code
     * org.postgresql.PGProperty#SSL_RESPONSE_TIMEOUT}) - the ceiling {@link
     * #boundTimeoutsToRemainingDeadline} never exceeds, only tightens.
     */
    private static final long DEFAULT_SSL_RESPONSE_TIMEOUT_MILLIS = 5000L;

    private final Clock clock;
    private final Optional<X509Certificate> testTrustAnchor;

    public PostgresqlPhysicalJdbcConnector(Clock clock) {
        this(clock, Optional.empty());
    }

    /**
     * @param testTrustAnchor when present, TLS chain verification trusts only this CA certificate
     *     via the RivoQuery-owned {@link TrustAnchorSslSocketFactory}, instead of pgjdbc's own
     *     default trust path. This is a deployment-time construction knob for test environments
     *     that must trust a synthetic CA (e.g. Testcontainers-issued certificates) - it is never
     *     derived from request, tenant, or client input. Production wiring MUST use {@link
     *     #PostgresqlPhysicalJdbcConnector(Clock)} (equivalent to {@code Optional.empty()}).
     */
    public PostgresqlPhysicalJdbcConnector(Clock clock, Optional<X509Certificate> testTrustAnchor) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.testTrustAnchor = Objects.requireNonNull(testTrustAnchor, "testTrustAnchor");
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
        if (target.kind() != DatabaseKind.POSTGRESQL) {
            throw safeFailure("connector kind mismatch");
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
        if (testTrustAnchor.isPresent()) {
            properties.setProperty(SSL_FACTORY_PROPERTY, TrustAnchorSslSocketFactory.class.getName());
            properties.setProperty(TRUST_ANCHOR_PROPERTY, encodeCertificate(testTrustAnchor.get()));
        } else {
            properties.setProperty(SSL_FACTORY_PROPERTY, PRODUCTION_SSL_FACTORY_CLASS_NAME);
        }
        applyCredential(properties, borrowedSecretMaterial);
        boundTimeoutsToRemainingDeadline(properties, deadline, clock);
        return properties;
    }

    /**
     * Derives pgjdbc's own connect/TLS-handshake/auth-phase timeout properties from the caller's
     * remaining absolute {@link Deadline} budget at connect-build time, so the full connect+TLS+
     * auth+probe cycle is genuinely bounded by the caller's deadline - not just the initial TCP
     * connect (already bounded by {@link RivoQueryPinnedSocketFactory}'s own per-attempt/absolute
     * deadline logic, independently of these properties).
     *
     * <p>Confirmed by reading the pinned 42.7.11 sources: {@code
     * org.postgresql.core.v3.ConnectionFactoryImpl#tryConnect} applies {@code socketTimeout} as the
     * steady-state socket read timeout for authentication/startup packets once TLS is up; {@code
     * org.postgresql.ssl.MakeSSL#convert} reads {@code connectTimeout} directly to bound the TLS
     * handshake read timeout; {@code enableSSL}/{@code enableGSSEncrypted} additionally cap the
     * plaintext SSL-upgrade-response wait to {@code min(connectTimeout, sslResponseTimeout)}; and
     * {@code org.postgresql.Driver#connect} races the <em>entire</em> connection attempt (on a
     * background thread) against {@code loginTimeout} as a final backstop. Without this, {@code
     * PostgresqlConnectorDialect}'s fixed connectTimeout/socketTimeout defaults would keep bounding
     * TLS/auth phases at their fixed values even when the caller's actual remaining budget is much
     * smaller.
     */
    private static void boundTimeoutsToRemainingDeadline(Properties properties, Deadline deadline, Clock clock) {
        long remainingMillis = Math.max(1L, deadline.remaining(clock).toMillis());
        long remainingSeconds = Math.max(1L, ceilMillisToSeconds(remainingMillis));
        tightenToAtMost(properties, PostgresqlConnectorProperty.CONNECT_TIMEOUT.key(), remainingSeconds);
        tightenToAtMost(properties, PostgresqlConnectorProperty.SOCKET_TIMEOUT.key(), remainingSeconds);
        properties.setProperty(PostgresqlConnectorProperty.LOGIN_TIMEOUT.key(), String.valueOf(remainingSeconds));
        long sslResponseTimeoutMillis = Math.min(DEFAULT_SSL_RESPONSE_TIMEOUT_MILLIS, remainingMillis);
        properties.setProperty(
                PostgresqlConnectorProperty.SSL_RESPONSE_TIMEOUT.key(), String.valueOf(sslResponseTimeoutMillis));
    }

    private static long ceilMillisToSeconds(long millis) {
        return (millis + 999) / 1000;
    }

    /** Only ever narrows an already-present seconds-valued property, never widens it. */
    private static void tightenToAtMost(Properties properties, String key, long remainingSeconds) {
        long configured = Long.parseLong(properties.getProperty(key));
        properties.setProperty(key, String.valueOf(Math.min(configured, remainingSeconds)));
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

    private static String encodeCertificate(X509Certificate certificate) {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("failed to encode the test trust anchor certificate", e);
        }
    }

    /** Inverse of {@link #encodeCertificate}; used by {@link TrustAnchorSslSocketFactory}. */
    static X509Certificate decodeCertificate(String encoded) {
        try {
            byte[] der = Base64.getDecoder().decode(encoded);
            var factory = CertificateFactory.getInstance("X.509");
            return (X509Certificate) factory.generateCertificate(new ByteArrayInputStream(der));
        } catch (CertificateException e) {
            throw new IllegalStateException("failed to decode the test trust anchor certificate", e);
        }
    }

    /**
     * Scans {@code borrowedSecretMaterial} directly for the {@code '\0'} separator and builds the
     * username/password {@link String}s straight from byte-array slices - never via an intermediate
     * combined-credential {@code String} - so at most the two unavoidable final copies (see this
     * class's Javadoc) exist afterward, not three.
     */
    private static void applyCredential(Properties properties, byte[] borrowedSecretMaterial) {
        int separatorIndex = indexOfNulSeparator(borrowedSecretMaterial);
        if (separatorIndex < 0) {
            throw new IllegalArgumentException(
                    "borrowed secret material must encode username and password");
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
     * its own message, which for pgjdbc routinely embeds the hostname, IP, or server-supplied text
     * (see {@code CapabilityReportingDatabaseConnectorTest}'s own
     * {@code otherSqlExceptionMapsToConnectionTestFailedWithoutLeakingDriverDetail} for a concrete
     * example this must never surface). Mirrors this codebase's established redaction pattern
     * ({@code SecretStorageUnavailableException.redacting}).
     */
    private static <T extends SQLException> T redacted(T safe, SQLException original) {
        var redactedCause = new SQLException(original.getClass().getSimpleName(), original.getSQLState());
        redactedCause.setStackTrace(original.getStackTrace());
        safe.initCause(redactedCause);
        return safe;
    }
}
