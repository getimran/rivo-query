package com.knowimran.rivoquery.platform.connector.postgresql;

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
import com.knowimran.rivoquery.platform.connector.PostgresqlConnectorDialect;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real PostgreSQL Testcontainers proof of {@link PostgresqlPhysicalJdbcConnector} (P2-110): the
 * physical socket only ever dials an IP in {@code destination.vettedAddresses()} while the
 * logical JDBC/TLS host stays the canonical original hostname, with full chain/hostname
 * verification enforced against that hostname only.
 *
 * <p>{@code ORIGINAL_HOSTNAME} uses the {@code .invalid} TLD, which RFC 2606 reserves as
 * guaranteed to never resolve anywhere. A successful connection using that hostname is therefore
 * direct, environment-independent evidence that ordinary DNS resolution of the original hostname
 * never happened anywhere in the pgjdbc + RivoQuery stack for this connection - if it had been
 * attempted, the connection would fail with {@code UnknownHostException}, not succeed.
 */
@Testcontainers
class PostgresqlPhysicalJdbcConnectorIT {

    private static final String DB_USERNAME = "rivoquery_it";
    private static final String DB_PASSWORD = "rivoquery-it-synthetic-pw";

    /**
     * Deliberately both an RFC 2606 {@code .invalid}-TLD hostname (guaranteed to never resolve
     * anywhere) <em>and</em> a {@link TrappedTldResolverProvider#TRAP_SUFFIX} hostname (recorded
     * and rejected by a JVM-wide resolver trap if a lookup is ever attempted at all) - so every
     * test using this hostname gets both the indirect ("would fail if attempted") and the direct
     * ("was never attempted") proof for free, not just the tests that explicitly assert on {@link
     * TrappedTldResolverProvider#recordedLookups()}.
     */
    private static final String ORIGINAL_HOSTNAME = "pinned-db" + TrappedTldResolverProvider.TRAP_SUFFIX;

    private static final String WRONG_CERT_HOSTNAME = "wrong-host.rivoquery.invalid";

