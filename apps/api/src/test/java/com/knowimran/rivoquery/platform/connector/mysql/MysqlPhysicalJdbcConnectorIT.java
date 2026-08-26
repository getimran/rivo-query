package com.knowimran.rivoquery.platform.connector.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real MySQL Testcontainers proof of {@link MysqlPhysicalJdbcConnector} (P2-120): the physical
 * socket only ever dials an IP in {@code destination.vettedAddresses()} while the logical JDBC/TLS
 * host stays the canonical original hostname, with full chain/hostname verification enforced
 * against that hostname only. Mirrors {@code
 * platform.connector.postgresql.PostgresqlPhysicalJdbcConnectorIT}'s structure and proof strategy.
 */
@Testcontainers
class MysqlPhysicalJdbcConnectorIT {

    private static final String DB_USERNAME = "rivoquery_it";
    private static final String DB_PASSWORD = "rivoquery-it-synthetic-pw";
    private static final String DB_NAME = "probe_db";
    private static final String ROOT_PASSWORD = "rivoquery-it-root-not-used";

    /**
     * Both an RFC 2606 {@code .invalid}-TLD hostname (guaranteed to never resolve anywhere) and a
     * {@link TrappedTldResolverProviderBridge#TRAP_SUFFIX} hostname (recorded and rejected by the
     * shared, already-registered JVM-wide resolver trap if a lookup is ever attempted at all).
     */
    private static final String ORIGINAL_HOSTNAME = "pinned-mysql-db" + TrappedTldResolverProviderBridge.TRAP_SUFFIX;

    private static final String WRONG_CERT_HOSTNAME = "wrong-host.rivoquery-mysql.invalid";

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * The local Docker daemon's own address, resolved to a genuine dotted-decimal IP literal - used
     * only by {@link #ipLiteralOriginalHostnameIsVerifiedAgainstAnIpSubjectAlternativeName}, ADR-0013's
     * "when the configured host is itself an IP literal, verification requires a matching IP subject
     * alternative name" case.
     */
    private static final String IP_LITERAL_HOSTNAME = resolveDockerHostIpLiteral();

    private static final TestCertificateAuthority CA = new TestCertificateAuthority();
    private static final TestCertificateAuthority.IssuedServerCertificate CORRECT_CERT =
            CA.issueServerCertificate(ORIGINAL_HOSTNAME);
    private static final TestCertificateAuthority.IssuedServerCertificate MISMATCHED_CERT =
            CA.issueServerCertificate(WRONG_CERT_HOSTNAME);
    private static final TestCertificateAuthority.IssuedServerCertificate IP_LITERAL_CERT =
            CA.issueIpLiteralServerCertificate(IP_LITERAL_HOSTNAME);

    @Container
    private static final GenericContainer<?> CORRECT_CERT_MYSQL = sslMysql(CORRECT_CERT);

    @Container
    private static final GenericContainer<?> WRONG_CERT_MYSQL = sslMysql(MISMATCHED_CERT);

    @Container
    private static final GenericContainer<?> IP_LITERAL_MYSQL = sslMysql(IP_LITERAL_CERT);

    private final MysqlConnectorDialect dialect = new MysqlConnectorDialect();
    private boolean systemTrustStoreMutated;
    private String systemTrustStorePropertyBackup;
    private String systemTrustStorePasswordPropertyBackup;

    /**
     * Nit fix: only restores {@code javax.net.ssl.trustStore}/{@code -Password} for the two tests
     * that actually call {@link #installSystemTrustStore}, rather than unconditionally clearing them
     * (harmless today since no other test in this class sets them, but a future test that legitimately
     * needed a pre-set system trust store would otherwise have it silently cleared).
     */
    @AfterEach
    void restoreSystemTrustStoreProperties() {
        if (!systemTrustStoreMutated) {
            return;
        }
        restoreProperty("javax.net.ssl.trustStore", systemTrustStorePropertyBackup);
        restoreProperty("javax.net.ssl.trustStorePassword", systemTrustStorePasswordPropertyBackup);
    }

    @Test
    void connectsThroughTheVettedIpWhileVerifyingTlsAgainstTheOriginalHostname() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        // S4: prove the trap is genuinely active before trusting the "zero lookups" proof below - a
        // silently uninstalled trap (e.g. ServiceLoader provider-ordering fallout) would otherwise
        // make that proof pass vacuously with nothing actually checked.
        TrappedTldResolverProviderBridge.assertTrapIsGenuinelyActiveThenReset();
        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        // No exception: TCP dialed the vetted IP directly (never the unresolvable .invalid
        // hostname), and TLS full-chain/hostname verification passed against ORIGINAL_HOSTNAME.
        assertThat(TrappedTldResolverProviderBridge.recordedLookups()).isEmpty();
    }