    private static final WorkspaceId WORKSPACE = new WorkspaceId("wsp_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final ConnectionId CONNECTION = new ConnectionId("con_01J6MZ4H9ST5N7YQ2C8W3V0KFX");
    private static final Clock CLOCK = Clock.systemUTC();

    /**
     * The local Docker daemon's own address, resolved to a genuine dotted-decimal IP literal (never
     * left as a possibly-symbolic {@code getHost()} string such as {@code "localhost"}) - used only
     * by {@link #ipLiteralOriginalHostnameIsVerifiedAgainstAnIpSubjectAlternativeName}, ADR-0013's
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
    private static final GenericContainer<?> CORRECT_CERT_POSTGRES = sslPostgres(CORRECT_CERT);

    @Container
    private static final GenericContainer<?> WRONG_CERT_POSTGRES = sslPostgres(MISMATCHED_CERT);

    @Container
    private static final GenericContainer<?> IP_LITERAL_POSTGRES = sslPostgres(IP_LITERAL_CERT);

    private final PostgresqlConnectorDialect dialect = new PostgresqlConnectorDialect();

    @Test
    void connectsThroughTheVettedIpWhileVerifyingTlsAgainstTheOriginalHostname() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        TrappedTldResolverProvider.reset();
        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        // No exception: TCP dialed the vetted IP directly (never the unresolvable .invalid
        // hostname), and TLS full-chain/hostname verification passed against ORIGINAL_HOSTNAME.
        //
        // Definitive proof, not just indirect evidence: ORIGINAL_HOSTNAME ends in
        // TrappedTldResolverProvider.TRAP_SUFFIX, so if pgjdbc or the JDK had attempted an ordinary
        // lookup of it anywhere during this full connect+TLS+auth+probe cycle, the JVM-wide resolver
        // trap would have recorded it (and the lookup itself would have failed with
        // UnknownHostException, which - since the probe above succeeded - it demonstrably did not).
        assertThat(TrappedTldResolverProvider.recordedLookups()).isEmpty();
    }

    @Test
    void certificateValidOnlyForADifferentHostnameFailsFullHostnameVerification() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        // The vetted IP points at the WRONG_CERT_POSTGRES container (serving a certificate valid
        // for WRONG_CERT_HOSTNAME), but the capability's original hostname is still
        // ORIGINAL_HOSTNAME - proving verification is against the logical host, not the socket's
        // physical destination or the server's own idea of who it is.
        var destination = destinationFor(WRONG_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        // The connector's own SPI contract: some non-timeout SQLException. On its own this would
        // pass identically for a refused connection, wrong database, failed auth, or a container
        // that never started - it does not by itself prove hostname verification is what failed.
        assertThatThrownBy(() ->
                        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30))))
                .isInstanceOf(SQLException.class)
                .isNotInstanceOf(SQLTimeoutException.class);

        // Prove it is specifically a hostname-verification failure by going around the connector's
        // own message redaction and inspecting pgjdbc's raw exception directly, using the exact
        // driver properties the connector itself builds. pgjdbc's MakeSSL#verifyPeerName raises
        // "The hostname {host} could not be verified by hostnameverifier {verifier}." - asserting on
        // that message (not just the wrapper type) is what distinguishes this from every other
        // possible connection failure.
        Properties properties = connector.buildDriverProperties(
                target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        assertThatThrownBy(() -> DriverManager.getConnection(target.jdbcUrl(), properties))
                .isInstanceOf(SQLException.class)
                .satisfies(failure -> assertMessageChainContainsIgnoringCase(
                        failure, "hostname", ORIGINAL_HOSTNAME, "verif"));
    }

    @Test
    void readOnlyModeIsGenuinelyEnforcedByThePostgresSessionNotJustClaimedByTheDriver() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());
        Properties properties =
                connector.buildDriverProperties(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));

        try (Connection connection = DriverManager.getConnection(target.jdbcUrl(), properties)) {
            // First prove the readOnly=true/readOnlyMode=always connection properties alone are
            // already sufficient - before this test's own explicit setReadOnly(true) call, which
            // production's probe() also makes as defense in depth but which could otherwise mask
            // whether the properties alone actually did the job.
            assertThat(connection.isReadOnly())
                    .as("readOnly=true/readOnlyMode=always properties alone must already enforce "
                            + "read-only, before any explicit Connection#setReadOnly call")
                    .isTrue();

            connection.setReadOnly(true);
            assertThat(connection.isReadOnly()).isTrue();

            try (Statement statement = connection.createStatement()) {
                assertThatThrownBy(() -> statement.execute("CREATE TABLE rivoquery_write_probe (id int)"))
                        .isInstanceOf(SQLException.class)
                        .satisfies(failure -> {
                            var sqlException = (SQLException) failure;
                            assertThat(sqlException.getSQLState()).isEqualTo("25006"); // read_only_sql_transaction
                        });
            }
        }
    }

    @Test
    void anAlreadyExpiredDeadlineFailsClosedWithSqlTimeoutExceptionWithoutDialing() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(CORRECT_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        assertThatThrownBy(() ->
                        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).minusSeconds(1))))
                .isInstanceOf(SQLTimeoutException.class);
    }

    @Test
    void anUnreachableVettedAddressFailsSafelyWithoutLeakingDriverInternalsOrThePassword() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
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
        // matching IP subject alternative name." IP_LITERAL_POSTGRES serves a certificate whose
        // only SAN is the iPAddress SAN for IP_LITERAL_HOSTNAME (never a DNS SAN) - proving the full
        // pin+verify path works end to end when the original host is an IP literal, not a hostname.
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK, Optional.of(CA.caCertificate()));
        var destination = destinationFor(IP_LITERAL_POSTGRES, IP_LITERAL_HOSTNAME);
        var target = dialect.buildProbeTarget(IP_LITERAL_HOSTNAME, destination.port());

        connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        // No exception: TLS full-chain/IP-SAN verification passed against IP_LITERAL_HOSTNAME.
    }

    @Test
    void productionPathConnectsWhenTheTargetsCertificateChainsToAJvmTrustedCa() throws Exception {
        // No testTrustAnchor: exercises the real production sslfactory wiring
        // (org.postgresql.ssl.DefaultJavaSSLFactory, backed by SSLContext.getDefault()) rather than
        // TrustAnchorSslSocketFactory. SSLContext.setDefault(...) is the standard, fully supported
        // JSSE mechanism an operator uses to extend/replace the JVM's trusted CA set (equivalent in
        // effect to importing a CA into cacerts) - here scoped to this test and always restored.
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK); // production constructor
        var destination = destinationFor(CORRECT_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        SSLContext original = SSLContext.getDefault();
        try {
            SSLContext.setDefault(trustingOnly(CA.caCertificate()));

            connector.probe(target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30)));
            // No exception: the production sslfactory validated the full chain against the JVM's
            // (just-installed) default trust store, and hostname verification passed against
            // ORIGINAL_HOSTNAME.
        } finally {
            SSLContext.setDefault(original);
        }
    }

    @Test
    void productionPathRejectsWhenTheTargetsCertificateDoesNotChainToAJvmTrustedCa() throws Exception {
        var connector = new PostgresqlPhysicalJdbcConnector(CLOCK); // production constructor
        var destination = destinationFor(CORRECT_CERT_POSTGRES, ORIGINAL_HOSTNAME);
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, destination.port());

        SSLContext original = SSLContext.getDefault();
        try {
            // Trusts nothing at all - not even CA.caCertificate(), which actually signed the
            // target's certificate - proving chain verification is genuinely enforced by the
            // production sslfactory, not accidentally disabled or bypassed.
            SSLContext.setDefault(trustingNothing());

            assertThatThrownBy(() -> connector.probe(
                            target, destination, credential(), new Deadline(Instant.now(CLOCK).plusSeconds(30))))
                    .isInstanceOf(SQLException.class)
                    .isNotInstanceOf(SQLTimeoutException.class);
        } finally {
            SSLContext.setDefault(original);
        }
    }

    @Test
    void thePinnedDriverIsTheExpectedPgjdbcClass() throws Exception {
        var target = dialect.buildProbeTarget(ORIGINAL_HOSTNAME, 5432);
        assertThat(target.driverClassName()).isEqualTo("org.postgresql.Driver");
        // apps/api/pom.xml pins org.postgresql:postgresql transitively via the Spring Boot 3.5.16
        // parent BOM; resolved to 42.7.11 in this build (see PostgresqlPhysicalJdbcConnector's
        // class Javadoc for the exact disassembled behavior this mechanism depends on).
        assertThat(Class.forName(target.driverClassName())).isNotNull();
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

    /** Asserts every fragment appears (case-insensitively) somewhere in {@code failure}'s cause chain. */
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
     * A minimal {@link SSLContext} trusting only {@code certificate} - used to prove the production
     * {@code sslfactory} (backed by {@code SSLContext.getDefault()}) genuinely performs chain
     * validation against whatever the JVM's default trust store contains, using the same
     * KeyStore/TrustManagerFactory technique {@link TrustAnchorSslSocketFactory} itself uses.
     */
    private static SSLContext trustingOnly(X509Certificate certificate) throws GeneralSecurityException, java.io.IOException {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("rivoquery-test-ca", certificate);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    /** An {@link SSLContext} trusting no certificate at all - every chain validation must fail. */
    private static SSLContext trustingNothing() throws GeneralSecurityException, java.io.IOException {
        KeyStore emptyTrustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        emptyTrustStore.load(null, null);
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(emptyTrustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());
        return context;
    }

    /**
     * Resolves the local Docker daemon's address to a genuine dotted-decimal IP literal, without
     * requiring any container to already be running.
     */
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
                resolved.getAddress().length == 4 ? AddressFamily.IPV4 : AddressFamily.IPV6,
                resolved.getAddress());
        int mappedPort = container.getMappedPort(5432);
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
                DatabaseKind.POSTGRESQL,
                ConnectionPurpose.CONNECTION_TEST,
                new StructuredEndpoint(assessment.originalTlsHostname(), port, "probe_db"),
                new Deadline(Instant.now(CLOCK).plusSeconds(30)));
        return authority.validate(request);
    }

    private static GenericContainer<?> sslPostgres(TestCertificateAuthority.IssuedServerCertificate cert) {
        String dockerfile =
                """
                FROM postgres:17.6-alpine3.22@sha256:ef257d85f76e48da1c64832459b59fcaba1a4dac97bf5d7450c77753542eee94
                COPY server.crt /var/lib/postgresql/server.crt
                COPY server.key /var/lib/postgresql/server.key
                RUN chown postgres:postgres /var/lib/postgresql/server.crt /var/lib/postgresql/server.key \\
                    && chmod 644 /var/lib/postgresql/server.crt \\
                    && chmod 600 /var/lib/postgresql/server.key
                """;
        var image = new ImageFromDockerfile()
                .withFileFromString("Dockerfile", dockerfile)
                .withFileFromString("server.crt", cert.certificatePem())
                .withFileFromString("server.key", cert.privateKeyPem());
        return new GenericContainer<>(image)
                .withEnv("POSTGRES_USER", DB_USERNAME)
                .withEnv("POSTGRES_PASSWORD", DB_PASSWORD)
                // No explicit POSTGRES_DB: the official image then defaults the bootstrap
                // database's name to POSTGRES_USER, matching pgjdbc's own default (a probe
                // target's URL has no path segment, so pgjdbc defaults dbname to the "user"
                // property) - see PostgresqlConnectorDialect#buildProbeTarget.
                .withExposedPorts(5432)
                .withCommand(
                        "postgres",
                        "-c", "ssl=on",
                        "-c", "ssl_cert_file=/var/lib/postgresql/server.crt",
                        "-c", "ssl_key_file=/var/lib/postgresql/server.key")
                .waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
                .withStartupTimeout(Duration.ofSeconds(120));
    }
}