    @Test
    void certificateValidOnlyForADifferentHostnameFailsFullHostnameVerification() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        // The vetted IP points at WRONG_CERT_MYSQL (serving a certificate valid for
        // WRONG_CERT_HOSTNAME), but the capability's original hostname is still ORIGINAL_HOSTNAME -
        // proving verification is against the logical host, not the socket's physical destination.
        var destination = destinationFor(WRONG_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        assertThatThrownBy(() -> connector.probe(
                        target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);

        // Prove it is specifically a hostname-verification failure by going around the connector's
        // own message redaction and inspecting Connector/J's raw exception directly, using the exact
        // driver properties the connector itself builds. ExportControlled$HostnameChecker#match
        // raises "Server identity verification failed. None of the certificate's DNS or IP Subject
        // Alternative Name entries matched the server hostname/IP '<host>'." (empirically confirmed
        // against a real MySQL 8.4.6 server) - asserting on that message (not just the wrapper type)
        // is what distinguishes this from every other possible connection failure.
        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        assertThatThrownBy(() -> DriverManager.getConnection(target.jdbcUrl(), properties))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertMessageChainContainsIgnoringCase(
                        failure, "identity verification failed", ORIGINAL_HOSTNAME));
    }

    @Test
    void readOnlyModeIsGenuinelyEnforcedByTheMysqlSessionNotJustClaimedByTheDriver() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());
        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));

        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), properties)) {
            connection.setReadOnly(true);
            assertThat(connection.isReadOnly()).isTrue();

            // Direct server-side proof, not a client-side flag: readOnlyPropagatesToServer=true
            // makes ConnectionImpl#setReadOnlyInternal send "SET SESSION TRANSACTION READ ONLY" to
            // the server as part of Connection#setReadOnly (confirmed by decompiling
            // com.mysql.cj.jdbc.ConnectionImpl#setReadOnlyInternal for the pinned 9.7.0 driver) -
            // querying the server's own session variable is a genuine round trip proving the server
            // actually applied it, not merely that the driver remembered a local boolean.
            try (Statement probe = connection.createStatement();
                    var resultSet = probe.executeQuery("SELECT @@SESSION.transaction_read_only")) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getInt(1)).isEqualTo(1);
            }

            // Complementary, defense-in-depth proof: Connector/J's own StatementImpl also refuses a
            // data-modifying statement client-side once Connection#isReadOnly() is true (via
            // QueryInfo#isReadOnlySafeQuery, confirmed by decompiling com.mysql.cj.jdbc.StatementImpl
            // for 9.7.0), raising SQLState "S1009" before the statement ever reaches the server. This
            // is architecturally different from pgjdbc, which has no such client-side interception and
            // relies purely on the server rejecting the write (see
            // PostgresqlPhysicalJdbcConnectorIT#readOnlyModeIsGenuinelyEnforcedByThePostgresSessionNotJustClaimedByTheDriver's
            // SQLState 25006 assertion) - both layers are exercised here, but only the session-variable
            // check above is genuine evidence of server-side state, which is why it is asserted first
            // and independently.
            try (Statement statement = connection.createStatement()) {
                // S7: asserts the specific client-side SQLState ("S1009", confirmed by decompiling
                // com.mysql.cj.jdbc.StatementImpl for 9.7.0 - QueryInfo#isReadOnlySafeQuery raises it
                // before the statement ever reaches the server), not merely "some SQLException" - a
                // write failing for an unrelated reason (duplicate table, permission error) would
                // otherwise pass this assertion without proving read-only enforcement at all.
                assertThatThrownBy(() ->
                                statement.execute("CREATE TABLE " + DB_NAME + ".rivoquery_write_probe (id int)"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> assertThat(((SQLException) failure).getSQLState()).isEqualTo("S1009"));
            }
        }
    }

    @Test
    void anAlreadyExpiredDeadlineFailsClosedWithSqlTimeoutExceptionWithoutDialing() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        assertThatThrownBy(() -> connector.probe(
                        target, destination, credential(), new Deadline(Instant.now(CLOCK).minusSeconds(1))))
                .isInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void anUnreachableVettedAddressFailsSafelyWithoutLeakingDriverInternalsOrThePassword() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        int refusedPort = openThenImmediatelyClosePort();
        var vetted = new VettedAddress(AddressFamily.IPV4, InetAddress.getByName("127.0.0.1").getAddress());
        var assessment = new DestinationAssessment(
                ORIGINAL_HOSTNAME.toLowerCase(Locale.ROOT),
                ORIGINAL_HOSTNAME,
                refusedPort,
                Set.of(vetted),
                "fingerprint-refused",
                "policy-it",
                Instant.now(CLOCK),
                Instant.now(CLOCK).plusSeconds(60));
        var destination = mintDestination(assessment, refusedPort);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, refusedPort);
        var deadline = new Deadline(Instant.now(CLOCK).plusSeconds(30));

        SQLException failure = null;
        try {
            connector.probe(target, destination, credential(), deadline);
            fail("expected a connection failure");
        } catch (SQLException e) {
            failure = e;
        }

        assertNoLeakage(failure, DB_PASSWORD, ORIGINAL_HOSTNAME, "127.0.0.1", String.valueOf(refusedPort));
    }

    @Test
    void ipLiteralOriginalHostnameIsVerifiedAgainstAnIpSubjectAlternativeName() throws Exception {
        // ADR-0013: "When the configured host is itself an IP literal, verification requires a
        // matching IP subject alternative name." IP_LITERAL_MYSQL serves a certificate whose only
        // SAN is the iPAddress SAN for IP_LITERAL_HOSTNAME (never a DNS SAN).
        var connector = new MysqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(IP_LITERAL_MYSQL, IP_LITERAL_HOSTNAME);
        var target = dialect.buildProbeTarget(IP_LITERAL_HOSTNAME, destination.port());

        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        // No exception: TLS full-chain/IP-SAN verification passed against IP_LITERAL_HOSTNAME.
    }

    @Test
    void productionPathConnectsWhenTheTargetsCertificateChainsToATrustedCa() throws Exception {
        // No testTrustAnchor: exercises the real production trust wiring - Connector/J's own
        // ExportControlled#performTlsHandshake with trustCertificateKeyStoreUrl left unset and
        // fallbackToSystemTrustStore=true (its documented default), which reads the
        // javax.net.ssl.trustStore/-Password system properties when set. Unlike pgjdbc's
        // DefaultJavaSSLFactory, Connector/J's trust building does NOT consult
        // SSLContext.setDefault(...) at all (confirmed by reading ExportControlled$SslContextBuilder
        // - it builds its own SSLContext from a TrustManagerFactory it constructs itself), so this
        // test installs the CA via the system-property mechanism getTrustStoreConfigurations itself
        // reads, not via SSLContext.setDefault.
        var connector = new MysqlPhysicalJdbcConnector(CLOCK); // production constructor
        var destination = destinationFor(CORRECT_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        installSystemTrustStore(CA.caCertificate());

        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        // No exception: the production trust path validated the full chain against the installed
        // system trust store, and hostname verification passed against ORIGINAL_HOSTNAME.
    }

    @Test
    void productionPathRejectsWhenTheTargetsCertificateDoesNotChainToATrustedCa() throws Exception {
        var connector = new MysqlPhysicalJdbcConnector(CLOCK); // production constructor
        var destination = destinationFor(CORRECT_CERT_MYSQL, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        // S6: this clears javax.net.ssl.trustStore/-Password so Connector/J's own
        // fallbackToSystemTrustStore=true default (its documented, unconditional default - see
        // productionPathConnectsWhenTheTargetsCertificateChainsToATrustedCa) falls back to the real
        // JVM cacerts trust store, which does not contain CA.caCertificate() (the synthetic CA that
        // actually signed the target's certificate) - proving chain verification is genuinely
        // enforced by the production trust path, not accidentally disabled. This is NOT "trusts
        // nothing at all": ExportControlled's trust building has no empty-trust-store mode reachable
        // this way; the rejection comes from the real cacerts set simply not containing this
        // synthetic CA, exactly like connecting to any server with an untrusted self-signed chain.
        installSystemTrustStore(null);

        assertThatThrownBy(() -> connector.probe(
                        target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);

        // Prove it is specifically a trust-chain rejection (not e.g. an auth failure, a not-ready
        // container, or connection-refused - all of which would also satisfy the assertion above) by
        // going around the connector's own message redaction and inspecting Connector/J's raw
        // exception directly, mirroring certificateValidOnlyForADifferentHostnameFailsFullHostnameVerification.
        // "Path does not chain with any of the trust anchors" (java.security.cert.CertPathValidatorException,
        // surfaced by the JDK's own PKIX CertPathValidator through JSSE's TLS handshake) is the actual
        // rejection message empirically observed against a real MySQL 8.4.6 server for the pinned
        // 9.7.0 driver on this JDK.
        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        assertThatThrownBy(() -> DriverManager.getConnection(target.jdbcUrl(), properties))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertMessageChainContainsIgnoringCase(
                        failure, "does not chain with any of the trust anchors"));
    }

    @Test
    void thePinnedDriverIsTheExpectedConnectorJClass() throws Exception {
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, 3306);
        assertThat(target.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        // apps/api/pom.xml pins com.mysql:mysql-connector-j transitively via the Spring Boot 3.5.16
        // parent BOM; resolved to 9.7.0 in this build (see MysqlPhysicalJdbcConnector's class
        // Javadoc for the exact decompiled behavior this mechanism depends on).
        assertThat(Class.forName(target.driverClassName())).isNotNull();
        var driver = (java.sql.Driver) Class.forName(target.driverClassName()).getDeclaredConstructor().newInstance();
        assertThat(driver.getMajorVersion()).isEqualTo(9);
        assertThat(driver.getMinorVersion()).isEqualTo(7);
    }

    private static byte[] credential() {
        return (DB_USERNAME + '\0' + DB_PASSWORD).getBytes(StandardCharsets.UTF_8);
    }

    private static void assertNoLeakage(Throwable failure, String... sensitiveValues) {
        assertThat(failure).isNotNull();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = String.valueOf(cause.getMessage());
            String toString = cause.toString();
            for (String sensitive : sensitiveValues) {
                assertThat(message).doesNotContain(sensitive);
                assertThat(toString).doesNotContain(sensitive);
            }
        }
    }

    private static void assertMessageChainContainsIgnoringCase(Throwable failure, String... requiredFragments) {
        assertThat(failure).isNotNull();
        StringBuilder combined = new StringBuilder();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            combined.append(String.valueOf(cause.getMessage())).append('\n');
        }
        String chain = combined.toString();
        for (String fragment : requiredFragments) {
            assertThat(chain).containsIgnoringCase(fragment);
        }
    }

    /**
     * Installs (or, when {@code trustedCa} is {@code null}, removes) the {@code
     * javax.net.ssl.trustStore}/{@code javax.net.ssl.trustStorePassword} system properties that
     * Connector/J's own {@code ExportControlled#getTrustStoreConfigurations} reads when {@code
     * fallbackToSystemTrustStore=true} (the default) and no explicit {@code
     * trustCertificateKeyStoreUrl} is configured - the standard JSSE system-property mechanism, not
     * an RivoQuery-specific extension.
     */
    private void installSystemTrustStore(X509Certificate trustedCa) throws Exception {
        systemTrustStoreMutated = true;
        systemTrustStorePropertyBackup = System.getProperty("javax.net.ssl.trustStore");
        systemTrustStorePasswordPropertyBackup = System.getProperty("javax.net.ssl.trustStorePassword");
        if (trustedCa == null) {
            System.clearProperty("javax.net.ssl.trustStore");
            System.clearProperty("javax.net.ssl.trustStorePassword");
            return;
        }
        char[] password = "rivoquery-it-truststore".toCharArray();
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("rivoquery-it-ca", trustedCa);
        Path file = Files.createTempFile("rivoquery-mysql-it-system-truststore", ".p12");
        file.toFile().deleteOnExit();
        try (var out = Files.newOutputStream(file)) {
            trustStore.store(out, password);
        }
        System.setProperty("javax.net.ssl.trustStore", file.toString());
        System.setProperty("javax.net.ssl.trustStorePassword", new String(password));
    }

    private static void restoreProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    private static String resolveDockerHostIpLiteral() {
        try {
            return InetAddress.getByName(DockerClientFactory.instance().dockerHostIpAddress()).getHostAddress();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                    "failed to resolve the local Docker host to an IP literal for the IP-literal TLS test", e);
        }
    }

    private static int openThenImmediatelyClosePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static ValidatedDestination destinationFor(GenericContainer<?> container, String originalHostname)
            throws Exception {
        InetAddress resolved = InetAddress.getByName(container.getHost()); // test setup only
        var vetted = new VettedAddress(
                resolved.getAddress().length == 4 ? AddressFamily.IPV4 : AddressFamily.IPV6, resolved.getAddress());
        int mappedPort = container.getMappedPort(3306);
        var assessment = new DestinationAssessment(
                originalHostname.toLowerCase(Locale.ROOT),
                originalHostname,
                mappedPort,
                Set.of(vetted),
                "fingerprint-it",
                "policy-it",
                Instant.now(CLOCK),
                Instant.now(CLOCK).plusSeconds(60));
        return mintDestination(assessment, mappedPort);
    }

    private static ValidatedDestination mintDestination(DestinationAssessment assessment, int port) {
        var authority = new DestinationCapabilityAuthority(request -> assessment, () -> "nonce-it", CLOCK);
        var request = new DestinationRequest(
                WORKSPACE,
                CONNECTION,
                new ConnectionVersion(1),
                DatabaseKind.MYSQL,
                ConnectionPurpose.CONNECTION_TEST,
                new StructuredEndpoint(assessment.originalTlsHostname(), port, DB_NAME),
                new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        return authority.validate(request);
    }

    private static GenericContainer<?> sslMysql(TestCertificateAuthority.IssuedServerCertificate cert) {
        String dockerfile =
                """
                FROM mysql:8.4.6@sha256:869218921e61d6c3c89820955d63cca42971f0e3e6c1e2792247bbd944ebc6e9
                COPY ca.crt /etc/mysql-tls/ca.crt
                COPY server.crt /etc/mysql-tls/server.crt
                COPY server.key /etc/mysql-tls/server.key
                RUN chown mysql:mysql /etc/mysql-tls/ca.crt /etc/mysql-tls/server.crt /etc/mysql-tls/server.key \\
                    && chmod 644 /etc/mysql-tls/ca.crt /etc/mysql-tls/server.crt \\
                    && chmod 600 /etc/mysql-tls/server.key
                """;
        var image = new ImageFromDockerfile()
                .withFileFromString("Dockerfile", dockerfile)
                .withFileFromString("ca.crt", CA.caCertificatePem())
                .withFileFromString("server.crt", cert.certificatePem())
                .withFileFromString("server.key", cert.privateKeyPem());
        return new GenericContainer<>(image)
                .withEnv("MYSQL_ROOT_PASSWORD", ROOT_PASSWORD)
                .withEnv("MYSQL_DATABASE", DB_NAME)
                .withEnv("MYSQL_USER", DB_USERNAME)
                .withEnv("MYSQL_PASSWORD", DB_PASSWORD)
                .withExposedPorts(3306)
                .withCommand(
                        "mysqld",
                        "--ssl-ca=/etc/mysql-tls/ca.crt",
                        "--ssl-cert=/etc/mysql-tls/server.crt",
                        "--ssl-key=/etc/mysql-tls/server.key")
                // S3: MySQL emits an X Plugin bootstrap "ready for connections... port: 33060" line
                // BEFORE the real server's "ready for connections... port: 3306" line; the previous
                // regex's ".*port: 3306.*" also matched the "3306" substring inside "33060" (a latent
                // false-positive risk, not actively flaky, since the X Plugin line arrives
                // microseconds earlier). "port: 3306[^0-9]" cannot match "33060" (its next character
                // is the digit '0'), only the real server's line, whose "port: 3306" is followed by a
                // space.
                .waitingFor(Wait.forLogMessage(".*ready for connections.*port: 3306[^0-9].*\\n", 1))
                .withStartupTimeout(Duration.ofSeconds(120));
    }
}
